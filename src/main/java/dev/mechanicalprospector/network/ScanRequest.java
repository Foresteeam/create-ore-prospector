package dev.mechanicalprospector.network;

import dev.mechanicalprospector.MechanicalProspector;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Row-major bitmask: one bit per needed chunk. Maximum request is about 2 KiB. */
public record ScanRequest(int requestId, UUID cacheId, int centerX, int centerZ, byte[] missingMask,
                          boolean cancel) implements CustomPacketPayload {
    public ScanRequest { missingMask = missingMask.clone(); if (missingMask.length > 2081) throw new IllegalArgumentException("Survey mask too large"); }
    @Override public byte[] missingMask() { return missingMask.clone(); }
    public static ScanRequest cancel(int id) { return new ScanRequest(id, new UUID(0, 0), 0, 0, new byte[0], true); }
    public static final Type<ScanRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MechanicalProspector.MOD_ID, "request"));
    public static final StreamCodec<FriendlyByteBuf, ScanRequest> CODEC = StreamCodec.of((buf, data) -> {
        buf.writeInt(data.requestId); buf.writeUUID(data.cacheId); buf.writeInt(data.centerX); buf.writeInt(data.centerZ);
        buf.writeByteArray(data.missingMask); buf.writeBoolean(data.cancel);
    }, buf -> new ScanRequest(buf.readInt(), buf.readUUID(), buf.readInt(), buf.readInt(), buf.readByteArray(2081), buf.readBoolean()));
    @Override public Type<ScanRequest> type() { return TYPE; }
}
