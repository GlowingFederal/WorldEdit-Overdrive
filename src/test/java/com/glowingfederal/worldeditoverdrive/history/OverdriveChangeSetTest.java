package com.glowingfederal.worldeditoverdrive.history;

import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import java.util.Iterator;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverdriveChangeSetTest {
    @Test public void exposesOnlyCommittedPrefixLazilyInBothOrders(){
        OverdriveChangeSet set=new OverdriveChangeSet("test",1<<20);set.prepare(-17,9,33,1<<4,4095<<4|15,null,null);set.prepare(2,10,3,2<<4,3<<4,null,null);set.commitPrepared(1);set.seal();
        assertEquals(1,set.size());Iterator<Change> undo=set.backwardIterator();assertTrue(undo.hasNext());BlockChange change=(BlockChange)undo.next();assertEquals(-17,change.getPosition().getBlockX());assertEquals(4095,change.getCurrent().getId());assertFalse(undo.hasNext());
        assertEquals(-17,((BlockChange)set.forwardIterator().next()).getPosition().getBlockX());
    }
    @Test public void omitsUnchangedAndEnforcesMemoryBound(){
        OverdriveChangeSet set=new OverdriveChangeSet("test",128);set.prepare(0,0,0,16,16,null,null);assertEquals(0,set.preparedSize());
        try{for(int i=0;i<100;i++)set.prepare(i,0,0,0,16,null,null);fail("unbounded");}catch(HistoryLimitExceededException expected){assertTrue(set.estimatedBytes()<=128);}
    }
}
