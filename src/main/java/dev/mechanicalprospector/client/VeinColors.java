package dev.mechanicalprospector.client;

import dev.mechanicalprospector.scan.ScanCell;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public final class VeinColors {
    private static final Map<String, Integer> CACHE = new HashMap<>();
    private VeinColors() {}
    public static int color(ScanCell cell) {
        return CACHE.computeIfAbsent(cell.veinId(), ignored -> choose(cell));
    }
    private static int choose(ScanCell cell) {
        String id = BuiltInRegistries.ITEM.getKey(cell.icon().getItem()).getPath();
        // Raw ore / gem hues. This makes familiar veins immediately recognizable in the legend.
        if (id.contains("copper")) return 0xffdb885d;
        if (id.contains("iron")) return 0xffd5b29c;
        if (id.contains("gold")) return 0xffedc64c;
        if (id.contains("zinc")) return 0xffb5c4a2;
        if (id.contains("netherite") || id.contains("ancient_debris")) return 0xff94776b;
        if (id.contains("diamond")) return 0xff62dfda;
        if (id.contains("emerald")) return 0xff48d786;
        if (id.contains("redstone")) return 0xffe25759;
        if (id.contains("lapis")) return 0xff537fd9;
        if (id.contains("coal")) return 0xff727984;
        if (id.contains("quartz")) return 0xffe9d8c9;
        if (id.contains("glowstone")) return 0xffe6b65b;
        if (id.contains("water")) return 0xff519de0;
        if (id.contains("lava")) return 0xffee8735;
        // Datapack-added veins use the actual icon texture, with a deterministic fallback.
        try {
            var mc = Minecraft.getInstance();
            var sprite = mc.getItemRenderer().getModel(cell.icon(), mc.level, null, 0).getParticleIcon();
            int width = sprite.contents().width(), height = sprite.contents().height();
            double red = 0, green = 0, blue = 0, weight = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int pixel = sprite.getPixelRGBA(0, x, y);
                if ((pixel >>> 24) < 160) continue;
                int r = pixel & 255, g = (pixel >>> 8) & 255, b = (pixel >>> 16) & 255;
                int high = Math.max(r, Math.max(g, b)), low = Math.min(r, Math.min(g, b));
                if (high < 45) continue;
                double w = 0.2 + (high - low) / 100.0;
                red += r * w; green += g * w; blue += b * w; weight += w;
            }
            if (weight > 0) return 0xff000000 | ((int) (red / weight) << 16) | ((int) (green / weight) << 8) | (int) (blue / weight);
        } catch (RuntimeException ignored) { /* A missing resource should never prevent opening the map. */ }
        return 0xff000000 | (Color.HSBtoRGB((cell.veinId().hashCode() & 0xffff) / 65536f, 0.48f, 0.86f) & 0xffffff);
    }
    public static int mix(int a, int b, double factor) {
        int r = (int) (((a >> 16) & 255) * factor + ((b >> 16) & 255) * (1 - factor));
        int g = (int) (((a >> 8) & 255) * factor + ((b >> 8) & 255) * (1 - factor));
        int blue = (int) ((a & 255) * factor + (b & 255) * (1 - factor));
        return 0xff000000 | (r << 16) | (g << 8) | blue;
    }
}
