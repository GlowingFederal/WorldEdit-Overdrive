package com.glowingfederal.worldeditoverdrive.execution;

/** Immutable, backend-neutral description of one operation-wide commit phase. */
public final class CommitPhase {
    private final String id; private final int order; private final boolean barrierAfter;
    private final SchedulingUnitType unitType; private final boolean strictOrder, chunkBatching;
    public CommitPhase(String id,int order,boolean barrierAfter,SchedulingUnitType unitType,
            boolean strictOrder,boolean chunkBatching) {
        if(id==null||id.length()==0||order<0||unitType==null)throw new IllegalArgumentException("invalid phase");
        this.id=id;this.order=order;this.barrierAfter=barrierAfter;this.unitType=unitType;
        this.strictOrder=strictOrder;this.chunkBatching=chunkBatching;
    }
    public String getId(){return id;} public int getOrder(){return order;}
    public boolean hasBarrierAfter(){return barrierAfter;} public SchedulingUnitType getUnitType(){return unitType;}
    public boolean isStrictOrder(){return strictOrder;} public boolean allowsChunkBatching(){return chunkBatching;}
}
