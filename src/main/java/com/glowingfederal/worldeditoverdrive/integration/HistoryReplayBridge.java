package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.*;
import com.sk89q.worldedit.history.UndoContext;
import com.sk89q.worldedit.history.change.*;
import com.sk89q.worldedit.history.changeset.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Callable;

/** Owns streamed-history undo/redo before Enhanced's completeBlindly loop.
 * The history pointer advances only after an entry finishes; all world calls
 * remain on the server. A request also waits for earlier pastes in that session. */
public final class HistoryReplayBridge {
    private static final Queue<Replay> REPLAYS=new ArrayDeque<Replay>();
    public static boolean tryCommand(WorldEdit worldEdit,Player player,LocalSession caller,CommandContext args,boolean redo)throws WorldEditException {
        LocalSession session=caller;
        if(args.argsLength()>=2){player.checkPermission(redo?"worldedit.history.redo.other":"worldedit.history.undo.other");session=worldEdit.getSession(args.getString(1));if(session==null)return false;}
        int times=Math.max(1,args.getInteger(0,1));
        try {
            List<EditSession> history=history(session);int pointer=pointer(session);boolean streaming=DeferredPasteManager.hasWork(session)||hasWork(session);
            for(int i=0;i<times&&i<history.size();i++){int at=redo?pointer+i:pointer-i-1;if(at<0||at>=history.size())break;streaming|=history.get(at).getChangeSet() instanceof PasteDiskHistory;}
            if(!streaming)return false;
            synchronized(HistoryReplayBridge.class){REPLAYS.add(new Replay(worldEdit,player,caller,session,times,redo,null,null));}
            return true;
        }catch(Exception e){player.printError("Unable to schedule history replay: "+e.getMessage());return true;}
    }
    /** Also covers direct EditSession.undo/redo API calls on our ChangeSet. */
    public static boolean trySessions(EditSession source,EditSession target,boolean redo){
        if(!(source.getChangeSet() instanceof PasteDiskHistory))return false;
        synchronized(HistoryReplayBridge.class){REPLAYS.add(new Replay(null,null,null,null,1,redo,source,target));}return true;
    }
    private static synchronized boolean hasWork(LocalSession session){for(Replay r:REPLAYS)if(r.session==session)return true;return false;}
    static synchronized boolean hasEarlierWork(LocalSession session,long order){for(Replay r:REPLAYS)if(r.session==session&&r.order<order)return true;return false;}
    static void tick(long deadline){
        Replay[] snapshot;synchronized(HistoryReplayBridge.class){snapshot=REPLAYS.toArray(new Replay[REPLAYS.size()]);}
        for(Replay r:snapshot){if(System.nanoTime()>=deadline)break;try{if(r.tick(deadline))remove(r,null);}catch(Throwable e){remove(r,e);}}
    }
    private static synchronized void remove(Replay r,Throwable failure){
        if(!REPLAYS.remove(r))return;
        if(failure!=null){if(r.player!=null)r.player.printError("History replay failed: "+failure.getMessage());OverdriveLog.error("history replay failed: {}",failure.toString());}
        r.release();
    }
    static synchronized void cancelAll(){for(Replay r:REPLAYS)r.release();REPLAYS.clear();}
    @SuppressWarnings("unchecked") private static List<EditSession> history(LocalSession s)throws Exception{return (List<EditSession>)field(LocalSession.class,"history").get(s);}
    private static int pointer(LocalSession s)throws Exception{return field(LocalSession.class,"historyPointer").getInt(s);}
    private static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}

    static final class Replay {
        enum State{START,INSTALL,READ,APPLY,FLUSH,COMMIT,DISPOSE,NEXT,DONE}
        final WorldEdit worldEdit;final Player player;final LocalSession caller,session;final boolean redo;
        final long order=DeferredPasteManager.SEQUENCE.incrementAndGet();final PasteMemoryBudget.Account memory=DeferredPasteManager.MEMORY.account();
        final PasteSliceBudget submissionBudget=new PasteSliceBudget(),commitBudget=new PasteSliceBudget();
        final PasteNbtSizer sizer=new PasteNbtSizer();
        PasteMemoryBudget.Ticket descriptor=memory.admitDescriptor(),stateMemory;
        int remaining,selectedPointer,at,commitStage=1;long processed,total,sourceCursor,entityCursor,pendingBytes;
        boolean sourceEntities=true,sizing,downstreamStarted;
        State state=State.START;EditSession original,target;PasteStreamStorage storage;PasteStreamingExtent reorder;Extent destination;
        PasteDiskHistory disk;Iterator<Change> legacy;
        List<ReplayRecord> sourceBatch;final PasteCommitCursor commitCursor=new PasteCommitCursor();
        Operation downstream;volatile boolean busy,cancelled;volatile Object result;volatile Throwable failure;
        Replay(WorldEdit worldEdit,Player player,LocalSession caller,LocalSession session,int count,boolean redo,EditSession original,EditSession target){this.worldEdit=worldEdit;this.player=player;this.caller=caller;this.session=session;remaining=count;this.redo=redo;this.original=original;this.target=target;}
        void job(final Callable<?> action){
            if(busy)throw new IllegalStateException("overlapping history worker");busy=true;result=null;
            DeferredPasteManager.WORKERS.execute(new Runnable(){public void run(){try{if(!cancelled)result=action.call();}catch(Throwable e){failure=e;}finally{if(cancelled)cleanup();busy=false;}}});
        }
        boolean tick(long deadline)throws Exception{
            long started=System.nanoTime();State phase=state;PasteSliceBudget pace=state==State.COMMIT?commitBudget:submissionBudget;
            deadline=Math.min(deadline,started+pace.targetNanos());
            try{
                if(failure!=null)throw new Exception("history worker failed",failure);if(busy)return false;
                if(state==State.START){
                    if(session!=null&&(DeferredPasteManager.hasEarlierWork(session,order)||!DeferredPasteManager.acquireSession(session,this)))return false;
                    if(original==null){List<EditSession> h=history(session);int p=pointer(session);selectedPointer=redo?p:p-1;if(selectedPointer<0||selectedPointer>=h.size()){if(player!=null)player.printError(redo?"Nothing left to redo.":"Nothing left to undo.");state=State.DONE;return true;}original=h.get(selectedPointer);}
                    disk=original.getChangeSet() instanceof PasteDiskHistory?(PasteDiskHistory)original.getChangeSet():null;
                    if(disk!=null&&!disk.ready())return false;
                    if(stateMemory==null){stateMemory=memory.acquire(PasteMemoryBudget.Kind.STATE,PasteStreamStorage.STATE_BYTES);if(stateMemory==null)return false;}
                    if(target==null){target=WorldEdit.getInstance().getEditSessionFactory().getEditSession(original.getWorld(),-1,caller.getBlockBag(player),player);target.enableQueue();}
                    // Normal mode performs lighting during the bounded placements,
                    // avoiding Enhanced's whole-dirty-chunk fast-mode finalizer.
                    target.setFastMode(false);
                    storage=new PasteStreamStorage(memory);job(new Callable<Object>(){public Object call()throws Exception{storage.initialize();return null;}});state=State.INSTALL;return false;
                }
                if(state==State.INSTALL){
                    reorder=PasteExtentInstaller.install(target,storage,false);destination=PasteExtentInstaller.bypassHistory(target);
                    if(disk==null){ChangeSet changes=original.getChangeSet();if(!(changes instanceof BlockOptimizedHistory))throw new IllegalArgumentException("unsupported legacy history in streamed request");legacy=redo?changes.forwardIterator():changes.backwardIterator();}
                    total+=original.getChangeSet().size();sourceCursor=redo?0:disk==null?0:disk.blocks.count-1;entityCursor=redo?0:disk==null?0:disk.entities.count-1;sourceEntities=true;state=State.READ;
                }
                if(state==State.READ){if(result!=null){sourceBatch=castRecords(result);result=null;at=0;state=State.APPLY;}else{job(new Callable<Object>(){public Object call()throws Exception{return read();}});return false;}}
                if(state==State.APPLY){
                    int entities=0;
                    while(at<sourceBatch.size()&&System.nanoTime()<deadline){
                        ReplayRecord entry=sourceBatch.get(at);PasteDiskJournal.Record r=entry.record;
                        if(entry.change!=null){UndoContext context=new UndoContext();context.setExtent(destination);if(redo)entry.change.redo(context);else entry.change.undo(context);entities++;}
                        else if(r.entity!=null){disk.applyEntity(r,entry.index,redo,destination);entities++;}
                        else{
                            BaseBlock block=redo?r.block:r.before;
                            if(!sizing){sizer.start(block.getNbtData());pendingBytes=0;sizing=true;}
                            pendingBytes+=sizer.resume(deadline,4096);if(!sizer.complete())return false;
                            if(!storage.beginSubmission(0,pendingBytes)){flush(false);return false;}
                            try{destination.setBlock(r.position(),block);}finally{storage.endSubmission();}sizing=false;
                        }
                        if(entry.record!=null&&entry.record.entity==null)entry.close();
                        at++;processed++;if(entities>=16)break;
                    }
                    if(at<sourceBatch.size())return false;flush(true);return false;
                }
                if(state==State.FLUSH){state=sourceBatch==null?State.READ:State.APPLY;result=null;return false;}
                if(state==State.COMMIT){
                    if(commitStage<=3){
                        if(commitCursor.batch==null){if(result!=null){commitCursor.accept((PasteStreamStorage.Batch)result);result=null;}else{final int stage=commitStage;job(new Callable<Object>(){public Object call()throws Exception{return storage.readBatch(stage);}});return false;}}
                        commitCursor.apply(reorder.getExtent(),deadline);if(!commitCursor.consumed())return false;boolean done=commitCursor.finishBatch();if(done){commitStage++;commitBudget.resetStage();}return false;
                    }
                    if(!downstreamStarted){downstream=target.commit();downstreamStarted=true;}
                    while(downstream!=null&&System.nanoTime()<deadline){EnhancedReorderYieldBridge.beginSlice(deadline);try{downstream=downstream.resume(new RunContext());}finally{EnhancedReorderYieldBridge.endSlice();}}
                    if(downstream!=null)return false;job(new Callable<Object>(){public Object call()throws Exception{storage.close();return null;}});state=State.NEXT;return false;
                }
                if(state==State.NEXT){
                    PasteExtentInstaller.restore(target,reorder);
                    if(session!=null){field(LocalSession.class,"historyPointer").setInt(session,redo?selectedPointer+1:selectedPointer);player.print(redo?"Redo successful.":"Undo successful.");worldEdit.flushBlockBag(player,original);}
                    remaining--;original=null;target=null;storage=null;disk=null;legacy=null;commitStage=1;downstream=null;downstreamStarted=false;
                    state=remaining==0?State.DONE:State.START;return state==State.DONE;
                }
                return state==State.DONE;
            }finally{
                pace.observe(System.nanoTime()-started,Math.max(0,deadline-started),state==phase);
                PasteHookStatus.historyReplayPhase=(redo?"REDO_":"UNDO_")+state.name();PasteHookStatus.historyReplayProcessed.set(processed);PasteHookStatus.historyReplayTotal.set(total);
                PasteHookStatus.historyReplayLiveMemoryBytes.set(memory.live());PasteHookStatus.historyReplayPeakLiveMemoryBytes.set(memory.peak());
            }
        }
        @SuppressWarnings("unchecked") private List<ReplayRecord> castRecords(Object value){return (List<ReplayRecord>)value;}
        List<ReplayRecord> read()throws Exception{
            List<ReplayRecord> batch=new ArrayList<ReplayRecord>();long bytes=0;
            try{
                while(batch.size()<PreparedClipboardView.PAGE_SIZE&&bytes<(1L<<20)&&!cancelled){
                    ReplayRecord entry=new ReplayRecord();long needed;
                    if(disk!=null){
                        if(sourceEntities&&(redo?entityCursor>=disk.entities.count:entityCursor<0))sourceEntities=false;
                        PasteDiskJournal journal=sourceEntities?disk.entities:disk.blocks;long cursor=sourceEntities?entityCursor:sourceCursor;
                        if(redo?cursor>=journal.count:cursor<0)break;
                        needed=512L+2*journal.memoryAt(journal.offset(cursor));entry.ticket=memory.acquire(PasteMemoryBudget.Kind.HISTORY,needed);if(entry.ticket==null)break;
                        entry.record=journal.read(cursor);entry.index=cursor;if(sourceEntities)entityCursor+=redo?1:-1;else sourceCursor+=redo?1:-1;
                    }else{
                        if(pendingLegacy==null&&!legacy.hasNext())break;Change change=pendingLegacy==null?legacy.next():pendingLegacy;pendingLegacy=null;
                        if(change instanceof BlockChange){BlockChange b=(BlockChange)change;PasteNbtSizer s=new PasteNbtSizer();s.start(b.getPrevious().getNbtData());long size=s.resume(Long.MAX_VALUE,Integer.MAX_VALUE);s.start(b.getCurrent().getNbtData());size+=s.resume(Long.MAX_VALUE,Integer.MAX_VALUE);needed=1024L+size*2;entry.ticket=memory.acquire(PasteMemoryBudget.Kind.HISTORY,needed);if(entry.ticket==null){pendingLegacy=change;break;}entry.record=PasteDiskJournal.Record.block(b.getPosition(),b.getPrevious(),b.getCurrent(),512L+size);}
                        else{if(!(change instanceof EntityCreate)&&!(change instanceof EntityRemove))throw new IllegalArgumentException("unbounded custom history change");needed=1024;entry.ticket=memory.acquire(PasteMemoryBudget.Kind.HISTORY,needed);if(entry.ticket==null){pendingLegacy=change;break;}entry.change=change;}
                    }
                    batch.add(entry);bytes+=needed;
                }
                return batch;
            }catch(Throwable e){for(ReplayRecord r:batch)r.close();throw e;}
        }
        Change pendingLegacy;
        void flush(final boolean releaseBatch){
            job(new Callable<Object>(){public Object call()throws Exception{
                if(releaseBatch){for(ReplayRecord entry:sourceBatch){if(entry.record!=null&&entry.record.entity!=null)disk.entities.updateEntityIdentity(entry.index,entry.record.entityId,entry.record.uuid);entry.close();}sourceBatch=null;}
                storage.flush();return null;
            }});
            // Empty source batch marks exhaustion, not temporary memory pressure.
            boolean exhausted=releaseBatch&&(disk!=null?(!sourceEntities&&(redo?sourceCursor>=disk.blocks.count:sourceCursor<0)):pendingLegacy==null&&!legacy.hasNext());
            state=exhausted?State.COMMIT:State.FLUSH;
        }
        void release(){cancelled=true;if(session!=null)DeferredPasteManager.releaseSession(session,this);if(!busy)DeferredPasteManager.WORKERS.execute(new Runnable(){public void run(){cleanup();}});}
        synchronized void cleanup(){
            try{if(result instanceof PasteStreamStorage.Batch)((PasteStreamStorage.Batch)result).close();if(result instanceof List)for(ReplayRecord r:castRecords(result))r.close();result=null;
                if(sourceBatch!=null){for(ReplayRecord r:sourceBatch)r.close();sourceBatch=null;}commitCursor.close();if(storage!=null)storage.close();
            }catch(Exception e){OverdriveLog.error("history cleanup failed: {}",e.toString());if(storage!=null)storage.releasePending();}
            finally{if(stateMemory!=null){stateMemory.close();stateMemory=null;}descriptor.close();PasteHookStatus.historyReplayLiveMemoryBytes.set(memory.live());PasteHookStatus.historyReplayPhase="IDLE";}
        }
    }
    private static final class ReplayRecord implements AutoCloseable {
        PasteDiskJournal.Record record;Change change;long index;PasteMemoryBudget.Ticket ticket;
        public void close(){if(record!=null){record.release();record=null;}change=null;if(ticket!=null){ticket.close();ticket=null;}}
    }
    private HistoryReplayBridge(){}
}
