package com.glowingfederal.worldeditoverdrive.integration;

import java.util.concurrent.atomic.AtomicLong;

/** Diagnostics owned exclusively by the optional Stage 5C paste bridge. */
public final class PasteHookStatus {
    public static volatile boolean forwardExtentCopySeen;
    public static volatile boolean pasteHookInstalled;
    public static volatile String hookReason="ForwardExtentCopy not transformed";
    public static volatile String lastPasteFallbackReason;
    public static final AtomicLong pasteBridgeInvocations=new AtomicLong();
    public static final AtomicLong pasteAccelerated=new AtomicLong();
    public static final AtomicLong pasteFallbacks=new AtomicLong();
    private PasteHookStatus() { }
}
