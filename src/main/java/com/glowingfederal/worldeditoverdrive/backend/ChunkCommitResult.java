package com.glowingfederal.worldeditoverdrive.backend;

/** Compact Stage-3 handoff; packet density is an estimate and no packets are sent here. */
public final class ChunkCommitResult {
    private final int changedBlocks, touchedSections, affectedColumns, changedTiles, biomeChanges, denseSections;
    private final int sectionMask;
    private final long estimatedBufferBytes;

    ChunkCommitResult(int changedBlocks, int touchedSections, int affectedColumns, int changedTiles,
            int biomeChanges, int denseSections, int sectionMask, long estimatedBufferBytes) {
        this.changedBlocks = changedBlocks; this.touchedSections = touchedSections;
        this.affectedColumns = affectedColumns; this.changedTiles = changedTiles;
        this.biomeChanges = biomeChanges; this.denseSections = denseSections;
        this.sectionMask = sectionMask; this.estimatedBufferBytes = estimatedBufferBytes;
    }
    public int getChangedBlocks() { return changedBlocks; }
    public int getTouchedSections() { return touchedSections; }
    public int getAffectedColumns() { return affectedColumns; }
    public int getChangedTiles() { return changedTiles; }
    public int getBiomeChanges() { return biomeChanges; }
    public int getDenseSections() { return denseSections; }
    public int getSectionMask() { return sectionMask; }
    public long getEstimatedBufferBytes() { return estimatedBufferBytes; }
    public boolean suggestsFullChunkPacket() { return changedBlocks >= 2048 || denseSections >= 4; }
}
