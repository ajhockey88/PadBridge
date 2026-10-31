package com.padbridge;

/** Linux input event numbers (linux/input-event-codes.h) that PadBridge reads and writes. */
final class Evdev {
    private Evdev() {}

    static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    static final int SYN_REPORT = 0, SYN_DROPPED = 3;

    static final int KEY_BACK = 158, KEY_MENU = 139, KEY_HOMEPAGE = 172;
    static final int BTN_JOYSTICK = 0x120; // BTN_TRIGGER: first of the 16 generic buttons
    static final int BTN_SOUTH = 0x130, BTN_EAST = 0x131, BTN_C = 0x132, BTN_NORTH = 0x133, BTN_WEST = 0x134,
            BTN_Z = 0x135, BTN_TL = 0x136, BTN_TR = 0x137, BTN_TL2 = 0x138, BTN_TR2 = 0x139,
            BTN_SELECT = 0x13a, BTN_START = 0x13b, BTN_MODE = 0x13c, BTN_THUMBL = 0x13d, BTN_THUMBR = 0x13e;
    static final int BTN_DPAD_UP = 0x220, BTN_DPAD_DOWN = 0x221, BTN_DPAD_LEFT = 0x222, BTN_DPAD_RIGHT = 0x223;

    static final int ABS_X = 0x00, ABS_Y = 0x01, ABS_Z = 0x02, ABS_RX = 0x03, ABS_RY = 0x04, ABS_RZ = 0x05,
            ABS_GAS = 0x09, ABS_BRAKE = 0x0a, ABS_HAT0X = 0x10, ABS_HAT0Y = 0x11, ABS_MT_POSITION_X = 0x35;

    // uinput ioctl numbers, as the `uinput` shell tool's "configuration" expects them.
    static final int UI_SET_EVBIT = 100, UI_SET_KEYBIT = 101, UI_SET_ABSBIT = 103;

    /** _IOW('E', 0x90, int): takes the device for this file only, so Android stops seeing it. */
    static final int EVIOCGRAB = 0x40044590;
}
