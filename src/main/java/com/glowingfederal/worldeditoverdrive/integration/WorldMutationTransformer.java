package com.glowingfederal.worldeditoverdrive.integration;

import net.minecraft.launchwrapper.IClassTransformer;
import cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Exact pinned Forge/Enhanced hooks. Shape failures leave native bytecode intact. */
public final class WorldMutationTransformer implements IClassTransformer, Opcodes {
    private static final String BRIDGE="com/glowingfederal/worldeditoverdrive/integration/PlacementMutationBridge";
    private static final String INDEX="com/glowingfederal/worldeditoverdrive/integration/ClientUpdateIndex";
    private static final String WORLD="net/minecraft/world/World", CHUNK="net/minecraft/world/chunk/Chunk";
    public byte[] transform(String name,String transformedName,byte[] bytes){
        if(bytes==null)return null;
        String target=(transformedName==null?name:transformedName).replace('.','/');
        boolean forge=target.equals("com/sk89q/worldedit/forge/ForgeWorld"), world=target.equals(WORLD),chunk=target.equals(CHUNK),client=target.equals("net/minecraft/server/management/PlayerManager$PlayerInstance");
        boolean tile=target.equals("com/sk89q/worldedit/forge/TileEntityUtils"),server=target.equals("net/minecraft/world/WorldServer");
        if(!forge&&!world&&!chunk&&!client&&!tile&&!server)return bytes;
        try{
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_FRAMES);int changed=0;
            if(forge){
                for(MethodNode method:node.methods)if(method.name.equals("setBlock")&&method.desc.equals("(Lcom/sk89q/worldedit/Vector;Lcom/sk89q/worldedit/blocks/BaseBlock;Z)Z")){
                    int hooks=0;
                    for(AbstractInsnNode i=method.instructions.getFirst();i!=null;i=i.getNext())if(i instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)i;
                        String owner=FMLDeobfuscatingRemapper.INSTANCE.map(call.owner),desc=FMLDeobfuscatingRemapper.INSTANCE.mapMethodDesc(call.desc),callName=FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(call.owner,call.name,call.desc);
                        if(owner.equals(WORLD)&&desc.equals("(II)L"+CHUNK+";")&&named(callName,"getChunkFromChunkCoords","func_72964_e")){replace(call,"chunk","(L"+WORLD+";II)L"+CHUNK+";");hooks++;}
                        else if(owner.equals(CHUNK)&&callName.equals("func_150807_a")&&desc.equals("(IIILnet/minecraft/block/Block;I)Z")){replace(call,"mutate","(L"+CHUNK+";IIILnet/minecraft/block/Block;I)Z");hooks++;}
                        else if(owner.equals(WORLD)&&callName.equals("func_147451_t")&&desc.equals("(III)Z")){replace(call,"checkLight","(L"+WORLD+";III)Z");hooks++;}
                    }
                    if(hooks!=3)throw new IllegalStateException("expected three ForgeWorld mutation anchors, found "+hooks);changed++;
                }
                if(changed!=1)throw new IllegalStateException("unexpected ForgeWorld setBlock shape");
                int reads=0;
                for(MethodNode method:node.methods)if(method.name.equals("getLazyBlock")&&method.desc.equals("(Lcom/sk89q/worldedit/Vector;)Lcom/sk89q/worldedit/blocks/BaseBlock;")){
                    LabelNode nativeRead=new LabelNode();InsnList read=new InsnList();read.add(new VarInsnNode(ALOAD,0));read.add(new VarInsnNode(ALOAD,1));read.add(new MethodInsnNode(INVOKESTATIC,BRIDGE,"lazy","(Lcom/sk89q/worldedit/forge/ForgeWorld;Lcom/sk89q/worldedit/Vector;)Lcom/sk89q/worldedit/blocks/BaseBlock;",false));read.add(new InsnNode(DUP));read.add(new JumpInsnNode(IFNULL,nativeRead));read.add(new InsnNode(ARETURN));read.add(nativeRead);read.add(new InsnNode(POP));method.instructions.insert(read);reads++;
                }
                if(reads!=1)throw new IllegalStateException("unexpected ForgeWorld lazy-read shape");
            }
            if(client){indexClient(node);changed++;}
            if(chunk){
                int callbacks=0;
                for(MethodNode method:node.methods)if(methodName(node,method).equals("func_150807_a"))for(AbstractInsnNode i=method.instructions.getFirst();i!=null;i=i.getNext())if(i instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)i;
                    if(!FMLDeobfuscatingRemapper.INSTANCE.map(call.owner).equals("net/minecraft/block/Block"))continue;
                    String callName=FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(call.owner,call.name,call.desc),desc=FMLDeobfuscatingRemapper.INSTANCE.mapMethodDesc(call.desc);
                    if(named(callName,"onBlockPreDestroy","func_149725_f")&&desc.equals("(L"+WORLD+";IIII)V")){replace(call,"preDestroy","(Lnet/minecraft/block/Block;L"+WORLD+";IIII)V");callbacks++;}
                    else if(named(callName,"breakBlock","func_149749_a")&&desc.equals("(L"+WORLD+";IIILnet/minecraft/block/Block;I)V")){replace(call,"breakBlock","(Lnet/minecraft/block/Block;L"+WORLD+";IIILnet/minecraft/block/Block;I)V");callbacks++;}
                    else if(named(callName,"onBlockAdded","func_149726_b")&&desc.equals("(L"+WORLD+";III)V")){replace(call,"added","(Lnet/minecraft/block/Block;L"+WORLD+";III)V");callbacks++;}
                }
                if(callbacks!=3)throw new IllegalStateException("expected three native block callbacks, found "+callbacks);
            }
            for(MethodNode method:node.methods){int category=-1;boolean light=false;
                String methodName=methodName(node,method),desc=FMLDeobfuscatingRemapper.INSTANCE.mapMethodDesc(method.desc);
                if(world){
                    if(named(methodName,"updateLightByType","func_147463_c")&&desc.equals("(Lnet/minecraft/world/EnumSkyBlock;III)Z")){category=PlacementProfile.SKY;light=true;}
                    else if(methodName.equals("func_147451_t")&&desc.equals("(III)Z"))category=PlacementProfile.LIGHT;
                    else if(named(methodName,"notifyBlockOfNeighborChange","func_147460_e")&&desc.equals("(IIILnet/minecraft/block/Block;)V"))category=PlacementProfile.NEIGHBOR;
                    else if(named(methodName,"markBlockForUpdate","func_147471_g")&&desc.equals("(III)V"))category=PlacementProfile.CLIENT;
                }
                if(chunk){
                    if(named(methodName,"relightBlock","func_76615_h")&&desc.equals("(III)V"))category=PlacementProfile.HEIGHT;
                    else if(named(methodName,"generateSkylightMap","func_76603_b")&&desc.equals("()V"))category=PlacementProfile.CHUNK_SKY;
                }
                if(client&&named(methodName,"flagChunkForUpdate","func_151253_a")&&desc.equals("(III)V"))category=PlacementProfile.CLIENT;
                if(tile&&method.name.equals("setTileEntity"))category=PlacementProfile.TILE;
                if(server&&(named(methodName,"scheduleBlockUpdate","func_147464_a")&&desc.equals("(IIILnet/minecraft/block/Block;I)V")||(named(methodName,"scheduleBlockUpdateWithPriority","func_147454_a")||methodName.equals("func_147446_b"))&&desc.equals("(IIILnet/minecraft/block/Block;II)V")))category=PlacementProfile.SCHEDULE;
                if(category>=0){time(method,category,light);changed++;}
            }
            if(world&&changed!=4||chunk&&changed!=2)throw new IllegalStateException("unexpected native profiling anchors: "+changed);
            if(tile&&changed!=2||server&&changed!=3)throw new IllegalStateException("unexpected lifecycle profiling anchors: "+changed);
            ClassWriter writer=new EditSessionSetTransformer.SafeClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] result=writer.toByteArray();
            status(forge,world,chunk,client,tile,server,"installed");return result;
        }catch(Throwable failure){status(forge,world,chunk,client,tile,server,"unavailable: "+failure.getMessage());return bytes;}
    }
    private static void status(boolean forge,boolean world,boolean chunk,boolean client,boolean tile,boolean server,String reason){if(forge)PlacementProfile.forgeHook=reason;if(world)PlacementProfile.lightingHook=reason;if(chunk)PlacementProfile.chunkHook=reason;if(client)PlacementProfile.clientHook=reason;if(tile)PlacementProfile.tileHook=reason;if(server)PlacementProfile.scheduledHook=reason;}
    private static boolean named(String actual,String mcp,String srg){return actual.equals(mcp)||actual.equals(srg);}
    private static String methodName(ClassNode node,MethodNode method){return FMLDeobfuscatingRemapper.INSTANCE.mapMethodName(node.name,method.name,method.desc);}
    private static void replace(MethodInsnNode call,String name,String desc){call.setOpcode(INVOKESTATIC);call.owner=BRIDGE;call.name=name;call.desc=desc;call.itf=false;}
    private static void time(MethodNode method,int category,boolean light){
        int token=method.maxLocals;method.maxLocals+=3;LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();InsnList prefix=new InsnList();
        if(light){prefix.add(new VarInsnNode(ALOAD,1));prefix.add(new MethodInsnNode(INVOKESTATIC,BRIDGE,"enterLight","(Lnet/minecraft/world/EnumSkyBlock;)J",false));}
        else{prefix.add(new LdcInsnNode(category));prefix.add(new MethodInsnNode(INVOKESTATIC,BRIDGE,"enter","(I)J",false));}
        prefix.add(new VarInsnNode(LSTORE,token));prefix.add(start);method.instructions.insert(prefix);
        for(AbstractInsnNode i=method.instructions.getFirst();i!=null;i=i.getNext())if(i.getOpcode()>=IRETURN&&i.getOpcode()<=RETURN)method.instructions.insertBefore(i,exit(token,category,light));
        method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new VarInsnNode(ASTORE,token+2));method.instructions.add(exit(token,category,light));method.instructions.add(new VarInsnNode(ALOAD,token+2));method.instructions.add(new InsnNode(ATHROW));method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
    private static InsnList exit(int token,int category,boolean light){InsnList list=new InsnList();list.add(new VarInsnNode(LLOAD,token));if(light){list.add(new VarInsnNode(ALOAD,1));list.add(new MethodInsnNode(INVOKESTATIC,BRIDGE,"exitLight","(JLnet/minecraft/world/EnumSkyBlock;)V",false));}else{list.add(new LdcInsnNode(category));list.add(new MethodInsnNode(INVOKESTATIC,BRIDGE,"exit","(JI)V",false));}return list;}
    private static void indexClient(ClassNode node){
        MethodNode flag=null,send=null;for(MethodNode method:node.methods){if(named(methodName(node,method),"flagChunkForUpdate","func_151253_a")&&method.desc.equals("(III)V"))flag=method;if(named(methodName(node,method),"sendChunkUpdate","func_73254_a")&&method.desc.equals("()V"))send=method;}
        if(flag==null||send==null)throw new IllegalStateException("missing PlayerInstance methods");
        FieldInsnNode positions=null,count=null;VarInsnNode keyStore=null,loopStore=null;JumpInsnNode loopEnd=null;
        for(AbstractInsnNode i=flag.instructions.getFirst();i!=null;i=i.getNext()){
            if(i instanceof FieldInsnNode){FieldInsnNode f=(FieldInsnNode)i;if(f.owner.equals(node.name)&&f.desc.equals("[S"))positions=f;}
            if(i.getOpcode()==I2S&&i.getNext() instanceof VarInsnNode)keyStore=(VarInsnNode)i.getNext();
            if(keyStore!=null&&i instanceof VarInsnNode&&i.getOpcode()==ISTORE&&i!=keyStore&&loopStore==null)loopStore=(VarInsnNode)i;
            if(loopStore!=null&&i instanceof FieldInsnNode&&i.getOpcode()==GETFIELD&&((FieldInsnNode)i).desc.equals("I")&&count==null)count=(FieldInsnNode)i;
            if(loopStore!=null&&i.getOpcode()==GOTO){loopEnd=(JumpInsnNode)i;break;}
        }
        if(positions==null||count==null||keyStore==null||loopStore==null||loopEnd==null||loopStore.getPrevious().getOpcode()!=ICONST_0)throw new IllegalStateException("unrecognized Forge duplicate loop");
        String field="overdrive$updates";node.fields.add(new FieldNode(ACC_PRIVATE,field,"L"+INDEX+";",null,null));
        LabelNode nativeLoop=new LabelNode(),ready=new LabelNode(),continueNative=new LabelNode();InsnList index=new InsnList();
        index.add(new MethodInsnNode(INVOKESTATIC,BRIDGE,"active","()Z",false));index.add(new JumpInsnNode(IFEQ,nativeLoop));
        index.add(new FieldInsnNode(GETSTATIC,"com/glowingfederal/worldeditoverdrive/integration/PlacementProfile","optimized","Z"));index.add(new JumpInsnNode(IFEQ,nativeLoop));
        index.add(new VarInsnNode(ALOAD,0));index.add(new FieldInsnNode(GETFIELD,node.name,field,"L"+INDEX+";"));index.add(new JumpInsnNode(IFNONNULL,ready));
        index.add(new VarInsnNode(ALOAD,0));index.add(new TypeInsnNode(NEW,INDEX));index.add(new InsnNode(DUP));index.add(new MethodInsnNode(INVOKESPECIAL,INDEX,"<init>","()V",false));index.add(new FieldInsnNode(PUTFIELD,node.name,field,"L"+INDEX+";"));index.add(ready);
        index.add(new VarInsnNode(ALOAD,0));index.add(new FieldInsnNode(GETFIELD,node.name,field,"L"+INDEX+";"));
        index.add(new VarInsnNode(ALOAD,0));index.add(new FieldInsnNode(GETFIELD,positions.owner,positions.name,positions.desc));index.add(new VarInsnNode(ALOAD,0));index.add(new FieldInsnNode(GETFIELD,count.owner,count.name,count.desc));index.add(new VarInsnNode(ILOAD,keyStore.var));index.add(new MethodInsnNode(INVOKEVIRTUAL,INDEX,"duplicate","([SIS)Z",false));index.add(new JumpInsnNode(IFEQ,continueNative));index.add(new InsnNode(RETURN));index.add(continueNative);
        LabelNode afterLoop=new LabelNode();flag.instructions.insert(loopEnd,afterLoop);index.add(new JumpInsnNode(GOTO,afterLoop));index.add(nativeLoop);flag.instructions.insertBefore(loopStore.getPrevious(),index);
        LabelNode noIndex=new LabelNode();InsnList reset=new InsnList();reset.add(new VarInsnNode(ALOAD,0));reset.add(new FieldInsnNode(GETFIELD,node.name,field,"L"+INDEX+";"));reset.add(new JumpInsnNode(IFNULL,noIndex));reset.add(new VarInsnNode(ALOAD,0));reset.add(new FieldInsnNode(GETFIELD,node.name,field,"L"+INDEX+";"));reset.add(new MethodInsnNode(INVOKEVIRTUAL,INDEX,"reset","()V",false));reset.add(noIndex);send.instructions.insert(reset);
    }
}
