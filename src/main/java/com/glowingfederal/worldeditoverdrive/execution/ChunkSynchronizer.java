package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.ChunkCommitResult;
import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Chunk-granular 1.7.10 synchronization; never broadcasts outside tracking players. */
public final class ChunkSynchronizer {
    public enum Strategy { NONE, MULTI_BLOCK, CHUNK }

    public Strategy synchronize(WorldServer world, Chunk chunk, PreparedChunkChange change,
            ChunkCommitResult result, int sparseThreshold) {
        return synchronize(world, chunk, change, result, sparseThreshold, null);
    }

    Strategy synchronize(WorldServer world, Chunk chunk, PreparedChunkChange change,
            ChunkCommitResult result, int sparseThreshold, OverdriveOperation operation) {
        PlayerManager manager = world.getPlayerManager();
        if (!manager.func_152621_a(change.getChunkX(), change.getChunkZ())) return Strategy.NONE;
        boolean full = result.isBiomeDirty() || result.suggestsFullChunkPacket()
                || result.getChangedBlocks() > sparseThreshold;
        Packet packet = full
                ? new S21PacketChunkData(chunk, result.isBiomeDirty(), full ? 65535 : result.getSectionMask())
                : new S22PacketMultiBlockChange(change.getChangedBlockCount(), change.changedPositions(), chunk);
        for (EntityPlayer player : (List<EntityPlayer>) world.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) continue;
            EntityPlayerMP target = (EntityPlayerMP) player;
            if (manager.isPlayerWatchingChunk(target, change.getChunkX(), change.getChunkZ()))
                target.playerNetServerHandler.sendPacket(packet);
        }
        if (result.isTileDirty()) {
            for (PreparedChunkChange.TileData tile : change.getTiles()) {
                TileEntity live = world.getTileEntity((change.getChunkX() << 4) | tile.getLocalX(),
                        tile.getY(), (change.getChunkZ() << 4) | tile.getLocalZ());
                if (live == null || live.getDescriptionPacket() == null) continue;
                for (EntityPlayer player : (List<EntityPlayer>) world.playerEntities) {
                    if (player instanceof EntityPlayerMP && manager.isPlayerWatchingChunk(
                            (EntityPlayerMP) player, change.getChunkX(), change.getChunkZ())) {
                        ((EntityPlayerMP) player).playerNetServerHandler.sendPacket(live.getDescriptionPacket());
                        if (operation != null) operation.tilePackets++;
                    }
                }
            }
        }
        return full ? Strategy.CHUNK : Strategy.MULTI_BLOCK;
    }
}
