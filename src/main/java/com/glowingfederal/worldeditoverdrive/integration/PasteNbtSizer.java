package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.jnbt.Tag;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Incremental retained-payload estimate. Never renders an NBT tree to a giant string. */
final class PasteNbtSizer {
    private final Deque<Iterator<?>> pending=new ArrayDeque<Iterator<?>>();
    boolean complete(){return pending.isEmpty();}
    void start(Tag tag){if(!complete())throw new IllegalStateException("NBT estimate already pending");if(tag!=null)pending.push(Collections.singleton(tag).iterator());}
    long resume(long deadline,int maxEntries){
        long bytes=0;int visited=0;
        while(!pending.isEmpty()&&visited<maxEntries&&System.nanoTime()<deadline){
            Iterator<?> iterator=pending.peek();if(!iterator.hasNext()){pending.pop();continue;}
            Object next=iterator.next();visited++;
            if(next instanceof Map.Entry){Map.Entry<?,?> entry=(Map.Entry<?,?>)next;Object key=entry.getKey();bytes+=64L+(key instanceof String?((String)key).length()*2L:0L);next=entry.getValue();}
            if(!(next instanceof Tag))throw new IllegalArgumentException("invalid NBT payload entry");
            Object value=((Tag)next).getValue();bytes+=64L;
            if(value instanceof Map)pending.push(((Map<?,?>)value).entrySet().iterator());
            else if(value instanceof List)pending.push(((List<?>)value).iterator());
            else if(value instanceof String)bytes+=40L+((String)value).length()*2L;
            else if(value instanceof byte[])bytes+=16L+((byte[])value).length;
            else if(value instanceof int[])bytes+=16L+((int[])value).length*4L;
            else if(value instanceof long[])bytes+=16L+((long[])value).length*8L;
            else bytes+=32L;
        }
        return bytes;
    }
}
