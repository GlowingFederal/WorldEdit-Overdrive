package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.backend.ChunkCommitResult;
import com.glowingfederal.worldeditoverdrive.backend.ForgeChunkWriter;
import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard;
import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import com.glowingfederal.worldeditoverdrive.execution.ChunkSynchronizer;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.forge.ForgeWorld;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.patterns.SingleBlockPattern;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import cpw.mods.fml.common.FMLLog;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Entry point invoked by the surgical EditSession transformer; null means untouched fallback. */
public final class Stage4SetBridge {
    private Stage4SetBridge() { }

    public static Integer trySet(EditSession session, Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        if (session == null || region == null || pattern == null) return null;
        if (region.getClass() != CuboidRegion.class || pattern.getClass() != SingleBlockPattern.class
                || session.getClass() != EditSession.class || session.getMask() != null
                || session.isQueueEnabled() || !(session.getWorld() instanceof ForgeWorld)) return null;
        net.minecraft.world.World nativeWorld=((ForgeWorld)session.getWorld()).getWorld();
        if (!(nativeWorld instanceof WorldServer) || nativeWorld.isRemote) return null;
        BaseBlock block=((SingleBlockPattern)pattern).getBlock();
        if (block.getId()<0 || block.getId()>4095 || block.getData()<0 || block.getData()>15) return null;
        long volume;
        try { volume=ConstantFillPlanner.volume((CuboidRegion)region); }
        catch (ArithmeticException overflow) { return null; }
        if (region.getMinimumPoint().getBlockY()<0 || region.getMaximumPoint().getBlockY()>255) return null;
        int limit=session.getBlockChangeLimit();
        if (limit>=0 && volume>limit) throw new MaxChangedBlocksException(limit);
        if (volume>Integer.MAX_VALUE) return null; // Enhanced's return/history size are int bounded.

        WorldServer world=(WorldServer)nativeWorld;
        ServerThreadGuard.capture();
        NBTTagCompound tile=toNative(block);
        if (block.getNbtData()!=null && tile==null) return null;
        List<PreparedChunkChange> changes=ConstantFillPlanner.prepare((CuboidRegion)region,block,tile);
        ForgeChunkWriter writer=new ForgeChunkWriter(); ChunkSynchronizer sync=new ChunkSynchronizer();
        long started=System.nanoTime(); int affected=0, raw=0, nativeCount=0, dense=0, sparse=0;
        for(PreparedChunkChange change:changes) {
            captureHistory(session,change,block);
            ChunkCommitResult result=writer.commit(world,change,SideEffectPolicy.RAW);
            raw+=result.getRawBlocks(); nativeCount+=result.getNativeBlocks(); dense+=result.getDenseSections();
            sparse+=result.getTouchedSections()-result.getDenseSections();
            Chunk chunk=world.getChunkFromChunkCoords(change.getChunkX(),change.getChunkZ());
            sync.synchronize(world,chunk,change,result,512); affected+=result.getChangedBlocks();
        }
        FMLLog.info("Overdrive //set completed: %d blocks, %d chunks, %d dense/%d sparse sections, "
                + "%d raw/%d native, %.3f ms",affected,changes.size(),dense,sparse,raw,nativeCount,
                (System.nanoTime()-started)/1000000D);
        return affected;
    }

    private static void captureHistory(EditSession session, PreparedChunkChange change, final BaseBlock current) {
        for(int sy=0;sy<16;sy++) if(change.getSection(sy)!=null) {
            final int section=sy;
            change.getSection(sy).forEach(new com.glowingfederal.worldeditoverdrive.backend.SectionChange.Visitor(){
                public void visit(int index,int packed) {
                    int x=(change.getChunkX()<<4)|com.glowingfederal.worldeditoverdrive.backend.SectionChange.localX(index);
                    int y=(section<<4)|com.glowingfederal.worldeditoverdrive.backend.SectionChange.localY(index);
                    int z=(change.getChunkZ()<<4)|com.glowingfederal.worldeditoverdrive.backend.SectionChange.localZ(index);
                    Vector position=new BlockVector(x,y,z);
                    session.getChangeSet().add(new BlockChange((BlockVector)position,session.getWorld().getBlock(position),current));
                }});
        }
    }

    private static NBTTagCompound toNative(BaseBlock block) {
        CompoundTag tag=block.getNbtData(); if(tag==null)return null;
        try { Class<?> type=Class.forName("com.sk89q.worldedit.forge.NBTConverter");
            Method method=type.getDeclaredMethod("toNative",CompoundTag.class); method.setAccessible(true);
            NBTTagCompound nativeTag=(NBTTagCompound)method.invoke(null,tag); nativeTag.setString("id",block.getNbtId()); return nativeTag;
        } catch(Exception incompatible) { FMLLog.warning("Overdrive tile translation unavailable: %s",incompatible.toString()); return null; }
    }

}
