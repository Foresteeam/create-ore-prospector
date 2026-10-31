package dev.mechanicalprospector.scan;

import com.mojang.serialization.JsonOps;
import com.tom.createores.Config;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.recipe.VeinRecipe;
import dev.mechanicalprospector.MechanicalProspector;
import dev.mechanicalprospector.ProspectorConfig;
import dev.mechanicalprospector.cache.RegionDiskCache;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.function.Supplier;

/** Shared by all players. CPU prediction and cache I/O are bounded background jobs; world access stays on the server thread. */
public final class ServerSurveyCache implements AutoCloseable {
    private static final Map<MinecraftServer, ServerSurveyCache> SERVERS = new HashMap<>();
    public record Dimension(UUID id, ServerLevel level, List<RecipeHolder<VeinRecipe>> recipes,
                            RegionDiskCache<VeinSnapshot> store, PredictionModel model) {}
    public record Stats(long cacheHits, long predictions, long worldReads) {}
    public record ParallelStats(int threads, int activeBatches, int queuedBatches, int peakActiveBatches,
                                long completedJobs, long backgroundPredictions, long mainThreadFallbacks) {}
    private record WorkKey(UUID context, long chunk, boolean refresh) {}
    private final MinecraftServer server;
    private final UUID worldId;
    private final UUID serverId;
    private final Path root;
    private final Map<ResourceKey<Level>, Dimension> dimensions = new HashMap<>();
    private final LinkedHashMap<WorkKey, Work> pending = new LinkedHashMap<>();
    private final PredictionExecutor workers;
    private final int maxInFlight;
    private long cacheHits, predictions, worldReads;
    private long backgroundPredictions, mainThreadFallbacks;

    private ServerSurveyCache(MinecraftServer server) {
        this.server = server; worldId = WorldCacheIdentity.get(server);
        root = server.getServerDirectory().toAbsolutePath().normalize().resolve("orechunk");
        try { java.nio.file.Files.createDirectories(root); }
        catch (Exception error) { throw new IllegalStateException("Cannot create server orechunk directory", error); }
        Path identityFile = root.resolve("server-id.txt");
        try {
            if (!java.nio.file.Files.exists(identityFile)) java.nio.file.Files.writeString(identityFile, UUID.randomUUID().toString(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            serverId = UUID.fromString(java.nio.file.Files.readString(identityFile, StandardCharsets.UTF_8).strip());
        } catch (Exception error) { throw new IllegalStateException("Cannot read server ore cache identity", error); }
        int threads = ProspectorConfig.predictionThreads();
        workers = new PredictionExecutor(threads, Math.max(4, threads * 2));
        maxInFlight = Math.clamp(threads * 64, 512, 2048);
    }
    public static ServerSurveyCache get(MinecraftServer server) { return SERVERS.computeIfAbsent(server, ServerSurveyCache::new); }
    public static void closeFor(MinecraftServer server) {
        var old = SERVERS.remove(server); if (old != null) old.close();
    }
    public static void flushFor(MinecraftServer server) { var cache = SERVERS.get(server); if (cache != null) cache.flush(); }
    public static void tickAll(long deadline, int budget) {
        for (var cache : SERVERS.values()) {
            budget -= cache.pump(deadline, budget);
            if (budget <= 0 || System.nanoTime() >= deadline) break;
        }
    }
    public Stats stats() { return new Stats(cacheHits, predictions, worldReads); }
    public ParallelStats parallelStats() { return new ParallelStats(workers.threads(), workers.activeBatches(), workers.queuedBatches(),
            workers.peakActiveBatches(), workers.completedJobs(), backgroundPredictions, mainThreadFallbacks); }
    public boolean canAcceptMore() { return pending.size() < maxInFlight; }
    public int perPlayerInFlight() {
        // Cover cache read -> worker -> cache write latency without starving a fast worker pool.
        return Math.clamp(ProspectorConfig.serverRate() / 5, 128, Math.min(maxInFlight, 1024));
    }
    public Path root() { return root; }
    public Dimension dimension(ServerLevel level) {
        return dimensions.computeIfAbsent(level.dimension(), ignored -> {
            var recipes = VeinSurvey.recipes(level);
            String fingerprint = fingerprint(level, recipes);
            String dimension = level.dimension().location().toString();
            UUID id = UUID.nameUUIDFromBytes((serverId + ":" + worldId + ":" + dimension + ":" + fingerprint).getBytes(StandardCharsets.UTF_8));
            String folder = dimension.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + hash(dimension).substring(0, 8);
            Path location = root.resolve(worldId.toString()).resolve(fingerprint).resolve(folder);
            var model = PredictionModel.capture(level, recipes);
            MechanicalProspector.LOGGER.info("Ore prospector dimension {}: {} prediction threads, {} biome evaluation", dimension,
                    workers.threads(), model.fullyParallel() ? "background" : "compatible main-thread fallback when required");
            return new Dimension(id, level, recipes, new RegionDiskCache<>(location, VeinSnapshot.CODEC, 128, "prospector-server-cache"), model);
        });
    }
    private static String fingerprint(ServerLevel level, List<RecipeHolder<VeinRecipe>> recipes) {
        var text = new StringBuilder("coe-1.6.8-survey-v2|").append(level.getSeed()).append('|')
                .append(Config.finiteAmountBase).append('|').append(Config.defaultInfinite);
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (var recipe : recipes) text.append('|').append(recipe.id()).append('=')
                .append(VeinRecipe.Serializer.CODEC.codec().encodeStart(ops, recipe.value()).getOrThrow());
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        biomes.holders().sorted(java.util.Comparator.comparing(h -> h.key().location())).forEach(h -> {
            text.append('|').append(h.key().location());
            h.tags().map(tag -> tag.location().toString()).sorted().forEach(tag -> text.append('#').append(tag));
        });
        return hash(text.toString());
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public CompletableFuture<ScanCell> resolve(Dimension context, ChunkPos pos, boolean refresh) {
        if (!context.level.getWorldBorder().isWithinBounds(pos)) return CompletableFuture.completedFuture(ScanCell.unavailable(pos.x, pos.z));
        var resident = context.store.getIfPresent(pos.x, pos.z);
        if (!refresh && resident != null) {
            cacheHits++; return CompletableFuture.completedFuture(resident.toCell(context.level, pos, true));
        }
        var key = new WorkKey(context.id, pos.toLong(), refresh);
        return pending.computeIfAbsent(key, ignored -> new Work(context, pos, refresh)).result;
    }

    public int pump(long deadline, int budget) {
        int spent = 0;
        var ready = new ArrayList<Work>(32);
        var iterator = pending.values().iterator();
        while (iterator.hasNext() && spent < budget && System.nanoTime() < deadline) {
            var work = iterator.next();
            try {
                if (!work.lookup.isDone()) continue;
                if (!work.lookedUp) {
                    work.cached = work.lookup.join().orElse(null); work.lookedUp = true;
                    if (work.cached != null && !work.refresh) {
                        cacheHits++; work.result.complete(work.cached.toCell(work.context.level, work.pos, true)); iterator.remove(); spent++; continue;
                    }
                    if (work.context.level.getChunkSource().getChunkNow(work.pos.x, work.pos.z) != null) {
                        work.snapshot = VeinSnapshot.from(OreDataAttachment.getData(work.context.level.getChunkSource().getChunkNow(work.pos.x, work.pos.z)), ScanCell.LIVE);
                    } else {
                        work.saved = work.context.level.getChunkSource().chunkMap.read(work.pos); worldReads++;
                    }
                    spent++;
                }
                if (work.snapshot == null) {
                    if (work.saved == null || !work.saved.isDone()) continue;
                    var live = work.context.level.getChunkSource().getChunkNow(work.pos.x, work.pos.z);
                    if (live != null) work.snapshot = VeinSnapshot.from(OreDataAttachment.getData(live), ScanCell.LIVE);
                    else if (work.computation == null) {
                        if (!work.inputPrepared) {
                            work.input = SavedChunkSurvey.predictionInput(work.saved.join().orElse(null)); work.inputPrepared = true;
                        }
                        ready.add(work);
                        if (ready.size() == 32) submitReady(ready);
                        spent++; continue;
                    } else {
                        if (!work.computation.isDone()) continue;
                        var result = work.computation.join(work.batchIndex);
                        if (result.snapshot() == null) {
                            work.snapshot = VeinSurvey.predictSnapshot(work.context.level, work.pos, work.input, work.context.recipes);
                            predictions++; mainThreadFallbacks++;
                        } else {
                            work.snapshot = result.snapshot(); work.reusedCached = result.reusedCached();
                            if (result.predicted()) { predictions++; backgroundPredictions++; }
                            if (result.reusedCached()) cacheHits++;
                        }
                    }
                    spent++;
                }
                if (work.write == null) work.write = work.context.store.putAsync(work.pos.x, work.pos.z, work.snapshot);
                if (!work.write.isDone()) continue;
                work.write.join();
                work.result.complete(work.snapshot.toCell(work.context.level, work.pos, work.reusedCached)); iterator.remove(); spent++;
            } catch (Exception error) {
                MechanicalProspector.LOGGER.warn("Could not resolve ore cache for {}", work.pos, error);
                work.result.complete(work.cached == null ? ScanCell.unavailable(work.pos.x, work.pos.z)
                        : work.cached.toCell(work.context.level, work.pos, true));
                iterator.remove(); spent++;
            }
        }
        submitReady(ready);
        if (pending.size() > 1) {
            var first = pending.entrySet().iterator().next(); pending.remove(first.getKey()); pending.put(first.getKey(), first.getValue());
        }
        return spent;
    }
    private void submitReady(List<Work> ready) {
        if (ready.isEmpty()) return;
        var jobs = new ArrayList<Supplier<PredictionModel.Resolution>>(ready.size());
        for (var work : ready) {
            var model = work.context.model; var pos = work.pos; var input = work.input; var cached = work.cached;
            jobs.add(() -> model.resolve(pos, input, cached));
        }
        var results = workers.trySubmitBatch(jobs);
        if (results != null) for (int i = 0; i < ready.size(); i++) { var work = ready.get(i); work.computation = results; work.batchIndex = i; }
        ready.clear();
    }
    public void flush() { for (var dimension : dimensions.values()) dimension.store.flushAsync(); }
    @Override public void close() {
        for (var work : pending.values()) work.result.completeExceptionally(new IllegalStateException("Server cache closed"));
        pending.clear(); workers.close(); for (var dimension : dimensions.values()) dimension.store.close(); dimensions.clear();
    }
    private static final class Work {
        final Dimension context; final ChunkPos pos; final boolean refresh;
        final CompletableFuture<Optional<VeinSnapshot>> lookup;
        final CompletableFuture<ScanCell> result = new CompletableFuture<>();
        CompletableFuture<Optional<CompoundTag>> saved;
        CompletableFuture<Void> write;
        PredictionExecutor.Batch<PredictionModel.Resolution> computation;
        int batchIndex;
        CompoundTag input;
        boolean lookedUp, reusedCached, inputPrepared;
        VeinSnapshot cached, snapshot;
        Work(Dimension context, ChunkPos pos, boolean refresh) {
            this.context = context; this.pos = pos; this.refresh = refresh;
            this.lookup = context.store.getAsync(pos.x, pos.z);
        }
    }
}
