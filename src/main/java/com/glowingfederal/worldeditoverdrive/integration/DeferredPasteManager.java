package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MutableBlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Owns compatibility traversal and the narrow identity-paste accelerator. */
public final class DeferredPasteManager {
    private static final long GLOBAL=128L<<20,PER_OPERATION=64L<<20,COMMIT_NANOS=5000000L;
    private static final Queue<Owner> OWNERS=new ArrayDeque<Owner>();
    private static final ExecutorService WORKERS=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),new ThreadFactory(){
        private final AtomicLong sequence=new AtomicLong();public Thread newThread(Runnable task){Thread thread=new Thread(task,"worldedit-overdrive-paste-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;}});
    private static final AtomicLong RETAINED=new AtomicLong();
    public static synchronized void register(ForwardExtentCopy operation,PasteOperationAdapter adapter,Player player,LocalSession session,boolean selectPasted)throws Exception{
        if(operation==null||adapter==null||player==null||session==null)throw new NullPointerException("deferred paste context");
        Owner owner=new Owner(operation,adapter,player,session,session.getClipboard(),selectPasted);OWNERS.add(owner);PasteHookStatus.pasteDeferredActive.incrementAndGet();
    }
    public static void tick(){
        Owner[] snapshot; synchronized(DeferredPasteManager.class){snapshot=OWNERS.toArray(new Owner[OWNERS.size()]);}
        for(Owner owner:snapshot){try{if(owner.tick())remove(owner,true,null);}catch(Throwable failure){remove(owner,false,failure);}}
    }
    private static void remove(Owner owner,boolean success,Throwable failure){synchronized(DeferredPasteManager.class){if(!OWNERS.remove(owner))return;}
        owner.release();if(owner.lifecycle.state()==PasteContinuationOperation.State.COMMITTING)PasteHookStatus.pasteCommitActive.decrementAndGet();PasteHookStatus.pasteDeferredActive.decrementAndGet();
        if(success)PasteHookStatus.pasteDeferredCompleted.incrementAndGet();else{PasteHookStatus.pasteDeferredFailed.incrementAndGet();PasteHookStatus.lastPasteDeferredReason="failed: "+failure;owner.player.printError("Paste failed: "+failure.getMessage());OverdriveLog.error("deferred paste failed: {}",failure.toString());}}
    private static final class Plan {final int[] x,y,z,state;Plan(int[] x,int[] y,int[] z,int[] state){this.x=x;this.y=y;this.z=z;this.state=state;}}
    private static final class Owner {
        final ForwardExtentCopy original;final PasteOperationAdapter adapter;final Player player;final LocalSession session;final ClipboardHolder holder;final boolean select;
        final PasteContinuationOperation lifecycle=new PasteContinuationOperation();volatile Plan plan;volatile Throwable planningFailure;boolean vanilla,mutation;int cursor;long reserved,commitNanos;
        Owner(ForwardExtentCopy original,PasteOperationAdapter adapter,Player player,LocalSession session,ClipboardHolder holder,boolean select)throws Exception{
            this.original=original;this.adapter=adapter;this.player=player;this.session=session;this.holder=holder;this.select=select;
            PasteOperationAdapter.Eligibility eligible=adapter.accelerationEligibility();if(eligible.kind!=PasteOperationAdapter.Eligibility.Kind.ACCELERATE){defer(eligible.reason);return;}
            long volume=adapter.region.getArea(),estimate=64L+volume*24L;if(volume>Integer.MAX_VALUE||estimate>PER_OPERATION){defer("accelerated paste estimate exceeds operation memory limit: "+estimate);return;}
            if(!reserve(estimate)){defer("accelerated paste estimate exceeds available global memory: "+estimate);return;}reserved=estimate;
            long started=System.nanoTime();PreparedClipboardView prepared=capture((BlockArrayClipboard)adapter.clipboard);PasteHookStatus.lastPastePrepareMillis.set(ms(started));PasteHookStatus.pastePreparedBlocks.addAndGet(prepared.getVolume());
            if(prepared.hasTiles()){release();defer("tile entities not yet supported by accelerated paste");return;}
            lifecycle.submitted();PasteHookStatus.pastePlanningActive.incrementAndGet();WORKERS.execute(new Planner(this,prepared,adapter.ignoreAir));
            PasteHookStatus.lastPasteDeferredReason="accelerated identity clipboard paste admitted";
        }
        void defer(String reason){vanilla=true;PasteHookStatus.pasteAccelerationFallbacks.incrementAndGet();PasteHookStatus.lastPasteAccelerationFallbackReason=reason;PasteHookStatus.lastPasteDeferredReason="deferred vanilla: "+reason;}
        boolean tick()throws Exception{
            if(vanilla){Operations.completeLegacy(original);adapter.destination.flushQueue();finish();return true;}
            if(planningFailure!=null){if(!mutation){defer("worker planning failed before mutation: "+planningFailure);return false;}throw new Exception("accelerated planning failed",planningFailure);}
            Plan ready=plan;if(ready==null)return false;if(lifecycle.state()==PasteContinuationOperation.State.RUNNING){lifecycle.committing();PasteHookStatus.pasteCommitActive.incrementAndGet();}
            long tickStarted=System.nanoTime(),deadline=tickStarted+COMMIT_NANOS;MutableBlockVector position=new MutableBlockVector();int submitted=0,changed=0;
            // flushQueue has no incremental/time-budget overload at EditSession level. Bound the
            // queue which it must drain as well as the submission loop, and include that drain in
            // the measured commit time. A flushed batch, not setBlock(), is a visible commit.
            while(cursor<ready.state.length&&submitted<256&&System.nanoTime()<deadline){int packed=ready.state[cursor];position.setComponents(ready.x[cursor],ready.y[cursor],ready.z[cursor]);if(adapter.destination.setBlock(position,new BaseBlock(packed>>>4,packed&15)))changed++;mutation=true;cursor++;submitted++;}
            if(submitted!=0){PasteHookStatus.pasteSubmittedBlocks.addAndGet(submitted);adapter.destination.flushQueue();PasteHookStatus.pasteCommittedBlocks.addAndGet(changed);}
            commitNanos+=System.nanoTime()-tickStarted;PasteHookStatus.lastPasteCommitMillis.set(commitNanos/1000000L);
            if(cursor<ready.state.length)return false;
            // The last batch is world-applied before history and success become observable.
            finish();PasteHookStatus.pasteCommitActive.decrementAndGet();lifecycle.complete();PasteHookStatus.pasteAccelerated.incrementAndGet();return true;
        }
        void finish(){session.remember(adapter.destination);Vector to=adapter.destinationOrigin;if(select){Vector max=to.add(adapter.region.getMaximumPoint().subtract(adapter.region.getMinimumPoint()));RegionSelector selector=new CuboidRegionSelector(player.getWorld(),to,max);session.setRegionSelector(player.getWorld(),selector);selector.learnChanges();selector.explainRegionAdjust(player,session);}player.print("The clipboard has been pasted at "+to);}
        void release(){if(reserved!=0){RETAINED.addAndGet(-reserved);reserved=0;}}
    }
    private static final class Planner implements Runnable {final Owner owner;final PreparedClipboardView view;final boolean ignoreAir;Planner(Owner o,PreparedClipboardView v,boolean i){owner=o;view=v;ignoreAir=i;}
        public void run(){long started=System.nanoTime();try{owner.lifecycle.running();int count=0;for(int y=0;y<view.getSizeY();y++)for(int z=0;z<view.getSizeZ();z++)for(int x=0;x<view.getSizeX();x++){int s=view.packedStateAt(view.getMinX()+x,view.getMinY()+y,view.getMinZ()+z);if(!ignoreAir||(s>>>4)!=0)count++;}
            int[] xs=new int[count],ys=new int[count],zs=new int[count],states=new int[count];int n=0,dx=owner.adapter.destinationOrigin.getBlockX()-view.getOriginX(),dy=owner.adapter.destinationOrigin.getBlockY()-view.getOriginY(),dz=owner.adapter.destinationOrigin.getBlockZ()-view.getOriginZ();
            for(int y=0;y<view.getSizeY();y++)for(int z=0;z<view.getSizeZ();z++)for(int x=0;x<view.getSizeX();x++){int sx=view.getMinX()+x,sy=view.getMinY()+y,sz=view.getMinZ()+z,s=view.packedStateAt(sx,sy,sz);if(ignoreAir&&(s>>>4)==0)continue;xs[n]=sx+dx;ys[n]=sy+dy;zs[n]=sz+dz;states[n++]=s;}owner.plan=new Plan(xs,ys,zs,states);PasteHookStatus.pastePlannedBlocks.addAndGet(count);
        }catch(Throwable t){owner.planningFailure=t;}finally{PasteHookStatus.lastPastePlanMillis.set(ms(started));PasteHookStatus.pastePlanningActive.decrementAndGet();}}
    }
    private static PreparedClipboardView capture(BlockArrayClipboard clipboard){Vector min=clipboard.getMinimumPoint(),max=clipboard.getMaximumPoint(),origin=clipboard.getOrigin();int sx=max.getBlockX()-min.getBlockX()+1,sy=max.getBlockY()-min.getBlockY()+1,sz=max.getBlockZ()-min.getBlockZ()+1;int[] states=new int[sx*sy*sz];MutableBlockVector p=new MutableBlockVector();int n=0;java.util.Map<Integer,com.sk89q.jnbt.CompoundTag> tiles=new java.util.HashMap<Integer,com.sk89q.jnbt.CompoundTag>();for(int y=min.getBlockY();y<=max.getBlockY();y++)for(int z=min.getBlockZ();z<=max.getBlockZ();z++)for(int x=min.getBlockX();x<=max.getBlockX();x++){p.setComponents(x,y,z);BaseBlock b=clipboard.getBlock(p);states[n]=b.getId()<<4|b.getData();if(b.getNbtData()!=null)tiles.put(Integer.valueOf(n),b.getNbtData());n++;}return new PreparedClipboardView(min.getBlockX(),min.getBlockY(),min.getBlockZ(),sx,sy,sz,origin.getBlockX(),origin.getBlockY(),origin.getBlockZ(),states,tiles);}
    private static boolean reserve(long bytes){for(;;){long current=RETAINED.get();if(current+bytes>GLOBAL)return false;if(RETAINED.compareAndSet(current,current+bytes))return true;}}
    private static long ms(long start){return (System.nanoTime()-start)/1000000L;}
    private DeferredPasteManager(){}
}
