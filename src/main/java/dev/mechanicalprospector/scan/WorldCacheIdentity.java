package dev.mechanicalprospector.scan;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.UUID;

/** A world-owned UUID prevents caches from leaking between two saves with the same seed or name. */
public final class WorldCacheIdentity extends SavedData {
    private static final String FILE_ID = "mechanical_prospector_cache_identity";
    private static final Factory<WorldCacheIdentity> FACTORY = new Factory<>(WorldCacheIdentity::new, WorldCacheIdentity::load);
    private final UUID id;
    private WorldCacheIdentity() { id = UUID.randomUUID(); setDirty(); }
    private WorldCacheIdentity(UUID id) { this.id = id; }
    private static WorldCacheIdentity load(CompoundTag tag, HolderLookup.Provider registries) {
        return tag.hasUUID("id") ? new WorldCacheIdentity(tag.getUUID("id")) : new WorldCacheIdentity();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { tag.putUUID("id", id); return tag; }
    public static UUID get(MinecraftServer server) {
        var level = server.overworld();
        var identity = level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
        if (identity.isDirty()) {
            // Save the identifier before any cache files can outlive an ungraceful server shutdown.
            var folder = server.getWorldPath(LevelResource.ROOT).resolve("data");
            try { Files.createDirectories(folder); }
            catch (Exception error) { throw new IllegalStateException("Cannot create world cache identity", error); }
            identity.save(folder.resolve(FILE_ID + ".dat").toFile(), level.registryAccess());
        }
        return identity.id;
    }
}
