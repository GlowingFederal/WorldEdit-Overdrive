package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.entity.ExtentEntityCopy;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.Location;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Owns deferred compatibility traversal and full standard PasteBuilder acceleration. */
public final class DeferredPasteManager {
    private static final long GLOBAL=128L<<20,PER_OPERATION=64L<<20,COMMIT_NANOS=5000000L;
    private static final Queue<Owner> OWNERS=new ArrayDeque<Owner>();
    private static final ExecutorService WORKERS=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),new ThreadFactory(){private final AtomicLong sequence=new AtomicLong();public Thread newThread(Runnable task){Thread thread=new Thread(task,"worldedit-overdrive-paste-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;}});
    private static final AtomicLong RETAINED=new AtomicLong();
    public static synchronized void register(ForwardExtentCopy operation,PasteOperationAdapter adapter,Player player,LocalSession session,boolean selectPasted)throws Exception{if(operation==null||adapter==null||player==null||session==null)throw new NullPointerException("deferred paste context");OWNERS.add(new Owner(operation,adapter,player,session,session.getClipboard(),selectPasted));PasteHookStatus.pasteDeferredActive.incrementAndGet();}
    public static void tick(){Owner[] snapshot;synchronized(DeferredPasteManager.class){snapshot=OWNERS.toArray(new Owner[OWNERS.size()]);}for(Owner owner:snapshot)try{if(owner.tick())remove(owner,true,null);}catch(Throwable failure){remove(owner,false,failure);}}
    private static void remove(Owner owner,boolean success,Throwable failure){synchronized(DeferredPasteManager.class){if(!OWNERS.remove(owner))return;}owner.release();if(owner.commitActive)PasteHookStatus.pasteCommitActive.decrementAndGet();PasteHookStatus.pasteDeferredActive.decrementAndGet();if(success)PasteHookStatus.pasteDeferredCompleted.incrementAndGet();else{PasteHookStatus.pasteDeferredFailed.incrementAndGet();PasteHookStatus.lastPasteDeferredReason="failed: "+failure;owner.player.printError("Paste failed: "+failure.getMessage());OverdriveLog.error("deferred paste failed: {}",failure.toString());}}

    private static final class Plan {final int[] indices;Plan(int[] indices){this.indices=indices;}}
    private static final class Owner {
        final ForwardExtentCopy original;final PasteOperationAdapter adapter;final Player player;final LocalSession session;final ClipboardHolder holder;final boolean select;final PasteContinuationOperation lifecycle=new PasteContinuationOperation();
        volatile Plan plan;volatile Throwable planningFailure;PreparedClipboardView prepared;boolean vanilla,mutation,commitActive,blocksFlushed;int cursor,entityCursor;long reserved,commitNanos;
        Owner(ForwardExtentCopy original,PasteOperationAdapter adapter,Player player,LocalSession session,ClipboardHolder holder,boolean select)throws Exception{
            this.original=original;this.adapter=adapter;this.player=player;this.session=session;this.holder=holder;this.select=select;
            PasteOperationAdapter.Eligibility eligible=adapter.accelerationEligibility();if(eligible.kind!=PasteOperationAdapter.Eligibility.Kind.ACCELERATE){defer(eligible.reason);return;}
            long volume=adapter.region.getArea(),preEstimate=128L+volume*32L+adapter.clipboard.getEntities(adapter.region).size()*4096L;if(volume>Integer.MAX_VALUE||preEstimate>PER_OPERATION||!reserve(preEstimate)){defer("accelerated paste memory admission rejected: "+preEstimate);return;}reserved=preEstimate;
            long started=System.nanoTime();prepared=capture(adapter);long actual=prepared.estimatedBytes()+volume*4L;if(actual>PER_OPERATION||!resizeReservation(actual)){release();defer("accelerated paste retained data exceeds memory limit: "+actual);return;}
            PasteHookStatus.lastPastePrepareMillis.set(ms(started));PasteHookStatus.pastePreparedBlocks.addAndGet(prepared.getVolume());PasteHookStatus.pastePreparedTiles.addAndGet(prepared.tileCount());PasteHookStatus.pastePreparedEntities.addAndGet(prepared.entities().size());
            PasteHookStatus.lastPasteTransform=adapter.transform.getClass().getName();PasteHookStatus.lastPasteIgnoreAir=adapter.ignoreAir;if(!adapter.transform.isIdentity())PasteHookStatus.pasteTransformedBlocks.addAndGet(prepared.getVolume());
            lifecycle.submitted();PasteHookStatus.pastePlanningActive.incrementAndGet();WORKERS.execute(new Planner(this,prepared,adapter.ignoreAir));PasteHookStatus.lastPasteDeferredReason="accelerated standard Enhanced paste admitted";
        }
        boolean resizeReservation(long wanted){if(wanted<=reserved){RETAINED.addAndGet(wanted-reserved);reserved=wanted;return true;}long extra=wanted-reserved;if(!reserve(extra))return false;reserved=wanted;return true;}
        void defer(String reason){vanilla=true;PasteHookStatus.pasteAccelerationFallbacks.incrementAndGet();PasteHookStatus.lastPasteAccelerationFallbackReason=reason;PasteHookStatus.lastPasteDeferredReason="deferred vanilla: "+reason;}
        boolean tick()throws Exception{
            if(vanilla){Operations.completeLegacy(original);adapter.destination.flushQueue();finish();return true;}
            if(planningFailure!=null){if(!mutation){defer("worker planning failed before mutation: "+planningFailure);return false;}throw new Exception("accelerated planning failed",planningFailure);}
            Plan ready=plan;if(ready==null)return false;if(!commitActive){lifecycle.committing();commitActive=true;PasteHookStatus.pasteCommitActive.incrementAndGet();}
            long tickStarted=System.nanoTime(),deadline=tickStarted+COMMIT_NANOS;int submitted=0,changed=0;
            while(cursor<ready.indices.length&&submitted<256&&System.nanoTime()<deadline){int i=ready.indices[cursor++];if(adapter.destination.setBlock(new Vector(prepared.destinationX(i),prepared.destinationY(i),prepared.destinationZ(i)),prepared.blockAt(i)))changed++;mutation=true;submitted++;}
            if(submitted!=0){PasteHookStatus.pasteSubmittedBlocks.addAndGet(submitted);adapter.destination.flushQueue();PasteHookStatus.pasteCommittedBlocks.addAndGet(changed);PasteHookStatus.pasteCommittedTiles.addAndGet(countTiles(ready.indices,cursor-submitted,cursor));}
            if(cursor==ready.indices.length&&!blocksFlushed){adapter.destination.flushQueue();blocksFlushed=true;}
            int entities=0;while(blocksFlushed&&entityCursor<prepared.entities().size()&&entities<64&&System.nanoTime()<deadline){PreparedClipboardView.EntitySnapshot entity=prepared.entities().get(entityCursor++);if(adapter.destination.createEntity(new Location(adapter.destination,entity.location.toVector(),entity.location.getYaw(),entity.location.getPitch()),new BaseEntity(entity.state))!=null)PasteHookStatus.pasteCommittedEntities.incrementAndGet();mutation=true;entities++;}
            commitNanos+=System.nanoTime()-tickStarted;PasteHookStatus.lastPasteCommitMillis.set(commitNanos/1000000L);if(cursor<ready.indices.length||entityCursor<prepared.entities().size())return false;
            adapter.destination.flushQueue();finish();commitActive=false;PasteHookStatus.pasteCommitActive.decrementAndGet();lifecycle.complete();PasteHookStatus.pasteAccelerated.incrementAndGet();return true;
        }
        int countTiles(int[] indices,int from,int to){int count=0;for(int n=from;n<to;n++)if(prepared.blockAt(indices[n]).getNbtData()!=null)count++;return count;}
        void finish(){session.remember(adapter.destination);Vector to=adapter.destinationOrigin;if(select){Vector max=to.add(adapter.region.getMaximumPoint().subtract(adapter.region.getMinimumPoint()));RegionSelector selector=new CuboidRegionSelector(player.getWorld(),to,max);session.setRegionSelector(player.getWorld(),selector);selector.learnChanges();selector.explainRegionAdjust(player,session);}player.print("The clipboard has been pasted at "+to);}
        void release(){if(reserved!=0){RETAINED.addAndGet(-reserved);reserved=0;}}
    }
    private static final class Planner implements Runnable {final Owner owner;final PreparedClipboardView view;final boolean ignoreAir;Planner(Owner owner,PreparedClipboardView view,boolean ignoreAir){this.owner=owner;this.view=view;this.ignoreAir=ignoreAir;}public void run(){long started=System.nanoTime();try{owner.lifecycle.running();int count=0;for(int i=0;i<view.getVolume();i++)if(!ignoreAir||view.idAt(i)!=0)count++;int[] indices=new int[count];for(int i=0,n=0;i<view.getVolume();i++)if(!ignoreAir||view.idAt(i)!=0)indices[n++]=i;owner.plan=new Plan(indices);PasteHookStatus.pastePlannedBlocks.addAndGet(count);}catch(Throwable t){owner.planningFailure=t;}finally{PasteHookStatus.lastPastePlanMillis.set(ms(started));PasteHookStatus.pastePlanningActive.decrementAndGet();}}}

    private static PreparedClipboardView capture(PasteOperationAdapter adapter)throws Exception{
        Vector min=adapter.clipboard.getMinimumPoint(),max=adapter.clipboard.getMaximumPoint();int sx=max.getBlockX()-min.getBlockX()+1,sy=max.getBlockY()-min.getBlockY()+1,sz=max.getBlockZ()-min.getBlockZ()+1,volume=sx*sy*sz;
        int[] ids=new int[volume],data=new int[volume],dx=new int[volume],dy=new int[volume],dz=new int[volume];Map<Integer,BaseBlock> auxiliary=new HashMap<Integer,BaseBlock>();int n=0;
        for(int y=min.getBlockY();y<=max.getBlockY();y++)for(int z=min.getBlockZ();z<=max.getBlockZ();z++)for(int x=min.getBlockX();x<=max.getBlockX();x++){Vector source=new Vector(x,y,z);BaseBlock block=adapter.transformedSource.getBlock(source);ids[n]=block.getId();data[n]=block.getData();if(block.getNbtData()!=null||block.getClass()!=BaseBlock.class)auxiliary.put(Integer.valueOf(n),new BaseBlock(block));Vector destination=adapter.transform.apply(source.subtract(adapter.sourceOrigin)).add(adapter.destinationOrigin);dx[n]=destination.getBlockX();dy[n]=destination.getBlockY();dz[n]=destination.getBlockZ();n++;}
        CaptureExtent capture=new CaptureExtent();ExtentEntityCopy entityCopy=new ExtentEntityCopy(adapter.sourceOrigin,capture,adapter.destinationOrigin,adapter.transform);for(Entity entity:adapter.clipboard.getEntities(adapter.region))entityCopy.apply(entity);
        return new PreparedClipboardView(min.getBlockX(),min.getBlockY(),min.getBlockZ(),sx,sy,sz,ids,data,dx,dy,dz,auxiliary,capture.snapshots);
    }
    private static final class CaptureExtent extends NullExtent {final List<PreparedClipboardView.EntitySnapshot> snapshots=new ArrayList<PreparedClipboardView.EntitySnapshot>();public Entity createEntity(Location location,BaseEntity state){PreparedClipboardView.EntitySnapshot snapshot=new PreparedClipboardView.EntitySnapshot(location,state);snapshots.add(snapshot);return new SnapshotEntity(snapshot,this);} }
    private static final class SnapshotEntity implements Entity {final PreparedClipboardView.EntitySnapshot snapshot;final Extent extent;SnapshotEntity(PreparedClipboardView.EntitySnapshot snapshot,Extent extent){this.snapshot=snapshot;this.extent=extent;}public BaseEntity getState(){return new BaseEntity(snapshot.state);}public Location getLocation(){return snapshot.location;}public Extent getExtent(){return extent;}public boolean remove(){return false;}public <T>T getFacet(Class<? extends T> type){return null;}}
    private static boolean reserve(long bytes){for(;;){long current=RETAINED.get();if(current+bytes>GLOBAL)return false;if(RETAINED.compareAndSet(current,current+bytes))return true;}}
    private static long ms(long start){return(System.nanoTime()-start)/1000000L;}
    private DeferredPasteManager(){}
}
