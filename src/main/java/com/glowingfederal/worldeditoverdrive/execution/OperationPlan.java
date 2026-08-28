package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import net.minecraft.world.WorldServer;

/** Operation-level owner. It deliberately describes semantics, never a command name. */
public class OperationPlan {
    final long id,createdNanos=System.nanoTime(); final OverdriveCoordinator coordinator;
    final WorldServer world; final SideEffectPolicy policy; final String kind,sourceVolume,semanticPolicy;
    final PreparationClass preparationClass; final List<CommitPhase> phases;
    final List<OperationPhaseProgress> phaseProgress; final List<Deque<PreparedOperationChunk.PhasePartition>> ready;
    final FinalizationIntent finalizationIntent; final List<PreparedOperationChunk> chunkPlans=new ArrayList<PreparedOperationChunk>();
    volatile OperationState state=OperationState.CREATED; volatile Throwable failure; int currentPhase;
    long submitted,finishedPreparations,prepared,committed,preparedBlocks,committedBlocks,raw,nativeCount;
    long sparseSections,denseSections,preparationNanos,commitNanos,bufferedBytes,peakBufferedBytes;
    long sparsePackets,chunkPackets,tilePackets; boolean submissionsClosed,commitActive; int pendingSync;

    OperationPlan(long id,WorldServer world,SideEffectPolicy policy,OverdriveCoordinator coordinator,String kind,
            String sourceVolume,String semanticPolicy,PreparationClass preparationClass,List<CommitPhase> phases,
            FinalizationIntent finalizationIntent) {
        if(phases==null||phases.isEmpty())throw new IllegalArgumentException("at least one phase is required");
        this.id=id;this.world=world;this.policy=policy;this.coordinator=coordinator;this.kind=kind;
        this.sourceVolume=sourceVolume;this.semanticPolicy=semanticPolicy;this.preparationClass=preparationClass;
        List<CommitPhase> sorted=new ArrayList<CommitPhase>(phases);Collections.sort(sorted,new java.util.Comparator<CommitPhase>(){
            public int compare(CommitPhase a,CommitPhase b){return a.getOrder()-b.getOrder();}});
        for(int i=0;i<sorted.size();i++)if(sorted.get(i).getOrder()!=i)throw new IllegalArgumentException("phase orders must be contiguous");
        this.phases=Collections.unmodifiableList(sorted);this.finalizationIntent=finalizationIntent;
        phaseProgress=new ArrayList<OperationPhaseProgress>(sorted.size());ready=new ArrayList<Deque<PreparedOperationChunk.PhasePartition>>(sorted.size());
        for(int i=0;i<sorted.size();i++){phaseProgress.add(new OperationPhaseProgress());ready.add(new ArrayDeque<PreparedOperationChunk.PhasePartition>());}
    }
    public long getId(){return id;} public String getKind(){return kind;} public String getSourceVolume(){return sourceVolume;}
    public String getSemanticPolicy(){return semanticPolicy;} public PreparationClass getPreparationClass(){return preparationClass;}
    public List<CommitPhase> getPhases(){return phases;} public int getCurrentPhaseIndex(){return currentPhase;}
    public CommitPhase getCurrentPhase(){return currentPhase<phases.size()?phases.get(currentPhase):null;}
    public OperationPhaseProgress getPhaseProgress(int phase){return new OperationPhaseProgress(phaseProgress.get(phase));}
    public OperationState getState(){return state;} public Throwable getFailure(){return failure;}
    public OperationStatistics statistics(){return coordinator.statistics(this);} public boolean cancel(){return coordinator.cancel(this);}
}
