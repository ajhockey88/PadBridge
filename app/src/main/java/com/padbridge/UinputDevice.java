package com.padbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * One virtual input device made straight on /dev/uinput by the engine, through the native
 * helper. A report from a controller reaches it in one write(2), from the thread that read the
 * report, which is the fastest way Android has to pass input on: no `uinput` tool process, pipe
 * or JSON in between. Games' rumble comes back on the same file, read on its own thread.
 */
final class UinputDevice {
    interface Rumble {
        /** A game started (or with on false, stopped) rumble: magnitudes 0 to 0xffff, ms 0 if open-ended. */
        void onRumble(boolean on, int strong, int weak, int ms);
    }

    // linux/uinput.h, for aarch64 (_IOW and _IOWR sizes included).
    static final int UI_DEV_CREATE = 0x5501, UI_DEV_DESTROY = 0x5502, UI_DEV_SETUP = 0x405c5503,
            UI_ABS_SETUP = 0x401c5504, UI_SET_EVBIT = 0x40045564, UI_SET_KEYBIT = 0x40045565,
            UI_SET_ABSBIT = 0x40045567, UI_SET_FFBIT = 0x4004556b,
            UI_BEGIN_FF_UPLOAD = 0xc06855c8, UI_END_FF_UPLOAD = 0x406855c9,
            UI_BEGIN_FF_ERASE = 0xc00c55ca, UI_END_FF_ERASE = 0x400c55cb;
    static final int EV_FF = 0x15, EV_UINPUT = 0x0101, UI_FF_UPLOAD = 1, UI_FF_ERASE = 2, FF_RUMBLE = 0x50,
            BUS_USB = 3;
    /** struct uinput_ff_upload: request_id, retval, then two 48-byte struct ff_effect. */
    private static final int UPLOAD_SIZE = 104, ERASE_SIZE = 12;

    private final int fd;
    private final String name;
    private final Rumble rumble;
    private final byte[] out = new byte[2048];
    private volatile boolean open = true;
    /** Each uploaded effect's strong and weak magnitude and length, by effect id. */
    private final int[] strong = new int[16], weak = new int[16], length = new int[16];

    private UinputDevice(int fd, String name, Rumble rumble) {
        this.fd = fd;
        this.name = name;
        this.rumble = rumble;
    }

    /** True if this process can make devices on /dev/uinput (the native helper loaded, and allowed). */
    static boolean available() {
        if (!Native.loaded()) return false;
        try {
            int fd = Native.uinputOpen();
            if (fd < 0) {
                Engine.log("can't open /dev/uinput (error " + -fd + ")");
                return false;
            }
            Native.close(fd);
            return true;
        } catch (Throwable t) {
            Engine.log("no direct uinput: " + t);
            return false;
        }
    }

    /**
     * Makes a device. `abs` holds (code, minimum, maximum, fuzz, flat) for each axis. With
     * rumble set, the device has `ffEffects` rumble slots and passes games' rumble to it.
     */
    static UinputDevice create(String name, int vendor, int product, int[] keys, int[] abs, int ffEffects,
                               Rumble rumble) throws IOException {
        int fd = Native.uinputOpen();
        if (fd < 0) throw new IOException("can't open /dev/uinput (error " + -fd + ")");
        UinputDevice d = new UinputDevice(fd, name, rumble);
        try {
            d.check(Native.ioctlValue(fd, UI_SET_EVBIT, Evdev.EV_KEY), "EV_KEY");
            for (int k : keys) d.check(Native.ioctlValue(fd, UI_SET_KEYBIT, k), "key " + k);
            if (abs.length > 0) {
                d.check(Native.ioctlValue(fd, UI_SET_EVBIT, Evdev.EV_ABS), "EV_ABS");
                byte[] a = new byte[28];
                for (int i = 0; i + 4 < abs.length; i += 5) {
                    d.check(Native.ioctlValue(fd, UI_SET_ABSBIT, abs[i]), "axis " + abs[i]);
                    // struct uinput_abs_setup: u16 code, then struct input_absinfo at offset 4.
                    java.util.Arrays.fill(a, (byte) 0);
                    put16(a, 0, abs[i]);
                    put32(a, 8, abs[i + 1]);
                    put32(a, 12, abs[i + 2]);
                    put32(a, 16, abs[i + 3]);
                    put32(a, 20, abs[i + 4]);
                    d.check(Native.ioctlStruct(fd, UI_ABS_SETUP, a, a.length), "axis setup " + abs[i]);
                }
            }
            boolean ff = rumble != null && ffEffects > 0;
            if (ff) {
                d.check(Native.ioctlValue(fd, UI_SET_EVBIT, EV_FF), "EV_FF");
                d.check(Native.ioctlValue(fd, UI_SET_FFBIT, FF_RUMBLE), "FF_RUMBLE");
            }
            // struct uinput_setup: struct input_id (bus, vendor, product, version), name[80], ff_effects_max.
            byte[] s = new byte[92];
            put16(s, 0, BUS_USB);
            put16(s, 2, vendor);
            put16(s, 4, product);
            put16(s, 6, 1);
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(n, 0, s, 8, Math.min(n.length, 79));
            put32(s, 88, ff ? Math.min(ffEffects, 16) : 0);
            d.check(Native.ioctlStruct(fd, UI_DEV_SETUP, s, s.length), "setup");
            d.check(Native.ioctlValue(fd, UI_DEV_CREATE, 0), "create");
        } catch (IOException e) {
            Native.close(fd);
            throw e;
        }
        if (rumble != null) d.listen();
        return d;
    }

    private void check(int r, String what) throws IOException {
        if (r < 0) throw new IOException(name + ": uinput " + what + " failed (error " + -r + ")");
    }

    /**
     * Sends events (type, code, value triples; `n` ints of `ev`) and a SYN_REPORT, in one
     * write. False if the write failed.
     */
    boolean send(int[] ev, int n) {
        if (!open) return false;
        int size = Engine.EVENT_SIZE, tail = size - 8;
        int count = Math.min(n / 3, out.length / size - 1), off = 0;
        for (int i = 0; i < count; i++, off += size) event(off + tail, ev[3 * i], ev[3 * i + 1], ev[3 * i + 2]);
        event(off + tail, Evdev.EV_SYN, Evdev.SYN_REPORT, 0);
        off += size;
        // The kernel stamps the time itself, so the timestamp bytes (left at 0) don't matter.
        return Native.write(fd, out, off) == off;
    }

    private void event(int p, int type, int code, int value) {
        put16(out, p, type);
        put16(out, p + 2, code);
        put32(out, p + 4, value);
    }

    /** Removes the device: Android sees it unplugged straight away. */
    void destroy() {
        if (!open) return;
        open = false;
        Native.ioctlValue(fd, UI_DEV_DESTROY, 0);
        if (rumble == null) Native.close(fd); // else the reading thread closes it as it ends
    }

    /** Answers games' rumble: effect uploads and erases, and plays. */
    private void listen() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int size = Engine.EVENT_SIZE, tail = size - 8;
                byte[] in = new byte[size * 16];
                byte[] up = new byte[UPLOAD_SIZE], er = new byte[ERASE_SIZE];
                while (open) {
                    int n = Native.read(fd, in, in.length, 500);
                    if (n < 0) break;
                    for (int off = 0; off + size <= n; off += size) {
                        int p = off + tail;
                        int type = get16(in, p), code = get16(in, p + 2), value = get32(in, p + 4);
                        if (type == EV_UINPUT && code == UI_FF_UPLOAD) {
                            java.util.Arrays.fill(up, (byte) 0);
                            put32(up, 0, value);
                            if (Native.ioctlStruct(fd, UI_BEGIN_FF_UPLOAD, up, up.length) < 0) continue;
                            // The new effect at offset 8: type, id, direction, trigger (2 x u16),
                            // replay length and delay, then the rumble magnitudes from offset 24.
                            int id = (short) get16(up, 10);
                            if (id >= 0 && id < strong.length && get16(up, 8) == FF_RUMBLE) {
                                length[id] = get16(up, 18);
                                strong[id] = get16(up, 24);
                                weak[id] = get16(up, 26);
                            }
                            put32(up, 4, 0); // retval: accepted
                            Native.ioctlStruct(fd, UI_END_FF_UPLOAD, up, up.length);
                        } else if (type == EV_UINPUT && code == UI_FF_ERASE) {
                            java.util.Arrays.fill(er, (byte) 0);
                            put32(er, 0, value);
                            if (Native.ioctlStruct(fd, UI_BEGIN_FF_ERASE, er, er.length) < 0) continue;
                            put32(er, 4, 0);
                            Native.ioctlStruct(fd, UI_END_FF_ERASE, er, er.length);
                        } else if (type == EV_FF && code < strong.length) {
                            if (value > 0) rumble.onRumble(true, strong[code], weak[code], length[code]);
                            else rumble.onRumble(false, 0, 0, 0);
                        }
                    }
                }
                Native.close(fd);
            }
        }, "PadBridge rumble " + name);
        t.setDaemon(true);
        t.start();
    }

    static void put16(byte[] b, int p, int v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
    }

    static void put32(byte[] b, int p, int v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
        b[p + 2] = (byte) (v >> 16);
        b[p + 3] = (byte) (v >> 24);
    }

    static int get16(byte[] b, int p) { return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8; }

    static int get32(byte[] b, int p) { return get16(b, p) | get16(b, p + 2) << 16; }
}
