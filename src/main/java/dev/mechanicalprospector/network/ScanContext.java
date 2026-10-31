package dev.mechanicalprospector.network;

import dev.mechanicalprospector.MechanicalProspector;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public record ScanContext(int requestId, UUID cacheId, ResourceLocation dimension, int centerX, int centerZ,
                          int radius, boolean refresh) implements CustomPacketPayload {
    public static final Type<ScanContext> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MechanicalProspector.MOD_ID, "context"));
    public static final StreamCodec<FriendlyByteBuf, ScanContext> CODEC = StreamCodec.of((buf, data) -> {
        buf.writeInt(data.requestId); buf.writeUUID(data.cacheId); buf.writeResourceLocation(data.dimension);
        buf.writeInt(data.centerX); buf.writeInt(data.centerZ); buf.writeByte(data.radius); buf.writeBoolean(data.refresh);
    }, buf -> new ScanContext(buf.readInt(), buf.readUUID(), buf.readResourceLocation(), buf.readInt(), buf.readInt(), buf.readUnsignedByte(), buf.readBoolean()));
    @Override public Type<ScanContext> type() { return TYPE; }
}
