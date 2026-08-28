package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A single operation-owned chunk envelope with non-duplicated phase partitions. */
public final class PreparedOperationChunk {
    public interface OrderedPlacement { void commit() throws Exception; }
    public static final class PhasePartition {
        final int phase; final PreparedChunkChange chunkChange; final List<OrderedPlacement> ordered;
        PhasePartition(int phase,PreparedChunkChange chunkChange,List<OrderedPlacement> ordered){this.phase=phase;this.chunkChange=chunkChange;
            this.ordered=ordered==null?Collections.<OrderedPlacement>emptyList():Collections.unmodifiableList(new ArrayList<OrderedPlacement>(ordered));}
        public int getPhase(){return phase;} public PreparedChunkChange getChunkChange(){return chunkChange;}
        public List<OrderedPlacement> getOrderedPlacements(){return ordered;}
        public long estimatedBytes(){return chunkChange==null?ordered.size()*16L:chunkChange.estimatedBytes();}
    }
    private final int chunkX,chunkZ; private final List<PhasePartition> partitions;
    private PreparedOperationChunk(int x,int z,List<PhasePartition> p){chunkX=x;chunkZ=z;partitions=Collections.unmodifiableList(p);}
    public int getChunkX(){return chunkX;} public int getChunkZ(){return chunkZ;} public List<PhasePartition> getPartitions(){return partitions;}
    public long estimatedBytes(){long n=0;for(PhasePartition p:partitions)n+=p.estimatedBytes();return n;}
    public static Builder builder(int x,int z){return new Builder(x,z);}
    public static final class Builder { private final int x,z;private final List<PhasePartition> p=new ArrayList<PhasePartition>();
        Builder(int x,int z){this.x=x;this.z=z;} public Builder chunkPhase(int phase,PreparedChunkChange c){if(c==null)throw new NullPointerException("change");p.add(new PhasePartition(phase,c,null));return this;}
        public Builder orderedPhase(int phase,List<OrderedPlacement> entries){p.add(new PhasePartition(phase,null,entries));return this;}
        public PreparedOperationChunk build(){return new PreparedOperationChunk(x,z,new ArrayList<PhasePartition>(p));}}
}
