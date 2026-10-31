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
    interface Listener {
        void onFrame(PhysicalPad pad);

        /** The Thor's Back or Home button: it belongs to Android, not to a player. */
        void onSystemKey(int code, boolean down);
    }

    final InputScan.Device dev;
    final PadMapper mapper;
    final long connectedAt;
    int player;
    /** The Thor's own controls. */
    boolean builtin;
    String error = "";
    /**
     * How often the controller reports (ms between reports while it's in use), and how long
     * PadBridge takes from a report to passing it to the player pad. Averages; 0 until known.
     */
    volatile float intervalMs, addedMs;
    /** Its battery, or null if it doesn't report one; read now and then by the engine. */
    volatile Battery battery;
    long batteryReadAt;
    /** The low battery notice was shown, until it charges again. */
    boolean warnedLow;
    /** The Thor's key codes already written to the log, to diagnose its buttons. */
    private final java.util.BitSet loggedKeys = new java.util.BitSet();
    /** It turned down a rumble effect. */
    volatile boolean cantRumble;
    private boolean monotonic;
    private long lastReportUs;
    private int fdNum = -1, effect = -1, effectStrength;
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
        synchronized (this) {
            fdNum = Native.loaded() ? Native.fd(fd) : -1;
            effect = -1;
        }
        // Timestamps on the same clock as System.nanoTime(), to measure PadBridge's delay.
        monotonic = fdNum >= 0 && Native.monotonicClock(fdNum) == 0;
        intervalMs = addedMs = 0;
        lastReportUs = 0;
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
                        long reportUs = 0;
                        for (int off = 0; off + size <= n; off += size) {
                            int p = off + tail;
                            int type = (buf[p] & 0xff) | (buf[p + 1] & 0xff) << 8;
                            int code = (buf[p + 2] & 0xff) | (buf[p + 3] & 0xff) << 8;
                            int value = (buf[p + 4] & 0xff) | (buf[p + 5] & 0xff) << 8
                                    | (buf[p + 6] & 0xff) << 16 | buf[p + 7] << 24;
                            if (builtin && type == Evdev.EV_KEY && value == 1 && !loggedKeys.get(code)) {
                                // Which key each of the Thor's buttons sends, once each, for diagnostics.
                                loggedKeys.set(code);
                                Engine.log("Thor button: key " + code + (Evdev.isSystemKey(code) ? " (to Android)"
                                        : mapper.button(code) >= 0 ? "" : " (not used)"));
                            }
                            if (builtin && type == Evdev.EV_KEY && Evdev.isSystemKey(code)) {
                                // Held-key repeats (value 2) aren't needed: Android times long presses itself.
                                if (value != 2) listener.onSystemKey(code, value != 0);
                                continue;
                            }
                            if (mapper.handle(type, code, value)) {
                                frame = true;
                                reportUs = timestampUs(buf, off, size);
                            }
                        }
                        if (frame) {
                            listener.onFrame(PhysicalPad.this);
                            measure(reportUs);
                        }
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

    private static long timestampUs(byte[] b, int off, int size) {
        int w = size == 24 ? 8 : 4;
        return le(b, off, w) * 1000000L + le(b, off + w, w);
    }

    private static long le(byte[] b, int off, int bytes) {
        long v = 0;
        for (int i = bytes - 1; i >= 0; i--) v = v << 8 | (b[off + i] & 0xff);
        return v;
    }

    /** Updates the averages after a report was passed on. */
    private void measure(long reportUs) {
        if (reportUs <= 0) return;
        long nowUs = monotonic ? System.nanoTime() / 1000 : System.currentTimeMillis() * 1000;
        long added = nowUs - reportUs;
        if (added >= 0 && added < 1000000) addedMs = average(addedMs, added / 1000f);
        long gap = reportUs - lastReportUs;
        // Only while it's in use: a pause between presses isn't the controller's rate.
        if (lastReportUs != 0 && gap > 0 && gap < 60000) intervalMs = average(intervalMs, gap / 1000f);
        lastReportUs = reportUs;
    }

    private static float average(float avg, float v) { return avg == 0 ? v : avg * 0.9f + v * 0.1f; }

    /**
     * Starts or stops rumble at this strength (1 to 100), on this controller's open node. False
     * if it can't rumble. Effects last at most 5 seconds, in case a stop never comes.
     */
    synchronized boolean rumble(boolean on, int strength) {
        if (!open || fdNum < 0 || cantRumble) return false;
        if (!on) {
            if (effect >= 0) Native.rumblePlay(fdNum, effect, 0);
            return true;
        }
        if (effect < 0 || strength != effectStrength) {
            int magnitude = Math.round(0xffff * Math.min(100, strength) / 100f);
            int id = Native.rumbleUpload(fdNum, effect, magnitude, magnitude, 5000);
            if (id < 0) {
                cantRumble = true;
                effect = -1;
                return false;
            }
            effect = id;
            effectStrength = strength;
        }
        return Native.rumblePlay(fdNum, effect, 1) == 0;
    }

    /** Lets go of the controller; Android sees it again. */
    void close() {
        synchronized (this) {
            fdNum = -1; // the kernel drops this file's effects when it closes
            effect = -1;
        }
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
