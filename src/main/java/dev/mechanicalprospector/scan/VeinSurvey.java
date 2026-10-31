package dev.mechanicalprospector.scan;

import com.tom.createores.CreateOreExcavation;
import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.recipe.VeinRecipe;
import net.minecraft.core.QuartPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class VeinSurvey {
    private VeinSurvey() {}
    public static List<RecipeHolder<VeinRecipe>> recipes(ServerLevel level) {
        var recipes = new ArrayList<>(level.getRecipeManager().getAllRecipesFor(CreateOreExcavation.VEIN_RECIPES.getRecipeType()));
        recipes.sort(Comparator.<RecipeHolder<VeinRecipe>>comparingInt(r -> r.value().getNegGenerationPriority())
                .thenComparing(RecipeHolder::id));
        return List.copyOf(recipes);
    }

    public static ScanCell survey(ServerLevel level, ChunkPos pos, CompoundTag saved,
                                   List<RecipeHolder<VeinRecipe>> recipes) {
        return snapshot(level, pos, saved, recipes).toCell(level, pos, false);
    }

    public static VeinSnapshot snapshot(ServerLevel level, ChunkPos pos, CompoundTag saved,
                                        List<RecipeHolder<VeinRecipe>> recipes) {
        // A chunk can become loaded while its asynchronous disk read is in flight.
        var live = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (live != null) return VeinSnapshot.from(OreDataAttachment.getData(live), ScanCell.LIVE);
        var data = SavedChunkSurvey.oreData(saved);
        if (data != null && data.isLoaded()) return VeinSnapshot.from(data, ScanCell.SAVED);
        return predictSnapshot(level, pos, saved, recipes);
    }

    /**
     * Matches COE 1.6.8 RandomSpreadGenerator.pick(LevelChunk), including its vertical RNG bound,
     * recipe priority/tie order and the independent chunk seed used for resource amounts.
     * Adapted from tom5454/Create-Ore-Excavation under MIT; see THIRD_PARTY_NOTICES.txt.
     */
    public static ScanCell predict(ServerLevel level, ChunkPos pos, CompoundTag saved,
                                   List<RecipeHolder<VeinRecipe>> recipes) {
        return predictSnapshot(level, pos, saved, recipes).toCell(level, pos, false);
    }

    public static VeinSnapshot predictSnapshot(ServerLevel level, ChunkPos pos, CompoundTag saved,
                                                List<RecipeHolder<VeinRecipe>> recipes) {
        int minY = QuartPos.fromBlock(level.getMinBuildHeight());
        int maxY = minY + QuartPos.fromBlock(level.getHeight()) - 1;
        boolean fromSave = saved != null;
        for (var holder : recipes) {
            var recipe = holder.value();
            var candidate = recipe.getPlacement().getPotentialStructureChunk(level.getSeed(), pos.x, pos.z);
            if (!candidate.equals(pos)) continue;
            var rng = new WorldgenRandom(new LegacyRandomSource(0));
            rng.setLargeFeatureSeed(level.getSeed(), pos.x, pos.z);
            int x = rng.nextInt(4), y = minY + rng.nextInt(maxY), z = rng.nextInt(4);
            var biome = SavedChunkSurvey.biome(level, saved, x, y, z);
            if (biome == null) {
                fromSave = false;
                // Sample the biome source explicitly; never ask the chunk cache to generate terrain.
                biome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(
                        QuartPos.fromSection(pos.x) + x, y, QuartPos.fromSection(pos.z) + z,
                        level.getChunkSource().randomState().sampler());
            }
            if (recipe.canGenerate(level, biome)) {
                var predicted = new OreData();
                predicted.setRecipe(holder.id());
                predicted.setRandomMul(RandomSource.create(level.getSeed() ^ pos.toLong()).nextFloat());
                return VeinSnapshot.from(predicted, fromSave ? ScanCell.SAVED : ScanCell.SURVEY);
            }
        }
        return new VeinSnapshot("", 0, 0, fromSave ? ScanCell.SAVED : ScanCell.SURVEY);
    }

    public static ScanCell fromData(ServerLevel level, ChunkPos pos, OreData data, byte source) {
        var holder = data.getRecipe(level.getRecipeManager());
        if (holder == null) return data.getRecipeId() == null ? ScanCell.empty(pos.x, pos.z, source) : ScanCell.unavailable(pos.x, pos.z);
        var recipe = holder.value();
        long remaining = data.getResourcesRemaining(recipe);
        return new ScanCell(pos.x, pos.z, ScanCell.kindFor(remaining), source, holder.id().toString(),
                recipe.getName().copy(), recipe.getIcon().copyWithCount(1), remaining);
    }
}
