package com.padbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Brings PadBridge back after the Thor restarts (or PadBridge is updated).
 *
 * Nothing survives a restart, Shizuku included, and the engine needs Shizuku to start. So this
 * wakes PadBridge, which lets a running Shizuku hand over its binder and start the engine. If
 * Shizuku hasn't started a little while after the restart (without root, Shizuku has to be
 * started again by hand after each restart unless your Shizuku can start on boot), it posts a
 * notice that opens Shizuku. When Shizuku does start, it wakes PadBridge by itself, the engine
 * starts, and the notice goes.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "padbridge-start";
    private static final int NOTICE = 1;
    static final String SHIZUKU = "moe.shizuku.privileged.api";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Prefs prefs = new Prefs(app);
        if (!prefs.enabled() || !prefs.autoStart()) return;
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        final PendingResult result = goAsync();
        EngineControl.autoStart(app);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // Give Shizuku (root, or a Shizuku that starts on boot) time to come up.
                    for (int i = 0; i < 40 && !ShizukuClient.hasPermission(); i++) SystemClock.sleep(500);
                    if (ShizukuClient.hasPermission()) {
                        EngineControl.ensureRunning(app);
                        cancelNotice(app);
                    } else if (boot) {
                        postNotice(app);
                    }
                } finally {
                    result.finish();
                }
            }
        }, "PadBridge boot").start();
    }

    /** "Start Shizuku to turn PadBridge back on", opening Shizuku when tapped. */
    static void postNotice(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Starting after a restart",
                    NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = c.getPackageManager().getLaunchIntentForPackage(SHIZUKU);
            if (open == null) open = new Intent(c, LauncherActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("🎮 PadBridge is waiting for Shizuku")
                    .setContentText("Start Shizuku and your player pads come back by themselves")
                    .setStyle(new Notification.BigTextStyle().bigText("Start Shizuku and your player pads come back by "
                            + "themselves. Tap to open Shizuku."))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTICE, n);
        } catch (RuntimeException ignored) {
            // No notification permission: PadBridge still starts once Shizuku does.
        }
    }

    static void cancelNotice(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTICE);
    }
}
