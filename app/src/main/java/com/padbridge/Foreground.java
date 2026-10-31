package com.padbridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which apps are on screen, for the engine (as the shell user), so a game's profile can switch
 * on while it's in front. Asks Android's task manager directly, a quick call; if that's refused,
 * reads `dumpsys activity activities` instead, which is slower, so then it's asked less often.
 */
final class Foreground {
    private Foreground() {}

    private static boolean direct = true;
    private static final Pattern RESUMED = Pattern.compile("(?:topResumedActivity|mResumedActivity|ResumedActivity)"
            + "[=:].*? u\\d+ ([A-Za-z0-9_.]+)/");

    /** True when it uses the quick way. */
    static boolean quick() { return direct; }

    /** Packages with a visible task, topmost first (on every screen). Empty if unknown. */
    static List<String> visible() {
        if (direct) {
            try {
                return fromTasks();
            } catch (Throwable t) {
                direct = false;
                Engine.log("can't ask for visible apps directly (" + t + "), using dumpsys");
            }
        }
        return fromDumpsys(Engine.exec("dumpsys", "activity", "activities"));
    }

    private static List<String> fromTasks() throws Exception {
        Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Method all = atm.getClass().getMethod("getAllRootTaskInfos");
        List<?> tasks = (List<?>) all.invoke(atm);
        List<String> out = new ArrayList<>();
        for (Object t : tasks) {
            if (!bool(t, "visible", "isVisible")) continue;
            Object top = t.getClass().getField("topActivity").get(t);
            if (top instanceof android.content.ComponentName) {
                String pkg = ((android.content.ComponentName) top).getPackageName();
                if (!out.contains(pkg)) out.add(pkg);
            }
        }
        return out;
    }

    private static boolean bool(Object o, String... names) {
        for (String n : names) {
            try {
                Field f = o.getClass().getField(n);
                return f.getBoolean(o);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return true; // can't tell: count it
    }

    /** The resumed activities' packages from `dumpsys activity activities`. */
    static List<String> fromDumpsys(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            Matcher m = RESUMED.matcher(line);
            if (m.find() && !out.contains(m.group(1))) out.add(m.group(1));
        }
        return out;
    }

    /** The first visible app with a profile, other than PadBridge, or "". */
    static String pick(List<String> visible, java.util.Set<String> withProfiles) {
        for (String pkg : visible) {
            if (!"com.padbridge".equals(pkg) && withProfiles.contains(pkg)) return pkg;
        }
        return "";
    }
}
