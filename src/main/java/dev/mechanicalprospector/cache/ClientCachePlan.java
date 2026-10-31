package dev.mechanicalprospector.cache;

import dev.mechanicalprospector.scan.MapMath;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/** Shared, testable row/bit ordering for local hits and missing-only network requests. */
public final class ClientCachePlan {
    private ClientCachePlan() {}
    public static List<RegionDiskCache.Position> positions(int x, int z, int radius) {
        var result = new ArrayList<RegionDiskCache.Position>(MapMath.total(radius));
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++)
            result.add(new RegionDiskCache.Position(x + dx, z + dz));
        return List.copyOf(result);
    }
    public static BitSet missing(int x, int z, int radius, Map<Long, ?> known) {
        var missing = new BitSet(MapMath.total(radius)); int bit = 0;
        for (var pos : positions(x, z, radius)) {
            if (!known.containsKey(MapMath.key(pos.x(), pos.z()))) missing.set(bit);
            bit++;
        }
        return missing;
    }
}
