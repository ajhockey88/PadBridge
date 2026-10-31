package com.padbridge;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * A real controller the engine has taken over: it holds the controller's /dev/input node
 * exclusively (so Android and games stop seeing its own input) and reads every event into a
 * {@link PadMapper}. Closing the node gives the controller back to Android.
 */
final class PhysicalPad {
    interface Listener { void onFrame(PhysicalPad pad); }

    final InputScan.Device dev;
    final PadMapper mapper;
    final long connectedAt;
    int player;
    /** The Thor's own controls. */
    boolean builtin;
    String error = "";
    private FileInputStream in;
    private Thread reader;
    private volatile boolean open;

    PhysicalPad(InputScan.Device dev, long connectedAt) {
        this.dev = dev;
        this.connectedAt = connectedAt;
        mapper = new PadMapper(dev);
    }

    boolean isOpen() { return open; }

    /** Opens and grabs the node, then reads it on its own thread. False if it couldn't. */
    boolean open(final Listener listener) {
        if (open) return true;
        FileDescriptor fd;
        try {
            in = new FileInputStream(dev.path);
            fd = in.getFD();
        } catch (IOException e) {
            error = "Can't open " + dev.path + " (" + e.getMessage() + ")";
            closeQuietly();
            return false;
        }
        if (!grab(fd)) {
            closeQuietly();
            return false;
        }
        open = true;
        error = "";
        mapper.state.clear();
        final int size = Engine.EVENT_SIZE;
        reader = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[size * 64];
                int tail = size - 8; // type, code and value sit after the timestamp
                try {
                    while (open) {
                        int n = in.read(buf);
                        if (n <= 0) break;
                        boolean frame = false;
                        for (int off = 0; off + size <= n; off += size) {
                            int p = off + tail;
                            int type = (buf[p] & 0xff) | (buf[p + 1] & 0xff) << 8;
                            int code = (buf[p + 2] & 0xff) | (buf[p + 3] & 0xff) << 8;
                            int value = (buf[p + 4] & 0xff) | (buf[p + 5] & 0xff) << 8
                                    | (buf[p + 6] & 0xff) << 16 | buf[p + 7] << 24;
                            if (mapper.handle(type, code, value)) frame = true;
                        }
                        if (frame) listener.onFrame(PhysicalPad.this);
                    }
                } catch (IOException ignored) {
                    // Unplugged, or closed by close().
                }
                if (open) {
                    // The controller went away: let go of its buttons.
                    open = false;
                    mapper.state.clear();
                    listener.onFrame(PhysicalPad.this);
                }
            }
        }, "PadBridge read " + dev.path);
        reader.start();
        return true;
    }

    /** Lets go of the controller; Android sees it again. */
    void close() {
        open = false;
        closeQuietly();
        mapper.state.clear();
    }

    private void closeQuietly() {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        in = null;
    }

    /**
     * EVIOCGRAB: from now on this file is the only one that gets the controller's events.
     * android.system.Os has the ioctl, with a pointer argument, which the kernel only checks
     * for being non-zero.
     */
    private boolean grab(FileDescriptor fd) {
        try {
            Class<?> os = Class.forName("android.system.Os");
            try {
                Method m = os.getMethod("ioctlInt", FileDescriptor.class, int.class);
                m.invoke(null, fd, Evdev.EVIOCGRAB);
                return true;
            } catch (NoSuchMethodException e) {
                // Android 13 and older: ioctlInt(fd, cmd, Int32Ref).
                Class<?> ref = Class.forName("android.system.Int32Ref");
                Object one = ref.getConstructor(int.class).newInstance(1);
                Method m = os.getMethod("ioctlInt", FileDescriptor.class, int.class, ref);
                m.invoke(null, fd, Evdev.EVIOCGRAB, one);
                return true;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause);
            error = msg.contains("EBUSY")
                    ? "Another app has this controller (for example Docking Enhancer)"
                    : "Couldn't take over this controller (" + msg + ")";
        } catch (Exception e) {
            error = "Couldn't take over this controller (" + e + ")";
        }
        return false;
    }
}
