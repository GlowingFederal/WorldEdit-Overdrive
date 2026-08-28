package com.glowingfederal.worldeditoverdrive.backend;

import net.minecraft.nbt.NBTTagCompound;

/** Optional operation-owned history boundary; the writer supplies a defensive NBT copy. */
public interface OldTileSnapshotSink {
    OldTileSnapshotSink DISCARD = new OldTileSnapshotSink() {
        @Override public void accept(int x, int y, int z, NBTTagCompound oldNbt) { }
    };
    void accept(int x, int y, int z, NBTTagCompound oldNbt);
}
