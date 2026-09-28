package com.glowingfederal.worldeditoverdrive.execution;

/** Shared server allowance. Current external load shrinks it immediately;
 * recovery follows an EWMA. An exhausted tick receives zero work. */
public final class AdaptiveServerBudget {
    private static final long TICK=50000000L;
    private double normalNanos=15000000D,deviationNanos=2000000D,usedNanos=5000000D;
    private long configuredMaximum=30000000L,safetyMargin=5000000L;
    private long budgetNanos=5000000L,lastHeadroomNanos,lastUsedNanos,maximumUsedNanos,lastReserveNanos;
    public synchronized void configure(long maximum,long margin){configuredMaximum=Math.max(1000000L,Math.min(TICK,maximum));safetyMargin=Math.max(0,Math.min(TICK,margin));}
    public synchronized long beginTick(long observedNormalNanos){
        observedNormalNanos=Math.max(0,observedNormalNanos);
        double error=Math.abs(observedNormalNanos-normalNanos);normalNanos=normalNanos*.85D+observedNormalNanos*.15D;deviationNanos=deviationNanos*.85D+error*.15D;
        lastReserveNanos=(long)Math.max(safetyMargin,deviationNanos*2.5D);
        lastHeadroomNanos=Math.max(0,TICK-Math.max(observedNormalNanos,(long)normalNanos));
        long desired=Math.max(0,Math.min(configuredMaximum,lastHeadroomNanos-lastReserveNanos));
        budgetNanos=desired<budgetNanos?desired:Math.min(desired,(budgetNanos*3+desired)/4+250000L);
        return budgetNanos;
    }
    public synchronized void endTick(long actualUsedNanos){lastUsedNanos=actualUsedNanos;maximumUsedNanos=Math.max(maximumUsedNanos,actualUsedNanos);usedNanos=usedNanos*.8D+actualUsedNanos*.2D;}
    public synchronized long budgetNanos(){return budgetNanos;}
    public synchronized long headroomNanos(){return lastHeadroomNanos;}
    public synchronized long safetyMarginNanos(){return safetyMargin;}
    public synchronized long reserveNanos(){return lastReserveNanos;}
    public synchronized long lastUsedNanos(){return lastUsedNanos;}
    public synchronized long maximumUsedNanos(){return maximumUsedNanos;}
    public synchronized long estimatedUsedNanos(){return(long)usedNanos;}
}