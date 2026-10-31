package com.padbridge;

import java.io.File;

/**
 * A controller's battery, as its Linux driver reports it: a power_supply folder next to the
 * controller's input device in /sys (Bluetooth HID, PlayStation, Switch Pro and Xbox drivers
 * all add one). Read by the engine, which can see /sys.
 */
final class Battery {
    private Battery() {}

    /** Percent, or -1 when the controller doesn't report one. */
    int percent = -1;
    boolean charging;

    /** sysfs is the device's "Sysfs=" path from /proc/bus/input/devices. Null if no battery. */
    static Battery read(String sysfs) {
        if (sysfs == null || sysfs.isEmpty()) return null;
        return near(new File("/sys" + sysfs));
    }

    /** The battery near an input device's folder in /sys. */
    static Battery near(File dir) {
        // .../<hid device>/input/inputN: the power_supply folder sits on the HID device or above.
        for (int up = 0; up < 4; up++) {
            dir = dir.getParentFile();
            if (dir == null) break;
            File[] supplies = new File(dir, "power_supply").listFiles();
            if (supplies == null) continue;
            for (File s : supplies) {
                // A controller's battery says it powers a device, not the system.
                String scope = text(new File(s, "scope"));
                if (scope != null && !"Device".equals(scope)) continue;
                Battery b = new Battery();
                b.percent = percent(s);
                if (b.percent < 0) continue;
                String status = text(new File(s, "status"));
                b.charging = "Charging".equals(status) || "Full".equals(status);
                return b;
            }
        }
        return null;
    }

    private static int percent(File supply) {
        String capacity = text(new File(supply, "capacity"));
        if (capacity != null) {
            try {
                return Math.max(0, Math.min(100, Integer.parseInt(capacity)));
            } catch (NumberFormatException ignored) {
            }
        }
        // Some drivers only give a level.
        String level = text(new File(supply, "capacity_level"));
        if (level == null) return -1;
        switch (level) {
            case "Full": return 100;
            case "High": return 80;
            case "Normal": return 55;
            case "Low": return 20;
            case "Critical": return 5;
            default: return -1;
        }
    }

    private static String text(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
