package com.glowingfederal.worldeditoverdrive.integration;

import java.util.Arrays;
import org.junit.After;
import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
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
    @Test public void rejectsMissingField(){ClassNode node=shape();node.fields.remove(0);assertEquals("missing field source",EditSessionSetTransformer.inspectPasteShape(node));}
    @Test public void rejectsWrongFieldDescriptor(){ClassNode node=shape();((FieldNode)node.fields.get(0)).desc="Ljava/lang/Object;";assertEquals("wrong descriptor for field source",EditSessionSetTransformer.inspectPasteShape(node));}
    @Test public void rejectsWrongResumeDescriptor(){ClassNode node=shape();((MethodNode)node.methods.get(0)).desc="()V";assertTrue(EditSessionSetTransformer.inspectPasteShape(node).startsWith("wrong resume"));}
    @Test public void rejectsUnexpectedStructure(){ClassNode node=shape();node.superName="example/Base";assertEquals("unexpected superclass example/Base",EditSessionSetTransformer.inspectPasteShape(node));}
    @Test public void diagnosticsSeparateObservationFromCompatibility(){
        assertEquals(PasteHookStatus.RuntimeShape.NOT_SEEN,PasteHookStatus.runtimeShape());assertFalse(PasteHookStatus.runtimeShapeCompatible());
        PasteHookStatus.observedCompatible();assertEquals(PasteHookStatus.RuntimeShape.SEEN_COMPATIBLE,PasteHookStatus.runtimeShape());assertFalse(PasteHookStatus.pasteHookInstalled);
        PasteHookStatus.resetForTests();PasteHookStatus.observedIncompatible("missing field source");
        assertEquals(PasteHookStatus.RuntimeShape.SEEN_INCOMPATIBLE,PasteHookStatus.runtimeShape());assertEquals("incompatible runtime shape: missing field source",PasteHookStatus.hookReason);
    }
    private static ClassNode shape(){
        ClassNode n=new ClassNode();n.name="com/sk89q/worldedit/function/operation/ForwardExtentCopy";n.superName="java/lang/Object";n.interfaces=Arrays.asList("com/sk89q/worldedit/function/operation/Operation");
        String[][] f={{"source","Lcom/sk89q/worldedit/extent/Extent;"},{"destination","Lcom/sk89q/worldedit/extent/Extent;"},{"region","Lcom/sk89q/worldedit/regions/Region;"},{"from","Lcom/sk89q/worldedit/Vector;"},{"to","Lcom/sk89q/worldedit/Vector;"},{"repetitions","I"},{"sourceMask","Lcom/sk89q/worldedit/function/mask/Mask;"},{"sourceFunction","Lcom/sk89q/worldedit/function/RegionFunction;"},{"transform","Lcom/sk89q/worldedit/math/transform/Transform;"},{"copyEntities","Z"},{"copyBiomes","Z"},{"filterFunction","Lcom/sk89q/worldedit/function/RegionFunction;"}};
        for(String[] field:f)n.fields.add(new FieldNode(Opcodes.ACC_PRIVATE,field[0],field[1],null,null));
        n.methods.add(new MethodNode(Opcodes.ACC_PUBLIC,"resume","(Lcom/sk89q/worldedit/function/operation/RunContext;)Lcom/sk89q/worldedit/function/operation/Operation;",null,null));return n;
    }
}
