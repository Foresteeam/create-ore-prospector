package dev.mechanicalprospector.client;

import dev.mechanicalprospector.MechanicalProspector;
import dev.mechanicalprospector.ProspectorConfig;
import dev.mechanicalprospector.cache.CachedCell;
import dev.mechanicalprospector.cache.RegionDiskCache;
import dev.mechanicalprospector.scan.ScanRateLimiter;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import dev.mechanicalprospector.network.ScanBatch;
import dev.mechanicalprospector.network.ScanContext;
import dev.mechanicalprospector.network.ScanOpenRequest;
import dev.mechanicalprospector.network.ScanRequest;
import dev.mechanicalprospector.cache.ClientCachePlan;
import dev.mechanicalprospector.scan.MapMath;
import dev.mechanicalprospector.scan.ScanCell;
import dev.mechanicalprospector.scan.ScanService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Collections;

public final class ProspectorScreen extends Screen {
    private static final int INK = 0xff111e23, PANEL = 0xff1c2a2e, BRASS = 0xffbe9b5c;
    private static final int TEXT = 0xffeee4cc, MUTED = 0xff9daea9;
    private static int lastRadius = 32;
    private static final ScanRateLimiter CLIENT_RATE = new ScanRateLimiter();
    private final ArrayDeque<ScanCell> pendingServer = new ArrayDeque<>();
    private Iterator<Map.Entry<RegionDiskCache.Position, CachedCell>> pendingLocal = Collections.emptyIterator();
    private boolean localReady, serverFinished;
    private MapTexture mapTexture;
    private boolean mapDirty = true;
    private long lastMapUpdate;
    private double rasterPlayerX = Double.NaN, rasterPlayerZ = Double.NaN;
    private int rasterPhase = -1;
    private final Long2ObjectOpenHashMap<ScanCell> cells = new Long2ObjectOpenHashMap<>();
    private final Map<String, Legend> legend = new HashMap<>();
    private final LongOpenHashSet localCells = new LongOpenHashSet();
    private List<Legend> visibleLegend = List.of();
    private int radius = lastRadius, viewRadius = lastRadius, requestId, completed, ticks, requestTick, centerX, centerZ, legendScroll;
    private int left, top, panelWidth, panelHeight, mapX, mapY, mapSize, sideX, sideWidth, legendY, legendRows;
    private boolean finished, received;
    private boolean loadingLocal;
    private UUID cacheId;
    private ScanContext currentContext;
    private String filter = "";
    private Long selected;
    private ResourceLocation dimension;
    private Button rangeButton, refreshButton, allButton;
    private EditBox search;

    public ProspectorScreen() { super(tr("title")); }
    private static Component tr(String key, Object... args) { return Component.translatable("gui.create_ore_prospector." + key, args); }
    private static String number(long value) { return NumberFormat.getIntegerInstance(Locale.ROOT).format(value); }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 12, 664); panelHeight = Math.min(height - 12, 432);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        sideWidth = Math.min(170, Math.max(115, panelWidth / 3));
        int mapArea = panelWidth - sideWidth - 42;
        mapSize = Math.min(panelHeight - 119, mapArea);
        mapX = left + 18 + (mapArea - mapSize) / 2; mapY = top + 65;
        sideX = left + panelWidth - sideWidth - 12;
        legendY = mapY + 61; legendRows = Math.max(1, (mapSize - 63) / 20);
        rangeButton = addRenderableWidget(Button.builder(tr("range", radius * 2 + 1), b -> {
            radius = radius == 8 ? 16 : radius == 16 ? 32 : radius == 32 ? 64 : 8;
            viewRadius = radius;
            lastRadius = radius; b.setMessage(tr("range", radius * 2 + 1)); requestScan();
        }).bounds(left + 12, top + 32, 115, 20).build());
        rangeButton.setTooltip(Tooltip.create(tr("range_hint")));
        refreshButton = addRenderableWidget(Button.builder(tr("refresh"), b -> requestScan(true))
                .bounds(left + 133, top + 32, 70, 20).build());
        refreshButton.setTooltip(Tooltip.create(tr("refresh_hint")));
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose())
                .bounds(left + panelWidth - 27, top + 7, 18, 18).build());
        allButton = addRenderableWidget(Button.builder(tr("all"), b -> { filter = ""; mapDirty = true; updateLegend(); })
                .bounds(sideX, mapY + 35, sideWidth, 18).build());
        search = new EditBox(font, sideX, mapY + 13, sideWidth, 18, tr("search"));
        search.setMaxLength(64); search.setHint(tr("search").copy().withStyle(ChatFormatting.GRAY));
        search.setResponder(value -> { legendScroll = 0; updateLegend(); });
        addRenderableWidget(search);
        if (requestId == 0) requestScan(); // Resizing preserves the current survey and filter.
        mapDirty = true;
    }

    private void requestScan() { requestScan(false); }
    private void requestScan(boolean refresh) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return;
        requestId = ProspectorClient.nextRequestId();
        completed = 0; finished = false; received = false; cells.clear(); legend.clear(); visibleLegend = List.of();
        localCells.clear(); cacheId = null; currentContext = null; loadingLocal = false;
        pendingLocal = Collections.emptyIterator(); pendingServer.clear(); localReady = false; serverFinished = false; mapDirty = true;
        selected = null; legendScroll = 0; requestTick = ticks;
        centerX = minecraft.player.chunkPosition().x; centerZ = minecraft.player.chunkPosition().z;
        dimension = minecraft.level.dimension().location();
        PacketDistributor.sendToServer(new ScanOpenRequest(requestId, radius, refresh));
    }

    public void receive(ScanContext context) {
        if (context.requestId() != requestId || minecraft == null || minecraft.level == null
                || !context.dimension().equals(minecraft.level.dimension().location())) return;
        currentContext = context; cacheId = context.cacheId(); received = true; loadingLocal = true;
        centerX = context.centerX(); centerZ = context.centerZ(); radius = context.radius(); dimension = context.dimension();
        cells.clear(); legend.clear(); localCells.clear(); completed = 0; finished = false;
        pendingLocal = Collections.emptyIterator(); pendingServer.clear(); localReady = false; serverFinished = false; mapDirty = true;
        var connection = minecraft.getConnection();
        ClientSurveyCache.load(context).whenComplete((saved, error) -> minecraft.execute(() -> {
            if (minecraft.screen != this || currentContext != context || minecraft.getConnection() != connection
                    || minecraft.level == null || !context.dimension().equals(minecraft.level.dimension().location())) return;
            if (error != null) MechanicalProspector.LOGGER.warn("Could not read local survey cache; requesting server data", error);
            else pendingLocal = saved.entrySet().iterator();
            localReady = true;
        }));
    }

    public void receive(ScanBatch batch) {
        if (batch.requestId() != requestId || !batch.cacheId().equals(cacheId) || minecraft == null || minecraft.level == null
                || !batch.dimension().equals(minecraft.level.dimension().location())) return;
        centerX = batch.centerX(); centerZ = batch.centerZ(); radius = batch.radius();
        dimension = batch.dimension(); received = true; serverFinished |= batch.finished();
        pendingServer.addAll(batch.cells());
    }

    private void putCell(ScanCell cell, boolean local) {
            long key = MapMath.key(cell.x(), cell.z());
            var previous = cells.put(key, cell);
            if (local) localCells.add(key); else localCells.remove(key);
            if (previous != null && previous.hasVein()) {
                var old = legend.get(previous.veinId()); if (old != null) old.count--;
            }
            if (cell.hasVein()) legend.computeIfAbsent(cell.veinId(), ignored -> new Legend(cell)).count++;
            mapDirty = true;
    }

    private void updateLegend() {
        String query = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).strip();
        visibleLegend = legend.values().stream().filter(v -> v.count > 0 && (query.isEmpty()
                        || v.cell.name().getString().toLowerCase(Locale.ROOT).contains(query)
                        || v.cell.veinId().toLowerCase(Locale.ROOT).contains(query)))
                .sorted(Comparator.comparing(v -> v.cell.name().getString())).toList();
        legendScroll = Mth.clamp(legendScroll, 0, Math.max(0, visibleLegend.size() - legendRows));
    }

    @Override
    public void tick() {
        ticks++;
        if (minecraft == null || minecraft.player == null || !ScanService.holding(minecraft.player)) { onClose(); return; }
        if (ticks - requestTick >= 20 && minecraft.level != null &&
                (!minecraft.level.dimension().location().equals(dimension)
                        || minecraft.player.chunkPosition().x != centerX || minecraft.player.chunkPosition().z != centerZ)) requestScan();
        if (!received && ticks - requestTick >= 100) requestScan();
        processPending();
        rangeButton.active = ticks - requestTick >= 12;
        refreshButton.active = ticks - requestTick >= 12;
    }

    private void processPending() {
        long deadline = System.nanoTime() + 2_000_000L;
        boolean changed = false;
        while ((pendingLocal.hasNext() || !pendingServer.isEmpty()) && System.nanoTime() < deadline) {
            if (!CLIENT_RATE.tryAcquire(System.nanoTime(), ProspectorConfig.clientRate())) break;
            if (pendingLocal.hasNext()) {
                var entry = pendingLocal.next(); var pos = entry.getKey();
                var cell = entry.getValue().resolve(pos.x(), pos.z(), minecraft.level.getRecipeManager());
                if (cell != null) putCell(cell, true);
            } else putCell(pendingServer.removeFirst(), false);
            changed = true;
        }
        if (loadingLocal && localReady && !pendingLocal.hasNext()) {
            loadingLocal = false;
            var missing = ClientCachePlan.missing(centerX, centerZ, radius, cells);
            PacketDistributor.sendToServer(new ScanRequest(requestId, cacheId, centerX, centerZ, missing.toByteArray(), false));
        }
        completed = cells.size();
        finished = !loadingLocal && (completed == MapMath.total(radius) || (serverFinished && pendingServer.isEmpty()));
        if (changed) updateLegend();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xc00b1013);
        g.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        outline(g, left, top, panelWidth, panelHeight, BRASS);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + 27, 0xff28383a);
        g.renderItem(MechanicalProspector.PROSPECTOR.toStack(), left + 11, top + 7);
        g.drawString(font, title, left + 34, top + 11, TEXT, false);
        int span = (viewRadius * 2 + 1) * 16;
        if (panelWidth > 440) g.drawString(font, localCells.isEmpty() ? tr("power") : tr("local_hits", number(localCells.size())), left + 218, top + 39, 0xff90c9b2, false);
        drawMap(g, mouseX, mouseY);
        drawLegend(g, mouseX, mouseY);
        int footerY = top + panelHeight - 29;
        g.fill(left + 12, footerY - 5, left + panelWidth - 12, footerY - 4, 0xff43514b);
        int percentage = completed * 100 / MapMath.total(radius);
        g.drawString(font, finished ? tr("complete", number(completed)) : tr("scanning", percentage, completed, MapMath.total(radius)),
                left + 13, footerY, finished ? 0xff92d1b0 : 0xffe4c584, false);
        String rangeText = span + " × " + span + " m";
        g.drawString(font, rangeText, left + panelWidth - font.width(rangeText) - 13, footerY, MUTED, false);
        g.drawString(font, tr("help"), left + 13, footerY + 13, MUTED, false);
        super.render(g, mouseX, mouseY, partialTick);
        var hovered = hoveredCell(mouseX, mouseY);
        if (insideMap(mouseX, mouseY)) {
            var coords = hoveredChunk(mouseX, mouseY);
            drawCellTooltip(g, hovered, coords[0], coords[1], mouseX, mouseY);
        }
    }

    private void drawMap(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(mapX - 3, mapY - 3, mapX + mapSize + 3, mapY + mapSize + 3, 0xff0a1217);
        outline(g, mapX - 4, mapY - 4, mapSize + 8, mapSize + 8, BRASS);
        g.drawCenteredString(font, tr("north"), mapX + mapSize / 2, mapY - 12, TEXT);
        double scale = mapSize / (double) (viewRadius * 2 + 1);
        double playerX = minecraft.player.getX(), playerZ = minecraft.player.getZ();
        double cx = mapX + mapSize / 2.0, cy = mapY + mapSize / 2.0;
        g.enableScissor(mapX, mapY, mapX + mapSize, mapY + mapSize);
        long now = System.nanoTime();
        if (mapTexture == null || mapTexture.raster.size() != mapSize) {
            if (mapTexture != null) mapTexture.close();
            mapTexture = new MapTexture(mapSize); mapDirty = true; lastMapUpdate = 0;
        }
        boolean moved = Math.abs(playerX - rasterPlayerX) * scale / 16 >= 0.5
                || Math.abs(playerZ - rasterPlayerZ) * scale / 16 >= 0.5;
        if ((mapDirty || moved || (!finished && rasterPhase != ticks / 12)) && now - lastMapUpdate >= 100_000_000L) {
            rebuildMap(playerX, playerZ, scale);
            rasterPlayerX = playerX; rasterPlayerZ = playerZ; rasterPhase = ticks / 12;
            mapDirty = false; lastMapUpdate = now;
        }
        mapTexture.draw(g, mapX, mapY);
        if (selected != null) drawChunkOutline(g, (int)(long)selected, (int)(selected >> 32), playerX, playerZ, cx, cy, scale, 0xfff1ce7c);
        if (insideMap(mouseX, mouseY)) {
            int[] chunk = hoveredChunk(mouseX, mouseY);
            drawChunkOutline(g, chunk[0], chunk[1], playerX, playerZ, cx, cy, scale, 0xfff7edcf);
        }
        // The map is north-up. Minecraft yaw zero points south, hence the half turn.
        double angle = Math.toRadians(minecraft.player.getYRot() + 180);
        arrow(g, cx, cy, angle, 8, 0xfff6e6c7);
        arrow(g, cx, cy, angle, 6, 0xfff04f51);
        g.disableScissor();
        String location = tr("position", minecraft.player.chunkPosition().x, minecraft.player.chunkPosition().z).getString();
        g.drawCenteredString(font, font.plainSubstrByWidth(location, mapSize + 20), mapX + mapSize / 2, mapY + mapSize + 7, MUTED);
    }

    private void drawChunkOutline(GuiGraphics g, int x, int z, double playerX, double playerZ, double cx, double cy, double scale, int color) {
        int x1 = (int)Math.floor(MapMath.chunkEdge(x, playerX, cx, scale));
        int y1 = (int)Math.floor(MapMath.chunkEdge(z, playerZ, cy, scale));
        int x2 = (int)Math.floor(MapMath.chunkEdge(x + 1, playerX, cx, scale));
        int y2 = (int)Math.floor(MapMath.chunkEdge(z + 1, playerZ, cy, scale));
        outline(g, x1, y1, x2 - x1 + 1, y2 - y1 + 1, color);
    }

    private void rebuildMap(double playerX, double playerZ, double scale) {
        var raster = mapTexture.raster; raster.clear(INK);
        double cx = mapSize / 2.0, cy = mapSize / 2.0;
        for (int z = centerZ - viewRadius - 1; z <= centerZ + viewRadius + 1; z++) for (int x = centerX - viewRadius - 1; x <= centerX + viewRadius + 1; x++) {
            int x1 = (int) Math.floor(MapMath.chunkEdge(x, playerX, cx, scale));
            int y1 = (int) Math.floor(MapMath.chunkEdge(z, playerZ, cy, scale));
            int x2 = (int) Math.floor(MapMath.chunkEdge(x + 1, playerX, cx, scale));
            int y2 = (int) Math.floor(MapMath.chunkEdge(z + 1, playerZ, cy, scale));
            var cell = cells.get(MapMath.key(x, z));
            int color = ((x + z) & 1) == 0 ? 0xff1a2c31 : 0xff203238;
            if (cell == null) color = ((x + z + ticks / 12) & 3) == 0 ? 0xff2a3b40 : 0xff17242a;
            else if (cell.kind() == ScanCell.UNAVAILABLE) color = 0xff49383b;
            else if (cell.hasVein()) {
                boolean visible = (filter.isEmpty() || filter.equals(cell.veinId()));
                color = VeinColors.mix(VeinColors.color(cell), INK,
                        visible ? cell.kind() == ScanCell.DEPLETED ? 0.34 : 0.88 : 0.12);
            }
            int gap = scale >= 4 ? 1 : 0;
            raster.fill(x1 + gap, y1 + gap, x2, y2, color);
            if (cell != null && cell.kind() == ScanCell.DEPLETED && scale >= 5)
                raster.fill(x1 + 1, (y1 + y2) / 2, x2, (y1 + y2) / 2 + 1, 0xff7c5854);
            if (cell != null && cell.kind() == ScanCell.INFINITE && scale >= 7)
                raster.fill(x2 - 3, y1 + 2, x2 - 1, y1 + 4, 0xfff9f0da);
        }
        mapTexture.upload();
    }

    private void drawLegend(GuiGraphics g, int mouseX, int mouseY) {
        int count = legend.values().stream().mapToInt(v -> v.count).sum();
        g.drawString(font, tr("legend", count, legend.size()), sideX, mapY - 2, TEXT, false);
        allButton.setMessage(filter.isEmpty() ? tr("all") : tr("clear_filter"));
        allButton.active = !filter.isEmpty();
        for (int i = 0; i < legendRows && i + legendScroll < visibleLegend.size(); i++) {
            var entry = visibleLegend.get(i + legendScroll); int y = legendY + i * 20;
            boolean hover = mouseX >= sideX && mouseX < sideX + sideWidth && mouseY >= y && mouseY < y + 19;
            boolean active = entry.cell.veinId().equals(filter);
            g.fill(sideX, y, sideX + sideWidth, y + 19, active ? 0xff40534a : hover ? 0xff354448 : 0xff233438);
            g.fill(sideX + 1, y + 2, sideX + 4, y + 17, VeinColors.color(entry.cell));
            g.renderItem(entry.cell.icon(), sideX + 7, y + 1);
            String amount = String.valueOf(entry.count);
            String label = font.plainSubstrByWidth(entry.cell.name().getString(), sideWidth - font.width(amount) - 34);
            g.drawString(font, label, sideX + 26, y + 5, TEXT, false);
            g.drawString(font, amount, sideX + sideWidth - font.width(amount) - 4, y + 5, MUTED, false);
            if (hover) g.renderComponentTooltip(font, List.of(entry.cell.name(), tr("filter_hint").copy().withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
        }
        if (visibleLegend.isEmpty()) g.drawString(font, finished ? tr("no_matches") : tr("waiting"), sideX + 3, legendY + 6, MUTED, false);
        if (visibleLegend.size() > legendRows) {
            g.drawString(font, tr("scroll", legendScroll + 1, Math.min(legendScroll + legendRows, visibleLegend.size()), visibleLegend.size()),
                    sideX, mapY + mapSize + 7, MUTED, false);
        }
    }

    private void drawCellTooltip(GuiGraphics g, ScanCell cell, int x, int z, int mouseX, int mouseY) {
        var lines = new ArrayList<Component>();
        lines.add(tr("chunk", x, z).copy().withStyle(ChatFormatting.GOLD));
        if (cell == null) lines.add(tr(finished ? "outside" : "pending").copy().withStyle(ChatFormatting.GRAY));
        else if (cell.kind() == ScanCell.UNAVAILABLE) lines.add(tr("unavailable").copy().withStyle(ChatFormatting.RED));
        else if (!cell.hasVein()) lines.add(tr("empty").copy().withStyle(ChatFormatting.GRAY));
        else {
            lines.add(cell.name().copy().withColor(VeinColors.color(cell) & 0xffffff));
            Component reserve = cell.kind() == ScanCell.INFINITE ? tr("infinite")
                    : cell.kind() == ScanCell.DEPLETED ? tr("depleted") : tr(cell.source() == ScanCell.SURVEY ? "initial" : "remaining", number(cell.remaining()));
            lines.add(reserve.copy().withStyle(cell.kind() == ScanCell.DEPLETED ? ChatFormatting.RED : ChatFormatting.AQUA));
        }
        lines.add(tr("blocks", x * 16, x * 16 + 15, z * 16, z * 16 + 15).copy().withStyle(ChatFormatting.DARK_GRAY));
        if (cell != null && cell.kind() != ScanCell.UNAVAILABLE) {
            String sourceKey = localCells.contains(MapMath.key(x, z)) ? "local_cached" : cell.cached() ? "server_cached" : "source_" + cell.source();
            lines.add(tr(sourceKey).copy().withStyle(ChatFormatting.GRAY));
            if (cell.source() == ScanCell.SURVEY) lines.add(tr("survey_hint").copy().withStyle(ChatFormatting.DARK_GRAY));
        }
        if (cell != null && cell.hasVein() && minecraft.options.advancedItemTooltips)
            lines.add(Component.literal(cell.veinId()).withStyle(ChatFormatting.DARK_GRAY));
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private boolean insideMap(double x, double y) { return x >= mapX && x < mapX + mapSize && y >= mapY && y < mapY + mapSize; }
    private int[] hoveredChunk(double x, double y) {
        double scale = mapSize / (double) (viewRadius * 2 + 1);
        return new int[]{MapMath.chunkAt(minecraft.player.getX(), x - mapX - mapSize / 2.0, scale),
                MapMath.chunkAt(minecraft.player.getZ(), y - mapY - mapSize / 2.0, scale)};
    }
    private ScanCell hoveredCell(double x, double y) {
        if (!insideMap(x, y)) return null;
        var chunk = hoveredChunk(x, y); return cells.get(MapMath.key(chunk[0], chunk[1]));
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && insideMap(x, y)) {
            var chunk = hoveredChunk(x, y); selected = MapMath.key(chunk[0], chunk[1]); return true;
        }
        if (button == 0 && x >= sideX && x < sideX + sideWidth && y >= legendY && y < legendY + legendRows * 20) {
            int index = (int) (y - legendY) / 20 + legendScroll;
            if (index < visibleLegend.size()) {
                String id = visibleLegend.get(index).cell.veinId(); filter = filter.equals(id) ? "" : id; mapDirty = true; return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (insideMap(x, y) && vertical != 0) {
            viewRadius = vertical > 0 ? Math.max(8, viewRadius / 2) : Math.min(radius, viewRadius * 2);
            mapDirty = true;
            return true;
        }
        if (x >= sideX && x < sideX + sideWidth && y >= legendY && y < mapY + mapSize) {
            legendScroll = Mth.clamp(legendScroll - (int) Math.signum(vertical), 0, Math.max(0, visibleLegend.size() - legendRows)); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void onClose() {
        if (minecraft != null && minecraft.getConnection() != null)
            PacketDistributor.sendToServer(ScanRequest.cancel(requestId));
        ClientSurveyCache.flush();
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        if (mapTexture != null) { mapTexture.close(); mapTexture = null; }
        super.removed();
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) { }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color); g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color); g.fill(x + w - 1, y, x + w, y + h, color);
    }
    private static void arrow(GuiGraphics g, double cx, double cy, double angle, double size, int color) {
        double[][] shape = {{0, -size}, {-size * 0.64, size * 0.75}, {0, size * 0.38}, {size * 0.64, size * 0.75}};
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (var point : shape) {
            double x = point[0], y = point[1];
            point[0] = cx + x * Math.cos(angle) - y * Math.sin(angle);
            point[1] = cy + x * Math.sin(angle) + y * Math.cos(angle);
            minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
        }
        for (int y = (int) Math.floor(minY); y <= Math.ceil(maxY); y++) {
            var cuts = new ArrayList<Double>(4);
            for (int i = 0; i < shape.length; i++) {
                var a = shape[i]; var b = shape[(i + 1) % shape.length];
                if ((a[1] <= y + 0.5 && b[1] > y + 0.5) || (b[1] <= y + 0.5 && a[1] > y + 0.5))
                    cuts.add(a[0] + (y + 0.5 - a[1]) * (b[0] - a[0]) / (b[1] - a[1]));
            }
            cuts.sort(Double::compare);
            for (int i = 0; i + 1 < cuts.size(); i += 2)
                g.fill((int) Math.floor(cuts.get(i)), y, (int) Math.ceil(cuts.get(i + 1)), y + 1, color);
        }
    }
    private static final class Legend {
        final ScanCell cell; int count;
        Legend(ScanCell cell) { this.cell = cell; }
    }
}
