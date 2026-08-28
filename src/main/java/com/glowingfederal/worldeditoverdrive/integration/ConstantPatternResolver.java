package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.patterns.SingleBlockPattern;

/** Resolves only the legacy pattern type proven to hold one immutable block value. */
final class ConstantPatternResolver {
    private ConstantPatternResolver() { }

    static BaseBlock resolve(Pattern pattern) {
        return pattern instanceof SingleBlockPattern ? ((SingleBlockPattern)pattern).getBlock() : null;
    }
}
