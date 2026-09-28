package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.jnbt.*;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.*;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.*;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.function.operation.*;
import com.sk89q.worldedit.history.UndoContext;
import com.sk89q.worldedit.history.change.*;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.registry.LegacyWorldData;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Exercises the production owner/storage, not a separate scheduling harness. */
public class StreamingPasteTest {
    private final List<DeferredPasteManager.Owner> owners=new ArrayList<DeferredPasteManager.Owner>();
    @Before public void hooks(){PasteHookStatus.historyCommandHookInstalled=true;PasteHookStatus.historySessionHookInstalled=true;EnhancedReorderYieldBridge.blockPlacerHookInstalled();EnhancedReorderYieldBridge.stage3HookInstalled();}
    @After public void cleanup()throws Exception{
        DeferredPasteManager.cancelAll();
        for(DeferredPasteManager.Owner owner:owners){owner.release(true);waitOwner(owner);if(owner.history!=null)owner.history.close();}
    }
    @Test public void enormousSourceIsAdmittedWithOnlyBoundedDescriptor()throws Exception{
        Fixture f=new Fixture(512,256,128);DeferredPasteManager.Owner owner=owner(f,true,new PasteMemoryBudget(128L<<20,64L<<20));
        assertEquals(512L<<20,32L*owner.volume);assertNull(owner.capture);assertNull(owner.prepared);assertNull(owner.storage.directory);
        assertEquals(16L<<10,owner.memory.live());assertEquals(1024,owner.payloads.length);assertEquals(0,owner.captureIndex);
    }
    @Test public void smallSourceUsesTheSameAdmission()throws Exception{Fixture f=new Fixture(2,1,2);DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(2L<<20,1L<<20));assertEquals(4,o.volume);assertEquals(16L<<10,o.memory.live());}
    @Test public void captureAllocatesOnlyOnePageAtATime()throws Exception{
        Fixture f=new Fixture(128,32,128);DeferredPasteManager.Owner o=owner(f,true,new PasteMemoryBudget(2L<<20,1L<<20));
        until(o,DeferredPasteManager.Owner.State.CAPTURING);assertEquals(0,o.pagesAllocated);o.tick(System.nanoTime()+10000000);
        assertTrue(o.pagesAllocated<=1);assertTrue(o.captureIndex>0&&o.captureIndex<o.volume);assertTrue(o.memory.live()<=o.memory.limit());
    }
    @Test public void pasteEightTimesTheWorkingBudgetCompletesAndReleasesPages()throws Exception{
        Fixture f=new Fixture(128,16,128);for(int x=0;x<128;x++)for(int y=0;y<16;y++)for(int z=0;z<128;z++)f.clipboard.setBlock(new Vector(x,y,z),new BaseBlock(1));
        PasteMemoryBudget budget=new PasteMemoryBudget(2L<<20,1L<<20);DeferredPasteManager.Owner o=owner(f,false,budget);run(o);
        assertTrue(32L*o.volume>budget.operationLimit*7);assertEquals(o.volume,f.blocks.size());assertTrue(o.memory.peak()<=budget.operationLimit);
        assertEquals(o.pagesAllocated,o.pagesReleased);assertTrue(o.backpressure>0);assertEquals(0,o.memory.bytes(PasteMemoryBudget.Kind.CAPTURE));assertEquals(0,o.memory.bytes(PasteMemoryBudget.Kind.COMMIT));
        assertEquals(32768,o.memory.bytes(PasteMemoryBudget.Kind.HISTORY));
    }
    @Test public void globalAndOperationLimitsYieldThenResume(){
        PasteMemoryBudget b=new PasteMemoryBudget(128,64);PasteMemoryBudget.Account a=b.account(),c=b.account(),d=b.account();
        PasteMemoryBudget.Ticket x=a.acquire(PasteMemoryBudget.Kind.CAPTURE,64),y=c.acquire(PasteMemoryBudget.Kind.COMMIT,64);assertNull(d.acquire(PasteMemoryBudget.Kind.PLANNING,1));
        assertNull(a.acquire(PasteMemoryBudget.Kind.CAPTURE,1));x.close();PasteMemoryBudget.Ticket z=d.acquire(PasteMemoryBudget.Kind.PLANNING,64);assertNotNull(z);assertEquals(128,b.live());y.close();z.close();assertEquals(0,b.live());
    }
    @Test public void busyGlobalMemoryDoesNotInvokeVanillaForSupportedGraph()throws Exception{
        Fixture f=new Fixture(2,1,2);PasteMemoryBudget.Account a=DeferredPasteManager.MEMORY.account(),b=DeferredPasteManager.MEMORY.account();
        PasteMemoryBudget.Ticket x=a.acquire(PasteMemoryBudget.Kind.CAPTURE,64L<<20),y=b.acquire(PasteMemoryBudget.Kind.CAPTURE,64L<<20);long fallbacks=PasteHookStatus.pasteFallbacks.get();
        try{assertEquals(PasteBridge.Decision.DEFERRED,PasteBridge.tryDefer(f.operation(false),f.player,f.session,false));assertEquals(fallbacks,PasteHookStatus.pasteFallbacks.get());}
        finally{x.close();y.close();}
    }
    @Test public void descriptorToleranceIsExplicitAndAccounted(){PasteMemoryBudget b=new PasteMemoryBudget(16L<<10,16L<<10);PasteMemoryBudget.Ticket full=b.account().acquire(PasteMemoryBudget.Kind.COMMIT,16L<<10),waiting=b.account().admitDescriptor();assertEquals(32L<<10,b.live());assertNull(b.account().acquire(PasteMemoryBudget.Kind.CAPTURE,1));full.close();waiting.close();assertEquals(0,b.live());}
    @Test public void metadataTileNbtAndTransformMatchNativePasteAcrossPages()throws Exception{
        Fixture f=new Fixture(40,2,32);CompoundTag tag=tag("boundary");
        for(int x=0;x<40;x++)for(int z=0;z<32;z++)f.clipboard.setBlock(new Vector(x,0,z),new BaseBlock(z%7==0?54:53,z&3,z%7==0?tag:null));
        f.holder.setTransform(new AffineTransform().rotateY(90));f.to=new Vector(64,32,64);
        Map<BlockVector,BaseBlock> expected=f.nativeResult(true);DeferredPasteManager.Owner o=owner(f,true,new PasteMemoryBudget(2L<<20,1L<<20));run(o);assertEquals(expected,f.blocks);assertTrue(o.pagesReleased>1);assertTrue(o.tiles>0);
    }
    @Test public void ignoreAirMatchesNativeBehavior()throws Exception{Fixture f=new Fixture(40,1,32);f.clipboard.setBlock(new Vector(30,0,30),new BaseBlock(5,2));Map<BlockVector,BaseBlock> expected=f.nativeResult(true);DeferredPasteManager.Owner o=owner(f,true,new PasteMemoryBudget(2L<<20,1L<<20));run(o);assertEquals(expected,f.blocks);assertEquals(1279,o.ignored);}
    @Test public void historyUndoAndRedoUsePacedProductionReplay()throws Exception{
        Fixture f=new Fixture(40,1,32);for(int x=0;x<40;x++)for(int z=0;z<32;z++)f.clipboard.setBlock(new Vector(x,0,z),new BaseBlock(54,z&3,tag("undo")));
        DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(2L<<20,1L<<20));run(o);Map<BlockVector,BaseBlock> pasted=new HashMap<BlockVector,BaseBlock>(f.blocks);
        HistoryReplayBridge.Replay undo=new HistoryReplayBridge.Replay(null,null,null,null,1,false,f.edit,f.newEdit());run(undo);assertTrue(f.blocks.isEmpty());
        HistoryReplayBridge.Replay redo=new HistoryReplayBridge.Replay(null,null,null,null,1,true,f.edit,f.newEdit());run(redo);assertEquals(pasted,f.blocks);
    }
    @Test public void cancellationReleasesCaptureAndWorkerReservations()throws Exception{Fixture f=new Fixture(128,32,128);DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(2L<<20,1L<<20));until(o,DeferredPasteManager.Owner.State.CAPTURING);o.tick(System.nanoTime()+10000000);o.release(true);waitOwner(o);assertEquals(0,o.memory.live());assertNull(o.prepared);assertNull(o.capture);}
    @Test public void invalidNbtErrorDoesNotLeakPages()throws Exception{Fixture f=new Fixture(1,1,1);Map<String,Tag> malformed=new HashMap<String,Tag>();malformed.put("bad",null);f.clipboard.setBlock(Vector.ZERO,new BaseBlock(54,0,new CompoundTag(malformed)));DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(2L<<20,1L<<20));until(o,DeferredPasteManager.Owner.State.CAPTURING);try{o.tick(System.nanoTime()+10000000);fail();}catch(IllegalArgumentException expected){}o.release(true);waitOwner(o);assertEquals(0,o.memory.live());}
    @Test public void diskQueueReadYieldsWithoutConsumingCursor()throws Exception{
        PasteMemoryBudget b=new PasteMemoryBudget(1L<<20,1L<<20);PasteMemoryBudget.Account a=b.account();PasteStreamStorage s=new PasteStreamStorage(a);s.initialize();
        try{s.stage1.appendDisk(PasteDiskJournal.Record.block(Vector.ZERO,null,new BaseBlock(1),512));PasteMemoryBudget.Ticket full=a.acquire(PasteMemoryBudget.Kind.WORKER,1L<<20);PasteStreamStorage.Batch page=s.readBatch(1);assertTrue(page.pressure);assertEquals(0,s.stage1Cursor);page.close();full.close();page=s.readBatch(1);assertEquals(1,page.records.size());assertTrue(page.done);page.close();assertEquals(0,b.live());}finally{s.close();}
    }
    @Test public void stageThreeWalkSpansBatchesWithoutHeapSizedChain()throws Exception{
        PasteMemoryBudget b=new PasteMemoryBudget(2L<<20,1L<<20);PasteStreamStorage s=new PasteStreamStorage(b.account());s.initialize();
        try{
            int data=0;for(int d:new int[]{1,2,4,8})if(com.sk89q.worldedit.blocks.BlockType.getAttachment(106,d).vector().getBlockX()==-1)data=d;assertTrue(data!=0);
            for(int x=2499;x>=0;x--){PasteDiskJournal.Record r=PasteDiskJournal.Record.block(new Vector(x,20,0),null,new BaseBlock(106,data),512);long offset=s.stage3.appendDisk(r);s.index.put(x,20,0,offset);}
            long count=0;int batches=0;boolean done=false;while(!done){PasteStreamStorage.Batch page=s.readBatch(3);assertTrue(page.records.size()<=1024);for(PasteDiskJournal.Record r:page.records)assertEquals(count++,r.x);done=page.done;page.close();batches++;assertTrue(batches<20);assertTrue(b.live()<=b.operationLimit);}
            assertEquals(2500,count);assertTrue(batches>=3);
        }finally{s.close();}assertEquals(0,b.live());
    }
    @Test public void stageThreeDuplicateUsesLastBlockAndDependencyOrder()throws Exception{
        PasteStreamStorage s=new PasteStreamStorage(new PasteMemoryBudget(2L<<20,1L<<20).account());s.initialize();
        try{for(BaseBlock block:new BaseBlock[]{new BaseBlock(64,0),new BaseBlock(64,8),new BaseBlock(64,9)}){Vector p=new Vector(0,block.getData()==0?1:2,0);long offset=s.stage3.appendDisk(PasteDiskJournal.Record.block(p,null,block,512));s.index.put(p.getBlockX(),p.getBlockY(),p.getBlockZ(),offset);}PasteStreamStorage.Batch page=s.readBatch(3);assertEquals(2,page.records.size());assertEquals(2,page.records.get(0).y);assertEquals(9,page.records.get(0).block.getData());assertEquals(1,page.records.get(1).y);page.close();}finally{s.close();}
    }
    @Test public void entityStateAndIdentitySurviveDiskUndoAndRedo()throws Exception{
        PasteStreamStorage s=new PasteStreamStorage(new PasteMemoryBudget(2L<<20,1L<<20).account());s.initialize();final Map<Integer,TestEntity> live=new HashMap<Integer,TestEntity>();
        PasteDiskHistory.EntityIdentity identity=new PasteDiskHistory.EntityIdentity(){public void capture(PasteDiskJournal.Record r,Entity e){TestEntity t=(TestEntity)e;live.put(t.id,t);r.entityId=t.id;r.uuid=t.uuid;}public void remove(PasteDiskJournal.Record r){TestEntity t=live.remove(r.entityId);if(t!=null&&t.uuid.equals(r.uuid))t.remove();}};
        final Extent extent=new NullExtent(){public Entity createEntity(Location p,BaseEntity state){return new TestEntity(p,state);}};
        PasteDiskHistory history=new PasteDiskHistory(s,identity);TestEntity entity=new TestEntity(new Location(extent,1.5,20,2.5,90,10),new BaseEntity("Pig",tag("entity")));
        assertTrue(s.beginSubmission(0,2048));history.add(new EntityCreate(entity.location,entity.state,entity));s.endSubmission();s.flush();history.seal();
        UndoContext context=new UndoContext();context.setExtent(extent);history.backwardIterator().next().undo(context);assertTrue(live.isEmpty());history.forwardIterator().next().redo(context);assertEquals(1,live.size());assertEquals("entity",live.values().iterator().next().state.getNbtData().getString("Name"));history.backwardIterator().next().undo(context);assertTrue(live.isEmpty());history.detach();s.close();
    }
    @Test public void genuineIndivisibleResourceLimitStillFails(){PasteMemoryBudget b=new PasteMemoryBudget(1024,512);try{b.account().acquire(PasteMemoryBudget.Kind.CAPTURE,513);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("indivisible"));}}

    @Test public void loadedChunkCrossingsDoNotThrottleAWholeSubmissionPage()throws Exception{
        Fixture f=new Fixture(256,1,4);for(int x=0;x<256;x++)for(int z=0;z<4;z++)f.clipboard.setBlock(new Vector(x,0,z),new BaseBlock(1));
        DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(4L<<20,2L<<20));
        until(o,DeferredPasteManager.Owner.State.PLANNING);while(o.busy)Thread.sleep(1);
        o.indices=(int[])o.workerResult;o.workerResult=null;o.state=DeferredPasteManager.Owner.State.SUBMITTING;
        assertFalse(o.needsChunkLoad(Vector.ZERO));o.submit(System.nanoTime()+1000000000L);
        assertEquals(1024,o.submitted);assertEquals(1024,o.pageOffset);assertEquals(0,PasteHookStatus.chunksSinceLastDrain.get());
    }
    @Test public void doorPairCrossesBatchBoundaryAndCompletesTogether()throws Exception{
        final List<Integer> heights=new ArrayList<Integer>();
        Extent sink=new NullExtent(){public boolean setBlock(Vector p,BaseBlock b){heights.add(p.getBlockY());return true;}};
        PasteCommitCursor cursor=new PasteCommitCursor();PasteStreamStorage.Batch first=new PasteStreamStorage.Batch();
        PasteDiskJournal.Record upper=PasteDiskJournal.Record.block(new Vector(0,1,0),null,new BaseBlock(64,8),512);upper.pairWithNext=true;first.records.add(upper);
        cursor.accept(first);cursor.apply(sink,Long.MAX_VALUE);assertTrue(heights.isEmpty());assertFalse(cursor.finishBatch());
        PasteStreamStorage.Batch second=new PasteStreamStorage.Batch();second.done=true;second.records.add(PasteDiskJournal.Record.block(Vector.ZERO,null,new BaseBlock(64,0),512));
        cursor.accept(second);cursor.apply(sink,0);assertTrue(heights.isEmpty());assertFalse(cursor.consumed());
        cursor.apply(sink,Long.MAX_VALUE);assertEquals(Arrays.asList(1,0),heights);assertTrue(cursor.finishBatch());cursor.close();
    }
    @Test public void failedMutationReleasesProfilingScopeAndRecord()throws Exception{
        PasteCommitCursor cursor=new PasteCommitCursor();PasteStreamStorage.Batch batch=new PasteStreamStorage.Batch();
        batch.records.add(PasteDiskJournal.Record.block(Vector.ZERO,null,new BaseBlock(1),512));cursor.accept(batch);
        try{cursor.apply(new NullExtent(){public boolean setBlock(Vector p,BaseBlock b){throw new IllegalStateException("callback failure");}},Long.MAX_VALUE);fail();}
        catch(IllegalStateException expected){assertEquals("callback failure",expected.getMessage());}
        finally{cursor.close();}
        assertFalse(PlacementMutationBridge.active());
    }

    @Test public void productionSchedulerResumesPasteMoreThanOncePerTick()throws Exception{
        Fixture f=new Fixture(64,1,1);for(int x=0;x<64;x++)f.clipboard.setBlock(new Vector(x,0,0),new BaseBlock(1));
        DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(4L<<20,2L<<20));until(o,DeferredPasteManager.Owner.State.COMMITTING);
        waitWorker(o);assertTrue(o.workerResult instanceof PasteStreamStorage.Batch);o.commitCursor.accept((PasteStreamStorage.Batch)o.workerResult);o.workerResult=null;assertEquals(0,o.committed);
        for(int i=0;i<100;i++)DeferredPasteManager.tick(10000000L);
        f.mutationDelay=1500000L;enqueue(DeferredPasteManager.class,"OWNERS",o);PasteHookStatus.pasteDeferredActive.incrementAndGet();
        DeferredPasteManager.tick(10000000L);
        String detail="committed="+o.committed+" "+Arrays.toString(o.pacing.describe());assertTrue(detail,o.committed>=10);assertTrue(detail,PastePacingDiagnostics.resumesThisTick>1);assertTrue(detail,o.reorderBudget.targetNanos()>=PasteSliceBudget.MIN_NANOS);
    }
    @Test public void productionSchedulerResumesPreparationMoreThanOncePerTick()throws Exception{
        Fixture f=new Fixture(1024,1,1);for(int x=0;x<1024;x++)f.clipboard.setBlock(new Vector(x,0,0),new BaseBlock(1));
        DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(4L<<20,2L<<20));until(o,DeferredPasteManager.Owner.State.PLANNING);waitWorker(o);
        for(int i=0;i<100;i++)DeferredPasteManager.tick(10000000L);
        f.readDelay=250000L;enqueue(DeferredPasteManager.class,"OWNERS",o);PasteHookStatus.pasteDeferredActive.incrementAndGet();
        DeferredPasteManager.tick(10000000L);
        assertTrue("submission did not reuse its tick",o.submitted>15);assertTrue(PastePacingDiagnostics.resumesThisTick>1);assertTrue(o.submissionBudget.targetNanos()>=PasteSliceBudget.MIN_NANOS);
    }
    @Test public void productionSchedulerResumesUndoAndRedoInTheSameTick()throws Exception{
        Fixture f=new Fixture(64,1,1);for(int x=0;x<64;x++)f.clipboard.setBlock(new Vector(x,0,0),new BaseBlock(1));
        DeferredPasteManager.Owner o=owner(f,false,new PasteMemoryBudget(4L<<20,2L<<20));run(o);
        for(boolean redo:new boolean[]{false,true}){
            HistoryReplayBridge.Replay r=new HistoryReplayBridge.Replay(null,null,null,null,1,redo,f.edit,f.newEdit());
            try{
                long end=System.nanoTime()+5000000000L;
                while(r.state!=HistoryReplayBridge.Replay.State.COMMIT){r.tick(System.nanoTime()+10000000L);if(r.busy)Thread.sleep(1);assertTrue(System.nanoTime()<end);}
                while(r.busy)Thread.sleep(1);r.tick(System.nanoTime()+10000000L);while(r.busy)Thread.sleep(1);
                r.commitCursor.accept((PasteStreamStorage.Batch)r.result);r.result=null;
                for(int i=0;i<100;i++)DeferredPasteManager.tick(10000000L);
                f.mutationDelay=1500000L;enqueue(HistoryReplayBridge.class,"REPLAYS",r);DeferredPasteManager.tick(10000000L);
                String detail="changed="+r.commitCursor.changed+" "+Arrays.toString(r.pacing.describe());assertTrue(detail,PastePacingDiagnostics.resumesThisTick>1);assertTrue(detail,r.commitCursor.changed>=10);assertTrue(detail,r.commitBudget.targetNanos()>=PasteSliceBudget.MIN_NANOS);
            }finally{f.mutationDelay=0;HistoryReplayBridge.cancelAll();r.release();long end=System.nanoTime()+5000000000L;while(r.memory.live()!=0&&System.nanoTime()<end)Thread.sleep(1);assertEquals(0,r.memory.live());}
        }
    }
    @SuppressWarnings("unchecked") private static <T> void enqueue(Class<?> owner,String field,T value)throws Exception{Field f=owner.getDeclaredField(field);f.setAccessible(true);((Queue<T>)f.get(null)).add(value);}
    private static void waitWorker(DeferredPasteManager.Owner o)throws Exception{long end=System.nanoTime()+5000000000L;while(o.busy){Thread.sleep(1);assertTrue(System.nanoTime()<end);}}

    private DeferredPasteManager.Owner owner(Fixture f,boolean ignore,PasteMemoryBudget budget)throws Exception{PasteOperationAdapter.Result result=PasteOperationAdapter.recognize(f.operation(ignore));assertTrue(result.reason,result.isRecognized());DeferredPasteManager.Owner o=new DeferredPasteManager.Owner(result.adapter,f.player,f.session,false,budget);owners.add(o);return o;}
    private static void run(DeferredPasteManager.Owner o)throws Exception{long end=System.nanoTime()+120000000000L;while(!o.tick(System.nanoTime()+10000000)){assertTrue("paste stalled at "+o.state+" "+o.pressure,System.nanoTime()<end);if(o.busy)Thread.sleep(1);}o.release(false);waitOwner(o);}
    private static void run(HistoryReplayBridge.Replay r)throws Exception{long end=System.nanoTime()+30000000000L;while(!r.tick(System.nanoTime()+5000000)){assertTrue("replay stalled at "+r.state,System.nanoTime()<end);if(r.busy)Thread.sleep(1);}r.release();while(r.memory.live()!=0&&System.nanoTime()<end)Thread.sleep(1);assertEquals(0,r.memory.live());}
    private static void until(DeferredPasteManager.Owner o,DeferredPasteManager.Owner.State state)throws Exception{long end=System.nanoTime()+5000000000L;while(o.state!=state){o.tick(System.nanoTime()+10000000);assertTrue("waiting for "+state+" at "+o.state+" pressure="+o.pressure+" memory="+o.memory.live(),System.nanoTime()<end);if(o.busy)Thread.sleep(1);}}
    private static void waitOwner(DeferredPasteManager.Owner o)throws Exception{long end=System.nanoTime()+5000000000L;while(!o.cleaned&&System.nanoTime()<end)Thread.sleep(1);synchronized(o){assertTrue(o.cleaned);}}
    private static CompoundTag tag(String name){Map<String,Tag> tags=new HashMap<String,Tag>();tags.put("Name",new StringTag(name));tags.put("items",new IntArrayTag(new int[]{1,3,5}));return new CompoundTag(tags);}
    private static final class TestEntity implements Entity {
        static int next;final int id=++next;final UUID uuid=new UUID(0,id);final Location location;final BaseEntity state;
        TestEntity(Location p,BaseEntity state){location=p;this.state=new BaseEntity(state);}public BaseEntity getState(){return new BaseEntity(state);}public Location getLocation(){return location;}public Extent getExtent(){return location.getExtent();}public boolean remove(){return true;}public <T>T getFacet(Class<? extends T> type){return null;}
    }
    private static final class Fixture implements InvocationHandler {
        final Map<BlockVector,BaseBlock> blocks=new HashMap<BlockVector,BaseBlock>();final long serverThread=Thread.currentThread().getId();final World world;final Player player;final LocalSession session=new LocalSession();
        final BlockArrayClipboard clipboard;final ClipboardHolder holder;final EditSession edit;Vector to=Vector.ZERO;long mutationDelay,readDelay;
        Fixture(int x,int y,int z)throws Exception{world=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class[]{World.class},this);player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},this);clipboard=new BlockArrayClipboard(new CuboidRegion(Vector.ZERO,new Vector(x-1,y-1,z-1)));holder=new ClipboardHolder(clipboard,LegacyWorldData.getInstance());session.setClipboard(holder);edit=newEdit();}
        EditSession newEdit()throws Exception{Constructor<EditSession> ctor=EditSession.class.getDeclaredConstructor(EventBus.class,World.class,Integer.TYPE,BlockBag.class,EditSessionEvent.class);ctor.setAccessible(true);EditSession result=ctor.newInstance(new EventBus(),world,-1,null,new EditSessionEvent(world,null,-1,null));result.enableQueue();return result;}
        ForwardExtentCopy operation(boolean ignore){return (ForwardExtentCopy)holder.createPaste(edit,LegacyWorldData.getInstance()).to(to).ignoreAirBlocks(ignore).build();}
        Map<BlockVector,BaseBlock> nativeResult(boolean ignore)throws Exception{EditSession nativeEdit=newEdit();Operations.completeLegacy(holder.createPaste(nativeEdit,LegacyWorldData.getInstance()).to(to).ignoreAirBlocks(ignore).build());nativeEdit.flushQueue();Map<BlockVector,BaseBlock> expected=new HashMap<BlockVector,BaseBlock>(blocks);blocks.clear();return expected;}
        public Object invoke(Object proxy,Method method,Object[] args){
            assertEquals("world/player call escaped the server thread",serverThread,Thread.currentThread().getId());String name=method.getName();
            if(name.equals("getBlock")||name.equals("getLazyBlock")){long end=System.nanoTime()+readDelay;while(System.nanoTime()<end){}BaseBlock b=blocks.get(((Vector)args[0]).toBlockVector());return b==null?new BaseBlock(0):new BaseBlock(b);}
            if(name.equals("setBlock")){long end=System.nanoTime()+mutationDelay;while(System.nanoTime()<end){}BlockVector p=((Vector)args[0]).toBlockVector();BaseBlock b=(BaseBlock)args[1];if(b.getId()==0)blocks.remove(p);else blocks.put(p,new BaseBlock(b));return true;}
            if(name.equals("getWorld"))return world;if(name.equals("getWorldData"))return LegacyWorldData.getInstance();if(name.equals("getMaxY"))return 255;if(name.equals("getName"))return "test";
            if(name.equals("getUniqueId"))return new UUID(0,1);if(name.equals("getEntities"))return Collections.emptyList();if(name.equals("getMinimumPoint"))return new Vector(-30000000,0,-30000000);if(name.equals("getMaximumPoint"))return new Vector(30000000,255,30000000);
            if(name.equals("equals"))return proxy==args[0];if(name.equals("hashCode"))return System.identityHashCode(proxy);if(name.equals("toString"))return "streaming test world";
            Class<?> type=method.getReturnType();if(type==Boolean.TYPE)return true;if(type==Integer.TYPE)return 0;if(type==Long.TYPE)return 0L;if(type==Float.TYPE)return 0F;if(type==Double.TYPE)return 0D;return null;
        }
    }
}
