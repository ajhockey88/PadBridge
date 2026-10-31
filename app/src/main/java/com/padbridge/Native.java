package com.padbridge;

import java.io.File;
import java.io.FileDescriptor;

/**
 * The engine's native helper, libpadbridge.so (app/src/main/native): rumble effects and the
 * event clock, which take struct pointers that android.system.Os can't pass. Everything here
 * is optional: without the library, controllers work and just don't rumble.
 */
final class Native {
    private Native() {}

    private static boolean loaded;

    /** Loads the library from the app's native library folder. True if it's there. */
    static synchronized boolean load(String libDir) {
        if (loaded) return true;
        try {
            System.load(new File(libDir, "libpadbridge.so").getAbsolutePath());
            loaded = true;
        } catch (Throwable t) {
            Engine.log("no native helper (" + t.getMessage() + "): no rumble");
        }
        return loaded;
    }

    static boolean loaded() { return loaded; }

    /** The number behind a FileDescriptor, or -1. */
    static int fd(FileDescriptor fd) {
        try {
            return (Integer) FileDescriptor.class.getMethod("getInt$").invoke(fd);
        } catch (Exception e) {
            return -1;
        }
    }

    static native int rumbleUpload(int fd, int id, int strong, int weak, int ms);
    static native int rumblePlay(int fd, int id, int on);
    static native int rumbleErase(int fd, int id);
    static native int monotonicClock(int fd);
}
