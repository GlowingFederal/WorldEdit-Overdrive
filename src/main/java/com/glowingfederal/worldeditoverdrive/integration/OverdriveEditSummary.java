package com.glowingfederal.worldeditoverdrive.integration;

/** Immutable latest-operation record shared by logging, commands, and future profiling. */
public final class OverdriveEditSummary {
    public final String operationKind,executionMode,failedPhase,failureText;
    public final long selectedVolume,actualChangedBlocks,planningNanos,filteringNanos,historyNanos,
            commitNanos,lightingNanos,synchronizationNanos,totalNanos,peakPreparedBytes;
    public final int chunks,denseSections,sparseSections,rawBlocks,nativeBlocks,sparsePackets,chunkPackets,tilePackets;
    public final boolean success;
    public OverdriveEditSummary(String kind,String mode,long selected,long changed,int chunks,int dense,int sparse,
            int raw,int nativeCount,long planning,long filtering,long history,long commit,long lighting,long synchronization,
            long total,int sparsePackets,int chunkPackets,int tilePackets,long peak,boolean success,String phase,String failure){
        this.operationKind=kind;this.executionMode=mode;this.selectedVolume=selected;this.actualChangedBlocks=changed;
        this.chunks=chunks;this.denseSections=dense;this.sparseSections=sparse;this.rawBlocks=raw;this.nativeBlocks=nativeCount;
        this.planningNanos=planning;this.filteringNanos=filtering;this.historyNanos=history;this.commitNanos=commit;
        this.lightingNanos=lighting;this.synchronizationNanos=synchronization;this.totalNanos=total;
        this.sparsePackets=sparsePackets;this.chunkPackets=chunkPackets;this.tilePackets=tilePackets;
        this.peakPreparedBytes=peak;this.success=success;this.failedPhase=phase;this.failureText=failure;
    }
    public String format(){return String.format("//set: selected=%d changed=%d chunks=%d dense=%d sparse=%d raw=%d native=%d plan=%.1fms filter=%.1fms history=%.1fms commit=%.1fms light=%.1fms sync=%.1fms total=%.1fms peak=%.1fMiB",
            selectedVolume,actualChangedBlocks,chunks,denseSections,sparseSections,rawBlocks,nativeBlocks,ms(planningNanos),
            ms(filteringNanos),ms(historyNanos),ms(commitNanos),ms(lightingNanos),ms(synchronizationNanos),ms(totalNanos),peakPreparedBytes/1048576D);}
    public static double ms(long nanos){return nanos/1000000D;}
}
