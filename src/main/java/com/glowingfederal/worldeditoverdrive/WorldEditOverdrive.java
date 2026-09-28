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
import com.glowingfederal.worldeditoverdrive.integration.EnhancedReorderYieldBridge;
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
    public void preInitialize(cpw.mods.fml.common.event.FMLPreInitializationEvent event){
        net.minecraftforge.common.config.Configuration config=new net.minecraftforge.common.config.Configuration(event.getSuggestedConfigurationFile());
        config.load();
        double maximum=config.get("pacing","serverBudgetMillis",30D,"Maximum shared paste/preparation/undo/redo work per tick (1-40 ms).").getDouble(30D);
        double margin=config.get("pacing","safetyMarginMillis",5D,"Reserved tick margin, increased automatically for variable external load (1-25 ms).").getDouble(5D);
        if(!Double.isFinite(maximum))maximum=30D;if(!Double.isFinite(margin))margin=5D;
        com.glowingfederal.worldeditoverdrive.integration.DeferredPasteManager.configurePacing((long)(Math.max(1D,Math.min(40D,maximum))*1000000D),(long)(Math.max(1D,Math.min(25D,margin))*1000000D));
        if(config.hasChanged())config.save();
    }

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        ModContainer worldEdit=Loader.instance().getIndexedModList().get("worldedit");
        String fmlVersion=worldEdit==null ? "not present" : worldEdit.getVersion();
        String apiVersion=WorldEdit.getVersion();
        OverdriveLog.info("Server diagnostics active");
        OverdriveLog.info("detected WorldEdit mod: {}",fmlVersion);
        if (!sameVersion(apiVersion,fmlVersion))
            OverdriveLog.info("version diagnostics: FML={}, WorldEdit API={}",fmlVersion,apiVersion);
        String reason=Stage4HookStatus.activeSetCommandHookInstalled ? "" : Stage4HookStatus.selectionCommandSeen
                ? " (SelectionCommand descriptor/anchors did not match)" : " (SelectionCommand target not transformed)";
        OverdriveLog.info("WorldEdit {} detected; Stage 4 //set hook {}{}",
                fmlVersion,Stage4HookStatus.activeSetCommandHookInstalled ? "ACTIVE" : "INACTIVE",reason);
        FMLCommonHandler.instance().bus().register(ticks);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(ticks);
    }

    private static boolean sameVersion(String api,String fml) {
        return api!=null && fml!=null && !api.toLowerCase().contains("unknown") && api.equals(fml);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event){EnhancedReorderYieldBridge.prepareHooks();event.registerServerCommand(new OverdriveCommand(this));}

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        coordinator = new OverdriveCoordinator(configuration);
        ticks.setCoordinator(coordinator);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        com.glowingfederal.worldeditoverdrive.integration.DeferredPasteManager.cancelAll();
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
