package dev.mechanicalprospector.client;

import dev.mechanicalprospector.MechanicalProspector;
import dev.mechanicalprospector.cache.CachedCell;
import dev.mechanicalprospector.cache.ClientCachePlan;
import dev.mechanicalprospector.cache.RegionDiskCache;
import dev.mechanicalprospector.network.ScanBatch;
import dev.mechanicalprospector.network.ScanContext;
import dev.mechanicalprospector.scan.MapMath;
import dev.mechanicalprospector.scan.ScanCell;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = MechanicalProspector.MOD_ID, value = Dist.CLIENT)
public final class ClientSurveyCache {
    private static ClientPacketListener connection;
    private static final LinkedHashMap<UUID, RegionDiskCache<CachedCell>> STORES = new LinkedHashMap<>(4, .75f, true);
    private static final Map<ResourceLocation, UUID> LAST_CONTEXT = new HashMap<>();
    private static int ticks;
    private ClientSurveyCache() {}
    private static void bind() {
        var current = Minecraft.getInstance().getConnection();
        if (current != connection) { LAST_CONTEXT.clear(); connection = current; }
    }
    private static RegionDiskCache<CachedCell> store(UUID id) {
        var result = STORES.computeIfAbsent(id, key -> new RegionDiskCache<>(Minecraft.getInstance().gameDirectory.toPath()
                .resolve("orechunk-client").resolve(key.toString()), CachedCell.CODEC, 128, "prospector-client-cache"));
        while (STORES.size() > 4) {
            var oldest = STORES.entrySet().iterator().next(); STORES.remove(oldest.getKey());
            oldest.getValue().close();
        }
        return result;
    }
    public static CompletableFuture<Map<RegionDiskCache.Position, CachedCell>> load(ScanContext context) {
        bind(); LAST_CONTEXT.put(context.dimension(), context.cacheId());
        var store = store(context.cacheId());
        return context.refresh() ? CompletableFuture.completedFuture(Map.of())
                : store.getAllAsync(ClientCachePlan.positions(context.centerX(), context.centerZ(), context.radius()));
    }
    public static Map<Long, ScanCell> resident(ResourceLocation dimension, int x, int z, int radius) {
        bind();
        var id = LAST_CONTEXT.get(dimension); var mc = Minecraft.getInstance();
        if (id == null || mc.level == null) return Map.of();
        var store = STORES.get(id); if (store == null) return Map.of();
        var result = new HashMap<Long, ScanCell>();
        for (var pos : ClientCachePlan.positions(x, z, radius)) {
            var value = store.getIfPresent(pos.x(), pos.z());
            if (value == null) continue;
            var cell = value.resolve(pos.x(), pos.z(), mc.level.getRecipeManager());
            if (cell != null) result.put(MapMath.key(pos.x(), pos.z()), cell);
        }
        return result;
    }
    public static void remember(ScanBatch batch) {
        bind();
        var target = STORES.get(batch.cacheId()); if (target == null) return;
        var values = new HashMap<RegionDiskCache.Position, CachedCell>();
        for (var cell : batch.cells()) if (cell.kind() != ScanCell.UNAVAILABLE)
            values.put(new RegionDiskCache.Position(cell.x(), cell.z()), CachedCell.from(cell));
        if (!values.isEmpty()) target.putAllAsync(values);
        if (batch.finished()) target.flushAsync();
    }
    public static void flush() { for (var store : STORES.values()) store.flushAsync(); }
    private static void closeAll(boolean wait) {
        var stores = List.copyOf(STORES.values()); STORES.clear(); LAST_CONTEXT.clear();
        if (wait) stores.forEach(RegionDiskCache::close);
        else stores.forEach(store -> CompletableFuture.runAsync(store::close));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) { if (++ticks % 100 == 0) flush(); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { flush(); LAST_CONTEXT.clear(); connection = null; }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event) { closeAll(true); connection = null; }
}
