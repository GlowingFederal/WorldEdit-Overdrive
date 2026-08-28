package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.LocalSession;

/** Explicit ownership boundary for the future transformed paste call site. */
public final class PasteBridge {
    public enum Decision { VANILLA, DEFERRED }
    public static final class Result {
        public final Decision decision; public final PasteContinuationOperation continuation; public final String reason;
        private Result(Decision decision,PasteContinuationOperation continuation,String reason){this.decision=decision;this.continuation=continuation;this.reason=reason;}
        public boolean ownsOperation(){return decision==Decision.DEFERRED&&continuation!=null;}
    }
    /** Called only by the transformed standard ClipboardCommands#paste call site. */
    public static Decision tryDefer(Operation operation,Player player,LocalSession session,boolean selectPasted) {
        PasteHookStatus.pasteBridgeInvocations.incrementAndGet();
        if(!(operation instanceof ForwardExtentCopy))return fallback("PasteBuilder did not return ForwardExtentCopy");
        PasteOperationAdapter.Result recognized=PasteOperationAdapter.recognize((ForwardExtentCopy)operation);
        if(!recognized.isRecognized())return fallback(recognized.reason);
        try {
            DeferredPasteManager.register((ForwardExtentCopy)operation,recognized.adapter,player,session,selectPasted);
            PasteHookStatus.lastPasteDeferredReason="owned standard Enhanced 6.3.0 paste graph";
            return Decision.DEFERRED;
        } catch(Throwable unavailable) { return fallback("deferred ownership unavailable: "+unavailable.toString()); }
    }
    private static Decision fallback(String reason){PasteHookStatus.pasteFallbacks.incrementAndGet();PasteHookStatus.lastPasteFallbackReason=reason;return Decision.VANILLA;}
    public static Result tryCreateContinuation(ForwardExtentCopy operation) {
        PasteHookStatus.pasteBridgeInvocations.incrementAndGet();
        PasteOperationAdapter.Result recognized=PasteOperationAdapter.recognize(operation);
        String reason=recognized.isRecognized()?"async continuation runner not installed":recognized.reason;
        PasteHookStatus.pasteFallbacks.incrementAndGet();PasteHookStatus.lastPasteFallbackReason=reason;
        return new Result(Decision.VANILLA,null,reason);
    }
    private PasteBridge() { }
}
