package dev.mechanicalprospector.scan;

import com.google.gson.JsonObject;
import com.tom.createores.OreData;
import dev.mechanicalprospector.cache.RegionDiskCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.Optional;

/** Persist raw COE data, so names, icons and finite-amount settings remain server authoritative. */
public record VeinSnapshot(String recipeId, float randomMultiplier, long extracted, byte source) {
    public VeinSnapshot {
        if (source < ScanCell.LIVE || source > ScanCell.SURVEY || !Float.isFinite(randomMultiplier) || extracted < 0)
            throw new IllegalArgumentException("Invalid vein snapshot");
        if (!recipeId.isEmpty()) ResourceLocation.parse(recipeId);
    }
    public static VeinSnapshot from(OreData data, byte source) {
        var saved = data.save();
        return new VeinSnapshot(saved.recipe().map(ResourceLocation::toString).orElse(""), saved.random(), saved.extracted(), source);
    }
    public OreData toOreData() {
        return new OreData(new OreData.Serialized(true, recipeId.isEmpty() ? Optional.empty() : Optional.of(ResourceLocation.parse(recipeId)), extracted, randomMultiplier));
    }
    public ScanCell toCell(ServerLevel level, ChunkPos pos, boolean cached) {
        return VeinSurvey.fromData(level, pos, toOreData(), source).withCached(cached);
    }
    public static final RegionDiskCache.EntryCodec<VeinSnapshot> CODEC = new RegionDiskCache.EntryCodec<>() {
        @Override public JsonObject encode(VeinSnapshot value) {
            var json = new JsonObject(); json.addProperty("recipe", value.recipeId); json.addProperty("random", value.randomMultiplier);
            json.addProperty("extracted", value.extracted); json.addProperty("source", value.source); return json;
        }
        @Override public VeinSnapshot decode(JsonObject json) {
            return new VeinSnapshot(json.get("recipe").getAsString(), json.get("random").getAsFloat(),
                    json.get("extracted").getAsLong(), json.get("source").getAsByte());
        }
    };
}
