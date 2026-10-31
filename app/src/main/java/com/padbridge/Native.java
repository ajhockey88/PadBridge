package com.padbridge;

import java.io.File;
import java.io.FileDescriptor;

/**
 * The engine's native helper, libpadbridge.so (app/src/main/native): rumble effects, the
 * event clock, and player pads made straight on /dev/uinput, which take struct pointers that
 * android.system.Os can't pass. Everything here is optional: without the library, player pads
 * go through Android's slower `uinput` tool and controllers don't rumble.
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

    /** Opens /dev/uinput: the fd, or -errno. */
    static native int uinputOpen();
    static native int close(int fd);
    /** An ioctl whose argument is a plain int. */
    static native int ioctlValue(int fd, int cmd, int value);
    /** An ioctl on a struct held in buf (up to 256 bytes), which it may fill in. */
    static native int ioctlStruct(int fd, int cmd, byte[] buf, int len);
    /** write(2) of up to 2048 bytes: bytes written, or -errno. */
    static native int write(int fd, byte[] buf, int len);
    /** read(2) of up to 512 bytes, waiting at most ms: bytes read, 0 if none came, or -errno. */
    static native int read(int fd, byte[] buf, int len, int ms);
}
