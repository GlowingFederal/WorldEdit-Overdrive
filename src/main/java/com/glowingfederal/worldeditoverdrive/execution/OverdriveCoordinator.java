package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import com.glowingfederal.worldeditoverdrive.backend.ChunkCommitResult;
import com.glowingfederal.worldeditoverdrive.backend.ForgeChunkWriter;
import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard;
import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import com.glowingfederal.worldeditoverdrive.integration.MutationOperationOwner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Stage 3 owner for preparation, memory lifetime, fair commit, and
 * synchronization.
 */
public final class OverdriveCoordinator {
  private final Object lock = new Object();
  private final OverdriveConfiguration config;
  private final ThreadPoolExecutor workers;
  private final ForgeChunkWriter writer = new ForgeChunkWriter();
  private final ChunkSynchronizer synchronizer = new ChunkSynchronizer();
  private final List<OperationPlan> operations = new ArrayList<OperationPlan>();
  private final Queue<OwnedOperation> ownedOperations =
      new ArrayDeque<OwnedOperation>();
  private final Map<MutationOperationOwner, OwnedOperation>
      ownedOperationIndex =
          new IdentityHashMap<MutationOperationOwner, OwnedOperation>();
  private final Map<String, Integer> operationsByOwner =
      new java.util.HashMap<String, Integer>();
  private final AdaptiveServerBudget adaptiveBudget =
      new AdaptiveServerBudget();
  private final AtomicLong ids = new AtomicLong();
  private long globalBytes, peakGlobalBytes;
  private int cursor;
  private volatile boolean shutdown;
  private volatile int commitsThisTick;
  private volatile long commitNanosThisTick;

  private static final int MAX_OPERATIONS_PER_OWNER = 2;

  /**
   * Receives terminal owner transitions after all coordinator memory is
   * released.
   */
  public interface OwnerListener {
    void completed(long operationId);

    void failed(long operationId, Exception failure);

    void cancelled(long operationId);
  }

  private static final class OwnedOperation {
    private final long id;
    private final String ownerKey;
    private final MutationOperationOwner owner;
    private final OwnerListener listener;
    private long retainedBytes;

    private OwnedOperation(long id, String ownerKey,
                           MutationOperationOwner owner, OwnerListener listener,
                           long retainedBytes) {
      this.id = id;
      this.ownerKey = ownerKey;
      this.owner = owner;
      this.listener = listener;
      this.retainedBytes = retainedBytes;
    }
  }

  public OverdriveCoordinator(OverdriveConfiguration config) {
    if (config == null)
      throw new NullPointerException("config");
    this.config = config;
    final AtomicInteger threadIds = new AtomicInteger();
    ThreadFactory factory = new ThreadFactory() {
      public Thread newThread(Runnable work) {
        Thread t = new Thread(work, "WorldEditOverdrive-Prepare-" +
                                        threadIds.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    };
    workers = new ThreadPoolExecutor(
        config.preparationWorkers, config.preparationWorkers, 0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(config.submissionCapacity), factory,
        new ThreadPoolExecutor.AbortPolicy());
  }

  public OverdriveOperation createOperation(WorldServer world,
                                            SideEffectPolicy policy) {
    if (world == null || policy == null)
      throw new NullPointerException("operation argument");
    synchronized (lock) {
      if (shutdown)
        throw new IllegalStateException("coordinator is shut down");
      OverdriveOperation operation =
          new OverdriveOperation(ids.incrementAndGet(), world, policy, this);
      operations.add(operation);
      return operation;
    }
  }

  /**
   * Admits a server-thread operation before it captures any world state.
   * The initial reservation and per-owner slot are acquired atomically.
   */
  public long admitOwner(String ownerKey, MutationOperationOwner owner,
                         long initialBytes, OwnerListener listener) {
    if (ownerKey == null || owner == null || listener == null) {
      throw new NullPointerException("owner admission argument");
    }
    if (initialBytes < 0L ||
        initialBytes > config.maxPreparedBytesPerOperation) {
      throw new RejectedExecutionException(
          "operation memory reservation exceeds per-operation limit");
    }

    synchronized (lock) {
      if (shutdown) {
        throw new RejectedExecutionException("coordinator is shut down");
      }
      int ownerCount = countForOwner(ownerKey);
      if (ownerCount >= MAX_OPERATIONS_PER_OWNER) {
        throw new RejectedExecutionException(
            "owner already has the maximum number of queued operations");
      }
      if (globalBytes + initialBytes > config.maxPreparedBytes) {
        throw new RejectedExecutionException(
            "global operation memory limit reached");
      }

      OwnedOperation admitted = new OwnedOperation(
          ids.incrementAndGet(), ownerKey, owner, listener, initialBytes);
      ownedOperations.add(admitted);
      ownedOperationIndex.put(owner, admitted);
      operationsByOwner.put(ownerKey, Integer.valueOf(ownerCount + 1));
      globalBytes += initialBytes;
      peakGlobalBytes = Math.max(peakGlobalBytes, globalBytes);
      return admitted.id;
    }
  }

  /**
   * Adjusts an admitted owner's reservation without allowing either memory cap
   * to be exceeded.
   */
  public boolean resizeOwnerReservation(MutationOperationOwner owner,
                                        long wantedBytes) {
    synchronized (lock) {
      OwnedOperation admitted = ownedOperationIndex.get(owner);
      if (admitted == null || wantedBytes < 0L ||
          wantedBytes > config.maxPreparedBytesPerOperation) {
        return false;
      }
      long difference = wantedBytes - admitted.retainedBytes;
      if (difference > 0L &&
          globalBytes + difference > config.maxPreparedBytes) {
        return false;
      }
      admitted.retainedBytes = wantedBytes;
      globalBytes += difference;
      peakGlobalBytes = Math.max(peakGlobalBytes, globalBytes);
      return true;
    }
  }

  /**
   * Uses the coordinator's single bounded worker pool and bounded submission
   * queue.
   */
  public void submitOwnerPreparation(MutationOperationOwner owner,
                                     Runnable task) {
    if (owner == null || task == null) {
      throw new NullPointerException("owner preparation argument");
    }
    synchronized (lock) {
      if (shutdown || !ownedOperationIndex.containsKey(owner)) {
        throw new RejectedExecutionException("operation is not active");
      }
    }
    workers.execute(task);
  }

  public OperationPlan createPlan(WorldServer world, SideEffectPolicy policy,
                                  String kind, String sourceVolume,
                                  String semanticPolicy,
                                  PreparationClass preparationClass,
                                  List<CommitPhase> phases) {
    if (world == null || policy == null)
      throw new NullPointerException("operation argument");
    synchronized (lock) {
      if (shutdown)
        throw new IllegalStateException("coordinator is shut down");
      OperationPlan plan =
          new OperationPlan(ids.incrementAndGet(), world, policy, this, kind,
                            sourceVolume, semanticPolicy, preparationClass,
                            phases, FinalizationIntent.CHANGED_CHUNKS_ONCE);
      operations.add(plan);
      return plan;
    }
  }

  /**
   * Bounded submission: callers receive rejection rather than an unbounded
   * hidden queue.
   */
  public void submit(final OperationPlan operation,
                     final ChunkPreparationTask task) {
    if (operation == null || task == null)
      throw new NullPointerException("submission argument");
    synchronized (lock) {
      own(operation);
      if (shutdown || operation.state.isTerminal() ||
          operation.submissionsClosed)
        throw new RejectedExecutionException(
            "operation does not accept preparation work");
      operation.submitted++;
      operation.state = OperationState.PREPARING;
    }
    try {
      workers.execute(new Runnable() {
        public void run() { prepare(operation, task); }
      });
    } catch (RejectedExecutionException rejected) {
      synchronized (lock) {
        operation.submitted--;
        maybeComplete(operation);
      }
      throw rejected;
    }
  }

  public void submit(final OperationPlan operation,
                     final OperationPreparationTask task) {
    if (operation == null || task == null)
      throw new NullPointerException("submission argument");
    synchronized (lock) {
      own(operation);
      if (shutdown || operation.state.isTerminal() ||
          operation.submissionsClosed)
        throw new RejectedExecutionException(
            "operation does not accept preparation work");
      operation.submitted++;
      operation.state = OperationState.PREPARING;
    }
    try {
      workers.execute(new Runnable() {
        public void run() { prepare(operation, task); }
      });
    } catch (RejectedExecutionException rejected) {
      synchronized (lock) {
        operation.submitted--;
        maybeComplete(operation);
      }
      throw rejected;
    }
  }

  private void prepare(OperationPlan operation, OperationPreparationTask task) {
    long start = System.nanoTime();
    PreparedOperationChunk chunk = null;
    try {
      synchronized (lock) {
        if (operation.state.isTerminal() || shutdown) {
          preparationFinished(operation);
          return;
        }
      }
      chunk = task.prepare();
      if (chunk == null)
        throw new IllegalStateException("preparation returned null");
      long bytes = chunk.estimatedBytes();
      for (PreparedOperationChunk.PhasePartition part : chunk.getPartitions())
        if (part.phase < 0 || part.phase >= operation.phases.size())
          throw new IllegalArgumentException("unknown phase");
      synchronized (lock) {
        operation.preparationNanos += System.nanoTime() - start;
        while (!canAccount(operation, bytes) && !shutdown &&
               !operation.state.isTerminal())
          lock.wait();
        if (shutdown || operation.state.isTerminal()) {
          preparationFinished(operation);
          return;
        }
        account(operation, bytes);
        operation.chunkPlans.add(chunk);
        for (PreparedOperationChunk.PhasePartition part :
             chunk.getPartitions()) {
          operation.ready.get(part.phase).addLast(part);
          OperationPhaseProgress progress =
              operation.phaseProgress.get(part.phase);
          progress.preparedUnits++;
          progress.readyUnits++;
          progress.bufferedBytes += part.estimatedBytes();
          progress.peakBufferedBytes =
              Math.max(progress.peakBufferedBytes, progress.bufferedBytes);
        }
        operation.prepared++;
        preparationFinished(operation);
        if (!operation.state.isTerminal())
          operation.state = OperationState.READY;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(operation, "preparation", null, e);
    } catch (Exception e) {
      fail(operation, "preparation", null, e);
    }
  }

  private void account(OperationPlan operation, long bytes) {
    globalBytes += bytes;
    peakGlobalBytes = Math.max(peakGlobalBytes, globalBytes);
    operation.bufferedBytes += bytes;
    operation.peakBufferedBytes =
        Math.max(operation.peakBufferedBytes, operation.bufferedBytes);
  }

  public void finishSubmissions(OperationPlan operation) {
    synchronized (lock) {
      own(operation);
      operation.submissionsClosed = true;
      maybeComplete(operation);
    }
  }

  private void prepare(OperationPlan operation, ChunkPreparationTask task) {
    long start = System.nanoTime();
    PreparedChunkChange change = null;
    try {
      synchronized (lock) {
        if (operation.state.isTerminal() || shutdown) {
          preparationFinished(operation);
          return;
        }
      }
      change = task.prepare();
      if (change == null)
        throw new IllegalStateException("preparation returned null");
      long bytes = change.estimatedBytes();
      synchronized (lock) {
        operation.preparationNanos += System.nanoTime() - start;
        while (!canAccount(operation, bytes) && !shutdown &&
               !operation.state.isTerminal())
          lock.wait();
        if (shutdown || operation.state.isTerminal()) {
          preparationFinished(operation);
          return;
        }
        account(operation, bytes);
        PreparedOperationChunk envelope =
            PreparedOperationChunk
                .builder(change.getChunkX(), change.getChunkZ())
                .chunkPhase(0, change)
                .build();
        operation.chunkPlans.add(envelope);
        operation.ready.get(0).addLast(envelope.getPartitions().get(0));
        operation.prepared++;
        operation.preparedBlocks += change.getChangedBlockCount();
        OperationPhaseProgress pp = operation.phaseProgress.get(0);
        pp.preparedUnits++;
        pp.readyUnits++;
        pp.bufferedBytes += bytes;
        pp.peakBufferedBytes = Math.max(pp.peakBufferedBytes, pp.bufferedBytes);
        int dense = 0, touched = 0;
        for (int i = 0; i < 16; i++)
          if (change.getSection(i) != null) {
            touched++;
            if (change.getSection(i).isDense())
              dense++;
          }
        operation.denseSections += dense;
        operation.sparseSections += touched - dense;
        preparationFinished(operation);
        if (!operation.state.isTerminal())
          operation.state = OperationState.READY;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(operation, "preparation interrupted", change, interrupted);
    } catch (Exception exception) {
      fail(operation, "preparation", change, exception);
    } catch (Error error) {
      fail(operation, "preparation", change, error);
      throw error;
    }
  }

  private boolean canAccount(OperationPlan op, long bytes) {
    boolean oversize = bytes > config.maxPreparedBytes ||
                       bytes > config.maxPreparedBytesPerOperation;
    if (oversize)
      return globalBytes == 0 &&
          op.bufferedBytes == 0; // one isolated oversize buffer
    return globalBytes + bytes <= config.maxPreparedBytes &&
        op.bufferedBytes + bytes <= config.maxPreparedBytesPerOperation;
  }

  public void tick(long normalTickNanos) {
    ServerThreadGuard.assertServerThread();
    long start = System.nanoTime();
    long deadline = start + Math.min(config.commitBudgetNanos,
                                     adaptiveBudget.beginTick(normalTickNanos));
    int commits = tickOwnedOperations(deadline);
    while (System.nanoTime() < deadline) {
      OperationPlan operation;
      PreparedOperationChunk.PhasePartition partition;
      PreparedChunkChange change;
      synchronized (lock) {
        operation = nextReady();
        if (operation == null)
          break;
        if (operation.state.isTerminal()) {
          discardReady(operation);
          continue;
        }
        partition = operation.ready.get(operation.currentPhase).removeFirst();
        change = partition.chunkChange;
        OperationPhaseProgress pp =
            operation.phaseProgress.get(operation.currentPhase);
        pp.readyUnits--;
        pp.activeCommits++;
        operation.commitActive = true;
        operation.state = OperationState.COMMITTING;
      }
      long commitStart = System.nanoTime();
      try {
        if (partition.chunkChange == null) {
          for (PreparedOperationChunk.OrderedPlacement placement :
               partition.ordered)
            placement.commit();
          synchronized (lock) {
            finishPartition(operation, partition);
            maybeComplete(operation);
          }
          commits++;
          continue;
        }
        ChunkCommitResult result =
            writer.commit(operation.world, change, operation.policy);
        Chunk chunk = operation.world.getChunkFromChunkCoords(
            change.getChunkX(), change.getChunkZ());
        synchronized (lock) { operation.pendingSync++; }
        ChunkSynchronizer.SynchronizationResult synchronization =
            synchronizer.synchronize(operation.world, chunk, change, result,
                                     config.sparsePacketThreshold);
        ChunkSynchronizer.Strategy strategy = synchronization.getStrategy();
        synchronized (lock) {
          operation.pendingSync--;
          operation.committed++;
          operation.committedBlocks += result.getChangedBlocks();
          operation.raw += result.getRawBlocks();
          operation.nativeCount += result.getNativeBlocks();
          if (strategy == ChunkSynchronizer.Strategy.CHUNK)
            operation.chunkPackets++;
          else if (strategy == ChunkSynchronizer.Strategy.MULTI_BLOCK)
            operation.sparsePackets++;
          operation.tilePackets += synchronization.getTilePackets();
          operation.commitNanos += System.nanoTime() - commitStart;
          operation.commitActive = false;
          finishPartition(operation, partition);
          maybeComplete(operation);
        }
        commits++;
      } catch (Exception exception) {
        failCommit(operation, partition, exception);
      } catch (Error error) {
        failCommit(operation, partition, error);
        throw error;
      }
    }
    commitsThisTick = commits;
    commitNanosThisTick = System.nanoTime() - start;
    adaptiveBudget.endTick(commitNanosThisTick);
  }

  /** Retained for the Stage 3 programmatic API. */
  public void tick() { tick(0L); }

  private int tickOwnedOperations(long deadline) {
    int slices = 0;
    while (System.nanoTime() < deadline) {
      OwnedOperation operation;
      synchronized (lock) { operation = ownedOperations.poll(); }
      if (operation == null) {
        break;
      }

      boolean complete;
      try {
        complete = operation.owner.tick(deadline);
      } catch (Exception failure) {
        finishOwnedOperation(operation, failure, false);
        slices++;
        continue;
      } catch (Error failure) {
        finishOwnedOperation(
            operation,
            new Exception("operation aborted by an unrecoverable error",
                          failure),
            false);
        throw failure;
      }

      slices++;
      if (complete) {
        finishOwnedOperation(operation, null, false);
      } else {
        synchronized (lock) {
          if (ownedOperationIndex.containsKey(operation.owner)) {
            ownedOperations.add(operation);
          }
        }
      }
    }
    return slices;
  }

  private void finishOwnedOperation(OwnedOperation operation, Exception failure,
                                    boolean cancelled) {
    synchronized (lock) {
      if (ownedOperationIndex.remove(operation.owner) == null) {
        return;
      }
      ownedOperations.remove(operation);
      globalBytes -= operation.retainedBytes;
      decrementOwnerCount(operation.ownerKey);
      lock.notifyAll();
    }

    operation.owner.release();
    if (cancelled) {
      operation.listener.cancelled(operation.id);
    } else if (failure == null) {
      operation.listener.completed(operation.id);
    } else {
      operation.listener.failed(operation.id, failure);
    }
  }

  public boolean cancelOwner(long operationId) {
    OwnedOperation found = null;
    synchronized (lock) {
      for (OwnedOperation operation : ownedOperations) {
        if (operation.id == operationId) {
          MutationOperationOwner.Phase phase = operation.owner.phase();
          if (phase == MutationOperationOwner.Phase.COMMITTING ||
              phase == MutationOperationOwner.Phase.FINALIZING) {
            return false;
          }
          found = operation;
          break;
        }
      }
    }
    if (found == null) {
      return false;
    }
    finishOwnedOperation(found, null, true);
    return true;
  }

  private int countForOwner(String ownerKey) {
    Integer count = operationsByOwner.get(ownerKey);
    return count == null ? 0 : count.intValue();
  }

  private void decrementOwnerCount(String ownerKey) {
    int count = countForOwner(ownerKey);
    if (count <= 1) {
      operationsByOwner.remove(ownerKey);
    } else {
      operationsByOwner.put(ownerKey, Integer.valueOf(count - 1));
    }
  }

  public AdaptiveServerBudget adaptiveBudget() { return adaptiveBudget; }

  /**
   * Immutable diagnostic lines for command output; no live owner object
   * escapes.
   */
  public List<String> ownedOperationDescriptions() {
    synchronized (lock) {
      List<String> descriptions = new ArrayList<String>(ownedOperations.size());
      for (OwnedOperation operation : ownedOperations) {
        descriptions.add("id=" + operation.id + " owner=" + operation.ownerKey +
                         " phase=" + operation.owner.phase() +
                         " retainedBytes=" + operation.retainedBytes);
      }
      return descriptions;
    }
  }

  private OperationPlan nextReady() {
    if (operations.isEmpty())
      return null;
    for (int checked = 0; checked < operations.size(); checked++) {
      if (cursor >= operations.size())
        cursor = 0;
      OperationPlan op = operations.get(cursor++);
      if (op.currentPhase < op.ready.size() &&
          !op.ready.get(op.currentPhase).isEmpty() && !op.state.isTerminal())
        return op;
    }
    return null;
  }

  public boolean cancel(OperationPlan operation) {
    synchronized (lock) {
      own(operation);
      if (operation.state.isTerminal())
        return false;
      operation.state = OperationState.CANCELLED;
      discardReady(operation);
      lock.notifyAll();
      return true;
    }
  }

  private void failCommit(OperationPlan op,
                          PreparedOperationChunk.PhasePartition part,
                          Throwable cause) {
    synchronized (lock) {
      op.commitActive = false;
      releasePartition(op, part);
      failLocked(op, cause);
    }
    OverdriveLog.error(
        "operation {} phase {} commit failed; mutationStarted=true: {}", op.id,
        op.currentPhase, cause.toString());
  }
  private void fail(OperationPlan op, String phase, PreparedChunkChange change,
                    Throwable cause) {
    synchronized (lock) {
      preparationFinished(op);
      failLocked(op, cause);
    }
    OverdriveLog.error("operation {} {} failed{}; mutationStarted=false: {}",
                       op.id, phase,
                       change == null ? ""
                                      : " at chunk " + change.getChunkX() +
                                            "," + change.getChunkZ(),
                       cause.toString());
  }
  private void failLocked(OperationPlan op, Throwable cause) {
    if (op.failure == null)
      op.failure = cause;
    if (op.state != OperationState.CANCELLED)
      op.state = OperationState.FAILED;
    discardReady(op);
    lock.notifyAll();
  }
  private void preparationFinished(OperationPlan op) {
    op.finishedPreparations++;
    maybeComplete(op);
  }
  private void maybeComplete(OperationPlan op) {
    if (!op.state.isTerminal() && op.submissionsClosed &&
        op.finishedPreparations == op.submitted && !op.commitActive &&
        op.pendingSync == 0) {
      advancePhases(op);
      if (op.currentPhase == op.phases.size() && allReadyEmpty(op) &&
          op.bufferedBytes == 0)
        op.state = OperationState.COMPLETED;
    }
  }
  private void finishPartition(OperationPlan op,
                               PreparedOperationChunk.PhasePartition part) {
    OperationPhaseProgress p = op.phaseProgress.get(op.currentPhase);
    p.activeCommits--;
    p.committedUnits++;
    op.commitActive = false;
    releasePartition(op, part);
    if (!op.state.isTerminal())
      advancePhases(op);
  }
  private void advancePhases(OperationPlan op) {
    while (op.currentPhase < op.phases.size()) {
      OperationPhaseProgress p = op.phaseProgress.get(op.currentPhase);
      p.submissionsClosed = op.submissionsClosed;
      p.preparationFinished = op.finishedPreparations == op.submitted;
      if (!p.submissionsClosed || !p.preparationFinished ||
          !op.ready.get(op.currentPhase).isEmpty() || p.activeCommits != 0 ||
          op.pendingSync != 0) {
        if (op.phases.get(op.currentPhase).hasBarrierAfter())
          p.barrierWaits++;
        return;
      }
      p.synchronizedPhase = true;
      p.complete = true;
      p.finishedNanos = System.nanoTime();
      op.currentPhase++;
    }
  }
  private boolean allReadyEmpty(OperationPlan op) {
    for (java.util.Deque<?> q : op.ready)
      if (!q.isEmpty())
        return false;
    return true;
  }
  private void discardReady(OperationPlan op) {
    for (int i = 0; i < op.ready.size(); i++)
      while (!op.ready.get(i).isEmpty())
        releasePartition(op, op.ready.get(i).removeFirst());
  }
  private void releasePartition(OperationPlan op,
                                PreparedOperationChunk.PhasePartition part) {
    long bytes = part.estimatedBytes();
    OperationPhaseProgress p = op.phaseProgress.get(part.phase);
    p.bufferedBytes -= bytes;
    release(op, bytes);
  }
  private void release(OperationPlan op, long bytes) {
    op.bufferedBytes -= bytes;
    globalBytes -= bytes;
    lock.notifyAll();
  }
  private void own(OperationPlan op) {
    if (op.coordinator != this)
      throw new IllegalArgumentException("foreign operation");
  }

    public CoordinatorStatistics statistics() {
        synchronized (lock) {
            int active = ownedOperations.size(), ready = ownedOperations.size();
      for (OperationPlan op : operations) {
        if (!op.state.isTerminal())
          active++;
        for (java.util.Deque<?> q : op.ready)
          ready += q.size();
      }
      return new CoordinatorStatistics(
          active, ready, workers.getActiveCount(), commitsThisTick, globalBytes,
          config.maxPreparedBytes, commitNanosThisTick);
    }
  }

  OperationStatistics statistics(OperationPlan operation) {
    synchronized (lock) {
      own(operation);
      return new OperationStatistics(operation);
    }
  }

  /** Programmatic smoke path; deliberately not exposed through a command. */
  public OverdriveOperation submitSynthetic(WorldServer world,
                                            final PreparedChunkChange change) {
    OverdriveOperation op = createOperation(world, SideEffectPolicy.RAW);
    submit(op, new ChunkPreparationTask() {
      public PreparedChunkChange prepare() { return change; }
    });
    finishSubmissions(op);
    return op;
  }

  public void shutdown() {
    List<OwnedOperation> owners;
    synchronized (lock) {
      if (shutdown)
        return;
      shutdown = true;
      for (OperationPlan op : operations)
        if (!op.state.isTerminal()) {
          op.state = OperationState.CANCELLED;
          discardReady(op);
        }
      lock.notifyAll();
    }
    synchronized (lock) {
      owners = new ArrayList<OwnedOperation>(ownedOperations);
    }
    for (OwnedOperation owner : owners) {
      finishOwnedOperation(owner, null, true);
    }
    workers.shutdownNow();
    try {
      workers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
