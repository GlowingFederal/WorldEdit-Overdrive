package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import java.util.ArrayDeque;
import java.util.Queue;

/** Main-thread, next-tick owner for the first live Stage 5C continuation. */
public final class DeferredPasteManager {
    private static final Queue<Owner> PENDING=new ArrayDeque<Owner>();
    public static synchronized void register(ForwardExtentCopy operation,PasteOperationAdapter adapter,
            Player player,LocalSession session,boolean selectPasted) throws Exception {
        if(operation==null||adapter==null||player==null||session==null)throw new NullPointerException("deferred paste context");
        PENDING.add(new Owner(operation,adapter,player,session,session.getClipboard(),selectPasted));
        PasteHookStatus.pasteDeferredActive.incrementAndGet();
    }
    /** Progresses one owned paste per END server tick; resume remains entirely on that thread. */
    public static void tick() {
        Owner owner; synchronized(DeferredPasteManager.class){owner=PENDING.poll();}
        if(owner==null)return;
        try { owner.run(); PasteHookStatus.pasteDeferredCompleted.incrementAndGet(); }
        catch(Throwable failure){PasteHookStatus.pasteDeferredFailed.incrementAndGet();PasteHookStatus.lastPasteDeferredReason="failed: "+failure.toString();
            owner.player.printError("Paste failed: "+failure.getMessage());OverdriveLog.error("deferred paste failed: {}",failure.toString());}
        finally { PasteHookStatus.pasteDeferredActive.decrementAndGet(); }
    }
    private static final class Owner {
        final ForwardExtentCopy operation; final PasteOperationAdapter adapter; final Player player;
        final LocalSession session; final ClipboardHolder holder; final boolean selectPasted;
        Owner(ForwardExtentCopy operation,PasteOperationAdapter adapter,Player player,LocalSession session,
                ClipboardHolder holder,boolean selectPasted){this.operation=operation;this.adapter=adapter;this.player=player;
            this.session=session;this.holder=holder;this.selectPasted=selectPasted;}
        void run() throws Exception {
            Operations.completeLegacy(operation);
            // The command framework's earlier empty remember is a no-op; retain and remember
            // this same EditSession only after its deferred mutation and queue flush.
            session.remember(adapter.destination);
            Vector to=adapter.destinationOrigin;
            if(selectPasted){Vector max=to.add(adapter.region.getMaximumPoint().subtract(adapter.region.getMinimumPoint()));
                RegionSelector selector=new CuboidRegionSelector(player.getWorld(),to,max);session.setRegionSelector(player.getWorld(),selector);
                selector.learnChanges();selector.explainRegionAdjust(player,session);}
            player.print("The clipboard has been pasted at "+to);
        }
    }
    private DeferredPasteManager() { }
}
