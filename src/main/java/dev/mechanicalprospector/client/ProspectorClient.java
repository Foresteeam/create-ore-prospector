package dev.mechanicalprospector.client;

import dev.mechanicalprospector.network.ScanBatch;
import dev.mechanicalprospector.network.ScanContext;
import net.minecraft.client.Minecraft;

/** Kept separate from item and server code so dedicated servers never initialize client classes. */
public final class ProspectorClient {
    private static int sequence;
    private ProspectorClient() {}
    public static int nextRequestId() { return ++sequence; }
    public static void open() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player != null) minecraft.setScreen(new ProspectorScreen());
    }
    public static void receive(ScanBatch batch) {
        ClientSurveyCache.remember(batch);
        if (Minecraft.getInstance().screen instanceof ProspectorScreen screen) screen.receive(batch);
    }
    public static void receive(ScanContext context) {
        if (Minecraft.getInstance().screen instanceof ProspectorScreen screen) screen.receive(context);
    }
}
