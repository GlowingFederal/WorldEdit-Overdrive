package com.glowingfederal.worldeditoverdrive.snapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.NBTTagCompound;

/** Published immutable data only: no world, Chunk, storage, or TileEntity references. */
public final class ChunkSourceSnapshot {
    private final int chunkX,chunkZ;private final Map<Integer,SnapshotSection> sections;
    private final Map<Integer,NBTTagCompound> tiles;private final byte[] biomes;private final long bytes;
    ChunkSourceSnapshot(int x,int z,Map<Integer,SnapshotSection> sections,Map<Integer,NBTTagCompound> tiles,byte[] biomes){
        chunkX=x;chunkZ=z;this.sections=Collections.unmodifiableMap(new HashMap<Integer,SnapshotSection>(sections));
        Map<Integer,NBTTagCompound> copy=new HashMap<Integer,NBTTagCompound>();for(Map.Entry<Integer,NBTTagCompound> e:tiles.entrySet())copy.put(e.getKey(),(NBTTagCompound)e.getValue().copy());
        this.tiles=Collections.unmodifiableMap(copy);this.biomes=biomes==null?null:biomes.clone();long n=80+(this.biomes==null?0:this.biomes.length);
        for(SnapshotSection s:sections.values())n+=24+s.estimatedBytes();for(NBTTagCompound tag:copy.values())n+=64+tag.toString().length()*2L;bytes=n;
    }
    public int chunkX(){return chunkX;}public int chunkZ(){return chunkZ;}
    public SnapshotSection section(int sectionY){return sections.get(sectionY);}
    public Map<Integer,SnapshotSection> sections(){return sections;}
    public NBTTagCompound tile(int packedLocal){NBTTagCompound tag=tiles.get(packedLocal);return tag==null?null:(NBTTagCompound)tag.copy();}
    public Map<Integer,NBTTagCompound> tiles(){Map<Integer,NBTTagCompound> copy=new HashMap<Integer,NBTTagCompound>();for(Integer p:tiles.keySet())copy.put(p,tile(p));return Collections.unmodifiableMap(copy);}
    public byte[] biomes(){return biomes==null?null:biomes.clone();}public long estimatedBytes(){return bytes;}
    public static int packLocal(int x,int y,int z){if(x<0||x>15||y<0||y>255||z<0||z>15)throw new IllegalArgumentException("local position");return y<<8|z<<4|x;}
}
