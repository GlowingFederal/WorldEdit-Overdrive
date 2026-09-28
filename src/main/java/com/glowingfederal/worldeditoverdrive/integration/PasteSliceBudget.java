package com.glowingfederal.worldeditoverdrive.integration;

/** Per-phase feedback. Soft slice completion is not overload. All decisions use
 * caller-supplied time, so the production controller can be tested deterministically. */
final class PasteSliceBudget {
    static final long INITIAL_NANOS=5000000L, MIN_NANOS=4000000L, MAX_NANOS=20000000L;
    static final long DEPENDENCY_MAX_NANOS=10000000L, ENTITY_MAX_NANOS=5000000L;
    private long target=INITIAL_NANOS,lastTick=Long.MIN_VALUE,lastAdjustment;
    private long increases,decreases,effective,sliceUnits,sliceChunkLoads,sliceMaximum,sliceChunkNanos,typicalUnit=100000L,tickSpent,workDeadline=Long.MAX_VALUE,reserveCeiling=Long.MAX_VALUE;
    private int stableTicks,cooldown,backoff,expensiveTicks;
    private boolean usefulLastTick,chunkLastTick,unexplainedLastTick,dangerousLastTick;
    private final Samples mutations=new Samples(128),resumes=new Samples(64),placements=new Samples(64);
    private final Samples loaded=new Samples(128);
    private final Samples tileCosts=new Samples(64),chunkCosts=new Samples(32);
    private String limitReason="initial";
    private String increaseReason="initial",decreaseReason="none",reason="initial";

    long targetNanos(){return target;}
    long increases(){return increases;}
    long decreases(){return decreases;}
    long effectiveNanos(){return effective;}
    int backoffLevel(){return backoff;}
    int stableTicks(){return stableTicks;}
    long lastAdjustment(){return lastAdjustment;}
    String reason(){return reason;}
    String limitReason(){return limitReason;}
    long tileAverage(){return tileCosts.average();}
    long tileP95(){return tileCosts.p95();}
    long chunkAverage(){return chunkCosts.average();}
    long chunkP95(){return chunkCosts.p95();}
    String increaseReason(){return increaseReason;}
    String decreaseReason(){return decreaseReason;}
    long mutationAverage(){return mutations.average();}
    long mutationP95(){return mutations.p95();}
    long resumeAverage(){return resumes.average();}
    long resumeP95(){return resumes.p95();}
    double placementsAverage(){return placements.mean();}

    long beginSlice(long tick,long now,long hardDeadline,long available,long stageMaximum){
        long ceiling=Math.max(0,Math.min(stageMaximum,available));
        if(tick!=lastTick){
            typicalUnit=Math.max(100000L,loaded.p95());
            boolean hadTick=lastTick!=Long.MIN_VALUE;lastTick=tick;tickSpent=0;
            if(cooldown>0)cooldown--;
            boolean pressure=ceiling<target;
            if(pressure){adjust(ceiling,now,"server_headroom_or_stage_limit",false);stableTicks=0;}
            if(dangerousLastTick){reduce(now,"single_downstream_mutation_over_50ms");backoff=Math.max(3,backoff);cooldown=8;}
            else if(pressure){stableTicks=0;}
            else if(target<Math.min(MIN_NANOS,ceiling)){adjust(Math.min(MIN_NANOS,ceiling),now,"server_headroom_recovery",true);}
            else if(unexplainedLastTick){reduce(now,"scheduler_hard_deadline_overrun");cooldown=6;}
            else if(chunkLastTick&&cooldown==0){reduce(now,"chunk_load_transition");cooldown=4;}
            else if(usefulLastTick&&loaded.count>=8&&loaded.average()>4000000L){
                if(++expensiveTicks>=3&&cooldown==0){reduce(now,"sustained_expensive_loaded_mutations");cooldown=6;expensiveTicks=0;}
            }else{
                expensiveTicks=0;
                if(hadTick&&usefulLastTick&&cooldown==0){
                    stableTicks++;
                    if(stableTicks>=2){long next=Math.min(ceiling,Math.max(MIN_NANOS,target)+Math.max(1000000L,target/4));adjust(next,now,"sustained_server_headroom",true);stableTicks=0;if(backoff>0)backoff--;}
                }
            }
            usefulLastTick=false;chunkLastTick=false;unexplainedLastTick=false;dangerousLastTick=false;
        }
        long phaseAllowance=available;for(int i=0;i<backoff;i++)phaseAllowance=phaseAllowance*3/4;
        // A cold-class or chunk-adjacent sample must not make the next unit
        // impossible forever. Reserve typical cost, capped to part of a full
        // tick allowance; indivisible overruns are measured independently.
        reserveCeiling=Math.max(100000L,phaseAllowance/4);
        long remaining=Math.max(0,phaseAllowance-tickSpent);workDeadline=Math.min(hardDeadline,now+remaining);
        effective=Math.max(0,Math.min(Math.min(target,ceiling),workDeadline-now));
        limitReason=available<=0?"no_server_headroom":remaining<=0?"phase_tick_budget_exhausted":workDeadline-now<target?"remaining_hard_or_phase_deadline":ceiling<target?"headroom_or_stage_cap":backoff>0?"temporary_backoff":"phase_target";
        sliceUnits=0;sliceChunkLoads=0;sliceChunkNanos=0;sliceMaximum=0;
        return now+effective;
    }
    private void reduce(long now,String why){
        adjust(Math.max(Math.min(target,MIN_NANOS),target*3/4),now,why,false);
        backoff=Math.min(8,backoff+1);stableTicks=0;
    }
    private void adjust(long next,long now,String why,boolean up){
        reason=why;
        if(next==target)return;
        if(up){increases++;increaseReason=why;}else{decreases++;decreaseReason=why;}
        target=next;lastAdjustment=now;
    }
    void recordMutation(long nanos,boolean chunkLoad,boolean tile){
        nanos=Math.max(0,nanos);mutations.add(nanos);sliceUnits++;sliceMaximum=Math.max(sliceMaximum,nanos);
        if(chunkLoad){sliceChunkLoads++;sliceChunkNanos+=nanos;chunkLastTick=true;chunkCosts.add(nanos);}else loaded.add(nanos);
        if(tile)tileCosts.add(nanos);
        if(nanos>50000000L){dangerousLastTick=true;reason=chunkLoad?"single_chunk_load_over_50ms":tile?"single_tile_mutation_over_50ms":"single_mutation_over_50ms";}
    }
    long sliceUnits(){return sliceUnits;}
    long sliceChunkLoads(){return sliceChunkLoads;}
    long sliceChunkNanos(){return sliceChunkNanos;}
    long sliceMaximum(){return sliceMaximum;}

    // Typical cost is reserved at the HARD boundary. Soft slices may finish a
    // unit; door pairs reserve two calls and complete atomically once begun.
    long unitReserve(int calls){return Math.min(reserveCeiling,Math.max(typicalUnit,loaded.average()))*calls;}
    boolean canStartUnit(long now,long softDeadline,long hardDeadline,int calls){return now<softDeadline&&Math.min(hardDeadline,workDeadline)-now>=unitReserve(calls);}
    void observe(long elapsed,long allowance,boolean workRemains,long hardRemainingAtEntry){
        if(allowance<=0)return;
        tickSpent+=elapsed;
        if(sliceUnits==0&&elapsed<allowance/2)return;
        resumes.add(elapsed);placements.add(sliceUnits);
        usefulLastTick|=sliceUnits>0||workRemains&&elapsed>=allowance/2;
        // Overshoot attributable to the final indivisible call cannot feed the
        // old 1 ms feedback loop. Unexplained overhead still triggers backoff.
        if(elapsed>hardRemainingAtEntry&&elapsed-hardRemainingAtEntry>sliceMaximum+250000L)unexplainedLastTick=true;
    }

    /** Bounded rolling samples. Percentiles are calculated only for status reads. */
    static final class Samples {
        final long[] values;int count,cursor;long total;
        Samples(int capacity){values=new long[capacity];}
        void add(long n){if(count==values.length)total-=values[cursor];else count++;values[cursor]=n;total+=n;cursor=(cursor+1)%values.length;}
        long average(){return count==0?0:total/count;}
        double mean(){return count==0?0D:(double)total/count;}
        long p95(){if(count==0)return 0;long[] sorted=new long[count];System.arraycopy(values,0,sorted,0,count);java.util.Arrays.sort(sorted);return sorted[(count*95+99)/100-1];}
    }
}
