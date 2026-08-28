package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.regions.CuboidRegion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Allocation-light, world-read-free conversion of a cuboid constant fill into chunk buffers. */
public final class ConstantFillPlanner {
    private ConstantFillPlanner() { }

    public static long volume(CuboidRegion region) {
        Vector min=region.getMinimumPoint(), max=region.getMaximumPoint();
        return Math.multiplyExact(Math.multiplyExact((long)max.getBlockX()-min.getBlockX()+1,
                (long)max.getBlockY()-min.getBlockY()+1), (long)max.getBlockZ()-min.getBlockZ()+1);
    }

    public static List<PreparedChunkChange> prepare(CuboidRegion region, BaseBlock block) {
        return prepare(region, block, null);
    }

    public static List<PreparedChunkChange> prepare(CuboidRegion region, BaseBlock block, NBTTagCompound tile) {
        Vector min=region.getMinimumPoint(), max=region.getMaximumPoint();
        List<PreparedChunkChange> result=new ArrayList<PreparedChunkChange>();
        for(int cz=min.getBlockZ()>>4;cz<=max.getBlockZ()>>4;cz++) for(int cx=min.getBlockX()>>4;cx<=max.getBlockX()>>4;cx++) {
            int x0=Math.max(min.getBlockX(),cx<<4)-(cx<<4), x1=Math.min(max.getBlockX(),cx<<4|15)-(cx<<4);
            int z0=Math.max(min.getBlockZ(),cz<<4)-(cz<<4), z1=Math.min(max.getBlockZ(),cz<<4|15)-(cz<<4);
            PreparedChunkChange.Builder builder=new PreparedChunkChange.Builder(cx,cz).fill(x0,min.getBlockY(),z0,x1,max.getBlockY(),z1,
                    block.getId(),block.getData());
            if(tile!=null) for(int y=min.getBlockY();y<=max.getBlockY();y++) for(int z=z0;z<=z1;z++)
                for(int x=x0;x<=x1;x++) builder.setTileNbt(x,y,z,tile);
            result.add(builder.build());
        }
        return result;
    }

    /** Server-thread planning which retains only placements that Forge can observe as different. */
    public static List<PreparedChunkChange> prepareChanged(WorldServer world, CuboidRegion region,
            BaseBlock block, NBTTagCompound tile) {
        Vector min=region.getMinimumPoint(), max=region.getMaximumPoint();
        List<PreparedChunkChange> result=new ArrayList<PreparedChunkChange>();
        for(int cz=min.getBlockZ()>>4;cz<=max.getBlockZ()>>4;cz++) for(int cx=min.getBlockX()>>4;cx<=max.getBlockX()>>4;cx++) {
            Chunk chunk=world.getChunkFromChunkCoords(cx,cz);
            int x0=Math.max(min.getBlockX(),cx<<4), x1=Math.min(max.getBlockX(),cx<<4|15);
            int z0=Math.max(min.getBlockZ(),cz<<4), z1=Math.min(max.getBlockZ(),cz<<4|15);
            PreparedChunkChange.Builder builder=new PreparedChunkChange.Builder(cx,cz);
            for(int y=min.getBlockY();y<=max.getBlockY();y++) for(int z=z0;z<=z1;z++) for(int x=x0;x<=x1;x++) {
                int lx=x-(cx<<4), lz=z-(cz<<4);
                boolean same=net.minecraft.block.Block.getIdFromBlock(chunk.getBlock(lx,y,lz))==block.getId()
                        && chunk.getBlockMetadata(lx,y,lz)==block.getData();
                if(same && tile!=null) same=sameTile(world.getTileEntity(x,y,z),tile,x,y,z);
                if(same) continue;
                builder.setBlock(lx,y,lz,block.getId(),block.getData());
                if(tile!=null) builder.setTileNbt(lx,y,lz,tile);
            }
            PreparedChunkChange change=builder.build();
            if(change.getChangedBlockCount()!=0) result.add(change);
        }
        return result;
    }

    private static boolean sameTile(TileEntity existing,NBTTagCompound wanted,int x,int y,int z) {
        if(existing==null)return false;
        NBTTagCompound actual=new NBTTagCompound(); existing.writeToNBT(actual);
        NBTTagCompound expected=(NBTTagCompound)wanted.copy();
        expected.setInteger("x",x); expected.setInteger("y",y); expected.setInteger("z",z);
        return actual.equals(expected);
    }
}
