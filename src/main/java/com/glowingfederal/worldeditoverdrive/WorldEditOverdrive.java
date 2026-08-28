package com.glowingfederal.worldeditoverdrive;

import com.sk89q.worldedit.WorldEdit;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveConfiguration;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveCoordinator;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveTickHandler;
import com.glowingfederal.worldeditoverdrive.integration.Stage4HookStatus;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

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
    private final OverdriveConfiguration configuration=OverdriveConfiguration.defaults();
    private final OverdriveTickHandler ticks = new OverdriveTickHandler();

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        ModContainer worldEdit=Loader.instance().getIndexedModList().get("worldedit");
        String fmlVersion=worldEdit==null ? "not present" : worldEdit.getVersion();
        String apiVersion=WorldEdit.getVersion();
        OverdriveLog.info("Server diagnostics active");
        OverdriveLog.info("detected WorldEdit mod: {}",fmlVersion);
        if (!sameVersion(apiVersion,fmlVersion))
            OverdriveLog.info("version diagnostics: FML={}, WorldEdit API={}",fmlVersion,apiVersion);
        String reason=Stage4HookStatus.hookInstalled ? "" : Stage4HookStatus.editSessionSeen
                ? " (target descriptor did not match)" : " (EditSession target not transformed)";
        OverdriveLog.info("WorldEdit {} detected; Stage 4 //set hook {}{}",
                fmlVersion,Stage4HookStatus.hookInstalled ? "ACTIVE" : "INACTIVE",reason);
        FMLCommonHandler.instance().bus().register(ticks);
    }

    private static boolean sameVersion(String api,String fml) {
        return api!=null && fml!=null && !api.toLowerCase().contains("unknown") && api.equals(fml);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event){event.registerServerCommand(new OverdriveCommand(this));}

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        coordinator = new OverdriveCoordinator(configuration);
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
    public OverdriveConfiguration getConfiguration(){return configuration;}
}
