package com.glowingfederal.worldeditoverdrive.integration;

import java.util.ArrayList;
import java.util.List;

/** Bounded, server-thread-only samples of real downstream calls. No world is retained. */
public final class PlacementProfile {
    static final int CHUNK=0, LIGHT=1, SKY=2, BLOCK_LIGHT=3, HEIGHT=4, CHUNK_SKY=5,
            NEIGHBOR=6, CLIENT=7, TILE=8, LOOKUP=9, CALLBACK=10, SCHEDULE=11;
    static final String[] NAMES={"chunkMutation","checkLight","skylightPropagation","blockLightPropagation",
            "heightmapRelight","chunkSkylightMap","neighborNotification","clientUpdateMark",
            "tileInstall","chunkLookup","blockCallback","scheduledUpdateMethod"};
    public static volatile PlacementProfile latest;
    public static volatile boolean optimized=true;
    public static volatile int sampleEvery=64;
    public static volatile String forgeHook="not seen", lightingHook="not seen", chunkHook="not seen", clientHook="not seen";
    public static volatile String tileHook="not seen", scheduledHook="not seen";
    final long[] calls=new long[NAMES.length], nanos=new long[NAMES.length], maxima=new long[NAMES.length];
    final int[] depth=new int[NAMES.length];
    private final PasteSliceBudget.Samples durations=new PasteSliceBudget.Samples(512);
    private final long[] categoryCalls=new long[64], categoryNanos=new long[64];
    private final String[] labels=new String[64];
    int categories;
    private int samplingState=0x13579bdf;
    long attempts,samples,totalNanos,maxNanos,diagnosticNanos,lightSkipped,clientIndexed,clientComparisonsAvoided;
    long started,finished, sampleStarted;
    int oldId=-1,newId,x,y,z,oldHeight; boolean heightChanged,tile;
    String transition="unknown";
    boolean sampled;
    void begin(int x,int y,int z,int id,boolean tile){
        if(started==0)started=System.nanoTime();finished=0;
        this.x=x;this.y=y;this.z=z;newId=id;this.tile=tile;oldId=-1;heightChanged=false;transition="unknown";
        attempts++;int interval=sampleEvery;
        samplingState=samplingState*1664525+1013904223;
        sampled=interval>0&&(samplingState>>>16)%interval==0;
        if(sampled){java.util.Arrays.fill(depth,0);sampleStarted=System.nanoTime();}
        latest=this;
    }
    void end(){
        if(!sampled)return;
        long end=System.nanoTime(),elapsed=end-sampleStarted; samples++;totalNanos+=elapsed;maxNanos=Math.max(maxNanos,elapsed);durations.add(elapsed);
        String label=oldId+"->"+newId+" "+transition+" chunk="+(x>>4)+","+(z>>4)+" section="+(y>>4)+" heightChanged="+heightChanged+" tile="+tile;
        int slot=0;while(slot<categories&&!labels[slot].equals(label))slot++;
        if(slot==categories&&categories<labels.length){labels[slot]=label;categories++;}
        else if(slot==categories){
            // Keep costly new regions visible after a large paste fills the
            // bounded table. Counters cover each candidate's retained lifetime.
            int cheapest=0;for(int i=1;i<categories;i++)if(categoryNanos[i]/categoryCalls[i]<categoryNanos[cheapest]/categoryCalls[cheapest])cheapest=i;
            if(elapsed>categoryNanos[cheapest]/categoryCalls[cheapest]){slot=cheapest;labels[slot]=label;categoryCalls[slot]=0;categoryNanos[slot]=0;}
        }
        if(slot<categories){categoryCalls[slot]++;categoryNanos[slot]+=elapsed;}
        diagnosticNanos+=System.nanoTime()-end;
        sampled=false;
    }
    void finish(){if(finished==0&&started!=0)finished=System.nanoTime();}
    public String[] describe(){
        List<String> lines=new ArrayList<String>();
        lines.add("placementBackend=nativeChunk optimized="+optimized+" sampleEvery="+sampleEvery+" attempts="+attempts+" samples="+samples+" opticalChecksSkipped="+lightSkipped+" indexedClientMarks="+clientIndexed+" clientComparisonsAvoided="+clientComparisonsAvoided);
        lines.add("downstreamHooks forge="+forgeHook+" worldLight="+lightingHook+" chunk="+chunkHook+" client="+clientHook+" tile="+tileHook+" scheduled="+scheduledHook);
        lines.add("sampledPlacementMillis="+ms(totalNanos)+" sampledMeanMillis="+ms(samples==0?0:totalNanos/samples)+" recentSampleP95Millis="+ms(durations.p95())+" sampledMaxMillis="+ms(maxNanos)+" diagnosticAggregationMillis="+ms(diagnosticNanos)+" placementWallMillis="+ms(started==0?0:(finished==0?System.nanoTime():finished)-started));
        lines.add("Timings/counts below are sampled, inclusive; nested categories overlap. Uninstrumented overrides are excluded. Scheduled method calls include delegation, not unique queue insertions.");
        for(int i=0;i<NAMES.length;i++)lines.add(NAMES[i]+"SampleCalls="+calls[i]+" sampleMillis="+ms(nanos[i])+" sampleMaxMillis="+ms(maxima[i]));
        boolean[] used=new boolean[categories];
        for(int rank=0;rank<Math.min(categories,5);rank++){int best=-1;for(int i=0;i<categories;i++)if(!used[i]&&(best<0||categoryNanos[i]>categoryNanos[best]))best=i;used[best]=true;lines.add("expensiveSample "+labels[best]+" samples="+categoryCalls[best]+" millis="+ms(categoryNanos[best]));}
        lines.add("Mutation groups are 64 bounded expensive candidates; counts cover retention, not global group totals. P95 covers the latest 512 samples. Compare native/optimized on equivalent worlds; full profiling perturbs timings.");
        return lines.toArray(new String[lines.size()]);
    }
    private static String ms(long n){return String.format(java.util.Locale.ROOT,"%.3f",n/1000000D);}
}
