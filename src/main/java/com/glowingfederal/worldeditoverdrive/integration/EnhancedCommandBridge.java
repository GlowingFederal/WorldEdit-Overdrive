package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.masks.Mask;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.regions.Region;

/** Narrow method-entry adapters for the pinned Enhanced 6.3.0 EditSession API. */
public final class EnhancedCommandBridge {
    private EnhancedCommandBridge(){}

    /** Explicit outcome consumed by the entry transformer. */
    public static final class Decision {
        private static final Decision NOT_HANDLED=new Decision(false,0);
        private final boolean handled;
        private final int result;
        private Decision(boolean handled,int result){this.handled=handled;this.result=result;}
        public static Decision handled(int result){return new Decision(true,result);}
        public static Decision notHandled(){return NOT_HANDLED;}
        public boolean isHandled(){return handled;}
        public int getResult(){
            if(!handled)throw new IllegalStateException("A not-handled bridge decision has no result");
            return result;
        }
    }
    public static Decision replace(
            EditSession session,
            Region region,
            Mask mask,
            Pattern pattern
    ) throws MaxChangedBlocksException {
        CommandHookStatus.replaceBridgeInvoked.incrementAndGet();

        return unavailable("replace incremental owner not installed");
    }
    public static Decision geometry(EditSession session,Region region,Pattern pattern,int kind)throws MaxChangedBlocksException{
        CommandHookStatus.geometryBridgeInvoked.incrementAndGet();return unavailable("geometry incremental owner not installed");
    }
    public static Decision overlay(EditSession session,Region region,Pattern pattern)throws MaxChangedBlocksException{
        CommandHookStatus.overlayBridgeInvoked.incrementAndGet();return unavailable("overlay incremental owner not installed");
    }
    public static Decision naturalize(EditSession session,Region region)throws MaxChangedBlocksException{
        CommandHookStatus.overlayBridgeInvoked.incrementAndGet();return unavailable("naturalize incremental owner not installed");
    }
    public static Decision stack(EditSession session,Region region,Vector dir,int count,boolean copyAir)throws MaxChangedBlocksException{
        CommandHookStatus.copyMoveBridgeInvoked.incrementAndGet();return unavailable("stack incremental ordered owner not installed");
    }
    public static Decision move(EditSession session,Region region,Vector dir,int distance,boolean copyAir,BaseBlock leave)throws MaxChangedBlocksException{
        CommandHookStatus.copyMoveBridgeInvoked.incrementAndGet();return unavailable("move incremental ordered owner not installed");
    }
    private static Decision unavailable(String reason){CommandHookStatus.lastOperationFallbackReason=reason;return Decision.notHandled();}
}
