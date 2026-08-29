package com.glowingfederal.worldeditoverdrive.execution;

/** Feedback controller for all server-thread accelerator work. It controls time, never a block rate. */
public final class AdaptiveServerBudget {
    private static final long TICK=50000000L,MIN=1000000L,MAX=30000000L;
    private double normalNanos=15000000D,deviationNanos=2000000D,usedNanos=5000000D;
    private long budgetNanos=5000000L,lastHeadroomNanos,lastUsedNanos,maximumUsedNanos;
    public synchronized long beginTick(long observedNormalNanos){double error=Math.abs(observedNormalNanos-normalNanos);normalNanos=normalNanos*.85D+observedNormalNanos*.15D;deviationNanos=deviationNanos*.85D+error*.15D;long reserve=(long)Math.max(5000000D,deviationNanos*2.5D);lastHeadroomNanos=Math.max(0L,TICK-(long)normalNanos-reserve);if(observedNormalNanos>TICK*2L)budgetNanos=MIN;else if(observedNormalNanos>TICK)budgetNanos=Math.max(MIN,budgetNanos/3L);else{long desired=Math.max(MIN,Math.min(MAX,lastHeadroomNanos));budgetNanos=desired<budgetNanos?desired:(budgetNanos*3L+desired)/4L;}return budgetNanos;}
    public synchronized void endTick(long actualUsedNanos){lastUsedNanos=actualUsedNanos;maximumUsedNanos=Math.max(maximumUsedNanos,actualUsedNanos);usedNanos=usedNanos*.8D+actualUsedNanos*.2D;if(actualUsedNanos>budgetNanos*3L/2L)budgetNanos=Math.max(MIN,budgetNanos*2L/3L);}
    public synchronized long budgetNanos(){return budgetNanos;}public synchronized long headroomNanos(){return lastHeadroomNanos;}public synchronized long lastUsedNanos(){return lastUsedNanos;}public synchronized long maximumUsedNanos(){return maximumUsedNanos;}public synchronized long estimatedUsedNanos(){return(long)usedNanos;}
}
