package dev.mechanicalprospector;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ProspectorConfig {
    public static final ModConfigSpec CLIENT_SPEC, SERVER_SPEC;
    public static final ModConfigSpec.IntValue CLIENT_MAX_SCAN_CHUNKS_PER_SECOND, SERVER_MAX_SCAN_CHUNKS_PER_SECOND;
    public static final ModConfigSpec.IntValue SERVER_PREDICTION_THREADS;
    static {
        var client = new ModConfigSpec.Builder();
        client.comment("Client performance / 客户端性能").push("client");
        CLIENT_MAX_SCAN_CHUNKS_PER_SECOND = client.comment(
                "Maximum cached/received chunk records resolved and added to the UI per second.",
                "每秒最多处理的本地缓存及服务端区块记录数量；降低可减少加载期间的主线程开销。")
                .defineInRange("maxScanChunksPerSecond", 2048, 1, 65536);
        client.pop(); CLIENT_SPEC = client.build();
        var server = new ModConfigSpec.Builder();
        server.comment("Server performance / 服务端性能").push("server");
        SERVER_MAX_SCAN_CHUNKS_PER_SECOND = server.comment(
                "Global maximum chunk queries started per second, shared by ALL players (cache hits included).",
                "服务端所有玩家共享的每秒最大扫描区块数量，包含缓存命中；降低可减少服务器负担。")
                .defineInRange("maxScanChunksPerSecond", 2048, 1, 65536);
        SERVER_PREDICTION_THREADS = server.comment(
                "CPU prediction workers. 0 = automatic (leave two logical CPUs free, at most 8). Restart server to apply.",
                "矿脉预测线程数；0 为自动（预留两个逻辑核心，最多 8 线程），1 为单工作线程；服务器重启生效。")
                .defineInRange("predictionThreads", 0, 0, 32);
        server.pop(); SERVER_SPEC = server.build();
    }
    private ProspectorConfig() {}
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, "create_ore_prospector-client.toml");
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, "create_ore_prospector-server.toml");
    }
    public static int clientRate() { return CLIENT_SPEC.isLoaded() ? CLIENT_MAX_SCAN_CHUNKS_PER_SECOND.get() : 2048; }
    public static int serverRate() { return SERVER_SPEC.isLoaded() ? SERVER_MAX_SCAN_CHUNKS_PER_SECOND.get() : 2048; }
    public static int predictionThreads() {
        int configured = SERVER_SPEC.isLoaded() ? SERVER_PREDICTION_THREADS.get() : 0;
        return configured == 0 ? Math.clamp(Runtime.getRuntime().availableProcessors() - 2, 1, 8) : configured;
    }
}
