package dev.mechanicalprospector.network;

import dev.mechanicalprospector.MechanicalProspector;
import dev.mechanicalprospector.scan.ScanCell;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ScanBatch(int requestId, UUID cacheId, ResourceLocation dimension, int centerX, int centerZ,
                        int radius, int completed, boolean finished, List<ScanCell> cells) implements CustomPacketPayload {
    public ScanBatch(int requestId, ResourceLocation dimension, int centerX, int centerZ, int radius,
                     int completed, boolean finished, List<ScanCell> cells) {
        this(requestId, new UUID(0, 0), dimension, centerX, centerZ, radius, completed, finished, cells);
    }
    public static final Type<ScanBatch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MechanicalProspector.MOD_ID, "batch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScanBatch> CODEC = StreamCodec.of(ScanBatch::write, ScanBatch::read);
    private static void write(RegistryFriendlyByteBuf buf, ScanBatch data) {
        buf.writeInt(data.requestId); buf.writeUUID(data.cacheId); buf.writeResourceLocation(data.dimension);
        buf.writeInt(data.centerX); buf.writeInt(data.centerZ); buf.writeByte(data.radius);
        buf.writeVarInt(data.completed); buf.writeBoolean(data.finished); buf.writeVarInt(data.cells.size());
        data.cells.forEach(cell -> cell.write(buf));
    }
    private static ScanBatch read(RegistryFriendlyByteBuf buf) {
        int id = buf.readInt(); var cacheId = buf.readUUID(); var dimension = buf.readResourceLocation();
        int x = buf.readInt(), z = buf.readInt(), radius = buf.readUnsignedByte();
        int completed = buf.readVarInt(); boolean finished = buf.readBoolean(); int size = buf.readVarInt();
        if (size < 0 || size > 128 || radius > 64 || completed < 0 || completed > 16641)
            throw new IllegalArgumentException("Invalid survey batch");
        var cells = new ArrayList<ScanCell>(size);
        for (int i = 0; i < size; i++) cells.add(ScanCell.read(buf));
        return new ScanBatch(id, cacheId, dimension, x, z, radius, completed, finished, List.copyOf(cells));
    }
    @Override public Type<ScanBatch> type() { return TYPE; }
}
