package dev.mechanicalprospector.network;

import dev.mechanicalprospector.client.ProspectorClient;
import dev.mechanicalprospector.scan.ScanService;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ProspectorNetwork {
    private ProspectorNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(ScanOpenRequest.TYPE, ScanOpenRequest.CODEC,
                (data, context) -> ScanService.open(data, context.player()));
        registrar.playToServer(ScanRequest.TYPE, ScanRequest.CODEC,
                (data, context) -> ScanService.request(data, context.player()));
        registrar.playToClient(ScanBatch.TYPE, ScanBatch.CODEC,
                (data, context) -> ProspectorClient.receive(data));
        registrar.playToClient(ScanContext.TYPE, ScanContext.CODEC,
                (data, context) -> ProspectorClient.receive(data));
    }
}
