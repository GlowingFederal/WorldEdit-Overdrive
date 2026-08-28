package com.glowingfederal.worldeditoverdrive.integration;

/** Bounded observability store: exactly the latest terminal accelerated operation. */
public final class OverdriveSummaries {
    private static volatile OverdriveEditSummary latest;
    private OverdriveSummaries(){ }
    public static void publish(OverdriveEditSummary summary){latest=summary;}
    public static OverdriveEditSummary latest(){return latest;}
}
