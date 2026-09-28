package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.execution.AdaptiveServerBudget;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic elapsed-cost workloads exercise the actual production controller.
 * They do not predict Minecraft setBlock performance or include worker/disk latency. */
public class PastePacingTest {
    private static final long MS=1000000L,TICK=50*MS;
    @Test public void normalMutationOvershootAndOneOutlierRecover(){
        Result r=simulate(false,4000,0,20*MS,30*MS);
        assertEquals(20*MS,r.target);assertTrue(r.perSecond()>300);assertEquals(0,r.over50);assertTrue(r.increases>0);
    }
    @Test public void sustainedHeadroomIncreasesWithoutDecreasingAtTheFloor(){
        PasteSliceBudget p=new PasteSliceBudget();
        for(int tick=0;tick<30;tick++)slice(p,tick,30*MS,20*MS,1500000L,false);
        assertEquals(20*MS,p.targetNanos());assertEquals(0,p.decreases());
    }
    @Test public void realTickPressureShrinksImmediatelyAndZeroBudgetRecovers(){
        PasteSliceBudget p=new PasteSliceBudget();for(int t=0;t<20;t++)slice(p,t,30*MS,20*MS,1500000L,false);
        p.beginSlice(21,21*TICK,21*TICK+2*MS,2*MS,20*MS);assertEquals(2*MS,p.targetNanos());
        p.beginSlice(22,22*TICK,22*TICK,0,20*MS);assertEquals(0,p.effectiveNanos());
        slice(p,23,30*MS,20*MS,1500000L,false);assertTrue(p.targetNanos()>=4*MS);
        for(int t=24;t<60;t++)slice(p,t,30*MS,20*MS,1500000L,false);assertEquals(20*MS,p.targetNanos());
    }
    @Test public void repeatedExpensiveLoadedWorkBacksOff(){
        PasteSliceBudget p=new PasteSliceBudget();for(int t=0;t<50;t++)slice(p,t,30*MS,20*MS,6*MS,false);
        assertTrue(p.backoffLevel()>0);assertEquals("sustained_expensive_loaded_mutations",p.decreaseReason());assertTrue(p.targetNanos()<20*MS);
    }
    @Test public void chunkLoadBackoffIsTemporaryAndDoesNotPoisonLoadedSamples(){
        PasteSliceBudget p=new PasteSliceBudget();for(int t=0;t<20;t++)slice(p,t,30*MS,20*MS,1500000L,false);
        slice(p,20,30*MS,20*MS,10*MS,true);slice(p,21,30*MS,20*MS,1500000L,false);
        assertEquals("chunk_load_transition",p.decreaseReason());assertTrue(p.backoffLevel()>0);
        for(int t=22;t<60;t++)slice(p,t,30*MS,20*MS,1500000L,false);
        assertEquals(20*MS,p.targetNanos());assertEquals(0,p.backoffLevel());
    }
    @Test public void hardBoundaryNeverStartsAUnitWithoutItsReserve(){
        PasteSliceBudget p=new PasteSliceBudget();long d=p.beginSlice(1,0,30*MS,30*MS,20*MS);
        p.recordMutation(1500000L,false,false);p.observe(1500000L,d,true,30*MS);
        d=p.beginSlice(2,TICK,TICK+30*MS,30*MS,20*MS);
        assertFalse(p.canStartUnit(TICK+29*MS,TICK+30*MS,TICK+30*MS,1));
        assertFalse(p.canStartUnit(TICK+28*MS,TICK+30*MS,TICK+30*MS,2));
        assertFalse(p.canStartUnit(TICK+30*MS,TICK+31*MS,TICK+30*MS,1));
    }
    @Test public void coldSampleLargerThanTickAllowanceCannotStarvePreparation(){
        PasteSliceBudget p=new PasteSliceBudget();long d=p.beginSlice(0,0,10*MS,10*MS,20*MS);
        p.recordMutation(16*MS,false,false);p.observe(16*MS,d,true,10*MS);
        d=p.beginSlice(1,TICK,TICK+10*MS,10*MS,20*MS);assertTrue(p.canStartUnit(TICK,d,TICK+10*MS,1));
        for(int t=2;t<60;t++)slice(p,t,30*MS,20*MS,1500000L,false);assertEquals(20*MS,p.targetNanos());
    }
    @Test public void boundedSameTickResumesUtilizeBudgetAndStop(){
        Result r=simulate(false,2000,1,20*MS,30*MS);
        assertTrue(r.resumesPerTick()>1);assertTrue(r.placementsPerTick()>15);assertEquals(0,r.hardOverruns);assertEquals(0,r.over50);
    }
    @Test public void dependencyAndEntityStagesHaveSeparateCaps(){
        Result dependency=simulate(false,1000,1,PasteSliceBudget.DEPENDENCY_MAX_NANOS,30*MS);
        Result entity=simulate(false,1000,1,PasteSliceBudget.ENTITY_MAX_NANOS,30*MS);
        assertEquals(10*MS,dependency.target);assertEquals(5*MS,entity.target);assertTrue(dependency.perSecond()>300);assertTrue(entity.perSecond()>300);
    }
    @Test public void safetyReserveAndCurrentExternalLoadBoundSharedBudget(){
        AdaptiveServerBudget b=new AdaptiveServerBudget();b.configure(30*MS,5*MS);
        for(int i=0;i<100;i++)b.beginTick(10*MS);
        assertEquals(30*MS,b.budgetNanos());assertEquals(40*MS,b.headroomNanos());assertTrue(b.reserveNanos()>=5*MS);
        assertTrue(b.beginTick(47*MS)==0);assertEquals(0,b.beginTick(60*MS));
        for(int i=0;i<100;i++)b.beginTick(10*MS);assertEquals(30*MS,b.budgetNanos());
        b.configure(12*MS,8*MS);assertTrue(b.beginTick(10*MS)<=12*MS);
    }
    @Test public void downstreamOver50IsSeparateFromSchedulerOverrun(){
        PasteSliceBudget p=new PasteSliceBudget();long d=p.beginSlice(0,0,30*MS,30*MS,20*MS);
        p.recordMutation(60*MS,false,true);p.observe(60*MS,d,true,30*MS);
        PastePacingDiagnostics diagnostics=new PastePacingDiagnostics();diagnostics.record(0,60*MS,"COMMITTING",p,60*MS,30*MS,1,0,0);
        String output=Arrays.toString(diagnostics.describe());assertTrue(output.contains("pasteSingleMutationResumesOver50Millis=1"));assertTrue(output.contains("pasteSchedulerOverruns=0"));
        p.beginSlice(1,TICK,TICK+30*MS,30*MS,20*MS);assertEquals("single_downstream_mutation_over_50ms",p.decreaseReason());assertTrue(p.backoffLevel()>=3);
    }
    @Test public void unexplainedOverrunTriggersSchedulerBackoff(){
        PasteSliceBudget p=new PasteSliceBudget();long d=p.beginSlice(0,0,30*MS,30*MS,20*MS);
        p.recordMutation(MS,false,false);p.observe(60*MS,d,true,30*MS);
        p.beginSlice(1,TICK,TICK+30*MS,30*MS,20*MS);assertEquals("scheduler_hard_deadline_overrun",p.decreaseReason());
    }
    @Test public void benchmark350000MutationsAgainstPreviousController(){
        Result old=simulate(true,350000,2,20*MS,30*MS),updated=simulate(false,350000,2,20*MS,30*MS);
        System.out.println("PACING_BENCHMARK legacy "+old);System.out.println("PACING_BENCHMARK updated "+updated);
        assertTrue(updated.perSecond()>old.perSecond()*10);assertTrue(updated.target>old.target);assertEquals(0,updated.over50);
    }
    private static void slice(PasteSliceBudget p,int tick,long available,long maximum,long cost,boolean chunk){
        long now=tick*TICK,hard=now+available;
        for(int rounds=0;rounds<128&&now<hard;rounds++){
            long start=now,d=p.beginSlice(tick,now,hard,available,maximum);int units=0;
            while(p.canStartUnit(now,d,hard,1)){now+=cost;p.recordMutation(cost,chunk,false);units++;if(now>=hard)break;}
            p.observe(now-start,d-start,true,hard-start);if(units==0)break;
        }
    }
    // kind 0: one 9.5ms outlier; kind 1: exact normal units; kind 2: periodic
    // 5-10ms outliers among 1.25-1.5ms units. The former algorithm is copied
    // verbatim from the working tree before this change, including floor counts.
    private static Result simulate(boolean legacy,int total,int kind,long maximum,long available){
        PasteSliceBudget p=new PasteSliceBudget();Legacy old=new Legacy();Result r=new Result(total);
        int done=0;long normal=kind==1?1500000L:1375000L;
        while(done<total){
            long now=r.ticks*TICK+10*MS,hard=now+available;r.ticks++;
            for(int round=0;round<(legacy?1:128)&&now<hard&&done<total;round++){
                long start=now,d=legacy?Math.min(hard,now+old.target):p.beginSlice(r.ticks,now,hard,available,maximum);int units=0;
                while(done<total&&(legacy?now<d:p.canStartUnit(now,d,hard,1))){
                    long cost=kind==1?normal:1250000L+(done%3)*125000L;
                    if(kind==0&&done==17)cost=9500000L;
                    if(kind==2&&done%257==17)cost=5000000L+(done%6)*MS;
                    now+=cost;if(!legacy)p.recordMutation(cost,false,false);done++;units++;
                }
                if(legacy)old.observe(now-start,d-start,done<total);else p.observe(now-start,d-start,done<total,hard-start);
                if(units==0)break;
                r.durations[r.resumes]=now-start;r.units[r.resumes]=units;r.resumes++;r.sumDuration+=now-start;
                r.maximum=Math.max(r.maximum,now-start);if(now-start>50*MS)r.over50++;if(now>hard)r.hardOverruns++;
            }
            if(r.ticks==1||r.ticks==10||r.ticks==100||r.ticks==1000)r.timeline+="tick"+r.ticks+":"+(legacy?old.target:p.targetNanos())/(double)MS+"ms ";
        }
        r.target=legacy?old.target:p.targetNanos();r.increases=legacy?old.increases:p.increases();r.decreases=legacy?old.decreases:p.decreases();r.total=done;return r;
    }
    private static final class Legacy {
        long target=5*MS,increases,decreases;int safe;
        void observe(long elapsed,long allowance,boolean remains){
            if(allowance<=0)return;
            if(elapsed>allowance*2){target=MS;safe=0;decreases++;}
            else if(elapsed>allowance+Math.max(100000L,allowance/10)){target=Math.max(MS,Math.min(target/2,allowance/2));safe=0;decreases++;}
            else if(remains&&allowance>=target*3/4&&elapsed>=allowance*3/4){if(++safe>=4){long next=Math.min(30*MS,target+target/10);if(next>target)increases++;target=next;safe=0;}}
            else safe=0;
        }
    }
    private static final class Result {
        final long[] durations,units;int total,resumes,ticks,over50,hardOverruns;long maximum,sumDuration,target,increases,decreases;String timeline="";
        Result(int maximum){durations=new long[maximum];units=new long[maximum];}
        double perSecond(){return total/(ticks*.05D);}
        double resumesPerTick(){return (double)resumes/ticks;}
        double placementsPerTick(){return (double)total/ticks;}
        long p95(long[] array){long[] sorted=Arrays.copyOf(array,resumes);Arrays.sort(sorted);return sorted[(resumes*95+99)/100-1];}
        public String toString(){return "blocks/sec="+perSecond()+" averageResumeMs="+sumDuration/(double)resumes/MS+" p95ResumeMs="+p95(durations)/(double)MS+" maxResumeMs="+maximum/(double)MS+" averagePlacements/resume="+(double)total/resumes+" p95Placements/resume="+p95(units)+" resumes/tick="+resumesPerTick()+" placements/tick="+placementsPerTick()+" targetMs="+target/(double)MS+" increases="+increases+" decreases="+decreases+" over50="+over50+" hardOverruns="+hardOverruns+" targetTimeline="+timeline;}
    }
}
