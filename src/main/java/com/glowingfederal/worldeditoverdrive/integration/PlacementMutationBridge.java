package com.glowingfederal.worldeditoverdrive.integration;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** Scoped to paced paste/replay calls. Native callbacks, lighting and Forge tile
 * lifecycle stay authoritative. No deferred repair or off-thread world access. */
public final class PlacementMutationBridge {
    private static volatile Thread owner;
    private static PlacementProfile active;
    private static Chunk cachedChunk;
    private static boolean skipLight;
    private static int lightX,lightY,lightZ;
    static void begin(PlacementProfile profile,int x,int y,int z,int id,boolean tile){
        if(active!=null)throw new IllegalStateException("nested placement scope");
        active=profile;cachedChunk=null;skipLight=false;profile.begin(x,y,z,id,tile);owner=Thread.currentThread();
    }
    static void end(){try{active.end();}finally{active=null;owner=null;cachedChunk=null;skipLight=false;}}
    public static boolean active(){return owner==Thread.currentThread()&&active!=null;}
    public static long enter(int category){
        if(!active()||!active.sampled)return 0;
        active.calls[category]++;return active.depth[category]++==0?System.nanoTime():-1;
    }
    public static long enterLight(EnumSkyBlock kind){return enter(kind==EnumSkyBlock.Sky?PlacementProfile.SKY:PlacementProfile.BLOCK_LIGHT);}
    public static void exit(long start,int category){
        if(start==0||!active()||!active.sampled)return;
        active.depth[category]--;if(start<0)return;long elapsed=System.nanoTime()-start;
        active.nanos[category]+=elapsed;active.maxima[category]=Math.max(active.maxima[category],elapsed);
    }
    public static void exitLight(long start,EnumSkyBlock kind){exit(start,kind==EnumSkyBlock.Sky?PlacementProfile.SKY:PlacementProfile.BLOCK_LIGHT);}
    public static Chunk chunk(World world,int x,int z){
        if(active()&&PlacementProfile.optimized&&cachedChunk!=null&&cachedChunk.isChunkLoaded&&cachedChunk.worldObj==world&&cachedChunk.xPosition==x&&cachedChunk.zPosition==z)return cachedChunk;
        long start=enter(PlacementProfile.LOOKUP);try{Chunk chunk=world.getChunkFromChunkCoords(x,z);if(active())cachedChunk=chunk;return chunk;}finally{exit(start,PlacementProfile.LOOKUP);}
    }
    /** Equivalent to pinned ForgeWorld's two world reads for the exact vanilla
     * server implementation, with one chunk lookup shared with the ensuing write. */
    public static com.sk89q.worldedit.blocks.BaseBlock lazy(com.sk89q.worldedit.forge.ForgeWorld forge,com.sk89q.worldedit.Vector position){
        if(!active()||!PlacementProfile.optimized||forge.getClass()!=com.sk89q.worldedit.forge.ForgeWorld.class)return null;
        World world=forge.getWorld();int x=position.getBlockX(),y=position.getBlockY(),z=position.getBlockZ();
        if(world.getClass()!=net.minecraft.world.WorldServer.class||y<0||y>=256||x<-30000000||z<-30000000||x>=30000000||z>=30000000)return null;
        Chunk chunk=chunk(world,x>>4,z>>4);
        if(chunk.getClass()!=Chunk.class)return null;
        return new com.sk89q.worldedit.blocks.LazyBlock(Block.getIdFromBlock(chunk.getBlock(x&15,y,z&15)),chunk.getBlockMetadata(x&15,y,z&15),forge,position);
    }
    public static boolean mutate(Chunk chunk,int x,int y,int z,Block next,int meta){
        boolean scoped=active();if(scoped)skipLight=false;
        // Identity-checked opaque vanilla cubes have no contextual opacity,
        // emission or callbacks that change the lighting inputs. Air, transparent,
        // emitting, tile and modded transitions always use native light checks.
        boolean eligible=scoped&&!active.tile&&PlacementProfile.optimized&&chunk.getClass()==Chunk.class&&chunk.worldObj.getClass()==net.minecraft.world.WorldServer.class&&opaque(next);
        Block old=scoped&&(active.sampled||eligible)?chunk.getBlock(x,y,z):null;
        if(scoped&&active.sampled){
            active.oldId=Block.getIdFromBlock(old);active.oldHeight=chunk.getHeightValue(x,z);
            active.newId=Block.getIdFromBlock(next);
            active.tile|=old.hasTileEntity(chunk.getBlockMetadata(x,y,z))||next.hasTileEntity(meta);
            int oldOpacity=old.getLightOpacity(chunk.worldObj,(chunk.xPosition<<4)+x,y,(chunk.zPosition<<4)+z);
            int nextOpacity=next.getLightOpacity(chunk.worldObj,(chunk.xPosition<<4)+x,y,(chunk.zPosition<<4)+z);
            active.transition=(old==Blocks.air?"air":oldOpacity>=15?"opaque":"transparent")+"->"+(next==Blocks.air?"air":nextOpacity>=15?"opaque":"transparent");
            if(old.getLightValue(chunk.worldObj,(chunk.xPosition<<4)+x,y,(chunk.zPosition<<4)+z)>0||next.getLightValue(chunk.worldObj,(chunk.xPosition<<4)+x,y,(chunk.zPosition<<4)+z)>0)active.transition+=" emissive";
        }
        long start=enter(PlacementProfile.CHUNK);
        try{
            boolean changed=chunk.func_150807_a(x,y,z,next,meta);
            if(scoped){
                skipLight=changed&&eligible&&opaque(old);lightX=(chunk.xPosition<<4)+x;lightY=y;lightZ=(chunk.zPosition<<4)+z;
                if(active.sampled)active.heightChanged=active.oldHeight!=chunk.getHeightValue(x,z);
            }
            return changed;
        }finally{exit(start,PlacementProfile.CHUNK);}
    }
    static boolean opaque(Block b){
        return b!=null&&((b==Blocks.stone&&b.getClass()==net.minecraft.block.BlockStone.class)
                ||(b==Blocks.dirt&&b.getClass()==net.minecraft.block.BlockDirt.class)
                ||(b==Blocks.sandstone&&b.getClass()==net.minecraft.block.BlockSandStone.class)
                ||((b==Blocks.cobblestone||b==Blocks.netherrack||b==Blocks.end_stone)&&b.getClass()==Block.class));
    }
    public static void preDestroy(Block block,World world,int x,int y,int z,int meta){long start=enter(PlacementProfile.CALLBACK);try{block.onBlockPreDestroy(world,x,y,z,meta);}finally{exit(start,PlacementProfile.CALLBACK);}}
    public static void breakBlock(Block block,World world,int x,int y,int z,Block old,int meta){long start=enter(PlacementProfile.CALLBACK);try{block.breakBlock(world,x,y,z,old,meta);}finally{exit(start,PlacementProfile.CALLBACK);}}
    public static void added(Block block,World world,int x,int y,int z){long start=enter(PlacementProfile.CALLBACK);try{block.onBlockAdded(world,x,y,z);}finally{exit(start,PlacementProfile.CALLBACK);}}
    public static boolean checkLight(World world,int x,int y,int z){
        if(active()&&skipLight&&x==lightX&&y==lightY&&z==lightZ){skipLight=false;active.lightSkipped++;return false;}
        return world.func_147451_t(x,y,z);
    }
    public static void indexed(int avoided){if(active()){active.clientIndexed++;active.clientComparisonsAvoided+=avoided;}}
}
