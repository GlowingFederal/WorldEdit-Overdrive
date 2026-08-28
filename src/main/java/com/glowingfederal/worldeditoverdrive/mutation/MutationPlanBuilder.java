package com.glowingfederal.worldeditoverdrive.mutation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds chunk-local plans without losing stable order inside each chunk. */
public final class MutationPlanBuilder {
    public interface Coordinates { int x(int index); int z(int index); }
    public static RegionMutationPlan chunkLocal(int[] indices,Coordinates coordinates){
        Map<Long,List<Integer>> grouped=new LinkedHashMap<Long,List<Integer>>();
        for(int index:indices){int cx=coordinates.x(index)>>4,cz=coordinates.z(index)>>4;long key=((long)cx<<32)^(cz&0xffffffffL);List<Integer> values=grouped.get(Long.valueOf(key));if(values==null){values=new ArrayList<Integer>();grouped.put(Long.valueOf(key),values);}values.add(Integer.valueOf(index));}
        List<ChunkMutationBatch> batches=new ArrayList<ChunkMutationBatch>(grouped.size());
        for(Map.Entry<Long,List<Integer>> entry:grouped.entrySet()){long key=entry.getKey().longValue();List<Integer> values=entry.getValue();int[] local=new int[values.size()];for(int i=0;i<local.length;i++)local[i]=values.get(i).intValue();batches.add(new ChunkMutationBatch((int)(key>>32),(int)key,local));}
        return new RegionMutationPlan(batches,RegionMutationPlan.Ordering.ORDER_INDEPENDENT);
    }
    private MutationPlanBuilder(){}
}
