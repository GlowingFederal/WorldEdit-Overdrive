package com.glowingfederal.worldeditoverdrive.history;

import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.jnbt.ByteArrayTag;
import com.sk89q.jnbt.CompoundTag;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverdriveChangeSetTest {
    @Test public void exposesOnlyCommittedPrefixLazilyInBothOrders(){
        OverdriveChangeSet set=new OverdriveChangeSet("test",1<<20);set.prepare(-30000000,9,29999999,1<<4,4095<<4|15,null,null);set.prepare(2,10,3,2<<4,3<<4,null,null);set.commitPrepared(1);set.seal();
        assertEquals(1,set.size());Iterator<Change> undo=set.backwardIterator();assertTrue(undo.hasNext());BlockChange change=(BlockChange)undo.next();assertEquals(-30000000,change.getPosition().getBlockX());assertEquals(29999999,change.getPosition().getBlockZ());assertEquals(4095,change.getCurrent().getId());assertEquals(15,change.getCurrent().getData());assertFalse(undo.hasNext());
        assertEquals(-30000000,((BlockChange)set.forwardIterator().next()).getPosition().getBlockX());
    }
    @Test public void omitsUnchangedAndEnforcesMemoryBound(){
        OverdriveChangeSet set=new OverdriveChangeSet("test",128);set.prepare(0,0,0,16,16,null,null);assertEquals(0,set.preparedSize());
        try{for(int i=0;i<100;i++)set.prepare(i,0,0,0,16,null,null);fail("unbounded");}catch(HistoryLimitExceededException expected){assertTrue(set.estimatedBytes()<=128);}
    }
    @Test public void ownsDefensiveTileCopies(){
        byte[] payload={1,2,3};Map<String,com.sk89q.jnbt.Tag> values=new HashMap<String,com.sk89q.jnbt.Tag>();values.put("data",new ByteArrayTag(payload));
        OverdriveChangeSet set=new OverdriveChangeSet(1<<20);set.prepare(1,2,3,1<<4,2<<4,new CompoundTag(values),null);payload[0]=99;set.commitPrepared(1);set.seal();
        BlockChange change=(BlockChange)set.forwardIterator().next();assertArrayEquals(new byte[]{1,2,3},change.getPrevious().getNbtData().getByteArray("data"));
    }
}
