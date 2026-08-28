package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.ChunkCommitResult;
import com.glowingfederal.worldeditoverdrive.backend.ForgeChunkWriter;
import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard;
import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import cpw.mods.fml.common.FMLLog;
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
    private final List<OverdriveOperation> operations = new ArrayList<OverdriveOperation>();
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

    /** Bounded submission: callers receive rejection rather than an unbounded hidden queue. */
    public void submit(final OverdriveOperation operation, final ChunkPreparationTask task) {
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

    public void finishSubmissions(OverdriveOperation operation) {
        synchronized (lock) { own(operation); operation.submissionsClosed=true; maybeComplete(operation); }
    }

    private void prepare(OverdriveOperation operation, ChunkPreparationTask task) {
        long start=System.nanoTime(); PreparedChunkChange change=null;
        try {
            synchronized (lock) { if (operation.state.isTerminal() || shutdown) { preparationFinished(operation); return; } }
            change=task.prepare(); if (change == null) throw new IllegalStateException("preparation returned null");
            long bytes=change.estimatedBytes();
            synchronized (lock) {
                operation.preparationNanos += System.nanoTime()-start;
                while (!canAccount(operation, bytes) && !shutdown && !operation.state.isTerminal()) lock.wait();
                if (shutdown || operation.state.isTerminal()) { preparationFinished(operation); return; }
                globalBytes+=bytes; peakGlobalBytes=Math.max(peakGlobalBytes, globalBytes);
                operation.bufferedBytes+=bytes; operation.peakBufferedBytes=Math.max(operation.peakBufferedBytes, operation.bufferedBytes);
                operation.ready.addLast(change); operation.prepared++; operation.preparedBlocks+=change.getChangedBlockCount();
                int dense=0, touched=0; for (int i=0;i<16;i++) if (change.getSection(i)!=null) { touched++; if(change.getSection(i).isDense()) dense++; }
                operation.denseSections+=dense; operation.sparseSections+=touched-dense;
                preparationFinished(operation); if (!operation.state.isTerminal()) operation.state=OperationState.READY;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); fail(operation, "preparation interrupted", change, interrupted);
        } catch (Exception exception) { fail(operation, "preparation", change, exception); }
        catch (Error error) { fail(operation, "preparation", change, error); throw error; }
    }

    private boolean canAccount(OverdriveOperation op, long bytes) {
        boolean oversize = bytes > config.maxPreparedBytes || bytes > config.maxPreparedBytesPerOperation;
        if (oversize) return globalBytes == 0 && op.bufferedBytes == 0; // one isolated oversize buffer
        return globalBytes+bytes<=config.maxPreparedBytes && op.bufferedBytes+bytes<=config.maxPreparedBytesPerOperation;
    }

    public void tick() {
        ServerThreadGuard.assertServerThread(); long start=System.nanoTime(); int commits=0;
        while (System.nanoTime()-start < config.commitBudgetNanos) {
            OverdriveOperation operation; PreparedChunkChange change;
            synchronized (lock) {
                operation=nextReady(); if (operation==null) break;
                if (operation.state.isTerminal()) { discardReady(operation); continue; }
                change=operation.ready.removeFirst(); operation.commitActive=true; operation.state=OperationState.COMMITTING;
            }
            long commitStart=System.nanoTime();
            try {
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
                    release(operation, change.estimatedBytes()); maybeComplete(operation);
                }
                commits++;
            } catch (Exception exception) { failCommit(operation, change, exception); }
            catch (Error error) { failCommit(operation, change, error); throw error; }
        }
        commitsThisTick=commits; commitNanosThisTick=System.nanoTime()-start;
    }

    private OverdriveOperation nextReady() {
        if (operations.isEmpty()) return null;
        for(int checked=0;checked<operations.size();checked++) {
            if(cursor>=operations.size()) cursor=0;
            OverdriveOperation op=operations.get(cursor++);
            if(!op.ready.isEmpty() && !op.state.isTerminal()) return op;
        }
        return null;
    }

    public boolean cancel(OverdriveOperation operation) {
        synchronized(lock) { own(operation); if(operation.state.isTerminal()) return false;
            operation.state=OperationState.CANCELLED; discardReady(operation); lock.notifyAll(); return true; }
    }

    private void failCommit(OverdriveOperation op, PreparedChunkChange change, Throwable cause) {
        synchronized(lock) { op.commitActive=false; release(op, change.estimatedBytes()); failLocked(op,cause); }
        FMLLog.severe("Overdrive operation %d commit failed at chunk %d,%d: %s", op.id,
                change.getChunkX(),change.getChunkZ(),cause.toString());
    }
    private void fail(OverdriveOperation op,String phase,PreparedChunkChange change,Throwable cause) {
        synchronized(lock) { preparationFinished(op); failLocked(op,cause); }
        FMLLog.severe("Overdrive operation %d %s failed%s: %s",op.id,phase,
                change==null?"":" at chunk "+change.getChunkX()+","+change.getChunkZ(),cause.toString());
    }
    private void failLocked(OverdriveOperation op,Throwable cause) {
        if(op.failure==null) op.failure=cause; if(op.state!=OperationState.CANCELLED) op.state=OperationState.FAILED;
        discardReady(op); lock.notifyAll();
    }
    private void preparationFinished(OverdriveOperation op) { op.finishedPreparations++; maybeComplete(op); }
    private void maybeComplete(OverdriveOperation op) {
        if(!op.state.isTerminal() && op.submissionsClosed && op.finishedPreparations==op.submitted
                && op.ready.isEmpty() && !op.commitActive && op.pendingSync==0) op.state=OperationState.COMPLETED;
    }
    private void discardReady(OverdriveOperation op) { while(!op.ready.isEmpty()) release(op,op.ready.removeFirst().estimatedBytes()); }
    private void release(OverdriveOperation op,long bytes) { op.bufferedBytes-=bytes; globalBytes-=bytes; lock.notifyAll(); }
    private void own(OverdriveOperation op) { if(op.coordinator!=this) throw new IllegalArgumentException("foreign operation"); }

    public CoordinatorStatistics statistics() { synchronized(lock) { int active=0,ready=0;
        for(OverdriveOperation op:operations){if(!op.state.isTerminal())active++;ready+=op.ready.size();}
        return new CoordinatorStatistics(active,ready,workers.getActiveCount(),commitsThisTick,globalBytes,
                config.maxPreparedBytes,commitNanosThisTick); }}

    OperationStatistics statistics(OverdriveOperation operation) {
        synchronized (lock) { own(operation); return new OperationStatistics(operation); }
    }

    /** Programmatic smoke path; deliberately not exposed through a command. */
    public OverdriveOperation submitSynthetic(WorldServer world, final PreparedChunkChange change) {
        OverdriveOperation op=createOperation(world,SideEffectPolicy.RAW);
        submit(op,new ChunkPreparationTask(){public PreparedChunkChange prepare(){return change;}});
        finishSubmissions(op); return op;
    }

    public void shutdown() {
        synchronized(lock) { if(shutdown)return; shutdown=true; for(OverdriveOperation op:operations)
            if(!op.state.isTerminal()){op.state=OperationState.CANCELLED;discardReady(op);} lock.notifyAll(); }
        workers.shutdownNow();
        try { workers.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
