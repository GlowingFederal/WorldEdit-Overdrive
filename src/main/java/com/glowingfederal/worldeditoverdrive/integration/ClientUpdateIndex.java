package com.glowingfederal.worldeditoverdrive.integration;

/** Exact unsigned-short membership; the native list still owns packet and tile
 * ordering. One lazy 8 KiB index per watcher used by accelerated edits. */
public final class ClientUpdateIndex {
    private final long[] bits=new long[1024];
    private int indexed;
    public void reset(){if(indexed==0)return;java.util.Arrays.fill(bits,0);indexed=0;}
    public boolean duplicate(short[] positions,int count,short position){
        if(count==0&&indexed!=0)reset();
        // Ordinary updates may have appended between accelerated placements.
        while(indexed<count){int key=positions[indexed++]&65535;bits[key>>>6]|=1L<<key;}
        int key=position&65535;boolean found=(bits[key>>>6]&(1L<<key))!=0;
        PlacementMutationBridge.indexed(found?0:count);
        return found;
    }
}
