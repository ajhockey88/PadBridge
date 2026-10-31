package com.padbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * One controller's own settings, from its menu on the main screen or Settings > Controllers:
 * rumble strength, stick deadzone, invert the right stick, Nintendo layout, its button map, and
 * how quickly it reports, live.
 */
final class ControllerSettings {
    private ControllerSettings() {}

    static void show(final Activity a, final String key, String name, final Runnable onChange) {
        final Prefs prefs = new Prefs(a);
        final Routing.PadSettings s = prefs.padSettings(key);
        final float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (2 * d), (int) (24 * d), 0);

        // How quickly it reports, refreshed while the dialog is open.
        final TextView speed = Ui.plain(a, "", 13, Ui.SUB);
        speed.setPadding((int) (12 * d), (int) (9 * d), (int) (12 * d), (int) (9 * d));
        speed.setBackground(Ui.round(Ui.CHIP, 10 * d));
        box.addView(speed);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                EngineControl.Controller ctl = find(a, key);
                String battery = ctl == null ? "" : battery(ctl);
                speed.setText(speedText(ctl) + (battery.isEmpty() ? "" : "\n" + battery + " battery"
                        + (ctl.charging ? ", charging" : lowBattery(ctl) ? ", charge it soon" : "")));
                handler.postDelayed(this, 700);
            }
        };
        tick.run();

        final Runnable save = new Runnable() {
            @Override public void run() {
                prefs.setPadSettings(key, s);
                EngineControl.pushConfig(a);
                if (onChange != null) onChange.run();
            }
        };

        box.addView(heading(a, "RUMBLE"));
        LinearLayout rumble = new LinearLayout(a);
        rumble.setGravity(Gravity.CENTER_VERTICAL);
        final TextView rumbleValue = Ui.chip(a, percent(s.rumble));
        SeekBar rumbleBar = slider(a, 10, s.rumble / 10, new Slid() {
            @Override public void to(int v) {
                s.rumble = v * 10;
                rumbleValue.setText(percent(s.rumble));
            }
        }, save);
        rumble.addView(rumbleBar, new LinearLayout.LayoutParams(0, (int) (36 * d), 1f));
        rumble.addView(rumbleValue);
        TextView test = Ui.button(a, "📳  Test");
        test.setTextSize(13);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(a, Haptics.PICK);
                testRumble(a, find(a, key), s.rumble);
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = (int) (8 * d);
        rumble.addView(test, tlp);
        box.addView(rumble);
        box.addView(note(a, "Games' rumble reaches this controller through its player. The Thor's own controls use "
                + "the Thor's motor"));

        box.addView(heading(a, "STICK DEADZONE"));
        LinearLayout dead = new LinearLayout(a);
        dead.setGravity(Gravity.CENTER_VERTICAL);
        final TextView deadValue = Ui.chip(a, percent(s.deadzone));
        SeekBar deadBar = slider(a, 30, s.deadzone, new Slid() {
            @Override public void to(int v) {
                s.deadzone = v;
                deadValue.setText(percent(v));
            }
        }, save);
        dead.addView(deadBar, new LinearLayout.LayoutParams(0, (int) (36 * d), 1f));
        dead.addView(deadValue);
        box.addView(dead);
        box.addView(note(a, "Raise it if a worn stick drifts when you let go"));

        box.addView(switchRow(a, "Invert right stick up/down", s.invertRy, new Flip() {
            @Override public void to(boolean on) {
                s.invertRy = on;
                save.run();
            }
        }));
        box.addView(switchRow(a, "Nintendo layout (swap A/B and X/Y)", prefs.nintendo(key), new Flip() {
            @Override public void to(boolean on) {
                prefs.setNintendo(key, on);
                save.run();
            }
        }));

        box.addView(heading(a, "BUTTONS"));
        LinearLayout map = new LinearLayout(a);
        map.setGravity(Gravity.CENTER_VERTICAL);
        final TextView mapSummary = Ui.plain(a, ButtonMap.summary(s), 15, Ui.TEXT);
        map.addView(mapSummary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView remap = Ui.button(a, "🎛️  Remap");
        remap.setTextSize(13);
        final String title = name;
        remap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(a, Haptics.PICK);
                ButtonMap.show(a, title, s, new Runnable() {
                    @Override public void run() {
                        save.run();
                        mapSummary.setText(ButtonMap.summary(s));
                    }
                });
            }
        });
        map.addView(remap);
        box.addView(map);
        box.addView(note(a, "Swap or turn off this controller's buttons, on its player pad only"));

        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.addView(box);
        new AlertDialog.Builder(a)
                .setTitle(name)
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface dialog) { handler.removeCallbacks(tick); }
                })
                .show();
    }

    /** The controller as the engine last reported it (or the screenshot's made-up one). */
    static EngineControl.Controller find(Context c, String key) {
        EngineControl.Status st = MainActivity.demoStatus != null ? MainActivity.demoStatus : EngineControl.status(c);
        for (EngineControl.Controller ctl : st.controllers) if (ctl.key.equals(key)) return ctl;
        return null;
    }

    static String speedText(EngineControl.Controller ctl) {
        if (ctl == null) return "⚡ Not connected. Connect it to see how quickly it reports";
        if (!ctl.active) return "⚡ Left to Android, so PadBridge isn't timing it";
        if (ctl.intervalMs <= 0) return "⚡ Press buttons or move a stick to time it";
        return String.format(Locale.US, "⚡ Reports every %.1f ms (%d a second) · PadBridge adds %s",
                ctl.intervalMs, Math.round(1000 / ctl.intervalMs), added(ctl.addedMs));
    }

    /** The short form, on the controller's card: "8 ms · +0.3 ms". Empty until known. */
    static String shortSpeed(EngineControl.Controller ctl) {
        if (!ctl.active || ctl.intervalMs <= 0) return "";
        return String.format(Locale.US, "⚡ %.0f ms · +%s", ctl.intervalMs, added(ctl.addedMs));
    }

    /** The battery, on the controller's card: "🔋 80%", "🔌 80%" charging, "🔋 12% low". Empty if unknown. */
    static String battery(EngineControl.Controller ctl) {
        if (ctl.battery < 0) return "";
        return (ctl.charging ? "🔌 " : "🔋 ") + ctl.battery + "%" + (lowBattery(ctl) ? " low" : "");
    }

    static boolean lowBattery(EngineControl.Controller ctl) {
        return ctl.battery >= 0 && !ctl.charging && ctl.battery <= Engine.BATTERY_LOW;
    }

    /** PadBridge's own delay: to a hundredth of a millisecond while it's under one. */
    static String added(double ms) {
        if (ms < 0.01) return "<0.01 ms";
        return String.format(Locale.US, ms < 1 ? "%.2f ms" : "%.1f ms", ms);
    }

    /**
     * Rumbles the controller's player pad the way a game would, so the whole path is tested:
     * Android, the player pad, PadBridge, the controller.
     */
    @SuppressWarnings("deprecation")
    private static void testRumble(Context c, EngineControl.Controller ctl, int strength) {
        String problem = null;
        if (strength <= 0) problem = "Rumble is off for this controller";
        else if (ctl == null || !ctl.active || ctl.player <= 0) problem = "Connect it and turn PadBridge on to test rumble";
        else if (ctl.cantRumble) problem = "This controller didn't accept rumble";
        if (problem == null) {
            InputManager im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
            String pad = "PadBridge Player " + ctl.player;
            problem = "Player " + ctl.player + "'s pad wasn't found";
            for (int id : im.getInputDeviceIds()) {
                InputDevice dev = im.getInputDevice(id);
                if (dev == null || !pad.equals(dev.getName())) continue;
                if (dev.getVibrator().hasVibrator()) {
                    dev.getVibrator().vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE));
                    problem = null;
                } else {
                    problem = "Turn PadBridge off and on to give Player " + ctl.player + " rumble";
                }
                break;
            }
        }
        if (problem != null) Toast.makeText(c, problem, Toast.LENGTH_SHORT).show();
    }

    private static String percent(int v) { return v == 0 ? "Off" : v + "%"; }

    private interface Slid { void to(int v); }

    private interface Flip { void to(boolean on); }

    private static SeekBar slider(Context c, int max, int value, final Slid slid, final Runnable done) {
        SeekBar bar = new SeekBar(c);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(0x2e, 0x32, 0x3b)));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT_TEXT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { slid.to(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { done.run(); }
        });
        return bar;
    }

    private static View switchRow(final Context c, String title, boolean value, final Flip flip) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * d), 0, 0);
        row.addView(Ui.plain(c, title, 16, Color.WHITE), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(c);
        sw.setChecked(value);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[] {Ui.ACCENT_TEXT, Color.rgb(0x9a, 0xa0, 0xab)}));
        sw.setTrackTintList(new ColorStateList(states, new int[] {Color.rgb(0x2b, 0x6e, 0x99), Color.rgb(0x3a, 0x3e, 0x48)}));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Haptics.play(c, Haptics.SETTING);
                flip.to(checked);
            }
        });
        row.addView(sw);
        return row;
    }

    private static TextView heading(Context c, String s) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = Ui.plain(c, s, 11, Ui.DIM);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, (int) (14 * d), 0, (int) (2 * d));
        return t;
    }

    private static TextView note(Context c, String s) {
        return Ui.plain(c, s, 12, Ui.DIM);
    }
}
