package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.SideEffectPolicy;
import java.util.Collections;
import net.minecraft.world.WorldServer;

/** Source-compatible name for the original one-phase Stage 3 API. */
public final class OverdriveOperation extends OperationPlan {
    OverdriveOperation(long id,WorldServer world,SideEffectPolicy policy,OverdriveCoordinator coordinator){
        super(id,world,policy,coordinator,"synthetic-chunk","unspecified","stage-3-compatible",
                PreparationClass.PURE,Collections.singletonList(new CommitPhase("block-mutation",0,true,
                SchedulingUnitType.CHUNK,false,true)),FinalizationIntent.CHANGED_CHUNKS_ONCE);
    }
}
