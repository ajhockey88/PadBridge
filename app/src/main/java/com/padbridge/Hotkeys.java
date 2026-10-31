package com.padbridge;

/**
 * Button combos that work Android or PadBridge from any controller, mid-game: Android's Home,
 * Back, swapping Players 1 and 2, or opening PadBridge. Each action can have one combo, or none
 * (the default, so games keep every combo to themselves).
 */
final class Hotkeys {
    private Hotkeys() {}

    /** The broadcast the engine sends HotkeyReceiver to swap players (only the shell user may send it). */
    static final String ACTION_SWAP = "com.padbridge.action.SWAP";
    static final String HOME = "home", BACK = "back", SWAP = "swap", OPEN = "open";
    static final String[] ACTIONS = {HOME, BACK, SWAP, OPEN};
    static final String[] ACTION_NAMES = {"🏠  Android Home", "↩️  Android Back", "🔁  Swap Players 1 and 2",
            "🌉  Open PadBridge"};

    /** The combos to pick from: id, name, and the PadState buttons held together. */
    static final String[] COMBO_IDS = {"select_start", "guide_a", "guide_b", "guide_x", "guide_y", "guide_select",
            "guide_start", "l3_r3", "select_l1", "select_r1"};
    static final String[] COMBO_NAMES = {"Select + Start", "Guide + A", "Guide + B", "Guide + X", "Guide + Y",
            "Guide + Select", "Guide + Start", "L3 + R3 (both sticks pressed)", "Select + L1", "Select + R1"};
    private static final int[][] COMBO_BUTTONS = {{PadState.SELECT, PadState.START}, {PadState.MODE, PadState.A},
            {PadState.MODE, PadState.B}, {PadState.MODE, PadState.X}, {PadState.MODE, PadState.Y},
            {PadState.MODE, PadState.SELECT}, {PadState.MODE, PadState.START}, {PadState.L3, PadState.R3},
            {PadState.SELECT, PadState.L1}, {PadState.SELECT, PadState.R1}};

    /** The buttons of a combo as a PadState button mask, or 0 for an unknown id. */
    static long mask(String combo) {
        for (int i = 0; i < COMBO_IDS.length; i++) {
            if (COMBO_IDS[i].equals(combo)) {
                long m = 0;
                for (int b : COMBO_BUTTONS[i]) m |= 1L << b;
                return m;
            }
        }
        return 0;
    }

    static String comboName(String combo) {
        for (int i = 0; i < COMBO_IDS.length; i++) if (COMBO_IDS[i].equals(combo)) return COMBO_NAMES[i];
        return "Off";
    }

    /** What the engine runs (as the shell user) for an action. */
    static String[] command(String action) {
        switch (action) {
            case HOME: return new String[] {"input", "keyevent", "3"};
            case BACK: return new String[] {"input", "keyevent", "4"};
            case SWAP: return new String[] {"am", "broadcast", "-n", "com.padbridge/.HotkeyReceiver",
                    "-a", ACTION_SWAP};
            case OPEN: return new String[] {"am", "start", "-n", "com.padbridge/.LauncherActivity"};
            default: return null;
        }
    }
}
