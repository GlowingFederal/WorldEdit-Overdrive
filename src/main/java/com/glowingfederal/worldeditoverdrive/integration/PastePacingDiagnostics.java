package com.glowingfederal.worldeditoverdrive.integration;

/** Bounded operation diagnostics shared by preparation, paste, undo and redo.
 * Distribution sorting and rate calculation happen on status requests only. */
public final class PastePacingDiagnostics {
    public static volatile long hardDeadline,headroom,safetyMargin,reserve,tickSequence;
    public static volatile long resumesThisTick,placementsThisTick,sameTickResumes;
    private final long[] times=new long[64],placed=new long[64],changed=new long[64],captured=new long[64],submitted=new long[64];
    private int count,cursor;
    private long lastTick=Long.MIN_VALUE;
    private volatile PasteSliceBudget active;
    private volatile String phase="none";
    private String waitReason="none";
    void waitReason(String reason){waitReason=reason;}
    public String waitReason(){return waitReason;}
    private long hardOverruns,downstreamOverruns,schedulerOverruns,singleOver50,resumesOver50,chunkLoads,chunkNanos,lastHardRemaining;
    void record(long tick,long now,String phase,PasteSliceBudget pace,long elapsed,long hardRemaining,long changes,long captures,long submissions){
        this.active=pace;this.phase=phase;lastHardRemaining=Math.max(0,hardRemaining-elapsed);
        if(pace.sliceUnits()==0&&elapsed<250000L&&captures==0&&submissions==0)return;
        if(tick!=lastTick){lastTick=tick;cursor=count==0?0:(cursor+1)%times.length;if(count<times.length)count++;times[cursor]=now;placed[cursor]=changed[cursor]=captured[cursor]=submitted[cursor]=0;}
        boolean committing=phase.equals("COMMITTING")||phase.equals("UNDO_COMMIT")||phase.equals("REDO_COMMIT");long placementCount=committing?pace.sliceUnits():0;
        placed[cursor]+=placementCount;changed[cursor]+=changes;captured[cursor]+=captures;submitted[cursor]+=submissions;
        resumesThisTick++;if(resumesThisTick>1)sameTickResumes++;placementsThisTick+=placementCount;
        chunkLoads+=pace.sliceChunkLoads();chunkNanos+=pace.sliceChunkNanos();
        if(elapsed>50000000L){resumesOver50++;if(pace.sliceMaximum()>50000000L)singleOver50++;else schedulerOverruns++;}
        if(elapsed>hardRemaining){hardOverruns++;if(elapsed-hardRemaining<=pace.sliceMaximum()+250000L)downstreamOverruns++;else schedulerOverruns++;}
    }
    private double rate(long[] values){
        if(count<2)return 0;
        int first=count<times.length?0:(cursor+1)%times.length;
        long span=Math.max(50000000L,System.nanoTime()-times[first]);long sum=0;for(int i=0;i<count;i++)sum+=values[i];
        return sum*1000000000D/span;
    }
    private static double ms(long n){return n/1000000D;}
    public String[] describe(){
        PasteSliceBudget p=active;if(p==null)return new String[]{"pasteControllerReason=waiting_for_work"};
        return new String[]{
            "pasteControllerPhase="+phase+" pasteControllerTargetMillis="+ms(p.targetNanos())+" pasteEffectiveBudgetMillis="+ms(p.effectiveNanos())+" pasteControllerReason="+p.reason()+" pasteControllerLimitReason="+p.limitReason()+" pasteControllerWaitReason="+waitReason,
            "pasteHardTickDeadlineRemainingMillis="+ms(lastHardRemaining)+" pasteServerHeadroomMillis="+ms(headroom)+" pasteSafetyMarginMillis="+ms(safetyMargin)+" pasteEffectiveSafetyReserveMillis="+ms(reserve),
            "pasteRecentResumeAverageMillis="+ms(p.resumeAverage())+" pasteRecentResumeP95Millis="+ms(p.resumeP95())+" pasteRecentMutationAverageMillis="+ms(p.mutationAverage())+" pasteRecentMutationP95Millis="+ms(p.mutationP95())+" pasteRecentPlacementsPerResume="+p.placementsAverage(),
            "pasteRecentPlacementsPerSecond="+rate(placed)+" pasteRecentCommittedPerSecond="+rate(changed)+" pasteRecentCapturedPerSecond="+rate(captured)+" pasteRecentSubmittedPerSecond="+rate(submitted),
            "pasteRecentTileMutationAverageMillis="+ms(p.tileAverage())+" pasteRecentTileMutationP95Millis="+ms(p.tileP95())+" pasteRecentChunkLoadAverageMillis="+ms(p.chunkAverage())+" pasteRecentChunkLoadP95Millis="+ms(p.chunkP95()),
            "pasteControllerIncreaseReason="+p.increaseReason()+" pasteControllerDecreaseReason="+p.decreaseReason()+" pasteControllerLastAdjustmentAgeMillis="+(p.lastAdjustment()==0?0:ms(System.nanoTime()-p.lastAdjustment()))+" pasteControllerBackoffLevel="+p.backoffLevel()+" pasteControllerStableTicks="+p.stableTicks(),
            "pasteSameTickResumeCount="+sameTickResumes+" pasteResumesThisTick="+resumesThisTick+" pastePlacementsThisTick="+placementsThisTick,
            "pasteHardDeadlineOverruns="+hardOverruns+" pasteDownstreamDeadlineOverruns="+downstreamOverruns+" pasteSchedulerOverruns="+schedulerOverruns+" pasteResumesOver50Millis="+resumesOver50+" pasteSingleMutationResumesOver50Millis="+singleOver50+" pasteChunkLoadCount="+chunkLoads+" pasteChunkLoadMillis="+ms(chunkNanos)
        };
    }
}
