package com.glowingfederal.worldeditoverdrive.backend;

public final class ServerThreadGuard {

    private static volatile Thread serverThread;

    private ServerThreadGuard() {
    }

    public static void capture() {
        Thread current = Thread.currentThread();

        if (serverThread == null) {
            serverThread = current;
            return;
        }

        if (serverThread != current) {
            throw new IllegalStateException(
                    "Attempted to capture server thread from a different thread"
            );
        }
    }

    public static boolean isServerThread() {
        return serverThread != null && Thread.currentThread() == serverThread;
    }

    public static void assertServerThread() {
        if (serverThread == null) {
            throw new IllegalStateException(
                    "Minecraft server thread has not been captured yet"
            );
        }

        if (Thread.currentThread() != serverThread) {
            throw new IllegalStateException(
                    "Live world mutation attempted outside the Minecraft server thread"
            );
        }
    }

    public static void clear() {
        serverThread = null;
    }
}