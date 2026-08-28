package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.function.mask.AbstractExtentMask;
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
            Object source=field(copy,"source"),destination=field(copy,"destination"),sourceFunction=field(copy,"sourceFunction");
            if(source==null||source.getClass()!=BlockTransformExtent.class)return Result.no("unsupported source extent graph: "+type(source));
            Object clipboardSource=((AbstractDelegateExtent)source).getExtent();
            if(!(clipboardSource instanceof Clipboard))return Result.no("unsupported clipboard implementation: "+type(clipboardSource));
            if(destination==null||destination.getClass()!=EditSession.class)return Result.no("unsupported destination extent: "+type(destination));
            if(sourceFunction!=null)return Result.no("source mutation is not PasteBuilder semantics");
            if(((Boolean)field(copy,"removingEntities")).booleanValue())return Result.no("entity removal is not PasteBuilder semantics");
            if(field(copy,"currentTransform")!=null||field(copy,"lastVisitor")!=null||((Integer)field(copy,"affected")).intValue()!=0)
                return Result.no("ForwardExtentCopy traversal has already started");
            Mask mask=(Mask)field(copy,"sourceMask");
            boolean ignoreAir=mask instanceof ExistingBlockMask;
            if(!ignoreAir&&mask!=Masks.alwaysTrue())return Result.no("unsupported source mask: "+type(mask));
            if(ignoreAir&&((AbstractExtentMask)mask).getExtent()!=clipboardSource)
                return Result.no("ExistingBlockMask is not bound to the PasteBuilder clipboard");
            Clipboard clipboard=(Clipboard)clipboardSource;
            Region region=(Region)field(copy,"region"); Vector from=(Vector)field(copy,"from"),to=(Vector)field(copy,"to");
            if(region!=clipboard.getRegion()||!from.equals(clipboard.getOrigin()))return Result.no("ForwardExtentCopy is not the standard PasteBuilder graph");
            Transform transform=(Transform)field(copy,"transform");
            if(((BlockTransformExtent)source).getTransform()!=transform)return Result.no("coordinate and block-state transforms do not share the PasteBuilder transform");
            int repetitions=((Integer)field(copy,"repetitions")).intValue();
            if(repetitions<0)return Result.no("invalid negative repetitions: "+repetitions);
            // Enhanced 6.3.0 has no entity/biome flags: resume() unconditionally queues
            // ExtentEntityCopy, while it never constructs a biome visitor.
            return Result.yes(new PasteOperationAdapter(clipboard,region,from,to,transform,
                    (EditSession)destination,ignoreAir,true,false,repetitions));
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
