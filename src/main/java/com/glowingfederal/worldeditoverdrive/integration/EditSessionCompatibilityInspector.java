package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.EditSession;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;

/** Proves relevant Enhanced session behavior before any accelerated mutation. */
final class EditSessionCompatibilityInspector {
    enum Classification { SAFE, INCOMPATIBLE, UNKNOWN }
    static final class Result { final Classification classification;final String reason;Result(Classification c,String r){classification=c;reason=r;} }
    private static final String[] RELEVANT={"getMask","getBlockBag","isQueueEnabled","hasFastMode","getSurvivalExtent","getWorld","getChangeSet"};
    private static final String[] SAFE_EXTENTS={"com.sk89q.worldedit.extent.validation.BlockChangeLimiter","com.sk89q.worldedit.extent.MaskingExtent",
        "com.sk89q.worldedit.extent.ChangeSetExtent","com.sk89q.worldedit.extent.reorder.MultiStageReorder","com.sk89q.worldedit.extent.inventory.BlockBagExtent",
        "com.sk89q.worldedit.extent.validation.DataValidatorExtent","com.sk89q.worldedit.extent.cache.LastAccessExtentCache","com.sk89q.worldedit.extent.world.ChunkLoadingExtent",
        "com.sk89q.worldedit.extent.world.BlockQuirkExtent","com.sk89q.worldedit.extent.world.SurvivalModeExtent","com.sk89q.worldedit.extent.world.FastModeExtent"};
    private EditSessionCompatibilityInspector(){ }
    static Result inspect(EditSession session){
        if(session.getMask()!=null)return new Result(Classification.INCOMPATIBLE,"active mask");
        if(session.getBlockBag()!=null)return new Result(Classification.INCOMPATIBLE,"block bag active");
        if(session.getSurvivalExtent()!=null && session.getSurvivalExtent().hasToolUse())return new Result(Classification.INCOMPATIBLE,"survival mode active");
        if(session.isQueueEnabled())return new Result(Classification.INCOMPATIBLE,"reorder queue enabled");
        // Fast mode's reduced notifications are a subset of RAW's existing server-side policy.
        Class<?> type=session.getClass();
        if(type!=EditSession.class)try{for(String name:RELEVANT){Method m=type.getMethod(name);if(m.getDeclaringClass()!=EditSession.class)return new Result(Classification.UNKNOWN,"session overrides "+name);}}
        catch(Exception e){return new Result(Classification.UNKNOWN,"session inspection failed: "+e);}
        Result chain=inspectExtentChain(session);if(chain.classification!=Classification.SAFE)return chain;
        return new Result(Classification.SAFE,session.hasFastMode()?"safe fast mode":"safe standard mode");
    }
    private static Result inspectExtentChain(EditSession session){try{
        Field root=EditSession.class.getDeclaredField("bypassNone");root.setAccessible(true);Extent extent=(Extent)root.get(session);
        int depth=0;while(extent instanceof AbstractDelegateExtent){if(!safeExtent(extent.getClass().getName()))return new Result(Classification.UNKNOWN,"custom extent="+extent.getClass().getName());
            extent=((AbstractDelegateExtent)extent).getExtent();if(++depth>16)return new Result(Classification.UNKNOWN,"extent chain too deep");}
        return new Result(Classification.SAFE,"known Enhanced extent chain");
    }catch(Exception e){return new Result(Classification.UNKNOWN,"extent chain inspection failed: "+e);}}
    private static boolean safeExtent(String name){for(String safe:SAFE_EXTENTS)if(safe.equals(name))return true;return false;}
}
