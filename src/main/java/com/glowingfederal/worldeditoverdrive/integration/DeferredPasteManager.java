package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import com.glowingfederal.worldeditoverdrive.execution.AdaptiveServerBudget;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.*;
import com.sk89q.worldedit.extent.*;
import com.sk89q.worldedit.function.entity.ExtentEntityCopy;
import com.sk89q.worldedit.function.operation.*;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.util.Location;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Server-owned world work, bounded immutable pages, worker-owned disk I/O. */
public final class DeferredPasteManager {
    static final long GLOBAL=128L<<20, PER_OPERATION=64L<<20;
    static final PasteMemoryBudget MEMORY=new PasteMemoryBudget(GLOBAL,PER_OPERATION);
    private static final AdaptiveServerBudget BUDGET=new AdaptiveServerBudget();
    private static final Queue<Owner> OWNERS=new ArrayDeque<Owner>();
    static final AtomicLong SEQUENCE=new AtomicLong();
    private static final Map<LocalSession,Object> SESSION_OWNERS=new IdentityHashMap<LocalSession,Object>();
    static synchronized boolean acquireSession(LocalSession s,Object owner){Object held=SESSION_OWNERS.get(s);if(held==null){SESSION_OWNERS.put(s,owner);return true;}return held==owner;}
    static synchronized void releaseSession(LocalSession s,Object owner){if(SESSION_OWNERS.get(s)==owner)SESSION_OWNERS.remove(s);}
    static synchronized boolean hasEarlierWork(LocalSession s,long order){for(Owner o:OWNERS)if(o.session==s&&o.order<order)return true;return false;}
    static final ExecutorService WORKERS=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),new ThreadFactory(){
        final AtomicLong sequence=new AtomicLong();
        public Thread newThread(Runnable r){Thread t=new Thread(r,"worldedit-overdrive-paste-"+sequence.incrementAndGet());t.setDaemon(true);return t;}
    });
    static final class AdmissionRejectedException extends Exception {AdmissionRejectedException(String reason){super(reason);}}
    public static synchronized void register(ForwardExtentCopy op,PasteOperationAdapter adapter,Player player,LocalSession session,boolean select)throws Exception{
        if(op==null||adapter==null||player==null||session==null)throw new NullPointerException("paste context");
        OWNERS.add(new Owner(adapter,player,session,select));PasteHookStatus.pasteDeferredActive.incrementAndGet();
    }
    static synchronized boolean hasWork(LocalSession session){for(Owner o:OWNERS)if(o.session==session)return true;return false;}
    public static void tick(long normalTickNanos){
        long started=System.nanoTime(),deadline=started+BUDGET.beginTick(normalTickNanos);
        Owner[] snapshot;synchronized(DeferredPasteManager.class){snapshot=OWNERS.toArray(new Owner[OWNERS.size()]);}
        for(Owner o:snapshot){if(System.nanoTime()>=deadline)break;try{if(o.tick(deadline))remove(o,true,null);else rotate(o);}catch(Throwable e){remove(o,false,e);}}
        HistoryReplayBridge.tick(deadline);
        PasteHookStatus.pasteGlobalLiveMemoryBytes.set(MEMORY.live());PasteHookStatus.pasteGlobalPeakLiveMemoryBytes.set(MEMORY.peak());BUDGET.endTick(System.nanoTime()-started);
    }
    private static synchronized void rotate(Owner o){if(OWNERS.remove(o))OWNERS.add(o);}
    private static void remove(Owner o,boolean success,Throwable e){
        synchronized(DeferredPasteManager.class){if(!OWNERS.remove(o))return;}
        if(o.commitActive)PasteHookStatus.pasteCommitActive.decrementAndGet();PasteHookStatus.pasteDeferredActive.decrementAndGet();
        if(success)PasteHookStatus.pasteDeferredCompleted.incrementAndGet();
        else{
            o.lifecycle.fail(e);PasteHookStatus.pasteDeferredFailed.incrementAndGet();PasteHookStatus.lastPasteDeferredReason="failed: "+e;
            o.player.printError("Paste failed: "+e.getMessage());OverdriveLog.error("deferred paste failed: {}",e.toString());
            if(o.mutation&&o.history!=null){o.keepHistory=true;o.session.remember(o.adapter.destination);}
        }
        if(o.extent!=null)try{PasteExtentInstaller.restore(o.adapter.destination,o.extent);}catch(Exception restore){OverdriveLog.error("paste extent restoration failed: {}",restore.toString());}
        o.release(!success);
    }
    public static synchronized void cancelAll(){
        for(Owner o:OWNERS){o.lifecycle.cancel();o.release(true);}
        PasteHookStatus.pasteDeferredActive.addAndGet(-OWNERS.size());OWNERS.clear();HistoryReplayBridge.cancelAll();
    }
    static final class Owner implements MutationOperationOwner {
        enum State{STARTING,CAPTURING,PLANNING,SUBMITTING,FLUSHING,ENTITY_CAPTURE,COMMITTING,FINALIZING,FINISHING,COMPLETE}
        final PasteOperationAdapter adapter;final Player player;final LocalSession session;final boolean select;
        final PasteContinuationOperation lifecycle=new PasteContinuationOperation();
        final PasteMemoryBudget.Account memory;
        final PasteStreamStorage storage;
        final long operationStarted=System.nanoTime();final int minX,minY,minZ,sizeX,sizeY,sizeZ,volume;
        final long order=SEQUENCE.incrementAndGet();
        final PasteNbtSizer sizer=new PasteNbtSizer();
        final PasteSliceBudget captureBudget=new PasteSliceBudget(),submissionBudget=new PasteSliceBudget(),reorderBudget=new PasteSliceBudget();
        final PasteResumeStatistics resumeStatistics=new PasteResumeStatistics();
        PasteMemoryBudget.Ticket descriptor,stateMemory,workspace,pageMemory,planningMemory;
        final PasteMemoryBudget.Ticket[] payloads=new PasteMemoryBudget.Ticket[PreparedClipboardView.PAGE_SIZE];
        PreparedClipboardView.Builder capture;PreparedClipboardView prepared;
        BaseBlock pendingBlock,previous;Vector pendingDestination;long pendingBytes,previousBytes;
        boolean previousRead,commitActive,mutation,keepHistory,downstreamStarted,planningActive;
        volatile boolean cancelled,busy;volatile Throwable workerFailure;volatile Object workerResult;
        State state=State.STARTING,afterFlush;int captureIndex,pageOffset,entityCaptureCursor,entityLimit,commitStage=1,batchOffset;
        long pagesAllocated,pagesReleased,planned,submitted,committed,matched,ignored,air,tiles,preparedEntities,committedEntities;
        long captureNanos,submissionNanos,commitNanos,finalizationNanos,planningNanos,commitStarted,reorderStarted,finalizationStarted,backpressure,captureFinished;
        int[] indices;List<? extends Entity> sourceEntities;PreparedClipboardView.EntitySnapshot pendingEntity;
        PasteStreamStorage.Batch batch;PasteStreamingExtent extent;PasteDiskHistory history;Operation downstream;String pressure="none";
        final PasteCommitCursor commitCursor=new PasteCommitCursor();
        Owner(PasteOperationAdapter adapter,Player player,LocalSession session,boolean select)throws Exception{
            this(adapter,player,session,select,MEMORY);
        }
        Owner(PasteOperationAdapter adapter,Player player,LocalSession session,boolean select,PasteMemoryBudget budget)throws Exception{
            this.adapter=adapter;this.player=player;this.session=session;this.select=select;
            memory=budget.account();storage=new PasteStreamStorage(memory);
            PasteOperationAdapter.Eligibility e=adapter.accelerationEligibility();if(e.kind!=PasteOperationAdapter.Eligibility.Kind.ACCELERATE)throw new IllegalArgumentException(e.reason);
            Vector min=adapter.clipboard.getMinimumPoint(),max=adapter.clipboard.getMaximumPoint();minX=min.getBlockX();minY=min.getBlockY();minZ=min.getBlockZ();
            long x=(long)max.getBlockX()-minX+1,y=(long)max.getBlockY()-minY+1,z=(long)max.getBlockZ()-minZ+1;
            if(x<=0||y<=0||z<=0||x>Integer.MAX_VALUE||y>Integer.MAX_VALUE||z>Integer.MAX_VALUE||x*y>Integer.MAX_VALUE/z)throw new AdmissionRejectedException("Invalid or overflowing clipboard dimensions");
            sizeX=(int)x;sizeY=(int)y;sizeZ=(int)z;volume=(int)(x*y*z);
            descriptor=memory.admitDescriptor();
            resetOperationDiagnostics();resetPacingDiagnostics();PasteHookStatus.pasteEstimatedTotalSourceBytes.set(128L+(long)volume*32);
            PasteHookStatus.pasteMemoryBudgetBytes.set(memory.limit());PasteHookStatus.lastPasteAdmissionRejection=null;
            PasteHookStatus.lastPasteTransform=adapter.transform.getClass().getName();PasteHookStatus.lastPasteIgnoreAir=adapter.ignoreAir;
            PasteHookStatus.queueEnabled=adapter.destination.isQueueEnabled();PasteHookStatus.incrementalCommitSupported=true;
            PasteHookStatus.queueImplementationClass=PasteStreamingExtent.class.getName();PasteHookStatus.editSessionExtentClass=EditSession.class.getName();
            PasteHookStatus.activePhase="PREPARING";PasteHookStatus.captureWorkStage="ADMITTED";lifecycle.submitted();
        }
        private void job(final Callable<?> action){job(action,false);}
        private void job(final Callable<?> action,final boolean planning){
            if(busy)throw new IllegalStateException("overlapping paste worker");busy=true;workerResult=null;
            PasteHookStatus.pasteWorkerTasksSubmitted.incrementAndGet();PasteHookStatus.workerQueuedChunks.incrementAndGet();
            WORKERS.execute(new Runnable(){public void run(){
                long start=System.nanoTime(),active=PasteHookStatus.pasteWorkerActive.incrementAndGet();updateMax(PasteHookStatus.pasteWorkerMaxConcurrency,active);
                try{if(!cancelled)workerResult=action.call();}catch(Throwable e){workerFailure=e;}
                finally{long elapsed=System.nanoTime()-start;if(planning){planningNanos+=elapsed;PasteHookStatus.pasteWorkerPlanNanos.addAndGet(elapsed);}else PasteHookStatus.pasteWorkerIoNanos.addAndGet(elapsed);
                    PasteHookStatus.pasteWorkerTasksCompleted.incrementAndGet();PasteHookStatus.workerCompletedChunks.incrementAndGet();PasteHookStatus.pasteWorkerActive.decrementAndGet();
                    if(cancelled)cleanup();busy=false;}
            }});
        }
        public boolean tick(long deadline)throws Exception{
            long start=System.nanoTime();State phase=state;
            PasteSliceBudget pace=phase==State.CAPTURING||phase==State.ENTITY_CAPTURE?captureBudget:phase==State.SUBMITTING?submissionBudget:phase==State.COMMITTING?reorderBudget:null;
            if(pace!=null)deadline=Math.min(deadline,start+pace.targetNanos());long allowance=Math.max(0,deadline-start);pressure="none";
            try{
                if(workerFailure!=null)throw new Exception("paste worker failed",workerFailure);
                if(busy){pressure="WORKER_IO";return false;}
                if(state==State.STARTING){
                    if(HistoryReplayBridge.hasEarlierWork(session,order)||!acquireSession(session,this)){pressure="SESSION_WAIT";return false;}
                    if(stateMemory==null){stateMemory=memory.acquire(PasteMemoryBudget.Kind.STATE,PasteStreamStorage.STATE_BYTES);if(stateMemory==null)return pressure("GLOBAL_STATE");workspace=stateMemory.split(PasteMemoryBudget.Kind.WORKER,192L<<10);}
                    lifecycle.running();job(new Callable<Object>(){public Object call()throws Exception{storage.initialize();return null;}});state=State.FLUSHING;afterFlush=State.CAPTURING;return false;
                }
                if(state==State.FLUSHING){
                    if(extent==null){extent=PasteExtentInstaller.install(adapter.destination,storage,true);history=(PasteDiskHistory)adapter.destination.getChangeSet();}
                    if(afterFlush==State.CAPTURING)releasePage();state=afterFlush;workerResult=null;return false;
                }
                if(state==State.CAPTURING){capture(deadline);return false;}
                if(state==State.PLANNING){
                    if(planningMemory==null){planningMemory=memory.acquire(PasteMemoryBudget.Kind.PLANNING,16L<<10);if(planningMemory==null)return pressure("PLANNING");dispatchPlan();return false;}
                    if(workerResult==null)return false;indices=(int[])workerResult;workerResult=null;pageOffset=0;state=State.SUBMITTING;planningActive=false;PasteHookStatus.pastePlanningActive.decrementAndGet();
                    if(!commitActive){commitActive=true;commitStarted=System.nanoTime();lifecycle.committing();PasteHookStatus.pasteCommitActive.incrementAndGet();}
                }
                if(state==State.SUBMITTING){submit(deadline);return false;}
                if(state==State.ENTITY_CAPTURE){captureEntities(deadline);return false;}
                if(state==State.COMMITTING){commit(deadline);return false;}
                if(state==State.FINALIZING){entities(deadline);return false;}
                if(state==State.FINISHING){
                    history.seal();PasteExtentInstaller.restore(adapter.destination,extent);session.remember(adapter.destination);Vector to=adapter.destinationOrigin;
                    if(select){Vector max=to.add(adapter.region.getMaximumPoint().subtract(adapter.region.getMinimumPoint()));RegionSelector selector=new CuboidRegionSelector(player.getWorld(),to,max);session.setRegionSelector(player.getWorld(),selector);selector.learnChanges();selector.explainRegionAdjust(player,session);}
                    player.print("The clipboard has been pasted at "+to);lifecycle.complete();state=State.COMPLETE;PasteHookStatus.pasteAccelerated.incrementAndGet();PasteHookStatus.commitCompletedNormally=true;return true;
                }
                return state==State.COMPLETE;
            }finally{
                long n=System.nanoTime()-start;updateMax(PasteHookStatus.lastOperationMaxServerSliceMillis,n/1000000);
                if(phase==State.CAPTURING||phase==State.ENTITY_CAPTURE){captureNanos+=n;captureBudget.observe(n,allowance,state==phase);PasteHookStatus.captureSlices.incrementAndGet();updateMax(PasteHookStatus.maxCaptureSliceNanos,n);}
                else if(phase==State.SUBMITTING){submissionNanos+=n;submissionBudget.observe(n,allowance,state==phase);}
                else if(phase==State.COMMITTING){commitNanos+=n;reorderBudget.observe(n,allowance,state==phase);}else if(phase==State.FINALIZING)finalizationNanos+=n;
                publish();
            }
        }
        void capture(long deadline)throws Exception{
            if(captureIndex==volume){state=State.ENTITY_CAPTURE;return;}
            if(capture==null){
                pageMemory=memory.acquire(PasteMemoryBudget.Kind.CAPTURE,64L<<10);if(pageMemory==null){pressure("GLOBAL_CAPTURE");return;}
                capture=new PreparedClipboardView.Builder(0,0,0,Math.min(PreparedClipboardView.PAGE_SIZE,volume-captureIndex),1,1);pagesAllocated++;
            }
            while(System.nanoTime()<deadline&&capture.captured<capture.volume&&captureIndex<volume){
                if(pendingBlock==null){
                    int i=captureIndex,x=minX+i%sizeX,q=i/sizeX,z=minZ+q%sizeZ,y=minY+q/sizeZ;Vector source=new Vector(x,y,z);
                    if(!adapter.region.contains(source)){captureIndex++;PasteHookStatus.pasteOtherwiseFilteredCells.incrementAndGet();continue;}
                    pendingBlock=adapter.transformedSource.getBlock(source);pendingDestination=adapter.transform.apply(source.subtract(adapter.sourceOrigin)).add(adapter.destinationOrigin);
                    sizer.start(pendingBlock.getNbtData());pendingBytes=0;
                }
                pendingBytes+=sizer.resume(deadline,4096);PasteHookStatus.captureWorkStage=sizer.complete()?"BLOCKS":"NBT_ACCOUNTING";if(!sizer.complete())return;
                PasteMemoryBudget.Ticket payload=memory.acquire(PasteMemoryBudget.Kind.CAPTURE,512L+pendingBytes);
                if(payload==null){if(capture.captured>0){pendingBlock=null;flushCapture();return;}pressure("CAPTURE_PAYLOAD");return;}
                int index=capture.captured;payloads[index]=payload;capture.set(index,pendingBlock.getId(),pendingBlock.getData(),pendingDestination.getBlockX(),pendingDestination.getBlockY(),pendingDestination.getBlockZ(),pendingBlock.getNbtData()==null?null:new BaseBlock(pendingBlock));
                capture.bytes+=pendingBytes;captureIndex++;if(pendingBlock.getId()==0)air++;if(pendingBlock.getNbtData()!=null)tiles++;pendingBlock=null;
                if(capture.bytes>Math.min(1L<<20,memory.limit()/8)){flushCapture();return;}
            }
            if(capture.captured==capture.volume||captureIndex==volume){if(capture.captured>0)flushCapture();else{releasePage();state=State.ENTITY_CAPTURE;}}
        }
        void flushCapture(){prepared=capture.sealPrefix();capture=null;state=State.PLANNING;}
        void dispatchPlan(){
            final PreparedClipboardView view=prepared;
            planningActive=true;PasteHookStatus.pastePlanningActive.incrementAndGet();job(new Callable<Object>(){public Object call(){int[] result=new int[view.getVolume()];int count=0;for(int i=0;i<view.getVolume();i++)if(!adapter.ignoreAir||view.idAt(i)!=0)result[count++]=i;planned+=count;return Arrays.copyOf(result,count);}},true);
        }
        void submit(long deadline)throws Exception{
            int attempts=0,chunks=0;long last=Long.MIN_VALUE;
            while(pageOffset<indices.length&&attempts<4096&&System.nanoTime()<deadline){
                int i=indices[pageOffset];Vector position=new Vector(prepared.destinationX(i),prepared.destinationY(i),prepared.destinationZ(i));
                long chunk=((long)(position.getBlockX()>>4)<<32)^(position.getBlockZ()>>4&0xffffffffL);
                if(chunk!=last&&chunks>=2)break;if(chunk!=last){chunks++;last=chunk;}BaseBlock desired=prepared.blockAt(i);
                if(!previousRead){
                    previous=adapter.destination.getBlock(position);previousRead=true;
                    if(desired.getNbtData()==null&&previous.getId()==desired.getId()&&previous.getData()==desired.getData()){matched++;planned--;releaseCell(i);pageOffset++;previous=null;previousRead=false;continue;}
                    sizer.start(previous.getNbtData());previousBytes=0;
                }
                previousBytes+=sizer.resume(deadline,4096);if(!sizer.complete()){pressure="DESTINATION_NBT_ACCOUNTING";return;}
                long desiredBytes=Math.max(0,payloads[i].bytes()-512);
                if(!storage.beginSubmission(previousBytes,desiredBytes)){flush(State.SUBMITTING);pressure("SUBMISSION_MEMORY");return;}
                try{long direct=storage.directChanged,dt=storage.directTiles;int historyBefore=history.size();adapter.destination.setBlock(position,desired);if(history.size()==historyBefore)PasteHookStatus.pasteOtherwiseFilteredCells.incrementAndGet();committed+=storage.directChanged-direct;PasteHookStatus.pasteCommittedTiles.addAndGet(storage.directTiles-dt);mutation=true;submitted++;attempts++;}
                finally{storage.endSubmission();}
                releaseCell(i);pageOffset++;previous=null;previousRead=false;
            }
            PasteHookStatus.chunksSinceLastDrain.set(chunks);PasteHookStatus.submittedSinceLastDrain.set(attempts);
            if(pageOffset==indices.length){ignored+=prepared.getVolume()-indices.length;flush(State.CAPTURING);}
        }
        void flush(State next){afterFlush=next;state=State.FLUSHING;job(new Callable<Object>(){public Object call()throws Exception{storage.flush();return null;}});}
        void releaseCell(int i){if(payloads[i]!=null){payloads[i].close();payloads[i]=null;}prepared.releaseCell(i);}
        void releasePage(){
            if(prepared!=null||capture!=null)pagesReleased++;
            for(int i=0;i<payloads.length;i++)if(payloads[i]!=null){payloads[i].close();payloads[i]=null;}
            prepared=null;capture=null;indices=null;if(pageMemory!=null){pageMemory.close();pageMemory=null;}if(planningMemory!=null){planningMemory.close();planningMemory=null;}
        }

        void captureEntities(long deadline)throws Exception{
            if(sourceEntities==null){sourceEntities=adapter.clipboard.getEntities();entityLimit=sourceEntities.size();}
            while(System.nanoTime()<deadline){
                if(pendingEntity==null){
                    if(entityCaptureCursor==entityLimit){sourceEntities=null;captureFinished=System.nanoTime();flush(State.COMMITTING);reorderStarted=System.nanoTime();return;}
                    Entity e=sourceEntities.get(entityCaptureCursor++);if(!adapter.region.contains(e.getLocation().toVector()))continue;
                    BaseEntity state=e.getState();if(state==null)continue;pendingEntity=new PreparedClipboardView.EntitySnapshot(e.getLocation(),state);sizer.start(state.getNbtData());pendingBytes=0;
                }
                pendingBytes+=sizer.resume(deadline,4096);PasteHookStatus.captureWorkStage="ENTITIES";if(!sizer.complete())return;
                final PasteMemoryBudget.Ticket reservation=memory.acquire(PasteMemoryBudget.Kind.ENTITY,4096L+pendingBytes*3);
                if(reservation==null){pressure("ENTITY_MEMORY");return;}
                final PreparedClipboardView.EntitySnapshot source=pendingEntity;pendingEntity=null;final long bytes=pendingBytes;
                job(new Callable<Object>(){public Object call()throws Exception{
                    try{CaptureExtent sink=new CaptureExtent();new ExtentEntityCopy(adapter.sourceOrigin,sink,adapter.destinationOrigin,adapter.transform).apply(new SnapshotEntity(source,sink));
                        if(sink.last!=null){PasteDiskJournal.Record r=PasteDiskJournal.Record.entity(sink.last.location,sink.last.state,1024L+bytes);r.ticket=reservation.split(PasteMemoryBudget.Kind.ENTITY,1024L+bytes);storage.entities.append(r);storage.entities.flush();preparedEntities++;}return null;
                    }finally{reservation.close();}
                }});return;
            }
        }
        void commit(long deadline)throws Exception{
            if(commitStage<=3){
                if(commitCursor.batch==null){if(workerResult!=null){commitCursor.accept((PasteStreamStorage.Batch)workerResult);workerResult=null;}else{final int stage=commitStage;job(new Callable<Object>(){public Object call()throws Exception{return storage.readBatch(stage);}});return;}}
                long start=System.nanoTime(),changedBefore=commitCursor.changed,tilesBefore=commitCursor.tiles;PasteHookStatus.incrementalCommitSlices.incrementAndGet();
                commitCursor.apply(extent.getExtent(),deadline);long placements=commitCursor.placements;
                committed+=commitCursor.changed-changedBefore;PasteHookStatus.pasteCommittedTiles.addAndGet(commitCursor.tiles-tilesBefore);mutation|=placements!=0;
                long n=System.nanoTime()-start;resumeStatistics.record(n,Math.max(0,deadline-start));PasteHookStatus.commitResumeCalls.incrementAndGet();
                PasteHookStatus.placementsThisResume.set(placements);PasteHookStatus.childResumeStage="STREAM_STAGE_"+commitStage;PasteHookStatus.commitPacingStage=PasteHookStatus.childResumeStage;
                if(!commitCursor.consumed()){PasteHookStatus.deadlineYieldCount.incrementAndGet();return;}
                boolean done=commitCursor.finishBatch();if(commitCursor.pressure)pressure("COMMIT_READ_MEMORY");if(done){commitStage++;reorderBudget.resetStage();}return;
            }
            if(!downstreamStarted){downstream=adapter.destination.commit();downstreamStarted=true;PasteHookStatus.commitOperationClass=downstream==null?"none":downstream.getClass().getName();}
            RunContext run=new RunContext();
            while(downstream!=null&&System.nanoTime()<deadline){EnhancedReorderYieldBridge.beginSlice(deadline);try{downstream=downstream.resume(run);}finally{EnhancedReorderYieldBridge.endSlice();}}
            if(downstream!=null)return;state=State.FINALIZING;finalizationStarted=System.nanoTime();PasteHookStatus.topLevelCommitReturnedNull=true;
        }
        void entities(long deadline)throws Exception{
            if(batch==null){if(workerResult!=null){batch=(PasteStreamStorage.Batch)workerResult;workerResult=null;batchOffset=0;}else{job(new Callable<Object>(){public Object call()throws Exception{return storage.readBatch(4);}});return;}}
            int count=0;
            while(batchOffset<batch.records.size()&&count<16&&System.nanoTime()<deadline){
                PasteDiskJournal.Record r=batch.records.get(batchOffset);
                if(!storage.beginSubmission(0,Math.max(0,r.memory-1024))){flush(State.FINALIZING);pressure("ENTITY_HISTORY_MEMORY");return;}
                try{if(adapter.destination.createEntity(new Location(adapter.destination,r.ex,r.ey,r.ez,r.yaw,r.pitch),r.entity)!=null)committedEntities++;mutation=true;}finally{storage.endSubmission();}
                batchOffset++;r.release();count++;
            }
            if(batchOffset<batch.records.size())return;
            boolean done=batch.done,p=batch.pressure;batch.close();batch=null;if(p)pressure("ENTITY_READ_MEMORY");
            if(done){job(new Callable<Object>(){public Object call()throws Exception{storage.flush();storage.closeWorkFiles();return null;}});state=State.FINISHING;keepHistory=true;}else flush(State.FINALIZING);
        }
        boolean pressure(String reason){pressure=reason;backpressure++;return false;}
        void publish(){
            PasteHookStatus.activePhase=state==State.COMPLETE?"IDLE":state.name();if(state!=State.CAPTURING)PasteHookStatus.captureWorkStage=state.name();
            PasteHookStatus.snapshotProcessed.set(captureIndex);PasteHookStatus.snapshotTotalEstimate.set(volume);PasteHookStatus.pasteSourceCellsRemaining.set(volume-captureIndex);
            PasteHookStatus.capturePagesAllocated.set(pagesAllocated);PasteHookStatus.pasteCapturePagesReleased.set(pagesReleased);PasteHookStatus.pasteCapturePagesResident.set(pagesAllocated-pagesReleased);
            PasteHookStatus.pasteLiveMemoryBytes.set(memory.live());PasteHookStatus.pastePeakLiveMemoryBytes.set(memory.peak());
            PasteHookStatus.pasteCaptureMemoryBytes.set(memory.bytes(PasteMemoryBudget.Kind.CAPTURE));PasteHookStatus.pastePlanningMemoryBytes.set(memory.bytes(PasteMemoryBudget.Kind.PLANNING));
            PasteHookStatus.pasteCommitMemoryBytes.set(memory.bytes(PasteMemoryBudget.Kind.COMMIT));PasteHookStatus.pasteHistoryMemoryBytes.set(memory.bytes(PasteMemoryBudget.Kind.HISTORY));
            PasteHookStatus.pasteEntityMemoryBytes.set(memory.bytes(PasteMemoryBudget.Kind.ENTITY));PasteHookStatus.pasteWorkerMemoryBytes.set(memory.bytes(PasteMemoryBudget.Kind.WORKER));PasteHookStatus.pasteStateMemoryBytes.set(memory.bytes(PasteMemoryBudget.Kind.STATE));
            PasteHookStatus.pasteMemoryBackpressureYields.set(backpressure);PasteHookStatus.pasteMemoryBackpressureReason=pressure;PasteHookStatus.pasteSpillBytes.set(storage.diskBytes);
            PasteHookStatus.pastePreparedBlocks.set(captureIndex);PasteHookStatus.pasteSourceAirCells.set(air);PasteHookStatus.pasteIgnoreAirFilteredCells.set(ignored);PasteHookStatus.pasteDestinationMatchedCells.set(matched);
            PasteHookStatus.pastePreparedTiles.set(tiles);PasteHookStatus.pastePreparedEntities.set(preparedEntities);PasteHookStatus.pasteCommittedEntities.set(committedEntities);
            PasteHookStatus.pastePlannedBlocks.set(planned);PasteHookStatus.pasteSubmittedBlocks.set(submitted);PasteHookStatus.pasteCommittedBlocks.set(committed);
            long pending=state==State.COMMITTING?commitCursor.remaining():0;
            long first=storage.stage1==null?0:storage.stage1.count-storage.stage1Cursor+storage.stage1.pending.size();
            long second=storage.stage2==null?0:storage.stage2.count-storage.stage2Cursor+storage.stage2.pending.size();
            long third=commitStage>3?0:storage.index==null?0:storage.index.size()-storage.stage3Decoded;
            if(state==State.COMMITTING){if(commitStage==1)first+=pending;else if(commitStage==2)second+=pending;else if(commitStage==3)third+=pending;}
            long remaining=Math.max(0,first+second+third);PasteHookStatus.commitRemaining.set(remaining);PasteHookStatus.commitOperationRemaining.set(remaining);
            PasteHookStatus.reorderStage1Remaining.set(first);PasteHookStatus.reorderStage2Remaining.set(second);PasteHookStatus.reorderStage3Remaining.set(third);
            PasteHookStatus.pasteTransformedBlocks.set(adapter.transform.isIdentity()?0:captureIndex);
            PasteHookStatus.lastOperationWallMillis.set(ms(operationStarted));PasteHookStatus.lastPastePrepareMillis.set(captureNanos/1000000);
            PasteHookStatus.lastOperationSnapshotActiveMillis.set(captureNanos/1000000);PasteHookStatus.sourceCaptureServerMillis.set(captureNanos/1000000);
            PasteHookStatus.lastOperationSnapshotWallMillis.set(((captureFinished==0?System.nanoTime():captureFinished)-operationStarted)/1000000);PasteHookStatus.lastOperationPlanWallMillis.set(planningNanos/1000000);PasteHookStatus.lastPastePlanMillis.set(planningNanos/1000000);
            PasteHookStatus.submissionActiveServerMillis.set(submissionNanos/1000000);PasteHookStatus.submissionElapsedWallMillis.set(commitStarted==0?0:ms(commitStarted));
            PasteHookStatus.commitStateActiveServerMillis.set(commitNanos/1000000);PasteHookStatus.commitStateElapsedWallMillis.set(reorderStarted==0?0:ms(reorderStarted));
            PasteHookStatus.finalizationServerMillis.set(finalizationNanos/1000000);PasteHookStatus.finalizationElapsedWallMillis.set(finalizationStarted==0?0:ms(finalizationStarted));
            long active=submissionNanos+commitNanos+finalizationNanos;PasteHookStatus.lastPasteCommitMillis.set(active/1000000);PasteHookStatus.commitServerMillis.set(active/1000000);PasteHookStatus.lastOperationCommitActiveMillis.set(active/1000000);
            PasteHookStatus.lastOperationCommitWallMillis.set(commitStarted==0?0:ms(commitStarted));
            PasteHookStatus.captureTargetNanos.set(captureBudget.targetNanos());PasteHookStatus.submissionTargetNanos.set(submissionBudget.targetNanos());PasteHookStatus.commitTargetNanos.set(reorderBudget.targetNanos());
            PasteHookStatus.commitBudgetIncreases.set(reorderBudget.increases());PasteHookStatus.commitBudgetDecreases.set(reorderBudget.decreases());
        }
        public Phase phase(){return state==State.CAPTURING||state==State.ENTITY_CAPTURE?Phase.SNAPSHOTTING:state==State.PLANNING?Phase.PLANNING:state==State.FINISHING?Phase.FINALIZING:Phase.COMMITTING;}
        public void release(){release(true);}
        void release(boolean aborted){cancelled=true;releaseSession(session,this);storage.cancelled=aborted;if(!busy)WORKERS.execute(new Runnable(){public void run(){cleanup();}});}
        volatile boolean cleaned;
        synchronized void cleanup(){
            if(cleaned)return;cleaned=true;
            try{
                if(workerResult instanceof PasteStreamStorage.Batch)((PasteStreamStorage.Batch)workerResult).close();workerResult=null;
                if(batch!=null){batch.close();batch=null;}commitCursor.close();releasePage();pendingBlock=null;previous=null;pendingEntity=null;sourceEntities=null;
                if(keepHistory&&history!=null){storage.cancelled=false;storage.flush();history.seal();storage.closeWorkFiles();storage.historyBlocks.sealReadOnly();storage.historyEntities.sealReadOnly();history.retainDescriptor(stateMemory.split(PasteMemoryBudget.Kind.HISTORY,32768));}
                else{if(history!=null)history.detach();storage.close();}
            }catch(Throwable e){OverdriveLog.error("paste cleanup/history persistence failed: {}",e.toString());storage.releasePending();}
            finally{if(planningActive){planningActive=false;PasteHookStatus.pastePlanningActive.decrementAndGet();}if(workspace!=null){workspace.close();workspace=null;}if(stateMemory!=null){stateMemory.close();stateMemory=null;}if(descriptor!=null){descriptor.close();descriptor=null;}publish();}
        }
    }
    private static final class CaptureExtent extends NullExtent {
        PreparedClipboardView.EntitySnapshot last;
        public Entity createEntity(Location location,BaseEntity state){last=new PreparedClipboardView.EntitySnapshot(location,state);return new SnapshotEntity(last,this);}
    }
    private static final class SnapshotEntity implements Entity {
        final PreparedClipboardView.EntitySnapshot snapshot;final Extent extent;
        SnapshotEntity(PreparedClipboardView.EntitySnapshot s,Extent extent){snapshot=s;this.extent=extent;}
        public BaseEntity getState(){return new BaseEntity(snapshot.state);}public Location getLocation(){return snapshot.location;}public Extent getExtent(){return extent;}
        public boolean remove(){return false;}public <T>T getFacet(Class<? extends T> cls){return null;}
    }
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
    private static long ms(long start){return(System.nanoTime()-start)/1000000L;}
    public static AdaptiveServerBudget budget(){return BUDGET;}
    private DeferredPasteManager(){}
}
