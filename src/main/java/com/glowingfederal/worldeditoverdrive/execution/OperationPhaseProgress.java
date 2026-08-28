package com.glowingfederal.worldeditoverdrive.execution;

/** Mutable accounting owned by an OperationPlan and exposed as immutable snapshots. */
public final class OperationPhaseProgress {
    long preparedUnits,readyUnits,committedUnits,activeCommits,bufferedBytes,peakBufferedBytes;
    long startedNanos,finishedNanos,barrierWaits; boolean submissionsClosed,preparationFinished,synchronizedPhase,complete;
    OperationPhaseProgress(){startedNanos=System.nanoTime();}
    OperationPhaseProgress(OperationPhaseProgress p){preparedUnits=p.preparedUnits;readyUnits=p.readyUnits;
        committedUnits=p.committedUnits;activeCommits=p.activeCommits;bufferedBytes=p.bufferedBytes;
        peakBufferedBytes=p.peakBufferedBytes;startedNanos=p.startedNanos;finishedNanos=p.finishedNanos;
        barrierWaits=p.barrierWaits;submissionsClosed=p.submissionsClosed;preparationFinished=p.preparationFinished;
        synchronizedPhase=p.synchronizedPhase;complete=p.complete;}
    public long getPreparedUnits(){return preparedUnits;} public long getReadyUnits(){return readyUnits;}
    public long getCommittedUnits(){return committedUnits;} public long getActiveCommits(){return activeCommits;}
    public long getBufferedBytes(){return bufferedBytes;} public long getPeakBufferedBytes(){return peakBufferedBytes;}
    public long getElapsedNanos(){return (finishedNanos==0?System.nanoTime():finishedNanos)-startedNanos;}
    public long getBarrierWaits(){return barrierWaits;} public boolean isComplete(){return complete;}
}
