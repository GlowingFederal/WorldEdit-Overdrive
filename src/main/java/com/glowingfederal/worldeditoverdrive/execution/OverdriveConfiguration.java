package com.glowingfederal.worldeditoverdrive.execution;

public final class OverdriveConfiguration {
    public final int preparationWorkers, submissionCapacity, sparsePacketThreshold;
    public final long maxPreparedBytes, maxPreparedBytesPerOperation, commitBudgetNanos;

    public OverdriveConfiguration(int workers, int capacity, long globalBytes, long operationBytes,
            double commitBudgetMillis, int sparsePacketThreshold) {
        if (workers < 1 || capacity < 1 || globalBytes < 1 || operationBytes < 1
                || commitBudgetMillis <= 0 || sparsePacketThreshold < 1 || sparsePacketThreshold > 4096)
            throw new IllegalArgumentException("invalid Stage 3 configuration");
        this.preparationWorkers = workers; this.submissionCapacity = capacity;
        this.maxPreparedBytes = globalBytes; this.maxPreparedBytesPerOperation = operationBytes;
        this.commitBudgetNanos = (long) (commitBudgetMillis * 1000000D);
        this.sparsePacketThreshold = sparsePacketThreshold;
    }

    public static OverdriveConfiguration defaults() {
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        return new OverdriveConfiguration(workers, workers * 4, 128L << 20, 64L << 20, 5D, 512);
    }
}
