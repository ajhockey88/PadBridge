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
    }

    /** What the engine last reported. */
    static final class Status {
        boolean alive;
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
            long age = System.currentTimeMillis() - o.optLong("time");
            s.alive = o.optBoolean("running") && age < 4000 && age > -4000;
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
    static void ensureRunning(Context context) {
        if (!new Prefs(context).enabled() || !ShizukuClient.hasPermission()) return;
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

    private static void start(Context context) {
        pushConfig(context);
        String apk = context.getApplicationInfo().sourceDir;
        String dir = folder(context).getAbsolutePath();
        String lib = context.getApplicationInfo().nativeLibraryDir;
        // setsid: the engine outlives this shell, Shizuku's process for it, and the app.
        ShizukuClient.runShell("pkill -x " + PROCESS + "; cd /; CLASSPATH='" + apk + "' setsid app_process "
                + "/system/bin --nice-name=" + PROCESS + " " + Engine.class.getName() + " '" + dir + "' '" + apk
                + "' '" + lib + "' </dev/null >'" + dir + "/engine.log' 2>&1 &");
    }

    /** Switches PadBridge off: the engine gives every controller back and removes the player pads. */
    static void stop(Context context) {
        new Prefs(context).setEnabled(false);
        pushConfig(context);
        if (ShizukuClient.hasPermission()) {
            // The engine stops by itself on the new config; this is in case it's stuck.
            SystemClock.sleep(1500);
            if (status(context).alive) ShizukuClient.runShell("pkill -x " + PROCESS);
        }
    }

    /** Called when Shizuku hands PadBridge its binder: starts the engine once allowed. */
    static void autoStart(Context context) {
        final Context app = context.getApplicationContext();
        if (!new Prefs(app).autoStart()) return;
        ShizukuClient.addListener(new ShizukuClient.Listener() {
            @Override public void onShizukuChanged() {
                if (!ShizukuClient.hasPermission()) return;
                ShizukuClient.removeListener(this);
                new Thread(new Runnable() {
                    @Override public void run() { ensureRunning(app); }
                }, "PadBridge auto start").start();
            }
        });
    }
}
