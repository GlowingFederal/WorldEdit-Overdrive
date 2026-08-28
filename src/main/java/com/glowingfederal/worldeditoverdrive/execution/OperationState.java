package com.glowingfederal.worldeditoverdrive.execution;

public enum OperationState {
    CREATED, PREPARING, READY, COMMITTING, COMPLETED, CANCELLED, FAILED;
    public boolean isTerminal() { return this == COMPLETED || this == CANCELLED || this == FAILED; }
}
