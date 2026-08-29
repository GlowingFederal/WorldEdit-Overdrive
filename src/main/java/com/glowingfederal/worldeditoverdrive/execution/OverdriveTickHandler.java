package com.glowingfederal.worldeditoverdrive.execution;

import com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import com.glowingfederal.worldeditoverdrive.integration.DeferredPasteManager;

public final class OverdriveTickHandler {
    private volatile OverdriveCoordinator coordinator;
    private long tickStarted;
    public void setCoordinator(OverdriveCoordinator coordinator) { this.coordinator = coordinator; }
    @SubscribeEvent public void onServerTick(TickEvent.ServerTickEvent event) {
        if(event.phase==TickEvent.Phase.START){tickStarted=System.nanoTime();return;}
        if (event.phase != TickEvent.Phase.END) return;
        ServerThreadGuard.capture();
        DeferredPasteManager.tick(Math.max(0L,System.nanoTime()-tickStarted));
        OverdriveCoordinator current = coordinator;
        if (current != null) current.tick();
    }
}
