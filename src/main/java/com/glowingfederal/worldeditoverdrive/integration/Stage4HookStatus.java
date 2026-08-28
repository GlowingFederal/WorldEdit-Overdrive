package com.glowingfederal.worldeditoverdrive.integration;

import java.util.concurrent.atomic.AtomicLong;

/** Tiny cross-class-loader-safe diagnostic state for the Stage 4 launch hook. */
public final class Stage4HookStatus {
    public static volatile boolean corePluginLoaded;
    public static volatile boolean transformerRegistered;
    public static volatile boolean editSessionSeen;
    public static volatile boolean targetMethodMatched;
    public static volatile boolean hookInstalled;
    public static volatile String targetNames;
    public static volatile String lastFallbackReason;
    public static final AtomicLong bridgeInvocations = new AtomicLong();
    public static final AtomicLong acceleratedInvocations = new AtomicLong();
    public static final AtomicLong fallbackInvocations = new AtomicLong();

    private Stage4HookStatus() { }
}
