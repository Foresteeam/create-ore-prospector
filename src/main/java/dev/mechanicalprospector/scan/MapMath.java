package dev.mechanicalprospector.scan;

public final class MapMath {
    private MapMath() {}
    public static final int[] RADII = {8, 16, 32, 64};
    public static int clampRadius(int value) {
        if (value <= 8) return 8;
        if (value <= 16) return 16;
        if (value <= 32) return 32;
        return 64;
    }
    public static int chunkAt(double playerBlock, double screenOffset, double cellSize) {
        return (int) Math.floor(playerBlock / 16.0 + screenOffset / cellSize);
    }
    public static double chunkEdge(int chunk, double playerBlock, double center, double cellSize) {
        return center + (chunk - playerBlock / 16.0) * cellSize;
    }
    public static int total(int radius) { return (radius * 2 + 1) * (radius * 2 + 1); }
    public static long key(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
}
