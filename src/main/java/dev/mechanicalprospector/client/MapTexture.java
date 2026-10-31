package dev.mechanicalprospector.client;

import dev.mechanicalprospector.MechanicalProspector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** One cached textured quad instead of a GUI fill/flush for every visible chunk. */
final class MapTexture implements AutoCloseable {
    private static int sequence;
    private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MechanicalProspector.MOD_ID, "survey_map_" + sequence++);
    private final DynamicTexture texture;
    final MapRaster raster;
    MapTexture(int size) {
        raster = new MapRaster(size); texture = new DynamicTexture(size, size, false);
        texture.setFilter(false, false);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }
    void upload() {
        var image = texture.getPixels(); var pixels = raster.pixels(); int size = raster.size();
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setPixelRGBA(x, y, MapRaster.abgr(pixels[y * size + x]));
        texture.upload();
    }
    void draw(GuiGraphics graphics, int x, int y) {
        int size = raster.size(); graphics.blit(id, x, y, 0, 0, size, size, size, size);
    }
    @Override public void close() { Minecraft.getInstance().getTextureManager().release(id); }
}
