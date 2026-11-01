package com.padbridge;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The app's side of the {@link Engine}: starts and stops it through Shizuku, hands it the
 * settings (config.json) and reads back what it's doing (status.json).
 */
final class EngineControl {
    private EngineControl() {}

    /** The engine's process name, so it can be found and stopped. */
    private static final String PROCESS = "padbridged";
    private static long lastStartAt;
    /** Starts since the engine last reported in; after a few, PadBridge stops retrying. */
    private static int failedStarts;
    static final int MAX_STARTS = 3;

    /** A controller as the engine last reported it. */
    static final class Controller {
        String key, name, connection, error;
        boolean builtin, active, cantRumble;
        int player;
        /** ms between reports, and ms PadBridge adds; 0 until known. */
        double intervalMs, addedMs;
        /** Battery percent, or -1 when it doesn't report one. */
        int battery = -1;
        boolean charging;
        /** The last button pressed on it (a PadState button, or -1), and a count of presses. */
        int pressed = -1, pressSeq;
    }

    /** What the engine last reported. */
    static final class Status {
        boolean alive;
        /** Player pads go straight to /dev/uinput, rather than through the slower uinput tool. */
        boolean direct;
        /** The game whose profile is on (its package), or "". */
        String profile = "";
        int version, players;
        String style = Routing.STANDARD, error = "";
        final List<Controller> controllers = new ArrayList<>();
    }

    static File folder(Context context) {
        File dir = context.getExternalFilesDir("engine");
        if (dir == null) dir = new File(context.getFilesDir(), "engine");
        dir.mkdirs();
        return dir;
    }

    /** Hands the engine the current settings; it picks them up within half a second. */
    static void pushConfig(Context context) {
        try {
            File dir = folder(context);
            File tmp = new File(dir, "config.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(new Prefs(context).engineConfig().toJson().toString(2).getBytes(StandardCharsets.UTF_8));
            out.close();
            tmp.renameTo(new File(dir, "config.json"));
        } catch (IOException | JSONException ignored) {
        }
    }

    static Status status(Context context) {
        Status s = new Status();
        String text = Engine.read(new File(folder(context), "status.json"));
        if (text == null) return s;
        try {
            JSONObject o = new JSONObject(text);
            s.version = o.optInt("version");
            s.players = o.optInt("players");
            s.style = o.optString("style", Routing.STANDARD);
            s.error = o.optString("error", "");
            s.direct = o.optBoolean("direct");
            s.profile = o.optString("profile", "");
            long age = System.currentTimeMillis() - o.optLong("time");
            s.alive = o.optBoolean("running") && age < 6000 && age > -6000; // the engine writes at least every 2 s
            JSONArray list = o.optJSONArray("controllers");
            for (int i = 0; s.alive && list != null && i < list.length(); i++) {
                JSONObject d = list.getJSONObject(i);
                Controller c = new Controller();
                c.key = d.optString("key");
                c.name = d.optString("name");
                c.connection = d.optString("connection");
                c.error = d.optString("error", "");
                c.builtin = d.optBoolean("builtin");
                c.active = d.optBoolean("active");
                c.player = d.optInt("player");
                c.intervalMs = d.optDouble("intervalMs", 0);
                c.addedMs = d.optDouble("addedMs", 0);
                c.cantRumble = d.optBoolean("cantRumble");
                c.battery = d.optInt("battery", -1);
                c.charging = d.optBoolean("charging");
                c.pressed = d.optInt("pressed", -1);
                c.pressSeq = d.optInt("pressSeq");
                s.controllers.add(c);
            }
        } catch (JSONException ignored) {
        }
        return s;
    }

    /**
     * Starts the engine if PadBridge is on and it isn't running (or is an older one). Call off
     * the main thread.
     */
    /**
     * Removes player pads whose engine is gone (it was killed, or the app was uninstalled while
     * it ran): each pad is a uinput tool process marked PADBRIDGE_PADS, or one left with no parent.
     */
    private static final String UNPLUG_LEFTOVERS = "for p in $(pidof uinput); do "
            + "if grep -qa '" + VirtualPads.MARK + "=1' /proc/$p/environ 2>/dev/null "
            + "|| [ \"$(cut -d' ' -f4 /proc/$p/stat 2>/dev/null)\" = 1 ]; then kill $p; fi; done; true";

    private static boolean unpluggedWhileOff;

    static void ensureRunning(Context context) {
        if (!ShizukuClient.hasPermission()) return;
        if (!new Prefs(context).enabled()) {
            // Off: make sure no pads were left behind, once per app start (and on every Turn off).
            if (!unpluggedWhileOff && !status(context).alive) {
                unpluggedWhileOff = true;
                ShizukuClient.runShell(UNPLUG_LEFTOVERS);
            }
            return;
        }
        Status s = status(context);
        if (s.alive && s.version == Engine.VERSION) {
            failedStarts = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastStartAt != 0 && now - lastStartAt < 8000) return; // give the last start time to report in
        if (failedStarts >= MAX_STARTS) return; // stuck: Settings has diagnostics, Turn on retries
        lastStartAt = now;
        failedStarts++;
        start(context);
    }

    /** The engine was started a few times and never reported in. */
    static boolean stuck() { return failedStarts >= MAX_STARTS && SystemClock.elapsedRealtime() - lastStartAt > 8000; }

    /** Turn on: try starting again from scratch. */
    static void retry() {
        failedStarts = 0;
        lastStartAt = 0;
    }

    /**
     * Starts the engine under a small keep-alive shell: if the engine ever stops on its own
     * (a crash, or Android stopping it), the shell starts it again within a couple of seconds.
     * It gives up after five quick stops in a row, and stops for good when the engine exits
     * cleanly (PadBridge turned off, or updated).
     */
    private static void start(Context context) {
        pushConfig(context);
        String apk = context.getApplicationInfo().sourceDir;
        String dir = folder(context).getAbsolutePath();
        String lib = context.getApplicationInfo().nativeLibraryDir;
        String loop = "f=0; while :; do s=$(date +%s); "
                + "CLASSPATH=\"$1\" app_process /system/bin --nice-name=" + PROCESS + " " + Engine.class.getName()
                + " \"$2\" \"$1\" \"$3\"; c=$?; "
                + "[ $c -eq 0 ] && exit 0; [ -f \"$1\" ] || exit 0; "
                + "if [ $(( $(date +%s) - s )) -lt 30 ]; then f=$((f+1)); else f=0; fi; "
                + "[ $f -ge 5 ] && { echo \"$(date +%s)000 engine keeps stopping (exit $c), giving up\"; exit 1; }; "
                + "echo \"$(date +%s)000 engine stopped (exit $c), starting it again\"; sleep 2; done";
        // setsid: the engine outlives this shell, Shizuku's process for it, and the app.
        // The keep-alive's name is put together at run time so this command doesn't match itself.
        ShizukuClient.runShell(STOP_ALL + "; pm grant " + context.getPackageName()
                + " android.permission.POST_NOTIFICATIONS 2>/dev/null; cd /; n=" + KEEPALIVE_HEAD + "; "
                + "setsid sh -c '" + loop + "' \"${n}" + KEEPALIVE_TAIL + "\" '" + apk + "' '" + dir + "' '" + lib
                + "' </dev/null >'" + dir + "/engine.log' 2>&1 &");
    }

    /** The keep-alive shell is named padbridge-keepalive; [a] stops pkill matching its own shell. */
    private static final String KEEPALIVE_HEAD = "padbridge-keep", KEEPALIVE_TAIL = "alive",
            STOP_ALL = "pkill -f 'padbridge-keep[a]live'; pkill -x " + PROCESS;

    /** Switches PadBridge off: the engine gives every controller back and removes the player pads. */
    static void stop(Context context) {
        new Prefs(context).setEnabled(false);
        pushConfig(context);
        if (ShizukuClient.hasPermission()) {
            // The engine stops by itself on the new config; this is in case it's stuck.
            SystemClock.sleep(1500);
            ShizukuClient.runShell(status(context).alive ? STOP_ALL : "pkill -f 'padbridge-keep[a]live'");
            SystemClock.sleep(500);
            ShizukuClient.runShell(UNPLUG_LEFTOVERS);
        }
    }

    /**
     * Called when Shizuku hands PadBridge its binder (Shizuku just started, often after a
     * restart, or PadBridge's process did): starts the engine once allowed.
     */
    static void autoStart(Context context) {
        final Context app = context.getApplicationContext();
        if (!new Prefs(app).autoStart()) return;
        final Runnable go = new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        ensureRunning(app);
                        // It's back: the "waiting for Shizuku" notice can go.
                        BootReceiver.cancelNotice(app);
                    }
                }, "PadBridge auto start").start();
            }
        };
        if (ShizukuClient.hasPermission()) {
            go.run();
            return;
        }
        ShizukuClient.addListener(new ShizukuClient.Listener() {
            @Override public void onShizukuChanged() {
                if (!ShizukuClient.hasPermission()) return;
                ShizukuClient.removeListener(this);
                go.run();
            }
        });
    }
}
