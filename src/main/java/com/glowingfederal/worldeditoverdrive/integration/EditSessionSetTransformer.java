package com.glowingfederal.worldeditoverdrive.integration;

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
import com.glowingfederal.worldeditoverdrive.OverdriveLog;

/** Adds one nullable delegation at the head of Enhanced's region/pattern set method. */
public final class EditSessionSetTransformer implements IClassTransformer {
    private static final String TARGET="com.sk89q.worldedit.EditSession";
    private static final String DESC="(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)I";
    public EditSessionSetTransformer() {
        Stage4HookStatus.transformerRegistered=true;
    }

    public byte[] transform(String name,String transformedName,byte[] bytes) {
        if(!TARGET.equals(transformedName))return bytes;
        Stage4HookStatus.editSessionSeen=true;
        Stage4HookStatus.targetNames="name="+name+", transformedName="+transformedName;
        ClassNode node=new ClassNode(); new ClassReader(bytes).accept(node,0); int matches=0;
        for(MethodNode method:node.methods) if("setBlocks".equals(method.name)&&DESC.equals(method.desc)) {
            matches++; LabelNode fallback=new LabelNode(); InsnList hook=new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD,0)); hook.add(new VarInsnNode(Opcodes.ALOAD,1)); hook.add(new VarInsnNode(Opcodes.ALOAD,2));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/glowingfederal/worldeditoverdrive/integration/Stage4SetBridge","trySet",
                    "(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)Ljava/lang/Integer;",false));
            hook.add(new InsnNode(Opcodes.DUP)); hook.add(new JumpInsnNode(Opcodes.IFNULL,fallback));
            hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Integer","intValue","()I",false)); hook.add(new InsnNode(Opcodes.IRETURN));
            hook.add(fallback); hook.add(new FrameNode(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Integer"})); hook.add(new InsnNode(Opcodes.POP));
            AbstractInsnNode first=method.instructions.getFirst(); method.instructions.insertBefore(first,hook);
        }
        Stage4HookStatus.targetMethodMatched=matches==1;
        if(matches!=1) {
            OverdriveLog.error("saw EditSession ({}) but expected descriptor {} matched {} methods; Stage 4 hook not installed",
                    Stage4HookStatus.targetNames,DESC,matches);
            throw new IllegalStateException("Expected exactly one Enhanced setBlocks(Region,Pattern), found "+matches);
        }
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        Stage4HookStatus.hookInstalled=true;
        OverdriveLog.info("Stage 4 hook installed into EditSession#setBlocks(Region, Pattern) ({}; descriptor matched=true)",
                Stage4HookStatus.targetNames);
        return writer.toByteArray();
    }
}
