package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.mutation.ChunkMutationBatch;
import com.glowingfederal.worldeditoverdrive.mutation.MutationPlanBuilder;
import com.glowingfederal.worldeditoverdrive.mutation.RegionMutationPlan;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.masks.Mask;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import java.util.ArrayList;
import java.util.List;

/** Narrow method-entry adapters for the pinned Enhanced 6.3.0 EditSession API. */
public final class EnhancedCommandBridge {
    private EnhancedCommandBridge(){}

    /** Explicit outcome consumed by the entry transformer. */
    public static final class Decision {
        private static final Decision NOT_HANDLED=new Decision(false,0);
        private final boolean handled;
        private final int result;
        private Decision(boolean handled,int result){this.handled=handled;this.result=result;}
        public static Decision handled(int result){return new Decision(true,result);}
        public static Decision notHandled(){return NOT_HANDLED;}
        public boolean isHandled(){return handled;}
        public int getResult(){
            if(!handled)throw new IllegalStateException("A not-handled bridge decision has no result");
            return result;
        }
    }
    public static Decision replace(EditSession session,Region region,Mask mask,Pattern pattern)throws MaxChangedBlocksException{
        CommandHookStatus.replaceBridgeInvoked.incrementAndGet();long wall=System.nanoTime(),snap=wall;
        List<Vector> positions=new ArrayList<Vector>();List<BaseBlock> old=new ArrayList<BaseBlock>();
        for(Vector p:region)if(mask.matches(p)){positions.add(p.toBlockVector());old.add(new BaseBlock(session.getBlock(p)));}
        CommandHookStatus.lastOperationSnapshotMillis.set(ms(snap));int changed=apply(session,positions,pattern);
        complete("replace",wall,CommandHookStatus.replaceAccelerated);return Decision.handled(changed);
    }
    public static Decision geometry(EditSession session,Region region,Pattern pattern,int kind)throws MaxChangedBlocksException{
        CommandHookStatus.geometryBridgeInvoked.incrementAndGet();long wall=System.nanoTime();CuboidRegion box=CuboidRegion.makeCuboid(region);Vector min=box.getMinimumPoint(),max=box.getMaximumPoint();List<Vector> out=new ArrayList<Vector>();
        if(kind==2){Vector c=region.getCenter();for(int y=(int)c.getY();y<=c.getBlockY();y++)for(int z=(int)c.getZ();z<=c.getBlockZ();z++)for(int x=(int)c.getX();x<=c.getBlockX();x++)out.add(new Vector(x,y,z));}
        else for(int y=min.getBlockY();y<=max.getBlockY();y++)for(int z=min.getBlockZ();z<=max.getBlockZ();z++)for(int x=min.getBlockX();x<=max.getBlockX();x++)if(x==min.getBlockX()||x==max.getBlockX()||z==min.getBlockZ()||z==max.getBlockZ()||(kind==1&&(y==min.getBlockY()||y==max.getBlockY())))out.add(new Vector(x,y,z));
        int changed=apply(session,out,pattern);complete(kind==0?"walls":kind==1?"faces":"center",wall,CommandHookStatus.geometryAccelerated);return Decision.handled(changed);
    }
    public static Decision overlay(EditSession session,Region region,Pattern pattern)throws MaxChangedBlocksException{
        CommandHookStatus.overlayBridgeInvoked.incrementAndGet();long wall=System.nanoTime(),snap=wall;Vector min=region.getMinimumPoint(),max=region.getMaximumPoint();List<Vector> out=new ArrayList<Vector>();
        for(int z=min.getBlockZ();z<=max.getBlockZ();z++)for(int x=min.getBlockX();x<=max.getBlockX();x++)for(int y=max.getBlockY();y>=min.getBlockY();y--)if(session.getBlock(new Vector(x,y,z)).getId()!=0){if(y<session.getWorld().getMaxY())out.add(new Vector(x,y+1,z));break;}
        CommandHookStatus.lastOperationSnapshotMillis.set(ms(snap));int changed=apply(session,out,pattern);complete("overlay",wall,CommandHookStatus.overlayAccelerated);return Decision.handled(changed);
    }
    public static Decision naturalize(EditSession session,Region region)throws MaxChangedBlocksException{
        CommandHookStatus.overlayBridgeInvoked.incrementAndGet();long wall=System.nanoTime();Vector min=region.getMinimumPoint(),max=region.getMaximumPoint();int changed=0;
        for(int z=min.getBlockZ();z<=max.getBlockZ();z++)for(int x=min.getBlockX();x<=max.getBlockX();x++){int depth=0;for(int y=max.getBlockY();y>=min.getBlockY();y--){Vector p=new Vector(x,y,z);if(session.getBlock(p).getId()==0){depth=0;continue;}BaseBlock b=depth==0?new BaseBlock(2):depth<4?new BaseBlock(3):new BaseBlock(1);if(session.setBlock(p,b))changed++;depth++;}}
        complete("naturalize",wall,CommandHookStatus.naturalizeAccelerated);return Decision.handled(changed);
    }
    public static Decision stack(EditSession session,Region region,Vector dir,int count,boolean copyAir)throws MaxChangedBlocksException{
        CommandHookStatus.copyMoveBridgeInvoked.incrementAndGet();long wall=System.nanoTime(),snap=wall;Vector min=region.getMinimumPoint(),max=region.getMaximumPoint(),size=max.subtract(min).add(1,1,1),step=dir.multiply(size);List<Vector> src=new ArrayList<Vector>();List<BaseBlock> blocks=new ArrayList<BaseBlock>();
        for(Vector p:region){BaseBlock b=new BaseBlock(session.getBlock(p));if(copyAir||b.getId()!=0){src.add(p.toBlockVector());blocks.add(b);}}CommandHookStatus.lastOperationSnapshotMillis.set(ms(snap));int changed=0;long commit=System.nanoTime();
        for(int n=1;n<=count;n++)for(int i=0;i<src.size();i++)if(session.setBlock(src.get(i).add(step.multiply(n)),blocks.get(i)))changed++;CommandHookStatus.lastOperationCommitMillis.set(ms(commit));complete("stack",wall,CommandHookStatus.stackAccelerated);return Decision.handled(changed);
    }
    public static Decision move(EditSession session,Region region,Vector dir,int distance,boolean copyAir,BaseBlock leave)throws MaxChangedBlocksException{
        CommandHookStatus.copyMoveBridgeInvoked.incrementAndGet();long wall=System.nanoTime(),snap=wall;List<Vector> src=new ArrayList<Vector>();List<BaseBlock> blocks=new ArrayList<BaseBlock>();for(Vector p:region){BaseBlock b=new BaseBlock(session.getBlock(p));if(copyAir||b.getId()!=0){src.add(p.toBlockVector());blocks.add(b);}}CommandHookStatus.lastOperationSnapshotMillis.set(ms(snap));Vector delta=dir.multiply(distance);BaseBlock replacement=leave==null?new BaseBlock(0):leave;int changed=0;long commit=System.nanoTime();
        // Complete capture precedes clearing; destinations are written second, matching Enhanced's overlap buffer.
        for(Vector p:src)if(session.setBlock(p,replacement))changed++;for(int i=0;i<src.size();i++)if(session.setBlock(src.get(i).add(delta),blocks.get(i)))changed++;CommandHookStatus.lastOperationCommitMillis.set(ms(commit));complete("move",wall,CommandHookStatus.moveAccelerated);return Decision.handled(changed);
    }
    private static int apply(final EditSession session,final List<Vector> positions,Pattern pattern)throws MaxChangedBlocksException{long planned=System.nanoTime();int[] indices=new int[positions.size()];for(int i=0;i<indices.length;i++)indices[i]=i;RegionMutationPlan plan=MutationPlanBuilder.chunkLocal(indices,new MutationPlanBuilder.Coordinates(){public int x(int i){return positions.get(i).getBlockX();}public int z(int i){return positions.get(i).getBlockZ();}});CommandHookStatus.lastOperationPlanMillis.set(ms(planned));long commit=System.nanoTime();int changed=0;for(ChunkMutationBatch batch:plan.getBatches())for(int n=0;n<batch.size();n++){Vector p=positions.get(batch.sourceIndex(n));if(session.setBlock(p,pattern.next(p)))changed++;}CommandHookStatus.lastOperationCommitMillis.set(ms(commit));return changed;}
    private static void complete(String type,long wall,java.util.concurrent.atomic.AtomicLong counter){counter.incrementAndGet();CommandHookStatus.lastOperationType=type;CommandHookStatus.lastOperationFallbackReason="none";CommandHookStatus.lastOperationWallMillis.set(ms(wall));}
    private static long ms(long start){return(System.nanoTime()-start)/1000000L;}
}
