package com.glowingfederal.worldeditoverdrive.execution;

public final class CoordinatorStatistics {
    public final int activeOperations, readyChunks, activeWorkers, commitsThisTick;
    public final long preparedBytes, preparedByteLimit, commitNanosThisTick;
    CoordinatorStatistics(int active, int ready, int workers, int commits, long bytes, long limit, long nanos) {
        activeOperations=active; readyChunks=ready; activeWorkers=workers; commitsThisTick=commits;
        preparedBytes=bytes; preparedByteLimit=limit; commitNanosThisTick=nanos;
    }
}
