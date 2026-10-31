package dev.mechanicalprospector.cache;

import dev.mechanicalprospector.scan.ScanCell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RegionDiskCacheTest {
    @TempDir Path temporary;
    private static CachedCell empty() { return new CachedCell(ScanCell.EMPTY, ScanCell.SURVEY, "", 0); }
    private static CachedCell finite(long amount) { return new CachedCell(ScanCell.FINITE, ScanCell.SAVED, "createoreexcavation:iron", amount); }
    @Test void survivesCloseAndReopenIncludingEmptyNegativeChunksAndLargeLongs() {
        var folder = temporary.resolve("cache");
        try (var cache = new RegionDiskCache<>(folder, CachedCell.CODEC, 2, "cache-test")) {
            cache.putAsync(-1, -33, empty()).join(); cache.putAsync(32, 0, finite(Long.MAX_VALUE)).join();
            cache.flushAsync().join();
            assertEquals(0, cache.diskReads());
            assertTrue(Files.exists(folder.resolve("r.-1.-2.json.gz")));
        }
        try (var restored = new RegionDiskCache<>(folder, CachedCell.CODEC, 2, "cache-test")) {
            assertEquals(empty(), restored.getAsync(-1, -33).join().orElseThrow());
            assertEquals(Long.MAX_VALUE, restored.getAsync(32, 0).join().orElseThrow().remaining());
            assertTrue(restored.getAsync(-2, -33).join().isEmpty());
            long reads = restored.diskReads(); restored.getAsync(-1, -33).join();
            assertEquals(reads, restored.diskReads(), "Memory hits perform no further disk reads");
        }
    }
    @Test void lruEvictionWritesDirtyRegionsWithoutLosingUpdates() {
        try (var cache = new RegionDiskCache<>(temporary, CachedCell.CODEC, 1, "cache-test")) {
            cache.putAsync(0, 0, finite(91)).join(); cache.putAsync(32, 0, empty()).join();
            cache.putAsync(0, 0, finite(37)).join(); cache.putAsync(64, 0, empty()).join();
            assertEquals(37, cache.getAsync(0, 0).join().orElseThrow().remaining());
        }
        try (var cache = new RegionDiskCache<>(temporary, CachedCell.CODEC, 1, "cache-test")) {
            assertEquals(37, cache.getAsync(0, 0).join().orElseThrow().remaining());
            assertEquals(empty(), cache.getAsync(32, 0).join().orElseThrow());
        }
    }
    @Test void namespacesDoNotShareRecordsAndCorruptionBecomesAMiss() throws Exception {
        try (var one = new RegionDiskCache<>(temporary.resolve("world-one"), CachedCell.CODEC, 2, "cache-test");
             var two = new RegionDiskCache<>(temporary.resolve("world-two"), CachedCell.CODEC, 2, "cache-test")) {
            one.putAsync(5, -3, finite(38)).join(); assertTrue(two.getAsync(5, -3).join().isEmpty());
        }
        var bad = temporary.resolve("bad"); Files.createDirectories(bad); Files.writeString(bad.resolve("r.0.0.json.gz"), "not gzip");
        try (var cache = new RegionDiskCache<>(bad, CachedCell.CODEC, 2, "cache-test")) {
            assertTrue(cache.getAsync(0, 0).join().isEmpty());
            cache.putAsync(0, 0, empty()).join(); cache.flushAsync().join();
        }
        try (var cache = new RegionDiskCache<>(bad, CachedCell.CODEC, 2, "cache-test")) {
            assertEquals(empty(), cache.getAsync(0, 0).join().orElseThrow());
        }
        try (var files = Files.list(bad)) { assertTrue(files.anyMatch(p -> p.getFileName().toString().contains("corrupt-"))); }
    }
    @Test void concurrentSubmissionsAreSerializedIntoOneCompleteRegion() {
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        try (var cache = new RegionDiskCache<>(temporary, CachedCell.CODEC, 2, "cache-test")) {
            for (int i = 0; i < 1024; i++) { int value = i; futures.add(java.util.concurrent.CompletableFuture.runAsync(
                    () -> cache.putAsync(value % 32, value / 32, finite(value + 1L)).join())); }
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        }
        try (var cache = new RegionDiskCache<>(temporary, CachedCell.CODEC, 2, "cache-test")) {
            assertEquals(1024, cache.getAllAsync(ClientCachePlan.positions(16, 16, 32)).join().size());
        }
    }
    @Test void clientRequestsOnlyMissingCellsAndRetainsEmptyHits() {
        int radius = 8; var known = new HashMap<Long, CachedCell>();
        for (var position : ClientCachePlan.positions(-5, -3, radius))
            known.put(dev.mechanicalprospector.scan.MapMath.key(position.x(), position.z()), empty());
        assertTrue(ClientCachePlan.missing(-5, -3, radius, known).isEmpty());
        known.remove(dev.mechanicalprospector.scan.MapMath.key(-13, -11));
        var missing = ClientCachePlan.missing(-5, -3, radius, known);
        assertEquals(1, missing.cardinality()); assertTrue(missing.get(0));
        // Moving one chunk east requests one new column and reuses all overlapping cells.
        assertEquals(17, ClientCachePlan.missing(-4, -3, radius, known).cardinality());
    }
}
