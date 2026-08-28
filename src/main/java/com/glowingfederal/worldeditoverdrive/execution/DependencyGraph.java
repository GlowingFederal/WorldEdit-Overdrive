package com.glowingfederal.worldeditoverdrive.execution;

import java.util.*;

/** Deterministic operation-global dependency graph; node keys may identify any chunk. */
public final class DependencyGraph<K extends Comparable<K>> {
    public enum CyclePolicy { FAIL, DETERMINISTIC_BREAK }
    private final CyclePolicy policy; private final SortedMap<K,SortedSet<K>> outgoing=new TreeMap<K,SortedSet<K>>();
    private final SortedMap<K,Integer> incoming=new TreeMap<K,Integer>();
    public DependencyGraph(CyclePolicy policy){if(policy==null)throw new NullPointerException("policy");this.policy=policy;}
    public void addNode(K key){if(!incoming.containsKey(key)){incoming.put(key,0);outgoing.put(key,new TreeSet<K>());}}
    /** Adds prerequisite -> dependent. */ public void addDependency(K prerequisite,K dependent){addNode(prerequisite);addNode(dependent);
        if(outgoing.get(prerequisite).add(dependent))incoming.put(dependent,incoming.get(dependent)+1);}
    public int nodeCount(){return incoming.size();} public int dependencyCount(){int n=0;for(Set<K>s:outgoing.values())n+=s.size();return n;}
    public List<K> deterministicOrder(){SortedMap<K,Integer> degree=new TreeMap<K,Integer>(incoming);SortedSet<K> frontier=new TreeSet<K>();
        for(Map.Entry<K,Integer>e:degree.entrySet())if(e.getValue()==0)frontier.add(e.getKey());List<K> result=new ArrayList<K>();
        while(result.size()<degree.size()){if(frontier.isEmpty()){if(policy==CyclePolicy.FAIL)throw new IllegalStateException("dependency cycle");
                for(K k:degree.keySet())if(!result.contains(k)){frontier.add(k);break;}}
            K key=frontier.first();frontier.remove(key);if(result.contains(key))continue;result.add(key);
            for(K next:outgoing.get(key)){int d=degree.get(next)-1;degree.put(next,d);if(d<=0)frontier.add(next);}}
        return Collections.unmodifiableList(result);}
}
