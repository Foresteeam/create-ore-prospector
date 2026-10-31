package dev.mechanicalprospector.client;

import java.util.Arrays;

/** CPU-side map pixels; clips rectangles before writing and allocates once per UI size. */
public final class MapRaster {
    private final int size;
    private final int[] pixels;
    public MapRaster(int size) {
        if (size < 1 || size > 2048) throw new IllegalArgumentException("Invalid raster size");
        this.size = size; pixels = new int[size * size];
    }
    public int size() { return size; }
    public int[] pixels() { return pixels; }
    public void clear(int color) { Arrays.fill(pixels, color); }
    public void fill(int left, int top, int right, int bottom, int color) {
        left = Math.max(0, left); top = Math.max(0, top);
        right = Math.min(size, right); bottom = Math.min(size, bottom);
        if (right <= left || bottom <= top) return;
        for (int y = top; y < bottom; y++) Arrays.fill(pixels, y * size + left, y * size + right, color);
    }
    public static int abgr(int argb) {
        return (argb & 0xff00ff00) | ((argb >>> 16) & 255) | ((argb & 255) << 16);
    }
}
