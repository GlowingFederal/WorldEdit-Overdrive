package com.glowingfederal.worldeditoverdrive;

import com.sk89q.worldedit.WorldEdit;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;

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

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        FMLLog.info("WorldEdit Overdrive loaded with WorldEdit %s.", WorldEdit.getVersion());
    }
}
