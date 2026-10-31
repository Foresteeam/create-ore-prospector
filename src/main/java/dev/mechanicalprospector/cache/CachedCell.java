package dev.mechanicalprospector.cache;

import com.google.gson.JsonObject;
import com.tom.createores.recipe.VeinRecipe;
import dev.mechanicalprospector.scan.ScanCell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;

/** Compact client snapshot. Names/icons are resolved from synchronized recipes, never persisted as registry IDs. */
public record CachedCell(byte kind, byte source, String veinId, long remaining) {
    public CachedCell {
        if (kind < ScanCell.EMPTY || kind > ScanCell.DEPLETED || source < ScanCell.LIVE || source > ScanCell.SURVEY)
            throw new IllegalArgumentException("Invalid cached cell");
        if (kind != ScanCell.EMPTY) ResourceLocation.parse(veinId);
    }
    public static CachedCell from(ScanCell cell) { return new CachedCell(cell.kind(), cell.source(), cell.veinId(), cell.remaining()); }
    public ScanCell resolve(int x, int z, RecipeManager recipes) {
        if (kind == ScanCell.EMPTY) return ScanCell.empty(x, z, source).withCached(true);
        var holder = recipes.byKey(ResourceLocation.parse(veinId)).orElse(null);
        if (holder == null || !(holder.value() instanceof VeinRecipe vein)) return null;
        return new ScanCell(x, z, kind, source, veinId, vein.getName().copy(), vein.getIcon().copyWithCount(1), remaining, true);
    }
    public static final RegionDiskCache.EntryCodec<CachedCell> CODEC = new RegionDiskCache.EntryCodec<>() {
        @Override public JsonObject encode(CachedCell value) {
            var json = new JsonObject(); json.addProperty("kind", value.kind); json.addProperty("source", value.source);
            json.addProperty("vein", value.veinId); json.addProperty("remaining", value.remaining); return json;
        }
        @Override public CachedCell decode(JsonObject json) {
            return new CachedCell(json.get("kind").getAsByte(), json.get("source").getAsByte(),
                    json.get("vein").getAsString(), json.get("remaining").getAsLong());
        }
    };
}
