package com.padbridge;

/**
 * One controller's buttons and sticks in PadBridge's own layout. Every physical controller is
 * read into one of these, and every virtual player pad is written from one, so a player pad
 * reports the same buttons whichever controller is driving it.
 */
final class PadState {
    // Button bits.
    static final int A = 0, B = 1, X = 2, Y = 3, L1 = 4, R1 = 5, L2 = 6, R2 = 7, SELECT = 8, START = 9,
            MODE = 10, L3 = 11, R3 = 12, UP = 13, DOWN = 14, LEFT = 15, RIGHT = 16;
    /** The standard buttons, A to RIGHT, which a controller's button map can rearrange. */
    static final int BUTTONS = 17;
    /** BUTTON_1 to BUTTON_16 (evdev 288 to 303) of controllers without a standard layout. */
    static final int EXTRA = 17, EXTRA_COUNT = 16;

    // Axes: sticks from -1 to 1 (down and right are positive), triggers from 0 to 1.
    static final int LX = 0, LY = 1, RX = 2, RY = 3, LT = 4, RT = 5, AXES = 6;

    long buttons;
    final float[] axes = new float[AXES];

    boolean pressed(int button) { return (buttons & (1L << button)) != 0; }

    void set(int button, boolean down) {
        if (down) buttons |= 1L << button;
        else buttons &= ~(1L << button);
    }

    void clear() {
        buttons = 0;
        for (int i = 0; i < AXES; i++) axes[i] = 0;
    }

    /**
     * Adds another controller to this one, for players shared by two controllers: a button is
     * down if it's down on either, and each stick or trigger follows whichever is pushed further.
     */
    void merge(PadState other) {
        buttons |= other.buttons;
        for (int i = 0; i < AXES; i++) {
            if (Math.abs(other.axes[i]) > Math.abs(axes[i])) axes[i] = other.axes[i];
        }
    }

    void copyFrom(PadState other) {
        buttons = other.buttons;
        System.arraycopy(other.axes, 0, axes, 0, AXES);
    }
}
