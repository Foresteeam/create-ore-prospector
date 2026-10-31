package dev.mechanicalprospector.scan;

import com.tom.createores.Config;
import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.recipe.VeinRecipe;
import com.tom.createores.util.ThreeState;
import dev.mechanicalprospector.MechanicalProspector;
import dev.mechanicalprospector.network.ScanBatch;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class VeinIntegrationTest {
    @Test void actualCoeGenerationSavedBiomesAndPacketsAgree(MinecraftServer server) {
        server.submit(() -> {
            if (server.overworld() == null) {
                // The framework starts a server with registries/recipes but intentionally no levels.
                try {
                    var initialize = MinecraftServer.class.getDeclaredMethod("loadLevel");
                    initialize.setAccessible(true); initialize.invoke(server);
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            }
            var level = server.overworld();
            var recipes = VeinSurvey.recipes(level);
            assertFalse(recipes.isEmpty(), "COE recipes loaded in the real server");
            var samplePositions = new ArrayList<ChunkPos>();
            samplePositions.addAll(List.of(new ChunkPos(-1, -1), new ChunkPos(0, 0), new ChunkPos(1, -1)));
            for (var recipe : recipes) for (int z = -1; z <= 1 && samplePositions.size() < 15; z++)
                for (int x = -1; x <= 1 && samplePositions.size() < 15; x++) {
                    int spacing = recipe.value().getPlacement().spacing();
                    var candidate = recipe.value().getPlacement().getPotentialStructureChunk(level.getSeed(), x * spacing, z * spacing);
                    if (VeinSurvey.predict(level, candidate, null, recipes).hasVein() && !samplePositions.contains(candidate)) samplePositions.add(candidate);
                }
            assertTrue(samplePositions.size() > 3, "Found real vein positions, not only empty chunks");
            var cells = new ArrayList<ScanCell>();
            for (var pos : samplePositions) {
                var predicted = VeinSurvey.predict(level, pos, null, recipes);
                var chunk = level.getChunk(pos.x, pos.z);
                var actual = OreDataAttachment.getData(chunk);
                assertEquals(actual.getRecipeId() == null ? "" : actual.getRecipeId().toString(), predicted.veinId(), "COE vein at " + pos);
                var live = VeinSurvey.survey(level, pos, null, recipes);
                assertEquals(predicted.kind(), live.kind(), "Reserve state at " + pos);
                assertEquals(predicted.remaining(), live.remaining(), "Initial reserve at " + pos);
                var saved = ChunkSerializer.write(level, chunk);
                var restored = SavedChunkSurvey.oreData(saved);
                assertNotNull(restored, "NeoForge attachment is present under its actual serialized key");
                assertEquals(actual.save(), restored.save());
                for (int y : new int[]{-16, -1, 0, 15, 50, 79}) {
                    assertEquals(chunk.getNoiseBiome(1, y, 3), SavedChunkSurvey.biome(level, saved, 1, y, 3), "Stored biome at quart Y " + y);
                }
                var savedPrediction = VeinSurvey.predict(level, pos, saved, recipes);
                assertEquals(live.veinId(), savedPrediction.veinId());
                assertEquals(live.remaining(), savedPrediction.remaining());
                cells.add(live);
            }
            var packet = new ScanBatch(42, level.dimension().location(), 0, 0, 16, cells.size(), true, cells);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                ScanBatch.CODEC.encode(buffer, packet);
                assertTrue(buffer.readableBytes() < 32 * 1024, "A batch is small enough for routine multiplayer use");
                var restored = ScanBatch.CODEC.decode(buffer);
                assertEquals(packet.requestId(), restored.requestId());
                assertEquals(packet.dimension(), restored.dimension());
                assertEquals(cells.size(), restored.cells().size());
                for (int i = 0; i < cells.size(); i++) {
                    assertEquals(cells.get(i).veinId(), restored.cells().get(i).veinId());
                    assertEquals(cells.get(i).remaining(), restored.cells().get(i).remaining());
                    assertEquals(cells.get(i).name(), restored.cells().get(i).name());
                }
            } finally { buffer.release(); }
            var player = FakePlayerFactory.getMinecraft(level);
            player.setItemInHand(InteractionHand.MAIN_HAND, MechanicalProspector.PROSPECTOR.toStack());
            assertTrue(ScanService.holding(player));
            var result = MechanicalProspector.PROSPECTOR.get().use(level, player, InteractionHand.MAIN_HAND);
            assertTrue(result.getResult().consumesAction());
            assertEquals(0, result.getObject().getDamageValue());
            assertEquals(1, result.getObject().getCount());
        }).join();
    }

    @Test void reservesKeepMinedAmountAndInfiniteSentinel(MinecraftServer server) {
        server.submit(() -> {
            var recipe = new VeinRecipe();
            recipe.finite = ThreeState.ALWAYS;
            recipe.amountMultiplierMin = 1; recipe.amountMultiplierMax = 1;
            var data = new OreData(); data.setLoaded(true); data.setRandomMul(0.75f); data.setExtractedAmount(57);
            assertEquals(Config.finiteAmountBase - 57L, data.getResourcesRemaining(recipe));
            var encoded = OreData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
            var restored = OreData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
            assertEquals(data.getResourcesRemaining(recipe), restored.getResourcesRemaining(recipe));
            restored.setExtractedAmount(Config.finiteAmountBase);
            assertEquals(-1, restored.getResourcesRemaining(recipe));
            recipe.finite = ThreeState.NEVER;
            assertEquals(0, restored.getResourcesRemaining(recipe));
        }).join();
    }
}
