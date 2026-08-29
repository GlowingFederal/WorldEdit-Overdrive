package com.glowingfederal.worldeditoverdrive.integration;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeQueueDrainControllerTest {
    @Test public void neverExceedsEmergencyCeiling(){NativeQueueDrainController c=new NativeQueueDrainController();for(int i=0;i<100;i++)c.drained(c.limit(),1L,30000000L);assertTrue(c.limit()<=NativeQueueDrainController.HARD_MAX_MUTATIONS);}
    @Test public void expensiveDrainContractsAggressively(){NativeQueueDrainController c=new NativeQueueDrainController();c.drained(256,80000000L,25000000L);assertTrue(c.limit()<=128);}
    @Test public void cheapDrainGrowsConservatively(){NativeQueueDrainController c=new NativeQueueDrainController();c.drained(256,1000000L,25000000L);assertEquals(320,c.limit());}
}
