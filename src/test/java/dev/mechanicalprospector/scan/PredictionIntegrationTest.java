package dev.mechanicalprospector.scan;

import com.tom.createores.recipe.VeinRecipe;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class PredictionIntegrationTest {
    private record Query(ChunkPos position, CompoundTag input, VeinSnapshot expected) {}
    private record Case(PredictionModel model, List<Query> queries) {}
    private record Timing(List<VeinSnapshot> results, long nanos, int peakBatches) {}
    private static void world(MinecraftServer server) {
        if (server.overworld() != null) return;
        try { var load = MinecraftServer.class.getDeclaredMethod("loadLevel"); load.setAccessible(true); load.invoke(server); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    @Test void parallelResultsMatchTheReferenceAndBenchmarkA129Square(MinecraftServer server) throws Exception {
        var cases = server.submit(() -> {
            world(server); var inputs = new ArrayList<Case>();
            for (var level : List.of(server.overworld(), server.getLevel(Level.NETHER), server.getLevel(Level.END))) {
                var recipes = VeinSurvey.recipes(level); var model = PredictionModel.capture(level, recipes);
                assertTrue(model.fullyParallel(), "Standard biome/noise source is safe for parallel prediction: " + level.dimension());
                int radius = level == server.overworld() ? 64 : 16; var queries = new ArrayList<Query>();
                long start = System.nanoTime();
                for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
                    var pos = new ChunkPos(x, z);
                    queries.add(new Query(pos, null, VeinSurvey.predictSnapshot(level, pos, null, recipes)));
                }
                System.out.printf(java.util.Locale.ROOT, "REFERENCE dimension=%s chunks=%d ms=%.3f%n", level.dimension().location(), queries.size(), (System.nanoTime()-start)/1e6);
                inputs.add(new Case(model, List.copyOf(queries)));
            }
            // A valid datapack-like dense placement stresses biome noise rather than mostly-empty RNG tests.
            var base = VeinSurvey.recipes(server.overworld()).getFirst().value();
            var forest = new VeinRecipe(base.getName(), 1, Optional.of(net.minecraft.tags.TagKey.create(Registries.BIOME,
                    net.minecraft.resources.ResourceLocation.parse("minecraft:is_forest"))), Optional.empty(), base.finite,
                    1, 1, new RandomSpreadStructurePlacement(1, 0, RandomSpreadType.LINEAR, 71), base.getIcon());
            var fallback = new VeinRecipe(base.getName(), 0, Optional.empty(), Optional.empty(), base.finite,
                    1, 1, new RandomSpreadStructurePlacement(1, 0, RandomSpreadType.LINEAR, 72), base.getIcon());
            var dense = List.of(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:forest_vein"), forest),
                    new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:other_vein"), fallback));
            var denseQueries = new ArrayList<Query>();
            // The framework's overworld is flat. Use its real noise-based Nether for CPU scaling measurements.
            var noiseLevel = server.getLevel(Level.NETHER);
            for (var query : inputs.getFirst().queries) denseQueries.add(new Query(query.position, null,
                    VeinSurvey.predictSnapshot(noiseLevel, query.position, null, dense)));
            assertTrue(denseQueries.stream().allMatch(q -> q.expected.recipeId().equals("test:other_vein")), "Forest whitelist rejects Nether biomes before the fallback candidate");
            inputs.add(new Case(PredictionModel.capture(noiseLevel, dense), List.copyOf(denseQueries)));
            // Include real saved biome palettes, with and without an initialized COE attachment.
            var level = server.overworld(); var pos = new ChunkPos(-87, 89);
            var chunk = level.getChunk(pos.x, pos.z);
            var actual = com.tom.createores.OreDataAttachment.getData(chunk);
            var saved = ChunkSerializer.write(level, chunk);
            var queries = new ArrayList<Query>();
            assertNotNull(SavedChunkSurvey.oreData(saved));
            queries.add(new Query(pos, SavedChunkSurvey.predictionInput(saved), VeinSnapshot.from(actual, ScanCell.SAVED)));
            saved.getCompound("neoforge:attachments").remove("createoreexcavation:ore_vein");
            queries.add(new Query(pos, SavedChunkSurvey.predictionInput(saved), VeinSurvey.predictSnapshot(level, pos, saved, VeinSurvey.recipes(level))));
            inputs.add(new Case(PredictionModel.capture(level, VeinSurvey.recipes(level)), queries));
            return inputs;
        }).join();
        for (var item : cases) {
            var one = run(item, 1); var four = run(item, 4);
            var expected = item.queries.stream().map(Query::expected).toList();
            assertEquals(expected, one.results); assertEquals(expected, four.results);
            System.out.printf(java.util.Locale.ROOT, "PARITY chunks=%d singleWorkerMs=%.3f fourWorkersMs=%.3f peakBatches=%d%n",
                    expected.size(), one.nanos / 1e6, four.nanos / 1e6, four.peakBatches);
        }
        for (var target : List.of(cases.getFirst(), cases.get(3))) {
            long[] oneTimes = new long[3], fourTimes = new long[3];
            for (int i = 0; i < 3; i++) {
                var one = run(target, 1); var four = run(target, 4);
                assertEquals(one.results, four.results); oneTimes[i] = one.nanos; fourTimes[i] = four.nanos;
            }
            Arrays.sort(oneTimes); Arrays.sort(fourTimes);
            System.out.printf(java.util.Locale.ROOT, "PROSPECTOR_BENCHMARK kind=%s chunks=16641 logicalCpus=%d medianSingleWorkerMs=%.3f medianFourWorkersMs=%.3f ratio=%.3f%n",
                    target == cases.getFirst() ? "flatDefault" : "denseNetherNoise", Runtime.getRuntime().availableProcessors(),
                    oneTimes[1] / 1e6, fourTimes[1] / 1e6, oneTimes[1] / (double)fourTimes[1]);
        }
    }

    private static Timing run(Case item, int threads) throws Exception {
        long start = System.nanoTime();
        try (var pool = new PredictionExecutor(threads, 16)) {
            var pending = new ArrayList<PredictionExecutor.Batch<VeinSnapshot>>();
            for (int offset = 0; offset < item.queries.size(); offset += 32) {
                var jobs = new ArrayList<Supplier<VeinSnapshot>>();
                for (var query : item.queries.subList(offset, Math.min(offset+32, item.queries.size())))
                    jobs.add(() -> item.model.resolve(query.position, query.input, null).snapshot());
                PredictionExecutor.Batch<VeinSnapshot> batch;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while ((batch = pool.trySubmitBatch(jobs)) == null) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("Pool did not accept work");
                    Thread.yield();
                }
                pending.add(batch);
            }
            CompletableFuture.allOf(pending.stream().map(PredictionExecutor.Batch::completion).toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            var results = new ArrayList<VeinSnapshot>(item.queries.size());
            for (var batch : pending) for (int i = 0; i < batch.size(); i++) results.add(batch.join(i));
            return new Timing(results, System.nanoTime() - start, pool.peakActiveBatches());
        }
    }

    @Test void modelAndSavedInputAreSnapshotsAndUnknownBiomeCallbacksAreDeferred(MinecraftServer server) {
        server.submit(() -> {
            world(server); var level = server.overworld(); var template = VeinSurvey.recipes(level).getFirst();
            var recipe = new VeinRecipe(template.value().getName(), 10, Optional.empty(), Optional.empty(), template.value().finite,
                    1, 1, new RandomSpreadStructurePlacement(1, 0, RandomSpreadType.LINEAR, 123), template.value().getIcon());
            var recipes = List.of(new RecipeHolder<>(template.id(), recipe));
            var model = PredictionModel.capture(level, recipes); var pos = new ChunkPos(-81, -83);
            var expected = model.resolve(pos, null, null).snapshot(); assertFalse(expected.recipeId().isEmpty());
            recipe.placement = new RandomSpreadStructurePlacement(1000, 100, RandomSpreadType.TRIANGULAR, 987);
            recipe.biomeWhitelist = net.minecraft.tags.TagKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.parse("test:missing"));
            assertEquals(expected, model.resolve(pos, null, null).snapshot(), "Recipe edits cannot change an in-flight snapshot");
            var saved = ChunkSerializer.write(level, level.getChunk(1, -1));
            var input = SavedChunkSurvey.predictionInput(saved); var before = input.copy();
            saved.getList("sections", net.minecraft.nbt.Tag.TAG_COMPOUND).clear(); saved.getCompound("neoforge:attachments").remove("createoreexcavation:ore_vein");
            assertEquals(before, input, "Worker NBT has no mutable arrays/sections owned by chunk I/O");
            var fixed = level.registryAccess().registryOrThrow(Registries.BIOME).holders().findFirst().orElseThrow();
            var called = new java.util.concurrent.atomic.AtomicBoolean();
            BiomeSource custom = new BiomeSource() {
                @Override protected com.mojang.serialization.MapCodec<? extends BiomeSource> codec() { return FixedBiomeSource.CODEC; }
                @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return Stream.of(fixed); }
                @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) { called.set(true); throw new AssertionError("Must not run arbitrary mod callbacks on workers"); }
            };
            recipe.placement = new RandomSpreadStructurePlacement(1, 0, RandomSpreadType.LINEAR, 123); recipe.biomeWhitelist = null;
            var compatible = PredictionModel.capture(level, recipes, custom);
            assertFalse(compatible.fullyParallel()); assertNull(compatible.resolve(pos, null, null).snapshot()); assertFalse(called.get());
        }).join();
    }

    @Test void cacheCoalescesWorkerQueriesAndCancelsPendingWorkOnClose(MinecraftServer server) throws Exception {
        var initial = server.submit(() -> {
            world(server); ServerSurveyCache.closeFor(server); var cache = ServerSurveyCache.get(server);
            var context = cache.dimension(server.overworld()); assertTrue(context.model().fullyParallel());
            var results = new ArrayList<CompletableFuture<ScanCell>>();
            for (int i = 0; i < 256; i++) {
                var pos = new ChunkPos(30000+i, -31000); var result = cache.resolve(context, pos, false);
                assertSame(result, cache.resolve(context, pos, false), "Two players share a pending computation"); results.add(result);
            }
            return results;
        }).join();
        CompletableFuture.allOf(initial.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        server.submit(() -> {
            var cache = ServerSurveyCache.get(server); var stats = cache.parallelStats();
            assertEquals(256, cache.stats().predictions()); assertEquals(256, stats.backgroundPredictions()); assertEquals(0, stats.mainThreadFallbacks());
            assertEquals(256, stats.completedJobs()); assertTrue(stats.peakActiveBatches() > 0);
            var context = cache.dimension(server.overworld()); var pending = new ArrayList<CompletableFuture<ScanCell>>();
            for (int i = 0; i < 128; i++) pending.add(cache.resolve(context, new ChunkPos(40000+i, -41000), false));
            ServerSurveyCache.closeFor(server); pending.forEach(future -> assertTrue(future.isCompletedExceptionally()));
        }).join();
        var cold = server.submit(() -> {
            var fresh = ServerSurveyCache.get(server); return fresh.resolve(fresh.dimension(server.overworld()), new ChunkPos(30000,-31000), false);
        }).join();
        assertTrue(cold.get(30, TimeUnit.SECONDS).cached());
        server.submit(() -> { assertEquals(0, ServerSurveyCache.get(server).stats().predictions()); ServerSurveyCache.closeFor(server); }).join();
    }

    @Test void checkerboardAndRecipeTagsAreFrozenBeforeWorkersRun(MinecraftServer server) {
        server.submit(() -> {
            world(server); var level = server.overworld(); var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            var allowedTag = net.minecraft.tags.TagKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.parse("test:allowed_snapshot"));
            var sourceTag = net.minecraft.tags.TagKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.parse("test:checker_snapshot"));
            var before = new java.util.HashMap<net.minecraft.tags.TagKey<Biome>, List<Holder<Biome>>>();
            registry.getTags().forEach(pair -> before.put(pair.getFirst(), pair.getSecond().stream().toList()));
            var tags = new java.util.HashMap<>(before); List<Holder<Biome>> holders = registry.holders().limit(3).map(h -> (Holder<Biome>)h).toList();
            tags.put(allowedTag, List.of(holders.getFirst())); tags.put(sourceTag, holders); registry.bindTags(tags);
            try {
                var template = VeinSurvey.recipes(level).getFirst(); var base = template.value();
                var recipe = new VeinRecipe(base.getName(), 1, Optional.of(allowedTag), Optional.empty(), base.finite,
                        1, 1, new RandomSpreadStructurePlacement(1, 0, RandomSpreadType.LINEAR, 14), base.getIcon());
                var source = new net.minecraft.world.level.biome.CheckerboardColumnBiomeSource(registry.getOrCreateTag(sourceTag), 2);
                var model = PredictionModel.capture(level, List.of(new RecipeHolder<>(template.id(), recipe)), source);
                assertTrue(model.fullyParallel()); var positions = new ArrayList<ChunkPos>(); var expected = new ArrayList<String>();
                for (int x = -64; x <= 64; x++) {
                    var pos = new ChunkPos(x, -x/2); positions.add(pos);
                    var rng = new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(0));
                    rng.setLargeFeatureSeed(level.getSeed(), pos.x, pos.z);
                    int localX = rng.nextInt(4), y = level.getMinBuildHeight()/4 + rng.nextInt(level.getMinBuildHeight()/4 + level.getHeight()/4 - 1), localZ = rng.nextInt(4);
                    var biome = source.getNoiseBiome(pos.x*4 + localX, y, pos.z*4 + localZ, level.getChunkSource().randomState().sampler());
                    expected.add(recipe.canGenerate(level, biome) ? template.id().toString() : "");
                }
                assertEquals(2, expected.stream().distinct().count());
                tags.put(sourceTag, List.of(holders.getLast())); tags.put(allowedTag, List.of(holders.getLast())); registry.bindTags(tags);
                try (var workers = new PredictionExecutor(4, 8)) {
                    for (int offset = 0; offset < positions.size(); offset += 32) {
                        var batch = workers.trySubmitBatch(positions.subList(offset, Math.min(offset+32, positions.size())).stream()
                                .<Supplier<PredictionModel.Resolution>>map(pos -> () -> model.resolve(pos, null, null)).toList());
                        assertNotNull(batch); batch.completion().orTimeout(5, TimeUnit.SECONDS).join();
                        for (int i = 0; i < batch.size(); i++) assertEquals(expected.get(offset+i), batch.join(i).snapshot().recipeId());
                    }
                }
            } finally {
                before.put(allowedTag, List.of()); before.put(sourceTag, List.of()); registry.bindTags(before);
            }
        }).join();
    }
}
