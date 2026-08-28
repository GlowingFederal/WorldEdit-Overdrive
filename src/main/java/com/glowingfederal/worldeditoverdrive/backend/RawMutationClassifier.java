package com.glowingfederal.worldeditoverdrive.backend;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/** Conservative boundary: false means the writer must use the compatible path. */
public interface RawMutationClassifier {
    boolean isRawSafe(World world, int x, int y, int z, Block oldBlock, int oldMetadata,
            Block newBlock, int newMetadata);
}
