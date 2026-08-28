package com.glowingfederal.worldeditoverdrive.backend;

/** Immutable, numeric 1.7.10 block state. ID zero is explicit air. */
public final class BlockChange {
    private final int blockId;
    private final int metadata;

    public BlockChange(int blockId, int metadata) {
        if (blockId < 0 || blockId > 4095) {
            throw new IllegalArgumentException("1.7.10 block ID must be between 0 and 4095: " + blockId);
        }
        if (metadata < 0 || metadata > 15) {
            throw new IllegalArgumentException("metadata must be between 0 and 15: " + metadata);
        }
        this.blockId = blockId;
        this.metadata = metadata;
    }

    public int getBlockId() { return blockId; }
    public int getMetadata() { return metadata; }
    public int packed() { return blockId | metadata << 12; }

    static BlockChange unpack(int packed) {
        return new BlockChange(packed & 4095, packed >>> 12 & 15);
    }
}
