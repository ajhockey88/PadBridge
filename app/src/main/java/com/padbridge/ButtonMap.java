package com.padbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A controller's button map: for each of its buttons, which button its player pad presses.
 * Each controller has its own, so a friend's pad can have A and B swapped while the Thor's
 * controls stay as they are, and games and emulators never need remapping.
 */
final class ButtonMap {
    private ButtonMap() {}

    /** PadState's buttons, A to RIGHT, by name. */
    static final String[] NAMES = {"A", "B", "X", "Y", "L1 / LB", "R1 / RB", "L2 / LT", "R2 / RT", "Select / View",
            "Start / Menu", "Home / Guide", "L3 (left stick press)", "R3 (right stick press)", "D-pad up", "D-pad down",
            "D-pad left", "D-pad right"};

    /** A short name for a chip. */
    static String shortName(int b) {
        if (b < 0) return "Nothing";
        String n = NAMES[b];
        int cut = n.indexOf(" (");
        return cut > 0 ? n.substring(0, cut) : n;
    }

    /** "2 buttons remapped", or "Every button is itself". */
    static String summary(Routing.PadSettings s) {
        int n = s.remapped();
        return n == 0 ? "Every button presses itself" : n == 1 ? "1 button remapped" : n + " buttons remapped";
    }

    /**
     * Shows the map; `save` runs after each change. Pressing a button on the controller (`key`,
     * or with null any controller) picks it, as well as tapping it.
     */
    static void show(final Activity a, String controller, final Routing.PadSettings s, final Runnable save,
                     final String key) {
        final boolean[] picking = {false};
        final java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        final Runnable baseline = new Runnable() {
            @Override public void run() {
                seen.clear();
                for (EngineControl.Controller c : status(a).controllers) seen.put(c.key, c.pressSeq);
            }
        };
        baseline.run();
        final float d = a.getResources().getDisplayMetrics().density;
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (2 * d), (int) (24 * d), (int) (6 * d));
        final Runnable[] fill = new Runnable[1];
        final Picker[] openPick = new Picker[1];
        fill[0] = new Runnable() {
            @Override public void run() {
                box.removeAllViews();
                TextView lead = Ui.plain(a, key != null
                        ? "The button on this controller, and what its player pad gets. Only this controller changes"
                        : "The button on any controller, and what its player pad gets, while this game is in front",
                        13, Ui.SUB);
                lead.setPadding(0, 0, 0, (int) (6 * d));
                box.addView(lead);
                TextView press = Ui.plain(a, "👆  Press a button on the " + (key != null ? "controller" : "controller you're "
                        + "using") + " to change it, or tap it here", 13, Ui.ACCENT_TEXT);
                press.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
                press.setBackground(Ui.round(Ui.CHIP, 10 * d));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.bottomMargin = (int) (6 * d);
                box.addView(press, plp);
                LinearLayout row = null;
                for (int i = 0; i < PadState.BUTTONS; i++) {
                    if (i % 2 == 0) {
                        row = new LinearLayout(a);
                        box.addView(row);
                    }
                    final int b = i;
                    LinearLayout cell = new LinearLayout(a);
                    cell.setGravity(Gravity.CENTER_VERTICAL);
                    cell.setPadding((int) (12 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
                    boolean changed = s.mapped(b) != b;
                    cell.setBackground(Ui.round(changed ? Ui.SELECTED : Ui.BUTTON, 12 * d));
                    cell.setFocusable(true);
                    cell.addView(Ui.plain(a, shortName(b), 15, Color.WHITE), new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    cell.addView(Ui.plain(a, "→", 15, Ui.DIM));
                    TextView to = Ui.chip(a, shortName(s.mapped(b)));
                    if (!changed) to.setTextColor(Ui.SUB);
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    tlp.leftMargin = (int) (8 * d);
                    cell.addView(to, tlp);
                    cell.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            Haptics.play(a, Haptics.PICK);
                            openPick[0].pick(b);
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(i % 2 == 0 ? 0 : (int) (4 * d), (int) (4 * d), i % 2 == 0 ? (int) (4 * d) : 0, 0);
                    row.addView(cell, lp);
                }
                if (PadState.BUTTONS % 2 == 1) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
                TextView note = Ui.plain(a, "The Thor's Home, Back and volume buttons always stay Android's", 12, Ui.DIM);
                note.setPadding(0, (int) (10 * d), 0, 0);
                box.addView(note);
            }
        };
        openPick[0] = new Picker() {
            @Override public void pick(int b) {
                if (picking[0]) return;
                picking[0] = true;
                ButtonMap.pick(a, b, s, new Runnable() {
                    @Override public void run() {
                        save.run();
                        fill[0].run();
                    }
                }, new Runnable() {
                    @Override public void run() {
                        picking[0] = false;
                        baseline.run(); // presses made in the list don't count
                    }
                });
            }
        };
        fill[0].run();
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("🎛️  " + controller + " buttons")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset all", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                // Reset keeps the dialog open, showing every button back as itself.
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Haptics.play(a, Haptics.SETTING);
                        s.map = null;
                        save.run();
                        fill[0].run();
                    }
                });
            }
        });
        // A button pressed on the controller picks it. Its press also reaches the player pad,
        // and so this screen: those are held back here, so they don't tap the buttons above.
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface di, int code, KeyEvent ev) {
                android.view.InputDevice dev = ev.getDevice();
                boolean pad = dev != null && dev.getName() != null && dev.getName().startsWith("PadBridge Player");
                return pad && (KeyEvent.isGamepadButton(code) || (code >= KeyEvent.KEYCODE_DPAD_UP
                        && code <= KeyEvent.KEYCODE_DPAD_CENTER));
            }
        });
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable watch = new Runnable() {
            @Override public void run() {
                if (!picking[0]) {
                    for (EngineControl.Controller c : status(a).controllers) {
                        if (key != null && !key.equals(c.key)) continue;
                        Integer was = seen.get(c.key);
                        seen.put(c.key, c.pressSeq);
                        if (was != null && was != c.pressSeq && c.pressed >= 0 && c.pressed < PadState.BUTTONS) {
                            Haptics.play(a, Haptics.PICK);
                            openPick[0].pick(c.pressed);
                            break;
                        }
                    }
                }
                handler.postDelayed(this, 150);
            }
        };
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) { handler.removeCallbacks(watch); }
        });
        dialog.show();
        handler.postDelayed(watch, 150);
    }

    private interface Picker { void pick(int b); }

    private static EngineControl.Status status(Activity a) {
        return MainActivity.demoStatus != null ? MainActivity.demoStatus : EngineControl.status(a);
    }

    /** Picks what button b presses. */
    private static void pick(Activity a, final int b, final Routing.PadSettings s, final Runnable done,
                             final Runnable closed) {
        final String[] items = new String[PadState.BUTTONS + 1];
        int current = s.mapped(b);
        for (int i = 0; i < PadState.BUTTONS; i++) {
            items[i] = (i == current ? "●  " : "     ") + NAMES[i] + (i == b ? "  (itself)" : "");
        }
        items[PadState.BUTTONS] = (current < 0 ? "●  " : "     ") + "Nothing (turn it off)";
        new AlertDialog.Builder(a)
                .setTitle(shortName(b) + " presses…")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        s.setMapped(b, which == PadState.BUTTONS ? Routing.PadSettings.UNMAPPED : which);
                        done.run();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface dialog) { closed.run(); }
                })
                .show();
    }
}
