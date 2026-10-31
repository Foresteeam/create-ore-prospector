package dev.mechanicalprospector.scan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapMathTest {
    @Test void negativeCoordinatesUseFloorInsteadOfTruncation() {
        assertEquals(-1, MapMath.chunkAt(-0.01, 0, 8));
        assertEquals(-1, MapMath.chunkAt(-16, 0, 8));
        assertEquals(-2, MapMath.chunkAt(-16.01, 0, 8));
    }
    @Test void playerRemainsAtExactCenterInsideChunk() {
        for (double player : new double[]{-32.1, -0.1, 0, 5.5, 15.99, 432.25}) {
            int chunk = MapMath.chunkAt(player, 0, 7.75);
            double edge = MapMath.chunkEdge(chunk, player, 100, 7.75);
            assertTrue(edge <= 100 && edge + 7.75 > 100, "center is inside actual player chunk");
        }
    }
    @Test void hoveringTileInteriorsRoundTripsInEveryQuadrant() {
        for (double player : new double[]{-471.75, -0.001, 0, 18.5, 633.15})
            for (int chunk = -32; chunk <= 32; chunk++) {
                double centerOfTile = MapMath.chunkEdge(chunk, player, 211.5, 3.25) + 3.25 / 2;
                assertEquals(chunk, MapMath.chunkAt(player, centerOfTile - 211.5, 3.25));
            }
    }
    @Test void rangesAreBoundedAndIncludeCenter() {
        assertEquals(289, MapMath.total(8)); assertEquals(1089, MapMath.total(16)); assertEquals(4225, MapMath.total(32));
        assertEquals(16641, MapMath.total(64));
        assertEquals(8, MapMath.clampRadius(-1)); assertEquals(16, MapMath.clampRadius(12)); assertEquals(64, MapMath.clampRadius(255));
    }
    @Test void packedCoordinatesPreserveSignAndDoNotCollide() {
        assertNotEquals(MapMath.key(-1, 0), MapMath.key(0, -1));
        assertEquals(net.minecraft.world.level.ChunkPos.asLong(-513, 772), MapMath.key(-513, 772));
    }
}
