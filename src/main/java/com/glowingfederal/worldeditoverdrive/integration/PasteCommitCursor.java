package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;

/** Shared paste/replay placement cursor. A long dependency walk can yield at
 * safe placement boundaries; adjacent door halves retain their two-call atomic
 * placement even when a page or deadline ends between them. */
final class PasteCommitCursor implements AutoCloseable {
    PasteStreamStorage.Batch batch;
    private PasteDiskJournal.Record pair;
    private int offset;
    long changed,tiles,placements;
    boolean pressure;
    void accept(PasteStreamStorage.Batch batch){if(this.batch!=null)throw new IllegalStateException("commit batch still owned");this.batch=batch;offset=0;}
    long remaining(){return (pair==null?0:1)+(batch==null?0:batch.records.size()-offset);}
    void apply(Extent extent,long deadline)throws WorldEditException{
        placements=0;boolean force=false;
        if(pair!=null&&offset<batch.records.size()&&System.nanoTime()<deadline){place(extent,pair);pair=null;force=true;}
        while(offset<batch.records.size()&&(force||System.nanoTime()<deadline)){
            PasteDiskJournal.Record r=batch.records.get(offset);
            if(!force&&r.pairWithNext&&offset+1==batch.records.size()){
                pair=r;batch.records.remove(offset);break;
            }
            offset++;place(extent,r);force=!force&&r.pairWithNext;
        }
    }
    private void place(Extent extent,PasteDiskJournal.Record r)throws WorldEditException{
        long start=System.nanoTime();
        try{if(extent.setBlock(r.position(),r.block)){changed++;if(r.block.getNbtData()!=null)tiles++;}placements++;
            long nanos=System.nanoTime()-start;
            if(nanos>PasteHookStatus.maxDownstreamMutationNanos.get()){PasteHookStatus.maxDownstreamMutationNanos.set(nanos);PasteHookStatus.maxDownstreamMutationDestinationChunk=(r.x>>4)+","+(r.z>>4);PasteHookStatus.maxDownstreamMutationDetail="stream block "+r.block.getId()+":"+r.block.getData();}
        }finally{r.release();}
    }
    boolean consumed(){return offset==batch.records.size();}
    boolean finishBatch(){
        if(!consumed())throw new IllegalStateException("unconsumed commit batch");boolean done=batch.done&&pair==null;pressure=batch.pressure;batch.close();batch=null;return done;
    }
    public void close(){if(batch!=null){batch.close();batch=null;}if(pair!=null){pair.release();pair=null;}}
}
