package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import com.glowingfederal.worldeditoverdrive.mutation.ChunkMutationBatch;
import com.glowingfederal.worldeditoverdrive.execution.AdaptiveServerBudget;
import com.glowingfederal.worldeditoverdrive.mutation.MutationPlanBuilder;
import com.glowingfederal.worldeditoverdrive.mutation.RegionMutationPlan;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.entity.ExtentEntityCopy;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.Location;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Owns deferred compatibility traversal and full standard PasteBuilder acceleration. */
public final class DeferredPasteManager {
    private static final long GLOBAL=128L<<20,PER_OPERATION=64L<<20;
    private static final AdaptiveServerBudget BUDGET=new AdaptiveServerBudget();
    private static final Queue<Owner> OWNERS=new ArrayDeque<Owner>();
    private static final ExecutorService WORKERS=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),new ThreadFactory(){private final AtomicLong sequence=new AtomicLong();public Thread newThread(Runnable task){Thread thread=new Thread(task,"worldedit-overdrive-paste-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;}});
    private static final AtomicLong RETAINED=new AtomicLong();
    public static synchronized void register(ForwardExtentCopy operation,PasteOperationAdapter adapter,Player player,LocalSession session,boolean selectPasted)throws Exception{if(operation==null||adapter==null||player==null||session==null)throw new NullPointerException("deferred paste context");OWNERS.add(new Owner(operation,adapter,player,session,session.getClipboard(),selectPasted));PasteHookStatus.pasteDeferredActive.incrementAndGet();}
    public static void tick(long normalTickNanos){long started=System.nanoTime(),deadline=started+BUDGET.beginTick(normalTickNanos);Owner[] snapshot;synchronized(DeferredPasteManager.class){snapshot=OWNERS.toArray(new Owner[OWNERS.size()]);}for(Owner owner:snapshot){if(System.nanoTime()>=deadline)break;try{if(owner.tick(deadline))remove(owner,true,null);else rotate(owner);}catch(Throwable failure){remove(owner,false,failure);}}BUDGET.endTick(System.nanoTime()-started);}
    private static synchronized void rotate(Owner owner){if(OWNERS.remove(owner))OWNERS.add(owner);}
    private static void remove(Owner owner,boolean success,Throwable failure){synchronized(DeferredPasteManager.class){if(!OWNERS.remove(owner))return;}owner.release();if(owner.commitActive)PasteHookStatus.pasteCommitActive.decrementAndGet();PasteHookStatus.pasteDeferredActive.decrementAndGet();if(success)PasteHookStatus.pasteDeferredCompleted.incrementAndGet();else{PasteHookStatus.pasteDeferredFailed.incrementAndGet();PasteHookStatus.lastPasteDeferredReason="failed: "+failure;owner.player.printError("Paste failed: "+failure.getMessage());OverdriveLog.error("deferred paste failed: {}",failure.toString());}}

    private static final class Owner implements MutationOperationOwner {
        final ForwardExtentCopy original;final PasteOperationAdapter adapter;final Player player;final LocalSession session;final ClipboardHolder holder;final boolean select;final PasteContinuationOperation lifecycle=new PasteContinuationOperation();
        final long operationStarted=System.nanoTime(),snapshotStarted=operationStarted;final int minX,minY,minZ,sizeX,sizeY,sizeZ,volume;
        volatile RegionMutationPlan plan;volatile Throwable planningFailure;PreparedClipboardView prepared;int[] ids,data,dx,dy,dz;Map<Integer,BaseBlock> auxiliary;CaptureExtent entityCapture;List<? extends Entity> sourceEntities;
        boolean vanilla,mutation,commitActive,blocksFlushed,captureInitialized,blocksCaptured,entitiesListed;int captureIndex,entityCaptureCursor,batchCursor,batchOffset,entityCursor,adaptiveBatch=256;long reserved,commitNanos,snapshotActiveNanos,commitStarted,submittedTotal;
        Owner(ForwardExtentCopy original,PasteOperationAdapter adapter,Player player,LocalSession session,ClipboardHolder holder,boolean select)throws Exception{
            this.original=original;this.adapter=adapter;this.player=player;this.session=session;this.holder=holder;this.select=select;
            Vector min=adapter.clipboard.getMinimumPoint(),max=adapter.clipboard.getMaximumPoint();minX=min.getBlockX();minY=min.getBlockY();minZ=min.getBlockZ();sizeX=max.getBlockX()-minX+1;sizeY=max.getBlockY()-minY+1;sizeZ=max.getBlockZ()-minZ+1;
            long volumeLong=(long)sizeX*sizeY*sizeZ;volume=volumeLong>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)volumeLong;
            PasteOperationAdapter.Eligibility eligible=adapter.accelerationEligibility();if(eligible.kind!=PasteOperationAdapter.Eligibility.Kind.ACCELERATE)throw new IllegalArgumentException(eligible.reason);
            long estimate=128L+volumeLong*32L;if(volumeLong>Integer.MAX_VALUE||estimate>PER_OPERATION||!reserve(estimate))throw new IllegalStateException("accelerated paste memory admission rejected: "+estimate);reserved=estimate;
            PasteHookStatus.activePhase="SNAPSHOTTING";PasteHookStatus.snapshotProcessed.set(0);PasteHookStatus.snapshotTotalEstimate.set(volumeLong);PasteHookStatus.commitRemaining.set(volumeLong);
            PasteHookStatus.lastOperationSnapshotActiveMillis.set(0);PasteHookStatus.lastOperationMaxServerSliceMillis.set(0);PasteHookStatus.lastOperationWallMillis.set(0);
            PasteHookStatus.lastPasteTransform=adapter.transform.getClass().getName();PasteHookStatus.lastPasteIgnoreAir=adapter.ignoreAir;
            PasteHookStatus.lastPasteDeferredReason="accelerated paste admitted for incremental capture";
        }
        boolean resizeReservation(long wanted){if(wanted<=reserved){RETAINED.addAndGet(wanted-reserved);reserved=wanted;return true;}long extra=wanted-reserved;if(!reserve(extra))return false;reserved=wanted;return true;}
        void defer(String reason){vanilla=true;PasteHookStatus.pasteAccelerationFallbacks.incrementAndGet();PasteHookStatus.lastPasteAccelerationFallbackReason=reason;PasteHookStatus.lastPasteDeferredReason="deferred vanilla: "+reason;}
        public boolean tick(long globalDeadline)throws Exception{
            long sliceStarted=System.nanoTime();
            try {
                if(vanilla)throw new IllegalStateException("deferred owner cannot execute an unbounded vanilla traversal");
                if(prepared==null){captureUntil(globalDeadline);return false;}
                if(planningFailure!=null)throw new Exception("accelerated planning failed",planningFailure);
                RegionMutationPlan ready=plan;if(ready==null){PasteHookStatus.activePhase="PLANNING";return false;}if(!commitActive){lifecycle.committing();commitActive=true;commitStarted=System.nanoTime();PasteHookStatus.activePhase="COMMITTING";PasteHookStatus.pasteCommitActive.incrementAndGet();}
                long tickStarted=System.nanoTime();int submitted=0,changed=0,tiles=0,loadedChunks=0,lastChunk=Integer.MIN_VALUE;
                while(batchCursor<ready.getBatches().size()&&submitted<adaptiveBatch&&System.nanoTime()<globalDeadline){ChunkMutationBatch batch=ready.getBatches().get(batchCursor);if(batchOffset==batch.size()){batchCursor++;batchOffset=0;continue;}int i=batch.sourceIndex(batchOffset);int chunk=(prepared.destinationX(i)>>4)*31+(prepared.destinationZ(i)>>4);if(chunk!=lastChunk&&loadedChunks>=2)break;if(chunk!=lastChunk){lastChunk=chunk;loadedChunks++;}batchOffset++;BaseBlock desired=prepared.blockAt(i);Vector position=new Vector(prepared.destinationX(i),prepared.destinationY(i),prepared.destinationZ(i));BaseBlock existing=adapter.destination.getBlock(position);if(desired.getNbtData()==null&&existing.getId()==desired.getId()&&existing.getData()==desired.getData())continue;if(adapter.destination.setBlock(position,desired))changed++;mutation=true;submitted++;if(desired.getNbtData()!=null)tiles++;}
                if(submitted!=0){submittedTotal+=submitted;PasteHookStatus.pasteSubmittedBlocks.addAndGet(submitted);PasteHookStatus.pasteCommittedBlocks.addAndGet(changed);PasteHookStatus.pasteCommittedTiles.addAndGet(tiles);}
                long elapsed=System.nanoTime()-tickStarted;commitNanos+=elapsed;PasteHookStatus.lastOperationCommitActiveMillis.set(commitNanos/1000000L);PasteHookStatus.lastPasteCommitMillis.set(commitNanos/1000000L);
                if(submitted>0){long target=Math.max(1000000L,BUDGET.budgetNanos());long projected=(long)adaptiveBatch*target/Math.max(1L,elapsed);if(elapsed>target)adaptiveBatch=Math.max(32,adaptiveBatch/2);else adaptiveBatch=(int)Math.max(32L,Math.min(4096L,(adaptiveBatch*3L+projected)/4L));}
                PasteHookStatus.commitRemaining.set(Math.max(0L,volume-submittedTotal));
                if(batchCursor==ready.getBatches().size()&&!blocksFlushed){PasteHookStatus.activePhase="FINALIZING";adapter.destination.flushQueue();blocksFlushed=true;}
                int entities=0;while(blocksFlushed&&entityCursor<prepared.entities().size()&&entities<16&&System.nanoTime()<globalDeadline){PreparedClipboardView.EntitySnapshot entity=prepared.entities().get(entityCursor++);if(adapter.destination.createEntity(new Location(adapter.destination,entity.location.toVector(),entity.location.getYaw(),entity.location.getPitch()),new BaseEntity(entity.state))!=null)PasteHookStatus.pasteCommittedEntities.incrementAndGet();mutation=true;entities++;}
                PasteHookStatus.lastOperationCommitWallMillis.set((System.nanoTime()-commitStarted)/1000000L);if(batchCursor<ready.getBatches().size()||entityCursor<prepared.entities().size())return false;
                adapter.destination.flushQueue();finish();commitActive=false;PasteHookStatus.pasteCommitActive.decrementAndGet();lifecycle.complete();PasteHookStatus.pasteAccelerated.incrementAndGet();PasteHookStatus.activePhase="IDLE";PasteHookStatus.lastOperationWallMillis.set((System.nanoTime()-operationStarted)/1000000L);return true;
            } finally {long slice=System.nanoTime()-sliceStarted;updateMax(PasteHookStatus.lastOperationMaxServerSliceMillis,slice/1000000L);}
        }
        void captureUntil(long deadline)throws Exception{
            long active=System.nanoTime();PasteHookStatus.activePhase="SNAPSHOTTING";
            if(!captureInitialized){ids=new int[volume];data=new int[volume];dx=new int[volume];dy=new int[volume];dz=new int[volume];auxiliary=new HashMap<Integer,BaseBlock>();entityCapture=new CaptureExtent();captureInitialized=true;}
            while(!blocksCaptured&&captureIndex<volume&&System.nanoTime()<deadline){int i=captureIndex++;int x=minX+i%sizeX;int q=i/sizeX;int z=minZ+q%sizeZ;int y=minY+q/sizeZ;Vector source=new Vector(x,y,z);BaseBlock block=adapter.transformedSource.getBlock(source);ids[i]=block.getId();data[i]=block.getData();if(block.getNbtData()!=null||block.getClass()!=BaseBlock.class)auxiliary.put(Integer.valueOf(i),new BaseBlock(block));Vector destination=adapter.transform.apply(source.subtract(adapter.sourceOrigin)).add(adapter.destinationOrigin);dx[i]=destination.getBlockX();dy[i]=destination.getBlockY();dz[i]=destination.getBlockZ();}
            blocksCaptured=captureIndex==volume;PasteHookStatus.snapshotProcessed.set(captureIndex);if(!blocksCaptured){snapshotAccounting(active);return;}
            if(!entitiesListed){sourceEntities=adapter.clipboard.getEntities(adapter.region);entitiesListed=true;PasteHookStatus.snapshotTotalEstimate.set((long)volume+sourceEntities.size());}
            while(entityCaptureCursor<sourceEntities.size()&&System.nanoTime()<deadline){ExtentEntityCopy copy=new ExtentEntityCopy(adapter.sourceOrigin,entityCapture,adapter.destinationOrigin,adapter.transform);copy.apply(sourceEntities.get(entityCaptureCursor++));PasteHookStatus.snapshotProcessed.set((long)volume+entityCaptureCursor);}
            if(entityCaptureCursor<sourceEntities.size()){snapshotAccounting(active);return;}
            prepared=new PreparedClipboardView(minX,minY,minZ,sizeX,sizeY,sizeZ,ids,data,dx,dy,dz,auxiliary,entityCapture.snapshots);long actual=prepared.estimatedBytes()+volume*4L;if(actual>PER_OPERATION||!resizeReservation(actual))throw new IllegalStateException("accelerated paste retained data exceeds memory limit: "+actual);
            PasteHookStatus.pastePreparedBlocks.addAndGet(volume);PasteHookStatus.pastePreparedTiles.addAndGet(prepared.tileCount());PasteHookStatus.pastePreparedEntities.addAndGet(prepared.entities().size());if(!adapter.transform.isIdentity())PasteHookStatus.pasteTransformedBlocks.addAndGet(volume);
            snapshotAccounting(active);PasteHookStatus.lastOperationSnapshotWallMillis.set((System.nanoTime()-snapshotStarted)/1000000L);PasteHookStatus.lastPastePrepareMillis.set(PasteHookStatus.lastOperationSnapshotWallMillis.get());PasteHookStatus.activePhase="PLANNING";lifecycle.submitted();PasteHookStatus.pastePlanningActive.incrementAndGet();PasteHookStatus.pasteWorkerTasksSubmitted.incrementAndGet();PasteHookStatus.workerQueuedChunks.incrementAndGet();WORKERS.execute(new Planner(this,prepared,adapter.ignoreAir));
        }
        void snapshotAccounting(long started){snapshotActiveNanos+=System.nanoTime()-started;PasteHookStatus.lastOperationSnapshotActiveMillis.set(snapshotActiveNanos/1000000L);}
        void finish(){session.remember(adapter.destination);Vector to=adapter.destinationOrigin;if(select){Vector max=to.add(adapter.region.getMaximumPoint().subtract(adapter.region.getMinimumPoint()));RegionSelector selector=new CuboidRegionSelector(player.getWorld(),to,max);session.setRegionSelector(player.getWorld(),selector);selector.learnChanges();selector.explainRegionAdjust(player,session);}player.print("The clipboard has been pasted at "+to);}
        public MutationOperationOwner.Phase phase(){if(vanilla)return MutationOperationOwner.Phase.COMMITTING;if(prepared==null)return MutationOperationOwner.Phase.SNAPSHOTTING;if(plan==null)return MutationOperationOwner.Phase.PLANNING;if(!commitActive)return MutationOperationOwner.Phase.PLANNING;if(blocksFlushed&&entityCursor>=prepared.entities().size())return MutationOperationOwner.Phase.FINALIZING;return MutationOperationOwner.Phase.COMMITTING;}
        public void release(){if(reserved!=0){RETAINED.addAndGet(-reserved);reserved=0;}}
    }
    private static final class Planner implements Runnable {final Owner owner;final PreparedClipboardView view;final boolean ignoreAir;Planner(Owner owner,PreparedClipboardView view,boolean ignoreAir){this.owner=owner;this.view=view;this.ignoreAir=ignoreAir;}public void run(){long started=System.nanoTime();PasteHookStatus.pasteWorkerActive.incrementAndGet();try{owner.lifecycle.running();int count=0;for(int i=0;i<view.getVolume();i++)if(!ignoreAir||view.idAt(i)!=0)count++;int[] indices=new int[count];for(int i=0,n=0;i<view.getVolume();i++)if(!ignoreAir||view.idAt(i)!=0)indices[n++]=i;owner.plan=MutationPlanBuilder.chunkLocal(indices,new MutationPlanBuilder.Coordinates(){public int x(int index){return view.destinationX(index);}public int z(int index){return view.destinationZ(index);}});PasteHookStatus.pastePlannedBlocks.addAndGet(count);PasteHookStatus.commitRemaining.set(count);}catch(Throwable t){owner.planningFailure=t;}finally{long wall=ms(started);PasteHookStatus.lastPastePlanMillis.set(wall);PasteHookStatus.lastOperationPlanWallMillis.set(wall);PasteHookStatus.pastePlanningActive.decrementAndGet();PasteHookStatus.pasteWorkerActive.decrementAndGet();PasteHookStatus.pasteWorkerTasksCompleted.incrementAndGet();PasteHookStatus.workerCompletedChunks.incrementAndGet();}}}

    private static final class CaptureExtent extends NullExtent {final List<PreparedClipboardView.EntitySnapshot> snapshots=new ArrayList<PreparedClipboardView.EntitySnapshot>();public Entity createEntity(Location location,BaseEntity state){PreparedClipboardView.EntitySnapshot snapshot=new PreparedClipboardView.EntitySnapshot(location,state);snapshots.add(snapshot);return new SnapshotEntity(snapshot,this);} }
    private static final class SnapshotEntity implements Entity {final PreparedClipboardView.EntitySnapshot snapshot;final Extent extent;SnapshotEntity(PreparedClipboardView.EntitySnapshot snapshot,Extent extent){this.snapshot=snapshot;this.extent=extent;}public BaseEntity getState(){return new BaseEntity(snapshot.state);}public Location getLocation(){return snapshot.location;}public Extent getExtent(){return extent;}public boolean remove(){return false;}public <T>T getFacet(Class<? extends T> type){return null;}}
    private static void updateMax(AtomicLong target,long value){for(;;){long old=target.get();if(value<=old||target.compareAndSet(old,value))return;}}
    private static boolean reserve(long bytes){for(;;){long current=RETAINED.get();if(current+bytes>GLOBAL)return false;if(RETAINED.compareAndSet(current,current+bytes))return true;}}
    private static long ms(long start){return(System.nanoTime()-start)/1000000L;}
    public static AdaptiveServerBudget budget(){return BUDGET;}
    private DeferredPasteManager(){}
}
