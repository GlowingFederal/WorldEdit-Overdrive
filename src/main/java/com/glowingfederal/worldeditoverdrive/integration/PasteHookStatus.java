package com.glowingfederal.worldeditoverdrive.integration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Diagnostics owned exclusively by the optional Stage 5C paste bridge. */
public final class PasteHookStatus {
    public enum RuntimeShape { NOT_SEEN, SEEN_INCOMPATIBLE, SEEN_COMPATIBLE, HOOK_INSTALLED }
    private static final AtomicReference<RuntimeShape> runtimeShape=new AtomicReference<RuntimeShape>(RuntimeShape.NOT_SEEN);
    public static volatile boolean pasteHookInstalled;
    public static volatile boolean pasteBytecodeModified;
    public static volatile String hookReason="ForwardExtentCopy not observed";
    public static volatile String lastPasteFallbackReason;
    public static volatile String lastPasteGraphDiagnostic;
    public static volatile String lastPasteDeferredReason;
    public static volatile String lastPasteAccelerationFallbackReason;
    public static final AtomicLong pasteBridgeInvocations=new AtomicLong();
    public static final AtomicLong pasteAccelerated=new AtomicLong();
    public static final AtomicLong pasteFallbacks=new AtomicLong();
    public static final AtomicLong pasteDeferredActive=new AtomicLong();
    public static final AtomicLong pasteDeferredCompleted=new AtomicLong();
    public static final AtomicLong pasteDeferredFailed=new AtomicLong();
    public static final AtomicLong pasteAccelerationFallbacks=new AtomicLong();
    public static final AtomicLong pastePlanningActive=new AtomicLong(),pasteCommitActive=new AtomicLong();
    public static final AtomicLong pastePreparedBlocks=new AtomicLong(),pastePlannedBlocks=new AtomicLong(),pasteCommittedBlocks=new AtomicLong();
    public static final AtomicLong lastPastePrepareMillis=new AtomicLong(),lastPastePlanMillis=new AtomicLong(),lastPasteCommitMillis=new AtomicLong();
    public static RuntimeShape runtimeShape(){return runtimeShape.get();}
    public static boolean forwardExtentCopySeen(){return runtimeShape.get()!=RuntimeShape.NOT_SEEN;}
    public static boolean runtimeShapeCompatible(){RuntimeShape state=runtimeShape.get();return state==RuntimeShape.SEEN_COMPATIBLE||state==RuntimeShape.HOOK_INSTALLED;}
    static void observedCompatible(){if(pasteHookInstalled)return;runtimeShape.set(RuntimeShape.SEEN_COMPATIBLE);hookReason="compatible; ClipboardCommands paste hook not observed";}
    static void observedIncompatible(String reason){runtimeShape.set(RuntimeShape.SEEN_INCOMPATIBLE);pasteHookInstalled=false;hookReason="incompatible runtime shape: "+reason;}
    static void hookInstalled(){runtimeShape.set(RuntimeShape.HOOK_INSTALLED);pasteHookInstalled=true;pasteBytecodeModified=true;hookReason="installed";}
    static void resetForTests(){runtimeShape.set(RuntimeShape.NOT_SEEN);pasteHookInstalled=false;pasteBytecodeModified=false;hookReason="ForwardExtentCopy not observed";lastPasteGraphDiagnostic=null;}
    private PasteHookStatus() { }
}
