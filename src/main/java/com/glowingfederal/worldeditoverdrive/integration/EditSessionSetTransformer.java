package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Redirects Enhanced's composed /set operation before its RegionVisitor executes. */
public final class EditSessionSetTransformer implements IClassTransformer {
    private static final String LEGACY_TARGET="com.sk89q.worldedit.EditSession";
    private static final String COMMAND_TARGET="com.sk89q.worldedit.command.composition.SelectionCommand";
    private static final String LEGACY_DESC="(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)I";
    private static final String CALL_DESC="(Lcom/sk89q/worldedit/util/command/argument/CommandArgs;Lcom/sk89q/minecraft/util/commands/CommandLocals;)Lcom/sk89q/worldedit/function/operation/Operation;";

    public EditSessionSetTransformer(){Stage4HookStatus.transformerRegistered=true;}

    public byte[] transform(String name,String transformedName,byte[] bytes) {
        if(COMMAND_TARGET.equals(transformedName))return transformCommand(name,transformedName,bytes);
        if(LEGACY_TARGET.equals(transformedName))return transformLegacy(name,transformedName,bytes);
        return bytes;
    }

    private byte[] transformLegacy(String name,String transformedName,byte[] bytes) {
        Stage4HookStatus.editSessionSeen=true;
        Stage4HookStatus.targetNames="name="+name+", transformedName="+transformedName;
        ClassNode node=new ClassNode(); new ClassReader(bytes).accept(node,0); int matches=0;
        for(MethodNode method:node.methods) if("setBlocks".equals(method.name)&&LEGACY_DESC.equals(method.desc)) {
            matches++; LabelNode fallback=new LabelNode(); InsnList hook=new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD,0)); hook.add(new VarInsnNode(Opcodes.ALOAD,1)); hook.add(new VarInsnNode(Opcodes.ALOAD,2));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/glowingfederal/worldeditoverdrive/integration/Stage4SetBridge","trySet",
                    "(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)Ljava/lang/Integer;",false));
            hook.add(new InsnNode(Opcodes.DUP)); hook.add(new JumpInsnNode(Opcodes.IFNULL,fallback));
            hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Integer","intValue","()I",false)); hook.add(new InsnNode(Opcodes.IRETURN));
            hook.add(fallback); hook.add(new FrameNode(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Integer"})); hook.add(new InsnNode(Opcodes.POP));
            method.instructions.insertBefore(method.instructions.getFirst(),hook);
        }
        Stage4HookStatus.targetMethodMatched=matches==1;
        if(matches!=1) throw new IllegalStateException("Expected exactly one Enhanced setBlocks(Region,Pattern), found "+matches);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        Stage4HookStatus.legacySetBlocksHookInstalled=true;
        OverdriveLog.info("Stage 4 legacy EditSession#setBlocks hook installed; not the active /set hook ({})",Stage4HookStatus.targetNames);
        return writer.toByteArray();
    }

    private byte[] transformCommand(String name,String transformedName,byte[] bytes) {
        Stage4HookStatus.selectionCommandSeen=true;
        ClassNode node=new ClassNode(); new ClassReader(bytes).accept(node,0); int methods=0,factories=0,completions=0;
        for(MethodNode method:node.methods) if("call".equals(method.name)&&CALL_DESC.equals(method.desc)) {
            methods++; AbstractInsnNode operationStore=null,completion=null;
            for(AbstractInsnNode insn=method.instructions.getFirst();insn!=null;insn=insn.getNext()) if(insn instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)insn;
                if("com/sk89q/worldedit/function/Contextual".equals(call.owner)&&"createFromContext".equals(call.name)) {
                    AbstractInsnNode next=nextCode(insn.getNext());
                    if(next instanceof VarInsnNode&&next.getOpcode()==Opcodes.ASTORE){operationStore=next;factories++;}
                }
                if("com/sk89q/worldedit/function/operation/Operations".equals(call.owner)&&"completeBlindly".equals(call.name)){completion=call;completions++;}
            }
            if(operationStore!=null&&completion!=null) {
                int operation=((VarInsnNode)operationStore).var;
                LabelNode nativePath=new LabelNode(),feedback=new LabelNode(); InsnList hook=new InsnList();
                // Exact Enhanced 6.3.0 call layout, checked together with both semantic anchors above.
                hook.add(new VarInsnNode(Opcodes.ALOAD,8)); hook.add(new VarInsnNode(Opcodes.ALOAD,7)); hook.add(new VarInsnNode(Opcodes.ALOAD,operation));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/glowingfederal/worldeditoverdrive/integration/Stage4SetBridge","trySetOperation",
                        "(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/function/operation/Operation;)Ljava/lang/Integer;",false));
                hook.add(new InsnNode(Opcodes.DUP)); hook.add(new JumpInsnNode(Opcodes.IFNULL,nativePath));
                hook.add(new InsnNode(Opcodes.POP)); hook.add(new JumpInsnNode(Opcodes.GOTO,feedback));
                hook.add(nativePath); hook.add(new FrameNode(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Integer"})); hook.add(new InsnNode(Opcodes.POP));
                method.instructions.insert(operationStore,hook);
                method.instructions.insert(completion,feedback);
            }
        }
        boolean matched=methods==1&&factories==1&&completions==1;
        Stage4HookStatus.selectionCommandDescriptorMatched=matched;
        if(!matched)throw new IllegalStateException("Expected SelectionCommand call anchors once; methods="+methods+", factories="+factories+", completions="+completions);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        Stage4HookStatus.activeSetCommandHookInstalled=true; Stage4HookStatus.hookInstalled=true;
        OverdriveLog.info("Stage 4 active /set hook installed into SelectionCommand#call (name={}, transformedName={})",name,transformedName);
        return writer.toByteArray();
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        while(node!=null&&(node.getType()==AbstractInsnNode.LABEL||node.getType()==AbstractInsnNode.LINE||node.getType()==AbstractInsnNode.FRAME))node=node.getNext();
        return node;
    }
}
