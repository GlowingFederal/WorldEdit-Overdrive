package com.glowingfederal.worldeditoverdrive.execution;

/** Worker preparation returning one multi-phase, operation-owned chunk plan. */
public interface OperationPreparationTask { PreparedOperationChunk prepare() throws Exception; }
