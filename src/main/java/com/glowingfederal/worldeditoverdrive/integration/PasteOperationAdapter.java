package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
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
    public final BlockTransformExtent transformedSource;
    public final int repetitions;
    private PasteOperationAdapter(Clipboard clipboard,Region region,Vector sourceOrigin,Vector destinationOrigin,
            Transform transform,BlockTransformExtent transformedSource,EditSession destination,boolean ignoreAir,boolean copyEntities,boolean copyBiomes,int repetitions) {
        this.clipboard=clipboard;this.region=region;this.sourceOrigin=sourceOrigin;this.destinationOrigin=destinationOrigin;
        this.transform=transform;this.transformedSource=transformedSource;this.destination=destination;this.ignoreAir=ignoreAir;this.copyEntities=copyEntities;
        this.copyBiomes=copyBiomes;this.repetitions=repetitions;
    }
    public static Result recognize(ForwardExtentCopy copy) {
        Graph graph=new Graph(copy);
        try {
            if(copy==null||copy.getClass()!=ForwardExtentCopy.class)return graph.no("operation is not concrete ForwardExtentCopy");
            Object source=field(copy,"source"),destination=field(copy,"destination"),sourceFunction=field(copy,"sourceFunction");
            graph.source=source;graph.destination=destination;graph.sourceFunction=sourceFunction;
            graph.sourceMask=field(copy,"sourceMask");graph.transform=field(copy,"transform");graph.currentTransform=field(copy,"currentTransform");
            if(source==null||source.getClass()!=BlockTransformExtent.class)return graph.no("unsupported source extent: expected standard BlockTransformExtent, found "+type(source));
            Object clipboardSource=((AbstractDelegateExtent)source).getExtent();
            graph.sourceDelegate=clipboardSource;graph.sourceIsClipboard=clipboardSource instanceof Clipboard;
            if(!(clipboardSource instanceof Clipboard))return graph.no("unsupported source delegate: BlockTransformExtent delegates to "+type(clipboardSource));
            if(destination==null||destination.getClass()!=EditSession.class)return graph.no("destination is not standard EditSession: "+type(destination));
            if(sourceFunction!=null)return graph.no("unsupported sourceFunction: "+type(sourceFunction));
            if(((Boolean)field(copy,"removingEntities")).booleanValue())return graph.no("unsupported entity removal: standard PasteBuilder does not remove source entities");
            if(field(copy,"currentTransform")!=null||field(copy,"lastVisitor")!=null||((Integer)field(copy,"affected")).intValue()!=0)
                return graph.no("ForwardExtentCopy traversal has already started");
            Mask mask=(Mask)field(copy,"sourceMask");
            boolean ignoreAir=mask instanceof ExistingBlockMask;
            if(!ignoreAir&&mask!=Masks.alwaysTrue())return graph.no("unsupported sourceMask: "+type(mask));
            if(ignoreAir&&((AbstractExtentMask)mask).getExtent()!=clipboardSource)
                return graph.no("ignore-air mask uses unexpected extent: "+type(((AbstractExtentMask)mask).getExtent()));
            Clipboard clipboard=(Clipboard)clipboardSource;
            Region region=(Region)field(copy,"region"); Vector from=(Vector)field(copy,"from"),to=(Vector)field(copy,"to");
            Region clipboardRegion=clipboard.getRegion();
            // BlockArrayClipboard deliberately returns a clone from getRegion(). PasteBuilder
            // receives one such clone, so object identity can never prove this standard edge.
            // The clone preserves concrete shape, bounds, area, and world; require all of them.
            if(!sameStandardRegion(region,clipboardRegion))return graph.no("operation region does not match clipboard region by class, bounds, area, and world");
            if(from==null||!from.equals(clipboard.getOrigin()))return graph.no("source anchor does not match clipboard origin");
            Transform transform=(Transform)field(copy,"transform");
            if(((BlockTransformExtent)source).getTransform()!=transform)return graph.no("coordinate and block-state transforms do not share the PasteBuilder transform");
            int repetitions=((Integer)field(copy,"repetitions")).intValue();
            if(repetitions!=1)return graph.no("unsupported repetitions: standard PasteBuilder starts at 1, found "+repetitions);
            // Enhanced 6.3.0 has no entity/biome flags: resume() unconditionally queues
            // ExtentEntityCopy, while it never constructs a biome visitor.
            return Result.yes(new PasteOperationAdapter(clipboard,region,from,to,transform,(BlockTransformExtent)source,
                    (EditSession)destination,ignoreAir,true,false,repetitions),graph.describe(null));
        } catch(Throwable incompatible) { return graph.no("runtime paste graph shape unavailable: "+incompatible.getClass().getName()+": "+incompatible.getMessage()); }
    }
    public Eligibility accelerationEligibility(){
        if(clipboard.getClass()!=BlockArrayClipboard.class)return Eligibility.defer("clipboard is not concrete BlockArrayClipboard");
        return Eligibility.accelerate();
    }
    public static final class Eligibility {public enum Kind{ACCELERATE,DEFER_VANILLA,VANILLA_FALLBACK} public final Kind kind;public final String reason;
        private Eligibility(Kind kind,String reason){this.kind=kind;this.reason=reason;}static Eligibility accelerate(){return new Eligibility(Kind.ACCELERATE,null);}
        static Eligibility defer(String reason){return new Eligibility(Kind.DEFER_VANILLA,reason);}}
    private static boolean sameStandardRegion(Region actual,Region expected){
        if(actual==null||expected==null||actual.getClass()!=expected.getClass())return false;
        if(!actual.getMinimumPoint().equals(expected.getMinimumPoint())||!actual.getMaximumPoint().equals(expected.getMaximumPoint()))return false;
        if(actual.getArea()!=expected.getArea())return false;
        Object actualWorld=actual.getWorld(),expectedWorld=expected.getWorld();return actualWorld==expectedWorld;
    }
    private static Object field(Object owner,String name)throws Exception {Field f=ForwardExtentCopy.class.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static String type(Object value){return value==null?"null":value.getClass().getName();}
    private static final class Graph {
        Object source,destination,sourceMask,sourceFunction,transform,currentTransform,sourceDelegate;boolean sourceIsClipboard;
        Graph(Object copy){ }
        Result no(String reject){return Result.no(reject,describe(reject));}
        String describe(String reject){return "source="+type(source)+" source.delegate="+type(sourceDelegate)+
                " destination="+type(destination)+" sourceMask="+type(sourceMask)+" sourceFunction="+type(sourceFunction)+
                " transform="+type(transform)+" currentTransform="+type(currentTransform)+
                " sourceDelegateIsClipboard="+sourceIsClipboard+(reject==null?"":" reject="+reject);}
    }
    public static final class Result { public final PasteOperationAdapter adapter; public final String reason,diagnostic;
        private Result(PasteOperationAdapter adapter,String reason,String diagnostic){this.adapter=adapter;this.reason=reason;this.diagnostic=diagnostic;}
        static Result yes(PasteOperationAdapter adapter,String diagnostic){return new Result(adapter,null,diagnostic);} static Result no(String reason,String diagnostic){return new Result(null,reason,diagnostic);}
        public boolean isRecognized(){return adapter!=null;}
    }
}
