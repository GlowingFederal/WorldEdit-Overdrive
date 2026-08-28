package com.glowingfederal.worldeditoverdrive.integration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Diagnostics owned exclusively by the optional Stage 5C paste bridge. */
public final class PasteHookStatus {
    public enum RuntimeShape { NOT_SEEN, SEEN_INCOMPATIBLE, SEEN_COMPATIBLE, HOOK_INSTALLED }
    private static final AtomicReference<RuntimeShape> runtimeShape=new AtomicReference<RuntimeShape>(RuntimeShape.NOT_SEEN);
    public static volatile boolean pasteHookInstalled;
    public static volatile String hookReason="ForwardExtentCopy not observed";
    public static volatile String lastPasteFallbackReason;
    public static final AtomicLong pasteBridgeInvocations=new AtomicLong();
    public static final AtomicLong pasteAccelerated=new AtomicLong();
    public static final AtomicLong pasteFallbacks=new AtomicLong();
    public static RuntimeShape runtimeShape(){return runtimeShape.get();}
    public static boolean forwardExtentCopySeen(){return runtimeShape.get()!=RuntimeShape.NOT_SEEN;}
    public static boolean runtimeShapeCompatible(){RuntimeShape state=runtimeShape.get();return state==RuntimeShape.SEEN_COMPATIBLE||state==RuntimeShape.HOOK_INSTALLED;}
    static void observedCompatible(){runtimeShape.set(RuntimeShape.SEEN_COMPATIBLE);pasteHookInstalled=false;hookReason="compatible; async continuation runner not implemented";}
    static void observedIncompatible(String reason){runtimeShape.set(RuntimeShape.SEEN_INCOMPATIBLE);pasteHookInstalled=false;hookReason="incompatible runtime shape: "+reason;}
    static void hookInstalled(){runtimeShape.set(RuntimeShape.HOOK_INSTALLED);pasteHookInstalled=true;hookReason="installed";}
    static void resetForTests(){runtimeShape.set(RuntimeShape.NOT_SEEN);pasteHookInstalled=false;hookReason="ForwardExtentCopy not observed";}
    private PasteHookStatus() { }
}
