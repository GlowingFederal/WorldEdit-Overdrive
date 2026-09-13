package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import com.glowingfederal.worldeditoverdrive.execution.AdaptiveServerBudget;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveCoordinator;
import com.glowingfederal.worldeditoverdrive.mutation.ChunkMutationBatch;
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
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.Location;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns deferred compatibility traversal and full standard PasteBuilder
 * acceleration.
 */
public final class DeferredPasteManager {
  private static volatile OverdriveCoordinator coordinator;

  public static void setCoordinator(OverdriveCoordinator value) {
    coordinator = value;
  }

  public static void register(ForwardExtentCopy operation,
                              PasteOperationAdapter adapter, Player player,
                              LocalSession session, boolean selectPasted)
      throws Exception {
    if (operation == null || adapter == null || player == null ||
        session == null) {
      throw new NullPointerException("deferred paste context");
    }
    OverdriveCoordinator current = coordinator;
    if (current == null) {
      throw new IllegalStateException("Overdrive coordinator is not running");
    }

    final Owner owner = new Owner(current, operation, adapter, player, session,
                                  session.getClipboard(), selectPasted);
    try {
      owner.operationId =
          current.admitOwner(player.getName(), owner, owner.initialReservation,
                             new PasteOwnerListener(owner));
    } catch (RejectedExecutionException rejected) {
      PasteHookStatus.lastPasteDeferredReason =
          "rejected: " + rejected.getMessage();
      throw rejected;
    }
    PasteHookStatus.pasteDeferredActive.incrementAndGet();
  }

  private static final class PasteOwnerListener
      implements OverdriveCoordinator.OwnerListener {
    private final Owner owner;

    private PasteOwnerListener(Owner owner) { this.owner = owner; }

    public void completed(long operationId) { finishCounters(true, null); }

    public void failed(long operationId, Exception failure) {
      owner.state = Owner.State.FAILED;
      finishCounters(false, failure);
      owner.player.printError("Paste failed: " + failure.getMessage());
      OverdriveLog.error("deferred paste {} failed: {}", operationId,
                         failure.toString());
    }

    public void cancelled(long operationId) {
      owner.state = Owner.State.CANCELLED;
      finishCounters(false, new Exception("cancelled"));
      owner.player.printError("Paste " + operationId + " was cancelled");
    }

    private void finishCounters(boolean success, Exception failure) {
      if (owner.commitActive) {
        owner.commitActive = false;
        PasteHookStatus.pasteCommitActive.decrementAndGet();
      }
      PasteHookStatus.pasteDeferredActive.decrementAndGet();
      if (success) {
        PasteHookStatus.pasteDeferredCompleted.incrementAndGet();
      } else {
        PasteHookStatus.pasteDeferredFailed.incrementAndGet();
        PasteHookStatus.lastPasteDeferredReason = failure.getMessage();
      }
    }
  }

  private static final class Owner implements MutationOperationOwner {
    enum State {
      CAPTURING,
      PLANNING,
      SUBMITTING,
      COMMITTING,
      FINALIZING,
      COMPLETE,
      FAILED,
      CANCELLED
    }
    final OverdriveCoordinator coordinator;
    final ForwardExtentCopy original;
    final PasteOperationAdapter adapter;
    final Player player;
    final LocalSession session;
    final ClipboardHolder holder;
    final boolean select;
    final PasteContinuationOperation lifecycle =
        new PasteContinuationOperation();
    final long operationStarted = System.nanoTime(), snapshotStarted =
                                                         operationStarted;
    final int minX, minY, minZ, sizeX, sizeY, sizeZ, volume;
    volatile RegionMutationPlan plan;
    volatile Exception planningFailure;
    PlanningCompletion planningCompletion;
    PreparedClipboardView prepared;
    int[] ids, data, dx, dy, dz;
    Map<Integer, BaseBlock> auxiliary;
    CaptureExtent entityCapture;
    List<? extends Entity> sourceEntities;
    boolean vanilla, mutation, commitActive, blocksFlushed, captureInitialized,
        blocksCaptured, entitiesListed, commitInitialized;
    State state = State.CAPTURING;
    int captureIndex, entityCaptureCursor, batchCursor, batchOffset,
        entityCursor;
    long operationId, initialReservation, reserved, commitNanos,
        snapshotActiveNanos, commitStarted, submittedTotal, planningStarted;
    int plannedTotal;
    Operation commitOperation;
    final Set<Long> touchedChunks = new HashSet<Long>();
    Owner(OverdriveCoordinator coordinator, ForwardExtentCopy original,
          PasteOperationAdapter adapter, Player player, LocalSession session,
          ClipboardHolder holder, boolean select) throws Exception {
      this.coordinator = coordinator;
      this.original = original;
      this.adapter = adapter;
      this.player = player;
      this.session = session;
      this.holder = holder;
      this.select = select;
      Vector min = adapter.clipboard.getMinimumPoint(),
             max = adapter.clipboard.getMaximumPoint();
      minX = min.getBlockX();
      minY = min.getBlockY();
      minZ = min.getBlockZ();
      sizeX = max.getBlockX() - minX + 1;
      sizeY = max.getBlockY() - minY + 1;
      sizeZ = max.getBlockZ() - minZ + 1;
      long volumeLong = (long)sizeX * sizeY * sizeZ;
      volume =
          volumeLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)volumeLong;
      PasteOperationAdapter.Eligibility eligible =
          adapter.accelerationEligibility();
      if (eligible.kind != PasteOperationAdapter.Eligibility.Kind.ACCELERATE)
        throw new IllegalArgumentException(eligible.reason);
      long estimate = 128L + volumeLong * 32L;
      if (volumeLong > Integer.MAX_VALUE)
        throw new IllegalStateException(
            "accelerated paste is too large to index safely");
      initialReservation = estimate;
      reserved = estimate;
      PasteHookStatus.activePhase = "SNAPSHOTTING";
      PasteHookStatus.snapshotProcessed.set(0);
      PasteHookStatus.snapshotTotalEstimate.set(volumeLong);
      PasteHookStatus.commitRemaining.set(volumeLong);
      PasteHookStatus.lastOperationSnapshotActiveMillis.set(0);
      PasteHookStatus.lastOperationMaxServerSliceMillis.set(0);
      PasteHookStatus.lastOperationWallMillis.set(0);
      resetOperationDiagnostics();
      PasteHookStatus.lastPasteTransform =
          adapter.transform.getClass().getName();
      PasteHookStatus.lastPasteIgnoreAir = adapter.ignoreAir;
      PasteHookStatus.queueEnabled = adapter.destination.isQueueEnabled();
      PasteHookStatus.incrementalCommitSupported =
          EnhancedReorderYieldBridge.isSupported();
      PasteHookStatus.lastPasteDeferredReason =
          PasteHookStatus.queueEnabled
              ? "accelerated paste admitted with normal reorder buffering " +
                "and incremental commit"
              : "accelerated paste admitted with bounded synchronous " +
                "setBlock and final flush";
      PasteHookStatus.queueImplementationClass =
          PasteHookStatus.queueEnabled
              ? "com.sk89q.worldedit.extent.reorder.MultiStageReorder"
              : "disabled";
      PasteHookStatus.editSessionExtentClass =
          "com.sk89q.worldedit.EditSession";
      OverdriveLog.info(
          "paste reorderEnabled={} incrementalCommitSupported={}",
          Boolean.valueOf(PasteHookStatus.queueEnabled),
          Boolean.valueOf(PasteHookStatus.incrementalCommitSupported));
    }
    boolean resizeReservation(long wanted) {
      if (!coordinator.resizeOwnerReservation(this, wanted))
        return false;
      reserved = wanted;
      return true;
    }
    void defer(String reason) {
      vanilla = true;
      PasteHookStatus.pasteAccelerationFallbacks.incrementAndGet();
      PasteHookStatus.lastPasteAccelerationFallbackReason = reason;
      PasteHookStatus.lastPasteDeferredReason = "deferred vanilla: " + reason;
    }
    public boolean tick(long globalDeadline) throws Exception {
      long sliceStarted = System.nanoTime();
      try {
        if (vanilla)
          throw new IllegalStateException(
              "deferred owner cannot execute an unbounded vanilla traversal");
        if (state == State.COMPLETE)
          return true;
        if (state == State.FAILED)
          throw new IllegalStateException(
              "failed deferred paste owner was scheduled again");
        if (state == State.CAPTURING) {
          captureUntil(globalDeadline);
          return false;
        }
        if (planningFailure != null)
          throw new Exception("accelerated planning failed", planningFailure);
        RegionMutationPlan ready = plan;
        if (state == State.PLANNING) {
          publishPlanningResult();
          ready = plan;
          if (ready == null) {
            PasteHookStatus.activePhase = "PLANNING";
            return false;
          }
          state = State.SUBMITTING;
          lifecycle.committing();
          commitActive = true;
          commitStarted = System.nanoTime();
          PasteHookStatus.activePhase = "SUBMITTING";
          PasteHookStatus.pasteCommitActive.incrementAndGet();
        }
        if (state == State.COMMITTING) {
          PasteHookStatus.activePhase = "COMMITTING";
          resumeCommit(globalDeadline);
          updateCommitRemaining();
          if (!blocksFlushed)
            return false;
          state = State.FINALIZING;
          PasteHookStatus.activePhase = "FINALIZING";
          return false;
        }
        if (state == State.FINALIZING) {
          PasteHookStatus.activePhase = "FINALIZING";
          int entities = 0;
          while (entityCursor < prepared.entities().size() && entities < 16 &&
                 System.nanoTime() < globalDeadline) {
            PreparedClipboardView.EntitySnapshot entity =
                prepared.entities().get(entityCursor++);
            if (adapter.destination.createEntity(
                    new Location(
                        adapter.destination, entity.location.toVector(),
                        entity.location.getYaw(), entity.location.getPitch()),
                    new BaseEntity(entity.state)) != null)
              PasteHookStatus.pasteCommittedEntities.incrementAndGet();
            mutation = true;
            entities++;
          }
          if (entityCursor < prepared.entities().size())
            return false;
          long finalizationStarted = System.nanoTime();
          finish();
          long finalizationNanos = System.nanoTime() - finalizationStarted;
          PasteHookStatus.finalizationServerMillis.set(finalizationNanos /
                                                       1000000L);
          commitNanos += finalizationNanos;
          PasteHookStatus.lastOperationCommitActiveMillis.set(commitNanos /
                                                              1000000L);
          commitActive = false;
          PasteHookStatus.pasteCommitActive.decrementAndGet();
          lifecycle.complete();
          PasteHookStatus.pasteAccelerated.incrementAndGet();
          PasteHookStatus.activePhase = "IDLE";
          PasteHookStatus.lastOperationWallMillis.set(
              (System.nanoTime() - operationStarted) / 1000000L);
          state = State.COMPLETE;
          validateSuccessfulRemoval();
          return true;
        }
        if (state != State.SUBMITTING)
          throw new IllegalStateException(
              "unexpected deferred paste lifecycle state: " + state);
        long tickStarted = System.nanoTime();
        int submitted = 0, changed = 0, tiles = 0, loadedChunks = 0,
            lastChunk = Integer.MIN_VALUE;
        while (batchCursor < ready.getBatches().size() && submitted < 4096 &&
               System.nanoTime() < globalDeadline) {
          ChunkMutationBatch batch = ready.getBatches().get(batchCursor);
          if (batchOffset == batch.size()) {
            batchCursor++;
            batchOffset = 0;
            continue;
          }
          int i = batch.sourceIndex(batchOffset);
          int chunkX = prepared.destinationX(i) >> 4,
              chunkZ = prepared.destinationZ(i) >> 4,
              chunk = chunkX * 31 + chunkZ;
          if (chunk != lastChunk && loadedChunks >= 2)
            break;
          if (chunk != lastChunk) {
            lastChunk = chunk;
            loadedChunks++;
          }
          batchOffset++;
          BaseBlock desired = prepared.blockAt(i);
          Vector position =
              new Vector(prepared.destinationX(i), prepared.destinationY(i),
                         prepared.destinationZ(i));
          BaseBlock existing = adapter.destination.getBlock(position);
          if (desired.getNbtData() == null &&
              existing.getId() == desired.getId() &&
              existing.getData() == desired.getData()) {
            plannedTotal--;
            PasteHookStatus.pastePlannedBlocks.decrementAndGet();
            PasteHookStatus.pasteDestinationMatchedCells.incrementAndGet();
            continue;
          }
          if (adapter.destination.setBlock(position, desired))
            changed++;
          mutation = true;
          submitted++;
          touchedChunks.add(
              Long.valueOf(((long)chunkX << 32) ^ (chunkZ & 0xffffffffL)));
          if (desired.getNbtData() != null)
            tiles++;
        }
        long submissionNanos = System.nanoTime() - tickStarted;
        updateMax(PasteHookStatus.maxSubmissionSliceMillis,
                  submissionNanos / 1000000L);
        if (submitted != 0) {
          submittedTotal += submitted;
          PasteHookStatus.pasteSubmittedBlocks.addAndGet(submitted);
          if (!PasteHookStatus.queueEnabled) {
            PasteHookStatus.pasteCommittedBlocks.addAndGet(changed);
            PasteHookStatus.pasteCommittedTiles.addAndGet(tiles);
          }
          PasteHookStatus.submittedSinceLastDrain.set(submitted);
          PasteHookStatus.chunksSinceLastDrain.set(loadedChunks);
        }
        long elapsed = System.nanoTime() - tickStarted;
        commitNanos += elapsed;
        PasteHookStatus.commitServerMillis.set(commitNanos / 1000000L);
        PasteHookStatus.lastOperationCommitActiveMillis.set(commitNanos /
                                                            1000000L);
        PasteHookStatus.lastPasteCommitMillis.set(commitNanos / 1000000L);
        PasteHookStatus.commitRemaining.set(
            Math.max(0L, plannedTotal - submittedTotal));
        PasteHookStatus.lastOperationCommitWallMillis.set(
            (System.nanoTime() - commitStarted) / 1000000L);
        if (batchCursor < ready.getBatches().size())
          return false;
        if (PasteHookStatus.queueEnabled) {
          state = State.COMMITTING;
          PasteHookStatus.activePhase = "COMMITTING";
        } else {
          drain((int)submittedTotal, true);
          blocksFlushed = true;
          PasteHookStatus.commitCompletedNormally = true;
          PasteHookStatus.commitRemaining.set(0);
          state = State.FINALIZING;
          PasteHookStatus.activePhase = "FINALIZING";
        }
        return false;
      } finally {
        long slice = System.nanoTime() - sliceStarted;
        updateMax(PasteHookStatus.lastOperationMaxServerSliceMillis,
                  slice / 1000000L);
      }
    }
    void resumeCommit(long deadline) throws Exception {
      long commitSliceStarted = System.nanoTime();
      if (!commitInitialized) {
        commitInitialized = true;
        EnhancedReorderYieldBridge.observeRemaining(adapter.destination);
        commitOperation = adapter.destination.commit();
        PasteHookStatus.commitOperationClass =
            commitOperation == null ? "none"
                                    : commitOperation.getClass().getName();
        if (commitOperation == null) {
          PasteHookStatus.topLevelCommitReturnedNull = true;
          verifyCommitExhausted();
          blocksFlushed = true;
          PasteHookStatus.commitCompletedNormally = true;
          return;
        }
      }
      long sliceEntry = System.nanoTime();
      PasteHookStatus.deadlineBudgetNanos.set(
          Math.max(0L, deadline - sliceEntry));
      PasteHookStatus.incrementalCommitSlices.incrementAndGet();
      EnhancedReorderYieldBridge.beginSlice(deadline);
      try {
        while (commitOperation != null && System.nanoTime() < deadline) {
          String stage = commitOperation.getClass().getName();
          PasteHookStatus.activeCommitOperationClassBeforeResume = stage;
          long started = System.nanoTime();
          commitOperation = commitOperation.resume(new RunContext());
          long nanos = System.nanoTime() - started;
          long placements = PasteHookStatus.blockMapPlacementsThisResume.get(),
               chains = PasteHookStatus.stage3ChainsThisResume.get();
          if (placements > 0)
            stage = "BlockMapEntryPlacer";
          else if (chains > 0)
            stage = "Stage3Committer";
          PasteHookStatus.resumeElapsedNanos.set(nanos);
          PasteHookStatus.placementsThisResume.set(placements);
          PasteHookStatus.commitResumeCalls.incrementAndGet();
          PasteHookStatus.activeCommitOperationClassAfterResume =
              commitOperation == null ? "null"
                                      : commitOperation.getClass().getName();
          PasteHookStatus.topLevelCommitReturnedNull = commitOperation == null;
          if (updateMax(PasteHookStatus.maxCommitResumeMillis,
                        nanos / 1000000L)) {
            PasteHookStatus.maxCommitResumeStage = stage;
            PasteHookStatus.maxCommitResumePlacements.set(placements);
            PasteHookStatus.maxCommitResumeChains.set(chains);
            PasteHookStatus.maxCommitResumeBeganExpired =
                PasteHookStatus.deadlineRemainingNanosAtResumeEntry.get() <= 0;
          }
        }
      } finally {
        EnhancedReorderYieldBridge.endSlice();
        long active = System.nanoTime() - commitSliceStarted;
        commitNanos += active;
        PasteHookStatus.commitServerMillis.set(commitNanos / 1000000L);
        PasteHookStatus.lastOperationCommitActiveMillis.set(commitNanos /
                                                            1000000L);
        PasteHookStatus.commitStateActiveServerMillis.set(commitNanos /
                                                          1000000L);
        PasteHookStatus.lastPasteCommitMillis.set(commitNanos / 1000000L);
        PasteHookStatus.commitStateElapsedWallMillis.set(
            (System.nanoTime() - commitStarted) / 1000000L);
        PasteHookStatus.lastOperationCommitWallMillis.set(
            PasteHookStatus.commitStateElapsedWallMillis.get());
      }
      if (commitOperation == null) {
        verifyCommitExhausted();
        blocksFlushed = true;
        PasteHookStatus.commitCompletedNormally = true;
      }
    }
    void updateCommitRemaining() {
      long downstream = PasteHookStatus.commitOperationRemaining.get();
      PasteHookStatus.commitRemaining.set(
          downstream < 0 ? Math.max(0L, plannedTotal - submittedTotal)
                         : downstream);
    }
    void validateSuccessfulRemoval() {
      long remaining = PasteHookStatus.commitOperationRemaining.get();
      if (state != State.COMPLETE || commitOperation != null ||
          (PasteHookStatus.queueEnabled &&
           (!PasteHookStatus.commitCompletedNormally || remaining != 0)))
        throw new IllegalStateException(
            "refusing successful deferred paste removal: state=" + state +
            ", commitOperation=" +
            (commitOperation == null ? "null"
                                     : commitOperation.getClass().getName()) +
            ", commitCompletedNormally=" +
            PasteHookStatus.commitCompletedNormally +
            ", commitRemaining=" + PasteHookStatus.commitRemaining.get() +
            ", reorderRemaining=" + remaining +
            ", stage1=" + PasteHookStatus.reorderStage1Remaining.get() +
            ", stage2=" + PasteHookStatus.reorderStage2Remaining.get() +
            ", stage3=" + PasteHookStatus.reorderStage3Remaining.get());
    }
    void verifyCommitExhausted() {
      long remaining = PasteHookStatus.commitOperationRemaining.get();
      if (remaining != 0)
        throw new IllegalStateException(
            "incremental reorder commit returned null with remaining work: " +
            "stage1=" +
            PasteHookStatus.reorderStage1Remaining.get() +
            ", stage2=" + PasteHookStatus.reorderStage2Remaining.get() +
            ", stage3=" + PasteHookStatus.reorderStage3Remaining.get());
    }
    void captureUntil(long deadline) throws Exception {
      long active = System.nanoTime();
      PasteHookStatus.activePhase = "SNAPSHOTTING";
      if (!captureInitialized) {
        ids = new int[volume];
        data = new int[volume];
        dx = new int[volume];
        dy = new int[volume];
        dz = new int[volume];
        auxiliary = new HashMap<Integer, BaseBlock>();
        entityCapture = new CaptureExtent();
        captureInitialized = true;
      }
      while (!blocksCaptured && captureIndex < volume &&
             System.nanoTime() < deadline) {
        int i = captureIndex++;
        int x = minX + i % sizeX;
        int q = i / sizeX;
        int z = minZ + q % sizeZ;
        int y = minY + q / sizeZ;
        Vector source = new Vector(x, y, z);
        BaseBlock block = adapter.transformedSource.getBlock(source);
        ids[i] = block.getId();
        data[i] = block.getData();
        if (block.getNbtData() != null || block.getClass() != BaseBlock.class)
          auxiliary.put(Integer.valueOf(i), new BaseBlock(block));
        Vector destination =
            adapter.transform.apply(source.subtract(adapter.sourceOrigin))
                .add(adapter.destinationOrigin);
        dx[i] = destination.getBlockX();
        dy[i] = destination.getBlockY();
        dz[i] = destination.getBlockZ();
      }
      blocksCaptured = captureIndex == volume;
      PasteHookStatus.snapshotProcessed.set(captureIndex);
      if (!blocksCaptured) {
        snapshotAccounting(active);
        return;
      }
      if (!entitiesListed) {
        sourceEntities = adapter.clipboard.getEntities(adapter.region);
        entitiesListed = true;
        PasteHookStatus.snapshotTotalEstimate.set((long)volume +
                                                  sourceEntities.size());
      }
      while (entityCaptureCursor < sourceEntities.size() &&
             System.nanoTime() < deadline) {
        ExtentEntityCopy copy =
            new ExtentEntityCopy(adapter.sourceOrigin, entityCapture,
                                 adapter.destinationOrigin, adapter.transform);
        copy.apply(sourceEntities.get(entityCaptureCursor++));
        PasteHookStatus.snapshotProcessed.set((long)volume +
                                              entityCaptureCursor);
      }
      if (entityCaptureCursor < sourceEntities.size()) {
        snapshotAccounting(active);
        return;
      }
      prepared = new PreparedClipboardView(minX, minY, minZ, sizeX, sizeY,
                                           sizeZ, ids, data, dx, dy, dz,
                                           auxiliary, entityCapture.snapshots);
      long actual = prepared.estimatedBytes() + volume * 4L;
      if (!resizeReservation(actual))
        throw new IllegalStateException(
            "accelerated paste retained data exceeds memory limit: " + actual);
      PasteHookStatus.pastePreparedBlocks.addAndGet(volume);
      long air = 0;
      for (int id : ids)
        if (id == 0)
          air++;
      PasteHookStatus.pasteSourceAirCells.addAndGet(air);
      if (adapter.ignoreAir)
        PasteHookStatus.pasteIgnoreAirFilteredCells.addAndGet(air);
      PasteHookStatus.pastePreparedTiles.addAndGet(prepared.tileCount());
      PasteHookStatus.pastePreparedEntities.addAndGet(
          prepared.entities().size());
      if (!adapter.transform.isIdentity())
        PasteHookStatus.pasteTransformedBlocks.addAndGet(volume);
      snapshotAccounting(active);
      PasteHookStatus.lastOperationSnapshotWallMillis.set(
          (System.nanoTime() - snapshotStarted) / 1000000L);
      PasteHookStatus.lastPastePrepareMillis.set(
          PasteHookStatus.lastOperationSnapshotWallMillis.get());
      state = State.PLANNING;
      PasteHookStatus.activePhase = "PLANNING";
      lifecycle.submitted();
      PasteHookStatus.pastePlanningActive.incrementAndGet();
      planningStarted = System.nanoTime();
      planningCompletion = new PlanningCompletion();
      submitPlanning(this, prepared, adapter.ignoreAir, planningCompletion);
    }
    void publishPlanningResult() {
      PlanningCompletion completion = planningCompletion;
      if (completion == null)
        return;
      if (completion.failure != null) {
        planningFailure = completion.failure;
        return;
      }
      if (completion.plan == null)
        return;
      plannedTotal = completion.plannedTotal;
      plan = completion.plan;
      PasteHookStatus.pastePlannedBlocks.addAndGet(plannedTotal);
      PasteHookStatus.commitRemaining.set(plannedTotal);
      PasteHookStatus.lastOperationPlanWallMillis.set(ms(planningStarted));
      PasteHookStatus.lastPastePlanMillis.set(
          PasteHookStatus.lastOperationPlanWallMillis.get());
    }
    void drain(int queued, boolean finalDrain) {
      if (queued == 0 && !finalDrain)
        return;
      if (finalDrain) {
        PasteHookStatus.finalFlushQueuedMutations.set(queued);
        PasteHookStatus.finalFlushChunks.set(touchedChunks.size());
      }
      long started = System.nanoTime();
      adapter.destination.flushQueue();
      long nanos = System.nanoTime() - started;
      if (finalDrain)
        PasteHookStatus.finalSynchronousFlushCount.incrementAndGet();
      PasteHookStatus.flushCount.incrementAndGet();
      PasteHookStatus.totalFlushNanos.addAndGet(nanos);
      PasteHookStatus.lastFlushMillis.set(nanos / 1000000L);
      updateMax(PasteHookStatus.maxFlushMillis, nanos / 1000000L);
      PasteHookStatus.queueDrainServerMillis.addAndGet(nanos / 1000000L);
      PasteHookStatus.submittedSinceLastDrain.set(0);
      PasteHookStatus.chunksSinceLastDrain.set(0);
      if (nanos >
          Math.max(1000000L, coordinator.adaptiveBudget().budgetNanos()))
        PasteHookStatus.uninterruptibleFlushOverBudgetCount.incrementAndGet();
      if (finalDrain) {
        PasteHookStatus.finalFlushMillis.set(nanos / 1000000L);
        updateMax(PasteHookStatus.maxFinalFlushMillis, nanos / 1000000L);
      }
    }
    void snapshotAccounting(long started) {
      snapshotActiveNanos += System.nanoTime() - started;
      PasteHookStatus.lastOperationSnapshotActiveMillis.set(
          snapshotActiveNanos / 1000000L);
      PasteHookStatus.sourceCaptureServerMillis.set(snapshotActiveNanos /
                                                    1000000L);
    }
    void finish() {
      session.remember(adapter.destination);
      Vector to = adapter.destinationOrigin;
      if (select) {
        Vector max = to.add(adapter.region.getMaximumPoint().subtract(
            adapter.region.getMinimumPoint()));
        RegionSelector selector =
            new CuboidRegionSelector(player.getWorld(), to, max);
        session.setRegionSelector(player.getWorld(), selector);
        selector.learnChanges();
        selector.explainRegionAdjust(player, session);
      }
      player.print("The clipboard has been pasted at " + to);
    }
    public MutationOperationOwner.Phase phase() {
      if (state == State.CANCELLED)
        return MutationOperationOwner.Phase.CANCELLED;
      if (state == State.FAILED)
        return MutationOperationOwner.Phase.FAILED;
      if (state == State.COMPLETE)
        return MutationOperationOwner.Phase.COMPLETED;
      if (vanilla)
        return MutationOperationOwner.Phase.COMMITTING;
      if (prepared == null)
        return MutationOperationOwner.Phase.SNAPSHOTTING;
      if (plan == null)
        return MutationOperationOwner.Phase.PLANNING;
      if (!commitActive)
        return MutationOperationOwner.Phase.PLANNING;
      if (blocksFlushed && entityCursor >= prepared.entities().size())
        return MutationOperationOwner.Phase.FINALIZING;
      return MutationOperationOwner.Phase.COMMITTING;
    }
    public void release() {
      reserved = 0;
      ids = null;
      data = null;
      dx = null;
      dy = null;
      dz = null;
      auxiliary = null;
      sourceEntities = null;
      prepared = null;
      plan = null;
    }
  }
  private static void submitPlanning(Owner owner, PreparedClipboardView view,
                                     boolean ignoreAir,
                                     PlanningCompletion completion) {
    final int[][] results = new int[1][];
    final AtomicInteger remaining = new AtomicInteger(1);
    PasteHookStatus.pasteWorkerTasksSubmitted.incrementAndGet();
    PasteHookStatus.workerQueuedChunks.incrementAndGet();
    try {
      owner.coordinator.submitOwnerPreparation(
          owner, new Planner(view, ignoreAir, 0, view.getVolume(), 0, results,
                             remaining, completion));
    } catch (RejectedExecutionException rejected) {
      PasteHookStatus.pastePlanningActive.decrementAndGet();
      throw rejected;
    }
  }
  private static final class PlanningCompletion {
    volatile RegionMutationPlan plan;
    volatile Exception failure;
    volatile int plannedTotal;
  }
  private static final class Planner implements Runnable {
    final PreparedClipboardView view;
    final boolean ignoreAir;
    final int from, to, slot;
    final int[][] results;
    final AtomicInteger remaining;
    final PlanningCompletion completion;
    Planner(PreparedClipboardView view, boolean ignoreAir, int from, int to,
            int slot, int[][] results, AtomicInteger remaining,
            PlanningCompletion completion) {
      this.view = view;
      this.ignoreAir = ignoreAir;
      this.from = from;
      this.to = to;
      this.slot = slot;
      this.results = results;
      this.remaining = remaining;
      this.completion = completion;
    }
    public void run() {
      long started = System.nanoTime();
      long active = PasteHookStatus.pasteWorkerActive.incrementAndGet();
      updateMax(PasteHookStatus.pasteWorkerMaxConcurrency, active);
      try {
        int count = 0;
        for (int i = from; i < to; i++)
          if (!ignoreAir || view.idAt(i) != 0)
            count++;
        int[] indices = new int[count];
        for (int i = from, n = 0; i < to; i++)
          if (!ignoreAir || view.idAt(i) != 0)
            indices[n++] = i;
        results[slot] = indices;
        if (remaining.decrementAndGet() == 0) {
          int total = 0;
          for (int[] part : results)
            total += part.length;
          int[] all = new int[total];
          int cursor = 0;
          for (int[] part : results) {
            System.arraycopy(part, 0, all, cursor, part.length);
            cursor += part.length;
          }
          completion.plannedTotal = total;
          completion.plan = MutationPlanBuilder.chunkLocal(
              all, new MutationPlanBuilder.Coordinates() {
                public int x(int index) { return view.destinationX(index); }
                public int z(int index) { return view.destinationZ(index); }
              });
          PasteHookStatus.pastePlanningActive.decrementAndGet();
        }
      } catch (Exception failure) {
        completion.failure = failure;
        PasteHookStatus.pastePlanningActive.decrementAndGet();
      } finally {
        long nanos = System.nanoTime() - started;
        PasteHookStatus.pasteWorkerPlanNanos.addAndGet(nanos);
        PasteHookStatus.pasteWorkerActive.decrementAndGet();
        PasteHookStatus.pasteWorkerTasksCompleted.incrementAndGet();
        PasteHookStatus.workerCompletedChunks.incrementAndGet();
      }
    }
  }

  private static final class CaptureExtent extends NullExtent {
    final List<PreparedClipboardView.EntitySnapshot> snapshots =
        new ArrayList<PreparedClipboardView.EntitySnapshot>();
    public Entity createEntity(Location location, BaseEntity state) {
      PreparedClipboardView.EntitySnapshot snapshot =
          new PreparedClipboardView.EntitySnapshot(location, state);
      snapshots.add(snapshot);
      return new SnapshotEntity(snapshot, this);
    }
  }
  private static final class SnapshotEntity implements Entity {
    final PreparedClipboardView.EntitySnapshot snapshot;
    final Extent extent;
    SnapshotEntity(PreparedClipboardView.EntitySnapshot snapshot,
                   Extent extent) {
      this.snapshot = snapshot;
      this.extent = extent;
    }
    public BaseEntity getState() { return new BaseEntity(snapshot.state); }
    public Location getLocation() { return snapshot.location; }
    public Extent getExtent() { return extent; }
    public boolean remove() { return false; }
    public <T> T getFacet(Class<? extends T> type) { return null; }
  }
  private static void resetOperationDiagnostics() {
    PasteHookStatus.pastePreparedBlocks.set(0);
    PasteHookStatus.pastePlannedBlocks.set(0);
    PasteHookStatus.pasteSubmittedBlocks.set(0);
    PasteHookStatus.pasteCommittedBlocks.set(0);
    PasteHookStatus.pasteSourceAirCells.set(0);
    PasteHookStatus.pasteIgnoreAirFilteredCells.set(0);
    PasteHookStatus.pasteDestinationMatchedCells.set(0);
    PasteHookStatus.pasteOtherwiseFilteredCells.set(0);
    PasteHookStatus.pastePreparedTiles.set(0);
    PasteHookStatus.pasteCommittedTiles.set(0);
    PasteHookStatus.pastePreparedEntities.set(0);
    PasteHookStatus.pasteCommittedEntities.set(0);
    PasteHookStatus.pasteTransformedBlocks.set(0);
    PasteHookStatus.pasteWorkerTasksSubmitted.set(0);
    PasteHookStatus.pasteWorkerTasksCompleted.set(0);
    PasteHookStatus.workerQueuedChunks.set(0);
    PasteHookStatus.workerCompletedChunks.set(0);
    PasteHookStatus.lastPastePrepareMillis.set(0);
    PasteHookStatus.lastPastePlanMillis.set(0);
    PasteHookStatus.lastPasteCommitMillis.set(0);
    PasteHookStatus.destinationCaptureServerMillis.set(0);
    PasteHookStatus.submittedSinceLastDrain.set(0);
    PasteHookStatus.chunksSinceLastDrain.set(0);
    PasteHookStatus.flushCount.set(0);
    PasteHookStatus.totalFlushNanos.set(0);
    PasteHookStatus.lastFlushMillis.set(0);
    PasteHookStatus.maxFlushMillis.set(0);
    PasteHookStatus.maxSubmissionSliceMillis.set(0);
    PasteHookStatus.maxFinalFlushMillis.set(0);
    PasteHookStatus.finalFlushQueuedMutations.set(0);
    PasteHookStatus.finalFlushChunks.set(0);
    PasteHookStatus.finalFlushMillis.set(0);
    PasteHookStatus.uninterruptibleFlushOverBudgetCount.set(0);
    PasteHookStatus.queueDrainServerMillis.set(0);
    PasteHookStatus.commitServerMillis.set(0);
    PasteHookStatus.finalizationServerMillis.set(0);
    PasteHookStatus.pasteWorkerPlanNanos.set(0);
    PasteHookStatus.pasteWorkerMaxConcurrency.set(0);
    PasteHookStatus.incrementalCommitSlices.set(0);
    PasteHookStatus.commitResumeCalls.set(0);
    PasteHookStatus.maxCommitResumeMillis.set(0);
    PasteHookStatus.commitOperationRemaining.set(-1);
    PasteHookStatus.finalSynchronousFlushCount.set(0);
    PasteHookStatus.reorderStage1Remaining.set(-1);
    PasteHookStatus.reorderStage2Remaining.set(-1);
    PasteHookStatus.reorderStage3Remaining.set(-1);
    PasteHookStatus.blockMapPlacementsThisResume.set(0);
    PasteHookStatus.stage3ChainsThisResume.set(0);
    PasteHookStatus.deadlineYieldCount.set(0);
    PasteHookStatus.blockMapDeadlineYields.set(0);
    PasteHookStatus.stage3DeadlineYields.set(0);
    PasteHookStatus.topLevelCommitReturnedNull = false;
    PasteHookStatus.commitCompletedNormally = false;
    PasteHookStatus.commitOperationClass = "none";
    PasteHookStatus.activeCommitOperationClassBeforeResume = "none";
    PasteHookStatus.activeCommitOperationClassAfterResume = "none";
    PasteHookStatus.maxCommitResumeStage = "none";
    PasteHookStatus.maxDownstreamMutationDestinationChunk = "none";
    PasteHookStatus.maxCommitResumeBeganExpired = false;
    PasteHookStatus.deadlineBudgetNanos.set(0);
    PasteHookStatus.deadlineRemainingNanosAtResumeEntry.set(0);
    PasteHookStatus.deadlineRemainingNanosAtFirstPlacement.set(-1);
    PasteHookStatus.resumeElapsedNanos.set(0);
    PasteHookStatus.placementsThisResume.set(0);
    PasteHookStatus.deadlineExpiredAtEntry.set(0);
    PasteHookStatus.deadlineExpiredAfterFirstPlacement.set(0);
    PasteHookStatus.commitStateElapsedWallMillis.set(0);
    PasteHookStatus.commitStateActiveServerMillis.set(0);
    PasteHookStatus.maxCommitResumePlacements.set(0);
    PasteHookStatus.maxCommitResumeChains.set(0);
    PasteHookStatus.maxDownstreamMutationNanos.set(0);
  }
  private static boolean updateMax(AtomicLong target, long value) {
    for (;;) {
      long old = target.get();
      if (value <= old)
        return false;
      if (target.compareAndSet(old, value))
        return true;
    }
  }
  private static long ms(long start) {
    return (System.nanoTime() - start) / 1000000L;
  }
  public static AdaptiveServerBudget budget() {
    OverdriveCoordinator current = coordinator;
    return current == null ? null : current.adaptiveBudget();
  }
  private DeferredPasteManager() {}
}
