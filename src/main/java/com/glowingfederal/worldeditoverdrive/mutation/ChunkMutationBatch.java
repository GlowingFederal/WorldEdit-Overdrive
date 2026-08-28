package com.glowingfederal.worldeditoverdrive.mutation;

/** Immutable, chunk-local slice of a mutation plan. */
public final class ChunkMutationBatch {
    private final int chunkX,chunkZ; private final int[] sourceIndices;
    public ChunkMutationBatch(int chunkX,int chunkZ,int[] sourceIndices){this.chunkX=chunkX;this.chunkZ=chunkZ;this.sourceIndices=sourceIndices.clone();}
    public int getChunkX(){return chunkX;} public int getChunkZ(){return chunkZ;}
    public int size(){return sourceIndices.length;} public int sourceIndex(int index){return sourceIndices[index];}
}
