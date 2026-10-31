package dev.mechanicalprospector.scan;

import com.mojang.authlib.GameProfile;
import com.tom.createores.OreData;
import com.tom.createores.OreDataAttachment;
import com.tom.createores.recipe.VeinRecipe;
import com.tom.createores.util.ThreeState;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingInput;
import com.simibubi.create.content.kinetics.crafter.RecipeGridHandler;
import dev.mechanicalprospector.MechanicalProspector;
import dev.mechanicalprospector.ProspectorConfig;
import dev.mechanicalprospector.cache.CachedCell;
import dev.mechanicalprospector.cache.ClientCachePlan;
import dev.mechanicalprospector.cache.RegionDiskCache;
import dev.mechanicalprospector.network.ScanBatch;
import dev.mechanicalprospector.network.ScanContext;
import dev.mechanicalprospector.network.ScanOpenRequest;
import dev.mechanicalprospector.network.ScanRequest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class ServerCacheIntegrationTest {
    private static void world(MinecraftServer server) {
        if (server.overworld() != null) return;
        try { var load = MinecraftServer.class.getDeclaredMethod("loadLevel"); load.setAccessible(true); load.invoke(server); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Test void renamedInstrumentHasItsOwnCreativeTabAndLegacyItemsStillOpen(MinecraftServer server) {
        server.submit(() -> {
            world(server);
            assertEquals(ResourceLocation.parse("create:ore_prospector"), MechanicalProspector.PROSPECTOR.getId());
            assertEquals("item.create.ore_prospector", MechanicalProspector.PROSPECTOR.get().getDescriptionId());
            var tab = MechanicalProspector.PROSPECTOR_TAB.get();
            assertEquals(ResourceLocation.parse("create_ore_prospector:main"), BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab));
            assertEquals(net.minecraft.network.chat.Component.translatable("itemGroup.create_ore_prospector"), tab.getDisplayName());
            assertTrue(tab.getIconItem().is(MechanicalProspector.PROSPECTOR.get()));
            tab.buildContents(new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
                    server.overworld().enabledFeatures(), true, server.registryAccess()));
            assertEquals(1, tab.getDisplayItems().size());
            assertTrue(tab.getDisplayItems().iterator().next().is(MechanicalProspector.PROSPECTOR.get()));
            assertFalse(tab.contains(MechanicalProspector.LEGACY_PROSPECTOR.toStack()));
            var legacy = player(server, "LegacySurvey");
            legacy.player.setItemInHand(InteractionHand.MAIN_HAND, MechanicalProspector.LEGACY_PROSPECTOR.toStack());
            open(legacy, 301, 8, false);
            assertNotNull(legacy.context(301), "Previously saved instruments still pass server validation");
            ScanService.request(ScanRequest.cancel(301), legacy.player);
        }).join();
        try (var reader = new java.io.InputStreamReader(getClass().getResourceAsStream(
                "/assets/create_ore_prospector/lang/zh_cn.json"), java.nio.charset.StandardCharsets.UTF_8)) {
            var language = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals("机械动力：探矿仪", language.get("itemGroup.create_ore_prospector").getAsString());
            assertEquals("机械探矿仪", language.get("item.create.ore_prospector").getAsString());
            assertEquals("机械探矿仪", language.get("gui.create_ore_prospector.title").getAsString());
            assertNotNull(getClass().getResource("/assets/create/models/item/ore_prospector.json"));
            assertNotNull(getClass().getResource("/assets/create_ore_prospector/textures/item/ore_prospector.png"));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    @Test void recipeUsesExactlyTheUploaded5x5PatternAndCannotBeCraftedAtAWorkbench(MinecraftServer server) {
        server.submit(() -> {
            world(server); var level = server.overworld(); var id = ResourceLocation.parse("create:ore_prospector");
            var recipe = level.getRecipeManager().byKey(id).orElseThrow().value();
            assertInstanceOf(MechanicalCraftingRecipe.class, recipe);
            var mechanical = (MechanicalCraftingRecipe) recipe;
            assertEquals(5, mechanical.getWidth()); assertEquals(5, mechanical.getHeight());
            String[][] grid = {
                    {"brass_block", "brass_sheet", "redstone_link", "brass_sheet", "brass_block"},
                    {"brass_sheet", "precision_mechanism", "display_board", "precision_mechanism", "brass_sheet"},
                    {"brass_casing", "electron_tube", "rose_quartz_lamp", "electron_tube", "brass_casing"},
                    {"brass_sheet", "sturdy_sheet", "createoreexcavation:vein_finder", "sturdy_sheet", "brass_sheet"},
                    {"brass_block", "brass_sheet", "mechanical_drill", "brass_sheet", "brass_block"}};
            var stacks = new ArrayList<ItemStack>();
            for (var row : grid) for (var ingredient : row)
                stacks.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(ingredient.contains(":") ? ingredient : "create:" + ingredient))));
            assertTrue(mechanical.matches(mechanicalInput(stacks, level.registryAccess()), level));
            assertTrue(mechanical.assemble(mechanicalInput(stacks, level.registryAccess()), level.registryAccess()).is(MechanicalProspector.PROSPECTOR.get()));
            assertFalse(mechanical.matches(CraftingInput.of(5, 5, stacks), level), "Regular crafting grids cannot use the mechanical recipe");
            assertTrue(level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream().noneMatch(r -> r.id().equals(id)));
            stacks.set(12, ItemStack.EMPTY);
            assertFalse(mechanical.matches(mechanicalInput(stacks, level.registryAccess()), level));
        }).join();
    }
    @Test void serverConfiguredRateIsSharedAcrossPlayers(MinecraftServer server) throws Exception {
        int previous = server.submit(() -> { world(server); return ProspectorConfig.serverRate(); }).join();
        var players = server.submit(() -> {
            assertTrue(ProspectorConfig.SERVER_SPEC.isLoaded());
            ProspectorConfig.SERVER_MAX_SCAN_CHUNKS_PER_SECOND.set(20);
            var one = player(server, "RateOne"); var two = player(server, "RateTwo");
            for (var player : List.of(one, two)) {
                open(player, 401, 8, false); var context = player.context(401);
                var mask = new BitSet(); mask.set(0, 289);
                ScanService.request(new ScanRequest(401, context.cacheId(), context.centerX(), context.centerZ(), mask.toByteArray(), false), player.player);
            }
            return List.of(one, two);
        }).join();
        try {
            Thread.sleep(1200);
            server.submit(() -> {
                int received = players.stream().mapToInt(p -> p.cells(401).size()).sum();
                assertTrue(received > 0 && received <= 28, "Both players share 20 chunks/sec, received=" + received);
                assertFalse(players.get(0).done(401)); assertFalse(players.get(1).done(401));
            }).join();
        } finally {
            server.submit(() -> {
                for (var player : players) ScanService.request(ScanRequest.cancel(401), player.player);
                ProspectorConfig.SERVER_MAX_SCAN_CHUNKS_PER_SECOND.set(previous);
            }).join();
        }
    }
    @Test void twoPlayersSharePredictionsAndColdServerCacheAndClientMissOnlyRequests(MinecraftServer server) throws Exception {
        var setup = server.submit(() -> {
            world(server); ServerSurveyCache.closeFor(server);
            var one = player(server, "SurveyOne"); var two = player(server, "SurveyTwo");
            open(one, 101, 8, false); open(two, 201, 8, false);
            var first = one.context(101); var second = two.context(201);
            assertEquals(first.cacheId(), second.cacheId());
            var missing = new BitSet(); missing.set(0, 289);
            ScanService.request(new ScanRequest(101, first.cacheId(), first.centerX(), first.centerZ(), missing.toByteArray(), false), one.player);
            ScanService.request(new ScanRequest(201, second.cacheId(), second.centerX(), second.centerZ(), missing.toByteArray(), false), two.player);
            return List.of(one, two);
        }).join();
        var one = setup.get(0); var two = setup.get(1);
        await(() -> one.done(101) && two.done(201));
        server.submit(() -> {
            var cache = ServerSurveyCache.get(server); var firstCells = one.cells(101); var secondCells = two.cells(201);
            assertEquals(289, firstCells.size()); assertEquals(289, secondCells.size());
            assertEquals(289, firstCells.stream().map(c -> MapMath.key(c.x(), c.z())).distinct().count());
            assertTrue(one.listener.packets.stream().filter(p -> p instanceof ScanBatch).allMatch(p -> ((ScanBatch)p).cells().size() <= 128));
            assertTrue(cache.stats().predictions() > 0); assertTrue(cache.stats().predictions() <= 289, "Shared requests predict each missing chunk once");
            assertTrue(cache.stats().worldReads() <= 289, "Shared requests read each chunk at most once");
            var before = cache.stats(); open(one, 102, 8, false); var context = one.context(102);
            var all = new BitSet(); all.set(0, 289);
            // Stale world tokens are rejected before any world/model work.
            ScanService.request(new ScanRequest(102, UUID.randomUUID(), context.centerX(), context.centerZ(), all.toByteArray(), false), one.player);
            assertFalse(one.done(102)); assertEquals(before, cache.stats());
            ScanService.request(new ScanRequest(102, context.cacheId(), context.centerX(), context.centerZ(), all.toByteArray(), false), one.player);
        }).join();
        await(() -> one.done(102));
        var modelCount = server.submit(() -> {
            var cache = ServerSurveyCache.get(server); assertTrue(one.cells(102).stream().allMatch(ScanCell::cached));
            var known = new HashMap<Long, CachedCell>(); one.cells(102).forEach(c -> known.put(MapMath.key(c.x(), c.z()), CachedCell.from(c)));
            open(one, 103, 8, false); var context = one.context(103);
            var mask = ClientCachePlan.missing(context.centerX(), context.centerZ(), 8, known);
            assertTrue(mask.isEmpty());
            ScanService.request(new ScanRequest(103, context.cacheId(), context.centerX(), context.centerZ(), mask.toByteArray(), false), one.player);
            assertTrue(one.done(103)); assertEquals(0, one.cells(103).size(), "Fully cached clients receive no duplicate cells");
            open(one, 104, 16, false); var expanded = one.context(104);
            var expansion = ClientCachePlan.missing(expanded.centerX(), expanded.centerZ(), 16, known);
            assertEquals(800, expansion.cardinality());
            ScanService.request(new ScanRequest(104, expanded.cacheId(), expanded.centerX(), expanded.centerZ(), expansion.toByteArray(), false), one.player);
            return cache.stats().predictions();
        }).join();
        await(() -> one.done(104));
        var contextBeforeRestart = one.context(104);
        server.submit(() -> {
            assertEquals(800, one.cells(104).size(), "Expanded scans send only newly uncovered chunks");
            assertTrue(ServerSurveyCache.get(server).stats().predictions() >= modelCount);
            ServerSurveyCache.closeFor(server); // Drops all memory and creates a fresh disk cache instance.
            var fresh = ServerSurveyCache.get(server); var context = fresh.dimension(server.overworld());
            assertEquals(contextBeforeRestart.cacheId(), context.id(), "Stable server/world identity survives cache recreation");
            assertNotEquals(context.id(), fresh.dimension(server.getLevel(net.minecraft.world.level.Level.NETHER)).id());
            open(one, 105, 8, false); var handshake = one.context(105);
            var all = new BitSet(); all.set(0, 289);
            ScanService.request(new ScanRequest(105, handshake.cacheId(), handshake.centerX(), handshake.centerZ(), all.toByteArray(), false), one.player);
        }).join();
        await(() -> one.done(105));
        server.submit(() -> {
            var cache = ServerSurveyCache.get(server); assertEquals(0, cache.stats().predictions()); assertEquals(0, cache.stats().worldReads());
            assertTrue(cache.dimension(server.overworld()).store().diskReads() > 0);
            assertTrue(one.cells(105).stream().allMatch(ScanCell::cached));
            assertEquals(server.getServerDirectory().toAbsolutePath().normalize().resolve("orechunk"), cache.root());
            assertTrue(java.nio.file.Files.isDirectory(cache.root()));
            ScanService.request(ScanRequest.cancel(105), one.player); ScanService.request(ScanRequest.cancel(201), two.player);
        }).join();
    }
    @Test void requestAndContextCodecsRoundTripAMaximumMask() {
        var mask = new BitSet(); mask.set(0, 16641); var id = UUID.randomUUID();
        var request = new ScanRequest(5, id, -9, 3, mask.toByteArray(), false);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ScanRequest.CODEC.encode(buf, request); assertTrue(buf.readableBytes() < 4096);
            var decoded = ScanRequest.CODEC.decode(buf); assertEquals(id, decoded.cacheId());
            assertEquals(mask, BitSet.valueOf(decoded.missingMask())); assertEquals(-9, decoded.centerX());
            var context = new ScanContext(5, id, ResourceLocation.parse("minecraft:the_nether"), -9, 3, 64, true);
            ScanContext.CODEC.encode(buf, context); assertEquals(context, ScanContext.CODEC.decode(buf));
        } finally { buf.release(); }
        assertThrows(IllegalArgumentException.class, () -> new ScanRequest(0, id, 0, 0, new byte[2082], false));
    }
    @Test void manualRefreshUpdatesFiniteReservesWithoutRerollingTheVein(MinecraftServer server) throws Exception {
        var stock = server.submit(() -> {
            world(server); ServerSurveyCache.closeFor(server);
            var level = server.overworld(); var pos = new ChunkPos(900, -901);
            var data = OreDataAttachment.getData(level.getChunk(pos.x, pos.z)); var before = data.save();
            var recipe = VeinSurvey.recipes(level).stream().filter(r -> BuiltInRegistries.ITEM.getKey(r.value().getIcon().getItem()).getPath().equals("raw_iron")).findFirst().orElseThrow();
            var prior = recipe.value().finite; recipe.value().finite = ThreeState.ALWAYS;
            data.setRecipe(recipe.id()); data.setRandomMul(.5f); data.setExtractedAmount(10); data.setLoaded(true);
            var cache = ServerSurveyCache.get(server); var context = cache.dimension(level);
            return new Stock(recipe, prior, pos, before, cache, context, cache.resolve(context, pos, false));
        }).join();
        try {
            await(stock.first::isDone); var original = stock.first.join(); assertEquals(ScanCell.FINITE, original.kind());
            var fresh = server.submit(() -> {
                OreDataAttachment.getData(server.overworld().getChunk(stock.pos.x, stock.pos.z)).extract(7);
                var cached = stock.cache.resolve(stock.context, stock.pos, false).join();
                assertTrue(cached.cached()); assertEquals(original.remaining(), cached.remaining(), "Ordinary requests use the cached snapshot");
                return stock.cache.resolve(stock.context, stock.pos, true);
            }).join();
            await(fresh::isDone); assertEquals(original.remaining() - 7, fresh.join().remaining()); assertEquals(original.veinId(), fresh.join().veinId());
            var cold = server.submit(() -> {
                assertEquals(0, stock.cache.stats().predictions());
                ServerSurveyCache.closeFor(server); var restarted = ServerSurveyCache.get(server);
                return restarted.resolve(restarted.dimension(server.overworld()), stock.pos, false);
            }).join();
            await(cold::isDone); assertTrue(cold.join().cached()); assertEquals(original.remaining() - 7, cold.join().remaining());
        } finally {
            server.submit(() -> {
                stock.recipe.value().finite = stock.prior;
                OreDataAttachment.getData(server.overworld().getChunk(stock.pos.x, stock.pos.z)).load(stock.before);
                ServerSurveyCache.closeFor(server);
            }).join();
        }
    }
    @Test void refreshingUnloadedPredictionsReusesTheModelResult(MinecraftServer server) throws Exception {
        var first = server.submit(() -> {
            world(server); ServerSurveyCache.closeFor(server); var cache = ServerSurveyCache.get(server);
            var context = cache.dimension(server.overworld());
            return cache.resolve(context, new ChunkPos(-10000, 10000), false);
        }).join();
        await(first::isDone);
        var refresh = server.submit(() -> {
            var cache = ServerSurveyCache.get(server); assertEquals(1, cache.stats().predictions());
            return cache.resolve(cache.dimension(server.overworld()), new ChunkPos(-10000, 10000), true);
        }).join();
        await(refresh::isDone);
        assertTrue(refresh.join().cached()); assertEquals(first.join().veinId(), refresh.join().veinId());
        server.submit(() -> { assertEquals(1, ServerSurveyCache.get(server).stats().predictions()); }).join();
    }
    private record Stock(RecipeHolder<VeinRecipe> recipe, ThreeState prior, ChunkPos pos, OreData.Serialized before,
                         ServerSurveyCache cache, ServerSurveyCache.Dimension context, CompletableFuture<ScanCell> first) {}
    private static final class Recorder extends ServerGamePacketListenerImpl {
        final ConcurrentLinkedQueue<CustomPacketPayload> packets = new ConcurrentLinkedQueue<>();
        Recorder(MinecraftServer server, ServerPlayer player) {
            super(server, new Connection(PacketFlow.SERVERBOUND) { @Override public boolean isConnected() { return true; } },
                    player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        @Override public void send(Packet<?> packet) { if (packet instanceof ClientboundCustomPayloadPacket payload) packets.add(payload.payload()); }
    }
    private static MechanicalCraftingInput mechanicalInput(List<ItemStack> stacks, HolderLookup.Provider registries) {
        var tag = new CompoundTag(); var grid = new ListTag();
        for (int i = 0; i < stacks.size(); i++) {
            var cell = new CompoundTag(); cell.putInt("x", i % 5); cell.putInt("y", 4 - i / 5);
            cell.put("item", stacks.get(i).saveOptional(registries)); grid.add(cell);
        }
        tag.put("Grid", grid);
        var grouped = RecipeGridHandler.GroupedItems.read(tag, registries); grouped.calcStats();
        return MechanicalCraftingInput.of(grouped);
    }
    private record TestPlayer(ServerPlayer player, Recorder listener) {
        ScanContext context(int id) { return listener.packets.stream().filter(p -> p instanceof ScanContext c && c.requestId() == id).map(p -> (ScanContext)p).findFirst().orElseThrow(); }
        boolean done(int id) { return listener.packets.stream().anyMatch(p -> p instanceof ScanBatch b && b.requestId() == id && b.finished()); }
        List<ScanCell> cells(int id) { return listener.packets.stream().filter(p -> p instanceof ScanBatch b && b.requestId() == id).flatMap(p -> ((ScanBatch)p).cells().stream()).toList(); }
    }
    private static TestPlayer player(MinecraftServer server, String name) {
        var player = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.randomUUID(), name));
        var listener = new Recorder(server, player); player.connection = listener; player.setPos(8, 70, 8);
        player.setItemInHand(InteractionHand.MAIN_HAND, MechanicalProspector.PROSPECTOR.toStack()); return new TestPlayer(player, listener);
    }
    private static void open(TestPlayer player, int id, int radius, boolean refresh) { ScanService.open(new ScanOpenRequest(id, radius, refresh), player.player); }
    private static void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(done.getAsBoolean(), "Server scan completed within 30 seconds");
    }
}
