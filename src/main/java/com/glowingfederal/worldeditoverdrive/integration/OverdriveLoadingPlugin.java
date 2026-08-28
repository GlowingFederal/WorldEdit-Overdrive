package com.glowingfederal.worldeditoverdrive.integration;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import java.util.Map;

@IFMLLoadingPlugin.Name("WorldEditOverdriveStage4")
@IFMLLoadingPlugin.MCVersion("1.7.10")
// Do not exclude the whole integration package: the injected bridge must be loaded by
// LaunchClassLoader so that it can resolve WorldEdit and Minecraft classes.
@IFMLLoadingPlugin.TransformerExclusions({
        "com.glowingfederal.worldeditoverdrive.integration.OverdriveLoadingPlugin",
        "com.glowingfederal.worldeditoverdrive.integration.EditSessionSetTransformer",
        "com.glowingfederal.worldeditoverdrive.integration.Stage4HookStatus"
})
public final class OverdriveLoadingPlugin implements IFMLLoadingPlugin {
    public OverdriveLoadingPlugin() {
        Stage4HookStatus.corePluginLoaded=true;
        OverdriveLog.info("core plugin initialized");
    }
    public String[] getASMTransformerClass(){return new String[]{EditSessionSetTransformer.class.getName()};}
    public String getModContainerClass(){return null;}
    public String getSetupClass(){return null;}
    public void injectData(Map<String,Object> data){}
    public String getAccessTransformerClass(){return null;}
}
