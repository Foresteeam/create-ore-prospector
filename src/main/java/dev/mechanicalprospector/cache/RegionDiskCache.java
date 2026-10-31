package dev.mechanicalprospector.cache;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mechanicalprospector.MechanicalProspector;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A bounded memory cache backed by atomic, compressed 32x32-chunk region files.
 * All filesystem operations and LRU mutations are serialized on one I/O thread.
 * Immutable entries may be read from resident regions without blocking a game thread. */
public final class RegionDiskCache<T> implements AutoCloseable {
    public interface EntryCodec<T> {
        JsonObject encode(T value);
        T decode(JsonObject value);
    }
    public record Position(int x, int z) {}
    private record RegionKey(int x, int z) {}
    private static final Gson GSON = new Gson();
    private static final int FORMAT = 1;
    private final Path root;
    private final EntryCodec<T> codec;
    private final int maxRegions;
    private final ExecutorService io;
    private final LinkedHashMap<RegionKey, Region<T>> lru = new LinkedHashMap<>(16, .75f, true);
    private final ConcurrentHashMap<RegionKey, Region<T>> resident = new ConcurrentHashMap<>();
    private final AtomicLong diskReads = new AtomicLong();
    private volatile boolean closed;

    public RegionDiskCache(Path root, EntryCodec<T> codec, int maxRegions, String threadName) {
        this.root = root.toAbsolutePath().normalize(); this.codec = codec; this.maxRegions = Math.max(1, maxRegions);
        this.io = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r, threadName); thread.setDaemon(true); return thread; });
    }
    public Path root() { return root; }
    public long diskReads() { return diskReads.get(); }
    private static RegionKey region(int x, int z) { return new RegionKey(Math.floorDiv(x, 32), Math.floorDiv(z, 32)); }
    private static int index(int x, int z) { return Math.floorMod(x, 32) + Math.floorMod(z, 32) * 32; }
    public T getIfPresent(int x, int z) {
        var loaded = resident.get(region(x, z));
        return loaded == null ? null : loaded.entries.get(index(x, z));
    }
    public CompletableFuture<Optional<T>> getAsync(int x, int z) {
        T value = getIfPresent(x, z);
        if (value != null) return CompletableFuture.completedFuture(Optional.of(value));
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Cache closed"));
        return CompletableFuture.supplyAsync(() -> Optional.ofNullable(load(region(x, z)).entries.get(index(x, z))), io);
    }
    public CompletableFuture<Map<Position, T>> getAllAsync(List<Position> positions) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Cache closed"));
        return CompletableFuture.supplyAsync(() -> {
            var found = new LinkedHashMap<Position, T>();
            for (var pos : positions) {
                T value = load(region(pos.x, pos.z)).entries.get(index(pos.x, pos.z));
                if (value != null) found.put(pos, value);
            }
            return Map.copyOf(found);
        }, io);
    }
    public CompletableFuture<Void> putAsync(int x, int z, T value) {
        return putAllAsync(Map.of(new Position(x, z), value));
    }
    public CompletableFuture<Void> putAllAsync(Map<Position, T> values) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Cache closed"));
        var copy = Map.copyOf(values);
        return CompletableFuture.runAsync(() -> {
            for (var entry : copy.entrySet()) {
                var pos = entry.getKey(); var r = load(region(pos.x, pos.z));
                T previous = r.entries.put(index(pos.x, pos.z), entry.getValue());
                if (!entry.getValue().equals(previous)) r.dirty = true;
            }
        }, io);
    }
    public CompletableFuture<Void> flushAsync() {
        if (closed) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> { for (var r : lru.values()) save(r); }, io);
    }
    private Path file(RegionKey key) { return root.resolve("r." + key.x + "." + key.z + ".json.gz"); }
    private Region<T> load(RegionKey key) {
        var present = lru.get(key);
        if (present != null) return present;
        var result = new Region<T>(key);
        Path target = file(key);
        try {
            Files.createDirectories(root);
            if (Files.isRegularFile(target)) {
                diskReads.incrementAndGet();
                try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(target)), StandardCharsets.UTF_8)) {
                    var json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (json.get("format").getAsInt() != FORMAT || json.get("region_x").getAsInt() != key.x
                            || json.get("region_z").getAsInt() != key.z) throw new IllegalArgumentException("Wrong region header");
                    var entries = json.getAsJsonObject("entries");
                    if (entries.size() > 1024) throw new IllegalArgumentException("Too many region entries");
                    for (var e : entries.entrySet()) {
                        int slot = Integer.parseInt(e.getKey());
                        if (slot < 0 || slot >= 1024) throw new IllegalArgumentException("Invalid chunk slot");
                        result.entries.put(slot, codec.decode(e.getValue().getAsJsonObject()));
                    }
                }
            }
        } catch (Exception error) {
            result.entries.clear();
            MechanicalProspector.LOGGER.warn("Ignoring unreadable ore cache {} (world data will be queried instead)", target, error);
            // Retain the damaged bytes for inspection before replacing a region with newly resolved data.
            try { if (Files.exists(target)) Files.move(target, target.resolveSibling(target.getFileName() + ".corrupt-" + System.currentTimeMillis())); }
            catch (Exception ignored) { }
        }
        lru.put(key, result); resident.put(key, result);
        while (lru.size() > maxRegions) {
            var oldest = lru.entrySet().iterator().next();
            if (!save(oldest.getValue())) break; // Retain dirty entries when a disk write fails.
            lru.remove(oldest.getKey()); resident.remove(oldest.getKey(), oldest.getValue());
        }
        return result;
    }
    private boolean save(Region<T> region) {
        if (!region.dirty) return true;
        Path target = file(region.key), temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(root);
            var json = new JsonObject(); json.addProperty("format", FORMAT);
            json.addProperty("region_x", region.key.x); json.addProperty("region_z", region.key.z);
            var entries = new JsonObject();
            region.entries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> entries.add(Integer.toString(e.getKey()), codec.encode(e.getValue())));
            json.add("entries", entries);
            try (var writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            region.dirty = false; return true;
        } catch (Exception error) {
            MechanicalProspector.LOGGER.error("Could not save ore cache {}", target, error); return false;
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        try { flushAsync().join(); }
        finally { closed = true; io.shutdown(); resident.clear(); }
    }
    private static final class Region<T> {
        final RegionKey key;
        final ConcurrentHashMap<Integer, T> entries = new ConcurrentHashMap<>();
        boolean dirty;
        Region(RegionKey key) { this.key = key; }
    }
}
