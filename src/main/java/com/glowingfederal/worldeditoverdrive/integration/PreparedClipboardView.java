package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.blocks.BaseBlock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable source-volume snapshot. WorldEdit objects are never consulted by a worker. */
public final class PreparedClipboardView {
    private final int minX,minY,minZ,sizeX,sizeY,sizeZ,originX,originY,originZ;
    private final int[] ids,data,relativeX,relativeY,relativeZ;
    private final Map<Integer,BaseBlock> payloads;
    public PreparedClipboardView(int minX,int minY,int minZ,int sizeX,int sizeY,int sizeZ,int originX,int originY,int originZ,
            int[] ids,int[] data,int[] relativeX,int[] relativeY,int[] relativeZ,Map<Integer,BaseBlock> payloads) {
        long volume=(long)sizeX*sizeY*sizeZ;
        if(sizeX<0||sizeY<0||sizeZ<0||volume>Integer.MAX_VALUE||ids==null||data==null||relativeX==null||relativeY==null||relativeZ==null||
                ids.length!=(int)volume||data.length!=ids.length||relativeX.length!=ids.length||relativeY.length!=ids.length||relativeZ.length!=ids.length)
            throw new IllegalArgumentException("invalid clipboard snapshot");
        this.minX=minX;this.minY=minY;this.minZ=minZ;this.sizeX=sizeX;this.sizeY=sizeY;this.sizeZ=sizeZ;
        this.originX=originX;this.originY=originY;this.originZ=originZ;this.ids=ids.clone();this.data=data.clone();
        this.relativeX=relativeX.clone();this.relativeY=relativeY.clone();this.relativeZ=relativeZ.clone();
        this.payloads=payloads==null||payloads.isEmpty()?Collections.<Integer,BaseBlock>emptyMap():Collections.unmodifiableMap(new HashMap<Integer,BaseBlock>(payloads));
    }
    public int idAt(int index){return ids[index];} public int dataAt(int index){return data[index];}
    public int relativeXAt(int index){return relativeX[index];} public int relativeYAt(int index){return relativeY[index];} public int relativeZAt(int index){return relativeZ[index];}
    public BaseBlock blockAt(int index){BaseBlock block=payloads.get(Integer.valueOf(index));return block==null?new BaseBlock(ids[index],data[index]):new BaseBlock(block);}
    public boolean isAirAt(int index){return ids[index]==0;}
    public int getMinX(){return minX;} public int getMinY(){return minY;} public int getMinZ(){return minZ;}
    public int getSizeX(){return sizeX;} public int getSizeY(){return sizeY;} public int getSizeZ(){return sizeZ;}
    public int getOriginX(){return originX;} public int getOriginY(){return originY;} public int getOriginZ(){return originZ;}
    public int getVolume(){return ids.length;} public int getPayloadCount(){return payloads.size();}
    public long estimatedBytes(){return 64L+ids.length*20L+payloads.size()*256L;}
}
