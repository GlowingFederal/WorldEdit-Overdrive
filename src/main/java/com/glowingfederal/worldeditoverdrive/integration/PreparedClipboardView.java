package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.util.Location;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable, paged snapshot. Only the server-owned builder can populate it before publication. */
public final class PreparedClipboardView {
    static final int PAGE_SHIFT=10,PAGE_SIZE=1<<PAGE_SHIFT,PAGE_MASK=PAGE_SIZE-1;
    public static final class EntitySnapshot {
        public final Location location; public final BaseEntity state;
        public EntitySnapshot(Location location,BaseEntity state){this.location=location;this.state=new BaseEntity(state);}
    }
    private static final class Page {
        final int[] cells;Map<Integer,BaseBlock> auxiliary;
        Page(int size){cells=new int[size*5];}
    }
    private static final class EntityPages extends AbstractList<EntitySnapshot> {
        final List<EntitySnapshot[]> pages=new ArrayList<EntitySnapshot[]>();int count;
        void append(EntitySnapshot value){if((count&PAGE_MASK)==0)pages.add(new EntitySnapshot[PAGE_SIZE]);pages.get(count>>PAGE_SHIFT)[count&PAGE_MASK]=value;count++;}
        public EntitySnapshot get(int index){if(index<0||index>=count)throw new IndexOutOfBoundsException();return pages.get(index>>PAGE_SHIFT)[index&PAGE_MASK];}
        public int size(){return count;}
    }
    static final class Builder {
        final int minX,minY,minZ,sizeX,sizeY,sizeZ,volume;final Page[] pages;final EntityPages entities=new EntityPages();
        long bytes,air;int captured,tiles,pagesAllocated;boolean sealed;
        Builder(int minX,int minY,int minZ,int sizeX,int sizeY,int sizeZ){
            if(sizeX<0||sizeY<0||sizeZ<0||(sizeZ>0&&(long)sizeX*sizeY>Integer.MAX_VALUE/sizeZ))throw new IllegalArgumentException("invalid clipboard snapshot");
            long count=(long)sizeX*sizeY*sizeZ;
            this.minX=minX;this.minY=minY;this.minZ=minZ;this.sizeX=sizeX;this.sizeY=sizeY;this.sizeZ=sizeZ;volume=(int)count;
            pages=new Page[(int)((count+PAGE_MASK)>>PAGE_SHIFT)];bytes=128L+count*20L+pages.length*128L;
        }
        void set(int index,int id,int data,int x,int y,int z,BaseBlock auxiliary){
            if(sealed||index!=captured)throw new IllegalStateException("clipboard capture is not append-only");
            int pageIndex=index>>PAGE_SHIFT,offset=(index&PAGE_MASK)*5;Page page=pages[pageIndex];
            if(page==null){page=new Page(Math.min(PAGE_SIZE,volume-(pageIndex<<PAGE_SHIFT)));pages[pageIndex]=page;pagesAllocated++;}
            page.cells[offset]=id;page.cells[offset+1]=data;page.cells[offset+2]=x;page.cells[offset+3]=y;page.cells[offset+4]=z;
            if(auxiliary!=null){if(page.auxiliary==null)page.auxiliary=new HashMap<Integer,BaseBlock>();page.auxiliary.put(Integer.valueOf(index&PAGE_MASK),auxiliary);tiles++;bytes+=128L;}
            if(id==0)air++;captured++;
        }
        EntitySnapshot addEntity(Location location,BaseEntity state){if(sealed)throw new IllegalStateException("clipboard capture sealed");EntitySnapshot value=new EntitySnapshot(location,state);if((entities.size()&PAGE_MASK)==0)bytes+=32L+PAGE_SIZE*8L;entities.append(value);bytes+=512L;return value;}
        PreparedClipboardView seal(){if(sealed||captured!=volume)throw new IllegalStateException("clipboard capture incomplete or already sealed");sealed=true;return new PreparedClipboardView(this);}
    }
    private final int minX,minY,minZ,sizeX,sizeY,sizeZ,volume,tiles;
    private final Page[] pages;private final List<EntitySnapshot> entities;private final long bytes;
    private PreparedClipboardView(Builder builder){minX=builder.minX;minY=builder.minY;minZ=builder.minZ;sizeX=builder.sizeX;sizeY=builder.sizeY;sizeZ=builder.sizeZ;volume=builder.volume;tiles=builder.tiles;pages=builder.pages;entities=Collections.unmodifiableList(builder.entities);bytes=builder.bytes;}

    /** Compatibility constructor; the live paste owner uses the incremental builder instead. */
    public PreparedClipboardView(int minX,int minY,int minZ,int sizeX,int sizeY,int sizeZ,int[] ids,int[] data,
            int[] destinationX,int[] destinationY,int[] destinationZ,Map<Integer,BaseBlock> auxiliaryBlocks,List<EntitySnapshot> entities) {
        Builder builder=new Builder(minX,minY,minZ,sizeX,sizeY,sizeZ);int count=builder.volume;
        if(ids.length!=count||data.length!=count||destinationX.length!=count||destinationY.length!=count||destinationZ.length!=count)throw new IllegalArgumentException("invalid clipboard snapshot");
        PasteNbtSizer sizer=new PasteNbtSizer();
        for(int i=0;i<count;i++){BaseBlock auxiliary=auxiliaryBlocks.get(Integer.valueOf(i));builder.set(i,ids[i],data[i],destinationX[i],destinationY[i],destinationZ[i],auxiliary==null?null:new BaseBlock(auxiliary));if(auxiliary!=null){sizer.start(auxiliary.getNbtData());builder.bytes+=sizer.resume(Long.MAX_VALUE,Integer.MAX_VALUE);}}
        for(EntitySnapshot entity:entities){builder.addEntity(entity.location,entity.state);sizer.start(entity.state.getNbtData());builder.bytes+=sizer.resume(Long.MAX_VALUE,Integer.MAX_VALUE);}
        builder.sealed=true;this.minX=minX;this.minY=minY;this.minZ=minZ;this.sizeX=sizeX;this.sizeY=sizeY;this.sizeZ=sizeZ;volume=count;tiles=builder.tiles;pages=builder.pages;this.entities=Collections.unmodifiableList(builder.entities);bytes=builder.bytes;
    }
    public int index(int x,int y,int z){return ((y-minY)*sizeZ+(z-minZ))*sizeX+(x-minX);}
    private int value(int index,int component){return pages[index>>PAGE_SHIFT].cells[(index&PAGE_MASK)*5+component];}
    public int idAt(int index){return value(index,0);}public int dataAt(int index){return value(index,1);}
    public int destinationX(int index){return value(index,2);}public int destinationY(int index){return value(index,3);}public int destinationZ(int index){return value(index,4);}
    public BaseBlock blockAt(int index){Page page=pages[index>>PAGE_SHIFT];BaseBlock auxiliary=page.auxiliary==null?null:page.auxiliary.get(Integer.valueOf(index&PAGE_MASK));return auxiliary==null?new BaseBlock(idAt(index),dataAt(index)):new BaseBlock(auxiliary);}
    public int getSizeX(){return sizeX;}public int getSizeY(){return sizeY;}public int getSizeZ(){return sizeZ;}public int getVolume(){return volume;}
    public int tileCount(){return tiles;}public List<EntitySnapshot> entities(){return entities;}public long estimatedBytes(){return bytes;}
}
