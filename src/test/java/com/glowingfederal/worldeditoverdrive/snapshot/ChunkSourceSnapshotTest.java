package com.glowingfederal.worldeditoverdrive.snapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChunkSourceSnapshotTest {
    @Test public void selectsCompactSectionEncodingsAndPreservesLegacyState(){
        char[] air=new char[256];assertEquals(SnapshotSection.Encoding.AIR,SnapshotSection.create(3,3,air).encoding());
        char[] high=new char[256];java.util.Arrays.fill(high,(char)(4095<<4|15));SnapshotSection uniform=SnapshotSection.create(2,2,high);
        assertEquals(SnapshotSection.Encoding.HOMOGENEOUS,uniform.encoding());assertEquals(4095,uniform.blockId(0,2,0));assertEquals(15,uniform.metadata(0,2,0));
        high[7]=1;assertEquals(SnapshotSection.Encoding.DENSE,SnapshotSection.create(2,2,high).encoding());
    }
    @Test public void snapshotIsPartialImmutableAndAccountsMemory(){
        char[] row=new char[256];row[0]=(char)(300<<4|7);Map<Integer,SnapshotSection> source=new HashMap<Integer,SnapshotSection>();source.put(4,SnapshotSection.create(5,5,row));
        ChunkSourceSnapshot snapshot=new ChunkSourceSnapshot(-2,-3,source,Collections.<Integer,net.minecraft.nbt.NBTTagCompound>emptyMap(),null);source.clear();
        assertEquals(-2,snapshot.chunkX());assertEquals(300,snapshot.section(4).blockId(0,5,0));assertNull(snapshot.section(0));assertTrue(snapshot.estimatedBytes()>0);
        try{snapshot.sections().clear();fail("mutable");}catch(UnsupportedOperationException expected){}
    }
}
