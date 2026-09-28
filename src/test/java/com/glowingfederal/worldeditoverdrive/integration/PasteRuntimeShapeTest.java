package com.glowingfederal.worldeditoverdrive.integration;

import java.util.Arrays;
import org.junit.After;
import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

public class PasteRuntimeShapeTest {
    @After public void reset(){PasteHookStatus.resetForTests();}

    @Test public void matchesOnlyExactLaunchWrapperTargetInEitherForm(){
        assertTrue(EditSessionSetTransformer.isPasteTarget("com.sk89q.worldedit.function.operation.ForwardExtentCopy",null));
        assertTrue(EditSessionSetTransformer.isPasteTarget(null,"com/sk89q/worldedit/function/operation/ForwardExtentCopy"));
        assertFalse(EditSessionSetTransformer.isPasteTarget("example.ForwardExtentCopy","example.ForwardExtentCopy"));
        assertFalse(EditSessionSetTransformer.isPasteTarget("com.sk89q.worldedit.function.operation.Operation",null));
    }
    @Test public void acceptsPinnedEnhancedShape(){assertNull(EditSessionSetTransformer.inspectPasteShape(shape()));}
    @Test public void rejectsMissingField(){ClassNode node=shape();node.fields.remove(0);assertEquals("missing field source: Lcom/sk89q/worldedit/extent/Extent;",EditSessionSetTransformer.inspectPasteShape(node));}
    @Test public void rejectsWrongFieldDescriptor(){ClassNode node=shape();((FieldNode)node.fields.get(0)).desc="Ljava/lang/Object;";assertEquals("field source descriptor mismatch: expected Lcom/sk89q/worldedit/extent/Extent;, found Ljava/lang/Object;",EditSessionSetTransformer.inspectPasteShape(node));}
    @Test public void enhancedShapeDoesNotInventEntityOrBiomeFlags(){ClassNode node=shape();assertFalse(hasField(node,"copyEntities"));assertFalse(hasField(node,"copyBiomes"));assertNull(EditSessionSetTransformer.inspectPasteShape(node));}
    @Test public void rejectsWrongResumeDescriptor(){ClassNode node=shape();((MethodNode)node.methods.get(0)).desc="()V";assertTrue(EditSessionSetTransformer.inspectPasteShape(node).startsWith("wrong resume"));}
    @Test public void rejectsUnexpectedStructure(){ClassNode node=shape();node.superName="example/Base";assertEquals("unexpected superclass example/Base",EditSessionSetTransformer.inspectPasteShape(node));}
    @Test public void diagnosticsSeparateObservationFromCompatibility(){
        assertEquals(PasteHookStatus.RuntimeShape.NOT_SEEN,PasteHookStatus.runtimeShape());assertFalse(PasteHookStatus.runtimeShapeCompatible());
        PasteHookStatus.observedCompatible();assertEquals(PasteHookStatus.RuntimeShape.SEEN_COMPATIBLE,PasteHookStatus.runtimeShape());assertFalse(PasteHookStatus.pasteHookInstalled);
        PasteHookStatus.resetForTests();PasteHookStatus.observedIncompatible("missing field source");
        assertEquals(PasteHookStatus.RuntimeShape.SEEN_INCOMPATIBLE,PasteHookStatus.runtimeShape());assertEquals("incompatible runtime shape: missing field source",PasteHookStatus.hookReason);
    }
    @Test public void coldPinnedClassesInstallReorderAndHistoryHooksWithValidStacks()throws Exception{
        PasteHookStatus.historyCommandHookInstalled=false;PasteHookStatus.historySessionHookInstalled=false;
        EditSessionSetTransformer transformer=new EditSessionSetTransformer();
        String[] names={"com.sk89q.worldedit.EditSession","com.sk89q.worldedit.command.HistoryCommands","com.sk89q.worldedit.function.operation.BlockMapEntryPlacer","com.sk89q.worldedit.extent.reorder.MultiStageReorder$Stage3Committer"};
        for(String name:names){
            InputStream input=getClass().getClassLoader().getResourceAsStream(name.replace('.','/')+".class");assertNotNull(input);ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];try{int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}finally{input.close();}
            byte[] original=output.toByteArray(),modified=transformer.transform(name,name,original);assertFalse(Arrays.equals(original,modified));
            ClassNode node=new ClassNode();new ClassReader(modified).accept(node,0);
            for(MethodNode method:node.methods)if(method.name.equals("undo")||method.name.equals("redo")||method.name.equals("resume"))new Analyzer(new BasicVerifier()).analyze(node.name,method);
            if(name.endsWith("HistoryCommands")){int calls=0;for(MethodNode method:node.methods)for(AbstractInsnNode i=method.instructions.getFirst();i!=null;i=i.getNext())if(i instanceof MethodInsnNode&&((MethodInsnNode)i).name.equals("tryCommand"))calls++;assertEquals(2,calls);}
        }
        EnhancedReorderYieldBridge.prepareHooks();assertTrue(EnhancedReorderYieldBridge.isSupported());assertTrue(PasteHookStatus.historyCommandHookInstalled);assertTrue(PasteHookStatus.historySessionHookInstalled);
    }
    private static ClassNode shape(){
        ClassNode n=new ClassNode();n.name="com/sk89q/worldedit/function/operation/ForwardExtentCopy";n.superName="java/lang/Object";n.interfaces=Arrays.asList("com/sk89q/worldedit/function/operation/Operation");
        String[][] f={{"source","Lcom/sk89q/worldedit/extent/Extent;"},{"destination","Lcom/sk89q/worldedit/extent/Extent;"},{"region","Lcom/sk89q/worldedit/regions/Region;"},{"from","Lcom/sk89q/worldedit/Vector;"},{"to","Lcom/sk89q/worldedit/Vector;"},{"repetitions","I"},{"sourceMask","Lcom/sk89q/worldedit/function/mask/Mask;"},{"removingEntities","Z"},{"sourceFunction","Lcom/sk89q/worldedit/function/RegionFunction;"},{"transform","Lcom/sk89q/worldedit/math/transform/Transform;"},{"currentTransform","Lcom/sk89q/worldedit/math/transform/Transform;"},{"lastVisitor","Lcom/sk89q/worldedit/function/visitor/RegionVisitor;"},{"affected","I"}};
        for(int i=0;i<f.length;i++)n.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|(i<5?Opcodes.ACC_FINAL:0),f[i][0],f[i][1],null,null));
        n.methods.add(new MethodNode(Opcodes.ACC_PUBLIC,"resume","(Lcom/sk89q/worldedit/function/operation/RunContext;)Lcom/sk89q/worldedit/function/operation/Operation;",null,null));return n;
    }
    private static boolean hasField(ClassNode node,String name){for(FieldNode field:node.fields)if(name.equals(field.name))return true;return false;}
}
