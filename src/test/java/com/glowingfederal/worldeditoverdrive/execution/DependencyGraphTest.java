package com.glowingfederal.worldeditoverdrive.execution;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DependencyGraphTest {
    @Test public void ordersCrossChunkSupportBeforeDependent(){DependencyGraph<String> graph=new DependencyGraph<String>(DependencyGraph.CyclePolicy.FAIL);
        graph.addDependency("chunk-a/support","chunk-b/dependent");
        assertEquals(Arrays.asList("chunk-a/support","chunk-b/dependent"),graph.deterministicOrder());}
    @Test(expected=IllegalStateException.class) public void failPolicyReportsCycle(){DependencyGraph<String> graph=new DependencyGraph<String>(DependencyGraph.CyclePolicy.FAIL);
        graph.addDependency("a","b");graph.addDependency("b","a");graph.deterministicOrder();}
    @Test public void breakPolicyIsDeterministic(){DependencyGraph<String> graph=new DependencyGraph<String>(DependencyGraph.CyclePolicy.DETERMINISTIC_BREAK);
        graph.addDependency("b","a");graph.addDependency("a","b");assertEquals(Arrays.asList("a","b"),graph.deterministicOrder());}
}
