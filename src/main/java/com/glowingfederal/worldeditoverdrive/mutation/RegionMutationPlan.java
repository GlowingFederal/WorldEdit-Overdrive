package com.glowingfederal.worldeditoverdrive.mutation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Generic worker-produced plan. Adapters share this representation and commit machinery. */
public final class RegionMutationPlan {
    public enum Ordering { ORDER_INDEPENDENT, ORDER_CONSTRAINED }
    private final List<ChunkMutationBatch> batches; private final Ordering ordering; private final int mutationCount;
    public RegionMutationPlan(List<ChunkMutationBatch> batches,Ordering ordering){this.batches=Collections.unmodifiableList(new ArrayList<ChunkMutationBatch>(batches));this.ordering=ordering;int count=0;for(ChunkMutationBatch batch:batches)count+=batch.size();mutationCount=count;}
    public List<ChunkMutationBatch> getBatches(){return batches;} public Ordering getOrdering(){return ordering;} public int size(){return mutationCount;}
}
