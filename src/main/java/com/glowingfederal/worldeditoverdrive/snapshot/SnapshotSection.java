package com.glowingfederal.worldeditoverdrive.snapshot;

/** Immutable partial 16x16x16 section preserving the legacy (id << 4 | metadata) value. */
public abstract class SnapshotSection {
    public enum Encoding { AIR, HOMOGENEOUS, DENSE }
    private final int minY,maxY;
    SnapshotSection(int minY,int maxY){this.minY=minY;this.maxY=maxY;}
    public final int minLocalY(){return minY;} public final int maxLocalY(){return maxY;}
    public abstract int combined(int x,int y,int z);
    public final int blockId(int x,int y,int z){return combined(x,y,z)>>>4;}
    public final int metadata(int x,int y,int z){return combined(x,y,z)&15;}
    public abstract Encoding encoding(); public abstract long estimatedBytes();

    static SnapshotSection create(int minY,int maxY,char[] states){
        int first=states[0]&0xffff;boolean same=true;for(int i=1;i<states.length;i++)if((states[i]&0xffff)!=first){same=false;break;}
        if(same)return new Uniform(minY,maxY,first);
        return new Dense(minY,maxY,states.clone());
    }
    private static int index(int minY,int x,int y,int z){
        if(x<0||x>15||z<0||z>15||y<minY)throw new IndexOutOfBoundsException();return ((y-minY)<<8)|(z<<4)|x;
    }
    private static final class Uniform extends SnapshotSection {
        private final int state;Uniform(int a,int b,int state){super(a,b);this.state=state;}
        public int combined(int x,int y,int z){index(minLocalY(),x,y,z);if(y>maxLocalY())throw new IndexOutOfBoundsException();return state;}
        public Encoding encoding(){return state==0?Encoding.AIR:Encoding.HOMOGENEOUS;}
        public long estimatedBytes(){return 40;}
    }
    private static final class Dense extends SnapshotSection {
        private final char[] states;Dense(int a,int b,char[] states){super(a,b);this.states=states;}
        public int combined(int x,int y,int z){if(y>maxLocalY())throw new IndexOutOfBoundsException();return states[index(minLocalY(),x,y,z)]&0xffff;}
        public Encoding encoding(){return Encoding.DENSE;} public long estimatedBytes(){return 40L+states.length*2L;}
    }
}
