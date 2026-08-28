package com.glowingfederal.worldeditoverdrive.snapshot;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Explicit, immutable declaration of live channels a server-thread capture may read. */
public final class SnapshotRequirements {
    public enum Channel { BLOCK_STATE, TILE_NBT, BIOME }
    private final Set<Channel> channels;

    private SnapshotRequirements(EnumSet<Channel> channels) {
        this.channels=Collections.unmodifiableSet(EnumSet.copyOf(channels));
    }
    public static SnapshotRequirements of(Channel first,Channel... rest) {
        if(first==null)throw new NullPointerException("first");
        EnumSet<Channel> set=EnumSet.of(first);if(rest!=null)for(Channel channel:rest)set.add(channel);
        return new SnapshotRequirements(set);
    }
    public boolean includes(Channel channel){return channels.contains(channel);}
    public Set<Channel> channels(){return channels;}
}
