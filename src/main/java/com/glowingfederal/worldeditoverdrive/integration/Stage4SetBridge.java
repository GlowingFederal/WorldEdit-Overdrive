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
    private static volatile boolean runtimeTypesLogged;
    private Stage4SetBridge() { }

    public static Integer trySet(EditSession session, Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        Stage4HookStatus.bridgeInvocations.incrementAndGet();
        if (session == null || region == null || pattern == null) return fallback("null argument: session="+session+", region="+region+", pattern="+pattern);
        if (!runtimeTypesLogged) {
            runtimeTypesLogged=true;
            FMLLog.info("WorldEdit Overdrive //set runtime: session=%s, region=%s, pattern=%s, world=%s, queueEnabled=%s, mask=%s",
                    type(session),type(region),type(pattern),type(session.getWorld()),session.isQueueEnabled(),value(session.getMask()));
        }
        // CuboidRegion is deliberately exact: subclasses may override bounds/iteration semantics.
        if (region.getClass() != CuboidRegion.class) return fallback("region="+type(region)+", expected exact CuboidRegion");
        BaseBlock block=ConstantPatternResolver.resolve(pattern);
        if (block == null) return fallback("pattern="+type(pattern)+", expected SingleBlockPattern");
        // Enhanced's builder creates EditSession directly; unknown subclasses can alter the extent boundary.
        if (session.getClass() != EditSession.class) return fallback("session="+type(session)+", expected exact EditSession");
        if (session.getMask() != null) return fallback("active mask="+value(session.getMask()));
        if (session.isQueueEnabled()) return fallback("reorder queue enabled");
        if (!(session.getWorld() instanceof ForgeWorld)) return fallback("world="+type(session.getWorld())+", expected ForgeWorld");
        net.minecraft.world.World nativeWorld=((ForgeWorld)session.getWorld()).getWorld();
        if (!(nativeWorld instanceof WorldServer)) return fallback("native world="+type(nativeWorld)+", expected WorldServer");
        if (nativeWorld.isRemote) return fallback("client world");
        if (block.getId()<0 || block.getId()>4095) return fallback("invalid legacy ID="+block.getId());
        if (block.getData()<0 || block.getData()>15) return fallback("invalid metadata="+block.getData());
        long volume;
        try { volume=ConstantFillPlanner.volume((CuboidRegion)region); }
        catch (ArithmeticException overflow) { return fallback("cuboid volume overflow"); }
        if (region.getMinimumPoint().getBlockY()<0 || region.getMaximumPoint().getBlockY()>255) return fallback("out-of-height range");
        int limit=session.getBlockChangeLimit();
        if (limit>=0 && volume>limit) throw new MaxChangedBlocksException(limit);
        if (volume>Integer.MAX_VALUE) return fallback("volume over Enhanced limit: "+volume); // return/history are int bounded.

        WorldServer world=(WorldServer)nativeWorld;
        ServerThreadGuard.capture();
        NBTTagCompound tile=toNative(block);
        if (block.getNbtData()!=null && tile==null) return fallback("tile NBT conversion unavailable");
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
        Stage4HookStatus.acceleratedInvocations.incrementAndGet();
        return affected;
    }

    private static Integer fallback(String reason) {
        Stage4HookStatus.fallbackInvocations.incrementAndGet();
        Stage4HookStatus.lastFallbackReason=reason;
        FMLLog.info("Overdrive //set fallback: %s",reason);
        return null;
    }

    private static String type(Object value) { return value==null ? "null" : value.getClass().getName(); }
    private static String value(Object value) { return value==null ? "null" : type(value)+"("+value+")"; }

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
