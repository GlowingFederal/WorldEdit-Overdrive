package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.PlayerDirection;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.blocks.BlockType;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Deadline-aware, Enhanced-6.3.0-specific resumptions for its two unbounded reorder operations. */
public final class EnhancedReorderYieldBridge {
    private static volatile boolean blockPlacerHook,stage3Hook;
    private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<Long>();
    private static final Map<Object,Stage3State> STAGE3=new WeakHashMap<Object,Stage3State>();
    static void blockPlacerHookInstalled(){blockPlacerHook=true;updateSupport();}
    static void stage3HookInstalled(){stage3Hook=true;updateSupport();}
    private static void updateSupport(){PasteHookStatus.incrementalCommitSupported=blockPlacerHook&&stage3Hook;}
    public static boolean isSupported(){return blockPlacerHook&&stage3Hook;}
    static void beginSlice(long deadline){DEADLINE.set(Long.valueOf(deadline));}
    static void endSlice(){DEADLINE.remove();}
    private static boolean expired(){Long deadline=DEADLINE.get();return deadline!=null&&System.nanoTime()>=deadline.longValue();}

    @SuppressWarnings("unchecked")
    public static Operation resumeBlockPlacer(Object operation)throws WorldEditException{
        try{
            Iterator<Map.Entry<BlockVector,BaseBlock>> iterator=(Iterator<Map.Entry<BlockVector,BaseBlock>>)field(operation,"iterator").get(operation);
            Extent extent=(Extent)field(operation,"extent").get(operation);
            do {if(!iterator.hasNext())return null;Map.Entry<BlockVector,BaseBlock> entry=iterator.next();extent.setBlock(entry.getKey(),entry.getValue());} while(!expired());
            return iterator.hasNext()?(Operation)operation:null;
        }catch(WorldEditException e){throw e;}catch(Exception e){throw new IllegalStateException("Enhanced BlockMapEntryPlacer shape changed",e);}
    }

    @SuppressWarnings("unchecked")
    public static Operation resumeStage3(Object operation)throws WorldEditException{
        try{
            Object reorder=field(operation,"this$0").get(operation);Stage3State state;
            synchronized(STAGE3){state=STAGE3.get(operation);if(state==null){state=new Stage3State();Iterable<Map.Entry<BlockVector,BaseBlock>> entries=(Iterable<Map.Entry<BlockVector,BaseBlock>>)field(reorder,"stage3").get(reorder);for(Map.Entry<BlockVector,BaseBlock> entry:entries){state.blocks.add(entry.getKey());state.types.put(entry.getKey(),entry.getValue());}STAGE3.put(operation,state);}}
            Extent extent=(Extent)reorder.getClass().getMethod("getExtent").invoke(reorder);
            do {if(state.blocks.isEmpty()){field(reorder,"stage1").get(reorder).getClass().getMethod("clear").invoke(field(reorder,"stage1").get(reorder));field(reorder,"stage2").get(reorder).getClass().getMethod("clear").invoke(field(reorder,"stage2").get(reorder));field(reorder,"stage3").get(reorder).getClass().getMethod("clear").invoke(field(reorder,"stage3").get(reorder));synchronized(STAGE3){STAGE3.remove(operation);}return null;}placeDependencyChain(extent,state);}while(!expired());
            PasteHookStatus.commitOperationRemaining.set(state.blocks.size());return (Operation)operation;
        }catch(WorldEditException e){throw e;}catch(Exception e){throw new IllegalStateException("Enhanced Stage3Committer shape changed",e);}
    }

    private static void placeDependencyChain(Extent extent,Stage3State state)throws WorldEditException{
        BlockVector current=state.blocks.iterator().next();Deque<BlockVector> walked=new ArrayDeque<BlockVector>();
        while(true){walked.addFirst(current);BaseBlock block=state.types.get(current);int type=block.getType(),data=block.getData();
            if((type==BlockID.WOODEN_DOOR||type==BlockID.IRON_DOOR)&&(data&8)==0){BlockVector upper=current.add(0,1,0).toBlockVector();if(state.blocks.contains(upper)&&!walked.contains(upper))walked.addFirst(upper);}
            else if(type==BlockID.MINECART_TRACKS||type==BlockID.POWERED_RAIL||type==BlockID.DETECTOR_RAIL||type==BlockID.ACTIVATOR_RAIL){BlockVector lower=current.add(0,-1,0).toBlockVector();if(state.blocks.contains(lower)&&!walked.contains(lower))walked.addFirst(lower);}
            PlayerDirection attachment=BlockType.getAttachment(type,data);if(attachment==null)break;current=current.add(attachment.vector()).toBlockVector();if(!state.blocks.contains(current)||walked.contains(current))break;
        }
        for(BlockVector point:walked){extent.setBlock(point,state.types.get(point));state.blocks.remove(point);}
    }
    private static Field field(Object owner,String name)throws Exception{Class<?> type=owner.getClass();while(type!=null){try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}
    private static final class Stage3State{final Set<BlockVector> blocks=new HashSet<BlockVector>();final Map<BlockVector,BaseBlock> types=new HashMap<BlockVector,BaseBlock>();}
    private EnhancedReorderYieldBridge(){}
}
