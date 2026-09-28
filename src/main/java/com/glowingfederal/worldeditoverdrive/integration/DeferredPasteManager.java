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
import com.sk89q.worldedit.function.entity.ExtentEntityCopy;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.Location;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns deferred compatibility traversal and full standard PasteBuilder acceleration. */
public final class DeferredPasteManager {
    private static final long GLOBAL=128L<<20,PER_OPERATION=64L<<20;
    private static final AdaptiveServerBudget BUDGET=new AdaptiveServerBudget();
    private static final Queue<Owner> OWNERS=new ArrayDeque<Owner>();
    private static final ExecutorService WORKERS=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),new ThreadFactory(){private final AtomicLong sequence=new AtomicLong();public Thread newThread(Runnable task){Thread thread=new Thread(task,"worldedit-overdrive-paste-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;}});
    private static final AtomicLong RETAINED=new AtomicLong();
    static final class AdmissionRejectedException extends Exception {AdmissionRejectedException(String reason){super(reason);}}
    public static synchronized void register(ForwardExtentCopy operation,PasteOperationAdapter adapter,Player player,LocalSession session,boolean selectPasted)throws Exception{if(operation==null||adapter==null||player==null||session==null)throw new NullPointerException("deferred paste context");OWNERS.add(new Owner(operation,adapter,player,session,session.getClipboard(),selectPasted));PasteHookStatus.pasteDeferredActive.incrementAndGet();}
    public static void tick(long normalTickNanos){long started=System.nanoTime(),deadline=started+BUDGET.beginTick(normalTickNanos);Owner[] snapshot;synchronized(DeferredPasteManager.class){snapshot=OWNERS.toArray(new Owner[OWNERS.size()]);}for(Owner owner:snapshot){if(System.nanoTime()>=deadline)break;try{if(owner.tick(deadline))remove(owner,true,null);else rotate(owner);}catch(Throwable failure){remove(owner,false,failure);}}BUDGET.endTick(System.nanoTime()-started);}
    private static synchronized void rotate(Owner owner){if(OWNERS.remove(owner))OWNERS.add(owner);}
    private static void remove(Owner owner,boolean success,Throwable failure){if(success)owner.validateSuccessfulRemoval();synchronized(DeferredPasteManager.class){if(!OWNERS.remove(owner))return;}owner.release();if(owner.commitActive)PasteHookStatus.pasteCommitActive.decrementAndGet();PasteHookStatus.pasteDeferredActive.decrementAndGet();if(success)PasteHookStatus.pasteDeferredCompleted.incrementAndGet();else{owner.state=Owner.State.FAILED;PasteHookStatus.pasteDeferredFailed.incrementAndGet();PasteHookStatus.lastPasteDeferredReason="failed: "+failure;owner.player.printError("Paste failed: "+failure.getMessage());OverdriveLog.error("deferred paste failed: {}",failure.toString());}}

    private static final class Owner implements MutationOperationOwner {
        enum State { CAPTURING,PLANNING,SUBMITTING,COMMITTING,FINALIZING,COMPLETE,FAILED }
        final ForwardExtentCopy original;final PasteOperationAdapter adapter;final Player player;final LocalSession session;final ClipboardHolder holder;final boolean select;final PasteContinuationOperation lifecycle=new PasteContinuationOperation();
        final long operationStarted=System.nanoTime(),snapshotStarted=operationStarted;final int minX,minY,minZ,sizeX,sizeY,sizeZ,volume;
        volatile RegionMutationPlan plan;volatile Throwable planningFailure;PreparedClipboardView prepared;PreparedClipboardView.Builder capture;CaptureExtent entityCapture;List<? extends Entity> sourceEntities;int sourceEntityLimit;PlanningWork planningWork;final PasteNbtSizer nbtSizer=new PasteNbtSizer();
        boolean vanilla,mutation,commitActive,blocksFlushed,captureInitialized,entitiesListed,commitInitialized;State state=State.CAPTURING;int captureIndex,entityCaptureCursor,batchCursor,batchOffset,entityCursor;long reserved,commitNanos,snapshotActiveNanos,commitStarted,submittedTotal,planningStarted;int plannedTotal;Operation commitOperation;final Set<Long> touchedChunks=new HashSet<Long>();
        final PasteSliceBudget captureBudget=new PasteSliceBudget(),submissionBudget=new PasteSliceBudget(),reorderBudget=new PasteSliceBudget();
        final PasteResumeStatistics resumeStatistics=new PasteResumeStatistics();
        long submissionActiveNanos,finalizationActiveNanos,reorderStarted,finalizationStarted;String reorderStage="none";
        Owner(ForwardExtentCopy original,PasteOperationAdapter adapter,Player player,LocalSession session,ClipboardHolder holder,boolean select)throws Exception{
            this.original=original;this.adapter=adapter;this.player=player;this.session=session;this.holder=holder;this.select=select;
            PasteOperationAdapter.Eligibility eligible=adapter.accelerationEligibility();if(eligible.kind!=PasteOperationAdapter.Eligibility.Kind.ACCELERATE)throw new IllegalArgumentException(eligible.reason);
            Vector min=adapter.clipboard.getMinimumPoint(),max=adapter.clipboard.getMaximumPoint();minX=min.getBlockX();minY=min.getBlockY();minZ=min.getBlockZ();
            long xSize=(long)max.getBlockX()-minX+1L,ySize=(long)max.getBlockY()-minY+1L,zSize=(long)max.getBlockZ()-minZ+1L;
            if(xSize<=0||ySize<=0||zSize<=0||xSize>Integer.MAX_VALUE||ySize>Integer.MAX_VALUE||zSize>Integer.MAX_VALUE||xSize*ySize>Integer.MAX_VALUE/zSize)throw new AdmissionRejectedException("Clipboard dimensions exceed the accelerated paste limit");
            sizeX=(int)xSize;sizeY=(int)ySize;sizeZ=(int)zSize;long volumeLong=xSize*ySize*zSize;volume=(int)volumeLong;
            long estimate=128L+volumeLong*32L+((volumeLong+PreparedClipboardView.PAGE_MASK)>>PreparedClipboardView.PAGE_SHIFT)*128L;
            if(estimate>PER_OPERATION)throw new AdmissionRejectedException("Paste exceeds the 64 MiB preparation limit; use a smaller clipboard");
            if(!reserve(estimate))throw new AdmissionRejectedException("Paste preparation memory is busy; retry after the active paste finishes");reserved=estimate;
            PasteHookStatus.activePhase="SNAPSHOTTING";PasteHookStatus.snapshotProcessed.set(0);PasteHookStatus.snapshotTotalEstimate.set(volumeLong);PasteHookStatus.commitRemaining.set(volumeLong);
            PasteHookStatus.lastOperationSnapshotActiveMillis.set(0);PasteHookStatus.lastOperationMaxServerSliceMillis.set(0);PasteHookStatus.lastOperationWallMillis.set(0);resetOperationDiagnostics();resetPacingDiagnostics();
            PasteHookStatus.lastPasteTransform=adapter.transform.getClass().getName();PasteHookStatus.lastPasteIgnoreAir=adapter.ignoreAir;
            PasteHookStatus.queueEnabled=adapter.destination.isQueueEnabled();PasteHookStatus.incrementalCommitSupported=EnhancedReorderYieldBridge.isSupported();PasteHookStatus.lastPasteDeferredReason=PasteHookStatus.queueEnabled?"accelerated paste admitted with normal reorder buffering and incremental commit":"accelerated paste admitted with bounded synchronous setBlock and final flush";PasteHookStatus.queueImplementationClass=PasteHookStatus.queueEnabled?"com.sk89q.worldedit.extent.reorder.MultiStageReorder":"disabled";PasteHookStatus.editSessionExtentClass="com.sk89q.worldedit.EditSession";OverdriveLog.info("paste reorderEnabled={} incrementalCommitSupported={}",Boolean.valueOf(PasteHookStatus.queueEnabled),Boolean.valueOf(PasteHookStatus.incrementalCommitSupported));
        }
        boolean resizeReservation(long wanted){if(wanted<=reserved){RETAINED.addAndGet(wanted-reserved);reserved=wanted;return true;}long extra=wanted-reserved;if(!reserve(extra))return false;reserved=wanted;return true;}
        void defer(String reason){vanilla=true;PasteHookStatus.pasteAccelerationFallbacks.incrementAndGet();PasteHookStatus.lastPasteAccelerationFallbackReason=reason;PasteHookStatus.lastPasteDeferredReason="deferred vanilla: "+reason;}
        public boolean tick(long globalDeadline)throws Exception{
            long sliceStarted=System.nanoTime();
            RegionMutationPlan ready=plan;
            State sliceState=state==State.PLANNING&&ready!=null?State.SUBMITTING:state;
            PasteSliceBudget pacing=sliceState==State.CAPTURING||sliceState==State.PLANNING?captureBudget:sliceState==State.SUBMITTING?submissionBudget:sliceState==State.COMMITTING?reorderBudget:null;
            if(pacing!=null)globalDeadline=Math.min(globalDeadline,sliceStarted+pacing.targetNanos());
            long allowance=Math.max(0L,globalDeadline-sliceStarted);
            try {
                if(vanilla)throw new IllegalStateException("deferred owner cannot execute an unbounded vanilla traversal");
                if(state==State.COMPLETE)return true;
                if(state==State.FAILED)throw new IllegalStateException("failed deferred paste owner was scheduled again");
                if(state==State.CAPTURING){captureUntil(globalDeadline);return false;}
                if(planningFailure!=null)throw new Exception("accelerated planning failed",planningFailure);
                if(state==State.PLANNING){if(ready==null){PasteHookStatus.activePhase="PLANNING";dispatchPlanningUntil(this,globalDeadline);return false;}state=State.SUBMITTING;PasteHookStatus.captureWorkStage="COMPLETE";lifecycle.committing();commitActive=true;commitStarted=System.nanoTime();PasteHookStatus.activePhase="SUBMITTING";PasteHookStatus.pasteCommitActive.incrementAndGet();}
                if(state==State.COMMITTING){PasteHookStatus.activePhase="COMMITTING";resumeCommit(globalDeadline);updateCommitRemaining();if(!blocksFlushed)return false;state=State.FINALIZING;finalizationStarted=System.nanoTime();PasteHookStatus.activePhase="FINALIZING";return false;}
                if(state==State.FINALIZING){PasteHookStatus.activePhase="FINALIZING";int entities=0;while(entityCursor<prepared.entities().size()&&entities<16&&System.nanoTime()<globalDeadline){PreparedClipboardView.EntitySnapshot entity=prepared.entities().get(entityCursor++);if(adapter.destination.createEntity(new Location(adapter.destination,entity.location.toVector(),entity.location.getYaw(),entity.location.getPitch()),new BaseEntity(entity.state))!=null)PasteHookStatus.pasteCommittedEntities.incrementAndGet();mutation=true;entities++;}if(entityCursor<prepared.entities().size())return false;finish();commitActive=false;PasteHookStatus.pasteCommitActive.decrementAndGet();lifecycle.complete();PasteHookStatus.pasteAccelerated.incrementAndGet();PasteHookStatus.activePhase="IDLE";PasteHookStatus.lastOperationWallMillis.set((System.nanoTime()-operationStarted)/1000000L);state=State.COMPLETE;return true;}
                if(state!=State.SUBMITTING)throw new IllegalStateException("unexpected deferred paste lifecycle state: "+state);
                long tickStarted=System.nanoTime();int submitted=0,changed=0,tiles=0,loadedChunks=0,lastChunk=Integer.MIN_VALUE;
                while(batchCursor<ready.getBatches().size()&&submitted<4096&&System.nanoTime()<globalDeadline){ChunkMutationBatch batch=ready.getBatches().get(batchCursor);if(batchOffset==batch.size()){batchCursor++;batchOffset=0;continue;}int i=batch.sourceIndex(batchOffset);int chunkX=prepared.destinationX(i)>>4,chunkZ=prepared.destinationZ(i)>>4,chunk=chunkX*31+chunkZ;if(chunk!=lastChunk&&loadedChunks>=2)break;if(chunk!=lastChunk){lastChunk=chunk;loadedChunks++;}batchOffset++;BaseBlock desired=prepared.blockAt(i);Vector position=new Vector(prepared.destinationX(i),prepared.destinationY(i),prepared.destinationZ(i));BaseBlock existing=adapter.destination.getBlock(position);if(desired.getNbtData()==null&&existing.getId()==desired.getId()&&existing.getData()==desired.getData()){plannedTotal--;PasteHookStatus.pastePlannedBlocks.decrementAndGet();PasteHookStatus.pasteDestinationMatchedCells.incrementAndGet();continue;}if(adapter.destination.setBlock(position,desired))changed++;mutation=true;submitted++;touchedChunks.add(Long.valueOf(((long)chunkX<<32)^(chunkZ&0xffffffffL)));if(desired.getNbtData()!=null)tiles++;}
                long submissionNanos=System.nanoTime()-tickStarted;updateMax(PasteHookStatus.maxSubmissionSliceMillis,submissionNanos/1000000L);
                if(submitted!=0){submittedTotal+=submitted;PasteHookStatus.pasteSubmittedBlocks.addAndGet(submitted);if(!PasteHookStatus.queueEnabled){PasteHookStatus.pasteCommittedBlocks.addAndGet(changed);PasteHookStatus.pasteCommittedTiles.addAndGet(tiles);}PasteHookStatus.submittedSinceLastDrain.set(submitted);PasteHookStatus.chunksSinceLastDrain.set(loadedChunks);}
                PasteHookStatus.commitRemaining.set(Math.max(0L,plannedTotal-submittedTotal));PasteHookStatus.lastOperationCommitWallMillis.set((System.nanoTime()-commitStarted)/1000000L);
                if(batchCursor<ready.getBatches().size())return false;
                if(PasteHookStatus.queueEnabled){EnhancedReorderYieldBridge.observeRemaining(adapter.destination);updateCommitRemaining();state=State.COMMITTING;reorderStarted=System.nanoTime();PasteHookStatus.activePhase="COMMITTING";}else{drain((int)submittedTotal,true);blocksFlushed=true;PasteHookStatus.commitCompletedNormally=true;PasteHookStatus.commitRemaining.set(0);state=State.FINALIZING;finalizationStarted=System.nanoTime();PasteHookStatus.activePhase="FINALIZING";}return false;
            } finally {
                long slice=System.nanoTime()-sliceStarted;updateMax(PasteHookStatus.lastOperationMaxServerSliceMillis,slice/1000000L);
                if(sliceState==State.CAPTURING)captureBudget.observe(slice,allowance,state==State.CAPTURING);
                if(sliceState==State.SUBMITTING){submissionActiveNanos+=slice;submissionBudget.observe(slice,allowance,state==State.SUBMITTING);PasteHookStatus.submissionActiveServerMillis.set(submissionActiveNanos/1000000L);PasteHookStatus.submissionElapsedWallMillis.set(ms(commitStarted));}
                if(sliceState==State.FINALIZING){finalizationActiveNanos+=slice;PasteHookStatus.finalizationServerMillis.set(finalizationActiveNanos/1000000L);PasteHookStatus.finalizationElapsedWallMillis.set(ms(finalizationStarted));}
                long active=commitNanos+submissionActiveNanos+finalizationActiveNanos;
                PasteHookStatus.commitServerMillis.set(active/1000000L);PasteHookStatus.lastOperationCommitActiveMillis.set(active/1000000L);PasteHookStatus.lastPasteCommitMillis.set(active/1000000L);
                if(commitStarted!=0)PasteHookStatus.lastOperationCommitWallMillis.set(ms(commitStarted));
                PasteHookStatus.captureTargetNanos.set(captureBudget.targetNanos());PasteHookStatus.submissionTargetNanos.set(submissionBudget.targetNanos());PasteHookStatus.commitTargetNanos.set(reorderBudget.targetNanos());
                PasteHookStatus.commitBudgetIncreases.set(reorderBudget.increases());PasteHookStatus.commitBudgetDecreases.set(reorderBudget.decreases());
            }
        }
        void resumeCommit(long deadline)throws Exception{
            long commitSliceStarted=System.nanoTime();
            long sliceAllowance=Math.max(0L,deadline-commitSliceStarted);boolean resumed=false;
            PasteHookStatus.deadlineBudgetNanos.set(sliceAllowance);PasteHookStatus.incrementalCommitSlices.incrementAndGet();
            try{
                if(!commitInitialized){commitInitialized=true;EnhancedReorderYieldBridge.observeRemaining(adapter.destination);commitOperation=adapter.destination.commit();PasteHookStatus.commitOperationClass=commitOperation==null?"none":commitOperation.getClass().getName();PasteHookStatus.topLevelCommitReturnedNull=commitOperation==null;}
                RunContext context=new RunContext();
                while(commitOperation!=null&&System.nanoTime()<deadline){
                    String pacingStage=PasteHookStatus.reorderStage1Remaining.get()+PasteHookStatus.reorderStage2Remaining.get()>0?"STAGE_1_2":PasteHookStatus.reorderStage3Remaining.get()>0?"STAGE_3":"DOWNSTREAM";
                    if(!pacingStage.equals(reorderStage)){reorderStage=pacingStage;reorderBudget.resetStage();deadline=Math.min(deadline,System.nanoTime()+reorderBudget.targetNanos());}
                    PasteHookStatus.commitPacingStage=pacingStage;
                    String stage=commitOperation.getClass().getName();PasteHookStatus.activeCommitOperationClassBeforeResume=stage;PasteHookStatus.childResumeStage=stage;
                    PasteHookStatus.blockMapPlacementsThisResume.set(0);PasteHookStatus.stage3PlacementsThisResume.set(0);PasteHookStatus.stage3ChainsThisResume.set(0);PasteHookStatus.deadlineRemainingNanosAtFirstPlacement.set(-1);
                    long started=System.nanoTime(),allowance=Math.max(0L,deadline-started);PasteHookStatus.deadlineRemainingNanosAtResumeEntry.set(allowance);
                    EnhancedReorderYieldBridge.beginSlice(deadline);
                    commitOperation=commitOperation.resume(context);long nanos=System.nanoTime()-started;resumed=true;
                    long placements=PasteHookStatus.blockMapPlacementsThisResume.get()+PasteHookStatus.stage3PlacementsThisResume.get(),chains=PasteHookStatus.stage3ChainsThisResume.get();
                    stage=PasteHookStatus.childResumeStage;
                    PasteHookStatus.resumeElapsedNanos.set(nanos);PasteHookStatus.placementsThisResume.set(placements);PasteHookStatus.commitResumeCalls.incrementAndGet();
                    PasteHookStatus.activeCommitOperationClassAfterResume=commitOperation==null?"null":commitOperation.getClass().getName();PasteHookStatus.topLevelCommitReturnedNull=commitOperation==null;
                    if(nanos>PasteHookStatus.maxCommitResumeNanos.get()){PasteHookStatus.maxCommitResumeMillis.set(nanos/1000000L);PasteHookStatus.maxCommitResumeStage=stage;PasteHookStatus.maxCommitResumePlacements.set(placements);PasteHookStatus.maxCommitResumeChains.set(chains);PasteHookStatus.maxCommitResumeBeganExpired=PasteHookStatus.deadlineRemainingNanosAtResumeEntry.get()<=0;}
                    resumeStatistics.record(nanos,allowance);
                    reorderBudget.observe(nanos,allowance,commitOperation!=null);
                    // Feedback may shorten this slice, but an inner resume never renews its allowance.
                    deadline=Math.min(deadline,commitSliceStarted+reorderBudget.targetNanos());
                }
            } finally{
                EnhancedReorderYieldBridge.endSlice();long active=System.nanoTime()-commitSliceStarted;commitNanos+=active;
                if(!resumed)reorderBudget.observe(active,sliceAllowance,commitOperation!=null);
                PasteHookStatus.commitStateActiveServerMillis.set(commitNanos/1000000L);PasteHookStatus.commitStateElapsedWallMillis.set(ms(reorderStarted));
            }
            if(commitOperation==null){verifyCommitExhausted();blocksFlushed=true;PasteHookStatus.commitCompletedNormally=true;}
        }
        void updateCommitRemaining(){long downstream=PasteHookStatus.commitOperationRemaining.get();PasteHookStatus.commitRemaining.set(downstream<0?Math.max(0L,plannedTotal-submittedTotal):downstream);}
        void validateSuccessfulRemoval(){long remaining=PasteHookStatus.commitOperationRemaining.get();if(state!=State.COMPLETE||commitOperation!=null||(PasteHookStatus.queueEnabled&&(!PasteHookStatus.commitCompletedNormally||remaining!=0)))throw new IllegalStateException("refusing successful deferred paste removal: state="+state+", commitOperation="+(commitOperation==null?"null":commitOperation.getClass().getName())+", commitCompletedNormally="+PasteHookStatus.commitCompletedNormally+", commitRemaining="+PasteHookStatus.commitRemaining.get()+", reorderRemaining="+remaining+", stage1="+PasteHookStatus.reorderStage1Remaining.get()+", stage2="+PasteHookStatus.reorderStage2Remaining.get()+", stage3="+PasteHookStatus.reorderStage3Remaining.get());}
        void verifyCommitExhausted(){long remaining=PasteHookStatus.commitOperationRemaining.get();if(remaining!=0)throw new IllegalStateException("incremental reorder commit returned null with remaining work: stage1="+PasteHookStatus.reorderStage1Remaining.get()+", stage2="+PasteHookStatus.reorderStage2Remaining.get()+", stage3="+PasteHookStatus.reorderStage3Remaining.get());}
        void captureUntil(long deadline)throws Exception{
            long active=System.nanoTime();PasteHookStatus.activePhase="SNAPSHOTTING";
            try{
                if(!captureInitialized){PasteHookStatus.captureWorkStage="INITIALIZING";capture=new PreparedClipboardView.Builder(minX,minY,minZ,sizeX,sizeY,sizeZ);entityCapture=new CaptureExtent(capture);captureInitialized=true;}
                while(System.nanoTime()<deadline){
                    if(!nbtSizer.complete()){PasteHookStatus.captureWorkStage="NBT_ACCOUNTING";capture.bytes+=nbtSizer.resume(deadline,4096);ensureCaptureReservation();if(!nbtSizer.complete())return;}
                    if(System.nanoTime()>=deadline)return;
                    if(captureIndex<volume){
                        PasteHookStatus.captureWorkStage="BLOCKS";int i=captureIndex;int x=minX+i%sizeX;int q=i/sizeX;int z=minZ+q%sizeZ;int y=minY+q/sizeZ;
                        Vector source=new Vector(x,y,z);BaseBlock block=adapter.transformedSource.getBlock(source);
                        Vector destination=adapter.transform.apply(source.subtract(adapter.sourceOrigin)).add(adapter.destinationOrigin);
                        BaseBlock auxiliary=block.getNbtData()!=null||block.getClass()!=BaseBlock.class?new BaseBlock(block):null;
                        capture.set(i,block.getId(),block.getData(),destination.getBlockX(),destination.getBlockY(),destination.getBlockZ(),auxiliary);captureIndex++;nbtSizer.start(block.getNbtData());
                        PasteHookStatus.snapshotProcessed.set(captureIndex);continue;
                    }
                    if(!entitiesListed){sourceEntities=adapter.clipboard.getEntities();sourceEntityLimit=sourceEntities.size();entitiesListed=true;PasteHookStatus.snapshotTotalEstimate.set((long)volume+sourceEntityLimit);}
                    if(entityCaptureCursor<sourceEntityLimit){
                        PasteHookStatus.captureWorkStage="ENTITIES";Entity entity=sourceEntities.get(entityCaptureCursor++);entityCapture.last=null;
                        if(adapter.region.contains(entity.getLocation().toVector())){ExtentEntityCopy copy=new ExtentEntityCopy(adapter.sourceOrigin,entityCapture,adapter.destinationOrigin,adapter.transform);copy.apply(entity);if(entityCapture.last!=null)nbtSizer.start(entityCapture.last.state.getNbtData());}
                        PasteHookStatus.snapshotProcessed.set((long)volume+entityCaptureCursor);continue;
                    }
                    PasteHookStatus.captureWorkStage="SEALING";ensureCaptureReservation();long air=capture.air;PasteHookStatus.capturePagesAllocated.set(capture.pagesAllocated);prepared=capture.seal();capture=null;entityCapture=null;sourceEntities=null;
                    PasteHookStatus.pastePreparedBlocks.addAndGet(volume);PasteHookStatus.pasteSourceAirCells.addAndGet(air);if(adapter.ignoreAir)PasteHookStatus.pasteIgnoreAirFilteredCells.addAndGet(air);PasteHookStatus.pastePreparedTiles.addAndGet(prepared.tileCount());PasteHookStatus.pastePreparedEntities.addAndGet(prepared.entities().size());if(!adapter.transform.isIdentity())PasteHookStatus.pasteTransformedBlocks.addAndGet(volume);
                    PasteHookStatus.lastPastePrepareMillis.set(ms(snapshotStarted));state=State.PLANNING;PasteHookStatus.activePhase="PLANNING";lifecycle.submitted();PasteHookStatus.pastePlanningActive.incrementAndGet();planningStarted=System.nanoTime();planningWork=new PlanningWork(prepared.getVolume());return;
                }
            } finally{
                if(capture!=null)PasteHookStatus.capturePagesAllocated.set(capture.pagesAllocated);
                snapshotAccounting(active);PasteHookStatus.captureSlices.incrementAndGet();updateMax(PasteHookStatus.maxCaptureSliceNanos,System.nanoTime()-active);
                if(capture!=null)ensureCaptureReservation();
            }
        }
        void ensureCaptureReservation(){
            long actual=capture.bytes+volume*4L;
            if(actual>PER_OPERATION||(actual>reserved&&!resizeReservation(actual)))throw new IllegalStateException("accelerated paste retained data exceeds memory limit: "+actual);
        }
        void drain(int queued,boolean finalDrain){
            if(queued==0&&!finalDrain)return;
            if(finalDrain){PasteHookStatus.finalFlushQueuedMutations.set(queued);PasteHookStatus.finalFlushChunks.set(touchedChunks.size());}
            long started=System.nanoTime();adapter.destination.flushQueue();long nanos=System.nanoTime()-started;
            if(finalDrain)PasteHookStatus.finalSynchronousFlushCount.incrementAndGet();
            PasteHookStatus.flushCount.incrementAndGet();PasteHookStatus.totalFlushNanos.addAndGet(nanos);PasteHookStatus.lastFlushMillis.set(nanos/1000000L);updateMax(PasteHookStatus.maxFlushMillis,nanos/1000000L);
            PasteHookStatus.queueDrainServerMillis.addAndGet(nanos/1000000L);PasteHookStatus.submittedSinceLastDrain.set(0);PasteHookStatus.chunksSinceLastDrain.set(0);
            if(nanos>Math.max(1000000L,BUDGET.budgetNanos()))PasteHookStatus.uninterruptibleFlushOverBudgetCount.incrementAndGet();
            if(finalDrain){PasteHookStatus.finalFlushMillis.set(nanos/1000000L);updateMax(PasteHookStatus.maxFinalFlushMillis,nanos/1000000L);}
        }
        void snapshotAccounting(long started){snapshotActiveNanos+=System.nanoTime()-started;PasteHookStatus.lastOperationSnapshotWallMillis.set(ms(snapshotStarted));PasteHookStatus.lastOperationSnapshotActiveMillis.set(snapshotActiveNanos/1000000L);PasteHookStatus.sourceCaptureServerMillis.set(snapshotActiveNanos/1000000L);}
        void finish(){session.remember(adapter.destination);Vector to=adapter.destinationOrigin;if(select){Vector max=to.add(adapter.region.getMaximumPoint().subtract(adapter.region.getMinimumPoint()));RegionSelector selector=new CuboidRegionSelector(player.getWorld(),to,max);session.setRegionSelector(player.getWorld(),selector);selector.learnChanges();selector.explainRegionAdjust(player,session);}player.print("The clipboard has been pasted at "+to);}
        public MutationOperationOwner.Phase phase(){if(vanilla)return MutationOperationOwner.Phase.COMMITTING;if(prepared==null)return MutationOperationOwner.Phase.SNAPSHOTTING;if(plan==null)return MutationOperationOwner.Phase.PLANNING;if(!commitActive)return MutationOperationOwner.Phase.PLANNING;if(blocksFlushed&&entityCursor>=prepared.entities().size())return MutationOperationOwner.Phase.FINALIZING;return MutationOperationOwner.Phase.COMMITTING;}
        public void release(){if(reserved!=0){RETAINED.addAndGet(-reserved);reserved=0;}}
    }
    private static final class PlanningWork {
        static final int SLICE_SIZE=8192;final int[][] results;final AtomicInteger remaining;int next;
        PlanningWork(int volume){int tasks=Math.max(1,(volume+SLICE_SIZE-1)/SLICE_SIZE);results=new int[tasks][];remaining=new AtomicInteger(tasks);}
    }
    private static void dispatchPlanningUntil(Owner owner,long deadline){
        PlanningWork work=owner.planningWork;int dispatched=0;PasteHookStatus.captureWorkStage="PLANNING_DISPATCH";
        while(work.next<work.results.length&&dispatched<4&&System.nanoTime()<deadline){int task=work.next++;int from=task*PlanningWork.SLICE_SIZE,to=Math.min(owner.prepared.getVolume(),from+PlanningWork.SLICE_SIZE);PasteHookStatus.pasteWorkerTasksSubmitted.incrementAndGet();PasteHookStatus.workerQueuedChunks.incrementAndGet();WORKERS.execute(new Planner(owner,owner.prepared,owner.adapter.ignoreAir,from,to,task,work.results,work.remaining));dispatched++;}
        if(work.next==work.results.length)PasteHookStatus.captureWorkStage="WAITING_FOR_PLAN";
    }
    private static final class Planner implements Runnable {
        final Owner owner;final PreparedClipboardView view;final boolean ignoreAir;final int from,to,slot;final int[][] results;final AtomicInteger remaining;
        Planner(Owner owner,PreparedClipboardView view,boolean ignoreAir,int from,int to,int slot,int[][] results,AtomicInteger remaining){this.owner=owner;this.view=view;this.ignoreAir=ignoreAir;this.from=from;this.to=to;this.slot=slot;this.results=results;this.remaining=remaining;}
        public void run(){long started=System.nanoTime();long active=PasteHookStatus.pasteWorkerActive.incrementAndGet();updateMax(PasteHookStatus.pasteWorkerMaxConcurrency,active);try{owner.lifecycle.running();int count=0;for(int i=from;i<to;i++)if(!ignoreAir||view.idAt(i)!=0)count++;int[] indices=new int[count];for(int i=from,n=0;i<to;i++)if(!ignoreAir||view.idAt(i)!=0)indices[n++]=i;results[slot]=indices;if(remaining.decrementAndGet()==0){int total=0;for(int[] part:results)total+=part.length;int[] all=new int[total];int cursor=0;for(int[] part:results){System.arraycopy(part,0,all,cursor,part.length);cursor+=part.length;}owner.plannedTotal=total;owner.plan=MutationPlanBuilder.chunkLocal(all,new MutationPlanBuilder.Coordinates(){public int x(int index){return view.destinationX(index);}public int z(int index){return view.destinationZ(index);}});PasteHookStatus.pastePlannedBlocks.addAndGet(total);PasteHookStatus.commitRemaining.set(total);PasteHookStatus.lastOperationPlanWallMillis.set(ms(owner.planningStarted));PasteHookStatus.lastPastePlanMillis.set(PasteHookStatus.lastOperationPlanWallMillis.get());PasteHookStatus.pastePlanningActive.decrementAndGet();}}catch(Throwable t){owner.planningFailure=t;PasteHookStatus.pastePlanningActive.decrementAndGet();}finally{long nanos=System.nanoTime()-started;PasteHookStatus.pasteWorkerPlanNanos.addAndGet(nanos);PasteHookStatus.pasteWorkerActive.decrementAndGet();PasteHookStatus.pasteWorkerTasksCompleted.incrementAndGet();PasteHookStatus.workerCompletedChunks.incrementAndGet();}}
    }


    private static final class CaptureExtent extends NullExtent {final PreparedClipboardView.Builder capture;PreparedClipboardView.EntitySnapshot last;CaptureExtent(PreparedClipboardView.Builder capture){this.capture=capture;}public Entity createEntity(Location location,BaseEntity state){last=capture.addEntity(location,state);return new SnapshotEntity(last,this);} }
    private static final class SnapshotEntity implements Entity {final PreparedClipboardView.EntitySnapshot snapshot;final Extent extent;SnapshotEntity(PreparedClipboardView.EntitySnapshot snapshot,Extent extent){this.snapshot=snapshot;this.extent=extent;}public BaseEntity getState(){return new BaseEntity(snapshot.state);}public Location getLocation(){return snapshot.location;}public Extent getExtent(){return extent;}public boolean remove(){return false;}public <T>T getFacet(Class<? extends T> type){return null;}}
    private static void resetOperationDiagnostics(){PasteHookStatus.pastePreparedBlocks.set(0);PasteHookStatus.pastePlannedBlocks.set(0);PasteHookStatus.pasteSubmittedBlocks.set(0);PasteHookStatus.pasteCommittedBlocks.set(0);PasteHookStatus.pasteSourceAirCells.set(0);PasteHookStatus.pasteIgnoreAirFilteredCells.set(0);PasteHookStatus.pasteDestinationMatchedCells.set(0);PasteHookStatus.pasteOtherwiseFilteredCells.set(0);PasteHookStatus.pastePreparedTiles.set(0);PasteHookStatus.pasteCommittedTiles.set(0);PasteHookStatus.pastePreparedEntities.set(0);PasteHookStatus.pasteCommittedEntities.set(0);PasteHookStatus.pasteTransformedBlocks.set(0);PasteHookStatus.pasteWorkerTasksSubmitted.set(0);PasteHookStatus.pasteWorkerTasksCompleted.set(0);PasteHookStatus.workerQueuedChunks.set(0);PasteHookStatus.workerCompletedChunks.set(0);PasteHookStatus.lastPastePrepareMillis.set(0);PasteHookStatus.lastPastePlanMillis.set(0);PasteHookStatus.lastPasteCommitMillis.set(0);PasteHookStatus.destinationCaptureServerMillis.set(0);PasteHookStatus.submittedSinceLastDrain.set(0);PasteHookStatus.chunksSinceLastDrain.set(0);PasteHookStatus.flushCount.set(0);PasteHookStatus.totalFlushNanos.set(0);PasteHookStatus.lastFlushMillis.set(0);PasteHookStatus.maxFlushMillis.set(0);PasteHookStatus.maxSubmissionSliceMillis.set(0);PasteHookStatus.maxFinalFlushMillis.set(0);PasteHookStatus.finalFlushQueuedMutations.set(0);PasteHookStatus.finalFlushChunks.set(0);PasteHookStatus.finalFlushMillis.set(0);PasteHookStatus.uninterruptibleFlushOverBudgetCount.set(0);PasteHookStatus.queueDrainServerMillis.set(0);PasteHookStatus.commitServerMillis.set(0);PasteHookStatus.finalizationServerMillis.set(0);PasteHookStatus.pasteWorkerPlanNanos.set(0);PasteHookStatus.pasteWorkerMaxConcurrency.set(0);PasteHookStatus.incrementalCommitSlices.set(0);PasteHookStatus.commitResumeCalls.set(0);PasteHookStatus.maxCommitResumeMillis.set(0);PasteHookStatus.commitOperationRemaining.set(-1);PasteHookStatus.finalSynchronousFlushCount.set(0);PasteHookStatus.reorderStage1Remaining.set(-1);PasteHookStatus.reorderStage2Remaining.set(-1);PasteHookStatus.reorderStage3Remaining.set(-1);PasteHookStatus.blockMapPlacementsThisResume.set(0);PasteHookStatus.stage3PlacementsThisResume.set(0);PasteHookStatus.stage3ChainsThisResume.set(0);PasteHookStatus.deadlineYieldCount.set(0);PasteHookStatus.blockMapDeadlineYields.set(0);PasteHookStatus.stage3DeadlineYields.set(0);PasteHookStatus.topLevelCommitReturnedNull=false;PasteHookStatus.commitCompletedNormally=false;PasteHookStatus.commitOperationClass="none";PasteHookStatus.activeCommitOperationClassBeforeResume="none";PasteHookStatus.activeCommitOperationClassAfterResume="none";PasteHookStatus.maxCommitResumeStage="none";PasteHookStatus.maxDownstreamMutationDestinationChunk="none";PasteHookStatus.maxCommitResumeBeganExpired=false;PasteHookStatus.deadlineBudgetNanos.set(0);PasteHookStatus.deadlineRemainingNanosAtResumeEntry.set(0);PasteHookStatus.deadlineRemainingNanosAtFirstPlacement.set(-1);PasteHookStatus.resumeElapsedNanos.set(0);PasteHookStatus.placementsThisResume.set(0);PasteHookStatus.deadlineExpiredAtEntry.set(0);PasteHookStatus.deadlineExpiredAfterFirstPlacement.set(0);PasteHookStatus.commitStateElapsedWallMillis.set(0);PasteHookStatus.commitStateActiveServerMillis.set(0);PasteHookStatus.maxCommitResumePlacements.set(0);PasteHookStatus.maxCommitResumeChains.set(0);PasteHookStatus.maxDownstreamMutationNanos.set(0);}
    private static void resetPacingDiagnostics(){
        PasteHookStatus.submissionElapsedWallMillis.set(0);PasteHookStatus.submissionActiveServerMillis.set(0);PasteHookStatus.finalizationElapsedWallMillis.set(0);
        PasteHookStatus.totalCommitResumeNanos.set(0);PasteHookStatus.maxCommitResumeNanos.set(0);PasteHookStatus.medianCommitResumeLowerNanos.set(0);PasteHookStatus.medianCommitResumeUpperNanos.set(0);
        PasteHookStatus.totalResumeAllowanceNanos.set(0);PasteHookStatus.commitResumesOver50Millis.set(0);PasteHookStatus.maxStage3PreparationNanos.set(0);PasteHookStatus.maxDependencyChainNanos.set(0);
        PasteHookStatus.captureTargetNanos.set(PasteSliceBudget.INITIAL_NANOS);PasteHookStatus.submissionTargetNanos.set(PasteSliceBudget.INITIAL_NANOS);PasteHookStatus.commitTargetNanos.set(PasteSliceBudget.INITIAL_NANOS);
        PasteHookStatus.commitBudgetIncreases.set(0);PasteHookStatus.commitBudgetDecreases.set(0);PasteHookStatus.commitPacingStage="none";PasteHookStatus.childResumeStage="none";PasteHookStatus.maxDownstreamMutationDetail="none";
        PasteHookStatus.lastOperationPlanWallMillis.set(0);PasteHookStatus.lastOperationCommitWallMillis.set(0);PasteHookStatus.lastOperationCommitActiveMillis.set(0);PasteHookStatus.sourceCaptureServerMillis.set(0);
        PasteHookStatus.captureWorkStage="PENDING";PasteHookStatus.captureSlices.set(0);PasteHookStatus.capturePagesAllocated.set(0);PasteHookStatus.maxCaptureSliceNanos.set(0);
    }
    private static boolean updateMax(AtomicLong target,long value){for(;;){long old=target.get();if(value<=old)return false;if(target.compareAndSet(old,value))return true;}}
    private static boolean reserve(long bytes){for(;;){long current=RETAINED.get();if(current+bytes>GLOBAL)return false;if(RETAINED.compareAndSet(current,current+bytes))return true;}}
    private static long ms(long start){return(System.nanoTime()-start)/1000000L;}
    public static AdaptiveServerBudget budget(){return BUDGET;}
    private DeferredPasteManager(){}
}
