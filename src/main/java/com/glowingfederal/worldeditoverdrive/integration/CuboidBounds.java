package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;

/** Immutable bounds accepted only from Enhanced's complete cuboid implementation. */
final class CuboidBounds {
    final int minX,minY,minZ,maxX,maxY,maxZ;
    private CuboidBounds(Vector min,Vector max){minX=min.getBlockX();minY=min.getBlockY();minZ=min.getBlockZ();maxX=max.getBlockX();maxY=max.getBlockY();maxZ=max.getBlockZ();}
    static CuboidBounds resolve(Region region){
        // Enhanced ships no CuboidRegion subclasses. Unknown plugin subclasses may override membership.
        return region!=null && region.getClass()==CuboidRegion.class ? new CuboidBounds(region.getMinimumPoint(),region.getMaximumPoint()) : null;
    }
}
