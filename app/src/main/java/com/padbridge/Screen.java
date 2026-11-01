package com.padbridge;

import java.lang.reflect.Method;

/** Whether the Thor's screens are on, for the engine: asks Android's power service directly. */
final class Screen {
    private Screen() {}

    private static Object power;
    private static Method isInteractive;
    private static boolean failed;

    /** True while the screen is on (or when it can't tell). */
    static boolean interactive() {
        if (failed) return true;
        try {
            if (isInteractive == null) {
                Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                        .invoke(null, "power");
                power = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", android.os.IBinder.class)
                        .invoke(null, binder);
                isInteractive = power.getClass().getMethod("isInteractive");
            }
            return (Boolean) isInteractive.invoke(power);
        } catch (Throwable t) {
            failed = true;
            Engine.log("can't tell if the screen is on (" + t + "): assuming it is");
            return true;
        }
    }
}
