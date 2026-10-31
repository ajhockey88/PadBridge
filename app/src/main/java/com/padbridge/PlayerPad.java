package com.padbridge;

import java.util.Locale;

/**
 * One virtual controller, "PadBridge Player N", as games see it. It always has the same
 * name, maker and model, so Android gives it the same identity every time and emulators keep
 * their mapping for it. Turns a {@link PadState} into the evdev events that changed since the
 * last frame.
 */
final class PlayerPad {
    static final int STICK_MAX = 32767, TRIGGER_MAX = 1023;
    // pid.codes' open-source vendor id, and a product id per player.
    private static final int VENDOR = 0x1209, PRODUCT = 0x5042;
    // Microsoft's Xbox 360 controller, for games that only know Xbox pads.
    private static final int XBOX_VENDOR = 0x045e, XBOX_PRODUCT = 0x028e;
    /** Rumble effects a game can upload at once. */
    private static final int FF_EFFECTS = 4;

    private static final int[] KEYS = {
            Evdev.BTN_SOUTH, Evdev.BTN_EAST, Evdev.BTN_NORTH, Evdev.BTN_WEST, Evdev.BTN_TL, Evdev.BTN_TR,
            Evdev.BTN_TL2, Evdev.BTN_TR2, Evdev.BTN_SELECT, Evdev.BTN_START, Evdev.BTN_MODE,
            Evdev.BTN_THUMBL, Evdev.BTN_THUMBR};
    /** KEYS[i] is PadState button i (A to R3). */

    final int player;
    final boolean xbox;
    /** ABS code for LX, LY, RX, RY, LT, RT. */
    private final int[] axisCode;
    private long lastButtons;
    private final int[] lastAxis = new int[PadState.AXES];
    private int lastHatX, lastHatY;

    PlayerPad(int player, String style) {
        this.player = player;
        xbox = Routing.XBOX.equals(style);
        // Android's generic layout reads the right stick from Z/RZ and triggers from BRAKE/GAS;
        // its Xbox 360 layout reads the right stick from RX/RY and triggers from Z/RZ.
        axisCode = xbox
                ? new int[] {Evdev.ABS_X, Evdev.ABS_Y, Evdev.ABS_RX, Evdev.ABS_RY, Evdev.ABS_Z, Evdev.ABS_RZ}
                : new int[] {Evdev.ABS_X, Evdev.ABS_Y, Evdev.ABS_Z, Evdev.ABS_RZ, Evdev.ABS_BRAKE, Evdev.ABS_GAS};
    }

    String name() { return "PadBridge Player " + player; }

    /** The `uinput` tool's "register" command for this pad. */
    String registerJson() {
        StringBuilder keys = new StringBuilder();
        for (int k : KEYS) keys.append(k).append(',');
        for (int i = 0; i < PadState.EXTRA_COUNT; i++) keys.append(Evdev.BTN_JOYSTICK + i).append(',');
        keys.setLength(keys.length() - 1);
        StringBuilder absBits = new StringBuilder(), absInfo = new StringBuilder();
        for (int a = 0; a < PadState.AXES; a++) {
            boolean trigger = a == PadState.LT || a == PadState.RT;
            absBits.append(axisCode[a]).append(',');
            absInfo.append(absInfo(axisCode[a], trigger ? 0 : -STICK_MAX - 1, trigger ? TRIGGER_MAX : STICK_MAX,
                    trigger ? 0 : 16, trigger ? 0 : 128)).append(',');
        }
        absBits.append(Evdev.ABS_HAT0X).append(',').append(Evdev.ABS_HAT0Y);
        absInfo.append(absInfo(Evdev.ABS_HAT0X, -1, 1, 0, 0)).append(',').append(absInfo(Evdev.ABS_HAT0Y, -1, 1, 0, 0));
        return String.format(Locale.US,
                "{\"id\":%d,\"command\":\"register\",\"name\":\"%s\",\"vid\":%d,\"pid\":%d,\"bus\":\"usb\","
                        // Rumble: Android sees a vibrator, and the tool reports when a game uses it.
                        + "\"ff_effects_max\":%d,"
                        + "\"configuration\":[{\"type\":%d,\"data\":[%d,%d]},{\"type\":%d,\"data\":[%s]},"
                        + "{\"type\":%d,\"data\":[%s]}],\"abs_info\":[%s]}",
                player, name(), xbox ? XBOX_VENDOR : VENDOR, xbox ? XBOX_PRODUCT : PRODUCT + player, FF_EFFECTS,
                Evdev.UI_SET_EVBIT, Evdev.EV_KEY, Evdev.EV_ABS, Evdev.UI_SET_KEYBIT, keys,
                Evdev.UI_SET_ABSBIT, absBits, absInfo);
    }

    private static String absInfo(int code, int min, int max, int fuzz, int flat) {
        return String.format(Locale.US, "{\"code\":%d,\"info\":{\"value\":0,\"minimum\":%d,\"maximum\":%d,"
                + "\"fuzz\":%d,\"flat\":%d,\"resolution\":0}}", code, min, max, fuzz, flat);
    }

    /**
     * The events (type, code, value triples) that bring this pad from what it last reported
     * to `s`; empty when nothing changed. The D-pad is reported as a hat, like most controllers.
     */
    int[] diff(PadState s) {
        int[] ev = new int[3 * (KEYS.length + PadState.EXTRA_COUNT + PadState.AXES + 2)];
        int n = 0;
        long changed = s.buttons ^ lastButtons;
        for (int b = 0; b < KEYS.length; b++) {
            if ((changed & (1L << b)) != 0) {
                ev[n++] = Evdev.EV_KEY; ev[n++] = KEYS[b]; ev[n++] = s.pressed(b) ? 1 : 0;
            }
        }
        for (int i = 0; i < PadState.EXTRA_COUNT; i++) {
            int b = PadState.EXTRA + i;
            if ((changed & (1L << b)) != 0) {
                ev[n++] = Evdev.EV_KEY; ev[n++] = Evdev.BTN_JOYSTICK + i; ev[n++] = s.pressed(b) ? 1 : 0;
            }
        }
        lastButtons = s.buttons;
        for (int a = 0; a < PadState.AXES; a++) {
            boolean trigger = a == PadState.LT || a == PadState.RT;
            int v = Math.round(s.axes[a] * (trigger ? TRIGGER_MAX : STICK_MAX));
            if (v != lastAxis[a]) {
                ev[n++] = Evdev.EV_ABS; ev[n++] = axisCode[a]; ev[n++] = v;
                lastAxis[a] = v;
            }
        }
        int hx = (s.pressed(PadState.RIGHT) ? 1 : 0) - (s.pressed(PadState.LEFT) ? 1 : 0);
        int hy = (s.pressed(PadState.DOWN) ? 1 : 0) - (s.pressed(PadState.UP) ? 1 : 0);
        if (hx != lastHatX) {
            ev[n++] = Evdev.EV_ABS; ev[n++] = Evdev.ABS_HAT0X; ev[n++] = hx;
            lastHatX = hx;
        }
        if (hy != lastHatY) {
            ev[n++] = Evdev.EV_ABS; ev[n++] = Evdev.ABS_HAT0Y; ev[n++] = hy;
            lastHatY = hy;
        }
        int[] out = new int[n];
        System.arraycopy(ev, 0, out, 0, n);
        return out;
    }
}
