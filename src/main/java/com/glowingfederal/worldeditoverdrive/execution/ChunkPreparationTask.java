package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;

/** Must use only immutable input or state exclusively owned by this invocation. */
public interface ChunkPreparationTask {
    PreparedChunkChange prepare() throws Exception;
}
