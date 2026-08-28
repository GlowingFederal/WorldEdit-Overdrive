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

/** Adds one nullable delegation at the head of Enhanced's region/pattern set method. */
public final class EditSessionSetTransformer implements IClassTransformer {
    private static final String TARGET="com.sk89q.worldedit.EditSession";
    private static final String DESC="(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)I";
    public byte[] transform(String name,String transformedName,byte[] bytes) {
        if(!TARGET.equals(transformedName))return bytes;
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
        if(matches!=1)throw new IllegalStateException("Expected exactly one Enhanced setBlocks(Region,Pattern), found "+matches);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
}
