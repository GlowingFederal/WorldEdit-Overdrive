package com.glowingfederal.worldeditoverdrive;

import com.sk89q.worldedit.WorldEdit;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveConfiguration;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveCoordinator;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveTickHandler;

@Mod(
        modid = WorldEditOverdrive.MOD_ID,
        name = WorldEditOverdrive.MOD_NAME,
        version = WorldEditOverdrive.VERSION,
        dependencies = "required-after:worldedit",
        acceptableRemoteVersions = "*"
)
public final class WorldEditOverdrive {
    public static final String MOD_ID = "worldeditoverdrive";
    public static final String MOD_NAME = "WorldEdit Overdrive";
    public static final String VERSION = "1.0.0";
    private volatile OverdriveCoordinator coordinator;
    private final OverdriveTickHandler ticks = new OverdriveTickHandler();

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        FMLLog.info("WorldEdit Overdrive loaded with WorldEdit %s.", WorldEdit.getVersion());
        FMLCommonHandler.instance().bus().register(ticks);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        coordinator = new OverdriveCoordinator(OverdriveConfiguration.defaults());
        ticks.setCoordinator(coordinator);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ticks.setCoordinator(null);
        OverdriveCoordinator current = coordinator;
        coordinator = null;
        if (current != null) current.shutdown();
        com.glowingfederal.worldeditoverdrive.backend.ServerThreadGuard.clear();
    }

    /** Internal Stage 3 API; command/session integration intentionally does not use it yet. */
    public OverdriveCoordinator getCoordinator() { return coordinator; }
}
