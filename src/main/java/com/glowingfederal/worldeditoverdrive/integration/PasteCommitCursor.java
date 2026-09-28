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
    private net.minecraft.world.World world;
    private long chunkTick=Long.MIN_VALUE;private int chunkLoads;boolean chunkLoadYield;
    void beginTick(long tick){if(chunkTick!=tick){chunkTick=tick;chunkLoads=0;}chunkLoadYield=false;}
    void world(com.sk89q.worldedit.world.World world){this.world=world instanceof com.sk89q.worldedit.forge.ForgeWorld?((com.sk89q.worldedit.forge.ForgeWorld)world).getWorld():null;}
    void accept(PasteStreamStorage.Batch batch){if(this.batch!=null)throw new IllegalStateException("commit batch still owned");this.batch=batch;offset=0;}
    long remaining(){return (pair==null?0:1)+(batch==null?0:batch.records.size()-offset);}
    void apply(Extent extent,long deadline)throws WorldEditException{
        apply(extent,deadline,deadline,null);
    }
    void apply(Extent extent,long deadline,long hardDeadline,PasteSliceBudget pace)throws WorldEditException{
        placements=0;boolean force=false;
        if(pair!=null&&batch.done&&batch.records.isEmpty())throw new IllegalStateException("missing paired door half");
        if(pair!=null&&offset<batch.records.size()&&canStart(deadline,hardDeadline,pace,2)){place(extent,pair,pace);pair=null;force=true;}
        if(pair!=null)return;
        while(offset<batch.records.size()&&(force||System.nanoTime()<deadline)){
            PasteDiskJournal.Record r=batch.records.get(offset);
            if(!force&&world!=null&&!world.getChunkProvider().chunkExists(r.x>>4,r.z>>4)&&chunkLoads>=2){chunkLoadYield=true;break;}
            if(!force&&!canStart(deadline,hardDeadline,pace,r.pairWithNext?2:1))break;
            if(!force&&r.pairWithNext&&offset+1==batch.records.size()){
                pair=r;batch.records.remove(offset);break;
            }
            offset++;place(extent,r,pace);force=!force&&r.pairWithNext;
        }
    }
    private boolean canStart(long deadline,long hardDeadline,PasteSliceBudget pace,int calls){long now=System.nanoTime();return pace==null?now<deadline:pace.canStartUnit(now,deadline,hardDeadline,calls);}
    private void place(Extent extent,PasteDiskJournal.Record r,PasteSliceBudget pace)throws WorldEditException{
        boolean load=world!=null&&!world.getChunkProvider().chunkExists(r.x>>4,r.z>>4);
        if(load)chunkLoads++;
        long start=System.nanoTime();
        try{if(extent.setBlock(r.position(),r.block)){changed++;if(r.block.getNbtData()!=null)tiles++;}placements++;
            long nanos=System.nanoTime()-start;
            if(nanos>PasteHookStatus.maxDownstreamMutationNanos.get()){PasteHookStatus.maxDownstreamMutationNanos.set(nanos);PasteHookStatus.maxDownstreamMutationDestinationChunk=(r.x>>4)+","+(r.z>>4);PasteHookStatus.maxDownstreamMutationDetail="stream block "+r.block.getId()+":"+r.block.getData();}
        }finally{if(pace!=null)pace.recordMutation(System.nanoTime()-start,load,r.block.getNbtData()!=null);r.release();}
    }
    boolean consumed(){return offset==batch.records.size();}
    boolean finishBatch(){
        if(!consumed())throw new IllegalStateException("unconsumed commit batch");boolean done=batch.done&&pair==null;pressure=batch.pressure;batch.close();batch=null;return done;
    }
    public void close(){if(batch!=null){batch.close();batch=null;}if(pair!=null){pair.release();pair=null;}}
}
