package com.glowingfederal.worldeditoverdrive.integration;

import java.util.concurrent.atomic.AtomicLong;

/** Runtime proof for the Enhanced 6.3 command-family entry hooks. */
public final class CommandHookStatus {
    public static volatile boolean replaceHookInstalled,geometryHookInstalled,copyMoveHookInstalled,overlayHookInstalled;
    public static final AtomicLong replaceBridgeInvoked=new AtomicLong(),geometryBridgeInvoked=new AtomicLong(),copyMoveBridgeInvoked=new AtomicLong(),overlayBridgeInvoked=new AtomicLong();
    public static final AtomicLong replaceAccelerated=new AtomicLong(),geometryAccelerated=new AtomicLong(),stackAccelerated=new AtomicLong(),moveAccelerated=new AtomicLong(),overlayAccelerated=new AtomicLong(),naturalizeAccelerated=new AtomicLong();
    public static volatile String lastOperationType="none",lastOperationFallbackReason="none";
    public static final AtomicLong lastOperationSnapshotMillis=new AtomicLong(),lastOperationPlanMillis=new AtomicLong(),lastOperationCommitMillis=new AtomicLong(),lastOperationWallMillis=new AtomicLong();
    private CommandHookStatus(){}
}
