package com.glowingfederal.worldeditoverdrive.integration;

/** Shared lifecycle contract for snapshot, plan, commit and finalization owners. */
public interface MutationOperationOwner {
    enum Phase { CREATED,SNAPSHOTTING,PLANNING,COMMITTING,FINALIZING,COMPLETED,FAILED,CANCELLED }
    boolean tick(long globalDeadlineNanos)throws Exception;
    Phase phase();
    void release();
}
