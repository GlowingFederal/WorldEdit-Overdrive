package com.glowingfederal.worldeditoverdrive.backend;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Server-thread-only Forge 1.7.10 commit engine. It never schedules work. */
public final class ForgeChunkWriter {
    private final RawMutationClassifier classifier;

    public ForgeChunkWriter() { this(new ConservativeRawMutationClassifier()); }
    public ForgeChunkWriter(RawMutationClassifier classifier) {
        if (classifier == null) throw new NullPointerException("classifier");
        this.classifier = classifier;
    }

    public ChunkCommitResult commit(WorldServer world, PreparedChunkChange change, SideEffectPolicy policy) {
        return commit(world, change, policy, OldTileSnapshotSink.DISCARD);
    }

    public ChunkCommitResult commit(WorldServer world, PreparedChunkChange change, SideEffectPolicy policy,
            OldTileSnapshotSink oldTiles) {
        if (world == null || change == null || policy == null) throw new NullPointerException("commit argument");
        if (oldTiles == null) throw new NullPointerException("oldTiles");
        assertServerThread();

        Chunk chunk = world.getChunkFromChunkCoords(change.getChunkX(), change.getChunkZ());
        ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        int[] rawSectionMask = new int[1];
        int[] applicationCounts = new int[2]; // raw, native
        int touchedSections = 0, denseSections = 0, sectionMask = 0;
        int biomeChanges = 0, affectedColumns = 0;
        int[] changedTiles = new int[1];

        // A failure escapes this method. Consequently no partial operation is ever reported as success.
        for (int sectionY = 0; sectionY < 16; sectionY++) {
            SectionChange section = change.getSection(sectionY);
            if (section == null) continue;
            touchedSections++;
            if (section.isDense()) denseSections++;
            sectionMask |= 1 << sectionY;
            commitSection(world, chunk, storage, change, sectionY, section, policy,
                    rawSectionMask, applicationCounts, oldTiles, changedTiles);
        }

        // Vanilla recalculation is intentionally limited to sections touched through direct arrays.
        for (int sectionY = 0; sectionY < 16; sectionY++) {
            if ((rawSectionMask[0] & 1 << sectionY) == 0) continue;
            ExtendedBlockStorage section = storage[sectionY];
            if (section != null) {
                section.removeInvalidBlocks();
                if (section.isEmpty()) storage[sectionY] = null;
            }
        }

        for (PreparedChunkChange.TileData tile : change.getTiles()) {
            installTile(world, change, tile);
            changedTiles[0]++;
        }
        byte[] biomeArray = chunk.getBiomeArray();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int biome = change.getBiome(x, z);
            if (biome >= 0 && (biomeArray[z << 4 | x] & 255) != biome) {
                biomeArray[z << 4 | x] = (byte) biome;
                biomeChanges++;
            }
            if (change.isColumnAffected(x, z)) affectedColumns++;
        }

        // generateSkylightMap uses Block#getLightOpacity(world,x,y,z), so removals can lower
        // height and modded contextual opacity remains authoritative. It also refreshes live light state.
        if (affectedColumns != 0) chunk.generateSkylightMap();
        chunk.setChunkModified();
        chunk.sendUpdates = true;
        return new ChunkCommitResult(change.getChangedBlockCount(), touchedSections, affectedColumns,
                changedTiles[0], biomeChanges, denseSections, sectionMask,
                affectedColumns == 0 ? 0 : sectionMask, applicationCounts[0], applicationCounts[1],
                change.estimatedBytes());
    }

    private void commitSection(final WorldServer world, final Chunk chunk, final ExtendedBlockStorage[] storage,
            final PreparedChunkChange change, final int sectionY, SectionChange changes,
            final SideEffectPolicy policy, final int[] rawSectionMask, final int[] applicationCounts,
            final OldTileSnapshotSink oldTiles, final int[] changedTiles) {
        changes.forEach(new SectionChange.Visitor() {
            @Override public void visit(int index, int packedState) {
                int localX = SectionChange.localX(index), localY = SectionChange.localY(index), localZ = SectionChange.localZ(index);
                int y = sectionY << 4 | localY;
                int x = change.getChunkX() << 4 | localX;
                int z = change.getChunkZ() << 4 | localZ;
                BlockChange state = BlockChange.unpack(packedState);
                Block next = Block.getBlockById(state.getBlockId());
                if (next == null) throw new IllegalArgumentException("unregistered block ID " + state.getBlockId());
                Block old = chunk.getBlock(localX, y, localZ);
                int oldMetadata = chunk.getBlockMetadata(localX, y, localZ);

                if (removeOldTile(world, x, y, z, oldTiles)) changedTiles[0]++;
                boolean raw = policy.getPlacement() == SideEffectPolicy.Placement.RAW_STORAGE
                        && classifier.isRawSafe(world, x, y, z, old, oldMetadata, next, state.getMetadata());
                if (raw) {
                    writeRaw(storage, sectionY, localX, localY, localZ, next, state.getMetadata(), !world.provider.hasNoSky);
                    rawSectionMask[0] |= 1 << sectionY;
                    applicationCounts[0]++;
                } else if (!world.setBlock(x, y, z, next, state.getMetadata(), 2)) {
                    Block actual = world.getBlock(x, y, z);
                    int actualMetadata = world.getBlockMetadata(x, y, z);
                    if (actual != next || actualMetadata != state.getMetadata()) {
                        throw new IllegalStateException("native placement rejected at " + x + ',' + y + ',' + z);
                    }
                    applicationCounts[1]++;
                } else {
                    applicationCounts[1]++;
                }
                if (next.hasTileEntity(state.getMetadata()) && tileAt(change, localX, y, localZ) == null) {
                    installDefaultTile(world, x, y, z, next, state.getMetadata());
                }
            }
        });
    }

    private static void writeRaw(ExtendedBlockStorage[] storage, int sectionY, int x, int y, int z,
            Block block, int metadata, boolean sky) {
        ExtendedBlockStorage section = storage[sectionY];
        if (section == null) storage[sectionY] = section = new ExtendedBlockStorage(sectionY << 4, sky);
        int id = Block.getIdFromBlock(block);
        int index = SectionChange.index(x, y, z);
        section.getBlockLSBArray()[index] = (byte) id;
        section.getMetadataArray().set(x, y, z, metadata);
        NibbleArray msb = section.getBlockMSBArray();
        int high = id >>> 8;
        if (high != 0 && msb == null) {
            msb = new NibbleArray(4096, 4);
            section.setBlockMSBArray(msb);
        }
        if (msb != null) msb.set(x, y, z, high);
    }

    private static boolean removeOldTile(WorldServer world, int x, int y, int z, OldTileSnapshotSink sink) {
        TileEntity old = world.getTileEntity(x, y, z);
        if (old != null) {
            NBTTagCompound oldNbt = new NBTTagCompound();
            old.writeToNBT(oldNbt);
            sink.accept(x, y, z, (NBTTagCompound) oldNbt.copy());
            old.invalidate();
            world.removeTileEntity(x, y, z);
            return true;
        }
        return false;
    }

    private static void installTile(WorldServer world, PreparedChunkChange change, PreparedChunkChange.TileData data) {
        int x = change.getChunkX() << 4 | data.getLocalX();
        int z = change.getChunkZ() << 4 | data.getLocalZ();
        Block block = world.getBlock(x, data.getY(), z);
        int metadata = world.getBlockMetadata(x, data.getY(), z);
        if (!block.hasTileEntity(metadata)) throw new IllegalStateException("NBT supplied for non-tile block at " + x + ',' + data.getY() + ',' + z);
        TileEntity tile = block.createTileEntity(world, metadata);
        if (tile == null) throw new IllegalStateException("tile-capable block returned no tile at " + x + ',' + data.getY() + ',' + z);
        NBTTagCompound nbt = data.copyNbt();
        nbt.setInteger("x", x); nbt.setInteger("y", data.getY()); nbt.setInteger("z", z);
        NBTTagCompound expected = new NBTTagCompound();
        tile.writeToNBT(expected);
        if (nbt.hasKey("id") && expected.hasKey("id") && !nbt.getString("id").equals(expected.getString("id"))) {
            throw new IllegalArgumentException("tile NBT type " + nbt.getString("id") + " does not match " + expected.getString("id"));
        }
        // A changed block's old tile was already captured and removed in the section loop.
        removeOldTile(world, x, data.getY(), z, OldTileSnapshotSink.DISCARD);
        tile.readFromNBT(nbt);
        world.setTileEntity(x, data.getY(), z, tile);
        tile.validate();
        tile.markDirty();
    }

    private static void installDefaultTile(WorldServer world, int x, int y, int z, Block block, int metadata) {
        TileEntity tile = block.createTileEntity(world, metadata);
        if (tile == null) throw new IllegalStateException("tile-capable block returned no tile at " + x + ',' + y + ',' + z);
        world.setTileEntity(x, y, z, tile);
        tile.validate();
        tile.markDirty();
    }

    private static PreparedChunkChange.TileData tileAt(PreparedChunkChange change, int x, int y, int z) {
        for (PreparedChunkChange.TileData tile : change.getTiles())
            if (tile.getLocalX() == x && tile.getY() == y && tile.getLocalZ() == z) return tile;
        return null;
    }

    private static void assertServerThread() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || Thread.currentThread() != server.getServerThread()) {
            throw new IllegalStateException("ForgeChunkWriter.commit must run on the Minecraft server thread");
        }
    }
}
