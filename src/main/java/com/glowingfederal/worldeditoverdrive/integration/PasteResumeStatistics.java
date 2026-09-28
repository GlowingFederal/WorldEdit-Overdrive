package com.glowingfederal.worldeditoverdrive.integration;

/** Fixed memory distribution; median is an explicit 0.25 ms interval, not a last sample. */
final class PasteResumeStatistics {
    private static final long WIDTH=250000L;
    private final long[] histogram=new long[257];
    private long count,total,totalAllowance,maximum,over50;

    void record(long elapsed,long allowance){
        histogram[(int)Math.min(histogram.length-1,elapsed/WIDTH)]++;
        count++;total+=elapsed;totalAllowance+=allowance;maximum=Math.max(maximum,elapsed);
        if(elapsed>50000000L)over50++;
        long seen=0,lowerRank=(count+1L)/2L,upperRank=count/2L+1L;
        int lowerBucket=-1,upperBucket=histogram.length-1;
        for(int bucket=0;bucket<histogram.length;bucket++){seen+=histogram[bucket];if(lowerBucket<0&&seen>=lowerRank)lowerBucket=bucket;if(seen>=upperRank){upperBucket=bucket;break;}}
        PasteHookStatus.totalCommitResumeNanos.set(total);PasteHookStatus.totalResumeAllowanceNanos.set(totalAllowance);
        PasteHookStatus.maxCommitResumeNanos.set(maximum);PasteHookStatus.commitResumesOver50Millis.set(over50);
        PasteHookStatus.medianCommitResumeLowerNanos.set((lowerBucket+upperBucket)*WIDTH/2L);
        PasteHookStatus.medianCommitResumeUpperNanos.set(upperBucket==histogram.length-1?Long.MAX_VALUE:(lowerBucket+upperBucket+2L)*WIDTH/2L);
    }
}
