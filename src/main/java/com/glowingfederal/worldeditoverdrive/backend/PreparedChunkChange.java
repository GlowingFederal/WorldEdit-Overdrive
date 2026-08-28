package com.glowingfederal.worldeditoverdrive.backend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;

/** Immutable ownership envelope produced without touching a live world. */
public final class PreparedChunkChange {
    public static final class TileData {
        private final int localX, y, localZ;
        private final NBTTagCompound nbt;

        private TileData(int localX, int y, int localZ, NBTTagCompound nbt) {
            this.localX = localX;
            this.y = y;
            this.localZ = localZ;
            this.nbt = nbt;
        }
        public int getLocalX() { return localX; }
        public int getY() { return y; }
        public int getLocalZ() { return localZ; }
        public NBTTagCompound copyNbt() { return (NBTTagCompound) nbt.copy(); }
    }

    public static final class Builder {
        private final int chunkX, chunkZ;
        private final SectionChange[] sections = new SectionChange[16];
        private final long[] columns = new long[4];
        private final List<TileData> tiles = new ArrayList<TileData>();
        private int[] biomes;
        private int changedBlocks;
        private boolean built;

        public Builder(int chunkX, int chunkZ) { this.chunkX = chunkX; this.chunkZ = chunkZ; }

        public Builder setBlock(int localX, int y, int localZ, int blockId, int metadata) {
            open();
            if (y < 0 || y > 255) throw new IllegalArgumentException("y must be 0..255: " + y);
            int sectionY = y >>> 4;
            SectionChange section = sections[sectionY];
            if (section == null) sections[sectionY] = section = new SectionChange();
            int before = section.getChangedCount();
            section.set(localX, y & 15, localZ, new BlockChange(blockId, metadata));
            if (section.getChangedCount() != before) changedBlocks++;
            markColumn(localX, localZ);
            return this;
        }

        public Builder setTileNbt(int localX, int y, int localZ, NBTTagCompound nbt) {
            open();
            checkPosition(localX, y, localZ);
            if (nbt == null) throw new NullPointerException("nbt");
            for (int i = 0; i < tiles.size(); i++) {
                TileData old = tiles.get(i);
                if (old.localX == localX && old.y == y && old.localZ == localZ) {
                    tiles.set(i, new TileData(localX, y, localZ, (NBTTagCompound) nbt.copy()));
                    return this;
                }
            }
            tiles.add(new TileData(localX, y, localZ, (NBTTagCompound) nbt.copy()));
            return this;
        }

        public Builder setBiome(int localX, int localZ, int biomeId) {
            open();
            if ((localX | localZ) < 0 || localX > 15 || localZ > 15) throw new IllegalArgumentException("local coordinates must be 0..15");
            if (biomeId < 0 || biomeId > 255) throw new IllegalArgumentException("biome ID must be 0..255");
            if (biomes == null) { biomes = new int[256]; Arrays.fill(biomes, -1); }
            biomes[localZ << 4 | localX] = biomeId;
            return this;
        }

        public PreparedChunkChange build() {
            open();
            built = true;
            return new PreparedChunkChange(chunkX, chunkZ, sections, columns, biomes,
                    Collections.unmodifiableList(new ArrayList<TileData>(tiles)), changedBlocks);
        }

        private void markColumn(int x, int z) {
            if ((x | z) < 0 || x > 15 || z > 15) throw new IllegalArgumentException("local coordinates must be 0..15");
            int index = z << 4 | x;
            columns[index >>> 6] |= 1L << (index & 63);
        }
        private void open() { if (built) throw new IllegalStateException("builder ownership was transferred by build()"); }
    }

    private final int chunkX, chunkZ, changedBlocks;
    private final SectionChange[] sections;
    private final long[] columns;
    private final int[] biomes;
    private final List<TileData> tiles;

    private PreparedChunkChange(int chunkX, int chunkZ, SectionChange[] sections, long[] columns,
            int[] biomes, List<TileData> tiles, int changedBlocks) {
        this.chunkX = chunkX; this.chunkZ = chunkZ; this.sections = sections;
        this.columns = columns; this.biomes = biomes; this.tiles = tiles; this.changedBlocks = changedBlocks;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public int getChangedBlockCount() { return changedBlocks; }
    public SectionChange getSection(int sectionY) { return sections[sectionY]; }
    public List<TileData> getTiles() { return tiles; }
    public boolean isColumnAffected(int localX, int localZ) {
        int index = localZ << 4 | localX;
        return (columns[index >>> 6] & 1L << (index & 63)) != 0;
    }
    public int getBiome(int localX, int localZ) { return biomes == null ? -1 : biomes[localZ << 4 | localX]; }

    /** Vanilla 1.7.10 S22 coordinate encoding, in deterministic section/index order. */
    public short[] changedPositions() {
        final short[] positions = new short[changedBlocks];
        final int[] cursor = new int[1];
        for (int sectionY = 0; sectionY < sections.length; sectionY++) {
            SectionChange section = sections[sectionY];
            if (section == null) continue;
            final int baseY = sectionY << 4;
            section.forEach(new SectionChange.Visitor() {
                @Override public void visit(int index, int packedState) {
                    positions[cursor[0]++] = (short) (SectionChange.localX(index) << 12
                            | SectionChange.localZ(index) << 8 | baseY | SectionChange.localY(index));
                }
            });
        }
        return positions;
    }

    public long estimatedBytes() {
        long bytes = 160 + columns.length * 8L + (biomes == null ? 0 : biomes.length * 4L);
        for (SectionChange section : sections) if (section != null) bytes += section.estimatedBytes();
        // NBT is variable; serialized-size traversal would be expensive. This conservative floor
        // is supplemented by each tag's textual size to keep Stage 3 accounting lightweight.
        for (TileData tile : tiles) bytes += 64L + tile.nbt.toString().length() * 2L;
        return bytes;
    }

    private static void checkPosition(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 255 || z > 15) throw new IllegalArgumentException("position outside chunk");
    }
}
