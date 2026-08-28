package com.glowingfederal.worldeditoverdrive.snapshot;

import com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/** Bounded server-thread capture for one touched chunk range. */
public final class ChunkSnapshotCapture {
    private ChunkSnapshotCapture(){}
    public static ChunkSourceSnapshot capture(WorldServer world,int chunkX,int chunkZ,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,SnapshotRequirements req){
        ServerThreadGuard.assertServerThread();if(world==null||req==null)throw new NullPointerException();
        if(minX<0||maxX>15||minZ<0||maxZ>15||minY<0||maxY>255||minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("range");
        Chunk chunk=world.getChunkFromChunkCoords(chunkX,chunkZ);Map<Integer,SnapshotSection> sections=new HashMap<Integer,SnapshotSection>();
        if(req.includes(SnapshotRequirements.Channel.BLOCK_STATE))for(int sy=minY>>4;sy<=maxY>>4;sy++){
            int lo=Math.max(minY,sy<<4)-(sy<<4),hi=Math.min(maxY,sy<<4|15)-(sy<<4);char[] states=new char[(hi-lo+1)*256];
            for(int y=lo;y<=hi;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){int id=net.minecraft.block.Block.getIdFromBlock(chunk.getBlock(x,(sy<<4)|y,z));int meta=chunk.getBlockMetadata(x,(sy<<4)|y,z);states[((y-lo)<<8)|(z<<4)|x]=(char)(id<<4|meta);}
            sections.put(sy,SnapshotSection.create(lo,hi,states));}
        Map<Integer,NBTTagCompound> tiles=new HashMap<Integer,NBTTagCompound>();if(req.includes(SnapshotRequirements.Channel.TILE_NBT))
            for(Object value:chunk.chunkTileEntityMap.values()){TileEntity tile=(TileEntity)value;int lx=tile.xCoord-(chunkX<<4),lz=tile.zCoord-(chunkZ<<4);
                if(lx>=minX&&lx<=maxX&&lz>=minZ&&lz<=maxZ&&tile.yCoord>=minY&&tile.yCoord<=maxY){NBTTagCompound tag=new NBTTagCompound();tile.writeToNBT(tag);tag.setInteger("x",lx);tag.setInteger("y",tile.yCoord);tag.setInteger("z",lz);tiles.put(ChunkSourceSnapshot.packLocal(lx,tile.yCoord,lz),tag);}}
        byte[] biomes=req.includes(SnapshotRequirements.Channel.BIOME)?chunk.getBiomeArray().clone():null;
        return new ChunkSourceSnapshot(chunkX,chunkZ,sections,tiles,biomes);
    }
}
