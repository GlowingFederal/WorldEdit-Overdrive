package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.function.operation.ForwardExtentCopy;

/** Explicit ownership boundary for the future transformed paste call site. */
public final class PasteBridge {
    public enum Decision { VANILLA, CONTINUATION }
    public static final class Result {
        public final Decision decision; public final PasteContinuationOperation continuation; public final String reason;
        private Result(Decision decision,PasteContinuationOperation continuation,String reason){this.decision=decision;this.continuation=continuation;this.reason=reason;}
        public boolean ownsOperation(){return decision==Decision.CONTINUATION&&continuation!=null;}
    }
    public static Result tryCreateContinuation(ForwardExtentCopy operation) {
        PasteHookStatus.pasteBridgeInvocations.incrementAndGet();
        PasteOperationAdapter.Result recognized=PasteOperationAdapter.recognize(operation);
        String reason=recognized.isRecognized()?"async continuation runner not installed":recognized.reason;
        PasteHookStatus.pasteFallbacks.incrementAndGet();PasteHookStatus.lastPasteFallbackReason=reason;
        return new Result(Decision.VANILLA,null,reason);
    }
    private PasteBridge() { }
}
