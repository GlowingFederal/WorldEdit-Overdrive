package com.glowingfederal.worldeditoverdrive.integration;

/** Time feedback owned by one paste and one phase, never learned from idle ticks. */
final class PasteSliceBudget {
    static final long INITIAL_NANOS=5000000L, MIN_NANOS=1000000L, MAX_NANOS=30000000L;
    private long target=INITIAL_NANOS;
    private int safeSamples;
    private long increases,decreases;

    long targetNanos(){return target;}
    long increases(){return increases;}
    long decreases(){return decreases;}
    void resetStage(){target=Math.min(target,INITIAL_NANOS);safeSamples=0;}

    void observe(long elapsed,long allowance,boolean workRemains){
        if(allowance<=0)return;
        if(elapsed>allowance*2L){target=MIN_NANOS;safeSamples=0;decreases++;}
        else if(elapsed>allowance+Math.max(100000L,allowance/10L)){
            target=Math.max(MIN_NANOS,Math.min(target/2L,allowance/2L));safeSamples=0;decreases++;
        }else if(workRemains&&allowance>=target*3L/4L&&elapsed>=allowance*3L/4L){
            if(++safeSamples>=4){long next=Math.min(MAX_NANOS,target+target/10L);if(next>target)increases++;target=next;safeSamples=0;}
        }else safeSamples=0;
    }
}
