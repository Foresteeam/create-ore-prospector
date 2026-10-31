package dev.mechanicalprospector.scan;

import com.tom.createores.OreData;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/** Reads an unloaded chunk without creating a chunk ticket or changing its save data. */
public final class SavedChunkSurvey {
    private SavedChunkSurvey() {}
    public static OreData oreData(CompoundTag chunk) {
        if (chunk == null) return null;
        var attachments = chunk.getCompound("neoforge:attachments");
        if (!attachments.contains("createoreexcavation:ore_vein", Tag.TAG_COMPOUND)) return null;
        return OreData.CODEC.parse(NbtOps.INSTANCE, attachments.getCompound("createoreexcavation:ore_vein"))
                .getOrThrow(error -> new IllegalStateException("Invalid saved ore data: " + error));
    }

    public static Holder<Biome> biome(ServerLevel level, CompoundTag chunk, int localX, int quartY, int localZ) {
        var id = biomeId(chunk, level.getMinBuildHeight(), level.getHeight(), localX, quartY, localZ);
        return id == null ? null : level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(id).orElse(null);
    }

    /** Parses a private NBT snapshot without touching a level, holder registry or chunk cache. */
    public static ResourceLocation biomeId(CompoundTag chunk, int minBuildHeight, int height, int localX, int quartY, int localZ) {
        if (chunk == null) return null;
        int minQuart = Math.floorDiv(minBuildHeight, 4);
        int maxQuart = minQuart + height / 4 - 1;
        int y = Math.clamp(quartY, minQuart, maxQuart);
        int sectionY = Math.floorDiv(y, 4);
        var sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.getCompound(i);
            if (section.getByte("Y") != sectionY || !section.contains("biomes", Tag.TAG_COMPOUND)) continue;
            var biomes = section.getCompound("biomes");
            var palette = biomes.getList("palette", Tag.TAG_STRING);
            if (palette.isEmpty()) return null;
            int index = palette.size() == 1 ? 0 : paletteIndex(biomes.getLongArray("data"), palette.size(),
                    ((y & 3) << 4) | ((localZ & 3) << 2) | (localX & 3));
            if (index < 0 || index >= palette.size()) return null;
            var id = ResourceLocation.tryParse(palette.getString(index));
            return id;
        }
        return null;
    }

    /** Copy only the fields needed by workers; the original chunk tag may still be owned by chunk I/O. */
    public static CompoundTag predictionInput(CompoundTag chunk) {
        if (chunk == null) return null;
        var result = new CompoundTag();
        var attachments = chunk.getCompound("neoforge:attachments");
        if (attachments.contains("createoreexcavation:ore_vein", Tag.TAG_COMPOUND)) {
            var copy = new CompoundTag(); copy.put("createoreexcavation:ore_vein", attachments.getCompound("createoreexcavation:ore_vein").copy());
            result.put("neoforge:attachments", copy);
        }
        var sections = chunk.getList("sections", Tag.TAG_COMPOUND); var copies = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.getCompound(i);
            if (!section.contains("biomes", Tag.TAG_COMPOUND)) continue;
            var copy = new CompoundTag(); copy.putByte("Y", section.getByte("Y")); copy.put("biomes", section.getCompound("biomes").copy()); copies.add(copy);
        }
        result.put("sections", copies); return result;
    }

    public static int paletteIndex(long[] data, int paletteSize, int index) {
        if (paletteSize <= 1) return 0;
        int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        int perLong = 64 / bits;
        int word = index / perLong;
        if (word >= data.length || word < 0) return -1;
        return (int) ((data[word] >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
    }
}
