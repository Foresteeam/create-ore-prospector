package dev.mechanicalprospector.scan;

import com.mojang.serialization.JsonOps;
import com.tom.createores.recipe.VeinRecipe;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A per-dimension generation snapshot. Workers never access ServerLevel or mutable recipe/registry state. */
public final class PredictionModel {
    public record Resolution(VeinSnapshot snapshot, boolean predicted, boolean reusedCached) {
        // A null snapshot asks the main thread to run an unsupported/custom biome or recipe callback.
        static Resolution deferred() { return new Resolution(null, false, false); }
    }
    private record Rule(String id, RandomSpreadStructurePlacement placement, Set<ResourceLocation> allowedBiomes) {}
    @FunctionalInterface private interface BiomeLookup { ResourceLocation sample(int x, int y, int z); }
    private final long seed;
    private final int minHeight, height, minQuart, rngYBound;
    private final List<Rule> rules;
    private final Set<ResourceLocation> knownBiomes;
    private final BiomeLookup biomeLookup;
    private final boolean supportedRules;
    private final ThreadLocal<Long2LongLinkedOpenHashMap[]> placementCaches;

    private PredictionModel(long seed, int minHeight, int height, List<Rule> rules,
                            Set<ResourceLocation> knownBiomes, BiomeLookup lookup, boolean supportedRules) {
        this.seed = seed; this.minHeight = minHeight; this.height = height;
        minQuart = QuartPos.fromBlock(minHeight); rngYBound = minQuart + QuartPos.fromBlock(height) - 1;
        this.rules = List.copyOf(rules); this.knownBiomes = Set.copyOf(knownBiomes);
        biomeLookup = lookup; this.supportedRules = supportedRules;
        placementCaches = ThreadLocal.withInitial(() -> {
            var caches = new Long2LongLinkedOpenHashMap[this.rules.size()];
            for (int i = 0; i < caches.length; i++) caches[i] = new Long2LongLinkedOpenHashMap(64);
            return caches;
        });
    }
    /** Capture on the server thread; biome tags and recipe objects must not be consulted later by workers. */
    public static PredictionModel capture(ServerLevel level, List<RecipeHolder<VeinRecipe>> recipes) {
        return capture(level, recipes, level.getChunkSource().getGenerator().getBiomeSource());
    }
    static PredictionModel capture(ServerLevel level, List<RecipeHolder<VeinRecipe>> recipes, BiomeSource source) {
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var known = new HashSet<ResourceLocation>(); biomes.keySet().forEach(known::add);
        var rules = new ArrayList<Rule>(); boolean supported = true;
        for (var holder : recipes) {
            var recipe = holder.value();
            if (recipe.getClass() != VeinRecipe.class || recipe.getPlacement().getClass() != RandomSpreadStructurePlacement.class) { supported = false; break; }
            var allowed = new HashSet<ResourceLocation>();
            biomes.holders().forEach(biome -> { if (recipe.canGenerate(level, biome)) allowed.add(biome.key().location()); });
            // Decode a private immutable placement instead of retaining a mutable recipe's placement reference.
            var codec = RandomSpreadStructurePlacement.CODEC.codec();
            var placement = codec.parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, recipe.getPlacement()).getOrThrow()).getOrThrow();
            rules.add(new Rule(holder.id().toString(), placement, Set.copyOf(allowed)));
        }
        var sampler = level.getChunkSource().randomState().sampler();
        BiomeLookup lookup = null;
        if (source.getClass() == FixedBiomeSource.class) {
            var id = source.getNoiseBiome(0, 0, 0, sampler).unwrapKey().map(key -> key.location()).orElse(null);
            if (id != null) lookup = (x, y, z) -> id;
        } else if (source.getClass() == CheckerboardColumnBiomeSource.class) {
            // HolderSet.Named can change during tag reload. Freeze its ordered contents as IDs.
            var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var json = CheckerboardColumnBiomeSource.CODEC.codec().encodeStart(ops, (CheckerboardColumnBiomeSource)source).getOrThrow().getAsJsonObject();
            var holders = Biome.LIST_CODEC.parse(ops, json.get("biomes")).getOrThrow();
            var ids = holders.stream().map(holder -> holder.unwrapKey().orElseThrow().location()).toList();
            int shift = 2 + (json.has("scale") ? json.get("scale").getAsInt() : 2);
            if (!ids.isEmpty()) lookup = (x, y, z) -> ids.get(Math.floorMod((x >> shift) + (z >> shift), ids.size()));
        } else if ((source.getClass() == MultiNoiseBiomeSource.class || source.getClass() == TheEndBiomeSource.class)
                && vanillaSampler(sampler) && source.possibleBiomes().stream().allMatch(holder -> holder.unwrapKey().isPresent())) {
            // These vanilla biome sources use immutable noise functions and thread-local climate search caches.
            // Capture only the source/sampler, never the level or registry accessors.
            source.possibleBiomes();
            lookup = (x, y, z) -> source.getNoiseBiome(x, y, z, sampler).unwrapKey().orElseThrow().location();
        }
        return new PredictionModel(level.getSeed(), level.getMinBuildHeight(), level.getHeight(), rules, known, lookup, supported);
    }
    private static boolean vanillaSampler(Climate.Sampler sampler) {
        boolean[] safe = {true};
        DensityFunction.Visitor visitor = function -> {
            if (!vanillaFunction(function)) safe[0] = false;
            return function;
        };
        for (var root : List.of(sampler.temperature(), sampler.humidity(), sampler.continentalness(), sampler.erosion(), sampler.depth(), sampler.weirdness())) {
            if (!vanillaFunction(root)) return false;
            if (!vanillaFunction(root.mapAll(visitor))) return false;
        }
        return safe[0];
    }
    private static boolean vanillaFunction(DensityFunction function) {
        String name = function.getClass().getName();
        return name.startsWith("net.minecraft.world.level.levelgen.DensityFunctions$")
                || name.equals("net.minecraft.world.level.levelgen.synth.BlendedNoise");
    }
    public boolean fullyParallel() { return supportedRules && biomeLookup != null; }
    public Resolution resolve(ChunkPos pos, CompoundTag privateSaved, VeinSnapshot cached) {
        var actual = SavedChunkSurvey.oreData(privateSaved);
        if (actual != null && actual.isLoaded()) return new Resolution(VeinSnapshot.from(actual, ScanCell.SAVED), false, false);
        if (cached != null) return new Resolution(cached, false, true);
        if (!supportedRules) return Resolution.deferred();
        boolean fromSave = privateSaved != null;
        var caches = placementCaches.get();
        ResourceLocation sampledBiome = null;
        for (int i = 0; i < rules.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Prediction interrupted");
            var rule = rules.get(i); var placement = rule.placement;
            long region = MapMath.key(Math.floorDiv(pos.x, placement.spacing()), Math.floorDiv(pos.z, placement.spacing()));
            var cache = caches[i]; long candidate;
            if (cache.containsKey(region)) candidate = cache.getAndMoveToLast(region);
            else {
                candidate = placement.getPotentialStructureChunk(seed, pos.x, pos.z).toLong();
                cache.putAndMoveToLast(region, candidate);
                if (cache.size() > 256) cache.removeFirstLong();
            }
            if (candidate != pos.toLong()) continue;
            // Every recipe uses the same per-chunk biome RNG; sample once even if several candidates overlap.
            if (sampledBiome == null) {
                var rng = new WorldgenRandom(new LegacyRandomSource(0)); rng.setLargeFeatureSeed(seed, pos.x, pos.z);
                int x = rng.nextInt(4), y = minQuart + rng.nextInt(rngYBound), z = rng.nextInt(4);
                sampledBiome = SavedChunkSurvey.biomeId(privateSaved, minHeight, height, x, y, z);
                if (sampledBiome == null || !knownBiomes.contains(sampledBiome)) {
                    if (biomeLookup == null) return Resolution.deferred();
                    fromSave = false; sampledBiome = biomeLookup.sample(QuartPos.fromSection(pos.x) + x, y, QuartPos.fromSection(pos.z) + z);
                }
            }
            if (rule.allowedBiomes.contains(sampledBiome)) {
                float amount = RandomSource.create(seed ^ pos.toLong()).nextFloat();
                return new Resolution(new VeinSnapshot(rule.id, amount, 0, fromSave ? ScanCell.SAVED : ScanCell.SURVEY), true, false);
            }
        }
        return new Resolution(new VeinSnapshot("", 0, 0, fromSave ? ScanCell.SAVED : ScanCell.SURVEY), true, false);
    }
}
