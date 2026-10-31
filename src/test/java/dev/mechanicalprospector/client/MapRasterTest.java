package dev.mechanicalprospector.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapRasterTest {
    @Test void clipsEdgesAndRejectsEmptyRectanglesWithoutChangingNeighbors() {
        var image = new MapRaster(4); image.clear(0xff112233);
        image.fill(-2, -1, 2, 2, 0xffaabbcc);
        assertArrayEquals(new int[]{0xffaabbcc,0xffaabbcc,0xff112233,0xff112233,
                0xffaabbcc,0xffaabbcc,0xff112233,0xff112233,
                0xff112233,0xff112233,0xff112233,0xff112233,
                0xff112233,0xff112233,0xff112233,0xff112233}, image.pixels());
        var before = image.pixels().clone();
        image.fill(5, 5, 8, 8, 0); image.fill(3, 3, 1, 1, 0);
        assertArrayEquals(before, image.pixels());
        assertThrows(IllegalArgumentException.class, () -> new MapRaster(0));
    }
    @Test void preservesAlphaAndSwapsNativeImageColorChannels() {
        assertEquals(0x80402010, MapRaster.abgr(0x80102040));
        assertEquals(0xfff04f51, MapRaster.abgr(MapRaster.abgr(0xfff04f51)));
    }
}
