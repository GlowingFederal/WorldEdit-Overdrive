package com.glowingfederal.worldeditoverdrive.integration;

import com.glowingfederal.worldeditoverdrive.OverdriveLog;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Redirects Enhanced's composed /set operation before its RegionVisitor executes. */
public final class EditSessionSetTransformer implements IClassTransformer {
    private static final String LEGACY_TARGET="com.sk89q.worldedit.EditSession";
    private static final String COMMAND_TARGET="com.sk89q.worldedit.command.composition.SelectionCommand";
    private static final String PASTE_TARGET="com.sk89q.worldedit.function.operation.ForwardExtentCopy";
    private static final String PASTE_COMMAND_TARGET="com.sk89q.worldedit.command.ClipboardCommands";
    private static final String PASTE_DESC="(Lcom/sk89q/worldedit/entity/Player;Lcom/sk89q/worldedit/LocalSession;Lcom/sk89q/worldedit/EditSession;ZZZ)V";
    private static final String LEGACY_DESC="(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)I";
    private static final String CALL_DESC="(Lcom/sk89q/worldedit/util/command/argument/CommandArgs;Lcom/sk89q/minecraft/util/commands/CommandLocals;)Lcom/sk89q/worldedit/function/operation/Operation;";
    private static final String COMPLETE_DESC="(Lcom/sk89q/worldedit/function/operation/Operation;)V";

    public EditSessionSetTransformer(){Stage4HookStatus.transformerRegistered=true;}

    public byte[] transform(String name,String transformedName,byte[] bytes) {
        if(isPasteTarget(name,transformedName))return inspectPasteTarget(bytes);
        if(PASTE_COMMAND_TARGET.equals(normalize(transformedName))||PASTE_COMMAND_TARGET.equals(normalize(name)))return transformPasteCommand(bytes);
        if(COMMAND_TARGET.equals(transformedName))return transformCommand(name,transformedName,bytes);
        if(LEGACY_TARGET.equals(transformedName))return transformLegacy(name,transformedName,bytes);
        return bytes;
    }

    private byte[] transformPasteCommand(byte[] bytes) {
        try {
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_FRAMES);
            MethodNode target=null;MethodInsnNode completion=null;int methods=0,calls=0;
            for(MethodNode method:node.methods)if("paste".equals(method.name)&&PASTE_DESC.equals(method.desc)){
                methods++;target=method;
                for(AbstractInsnNode insn=method.instructions.getFirst();insn!=null;insn=insn.getNext())if(insn instanceof MethodInsnNode){
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if(call.getOpcode()==Opcodes.INVOKESTATIC&&!call.itf&&"com/sk89q/worldedit/function/operation/Operations".equals(call.owner)
                            &&"completeLegacy".equals(call.name)&&COMPLETE_DESC.equals(call.desc)){calls++;completion=call;}
                }
            }
            if(methods!=1||calls!=1||target==null||completion==null)return pasteCommandUnavailable(bytes,"expected one paste"+PASTE_DESC+" completeLegacy call; methods="+methods+", calls="+calls);
            // Stack on entry is [operation]. Keep it for vanilla; ownership is explicit
            // only after tryDefer has registered a complete deferred owner.
            LabelNode vanilla=new LabelNode();InsnList hook=new InsnList();
            hook.add(new InsnNode(Opcodes.DUP));hook.add(new VarInsnNode(Opcodes.ALOAD,1));hook.add(new VarInsnNode(Opcodes.ALOAD,2));
            hook.add(new VarInsnNode(Opcodes.ILOAD,6));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/glowingfederal/worldeditoverdrive/integration/PasteBridge","tryDefer",
                    "(Lcom/sk89q/worldedit/function/operation/Operation;Lcom/sk89q/worldedit/entity/Player;Lcom/sk89q/worldedit/LocalSession;Z)Lcom/glowingfederal/worldeditoverdrive/integration/PasteBridge$Decision;",false));
            hook.add(new FieldInsnNode(Opcodes.GETSTATIC,"com/glowingfederal/worldeditoverdrive/integration/PasteBridge$Decision","DEFERRED","Lcom/glowingfederal/worldeditoverdrive/integration/PasteBridge$Decision;"));
            hook.add(new JumpInsnNode(Opcodes.IF_ACMPNE,vanilla));hook.add(new InsnNode(Opcodes.POP));hook.add(new InsnNode(Opcodes.RETURN));hook.add(vanilla);
            target.instructions.insertBefore(completion,hook);
            ClassWriter writer=new SafeClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(writer);
            PasteHookStatus.hookInstalled();OverdriveLog.info("Stage 5C installed ClipboardCommands#paste{} completeLegacy interception",PASTE_DESC);
            return writer.toByteArray();
        }catch(Throwable incompatible){return pasteCommandUnavailable(bytes,"paste call-site transform failed: "+incompatible.toString());}
    }
    private static byte[] pasteCommandUnavailable(byte[] bytes,String reason){PasteHookStatus.pasteHookInstalled=false;PasteHookStatus.hookReason=reason;
        OverdriveLog.warn("Stage 5C paste hook unavailable; Enhanced remains vanilla ({})",reason);return bytes;}

    static boolean isPasteTarget(String name,String transformedName) {
        return PASTE_TARGET.equals(normalize(name))||PASTE_TARGET.equals(normalize(transformedName));
    }
    private static String normalize(String name){return name==null?null:name.replace('/','.');}

    /**
     * Stage 5C deliberately starts with a bytecode gate, not a speculative
     * patch.  The asynchronous continuation is not installed until every
     * member used by the adapter is proven against the runtime artifact.
     */
    private byte[] inspectPasteTarget(byte[] bytes) {
        try {
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            String reason=inspectPasteShape(node);
            if(reason!=null)return pasteIncompatible(bytes,reason);
            // Shape is known, but suppressing resume without a command continuation would
            // falsely complete /paste. Keep Enhanced bytecode untouched and report INACTIVE.
            PasteHookStatus.observedCompatible();
            OverdriveLog.info("Stage 5C found compatible {}; bytecode intentionally unchanged",PASTE_TARGET);
            return bytes;
        } catch(Throwable incompatible){return pasteIncompatible(bytes,"bytecode inspection failed: "+incompatible.toString());}
    }

    static String inspectPasteShape(ClassNode node) {
        String internal=PASTE_TARGET.replace('.','/');
        if(!internal.equals(node.name))return "unexpected class name "+node.name;
        if(!"java/lang/Object".equals(node.superName))return "unexpected superclass "+node.superName;
        if(node.interfaces.size()!=1||!"com/sk89q/worldedit/function/operation/Operation".equals(node.interfaces.get(0)))return "unexpected interfaces "+node.interfaces;
        String[][] fields={{"source","Lcom/sk89q/worldedit/extent/Extent;","18"},{"destination","Lcom/sk89q/worldedit/extent/Extent;","18"},{"region","Lcom/sk89q/worldedit/regions/Region;","18"},{"from","Lcom/sk89q/worldedit/Vector;","18"},{"to","Lcom/sk89q/worldedit/Vector;","18"},{"repetitions","I","2"},{"sourceMask","Lcom/sk89q/worldedit/function/mask/Mask;","2"},{"removingEntities","Z","2"},{"sourceFunction","Lcom/sk89q/worldedit/function/RegionFunction;","2"},{"transform","Lcom/sk89q/worldedit/math/transform/Transform;","2"},{"currentTransform","Lcom/sk89q/worldedit/math/transform/Transform;","2"},{"lastVisitor","Lcom/sk89q/worldedit/function/visitor/RegionVisitor;","2"},{"affected","I","2"}};
        for(String[] required:fields){int named=0;String found=null;int access=0;for(FieldNode field:node.fields)if(required[0].equals(field.name)){named++;found=field.desc;access=field.access;}
            if(named==0)return "missing field "+required[0]+": "+required[1];if(named!=1)return "field "+required[0]+" count mismatch: expected 1, found "+named;if(!required[1].equals(found))return "field "+required[0]+" descriptor mismatch: expected "+required[1]+", found "+found;
            int expected=Integer.parseInt(required[2]);if(access!=expected)return "field "+required[0]+" access mismatch: expected "+expected+", found "+access;}
        String desc="(Lcom/sk89q/worldedit/function/operation/RunContext;)Lcom/sk89q/worldedit/function/operation/Operation;";
        int named=0,exact=0;for(MethodNode method:node.methods)if("resume".equals(method.name)){named++;if(desc.equals(method.desc)&&method.access==Opcodes.ACC_PUBLIC)exact++;}
        if(named!=1||exact!=1)return "wrong resume(RunContext):Operation descriptor/access";
        return null;
    }

    private static byte[] pasteIncompatible(byte[] bytes,String reason){
        PasteHookStatus.observedIncompatible(reason);
        OverdriveLog.warn("Stage 5C paste acceleration inactive; Enhanced remains untouched ({})",PasteHookStatus.hookReason);return bytes;
    }

    private byte[] transformLegacy(String name,String transformedName,byte[] bytes) {
        Stage4HookStatus.editSessionSeen=true;
        Stage4HookStatus.targetNames="name="+name+", transformedName="+transformedName;
        ClassNode node=new ClassNode(); new ClassReader(bytes).accept(node,ClassReader.SKIP_FRAMES); int matches=0;
        for(MethodNode method:node.methods) if("setBlocks".equals(method.name)&&LEGACY_DESC.equals(method.desc)) {
            matches++; LabelNode fallback=new LabelNode(); InsnList hook=new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD,0)); hook.add(new VarInsnNode(Opcodes.ALOAD,1)); hook.add(new VarInsnNode(Opcodes.ALOAD,2));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/glowingfederal/worldeditoverdrive/integration/Stage4SetBridge","trySet",
                    "(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)Ljava/lang/Integer;",false));
            hook.add(new InsnNode(Opcodes.DUP)); hook.add(new JumpInsnNode(Opcodes.IFNULL,fallback));
            hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Integer","intValue","()I",false)); hook.add(new InsnNode(Opcodes.IRETURN));
            hook.add(fallback); hook.add(new InsnNode(Opcodes.POP));
            method.instructions.insertBefore(method.instructions.getFirst(),hook);
        }
        installEnhancedCommandHooks(node);
        Stage4HookStatus.targetMethodMatched=matches==1;
        if(matches!=1) throw new IllegalStateException("Expected exactly one Enhanced setBlocks(Region,Pattern), found "+matches);
        // Every EditSession entry hook introduces a new branch target at the
        // original first instruction. Rebuild both frames and maxs for the
        // complete class; retaining the input frames is not valid after that
        // control-flow change on Java 8.
        ClassWriter writer=new SafeClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS); node.accept(writer);
        Stage4HookStatus.legacySetBlocksHookInstalled=true;
        OverdriveLog.info("Stage 4 legacy EditSession#setBlocks hook installed; not the active /set hook ({})",Stage4HookStatus.targetNames);
        return writer.toByteArray();
    }

    private static void installEnhancedCommandHooks(ClassNode node){
        int replace=0,geometry=0,copy=0,overlay=0;
        for(MethodNode m:node.methods){String bridge=null,desc=null;int[] vars=null;int kind=-1;
            if("replaceBlocks".equals(m.name)&&"(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/masks/Mask;Lcom/sk89q/worldedit/patterns/Pattern;)I".equals(m.desc)){bridge="replace";desc="(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/masks/Mask;Lcom/sk89q/worldedit/patterns/Pattern;)Lcom/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision;";vars=new int[]{1,2,3};replace++;}
            else if(("makeCuboidWalls".equals(m.name)||"makeCuboidFaces".equals(m.name)||"center".equals(m.name))&&"(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)I".equals(m.desc)){bridge="geometry";desc="(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;I)Lcom/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision;";vars=new int[]{1,2};kind="makeCuboidWalls".equals(m.name)?0:"makeCuboidFaces".equals(m.name)?1:2;geometry++;}
            else if("overlayCuboidBlocks".equals(m.name)&&"(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)I".equals(m.desc)){bridge="overlay";desc="(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/patterns/Pattern;)Lcom/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision;";vars=new int[]{1,2};overlay++;}
            else if("naturalizeCuboidBlocks".equals(m.name)&&"(Lcom/sk89q/worldedit/regions/Region;)I".equals(m.desc)){bridge="naturalize";desc="(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;)Lcom/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision;";vars=new int[]{1};overlay++;}
            else if("stackCuboidRegion".equals(m.name)&&"(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/Vector;IZ)I".equals(m.desc)){bridge="stack";desc="(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/Vector;IZ)Lcom/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision;";vars=new int[]{1,2,3,4};copy++;}
            else if("moveRegion".equals(m.name)&&"(Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/Vector;IZLcom/sk89q/worldedit/blocks/BaseBlock;)I".equals(m.desc)){bridge="move";desc="(Lcom/sk89q/worldedit/EditSession;Lcom/sk89q/worldedit/regions/Region;Lcom/sk89q/worldedit/Vector;IZLcom/sk89q/worldedit/blocks/BaseBlock;)Lcom/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision;";vars=new int[]{1,2,3,4,5};copy++;}
            if(bridge==null)continue;
            LabelNode vanilla=new LabelNode();InsnList h=new InsnList();
            h.add(new VarInsnNode(Opcodes.ALOAD,0));
            for(int v:vars)h.add(new VarInsnNode((v==3&&(bridge.equals("stack")||bridge.equals("move")))?Opcodes.ILOAD:(v==4&&(bridge.equals("stack")||bridge.equals("move")))?Opcodes.ILOAD:Opcodes.ALOAD,v));
            if(kind>=0)h.add(new InsnNode(Opcodes.ICONST_0+kind));
            h.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge",bridge,desc,false));
            // The decision object makes handled versus not-handled explicit.
            // A not-handled result must flow into the original body rather than leaving that body
            // unreachable behind an unconditional IRETURN.
            h.add(new InsnNode(Opcodes.DUP));
            h.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"com/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision","isHandled","()Z",false));
            h.add(new JumpInsnNode(Opcodes.IFEQ,vanilla));
            h.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"com/glowingfederal/worldeditoverdrive/integration/EnhancedCommandBridge$Decision","getResult","()I",false));h.add(new InsnNode(Opcodes.IRETURN));
            h.add(vanilla);h.add(new InsnNode(Opcodes.POP));m.instructions.insert(h);
        }
        CommandHookStatus.replaceHookInstalled=replace==1;CommandHookStatus.geometryHookInstalled=geometry==3;CommandHookStatus.copyMoveHookInstalled=copy==2;CommandHookStatus.overlayHookInstalled=overlay==2;
        OverdriveLog.info("Enhanced command hooks replace={} geometry={} copyMove={} overlay={}",replace,geometry,copy,overlay);
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
