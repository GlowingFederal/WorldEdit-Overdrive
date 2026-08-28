package com.glowingfederal.worldeditoverdrive.backend;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/** Initially permits only inert, identity-checked vanilla storage blocks. */
public final class ConservativeRawMutationClassifier implements RawMutationClassifier {
    @Override
    public boolean isRawSafe(World world, int x, int y, int z, Block oldBlock, int oldMetadata,
            Block newBlock, int newMetadata) {
        return inert(oldBlock, oldMetadata) && inert(newBlock, newMetadata);
    }

    private static boolean inert(Block block, int metadata) {
        if (block == null || block.hasTileEntity(metadata) || block.getTickRandomly()) return false;
        return block == Blocks.air || block == Blocks.stone || block == Blocks.dirt
                || block == Blocks.cobblestone || block == Blocks.sandstone
                || block == Blocks.netherrack || block == Blocks.end_stone;
    }
}
