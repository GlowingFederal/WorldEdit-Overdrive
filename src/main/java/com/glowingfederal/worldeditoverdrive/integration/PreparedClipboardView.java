package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.util.Location;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable source-volume snapshot. WorldEdit-specific transformation is completed before publication. */
public final class PreparedClipboardView {
    public static final class EntitySnapshot {
        public final Location location; public final BaseEntity state;
        public EntitySnapshot(Location location,BaseEntity state){this.location=location;this.state=new BaseEntity(state);}
    }
    private final int minX,minY,minZ,sizeX,sizeY,sizeZ;
    private final int[] ids,data,destinationX,destinationY,destinationZ;
    private final Map<Integer,BaseBlock> auxiliaryBlocks;
    private final List<EntitySnapshot> entities;
    public PreparedClipboardView(int minX,int minY,int minZ,int sizeX,int sizeY,int sizeZ,int[] ids,int[] data,
            int[] destinationX,int[] destinationY,int[] destinationZ,Map<Integer,BaseBlock> auxiliaryBlocks,List<EntitySnapshot> entities) {
        long volume=(long)sizeX*sizeY*sizeZ;if(sizeX<0||sizeY<0||sizeZ<0||volume>Integer.MAX_VALUE||ids.length!=(int)volume||data.length!=(int)volume||destinationX.length!=(int)volume||destinationY.length!=(int)volume||destinationZ.length!=(int)volume)throw new IllegalArgumentException("invalid clipboard snapshot");
        this.minX=minX;this.minY=minY;this.minZ=minZ;this.sizeX=sizeX;this.sizeY=sizeY;this.sizeZ=sizeZ;
        this.ids=ids;this.data=data;this.destinationX=destinationX;this.destinationY=destinationY;this.destinationZ=destinationZ;
        this.auxiliaryBlocks=auxiliaryBlocks.isEmpty()?Collections.<Integer,BaseBlock>emptyMap():Collections.unmodifiableMap(new HashMap<Integer,BaseBlock>(auxiliaryBlocks));
        this.entities=Collections.unmodifiableList(entities);
    }
    public int index(int x,int y,int z){return ((y-minY)*sizeZ+(z-minZ))*sizeX+(x-minX);}
    public int idAt(int index){return ids[index];} public int dataAt(int index){return data[index];}
    public int destinationX(int index){return destinationX[index];} public int destinationY(int index){return destinationY[index];} public int destinationZ(int index){return destinationZ[index];}
    public BaseBlock blockAt(int index){BaseBlock auxiliary=auxiliaryBlocks.get(Integer.valueOf(index));return auxiliary==null?new BaseBlock(ids[index],data[index]):new BaseBlock(auxiliary);}
    public int getSizeX(){return sizeX;} public int getSizeY(){return sizeY;} public int getSizeZ(){return sizeZ;} public int getVolume(){return ids.length;}
    public int tileCount(){return auxiliaryBlocks.size();} public List<EntitySnapshot> entities(){return entities;}
    public long estimatedBytes(){long bytes=64L+ids.length*20L+auxiliaryBlocks.size()*128L+entities.size()*512L;for(BaseBlock b:auxiliaryBlocks.values())if(b.getNbtData()!=null)bytes+=b.getNbtData().toString().length()*2L;for(EntitySnapshot e:entities)if(e.state.getNbtData()!=null)bytes+=e.state.getNbtData().toString().length()*2L;return bytes;}
}
