package com.glowingfederal.worldeditoverdrive.integration;

/**
 * Conservative feedback controller for the non-preemptible Enhanced native queue drain.
 * A caller must drain before {@link #limit()} more mutations can be accepted.
 */
final class NativeQueueDrainController {
    static final int MIN_MUTATIONS=32;
    static final int HARD_MAX_MUTATIONS=4096;
    private int limit=256;
    private double nanosPerMutation;

    int limit(){return Math.max(MIN_MUTATIONS,Math.min(HARD_MAX_MUTATIONS,limit));}
    void drained(int queued,long drainNanos,long targetNanos){
        if(queued<=0)return;
        double observed=(double)Math.max(1L,drainNanos)/queued;
        nanosPerMutation=nanosPerMutation==0D?observed:nanosPerMutation*.75D+observed*.25D;
        int estimated=(int)Math.max(MIN_MUTATIONS,Math.min(HARD_MAX_MUTATIONS,targetNanos/Math.max(1D,nanosPerMutation)));
        if(drainNanos>targetNanos)limit=Math.max(MIN_MUTATIONS,Math.min(limit/2,estimated));
        else limit=Math.min(estimated,Math.min(HARD_MAX_MUTATIONS,limit+Math.max(16,limit/4)));
    }
}
