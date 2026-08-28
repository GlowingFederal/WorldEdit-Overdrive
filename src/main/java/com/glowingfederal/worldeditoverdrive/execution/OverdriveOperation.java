package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.world.WorldServer;

public final class OverdriveOperation {
    final long id, createdNanos = System.nanoTime(); final OverdriveCoordinator coordinator;
    final WorldServer world; final SideEffectPolicy policy;
    final Deque<PreparedChunkChange> ready = new ArrayDeque<PreparedChunkChange>();
    volatile OperationState state = OperationState.CREATED; volatile Throwable failure;
    long submitted, finishedPreparations, prepared, committed, preparedBlocks, committedBlocks;
    long raw, nativeCount, sparseSections, denseSections, preparationNanos, commitNanos;
    long bufferedBytes, peakBufferedBytes, sparsePackets, chunkPackets, tilePackets;
    boolean submissionsClosed, commitActive; int pendingSync;

    OverdriveOperation(long id, WorldServer world, SideEffectPolicy policy, OverdriveCoordinator coordinator) {
        this.id=id; this.world=world; this.policy=policy; this.coordinator=coordinator;
    }
    public long getId() { return id; }
    public OperationState getState() { return state; }
    public Throwable getFailure() { return failure; }
    public OperationStatistics statistics() { return coordinator.statistics(this); }
    public boolean cancel() { return coordinator.cancel(this); }
}
