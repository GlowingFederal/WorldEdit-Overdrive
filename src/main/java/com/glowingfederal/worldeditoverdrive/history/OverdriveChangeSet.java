package com.glowingfederal.worldeditoverdrive.history;

import com.boydti.fawe.object.changeset.FaweChangeSet;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BaseBiome;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Operation-owned primitive history. Change objects are allocated only as an iterator advances. */
public final class OverdriveChangeSet extends FaweChangeSet {
    public enum Encoding { PACKED_RAW }
    private int[] xz=new int[256];private byte[] y=new byte[256];private char[] before=new char[256],after=new char[256];
    private CompoundTag[] beforeTile=new CompoundTag[256],afterTile=new CompoundTag[256];
    private int prepared,committed;private final long limit;private long bytes=64;private boolean sealed;

    public OverdriveChangeSet(World world,long memoryLimit){super(world);if(memoryLimit<1)throw new IllegalArgumentException("memoryLimit");limit=memoryLimit;}
    public OverdriveChangeSet(String world,long memoryLimit){super(world);if(memoryLimit<1)throw new IllegalArgumentException("memoryLimit");limit=memoryLimit;}
    /** Adds an uncommitted record; unchanged states without tile transitions are omitted. */
    public void prepare(int x,int yy,int z,int from,int to,CompoundTag oldTile,CompoundTag newTile){
        if(sealed)throw new IllegalStateException("sealed");if(from==to&&equal(oldTile,newTile))return;ensure(prepared+1);
        xz[prepared]=(x<<16)|(z&0xffff);y[prepared]=(byte)yy;before[prepared]=(char)from;after[prepared]=(char)to;
        beforeTile[prepared]=oldTile;afterTile[prepared]=newTile;prepared++;bytes+=18+estimate(oldTile)+estimate(newTile);
        if(bytes>limit){prepared--;bytes-=18+estimate(oldTile)+estimate(newTile);throw new HistoryLimitExceededException(limit);}
    }
    /** Establishes ownership of the next successfully mutated prefix. */
    public void commitPrepared(int count){if(count<0||committed+count>prepared)throw new IllegalArgumentException("commit prefix");committed+=count;}
    public void discardUncommitted(){prepared=committed;}
    public void seal(){discardUncommitted();sealed=true;}
    public int preparedSize(){return prepared;}public int size(){return committed;}public long estimatedBytes(){return bytes;}
    public long getSizeInMemory(){return bytes;}public int getCompressedSize(){return bytes>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)bytes;}
    public Encoding encoding(){return Encoding.PACKED_RAW;}public long spillBytes(){return 0;}

    public void add(int x,int yy,int z,int from,int to){prepare(x,yy,z,from,to,null,null);commitPrepared(1);}
    public void addTileCreate(CompoundTag tag){throw new UnsupportedOperationException("record tiles with block state");}
    public void addTileRemove(CompoundTag tag){throw new UnsupportedOperationException("record tiles with block state");}
    public void addEntityRemove(CompoundTag tag){throw new UnsupportedOperationException("entities are not a Stage 5B channel");}
    public void addEntityCreate(CompoundTag tag){throw new UnsupportedOperationException("entities are not a Stage 5B channel");}
    public void addBiomeChange(int x,int z,BaseBiome from,BaseBiome to){throw new UnsupportedOperationException("biome history deferred");}
    public Iterator<Change> getIterator(boolean redo){return iterator(redo);}
    public Iterator<Change> forwardIterator(){return iterator(true);}public Iterator<Change> backwardIterator(){return iterator(false);}
    private Iterator<Change> iterator(final boolean redo){if(committed==0)return Collections.<Change>emptyList().iterator();return new Iterator<Change>(){
        private int cursor=redo?0:committed-1;public boolean hasNext(){return redo?cursor<committed:cursor>=0;}
        public Change next(){if(!hasNext())throw new NoSuchElementException();int i=cursor;cursor+=redo?1:-1;int xx=xz[i]>>16,zz=(short)xz[i];
            return new BlockChange(new BlockVector(xx,y[i]&255,zz),block(before[i]&0xffff,beforeTile[i]),block(after[i]&0xffff,afterTile[i]));}
        public void remove(){throw new UnsupportedOperationException();}};}
    private static BaseBlock block(int state,CompoundTag tile){return new BaseBlock(state>>>4,state&15,tile);}
    private void ensure(int wanted){if(wanted<=xz.length)return;int n=Math.max(wanted,xz.length<<1);xz=copy(xz,n);y=copy(y,n);before=copy(before,n);after=copy(after,n);beforeTile=copy(beforeTile,n);afterTile=copy(afterTile,n);}
    private static int[] copy(int[] a,int n){int[] b=new int[n];System.arraycopy(a,0,b,0,a.length);return b;}private static byte[] copy(byte[] a,int n){byte[] b=new byte[n];System.arraycopy(a,0,b,0,a.length);return b;}
    private static char[] copy(char[] a,int n){char[] b=new char[n];System.arraycopy(a,0,b,0,a.length);return b;}private static CompoundTag[] copy(CompoundTag[] a,int n){CompoundTag[] b=new CompoundTag[n];System.arraycopy(a,0,b,0,a.length);return b;}
    private static long estimate(CompoundTag t){return t==null?0:48+t.toString().length()*2L;}private static boolean equal(Object a,Object b){return a==b||(a!=null&&a.equals(b));}
}
