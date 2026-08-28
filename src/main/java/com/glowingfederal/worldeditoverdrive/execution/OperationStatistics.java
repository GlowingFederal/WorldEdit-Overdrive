package com.glowingfederal.worldeditoverdrive.execution;

public final class OperationStatistics {
    public final long operationId, createdNanos, elapsedNanos, submittedChunks, preparedChunks,
            committedChunks, preparedBlocks, committedBlocks, rawApplications, nativeApplications,
            sparseSections, denseSections, preparationNanos, commitNanos, bufferedBytes,
            peakBufferedBytes, sparsePackets, chunkPackets, tilePackets;
    public final OperationState state;
    public final Throwable failure;

    OperationStatistics(OverdriveOperation o) {
        operationId=o.id; createdNanos=o.createdNanos; elapsedNanos=System.nanoTime()-o.createdNanos;
        submittedChunks=o.submitted; preparedChunks=o.prepared; committedChunks=o.committed;
        preparedBlocks=o.preparedBlocks; committedBlocks=o.committedBlocks; rawApplications=o.raw;
        nativeApplications=o.nativeCount; sparseSections=o.sparseSections; denseSections=o.denseSections;
        preparationNanos=o.preparationNanos; commitNanos=o.commitNanos; bufferedBytes=o.bufferedBytes;
        peakBufferedBytes=o.peakBufferedBytes; sparsePackets=o.sparsePackets;
        chunkPackets=o.chunkPackets; tilePackets=o.tilePackets; state=o.state; failure=o.failure;
    }
}
