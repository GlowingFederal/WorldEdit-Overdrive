package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.jnbt.CompoundTag;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable, allocation-free-per-read clipboard snapshot for preparation workers. */
public final class PreparedClipboardView {
    private final int minX,minY,minZ,sizeX,sizeY,sizeZ,originX,originY,originZ;
    private final int[] states; private final Map<Integer,CompoundTag> tiles;
    public PreparedClipboardView(int minX,int minY,int minZ,int sizeX,int sizeY,int sizeZ,int originX,int originY,int originZ,
            int[] states,Map<Integer,CompoundTag> tiles) {
        long volume=(long)sizeX*sizeY*sizeZ;if(sizeX<0||sizeY<0||sizeZ<0||volume>Integer.MAX_VALUE||states==null||states.length!=(int)volume)
            throw new IllegalArgumentException("invalid clipboard dimensions/state length");
        this.minX=minX;this.minY=minY;this.minZ=minZ;this.sizeX=sizeX;this.sizeY=sizeY;this.sizeZ=sizeZ;
        this.originX=originX;this.originY=originY;this.originZ=originZ;this.states=states.clone();
        this.tiles=tiles==null||tiles.isEmpty()?Collections.<Integer,CompoundTag>emptyMap():
                Collections.unmodifiableMap(new HashMap<Integer,CompoundTag>(tiles));
    }
    public int index(int x,int y,int z){int lx=x-minX,ly=y-minY,lz=z-minZ;if(lx<0||ly<0||lz<0||lx>=sizeX||ly>=sizeY||lz>=sizeZ)throw new IndexOutOfBoundsException();return (ly*sizeZ+lz)*sizeX+lx;}
    public int packedStateAt(int x,int y,int z){return states[index(x,y,z)];}
    public boolean isAirAt(int x,int y,int z){return (packedStateAt(x,y,z)>>>4)==0;}
    public CompoundTag tileAt(int x,int y,int z){return tiles.get(Integer.valueOf(index(x,y,z)));}
    public int getMinX(){return minX;} public int getMinY(){return minY;} public int getMinZ(){return minZ;}
    public int getSizeX(){return sizeX;} public int getSizeY(){return sizeY;} public int getSizeZ(){return sizeZ;}
    public int getOriginX(){return originX;} public int getOriginY(){return originY;} public int getOriginZ(){return originZ;}
    public int getVolume(){return states.length;} public boolean hasTiles(){return !tiles.isEmpty();}
    public long estimatedBytes(){return 36L+states.length*4L+tiles.size()*48L;}
}
