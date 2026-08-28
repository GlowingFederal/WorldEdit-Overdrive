package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.mask.Masks;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.regions.Region;
import java.lang.reflect.Field;

/** Strict adapter for the graph emitted by Enhanced 6.3.0 PasteBuilder. */
public final class PasteOperationAdapter {
    public final Clipboard clipboard; public final Region region; public final Vector sourceOrigin,destinationOrigin;
    public final Transform transform; public final EditSession destination; public final boolean ignoreAir,copyEntities,copyBiomes;
    public final int repetitions;
    private PasteOperationAdapter(Clipboard clipboard,Region region,Vector sourceOrigin,Vector destinationOrigin,
            Transform transform,EditSession destination,boolean ignoreAir,boolean copyEntities,boolean copyBiomes,int repetitions) {
        this.clipboard=clipboard;this.region=region;this.sourceOrigin=sourceOrigin;this.destinationOrigin=destinationOrigin;
        this.transform=transform;this.destination=destination;this.ignoreAir=ignoreAir;this.copyEntities=copyEntities;
        this.copyBiomes=copyBiomes;this.repetitions=repetitions;
    }
    public static Result recognize(ForwardExtentCopy copy) {
        try {
            if(copy==null||copy.getClass()!=ForwardExtentCopy.class)return Result.no("custom ForwardExtentCopy subclass");
            Object source=field(copy,"source"),destination=field(copy,"destination"),sourceFunction=field(copy,"sourceFunction"),filter=field(copy,"filterFunction");
            Object clipboardSource=source;
            if(source instanceof BlockTransformExtent)clipboardSource=((AbstractDelegateExtent)source).getExtent();
            if(!(clipboardSource instanceof Clipboard))return Result.no("unsupported clipboard implementation: "+type(clipboardSource));
            if(!(destination instanceof EditSession))return Result.no("opaque custom extent: "+type(destination));
            if(sourceFunction!=null)return Result.no("source mutation is not PasteBuilder semantics");
            if(filter!=null)return Result.no("unsupported dynamic paste filter: "+type(filter));
            Mask mask=(Mask)field(copy,"sourceMask");
            boolean ignoreAir=mask instanceof ExistingBlockMask;
            if(!ignoreAir&&mask!=Masks.alwaysTrue())return Result.no("unsupported source mask: "+type(mask));
            Clipboard clipboard=(Clipboard)clipboardSource;
            Region region=(Region)field(copy,"region"); Vector from=(Vector)field(copy,"from"),to=(Vector)field(copy,"to");
            if(region!=clipboard.getRegion()||!from.equals(clipboard.getOrigin()))return Result.no("ForwardExtentCopy is not the standard PasteBuilder graph");
            return Result.yes(new PasteOperationAdapter(clipboard,region,from,to,(Transform)field(copy,"transform"),
                    (EditSession)destination,ignoreAir,((Boolean)field(copy,"copyEntities")).booleanValue(),
                    ((Boolean)field(copy,"copyBiomes")).booleanValue(),((Integer)field(copy,"repetitions")).intValue()));
        } catch(Throwable incompatible) { return Result.no("runtime paste graph shape unavailable: "+incompatible.toString()); }
    }
    private static Object field(Object owner,String name)throws Exception {Field f=ForwardExtentCopy.class.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static String type(Object value){return value==null?"null":value.getClass().getName();}
    public static final class Result { public final PasteOperationAdapter adapter; public final String reason;
        private Result(PasteOperationAdapter adapter,String reason){this.adapter=adapter;this.reason=reason;}
        static Result yes(PasteOperationAdapter adapter){return new Result(adapter,null);} static Result no(String reason){return new Result(null,reason);}
        public boolean isRecognized(){return adapter!=null;}
    }
}
