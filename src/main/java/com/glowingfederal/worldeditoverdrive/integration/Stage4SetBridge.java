package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.backend.ChunkCommitResult;
import com.glowingfederal.worldeditoverdrive.backend.ForgeChunkWriter;
import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard;
import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import com.glowingfederal.worldeditoverdrive.execution.ChunkSynchronizer;
import com.glowingfederal.worldeditoverdrive.history.OverdriveChangeSet;
import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.forge.ForgeWorld;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.function.block.BlockReplace;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.visitor.RegionVisitor;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Entry point invoked by the surgical EditSession transformer; null means untouched fallback. */
public final class Stage4SetBridge {
    private static volatile boolean runtimeTypesLogged;
    private Stage4SetBridge() { }

    /** Entry used by Enhanced's composed /set command (Apply -> RegionVisitor -> BlockReplace). */
    public static Integer trySetOperation(Operation operation)
            throws MaxChangedBlocksException {
        Stage4HookStatus.bridgeInvocations.incrementAndGet();
        OverdriveLog.info("WorldEditOverdrive //set bridge invoked");
        if (!(operation instanceof RegionVisitor)) return null;
        try {
            Object region=readField(operation,"region");
            Object function=readField(operation,"function");
            if (!(region instanceof Region) || !(function instanceof BlockReplace)) return null;
            Object extent=readField(function,"extent");
            if (!(extent instanceof EditSession)) return null;
            Object candidate=readField(function,"pattern");
            BaseBlock block=resolveComposedConstant(candidate);
            if(block==null)return null;
            Integer result=trySetBlock((EditSession)extent,(Region)region,block);
            if(result!=null)writeField(operation,"affected",result);
            return result;
        } catch (ReflectiveOperationException incompatible) {
            return fallback("composed /set operation shape unavailable: "+incompatible.toString());
        }
    }

    public static Integer trySet(EditSession session, Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        Stage4HookStatus.bridgeInvocations.incrementAndGet();
        OverdriveLog.info("WorldEditOverdrive legacy setBlocks bridge invoked");
        if (session == null || region == null || pattern == null) return fallback("null argument: session="+session+", region="+region+", pattern="+pattern);
        BaseBlock block=ConstantPatternResolver.resolve(pattern);
        if (block == null) return fallback("pattern="+type(pattern)+", expected SingleBlockPattern");
        return trySetBlock(session,region,block);
    }

    private static Integer trySetBlock(EditSession session,Region region,BaseBlock block)
            throws MaxChangedBlocksException {
        if(session==null||region==null||block==null)return fallback("null composed /set argument");
        if (!runtimeTypesLogged) {
            runtimeTypesLogged=true;
            OverdriveLog.info("//set runtime: session={}, region={}, block={}, world={}, queueEnabled={}, mask={}",
                    type(session),type(region),type(block),type(session.getWorld()),session.isQueueEnabled(),value(session.getMask()));
        }
        CuboidBounds bounds=CuboidBounds.resolve(region);
        if (bounds == null) return fallback("region="+type(region)+", complete cuboid bounds not proven");
        EditSessionCompatibilityInspector.Result compatibility=EditSessionCompatibilityInspector.inspect(session);
        if(compatibility.classification!=EditSessionCompatibilityInspector.Classification.SAFE)return fallback(compatibility.reason);
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
        if (volume>Integer.MAX_VALUE) return fallback("volume over Enhanced limit: "+volume); // return/history are int bounded.

        WorldServer world=(WorldServer)nativeWorld;
        ServerThreadGuard.capture();
        NBTTagCompound tile=toNative(block);
        if (block.getNbtData()!=null && tile==null) return fallback("tile NBT conversion unavailable");
        net.minecraft.block.Block nativeBlock=net.minecraft.block.Block.getBlockById(block.getId());
        if (nativeBlock==null) return fallback("unregistered legacy ID="+block.getId());
        if (tile!=null && !nativeBlock.hasTileEntity(block.getData())) return fallback("NBT supplied for non-tile block");
        ForgeChunkWriter writer=new ForgeChunkWriter(); ChunkSynchronizer sync=new ChunkSynchronizer();
        long started=System.nanoTime(), planned=System.nanoTime();
        List<PreparedChunkChange> changes=ConstantFillPlanner.prepareChanged(world,(CuboidRegion)region,block,tile);
        long filtered=System.nanoTime();
        if (!reserveLimit(session,(int)volume)) return fallback("BlockChangeLimiter state unavailable");
        OverdriveChangeSet history=new OverdriveChangeSet(session.getWorld(),64L<<20);
        session.setChangeSet(history);
        int affected=0, raw=0, nativeCount=0, dense=0, sparse=0,sparsePackets=0,chunkPackets=0; long historyNanos=0, commitNanos=0,
                lightingNanos=0, syncNanos=0, preparedBytes=0;
        for(PreparedChunkChange change:changes) preparedBytes+=change.estimatedBytes();
        String phaseName="history/commit/synchronization"; PreparedChunkChange active=null; boolean mutationStarted=false;
        try { for(PreparedChunkChange change:changes) { active=change;
            long phase=System.nanoTime();
            captureHistory(world,history,change,block);
            historyNanos+=System.nanoTime()-phase; phase=System.nanoTime(); mutationStarted=true;
            ChunkCommitResult result=writer.commit(world,change,SideEffectPolicy.RAW);
            history.commitPrepared(result.getChangedBlocks());
            commitNanos+=System.nanoTime()-phase; lightingNanos+=result.getLightingNanos();
            raw+=result.getRawBlocks(); nativeCount+=result.getNativeBlocks(); dense+=result.getDenseSections();
            sparse+=result.getTouchedSections()-result.getDenseSections();
            Chunk chunk=world.getChunkFromChunkCoords(change.getChunkX(),change.getChunkZ());
            phase=System.nanoTime(); ChunkSynchronizer.Strategy strategy=sync.synchronize(world,chunk,change,result,512);
            if(strategy==ChunkSynchronizer.Strategy.CHUNK)chunkPackets++;else if(strategy==ChunkSynchronizer.Strategy.MULTI_BLOCK)sparsePackets++;
            syncNanos+=System.nanoTime()-phase; affected+=result.getChangedBlocks();
        }} catch(Throwable failure){
            history.seal();
            long total=System.nanoTime()-started;
            String where=active==null?"": " chunk="+active.getChunkX()+","+active.getChunkZ();
            OverdriveEditSummary summary=new OverdriveEditSummary("//set","accelerated",volume,affected,changes.size(),dense,sparse,raw,nativeCount,
                    planned-started,filtered-planned,historyNanos,commitNanos,lightingNanos,syncNanos,total,sparsePackets,chunkPackets,0,preparedBytes,false,phaseName,failure.toString());
            OverdriveSummaries.publish(summary);
            OverdriveLog.error("//set failed: phase="+phaseName+where+" mutationStarted="+mutationStarted,failure);
            if(failure instanceof Error)throw (Error)failure;
            if(failure instanceof RuntimeException)throw (RuntimeException)failure;
            throw new RuntimeException(failure);
        }
        history.seal();long total=System.nanoTime()-started;
        OverdriveEditSummary summary=new OverdriveEditSummary("//set","accelerated",volume,affected,changes.size(),dense,sparse,raw,nativeCount,
                planned-started,filtered-planned,historyNanos,commitNanos,lightingNanos,syncNanos,total,sparsePackets,chunkPackets,0,preparedBytes,true,null,null);
        OverdriveSummaries.publish(summary);
        OverdriveLog.info("//set summary emission proof: snapshot published");
        OverdriveLog.info(summary.format());
        Stage4HookStatus.acceleratedInvocations.incrementAndGet();
        return affected;
    }

    private static Object readField(Object owner,String name) throws ReflectiveOperationException {
        Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static BaseBlock resolveComposedConstant(Object candidate) throws ReflectiveOperationException {
        if(candidate instanceof BaseBlock)return (BaseBlock)candidate;
        if(candidate instanceof Pattern)return ConstantPatternResolver.resolve((Pattern)candidate);
        if(candidate!=null&&"com.sk89q.worldedit.function.pattern.BlockPattern".equals(candidate.getClass().getName()))
            return (BaseBlock)candidate.getClass().getMethod("getBlock").invoke(candidate);
        return null;
    }

    private static void writeField(Object owner,String name,Object value) throws ReflectiveOperationException {
        Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner,value);
    }

    /** Reserve the same attempted-position budget consumed by Enhanced's limiter. */
    private static boolean reserveLimit(EditSession session,int attempts) throws MaxChangedBlocksException {
        int limit=session.getBlockChangeLimit();
        if(limit<0)return true;
        try {
            Field limiterField=EditSession.class.getDeclaredField("changeLimiter"); limiterField.setAccessible(true);
            Object limiter=limiterField.get(session);
            Method getCount=limiter.getClass().getMethod("getCount");
            int count=((Integer)getCount.invoke(limiter)).intValue();
            if((long)count+attempts>limit)throw new MaxChangedBlocksException(limit);
            Field countField=limiter.getClass().getDeclaredField("count"); countField.setAccessible(true);
            countField.setInt(limiter,count+attempts);
            return true;
        } catch(MaxChangedBlocksException expected) { throw expected; }
        catch(Exception incompatible) { OverdriveLog.warn("limiter handshake unavailable: {}",incompatible.toString()); return false; }
    }

    private static Integer fallback(String reason) {
        Stage4HookStatus.fallbackInvocations.incrementAndGet();
        Stage4HookStatus.lastFallbackReason=reason;
        OverdriveLog.info("//set fallback: {}",reason);
        return null;
    }

    private static String type(Object value) { return value==null ? "null" : value.getClass().getName(); }
    private static String value(Object value) { return value==null ? "null" : type(value)+"("+value+")"; }

    private static void captureHistory(final WorldServer world,final OverdriveChangeSet history, PreparedChunkChange change, final BaseBlock current) {
        for(int sy=0;sy<16;sy++) if(change.getSection(sy)!=null) {
            final int section=sy;
            change.getSection(sy).forEach(new com.glowingfederal.worldeditoverdrive.backend.SectionChange.Visitor(){
                public void visit(int index,int packed) {
                    int x=(change.getChunkX()<<4)|com.glowingfederal.worldeditoverdrive.backend.SectionChange.localX(index);
                    int y=(section<<4)|com.glowingfederal.worldeditoverdrive.backend.SectionChange.localY(index);
                    int z=(change.getChunkZ()<<4)|com.glowingfederal.worldeditoverdrive.backend.SectionChange.localZ(index);
                    net.minecraft.block.Block old=world.getBlock(x,y,z);int from=net.minecraft.block.Block.getIdFromBlock(old)<<4|world.getBlockMetadata(x,y,z);
                    history.prepare(x,y,z,from,current.getId()<<4|current.getData(),tile(world.getTileEntity(x,y,z)),current.getNbtData());
                }});
        }
    }

    private static CompoundTag tile(net.minecraft.tileentity.TileEntity tile){if(tile==null)return null;try{
        NBTTagCompound nativeTag=new NBTTagCompound();tile.writeToNBT(nativeTag);Class<?> type=Class.forName("com.sk89q.worldedit.forge.NBTConverter");
        Method method=type.getDeclaredMethod("fromNative",NBTTagCompound.class);method.setAccessible(true);return (CompoundTag)method.invoke(null,nativeTag);
    }catch(Exception incompatible){throw new IllegalStateException("tile history translation unavailable",incompatible);}}

    private static NBTTagCompound toNative(BaseBlock block) {
        CompoundTag tag=block.getNbtData(); if(tag==null)return null;
        try { Class<?> type=Class.forName("com.sk89q.worldedit.forge.NBTConverter");
            Method method=type.getDeclaredMethod("toNative",CompoundTag.class); method.setAccessible(true);
            NBTTagCompound nativeTag=(NBTTagCompound)method.invoke(null,tag); nativeTag.setString("id",block.getNbtId()); return nativeTag;
        } catch(Exception incompatible) { OverdriveLog.warn("tile translation unavailable: {}",incompatible.toString()); return null; }
    }

}
