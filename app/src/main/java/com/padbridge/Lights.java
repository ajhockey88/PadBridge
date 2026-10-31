package com.padbridge;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * "Which player am I?": shows a controller's player on its own lights, where it has them and
 * Android lets the engine set them. DualSense and DualShock 4 light bars take the player's
 * color; DualSense, Switch Pro and Xbox 360 player lights show the player number. Best effort:
 * on many Android builds only the system may write these, and then nothing changes.
 */
final class Lights {
    private Lights() {}

    /** Each player's color, as on PadBridge's cards: blue, pink, green, yellow, purple, orange, teal, coral. */
    static final int[] COLORS = {0x78c8ff, 0xff9eb0, 0x8fe39c, 0xffd875, 0xc4a7ff, 0xffb06e, 0x6fdcd4, 0xff8686};

    /** Sets the controller's lights for this player. True if any light took it. */
    static boolean show(String sysfs, int player) {
        if (sysfs == null || sysfs.isEmpty() || player < 1) return false;
        File input = new File("/sys" + sysfs);
        // LEDs hang off the HID (or USB interface) device the input device belongs to.
        File owner = input.getParentFile() != null ? input.getParentFile().getParentFile() : null;
        File[] leds = owner != null ? new File(owner, "leds").listFiles() : null;
        if (leds == null) return false;
        int rgb = COLORS[(player - 1) % COLORS.length];
        int r = rgb >> 16 & 0xff, g = rgb >> 8 & 0xff, b = rgb & 0xff;
        boolean any = false;
        for (File led : leds) {
            String n = led.getName();
            if (n.endsWith(":rgb:indicator")) {
                // DualSense light bar (hid-playstation): a multicolor LED.
                any |= write(new File(led, "multi_intensity"), r + " " + g + " " + b)
                        & write(new File(led, "brightness"), "255");
            } else if (n.endsWith(":red")) {
                any |= write(new File(led, "brightness"), String.valueOf(r)); // DualShock 4 (hid-sony)
            } else if (n.endsWith(":green")) {
                any |= write(new File(led, "brightness"), String.valueOf(g));
            } else if (n.endsWith(":blue")) {
                any |= write(new File(led, "brightness"), String.valueOf(b));
            } else if (n.contains("player-")) {
                // DualSense (5 lights) and Switch Pro (4): light the player's number.
                int k = digit(n.substring(n.lastIndexOf("player-") + 7));
                boolean on = k == (player - 1) % 4 + 1;
                any |= write(new File(led, "brightness"), on ? "1" : "0");
            } else if (n.startsWith("xpad")) {
                // Xbox 360 ring: 6 to 9 light quarter 1 to 4.
                any |= write(new File(led, "brightness"), String.valueOf(5 + (player - 1) % 4 + 1));
            }
        }
        return any;
    }

    private static int digit(String s) {
        return !s.isEmpty() && Character.isDigit(s.charAt(0)) ? s.charAt(0) - '0' : -1;
    }

    private static boolean write(File f, String value) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
