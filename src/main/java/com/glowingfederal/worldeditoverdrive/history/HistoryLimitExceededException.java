package com.glowingfederal.worldeditoverdrive.history;

/** Recording stops before exceeding the configured bounded in-memory history. */
public final class HistoryLimitExceededException extends RuntimeException {
    public HistoryLimitExceededException(long limit){super("Overdrive history memory limit exceeded: "+limit+" bytes");}
}
