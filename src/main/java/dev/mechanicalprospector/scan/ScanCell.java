package dev.mechanicalprospector.scan;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;

public record ScanCell(int x, int z, byte kind, byte source, String veinId, Component name,
                       ItemStack icon, long remaining, boolean cached) {
    public static final byte EMPTY = 0, FINITE = 1, INFINITE = 2, DEPLETED = 3, UNAVAILABLE = 4;
    public static final byte LIVE = 0, SAVED = 1, SURVEY = 2;

    public ScanCell(int x, int z, byte kind, byte source, String veinId, Component name, ItemStack icon, long remaining) {
        this(x, z, kind, source, veinId, name, icon, remaining, false);
    }
    public ScanCell withCached(boolean value) { return new ScanCell(x, z, kind, source, veinId, name, icon, remaining, value); }

    public static ScanCell empty(int x, int z, byte source) {
        return new ScanCell(x, z, EMPTY, source, "", Component.empty(), ItemStack.EMPTY, 0);
    }
    public static ScanCell unavailable(int x, int z) {
        return new ScanCell(x, z, UNAVAILABLE, SURVEY, "", Component.empty(), ItemStack.EMPTY, 0);
    }
    public boolean hasVein() { return kind == FINITE || kind == INFINITE || kind == DEPLETED; }
    public static byte kindFor(long resources) {
        return resources < 0 ? DEPLETED : resources == 0 ? INFINITE : FINITE;
    }
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeInt(x); buf.writeInt(z); buf.writeByte(kind); buf.writeByte(source); buf.writeBoolean(cached);
        if (hasVein()) {
            buf.writeUtf(veinId, 256);
            ComponentSerialization.STREAM_CODEC.encode(buf, name);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, icon);
            buf.writeLong(remaining);
        }
    }
    public static ScanCell read(RegistryFriendlyByteBuf buf) {
        int x = buf.readInt(), z = buf.readInt();
        byte kind = buf.readByte(), source = buf.readByte(); boolean cached = buf.readBoolean();
        if (kind < 0 || kind > UNAVAILABLE || source < 0 || source > SURVEY)
            throw new IllegalArgumentException("Invalid survey cell");
        if (kind == FINITE || kind == INFINITE || kind == DEPLETED)
            return new ScanCell(x, z, kind, source, buf.readUtf(256),
                    ComponentSerialization.STREAM_CODEC.decode(buf), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readLong(), cached);
        return (kind == EMPTY ? empty(x, z, source) : unavailable(x, z)).withCached(cached);
    }
}
