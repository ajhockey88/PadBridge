package com.padbridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

/**
 * "Uninstall PadBridge completely": undoes everything PadBridge leaves outside its own app
 * data, then removes the app.
 *
 * PadBridge changes no Android settings (no Settings.Global/Secure/System values, app ops,
 * accessibility services or other apps' permissions). Its only grant is POST_NOTIFICATIONS to
 * itself, which Android drops with the app. What can outlive a plain uninstall is what runs
 * or was saved as the shell user, outside the app's folders:
 * <ul>
 *   <li>the engine (padbridged) and its keep-alive shell, which hold the player pads, the Thor
 *       keys clone and grabbed controllers (the engine notices the app is gone within about
 *       10 seconds and stops, unless it's stuck);</li>
 *   <li>`uinput` tool processes keeping player pads alive after an engine stopped badly;</li>
 *   <li>diagnostics files (padbridge-*) saved in Download.</li>
 * </ul>
 */
final class Uninstall {
    private Uninstall() {}

    /** Every PadBridge process that may be running as the shell user, stopped (pads unplugged). */
    static final String STOP_EVERYTHING =
            // The keep-alive first, so it can't start the engine again; SIGTERM lets the engine unplug its pads.
            "pkill -f 'padbridge-keep[a]live'; pkill -x padbridged; sleep 1; "
            + "pkill -9 -f 'padbridge-keep[a]live'; pkill -9 -x padbridged; "
            // uinput tools keeping player pads: marked ones, and older unmarked ones whose parent is gone.
            + "for p in /proc/[0-9]*; do "
            + "[ \"${p#/proc/}\" = $$ ] && continue; grep -q uinput $p/cmdline 2>/dev/null || continue; "
            + "if grep -q " + VirtualPads.MARK + "=1 $p/environ 2>/dev/null "
            + "|| [ \"$(cut -d')' -f2 $p/stat 2>/dev/null | cut -d' ' -f3)\" = 1 ]; then kill -9 ${p#/proc/}; fi; "
            + "done";

    /** Diagnostics PadBridge saved in Download. */
    static final String REMOVE_FILES = "rm -f /sdcard/Download/padbridge-*";

    /**
     * Turns PadBridge off, cleans up with Shizuku if it can, then uninstalls. Without Shizuku
     * it asks Android to uninstall (the engine then stops by itself). Call off the main thread.
     * Returns true if the cleanup ran through Shizuku.
     */
    static boolean everything(Context context) {
        Prefs prefs = new Prefs(context);
        prefs.setEnabled(false);
        EngineControl.pushConfig(context); // the engine hands every controller back cleanly
        BootReceiver.cancelNotice(context);
        if (ShizukuClient.hasPermission()) {
            SystemClock.sleep(1500);
            // Its own session, so the uninstall finishes even though it stops this app.
            boolean ok = ShizukuClient.runShell(STOP_EVERYTHING + "; " + REMOVE_FILES + "; "
                    + "setsid sh -c 'sleep 1; pm uninstall " + context.getPackageName() + "' "
                    + "</dev/null >/dev/null 2>&1 &");
            if (ok) return true;
        }
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + context.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
        return false;
    }
}
