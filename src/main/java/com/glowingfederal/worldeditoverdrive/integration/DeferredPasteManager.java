package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MutableBlockVector;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.entity.ExtentEntityCopy;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.Location;
import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import java.lang.reflect.Method;
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

/** Owns standard semantic paste acceleration and compatibility traversal. */
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
    public static void tick(){Owner[] snapshot;synchronized(DeferredPasteManager.class){snapshot=OWNERS.toArray(new Owner[OWNERS.size()]);}for(Owner owner:snapshot){try{if(owner.tick())remove(owner,true,null);}catch(Throwable failure){remove(owner,false,failure);}}}
    private static void remove(Owner owner,boolean success,Throwable failure){synchronized(DeferredPasteManager.class){if(!OWNERS.remove(owner))return;}owner.release();if(owner.lifecycle.state()==PasteContinuationOperation.State.COMMITTING)PasteHookStatus.pasteCommitActive.decrementAndGet();PasteHookStatus.pasteDeferredActive.decrementAndGet();if(success)PasteHookStatus.pasteDeferredCompleted.incrementAndGet();else{PasteHookStatus.pasteDeferredFailed.incrementAndGet();PasteHookStatus.lastPasteDeferredReason="failed: "+failure;owner.player.printError("Paste failed: "+failure.getMessage());OverdriveLog.error("deferred paste failed: {}",failure.toString());}}

    private static final class EntityPlan {final Location location;final BaseEntity state;EntityPlan(Location location,BaseEntity state){this.location=location;this.state=state;}}
    private static final class Plan {final int[] x,y,z,sourceIndex;final PreparedClipboardView view;final List<EntityPlan> entities;final Vector minimum,maximum;
        Plan(int[] x,int[] y,int[] z,int[] sourceIndex,PreparedClipboardView view,List<EntityPlan> entities,Vector minimum,Vector maximum){this.x=x;this.y=y;this.z=z;this.sourceIndex=sourceIndex;this.view=view;this.entities=entities;this.minimum=minimum;this.maximum=maximum;}}
    private static final class Owner {
        final ForwardExtentCopy original;final PasteOperationAdapter adapter;final Player player;final LocalSession session;final ClipboardHolder holder;final boolean select;
        final PasteContinuationOperation lifecycle=new PasteContinuationOperation();volatile Plan plan;volatile Throwable planningFailure;boolean vanilla,mutation;int blockCursor,entityCursor;long reserved,commitNanos;
        Owner(ForwardExtentCopy original,PasteOperationAdapter adapter,Player player,LocalSession session,ClipboardHolder holder,boolean select)throws Exception{
            this.original=original;this.adapter=adapter;this.player=player;this.session=session;this.holder=holder;this.select=select;
            PasteOperationAdapter.Eligibility eligible=adapter.accelerationEligibility();if(eligible.kind!=PasteOperationAdapter.Eligibility.Kind.ACCELERATE){defer(eligible.reason);return;}
            long volume=adapter.region.getArea(),entities=adapter.clipboard.getEntities(adapter.region).size(),estimate=256L+volume*40L+entities*2048L;
            if(volume>Integer.MAX_VALUE||estimate>PER_OPERATION){defer("accelerated paste estimate exceeds operation memory limit: "+estimate);return;}if(!reserve(estimate)){defer("accelerated paste estimate exceeds available global memory: "+estimate);return;}reserved=estimate;
            long started=System.nanoTime();PreparedClipboardView prepared=capture(adapter);List<EntityPlan> entityPlans=captureEntities(adapter);PasteHookStatus.lastPastePrepareMillis.set(ms(started));PasteHookStatus.pastePreparedBlocks.addAndGet(prepared.getVolume());PasteHookStatus.pastePreparedTiles.addAndGet(prepared.getPayloadCount());PasteHookStatus.pastePreparedEntities.addAndGet(entityPlans.size());
            lifecycle.submitted();PasteHookStatus.pastePlanningActive.incrementAndGet();WORKERS.execute(new Planner(this,prepared,entityPlans,adapter.ignoreAir));PasteHookStatus.lastPasteDeferredReason="accelerated standard paste admitted";PasteHookStatus.lastPasteTransform=adapter.transform.getClass().getName();PasteHookStatus.lastPasteIgnoreAir=adapter.ignoreAir;
        }
        void defer(String reason){vanilla=true;PasteHookStatus.pasteAccelerationFallbacks.incrementAndGet();PasteHookStatus.lastPasteAccelerationFallbackReason=reason;PasteHookStatus.lastPasteDeferredReason="deferred vanilla: "+reason;}
        boolean tick()throws Exception{
            if(vanilla){Operations.completeLegacy(original);adapter.destination.flushQueue();finish(null);return true;}
            if(planningFailure!=null){if(!mutation){defer("worker planning failed before mutation: "+planningFailure);return false;}throw new Exception("accelerated planning failed",planningFailure);}
            Plan ready=plan;if(ready==null)return false;if(lifecycle.state()==PasteContinuationOperation.State.RUNNING){lifecycle.committing();PasteHookStatus.pasteCommitActive.incrementAndGet();}
            long tickStarted=System.nanoTime(),deadline=tickStarted+COMMIT_NANOS;MutableBlockVector position=new MutableBlockVector();int submitted=0,changed=0,tiles=0;
            while(blockCursor<ready.sourceIndex.length&&submitted<256&&System.nanoTime()<deadline){int source=ready.sourceIndex[blockCursor];position.setComponents(ready.x[blockCursor],ready.y[blockCursor],ready.z[blockCursor]);BaseBlock block=ready.view.blockAt(source);if(adapter.destination.setBlock(position,block))changed++;if(block.getNbtData()!=null)tiles++;mutation=true;blockCursor++;submitted++;}
            if(submitted!=0){PasteHookStatus.pasteSubmittedBlocks.addAndGet(submitted);adapter.destination.flushQueue();PasteHookStatus.pasteCommittedBlocks.addAndGet(changed);PasteHookStatus.pasteCommittedTiles.addAndGet(tiles);}
            if(blockCursor==ready.sourceIndex.length){int created=0;while(entityCursor<ready.entities.size()&&created<64&&System.nanoTime()<deadline){EntityPlan entity=ready.entities.get(entityCursor++);mutation=true;if(adapter.destination.createEntity(entity.location,entity.state)!=null){created++;PasteHookStatus.pasteCommittedEntities.incrementAndGet();}}}
            commitNanos+=System.nanoTime()-tickStarted;PasteHookStatus.lastPasteCommitMillis.set(commitNanos/1000000L);
            if(blockCursor<ready.sourceIndex.length||entityCursor<ready.entities.size())return false;
            adapter.destination.flushQueue();finish(ready);PasteHookStatus.pasteCommitActive.decrementAndGet();lifecycle.complete();PasteHookStatus.pasteAccelerated.incrementAndGet();return true;
        }
        void finish(Plan plan){session.remember(adapter.destination);Vector to=adapter.destinationOrigin;if(select){Vector min=plan==null?to:plan.minimum,max=plan==null?to.add(adapter.region.getMaximumPoint().subtract(adapter.region.getMinimumPoint())):plan.maximum;RegionSelector selector=new CuboidRegionSelector(player.getWorld(),min,max);session.setRegionSelector(player.getWorld(),selector);selector.learnChanges();selector.explainRegionAdjust(player,session);}player.print("The clipboard has been pasted at "+to);}
        void release(){if(reserved!=0){RETAINED.addAndGet(-reserved);reserved=0;}}
    }
    private static final class Planner implements Runnable {final Owner owner;final PreparedClipboardView view;final List<EntityPlan> entities;final boolean ignoreAir;Planner(Owner o,PreparedClipboardView v,List<EntityPlan> e,boolean i){owner=o;view=v;entities=e;ignoreAir=i;}
        public void run(){long started=System.nanoTime();try{owner.lifecycle.running();int count=0;for(int i=0;i<view.getVolume();i++)if(!ignoreAir||!view.isAirAt(i))count++;int[] xs=new int[count],ys=new int[count],zs=new int[count],source=new int[count];int n=0,minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;int tx=owner.adapter.destinationOrigin.getBlockX(),ty=owner.adapter.destinationOrigin.getBlockY(),tz=owner.adapter.destinationOrigin.getBlockZ();for(int i=0;i<view.getVolume();i++){int x=tx+view.relativeXAt(i),y=ty+view.relativeYAt(i),z=tz+view.relativeZAt(i);minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);if(ignoreAir&&view.isAirAt(i))continue;xs[n]=x;ys[n]=y;zs[n]=z;source[n++]=i;}Vector min=view.getVolume()==0?owner.adapter.destinationOrigin:new Vector(minX,minY,minZ),max=view.getVolume()==0?owner.adapter.destinationOrigin:new Vector(maxX,maxY,maxZ);owner.plan=new Plan(xs,ys,zs,source,view,entities,min,max);PasteHookStatus.pastePlannedBlocks.addAndGet(count);if(!owner.adapter.transform.isIdentity())PasteHookStatus.pasteTransformedBlocks.addAndGet(count);
        }catch(Throwable t){owner.planningFailure=t;}finally{PasteHookStatus.lastPastePlanMillis.set(ms(started));PasteHookStatus.pastePlanningActive.decrementAndGet();}}
    }
    private static PreparedClipboardView capture(PasteOperationAdapter adapter){BlockArrayClipboard clipboard=(BlockArrayClipboard)adapter.clipboard;Vector min=clipboard.getMinimumPoint(),max=clipboard.getMaximumPoint(),origin=clipboard.getOrigin();int sx=max.getBlockX()-min.getBlockX()+1,sy=max.getBlockY()-min.getBlockY()+1,sz=max.getBlockZ()-min.getBlockZ()+1,count=sx*sy*sz;int[] ids=new int[count],data=new int[count],rx=new int[count],ry=new int[count],rz=new int[count];Map<Integer,BaseBlock> payloads=new HashMap<Integer,BaseBlock>();MutableBlockVector p=new MutableBlockVector();int n=0;for(int y=min.getBlockY();y<=max.getBlockY();y++)for(int z=min.getBlockZ();z<=max.getBlockZ();z++)for(int x=min.getBlockX();x<=max.getBlockX();x++){p.setComponents(x,y,z);BaseBlock b=adapter.transformedSource.getBlock(p);ids[n]=b.getId();data[n]=b.getData();Vector transformed=adapter.transform.apply(p.subtract(adapter.sourceOrigin));rx[n]=transformed.getBlockX();ry[n]=transformed.getBlockY();rz[n]=transformed.getBlockZ();if(b.getNbtData()!=null||b.getClass()!=BaseBlock.class)payloads.put(Integer.valueOf(n),new BaseBlock(b));n++;}return new PreparedClipboardView(min.getBlockX(),min.getBlockY(),min.getBlockZ(),sx,sy,sz,origin.getBlockX(),origin.getBlockY(),origin.getBlockZ(),ids,data,rx,ry,rz,payloads);}
    private static List<EntityPlan> captureEntities(PasteOperationAdapter adapter)throws Exception{List<? extends Entity> source=adapter.clipboard.getEntities(adapter.region);List<EntityPlan> result=new ArrayList<EntityPlan>(source.size());ExtentEntityCopy transformer=new ExtentEntityCopy(adapter.sourceOrigin,adapter.destination,adapter.destinationOrigin,adapter.transform);Method transformState=ExtentEntityCopy.class.getDeclaredMethod("transformNbtData",BaseEntity.class);transformState.setAccessible(true);Vector pivot=adapter.sourceOrigin.round().add(0.5,0.5,0.5),target=adapter.destinationOrigin.round().add(0.5,0.5,0.5);for(Entity entity:source){BaseEntity state=entity.getState();if(state==null)continue;Location old=entity.getLocation();Vector position=adapter.transform.apply(old.toVector().subtract(pivot)).add(target),direction=old.getDirection();if(!adapter.transform.isIdentity()){direction=adapter.transform.apply(direction).subtract(adapter.transform.apply(Vector.ZERO)).normalize();state=(BaseEntity)transformState.invoke(transformer,state);}result.add(new EntityPlan(new Location(adapter.destination,position,direction),state));}return result;}
    private static boolean reserve(long bytes){for(;;){long current=RETAINED.get();if(current+bytes>GLOBAL)return false;if(RETAINED.compareAndSet(current,current+bytes))return true;}}
    private static long ms(long start){return(System.nanoTime()-start)/1000000L;}private DeferredPasteManager(){}
}
