package com.glowingfederal.worldeditoverdrive.backend;

/** A policy, rather than an ignore-physics boolean, keeps each side effect explicit. */
public final class SideEffectPolicy {
    public enum Placement { RAW_STORAGE, NATIVE_COMPATIBLE }

    public static final SideEffectPolicy RAW = new SideEffectPolicy(
            Placement.RAW_STORAGE, false, false, false, true);
    public static final SideEffectPolicy NATIVE = new SideEffectPolicy(
            Placement.NATIVE_COMPATIBLE, true, true, true, true);

    private final Placement placement;
    private final boolean lifecycleCallbacks;
    private final boolean neighborNotifications;
    private final boolean comparatorUpdates;
    private final boolean tileHandling;

    public SideEffectPolicy(Placement placement, boolean lifecycleCallbacks,
            boolean neighborNotifications, boolean comparatorUpdates, boolean tileHandling) {
        if (placement == null) throw new NullPointerException("placement");
        this.placement = placement;
        this.lifecycleCallbacks = lifecycleCallbacks;
        this.neighborNotifications = neighborNotifications;
        this.comparatorUpdates = comparatorUpdates;
        this.tileHandling = tileHandling;
    }

    public Placement getPlacement() { return placement; }
    public boolean hasLifecycleCallbacks() { return lifecycleCallbacks; }
    public boolean hasNeighborNotifications() { return neighborNotifications; }
    public boolean hasComparatorUpdates() { return comparatorUpdates; }
    public boolean hasTileHandling() { return tileHandling; }
}
