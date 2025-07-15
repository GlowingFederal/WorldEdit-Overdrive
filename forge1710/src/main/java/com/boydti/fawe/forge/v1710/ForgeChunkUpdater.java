package com.boydti.fawe.forge.v1710;

import com.boydti.fawe.object.FaweChunk;
import com.boydti.fawe.object.FawePlayer;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.wrappers.WorldWrapper;
import com.sk89q.worldedit.forge.ForgeWorld;
import com.sk89q.worldedit.world.AbstractChunkUpdater;
import com.sk89q.worldedit.world.World;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Updates changed chunks after a WorldEdit operation by sending block updates
 * to all players watching the chunks.
 */
public class ForgeChunkUpdater extends AbstractChunkUpdater {
    @Override
    public void updateChunks(World world, FaweQueue queue, Collection<FaweChunk> chunks) {
        if (world instanceof WorldWrapper) {
            world = ((WorldWrapper) world).getParent();
        }
        if (!(world instanceof ForgeWorld)) {
            return;
        }
        if (!(queue instanceof ForgeQueue_All)) {
            return;
        }
        ForgeQueue_All fq = (ForgeQueue_All) queue;
        WorldServer mcWorld = (WorldServer) ((ForgeWorld) world).getWorld();

        // Build a player array for sending updates
        List<FawePlayer> players = new ArrayList<>();
        for (Object obj : mcWorld.playerEntities) {
            if (obj instanceof EntityPlayerMP) {
                players.add(FawePlayer.wrap(obj));
            }
        }
        FawePlayer[] arr = players.toArray(new FawePlayer[0]);

        for (FaweChunk chunk : chunks) {
            fq.sendBlockUpdate(chunk, arr);
        }
    }
}
