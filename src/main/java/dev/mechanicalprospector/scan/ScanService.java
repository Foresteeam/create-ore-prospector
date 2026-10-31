package dev.mechanicalprospector.scan;

import dev.mechanicalprospector.MechanicalProspector;
import dev.mechanicalprospector.ProspectorConfig;
import dev.mechanicalprospector.network.ScanBatch;
import dev.mechanicalprospector.network.ScanContext;
import dev.mechanicalprospector.network.ScanOpenRequest;
import dev.mechanicalprospector.network.ScanRequest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ScanService {
    private static final Map<UUID, Open> OPEN = new HashMap<>();
    private static final Map<UUID, Session> ACTIVE = new LinkedHashMap<>();
    private static final ScanRateLimiter RATE = new ScanRateLimiter();
    private ScanService() {}
    public static boolean holding(Player player) {
        return MechanicalProspector.isProspector(player.getMainHandItem())
                || MechanicalProspector.isProspector(player.getOffhandItem());
    }
    public static void open(ScanOpenRequest request, Player sender) {
        if (!(sender instanceof ServerPlayer player) || !holding(player) || !player.isAlive() || player.isSpectator()) return;
        var cache = ServerSurveyCache.get(player.getServer());
        var context = cache.dimension(player.serverLevel());
        var open = new Open(player, request.requestId(), MapMath.clampRadius(request.radius()), request.refresh(), player.chunkPosition(), context);
        ACTIVE.remove(player.getUUID()); OPEN.put(player.getUUID(), open);
        PacketDistributor.sendToPlayer(player, new ScanContext(open.id, context.id(), context.level().dimension().location(),
                open.center.x, open.center.z, open.radius, open.refresh));
    }
    public static void request(ScanRequest request, Player sender) {
        if (!(sender instanceof ServerPlayer player)) return;
        var open = OPEN.get(player.getUUID());
        if (open == null || open.id != request.requestId()) return;
        if (request.cancel()) { OPEN.remove(player.getUUID()); ACTIVE.remove(player.getUUID()); return; }
        if (open.started || !valid(open) || !open.context.id().equals(request.cacheId())
                || request.centerX() != open.center.x || request.centerZ() != open.center.z) return;
        var missing = BitSet.valueOf(request.missingMask());
        if (missing.length() > MapMath.total(open.radius)) return;
        if (open.refresh) missing.set(0, MapMath.total(open.radius));
        open.started = true;
        var session = new Session(open, missing);
        if (session.completed == MapMath.total(open.radius)) session.flush(true);
        else { ACTIVE.put(player.getUUID(), session); session.flush(false); }
    }
    private static boolean valid(Open open) {
        return !open.player.hasDisconnected() && holding(open.player) && open.player.isAlive()
                && open.player.serverLevel() == open.context.level()
                && Math.abs(open.player.chunkPosition().x - open.center.x) <= 2
                && Math.abs(open.player.chunkPosition().z - open.center.z) <= 2;
    }
    public static void tick(ServerTickEvent.Post event) {
        long deadline = System.nanoTime() + 4_000_000L;
        int globalBudget = 2048;
        ServerSurveyCache.tickAll(deadline, 512);
        OPEN.values().removeIf(open -> !valid(open));
        var iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            var session = iterator.next();
            if (OPEN.get(session.open.player.getUUID()) != session.open || !valid(session.open)) { iterator.remove(); continue; }
            globalBudget -= session.pump(deadline, Math.min(globalBudget, 512));
            boolean done = session.completed == MapMath.total(session.open.radius);
            if (done || session.outgoing.size() >= 32 || (!session.outgoing.isEmpty() && session.open.context.level().getGameTime() % 4 == 0)) session.flush(done);
            if (done) iterator.remove();
            if (globalBudget <= 0 || System.nanoTime() >= deadline) break;
        }
        if (ACTIVE.size() > 1) {
            var first = ACTIVE.entrySet().iterator().next(); ACTIVE.remove(first.getKey()); ACTIVE.put(first.getKey(), first.getValue());
        }
        if (System.nanoTime() < deadline) ServerSurveyCache.tickAll(deadline, 512);
        if (event.getServer().getTickCount() % 100 == 0) ServerSurveyCache.flushFor(event.getServer());
    }
    public static void stopped(ServerStoppedEvent event) {
        ACTIVE.clear(); OPEN.clear(); RATE.reset(); ServerSurveyCache.closeFor(event.getServer());
    }
    public static void dataReload(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        var players = List.copyOf(OPEN.values()); ACTIVE.clear(); OPEN.clear();
        ServerSurveyCache.closeFor(event.getPlayerList().getServer());
        for (var open : players) open(new ScanOpenRequest(open.id, open.radius, false), open.player);
    }
    private static final class Open {
        final ServerPlayer player; final int id, radius; final boolean refresh;
        final ChunkPos center; final ServerSurveyCache.Dimension context;
        boolean started;
        Open(ServerPlayer player, int id, int radius, boolean refresh, ChunkPos center, ServerSurveyCache.Dimension context) {
            this.player = player; this.id = id; this.radius = radius; this.refresh = refresh; this.center = center; this.context = context;
        }
    }
    private static final class Session {
        final Open open;
        final List<ChunkPos> positions = new ArrayList<>();
        final List<CompletableFuture<ScanCell>> pending = new ArrayList<>();
        final List<ScanCell> outgoing = new ArrayList<>();
        int cursor, completed;
        Session(Open open, BitSet missing) {
            this.open = open; int side = open.radius * 2 + 1;
            for (int bit = missing.nextSetBit(0); bit >= 0; bit = missing.nextSetBit(bit + 1))
                positions.add(new ChunkPos(open.center.x - open.radius + bit % side, open.center.z - open.radius + bit / side));
            completed = MapMath.total(open.radius) - positions.size();
            positions.sort(Comparator.<ChunkPos>comparingInt(p -> Math.max(Math.abs(p.x - open.center.x), Math.abs(p.z - open.center.z)))
                    .thenComparingInt(p -> (p.x - open.center.x) * (p.x - open.center.x) + (p.z - open.center.z) * (p.z - open.center.z)));
        }
        int pump(long deadline, int budget) {
            int spent = 0;
            var iterator = pending.iterator();
            while (iterator.hasNext() && spent < budget && System.nanoTime() < deadline) {
                var result = iterator.next(); if (!result.isDone()) continue;
                outgoing.add(result.join()); iterator.remove(); completed++; spent++;
            }
            var cache = ServerSurveyCache.get(open.player.getServer());
            while (cursor < positions.size() && pending.size() < cache.perPlayerInFlight() && spent < budget && System.nanoTime() < deadline) {
                if (!cache.canAcceptMore()) break;
                if (!RATE.tryAcquire(System.nanoTime(), ProspectorConfig.serverRate())) break;
                var result = cache.resolve(open.context, positions.get(cursor++), open.refresh);
                if (result.isDone()) { outgoing.add(result.join()); completed++; }
                else pending.add(result);
                spent++;
            }
            return spent;
        }
        void flush(boolean done) {
            if (outgoing.isEmpty()) send(completed, done, List.of());
            for (int start = 0; start < outgoing.size(); start += 128) {
                int end = Math.min(start + 128, outgoing.size());
                send(completed - (outgoing.size() - end), done && end == outgoing.size(), List.copyOf(outgoing.subList(start, end)));
            }
            outgoing.clear();
            if (done) ServerSurveyCache.get(open.player.getServer()).flush();
        }
        void send(int progress, boolean done, List<ScanCell> batch) {
            PacketDistributor.sendToPlayer(open.player, new ScanBatch(open.id, open.context.id(), open.context.level().dimension().location(),
                    open.center.x, open.center.z, open.radius, progress, done, batch));
        }
    }
}
