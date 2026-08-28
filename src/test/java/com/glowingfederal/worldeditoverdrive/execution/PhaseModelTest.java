package com.glowingfederal.worldeditoverdrive.execution;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhaseModelTest {
    @Test public void phaseCarriesBarrierAndSchedulingContract(){CommitPhase phase=new CommitPhase("stage-3",2,true,
            SchedulingUnitType.ORDERED_SEQUENCE,true,false);assertTrue(phase.hasBarrierAfter());
        assertTrue(phase.isStrictOrder());assertFalse(phase.allowsChunkBatching());assertEquals(2,phase.getOrder());}
}
