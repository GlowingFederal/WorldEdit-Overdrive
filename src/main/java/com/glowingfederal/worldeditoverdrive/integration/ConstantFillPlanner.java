package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.backend.PreparedChunkChange;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.regions.CuboidRegion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;

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
}
