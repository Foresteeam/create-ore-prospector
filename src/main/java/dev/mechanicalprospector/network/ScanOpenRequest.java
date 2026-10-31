package dev.mechanicalprospector.network;

import dev.mechanicalprospector.MechanicalProspector;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Every open obtains the server's world/dimension cache identity before client data is reused. */
public record ScanOpenRequest(int requestId, int radius, boolean refresh) implements CustomPacketPayload {
    public static final Type<ScanOpenRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MechanicalProspector.MOD_ID, "open"));
    public static final StreamCodec<FriendlyByteBuf, ScanOpenRequest> CODEC = StreamCodec.of(
            (buf, data) -> { buf.writeInt(data.requestId); buf.writeByte(data.radius); buf.writeBoolean(data.refresh); },
            buf -> new ScanOpenRequest(buf.readInt(), buf.readUnsignedByte(), buf.readBoolean()));
    @Override public Type<ScanOpenRequest> type() { return TYPE; }
}
