package com.glowingfederal.worldeditoverdrive;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** One Forge-routed, server-owned logging category for every Overdrive diagnostic. */
public final class OverdriveLog {
    private static final Logger LOG = LogManager.getLogger("WorldEditOverdrive");
    private static final String PREFIX = "[WorldEditOverdrive] ";
    private OverdriveLog() { }
    public static void info(String message,Object... arguments){LOG.info(PREFIX+message,arguments);}
    public static void warn(String message,Object... arguments){LOG.warn(PREFIX+message,arguments);}
    public static void error(String message,Object... arguments){LOG.error(PREFIX+message,arguments);}
    public static void error(String message,Throwable failure){LOG.error(PREFIX+message,failure);}
}
