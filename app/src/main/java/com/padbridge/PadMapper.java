package com.padbridge;

/**
 * Reads one physical controller's raw input events into a {@link PadState}. Buttons keep their
 * standard meaning; sticks, triggers and the D-pad are measured against the controller's own
 * ranges, so every controller ends up on the same scale.
 */
final class PadMapper {
    // What each ABS code of this controller is.
    private static final int NONE = -1, HAT_X = 100, HAT_Y = 101;
    private final int[] role = new int[0x40];
    private final float[] center = new float[0x40], half = new float[0x40], min = new float[0x40], span = new float[0x40];

    /** Swaps A with B and X with Y, for controllers with Nintendo's button layout. */
    volatile boolean nintendo;
    /** Stick deadzone, 0 to 0.3 of the stick's reach. */
    volatile float deadzone;
    volatile boolean invertRy;
    /** The controller's button map (see Routing.PadSettings.map), or null for none. */
    volatile int[] map;

    final PadState state = new PadState();
    /** Analog triggers seen, so their digital buttons don't fight them. */
    private final boolean analogL2, analogR2;

    PadMapper(InputScan.Device dev) {
        for (int i = 0; i < role.length; i++) role[i] = NONE;
        boolean rightOnRx = dev.abs.containsKey(Evdev.ABS_RX) && dev.abs.containsKey(Evdev.ABS_RY);
        set(dev, Evdev.ABS_X, PadState.LX);
        set(dev, Evdev.ABS_Y, PadState.LY);
        if (rightOnRx) {
            // Xbox USB, PlayStation and most others: right stick on RX/RY, triggers on Z/RZ.
            set(dev, Evdev.ABS_RX, PadState.RX);
            set(dev, Evdev.ABS_RY, PadState.RY);
            set(dev, Evdev.ABS_Z, PadState.LT);
            set(dev, Evdev.ABS_RZ, PadState.RT);
        } else {
            // Bluetooth HID pads (Xbox over Bluetooth, 8BitDo...): right stick on Z/RZ.
            set(dev, Evdev.ABS_Z, PadState.RX);
            set(dev, Evdev.ABS_RZ, PadState.RY);
        }
        set(dev, Evdev.ABS_BRAKE, PadState.LT);
        set(dev, Evdev.ABS_GAS, PadState.RT);
        set(dev, Evdev.ABS_HAT0X, HAT_X);
        set(dev, Evdev.ABS_HAT0Y, HAT_Y);
        analogL2 = hasRole(PadState.LT);
        analogR2 = hasRole(PadState.RT);
    }

    private void set(InputScan.Device dev, int code, int what) {
        int[] r = dev.abs.get(code);
        if (r == null || code >= role.length) return;
        role[code] = what;
        min[code] = r[0];
        span[code] = r[1] - r[0];
        center[code] = (r[0] + r[1]) / 2f;
        half[code] = (r[1] - r[0]) / 2f;
    }

    private boolean hasRole(int what) {
        for (int r : role) if (r == what) return true;
        return false;
    }

    /** Takes one raw event. Returns true at the end of a frame (SYN_REPORT). */
    boolean handle(int type, int code, int value) {
        if (type == Evdev.EV_SYN) return code == Evdev.SYN_REPORT;
        if (type == Evdev.EV_KEY) {
            int bit = button(code);
            if (bit >= 0) state.set(bit, value != 0);
        } else if (type == Evdev.EV_ABS && code < role.length && role[code] != NONE) {
            int r = role[code];
            if (r == HAT_X) {
                float v = value - center[code];
                state.set(PadState.LEFT, v < -0.25f * half[code]);
                state.set(PadState.RIGHT, v > 0.25f * half[code]);
            } else if (r == HAT_Y) {
                float v = value - center[code];
                state.set(PadState.UP, v < -0.25f * half[code]);
                state.set(PadState.DOWN, v > 0.25f * half[code]);
            } else if (r == PadState.LT || r == PadState.RT) {
                state.axes[r] = clamp((value - min[code]) / span[code], 0f, 1f);
            } else {
                state.axes[r] = clamp((value - center[code]) / half[code], -1f, 1f);
            }
        }
        return false;
    }

    /** The PadState button for an evdev key code, or -1. */
    int button(int code) {
        switch (code) {
            case Evdev.BTN_SOUTH: return nintendo ? PadState.B : PadState.A;
            case Evdev.BTN_EAST: return nintendo ? PadState.A : PadState.B;
            case Evdev.BTN_NORTH: return nintendo ? PadState.Y : PadState.X;
            case Evdev.BTN_WEST: return nintendo ? PadState.X : PadState.Y;
            case Evdev.BTN_TL: return PadState.L1;
            case Evdev.BTN_TR: return PadState.R1;
            case Evdev.BTN_TL2: return PadState.L2;
            case Evdev.BTN_TR2: return PadState.R2;
            case Evdev.BTN_SELECT: case Evdev.KEY_BACK: return PadState.SELECT;
            case Evdev.BTN_START: case Evdev.KEY_MENU: return PadState.START;
            case Evdev.BTN_MODE: case Evdev.KEY_HOMEPAGE: return PadState.MODE;
            case Evdev.BTN_THUMBL: return PadState.L3;
            case Evdev.BTN_THUMBR: return PadState.R3;
            case Evdev.BTN_DPAD_UP: return PadState.UP;
            case Evdev.BTN_DPAD_DOWN: return PadState.DOWN;
            case Evdev.BTN_DPAD_LEFT: return PadState.LEFT;
            case Evdev.BTN_DPAD_RIGHT: return PadState.RIGHT;
            default:
                if (code >= Evdev.BTN_JOYSTICK && code < Evdev.BTN_JOYSTICK + PadState.EXTRA_COUNT) {
                    return PadState.EXTRA + code - Evdev.BTN_JOYSTICK;
                }
                return -1;
        }
    }

    /**
     * This controller's state as a player pad reports it: a trigger that's only a button
     * presses its axis fully, and an analog trigger presses its button past halfway, so either
     * kind of controller looks the same to a game.
     */
    void normalizedInto(PadState out) {
        out.copyFrom(state);
        if (!analogL2 && state.pressed(PadState.L2)) out.axes[PadState.LT] = 1f;
        if (!analogR2 && state.pressed(PadState.R2)) out.axes[PadState.RT] = 1f;
        if (analogL2 && state.axes[PadState.LT] > 0.5f) out.set(PadState.L2, true);
        if (analogR2 && state.axes[PadState.RT] > 0.5f) out.set(PadState.R2, true);
        deadzone(out, PadState.LX, PadState.LY);
        deadzone(out, PadState.RX, PadState.RY);
        if (invertRy) out.axes[PadState.RY] = -out.axes[PadState.RY];
        int[] m = map;
        if (m != null) remap(out, m);
    }

    private static final long STANDARD = (1L << PadState.BUTTONS) - 1;

    /**
     * Applies a button map. An analog trigger goes where its button goes (L2 mapped to R2
     * moves the whole trigger), and any other button mapped to a trigger presses it fully.
     */
    static void remap(PadState out, int[] m) {
        long in = out.buttons, res = in & ~STANDARD;
        float lt = out.axes[PadState.LT], rt = out.axes[PadState.RT], newLt = 0, newRt = 0;
        for (int b = 0; b < PadState.BUTTONS; b++) {
            int to = m[b];
            boolean trigger = b == PadState.L2 || b == PadState.R2;
            if (trigger) {
                // Its axis already says how far it's pressed (fully, for a button-only trigger).
                float v = b == PadState.L2 ? lt : rt;
                if (to == PadState.L2) newLt = Math.max(newLt, v);
                else if (to == PadState.R2) newRt = Math.max(newRt, v);
            }
            if ((in & (1L << b)) == 0 || to < 0) continue;
            res |= 1L << to;
            if (trigger) continue;
            if (to == PadState.L2) newLt = 1f;
            else if (to == PadState.R2) newRt = 1f;
        }
        out.buttons = res;
        out.axes[PadState.LT] = newLt;
        out.axes[PadState.RT] = newRt;
    }

    /** Ignores a stick near its center, and scales the rest so the edge is still full. */
    private void deadzone(PadState out, int x, int y) {
        float dz = deadzone;
        if (dz <= 0) return;
        float vx = out.axes[x], vy = out.axes[y];
        float m = (float) Math.hypot(vx, vy);
        float scale = m <= dz ? 0f : Math.min(1f, (m - dz) / (1f - dz)) / m;
        out.axes[x] = vx * scale;
        out.axes[y] = vy * scale;
    }

    /** Takes this controller's settings. */
    void apply(boolean nintendo, Routing.PadSettings s) {
        this.nintendo = nintendo;
        deadzone = s.deadzone / 100f;
        invertRy = s.invertRy;
        map = s.map == null ? null : s.map.clone();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
