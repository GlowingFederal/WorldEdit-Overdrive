package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.ChunkCommitResult;
import com.glowingfederal.worldeditoverdrive.backend.ForgeChunkWriter;
import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard;
import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Stage 3 owner for preparation, memory lifetime, fair commit, and synchronization. */
public final class OverdriveCoordinator {
    private final Object lock = new Object();
    private final OverdriveConfiguration config; private final ThreadPoolExecutor workers;
    private final ForgeChunkWriter writer = new ForgeChunkWriter();
    private final ChunkSynchronizer synchronizer = new ChunkSynchronizer();
    private final List<OperationPlan> operations = new ArrayList<OperationPlan>();
    private final AtomicLong ids = new AtomicLong();
    private long globalBytes, peakGlobalBytes; private int cursor; private volatile boolean shutdown;
    private volatile int commitsThisTick; private volatile long commitNanosThisTick;

    public OverdriveCoordinator(OverdriveConfiguration config) {
        if (config == null) throw new NullPointerException("config"); this.config=config;
        final AtomicInteger threadIds = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() { public Thread newThread(Runnable work) {
            Thread t = new Thread(work, "WorldEditOverdrive-Prepare-" + threadIds.incrementAndGet());
            t.setDaemon(true); return t;
        }};
        workers = new ThreadPoolExecutor(config.preparationWorkers, config.preparationWorkers, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(config.submissionCapacity), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public OverdriveOperation createOperation(WorldServer world, SideEffectPolicy policy) {
        if (world == null || policy == null) throw new NullPointerException("operation argument");
        synchronized (lock) {
            if (shutdown) throw new IllegalStateException("coordinator is shut down");
            OverdriveOperation operation = new OverdriveOperation(ids.incrementAndGet(), world, policy, this);
            operations.add(operation); return operation;
        }
    }

    public OperationPlan createPlan(WorldServer world,SideEffectPolicy policy,String kind,String sourceVolume,
            String semanticPolicy,PreparationClass preparationClass,List<CommitPhase> phases) {
        if(world==null||policy==null)throw new NullPointerException("operation argument");
        synchronized(lock){if(shutdown)throw new IllegalStateException("coordinator is shut down");
            OperationPlan plan=new OperationPlan(ids.incrementAndGet(),world,policy,this,kind,sourceVolume,
                    semanticPolicy,preparationClass,phases,FinalizationIntent.CHANGED_CHUNKS_ONCE);
            operations.add(plan);return plan;}
    }

    /** Bounded submission: callers receive rejection rather than an unbounded hidden queue. */
    public void submit(final OperationPlan operation, final ChunkPreparationTask task) {
        if (operation == null || task == null) throw new NullPointerException("submission argument");
        synchronized (lock) {
            own(operation); if (shutdown || operation.state.isTerminal() || operation.submissionsClosed)
                throw new RejectedExecutionException("operation does not accept preparation work");
            operation.submitted++; operation.state=OperationState.PREPARING;
        }
        try { workers.execute(new Runnable() { public void run() { prepare(operation, task); }}); }
        catch (RejectedExecutionException rejected) {
            synchronized (lock) { operation.submitted--; maybeComplete(operation); }
            throw rejected;
        }
    }

    public void submit(final OperationPlan operation,final OperationPreparationTask task){
        if(operation==null||task==null)throw new NullPointerException("submission argument");
        synchronized(lock){own(operation);if(shutdown||operation.state.isTerminal()||operation.submissionsClosed)
            throw new RejectedExecutionException("operation does not accept preparation work");operation.submitted++;operation.state=OperationState.PREPARING;}
        try{workers.execute(new Runnable(){public void run(){prepare(operation,task);}});}catch(RejectedExecutionException rejected){
            synchronized(lock){operation.submitted--;maybeComplete(operation);}throw rejected;}
    }

    private void prepare(OperationPlan operation,OperationPreparationTask task){long start=System.nanoTime();PreparedOperationChunk chunk=null;
        try{synchronized(lock){if(operation.state.isTerminal()||shutdown){preparationFinished(operation);return;}}
            chunk=task.prepare();if(chunk==null)throw new IllegalStateException("preparation returned null");long bytes=chunk.estimatedBytes();
            for(PreparedOperationChunk.PhasePartition part:chunk.getPartitions())if(part.phase<0||part.phase>=operation.phases.size())throw new IllegalArgumentException("unknown phase");
            synchronized(lock){operation.preparationNanos+=System.nanoTime()-start;while(!canAccount(operation,bytes)&&!shutdown&&!operation.state.isTerminal())lock.wait();
                if(shutdown||operation.state.isTerminal()){preparationFinished(operation);return;}account(operation,bytes);operation.chunkPlans.add(chunk);
                for(PreparedOperationChunk.PhasePartition part:chunk.getPartitions()){
                    operation.ready.get(part.phase).addLast(part);OperationPhaseProgress progress=operation.phaseProgress.get(part.phase);
                    progress.preparedUnits++;progress.readyUnits++;progress.bufferedBytes+=part.estimatedBytes();progress.peakBufferedBytes=Math.max(progress.peakBufferedBytes,progress.bufferedBytes);}
                operation.prepared++;preparationFinished(operation);if(!operation.state.isTerminal())operation.state=OperationState.READY;}
        }catch(InterruptedException e){Thread.currentThread().interrupt();fail(operation,"preparation",null,e);}
        catch(Exception e){fail(operation,"preparation",null,e);}}

    private void account(OperationPlan operation,long bytes){globalBytes+=bytes;peakGlobalBytes=Math.max(peakGlobalBytes,globalBytes);
        operation.bufferedBytes+=bytes;operation.peakBufferedBytes=Math.max(operation.peakBufferedBytes,operation.bufferedBytes);}

    public void finishSubmissions(OperationPlan operation) {
        synchronized (lock) { own(operation); operation.submissionsClosed=true; maybeComplete(operation); }
    }

    private void prepare(OperationPlan operation, ChunkPreparationTask task) {
        long start=System.nanoTime(); PreparedChunkChange change=null;
        try {
            synchronized (lock) { if (operation.state.isTerminal() || shutdown) { preparationFinished(operation); return; } }
            change=task.prepare(); if (change == null) throw new IllegalStateException("preparation returned null");
            long bytes=change.estimatedBytes();
            synchronized (lock) {
                operation.preparationNanos += System.nanoTime()-start;
                while (!canAccount(operation, bytes) && !shutdown && !operation.state.isTerminal()) lock.wait();
                if (shutdown || operation.state.isTerminal()) { preparationFinished(operation); return; }
                account(operation,bytes); PreparedOperationChunk envelope=PreparedOperationChunk.builder(change.getChunkX(),change.getChunkZ()).chunkPhase(0,change).build();
                operation.chunkPlans.add(envelope);operation.ready.get(0).addLast(envelope.getPartitions().get(0)); operation.prepared++; operation.preparedBlocks+=change.getChangedBlockCount();
                OperationPhaseProgress pp=operation.phaseProgress.get(0);pp.preparedUnits++;pp.readyUnits++;pp.bufferedBytes+=bytes;pp.peakBufferedBytes=Math.max(pp.peakBufferedBytes,pp.bufferedBytes);
                int dense=0, touched=0; for (int i=0;i<16;i++) if (change.getSection(i)!=null) { touched++; if(change.getSection(i).isDense()) dense++; }
                operation.denseSections+=dense; operation.sparseSections+=touched-dense;
                preparationFinished(operation); if (!operation.state.isTerminal()) operation.state=OperationState.READY;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); fail(operation, "preparation interrupted", change, interrupted);
        } catch (Exception exception) { fail(operation, "preparation", change, exception); }
        catch (Error error) { fail(operation, "preparation", change, error); throw error; }
    }

    private boolean canAccount(OperationPlan op, long bytes) {
        boolean oversize = bytes > config.maxPreparedBytes || bytes > config.maxPreparedBytesPerOperation;
        if (oversize) return globalBytes == 0 && op.bufferedBytes == 0; // one isolated oversize buffer
        return globalBytes+bytes<=config.maxPreparedBytes && op.bufferedBytes+bytes<=config.maxPreparedBytesPerOperation;
    }

    public void tick() {
        ServerThreadGuard.assertServerThread(); long start=System.nanoTime(); int commits=0;
        while (System.nanoTime()-start < config.commitBudgetNanos) {
            OperationPlan operation; PreparedOperationChunk.PhasePartition partition; PreparedChunkChange change;
            synchronized (lock) {
                operation=nextReady(); if (operation==null) break;
                if (operation.state.isTerminal()) { discardReady(operation); continue; }
                partition=operation.ready.get(operation.currentPhase).removeFirst();change=partition.chunkChange;
                OperationPhaseProgress pp=operation.phaseProgress.get(operation.currentPhase);pp.readyUnits--;pp.activeCommits++;
                operation.commitActive=true; operation.state=OperationState.COMMITTING;
            }
            long commitStart=System.nanoTime();
            try {
                if(partition.chunkChange==null){for(PreparedOperationChunk.OrderedPlacement placement:partition.ordered)placement.commit();
                    synchronized(lock){finishPartition(operation,partition);maybeComplete(operation);}commits++;continue;}
                ChunkCommitResult result=writer.commit(operation.world, change, operation.policy);
                Chunk chunk=operation.world.getChunkFromChunkCoords(change.getChunkX(), change.getChunkZ());
                synchronized (lock) { operation.pendingSync++; }
                ChunkSynchronizer.Strategy strategy=synchronizer.synchronize(operation.world, chunk, change, result,
                        config.sparsePacketThreshold, operation);
                synchronized (lock) {
                    operation.pendingSync--; operation.committed++; operation.committedBlocks+=result.getChangedBlocks();
                    operation.raw+=result.getRawBlocks(); operation.nativeCount+=result.getNativeBlocks();
                    if(strategy==ChunkSynchronizer.Strategy.CHUNK) operation.chunkPackets++;
                    else if(strategy==ChunkSynchronizer.Strategy.MULTI_BLOCK) operation.sparsePackets++;
                    operation.commitNanos+=System.nanoTime()-commitStart; operation.commitActive=false;
                    finishPartition(operation,partition); maybeComplete(operation);
                }
                commits++;
            } catch (Exception exception) { failCommit(operation, partition, exception); }
            catch (Error error) { failCommit(operation, partition, error); throw error; }
        }
        commitsThisTick=commits; commitNanosThisTick=System.nanoTime()-start;
    }

    private OperationPlan nextReady() {
        if (operations.isEmpty()) return null;
        for(int checked=0;checked<operations.size();checked++) {
            if(cursor>=operations.size()) cursor=0;
            OperationPlan op=operations.get(cursor++);
            if(op.currentPhase<op.ready.size()&&!op.ready.get(op.currentPhase).isEmpty()&&!op.state.isTerminal()) return op;
        }
        return null;
    }

    public boolean cancel(OperationPlan operation) {
        synchronized(lock) { own(operation); if(operation.state.isTerminal()) return false;
            operation.state=OperationState.CANCELLED; discardReady(operation); lock.notifyAll(); return true; }
    }

    private void failCommit(OperationPlan op, PreparedOperationChunk.PhasePartition part, Throwable cause) {
        synchronized(lock) { op.commitActive=false; releasePartition(op,part); failLocked(op,cause); }
        OverdriveLog.error("operation {} phase {} commit failed; mutationStarted=true: {}", op.id,op.currentPhase,cause.toString());
    }
    private void fail(OperationPlan op,String phase,PreparedChunkChange change,Throwable cause) {
        synchronized(lock) { preparationFinished(op); failLocked(op,cause); }
        OverdriveLog.error("operation {} {} failed{}; mutationStarted=false: {}",op.id,phase,
                change==null?"":" at chunk "+change.getChunkX()+","+change.getChunkZ(),cause.toString());
    }
    private void failLocked(OperationPlan op,Throwable cause) {
        if(op.failure==null) op.failure=cause; if(op.state!=OperationState.CANCELLED) op.state=OperationState.FAILED;
        discardReady(op); lock.notifyAll();
    }
    private void preparationFinished(OperationPlan op) { op.finishedPreparations++; maybeComplete(op); }
    private void maybeComplete(OperationPlan op) {
        if(!op.state.isTerminal() && op.submissionsClosed && op.finishedPreparations==op.submitted
                && !op.commitActive && op.pendingSync==0) {advancePhases(op);
            if(op.currentPhase==op.phases.size()&&allReadyEmpty(op)&&op.bufferedBytes==0)op.state=OperationState.COMPLETED;}
    }
    private void finishPartition(OperationPlan op,PreparedOperationChunk.PhasePartition part){OperationPhaseProgress p=op.phaseProgress.get(op.currentPhase);
        p.activeCommits--;p.committedUnits++;op.commitActive=false;releasePartition(op,part);if(!op.state.isTerminal())advancePhases(op);}
    private void advancePhases(OperationPlan op){while(op.currentPhase<op.phases.size()){OperationPhaseProgress p=op.phaseProgress.get(op.currentPhase);
        p.submissionsClosed=op.submissionsClosed;p.preparationFinished=op.finishedPreparations==op.submitted;
        if(!p.submissionsClosed||!p.preparationFinished||!op.ready.get(op.currentPhase).isEmpty()||p.activeCommits!=0||op.pendingSync!=0){if(op.phases.get(op.currentPhase).hasBarrierAfter())p.barrierWaits++;return;}
        p.synchronizedPhase=true;p.complete=true;p.finishedNanos=System.nanoTime();op.currentPhase++;}}
    private boolean allReadyEmpty(OperationPlan op){for(java.util.Deque<?> q:op.ready)if(!q.isEmpty())return false;return true;}
    private void discardReady(OperationPlan op) { for(int i=0;i<op.ready.size();i++)while(!op.ready.get(i).isEmpty())releasePartition(op,op.ready.get(i).removeFirst()); }
    private void releasePartition(OperationPlan op,PreparedOperationChunk.PhasePartition part){long bytes=part.estimatedBytes();OperationPhaseProgress p=op.phaseProgress.get(part.phase);p.bufferedBytes-=bytes;release(op,bytes);}
    private void release(OperationPlan op,long bytes) { op.bufferedBytes-=bytes; globalBytes-=bytes; lock.notifyAll(); }
    private void own(OperationPlan op) { if(op.coordinator!=this) throw new IllegalArgumentException("foreign operation"); }

    public CoordinatorStatistics statistics() { synchronized(lock) { int active=0,ready=0;
        for(OperationPlan op:operations){if(!op.state.isTerminal())active++;for(java.util.Deque<?> q:op.ready)ready+=q.size();}
        return new CoordinatorStatistics(active,ready,workers.getActiveCount(),commitsThisTick,globalBytes,
                config.maxPreparedBytes,commitNanosThisTick); }}

    OperationStatistics statistics(OperationPlan operation) {
        synchronized (lock) { own(operation); return new OperationStatistics(operation); }
    }

    /** Programmatic smoke path; deliberately not exposed through a command. */
    public OverdriveOperation submitSynthetic(WorldServer world, final PreparedChunkChange change) {
        OverdriveOperation op=createOperation(world,SideEffectPolicy.RAW);
        submit(op,new ChunkPreparationTask(){public PreparedChunkChange prepare(){return change;}});
        finishSubmissions(op); return op;
    }

    public void shutdown() {
        synchronized(lock) { if(shutdown)return; shutdown=true; for(OperationPlan op:operations)
            if(!op.state.isTerminal()){op.state=OperationState.CANCELLED;discardReady(op);} lock.notifyAll(); }
        workers.shutdownNow();
        try { workers.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
