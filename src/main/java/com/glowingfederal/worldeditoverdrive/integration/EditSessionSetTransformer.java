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
    private static final String COMPLETE_DESC="(Lcom/sk89q/worldedit/function/operation/Operation;)V";

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
        try {
            ClassNode node=new ClassNode(); new ClassReader(bytes).accept(node,ClassReader.SKIP_FRAMES);
            int methods=0,completions=0,operationLocal=-1; MethodNode target=null; MethodInsnNode completion=null;
            String observedCompletion="none";
            for(MethodNode method:node.methods) if("call".equals(method.name)&&CALL_DESC.equals(method.desc)) {
                methods++; target=method;
                for(AbstractInsnNode insn=method.instructions.getFirst();insn!=null;insn=insn.getNext()) if(insn instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if("com/sk89q/worldedit/function/operation/Operations".equals(call.owner)&&"completeBlindly".equals(call.name)) {
                        completions++; completion=call; observedCompletion=call.desc;
                        AbstractInsnNode source=previousCode(insn.getPrevious());
                        if(source instanceof VarInsnNode&&source.getOpcode()==Opcodes.ALOAD)operationLocal=((VarInsnNode)source).var;
                    }
                }
            }
            boolean safe=methods==1&&completions==1&&completion!=null&&completion.getOpcode()==Opcodes.INVOKESTATIC
                    &&!completion.itf&&COMPLETE_DESC.equals(completion.desc);
            OverdriveLog.info("Stage 4 bytecode: target={} method=call{} completeBlindlyCandidates={} completionDescriptor={} operationSource={}",
                    COMMAND_TARGET,CALL_DESC,completions,observedCompletion,operationLocal<0?"operand stack":"local "+operationLocal);
            Stage4HookStatus.selectionCommandDescriptorMatched=safe;
            if(!safe)return commandUnavailable(bytes,"completeBlindly anchor not safely patchable (methods="+methods+", candidates="+completions+", descriptor="+observedCompletion+")");

            LabelNode nativePath=new LabelNode(),feedback=new LabelNode(); InsnList hook=new InsnList();
            // The original Operation is already on the stack as completeBlindly's sole argument.
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/glowingfederal/worldeditoverdrive/integration/Stage4SetBridge","trySetOperation",
                    "(Lcom/sk89q/worldedit/function/operation/Operation;)Ljava/lang/Integer;",false));
            hook.add(new InsnNode(Opcodes.DUP)); hook.add(new JumpInsnNode(Opcodes.IFNULL,nativePath));
            hook.add(new InsnNode(Opcodes.POP)); hook.add(new InsnNode(Opcodes.POP)); hook.add(new JumpInsnNode(Opcodes.GOTO,feedback));
            hook.add(nativePath); hook.add(new InsnNode(Opcodes.POP));
            target.instructions.insertBefore(completion,hook); target.instructions.insert(completion,feedback);
            ClassWriter writer=new SafeClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS); node.accept(writer);
            Stage4HookStatus.activeSetCommandHookInstalled=true; Stage4HookStatus.hookInstalled=true; Stage4HookStatus.hookReason="installed";
            OverdriveLog.info("Stage 4 bytecode: hookInstalled=yes");
            return writer.toByteArray();
        } catch(Throwable incompatible) {
            return commandUnavailable(bytes,"completeBlindly anchor not safely patchable: "+incompatible.toString());
        }
    }

    private static byte[] commandUnavailable(byte[] bytes,String reason) {
        Stage4HookStatus.selectionCommandDescriptorMatched=false; Stage4HookStatus.activeSetCommandHookInstalled=false;
        Stage4HookStatus.hookInstalled=false; Stage4HookStatus.hookReason=reason;
        OverdriveLog.warn("WorldEdit Overdrive: active //set hook unavailable for this Enhanced bytecode; acceleration disabled ({})",reason);
        OverdriveLog.info("Stage 4 bytecode: hookInstalled=no");
        return bytes;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode node) {
        while(node!=null&&(node.getType()==AbstractInsnNode.LABEL||node.getType()==AbstractInsnNode.LINE||node.getType()==AbstractInsnNode.FRAME))node=node.getPrevious();
        return node;
    }

    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(int flags){super(flags);}
        protected String getCommonSuperClass(String first,String second){return first.equals(second)?first:"java/lang/Object";}
    }
}
