package com.padbridge;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the controllers among Linux's input devices. The list and each device's buttons come
 * from /proc/bus/input/devices; stick ranges come from `getevent -p`, which can ask the kernel
 * for them (Java can't make those ioctl calls itself).
 */
final class InputScan {
    static final int BUS_USB = 0x03, BUS_BLUETOOTH = 0x05;
    /** Names of PadBridge's own virtual pads, which are never bridged again. */
    static final String OWN_PREFIX = "PadBridge";

    private InputScan() {}

    /** One /dev/input/event node. */
    static final class Device {
        String path = "", name = "", phys = "", uniq = "";
        int bus, vendor, product;
        final BitSet keys = new BitSet();
        final BitSet absBits = new BitSet();
        /** Stick and trigger ranges, by ABS code: {min, max}. Filled by {@link #applyRanges}. */
        final TreeMap<Integer, int[]> abs = new TreeMap<>();

        boolean isGamepad() {
            if (name.startsWith(OWN_PREFIX)) return false;
            if (absBits.get(Evdev.ABS_MT_POSITION_X)) return false; // a touchscreen
            boolean standard = keys.nextSetBit(Evdev.BTN_SOUTH) >= 0 && keys.nextSetBit(Evdev.BTN_SOUTH) <= Evdev.BTN_THUMBR;
            boolean generic = keys.nextSetBit(Evdev.BTN_JOYSTICK) >= 0 && keys.nextSetBit(Evdev.BTN_JOYSTICK) < Evdev.BTN_SOUTH
                    && absBits.get(Evdev.ABS_X) && absBits.get(Evdev.ABS_Y);
            return standard || generic;
        }

        boolean isExternal() { return bus == BUS_USB || bus == BUS_BLUETOOTH; }

        /**
         * Stays the same each time this controller connects: Bluetooth controllers carry their
         * address; for others the maker, model and name have to do.
         */
        String key() {
            String id = String.format(Locale.US, "%04x:%04x:", vendor, product);
            return id + (uniq.isEmpty() ? name : uniq);
        }

        String connection() {
            if (bus == BUS_BLUETOOTH) return "Bluetooth";
            if (bus == BUS_USB) return "USB";
            return "Built-in";
        }
    }

    /** Parses /proc/bus/input/devices. bitsPerWord is the kernel's long size (64 on the Thor). */
    static List<Device> parseProcDevices(String text, int bitsPerWord) {
        List<Device> out = new ArrayList<>();
        Device dev = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (dev != null && !dev.path.isEmpty()) out.add(dev);
                dev = null;
                continue;
            }
            if (line.length() < 3) continue;
            if (dev == null) dev = new Device();
            String body = line.substring(3);
            switch (line.charAt(0)) {
                case 'I':
                    dev.bus = hexField(body, "Bus=");
                    dev.vendor = hexField(body, "Vendor=");
                    dev.product = hexField(body, "Product=");
                    break;
                case 'N':
                    dev.name = quoted(body, "Name=");
                    break;
                case 'P':
                    dev.phys = after(body, "Phys=");
                    break;
                case 'U':
                    dev.uniq = after(body, "Uniq=");
                    break;
                case 'H':
                    for (String h : after(body, "Handlers=").split("\\s+")) {
                        if (h.startsWith("event")) dev.path = "/dev/input/" + h;
                    }
                    break;
                case 'B':
                    if (body.startsWith("KEY=")) bitmap(body.substring(4), bitsPerWord, dev.keys);
                    else if (body.startsWith("ABS=")) bitmap(body.substring(4), bitsPerWord, dev.absBits);
                    break;
                default:
                    break;
            }
        }
        if (dev != null && !dev.path.isEmpty()) out.add(dev);
        return out;
    }

    private static final Pattern ABS_LINE = Pattern.compile(
            "([0-9a-fA-F]{4})\\s*: value (-?\\d+), min (-?\\d+), max (-?\\d+)");

    /** Reads the stick and trigger ranges out of `getevent -p <node>`. */
    static void applyRanges(Device dev, String geteventOutput) {
        Matcher m = ABS_LINE.matcher(geteventOutput);
        while (m.find()) {
            int code = Integer.parseInt(m.group(1), 16);
            int min = Integer.parseInt(m.group(3)), max = Integer.parseInt(m.group(4));
            if (max > min) dev.abs.put(code, new int[] {min, max});
        }
    }

    /** "7fff000000000000 0 0 0 0": words, most significant first. */
    static void bitmap(String words, int bitsPerWord, BitSet into) {
        String[] parts = words.trim().split("\\s+");
        for (int w = 0; w < parts.length; w++) {
            long value;
            try {
                value = Long.parseUnsignedLong(parts[parts.length - 1 - w], 16);
            } catch (NumberFormatException e) {
                continue;
            }
            for (int b = 0; b < bitsPerWord; b++) {
                if ((value & (1L << b)) != 0) into.set(w * bitsPerWord + b);
            }
        }
    }

    private static int hexField(String body, String field) {
        String v = after(body, field);
        int sp = v.indexOf(' ');
        if (sp >= 0) v = v.substring(0, sp);
        try {
            return Integer.parseInt(v, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String after(String body, String field) {
        int i = body.indexOf(field);
        return i < 0 ? "" : body.substring(i + field.length()).trim();
    }

    private static String quoted(String body, String field) {
        String v = after(body, field);
        if (v.startsWith("\"")) v = v.substring(1);
        if (v.endsWith("\"")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
