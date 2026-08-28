package com.glowingfederal.worldeditoverdrive.integration;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.Name("WorldEditOverdriveStage4")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions("com.glowingfederal.worldeditoverdrive.integration")
public final class OverdriveLoadingPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass(){return new String[]{EditSessionSetTransformer.class.getName()};}
    public String getModContainerClass(){return null;}
    public String getSetupClass(){return null;}
    public void injectData(Map<String,Object> data){}
    public String getAccessTransformerClass(){return null;}
}
