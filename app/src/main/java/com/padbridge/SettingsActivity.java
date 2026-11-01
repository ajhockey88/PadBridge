package com.padbridge;

import android.text.TextUtils;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** PadBridge settings: section tabs on the left, the chosen section's settings on the right. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;
    private float d;

    // Tabs down the left; the chosen section's settings on the right, as cards.
    private static final String[] TABS = {"🎮 Players", "🕹️ Controllers", "🎯 Games", "⌨️ Hotkeys", "🖥️ Screens", "📳 Haptics", "🔄 Updates", "ℹ️ About"};
    private static final int BG = Ui.BG, SIDE = Ui.SIDE, CARD = Ui.CARD, ACCENT = Ui.ACCENT, SUB = Ui.SUB;
    /** The tab on show, kept when Settings rebuilds itself. */
    private static int tab;
    private LinearLayout page;
    private ScrollView scroll;
    private final TextView[] tabViews = new TextView[TABS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().setBackgroundColor(BG);

        LinearLayout split = new LinearLayout(this);
        split.setBackgroundColor(BG);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(SIDE);
        side.setPadding((int) (8 * d), (int) (14 * d), (int) (8 * d), (int) (12 * d));
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding((int) (8 * d), 0, 0, (int) (14 * d));
        diagnosticsOnLongPress(title);
        side.addView(title);
        for (int i = 0; i < TABS.length; i++) {
            final int which = i;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(16);
            t.setSingleLine(true);
            t.setFocusable(true);
            t.setPadding((int) (12 * d), (int) (11 * d), (int) (8 * d), (int) (11 * d));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (tab == which) return;
                    Haptics.play(SettingsActivity.this, Haptics.BAR);
                    showTab(which);
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = (int) (4 * d);
            side.addView(t, tlp);
            tabViews[i] = t;
        }
        side.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView version = new TextView(this);
        version.setText("PadBridge " + versionName());
        version.setTextSize(12);
        version.setTextColor(Ui.DIM);
        version.setPadding((int) (8 * d), 0, 0, 0);
        side.addView(version);
        int sideW = (int) (getResources().getDisplayMetrics().widthPixels * 0.27f);
        split.addView(side, new LinearLayout.LayoutParams(sideW, ViewGroup.LayoutParams.MATCH_PARENT));
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(0x1a, 0x1d, 0x24));
        split.addView(line, new LinearLayout.LayoutParams(Math.max(1, (int) (0.8f * d)), ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) (18 * d), (int) (16 * d), (int) (18 * d), (int) (16 * d));
        scroll.addView(page);
        split.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        FrameLayout root = new FrameLayout(this);
        root.addView(split);
        previewPill = new TextView(this);
        previewPill.setTextSize(15);
        previewPill.setTextColor(Ui.ACCENT_TEXT);
        previewPill.setBackground(Ui.round(Color.rgb(0x1d, 0x2a, 0x36), 30 * d));
        previewPill.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
        previewPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = (int) (14 * d);
        root.addView(previewPill, plp);
        setContentView(root);
        showTab(tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (page != null && tab == 7) showTab(tab); // About: the tour's skipped steps may have changed
    }

    /** For screenshots: opens on a given tab. */
    static void openOn(int which) { tab = which; }

    /** "1.0 (build 10)": the build number tells two builds of one version apart. */
    private String versionName() {
        try {
            android.content.pm.PackageInfo i = getPackageManager().getPackageInfo(getPackageName(), 0);
            return i.versionName + " (build " + i.versionCode + ")";
        } catch (Exception e) {
            return "";
        }
    }

    private void showTab(int which) {
        tab = which;
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == which;
            TextView t = tabViews[i];
            t.setTextColor(sel ? Color.WHITE : Color.rgb(0xaa, 0xb0, 0xbb));
            t.setBackground(sel ? Ui.round(Ui.SELECTED, 10 * d) : null);
        }
        page.removeAllViews();
        list = page;
        switch (which) {
            case 0:
                header("Players", "Who is Player 1, and how the player pads look to games");
                modeSection();
                playersSlider();
                styleSection();
                toggle("Which player am I?", "A controller buzzes once per player number when it connects or "
                        + "changes player, and lights up in its player's color where it can",
                        Prefs.KEY_ANNOUNCE, new Prefs(this).raw().getBoolean(Prefs.KEY_ANNOUNCE, true), true);
                toggle("Start by itself", "Back on after a restart as soon as Shizuku is running, and started again "
                        + "if it ever stops", Prefs.KEY_AUTO_START, prefs.autoStart(), false);
                restartNote();
                break;
            case 1:
                header("Controllers", "Every controller PadBridge has seen. Fix one to a player, or leave it alone");
                controllersSection();
                break;
            case 2:
                header("Games", "Settings that switch on by themselves while a game or emulator is in front");
                gamesSection();
                break;
            case 3:
                header("Hotkeys", "Button combos that work on any controller, mid-game");
                hotkeysSection();
                break;
            case 4:
                header("Screens", "Where PadBridge and the controller test open");
                toggle("Test on the other screen", "The controller test opens on the top screen. Off: on the same screen",
                        Prefs.KEY_BOTH_SCREENS, prefs.bothScreens(), false);
                toggle("Swap screens", "PadBridge on the top screen and the test on the bottom. Reopen PadBridge "
                        + "after changing", Prefs.KEY_SWAP_SCREENS, prefs.swapScreens(), false);
                break;
            case 5:
                header("Haptics", "Pick a feel and strength for each action.");
                hapticsSection();
                break;
            case 6:
                header("Updates", "Get the newest PadBridge from GitHub");
                updateSection();
                break;
            default:
                header("About", "PadBridge " + versionName() + ", its license and thanks");
                aboutSection();
                break;
        }
        scroll.scrollTo(0, 0);
    }

    private void header(String title, String lead) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = plain(title, 22, Color.WHITE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        diagnosticsOnLongPress(h);
        top.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Back, top right: closes Settings.
        TextView back = Ui.button(this, "←  Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(SettingsActivity.this, Haptics.BAR);
                finish();
            }
        });
        top.addView(back);
        page.addView(top);
        TextView l = plain(lead, 13, Color.rgb(0x7a, 0x80, 0x8b));
        l.setPadding(0, (int) (2 * d), 0, (int) (12 * d));
        page.addView(l);
    }

    /** A rounded card on the page; returned so a section can fill it. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        c.setBackground(Ui.round(CARD, 12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (7 * d);
        page.addView(c, lp);
        return c;
    }

    private TextView plain(String s, int sp, int color) { return Ui.plain(this, s, sp, color); }

    private TextView chip(String s) { return Ui.chip(this, s); }

    /** The engine follows settings as they change. */
    private void changed() { EngineControl.pushConfig(this); }

    // ---- Players -------------------------------------------------------------------------------

    private static final String[][] MODES = {
            {Routing.THOR_FIRST, "📱  Thor first", "The Thor's controls are Player 1, controllers join after"},
            {Routing.CONTROLLER_FIRST, "🎮  Controller first", "A controller is Player 1, the Thor's controls next"},
            {Routing.SHARED, "🤝  Both Player 1", "The Thor and your first controller share Player 1"}};

    private void modeSection() {
        LinearLayout c = card();
        c.addView(plain("Who is Player 1", 16, Color.WHITE));
        TextView sub = plain("🔁 Swap on the main screen flips the first two", 12, SUB);
        sub.setPadding(0, (int) (2 * d), 0, (int) (6 * d));
        c.addView(sub);
        for (final String[] m : MODES) {
            boolean sel = m[0].equals(prefs.mode());
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setFocusable(true);
            row.setPadding((int) (10 * d), (int) (5 * d), (int) (10 * d), (int) (5 * d));
            if (sel) row.setBackground(Ui.round(Ui.SELECTED, 10 * d));
            row.addView(plain(m[1] + (sel ? "   ✓" : ""), 15, sel ? Ui.ACCENT_TEXT : Color.WHITE));
            row.addView(plain(m[2], 12, SUB));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(SettingsActivity.this, Haptics.PICK);
                    prefs.setMode(m[0]);
                    changed();
                    showTab(0);
                }
            });
            c.addView(row);
        }
    }

    private void playersSlider() {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(plain("Players always ready", 16, Color.WHITE));
        words.addView(plain("Ready before a controller joins", 12, SUB));
        row.addView(words, new LinearLayout.LayoutParams((int) (190 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView value = chip(String.valueOf(prefs.keepPlayers()));
        SeekBar bar = new SeekBar(this);
        bar.setMax(Routing.MAX_PLAYERS - 1);
        bar.setProgress(prefs.keepPlayers() - 1);
        tint(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                value.setText(String.valueOf(p + 1));
                prefs.raw().edit().putInt(Prefs.KEY_KEEP_PLAYERS, p + 1).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { changed(); }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, (int) (36 * d), 1f));
        row.addView(value);
        c.addView(row);
    }

    private void styleSection() {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(plain("Player pad type", 16, Color.WHITE));
        words.addView(plain(Routing.XBOX.equals(prefs.style())
                ? "Xbox 360 pads, for games that only know Xbox controllers"
                : "Standard Android controllers. Try Xbox 360 if a game ignores them", 12, SUB));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
        GradientDrawable sbg = Ui.round(SIDE, 18 * d);
        sbg.setStroke(Math.max(1, (int) d), Color.rgb(0x1b, 0x1e, 0x25));
        seg.setBackground(sbg);
        String[][] styles = {{Routing.STANDARD, "Standard"}, {Routing.XBOX, "Xbox 360"}};
        for (final String[] s : styles) {
            boolean sel = s[0].equals(prefs.style());
            TextView t = plain(s[1], 14, sel ? Color.WHITE : SUB);
            t.setFocusable(true);
            t.setPadding((int) (14 * d), (int) (6 * d), (int) (14 * d), (int) (6 * d));
            if (sel) t.setBackground(Ui.round(Ui.SELECTED, 15 * d));
            t.setSingleLine(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(SettingsActivity.this, Haptics.PICK);
                    prefs.raw().edit().putString(Prefs.KEY_STYLE, s[0]).apply();
                    changed();
                    showTab(0);
                }
            });
            seg.addView(t);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = (int) (10 * d);
        row.addView(seg, slp);
        c.addView(row);
    }

    // ---- Controllers ---------------------------------------------------------------------------

    private void controllersSection() {
        JSONObject known = prefs.known();
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = known.keys(); it.hasNext(); ) keys.add(it.next());
        if (keys.isEmpty()) {
            TextView t = plain("Controllers show up here once PadBridge has seen them. Turn PadBridge on and "
                    + "connect a controller.", 14, SUB);
            t.setPadding(0, (int) (8 * d), 0, 0);
            page.addView(t);
            return;
        }
        for (final String key : keys) {
            JSONObject e = known.optJSONObject(key);
            final String name = e != null ? e.optString("name", key) : key;
            String connection = e != null ? e.optString("connection", "") : "";
            boolean builtin = key.equals(prefs.builtin()) || (prefs.builtin().isEmpty() && "Built-in".equals(connection));
            LinearLayout c = card();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(plain(builtin ? "📱" : "🎮", 20, Color.WHITE));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView n = plain(builtin ? "Thor controls" : name, 16, Color.WHITE);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(n);
            words.addView(plain((builtin ? name + " · " : "") + connection, 12, SUB));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wlp.leftMargin = (int) (10 * d);
            row.addView(words, wlp);
            final int pin = prefs.pin(key);
            TextView player = chip((pin > 0 ? "Player " + pin : pin == Routing.OFF ? "Left alone" : "Automatic") + "  ▾");
            player.setTextSize(15);
            player.setFocusable(true);
            player.setPadding((int) (12 * d), (int) (7 * d), (int) (12 * d), (int) (7 * d));
            player.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickPlayer(key, name, pin); }
            });
            row.addView(player);
            c.addView(row);

            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(0, (int) (8 * d), 0, 0);
            final String shown = builtin ? "Thor controls" : name;
            TextView tune = action("🎛️  Rumble, sticks and buttons", null);
            tune.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(SettingsActivity.this, Haptics.SETTING);
                    ControllerSettings.show(SettingsActivity.this, key, shown, new Runnable() {
                        @Override public void run() { showTab(1); }
                    });
                }
            });
            actions.addView(tune);
            final boolean marked = key.equals(prefs.builtin());
            actions.addView(action((marked ? "✅" : "📱") + "  Thor controls", new Runnable() {
                @Override public void run() { prefs.setBuiltin(marked ? "" : key); }
            }));
            actions.addView(action("🗑️  Forget", new Runnable() {
                @Override public void run() { prefs.forget(key); }
            }));
            c.addView(actions);
        }
        TextView note = plain("Rumble, sticks and buttons has rumble strength, stick deadzone, invert and Nintendo "
                + "layout. Tap Thor controls on the Thor's own controls if PadBridge didn't spot them (they show 📱).",
                12, Ui.DIM);
        note.setPadding((int) (4 * d), (int) (4 * d), 0, 0);
        page.addView(note);
    }

    private TextView action(String label, final Runnable run) {
        TextView t = Ui.button(this, label);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setPadding((int) (10 * d), (int) (5 * d), (int) (12 * d), (int) (5 * d));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(SettingsActivity.this, Haptics.SETTING);
                run.run();
                changed();
                showTab(1);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * d);
        t.setLayoutParams(lp);
        return t;
    }

    private void pickPlayer(final String key, String name, int pin) {
        final int[] values = new int[Routing.MAX_PLAYERS + 2];
        String[] labels = new String[values.length];
        for (int p = 1; p <= Routing.MAX_PLAYERS; p++) {
            values[p - 1] = p;
            labels[p - 1] = "Always Player " + p;
        }
        values[Routing.MAX_PLAYERS] = Routing.AUTO;
        labels[Routing.MAX_PLAYERS] = "Automatic (follows Who is Player 1)";
        values[Routing.MAX_PLAYERS + 1] = Routing.OFF;
        labels[Routing.MAX_PLAYERS + 1] = "Leave it to Android";
        int checked = Routing.MAX_PLAYERS;
        for (int i = 0; i < values.length; i++) if (values[i] == pin) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        Haptics.play(SettingsActivity.this, Haptics.PICK);
                        prefs.setPin(key, values[which]);
                        changed();
                        showTab(1);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- About ---------------------------------------------------------------------------------

    /** The repository's LICENSE file, its paragraphs unwrapped so they fit the screen. */
    private static final String LICENSE = "MIT License\n\n"
            + "Copyright (c) 2026 Anthony (ajhockey88)\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.";

    private void aboutSection() {
        tourSection();
        LinearLayout c = card();
        TextView t = plain("🎮 PadBridge " + versionName(), 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        TextView s = plain("The Thor's controls and your controllers as fixed players. Made for the AYN Thor by "
                + "ajhockey88. github.com/ajhockey88/PadBridge", 13, SUB);
        s.setPadding(0, (int) (4 * d), 0, 0);
        c.addView(s);

        c = card();
        t = plain("📄 License: MIT", 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        c.addView(plain("PadBridge is free and open source. You can use, copy, change and share it, as long as the "
                + "notice below comes with it.", 13, SUB));
        TextView body = plain(LICENSE, 12, Ui.DIM);
        body.setLineSpacing(0, 1.1f);
        body.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
        body.setBackground(Ui.round(Color.rgb(0x0c, 0x0e, 0x12), 8 * d));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = (int) (8 * d);
        c.addView(body, blp);

        c = card();
        t = plain("🙏 Thanks", 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        TextView thanks = plain("• Shizuku by RikkaApps (Apache License 2.0): PadBridge uses its API classes to start "
                + "its engine.\n• Docking Enhancer by Ezequiel-CE: the idea. None of its code is used.", 13, SUB);
        thanks.setLineSpacing(0, 1.15f);
        thanks.setPadding(0, (int) (4 * d), 0, 0);
        c.addView(thanks);
    }

    /** The setup tour: run it again, or finish the steps skipped last time. */
    private void tourSection() {
        LinearLayout c = card();
        TextView t = plain("🧭 Setup tour", 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        TextView s = plain("Asks how you play and picks the best settings, and shows how to set up your emulators. "
                + "Your current settings are the starting answers.", 13, SUB);
        s.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
        c.addView(s);
        LinearLayout row = new LinearLayout(this);
        row.addView(tourButton("Run the tour", false));
        final java.util.List<String> skipped = prefs.tourSkipped();
        if (!skipped.isEmpty()) {
            java.util.List<String> names = new ArrayList<>();
            for (String id : skipped) names.add(TourActivity.stepName(id));
            TextView k = plain("You skipped " + (skipped.size() == 1 ? "1 step" : skipped.size() + " steps") + ": "
                    + TextUtils.join(", ", names), 13, Ui.ACCENT_TEXT);
            k.setPadding(0, 0, 0, (int) (8 * d));
            c.addView(k);
            row.addView(tourButton("Finish skipped steps", true));
            TextView dismiss = action("Dismiss", new Runnable() {
                @Override public void run() { prefs.clearTourSkipped(); }
            });
            dismiss.setTextSize(14);
            dismiss.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(SettingsActivity.this, Haptics.SETTING);
                    prefs.clearTourSkipped();
                    showTab(tab);
                }
            });
            row.addView(dismiss);
        }
        c.addView(row);
    }

    private TextView tourButton(String label, final boolean skippedOnly) {
        TextView b = Ui.button(this, label);
        b.setTextSize(14);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(SettingsActivity.this, Haptics.PICK);
                TourActivity.open(SettingsActivity.this, skippedOnly);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * d);
        b.setLayoutParams(lp);
        return b;
    }

    // ---- Games ------------------------------------------------------------------------------------

    private void gamesSection() {
        final java.util.LinkedHashMap<String, Routing.Profile> all = prefs.profiles();
        String active = EngineControl.status(this).profile;
        if (all.isEmpty()) {
            LinearLayout c = card();
            c.addView(plain("🎯  No games yet", 15, Color.WHITE));
            TextView t = plain("Add a game or emulator to give it its own Player 1 and button map. They switch on "
                    + "while it's in front, and off when you leave it", 12, SUB);
            t.setPadding(0, (int) (4 * d), 0, 0);
            c.addView(t);
        }
        for (final java.util.Map.Entry<String, Routing.Profile> e : all.entrySet()) {
            final String pkg = e.getKey();
            final Routing.Profile p = e.getValue();
            LinearLayout c = card();
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.addView(plain(p.label.isEmpty() ? pkg : p.label, 16, Color.WHITE));
            words.addView(plain(pkg.equals(active) ? "✅ On now: it's in front" : pkg, 12, pkg.equals(active) ? Ui.GREEN : SUB));
            top.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView remove = Ui.button(this, "Remove");
            remove.setTextSize(13);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(SettingsActivity.this, Haptics.PICK);
                    prefs.setProfile(pkg, null);
                    changed();
                    showTab(2);
                }
            });
            top.addView(remove);
            c.addView(top);

            TextView who = plain("WHO IS PLAYER 1", 11, Ui.DIM);
            who.setLetterSpacing(0.1f);
            who.setPadding(0, (int) (10 * d), 0, (int) (4 * d));
            c.addView(who);
            LinearLayout seg = new LinearLayout(this);
            String[][] modes = {{"", "As usual"}, {Routing.THOR_FIRST, "📱 Thor"}, {Routing.CONTROLLER_FIRST, "🎮 Controller"},
                    {Routing.SHARED, "🤝 Both"}};
            for (final String[] m : modes) {
                boolean sel = m[0].equals(p.mode);
                TextView t = plain(m[1], 14, sel ? Color.WHITE : SUB);
                t.setFocusable(true);
                t.setSingleLine(true);
                t.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
                t.setBackground(Ui.round(sel ? Ui.SELECTED : Ui.BUTTON, 15 * d));
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Haptics.play(SettingsActivity.this, Haptics.PICK);
                        p.mode = m[0];
                        prefs.setProfile(pkg, p);
                        changed();
                        showTab(2);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = (int) (6 * d);
                seg.addView(t, lp);
            }
            c.addView(seg);

            LinearLayout map = new LinearLayout(this);
            map.setGravity(Gravity.CENTER_VERTICAL);
            map.setPadding(0, (int) (10 * d), 0, 0);
            final TextView summary = plain("🎛️  " + ButtonMap.summary(p.buttons) + ", on every controller", 14, Ui.TEXT);
            map.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView remap = Ui.button(this, "Remap");
            remap.setTextSize(13);
            remap.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(SettingsActivity.this, Haptics.PICK);
                    ButtonMap.show(SettingsActivity.this, p.label.isEmpty() ? pkg : p.label, p.buttons, new Runnable() {
                        @Override public void run() {
                            prefs.setProfile(pkg, p);
                            changed();
                            summary.setText("🎛️  " + ButtonMap.summary(p.buttons) + ", on every controller");
                        }
                    }, null);
                }
            });
            map.addView(remap);
            c.addView(map);
        }
        TextView add = Ui.button(this, "＋  Add a game or emulator");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(SettingsActivity.this, Haptics.PICK);
                pickGame(all.keySet());
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) (6 * d);
        page.addView(add, alp);
        TextView note = plain("A game's button map comes after each controller's own. With PadBridge on the bottom "
                + "screen, the game on the top screen still counts as in front", 12, Ui.DIM);
        note.setPadding(0, (int) (10 * d), 0, 0);
        page.addView(note);
    }

    /** Lists installed apps (not already added) to give one a profile. */
    private void pickGame(java.util.Set<String> have) {
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        final List<String[]> apps = new ArrayList<>();
        for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(main, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || have.contains(pkg)) continue;
            boolean dup = false;
            for (String[] a : apps) dup |= a[0].equals(pkg);
            if (!dup) apps.add(new String[] {pkg, String.valueOf(r.loadLabel(getPackageManager()))});
        }
        java.util.Collections.sort(apps, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); }
        });
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("🎯  Add a game or emulator")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Routing.Profile p = new Routing.Profile();
                        p.label = apps.get(which)[1];
                        prefs.setProfile(apps.get(which)[0], p);
                        changed();
                        showTab(2);
                    }
                })
                .show();
    }

    // ---- Hotkeys ----------------------------------------------------------------------------------

    private void hotkeysSection() {
        for (int i = 0; i < Hotkeys.ACTIONS.length; i++) {
            final String action = Hotkeys.ACTIONS[i];
            final String name = Hotkeys.ACTION_NAMES[i];
            LinearLayout c = card();
            c.setFocusable(true);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(plain(name, 16, Color.WHITE), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String combo = prefs.hotkey(action);
            TextView value = chip(Hotkeys.comboName(combo));
            if (combo.isEmpty()) value.setTextColor(SUB);
            row.addView(value);
            c.addView(row);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(SettingsActivity.this, Haptics.PICK);
                    final String[] items = new String[Hotkeys.COMBO_IDS.length + 1];
                    items[0] = "Off";
                    System.arraycopy(Hotkeys.COMBO_NAMES, 0, items, 1, Hotkeys.COMBO_NAMES.length);
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle(name)
                            .setItems(items, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) {
                                    prefs.setHotkey(action, which == 0 ? "" : Hotkeys.COMBO_IDS[which - 1]);
                                    changed();
                                    showTab(3);
                                }
                            })
                            .show();
                }
            });
        }
        TextView note = plain("Hold the buttons together on any controller. Its buttons don't reach the game while "
                + "held. Guide is the Xbox, PlayStation or Home button on a controller. Off by default, so games keep "
                + "every combo", 12, Ui.DIM);
        note.setPadding(0, (int) (10 * d), 0, 0);
        page.addView(note);
    }

    /** What a restart needs: Shizuku itself has to start again before PadBridge can. */
    private void restartNote() {
        LinearLayout c = card();
        c.addView(plain("🔁  After a restart", 15, Color.WHITE));
        TextView t = plain("PadBridge needs Shizuku running to start. With root, Shizuku starts with the Thor and "
                + "PadBridge follows by itself. Without root, Shizuku has to be started again after each restart (in "
                + "Shizuku, Start via Wireless debugging), unless your Shizuku version has a Start on boot option: "
                + "then turn that on. PadBridge shows a notice after a restart until Shizuku is running, and comes "
                + "back on its own once it is.", 12, SUB);
        t.setPadding(0, (int) (4 * d), 0, 0);
        c.addView(t);
    }

    // ---- Diagnostics ---------------------------------------------------------------------------

    private void diagnosticsOnLongPress(View v) {
        // Long-press a heading: saves the engine's log, the input devices and screenshots for bug reports.
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                saveDiagnostics();
                return true;
            }
        });
    }

    private void saveDiagnostics() {
        if (!ShizukuClient.hasPermission()) {
            Toast.makeText(this, "Needs Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Saving diagnostics…", Toast.LENGTH_SHORT).show();
        GuideService.dump();
        final String dir = EngineControl.folder(this).getAbsolutePath();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = ShizukuClient.runShell(
                        // A new time in each name: a file from last time may belong to another app now.
                        "d=/sdcard/Download; t=$(date +%H%M%S); cat '" + dir + "/engine.log' > $d/padbridge-engine-$t.txt; "
                        + "cat '" + dir + "/status.json' > $d/padbridge-status-$t.txt; "
                        + "cat '" + dir + "/config.json' > $d/padbridge-config-$t.txt; "
                        + "cat '" + dir + "/guide.txt' > $d/padbridge-guide-$t.txt 2>/dev/null; "
                        + "uiautomator dump $d/padbridge-ui-$t.xml >/dev/null 2>&1; "
                        + "cat /proc/bus/input/devices > $d/padbridge-devices-$t.txt; "
                        + "getevent -p > $d/padbridge-getevent-$t.txt 2>&1; "
                        + "dumpsys input > $d/padbridge-input-$t.txt; "
                        // Crashes of other apps (an emulator closing) and the last few minutes of the log.
                        + "ps -A -o PID,PPID,USER,STAT,NAME,ARGS > $d/padbridge-ps-$t.txt 2>&1; "
                        + "logcat -d -b crash > $d/padbridge-crash-$t.txt 2>&1; "
                        + "logcat -d -t 3000 > $d/padbridge-logcat-$t.txt 2>&1; "
                        + "i=0; for id in $(dumpsys SurfaceFlinger --display-id | sed -n 's/^Display \\([0-9]*\\).*/\\1/p'); do "
                        + "i=$((i+1)); screencap -d $id -p $d/padbridge-screen-$t-$i.png; done; true");
                final String log = Engine.read(new java.io.File(dir, "engine.log"));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boolean copied = copyLog(log);
                        Toast.makeText(SettingsActivity.this, (ok ? "Saved padbridge-* files to Download" : "Couldn't save the diagnostics")
                                + (copied ? ". The engine log is copied too: paste it in a message" : ""), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "PadBridge diagnostics").start();
    }

    /** Copies the end of the engine's log, so it can be pasted where a file won't upload. */
    private boolean copyLog(String log) {
        if (log == null || log.isEmpty()) return false;
        String[] lines = log.split("\n");
        StringBuilder sb = new StringBuilder("PadBridge " + versionName() + " engine log\n");
        for (int i = Math.max(0, lines.length - 120); i < lines.length; i++) sb.append(lines[i]).append('\n');
        android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clip == null) return false;
        clip.setPrimaryClip(android.content.ClipData.newPlainText("PadBridge engine log", sb.toString()));
        return true;
    }

    // ---- Updates -------------------------------------------------------------------------------

    private void updateSection() {
        list = card();
        final TextView status = plain("PadBridge " + versionName(), 16, Color.WHITE);
        status.setPadding(0, 0, 0, (int) (6 * d));
        list.addView(status);
        final Button check = new Button(this);
        check.setText("Check for updates");
        check.setAllCaps(false);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                check.setEnabled(false);
                final Updater[] updater = new Updater[1];
                updater[0] = new Updater(SettingsActivity.this, new Updater.Listener() {
                    @Override public void onUpdateStatus(String s, boolean done) {
                        status.setText(s);
                        if (done) check.setEnabled(true);
                    }

                    @Override public void onUpdateAvailable(Updater.Release r) {
                        UpdateDialog.show(SettingsActivity.this, r, updater[0], null);
                    }
                });
                updater[0].check();
            }
        });
        list.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Installs the latest release again even if it's this version, e.g. after its APK
        // was replaced on GitHub without a new version number.
        final Button reinstall = new Button(this);
        reinstall.setText("Reinstall");
        reinstall.setAllCaps(false);
        reinstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                reinstall.setEnabled(false);
                final Updater[] updater = new Updater[1];
                updater[0] = new Updater(SettingsActivity.this, new Updater.Listener() {
                    @Override public void onUpdateStatus(String s, boolean done) {
                        status.setText(s);
                        if (done) reinstall.setEnabled(true);
                    }

                    @Override public void onUpdateAvailable(Updater.Release r) {
                        UpdateDialog.show(SettingsActivity.this, r, updater[0], null);
                    }
                });
                updater[0].check(true);
            }
        });
        list.addView(reinstall, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list = page;
        releaseNotesSection();
    }

    /** A text file bundled with the app by build.sh, or null. */
    private String asset(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            return buf.toString("UTF-8");
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** This version's release notes (bundled with the app by build.sh), under the update buttons. */
    private void releaseNotesSection() {
        String notes = asset("release-notes.md");
        if (notes == null) return; // a build without notes: no section
        list = card();
        TextView title = plain("📄 Release notes · PadBridge " + versionName(), 16, Color.WHITE);
        title.setPadding(0, 0, 0, (int) (6 * d));
        list.addView(title);
        TextView body = plain("", 14, SUB);
        body.setText(ReleaseNotes.format(notes));
        body.setLineSpacing(0, 1.15f);
        list.addView(body);
        list = page;
    }

    // ---- Haptics -------------------------------------------------------------------------------

    private TextView previewPill;

    private final Runnable hidePill = new Runnable() {
        @Override public void run() { previewPill.setVisibility(View.GONE); }
    };

    private void hapticsSection() {
        if (!Haptics.hasStrengthControl(this)) {
            TextView motor = text("This device's vibration motor can't buzz softer or harder, so strength "
                    + "changes how long each buzz lasts instead.", 13);
            motor.setTextColor(SUB);
            list.addView(motor);
        }
        list = card();
        for (int a = 0; a < Haptics.ACTION_NAMES.length; a++) hapticRow(a);
        list = page;
        // Room so the preview pill never covers the last row.
        page.addView(new View(this), new LinearLayout.LayoutParams(1, (int) (50 * d)));
    }

    private void hapticRow(final int action) {
        final SharedPreferences sp = prefs.raw();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) (46 * d));

        TextView name = new TextView(this);
        name.setText((Haptics.ACTION_NAMES[action]));
        name.setTextSize(15);
        name.setTextColor(Color.WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Spinner feel = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Haptics.FEEL_NAMES) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(Color.rgb(0xcf, 0xe9, 0xff));
                t.setTextSize(15);
                return t;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        feel.setAdapter(adapter);
        feel.setSelection(Haptics.feel(sp, action), false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x1c, 0x1e, 0x24));
        bg.setCornerRadius(8 * d);
        feel.setBackground(bg);
        row.addView(feel, new LinearLayout.LayoutParams((int) (110 * d), (int) (36 * d)));

        // Strength: a chip showing the %, which opens a vertical slider under it.
        final int[] strength = {Haptics.strength(sp, action)};
        final TextView pct = chip(strength[0] + "%  ▾");
        pct.setTextSize(15);
        pct.setPadding((int) (10 * d), (int) (7 * d), (int) (10 * d), (int) (7 * d));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams((int) (76 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = (int) (10 * d);
        row.addView(pct, plp);
        pct.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                strengthPopup(pct, strength[0], new StrengthListener() {
                    @Override public void onStrength(int value, boolean done) {
                        strength[0] = value;
                        pct.setText(value + "%  ▾");
                        if (done) {
                            sp.edit().putInt(Haptics.strengthKey(action), value).apply();
                            preview(feel.getSelectedItemPosition(), value);
                        }
                    }
                });
            }
        });

        feel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == Haptics.feel(sp, action)) return; // not a change
                sp.edit().putInt(Haptics.feelKey(action), position).apply();
                preview(position, strength[0]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.addView(row);
    }

    private interface StrengthListener {
        /** done = the finger lifted: save and play a preview. */
        void onStrength(int value, boolean done);
    }

    /** A small panel under the chip: the % on top and a tall vertical slider (0 at the bottom). */
    private void strengthPopup(View anchor, int value, final StrengthListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, (int) (10 * d), 0, (int) (12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x16, 0x19, 0x1f));
        bg.setStroke(Math.max(1, (int) (0.8f * d)), Color.rgb(0x2a, 0x2f, 0x3a));
        bg.setCornerRadius(14 * d);
        box.setBackground(bg);
        final TextView shown = plain(value + "%", 17, Color.rgb(0xbf, 0xe6, 0xff));
        shown.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(shown);
        final int[] v = {value};
        View slider = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float cx = getWidth() / 2f, r = 12 * d, top = r + 2 * d, bottom = getHeight() - r - 2 * d;
                float y = bottom - (bottom - top) * v[0] / 100f;
                paint.setColor(Color.rgb(0x2e, 0x32, 0x3b));
                c.drawRoundRect(cx - 3.5f * d, top, cx + 3.5f * d, bottom, 3.5f * d, 3.5f * d, paint);
                paint.setColor(ACCENT);
                c.drawRoundRect(cx - 3.5f * d, y, cx + 3.5f * d, bottom, 3.5f * d, 3.5f * d, paint);
                paint.setColor(Color.argb(38, 120, 200, 255));
                c.drawCircle(cx, y, r + 6 * d, paint);
                paint.setColor(Color.rgb(0xbf, 0xe6, 0xff));
                c.drawCircle(cx, y, r, paint);
            }
            @Override public boolean onTouchEvent(MotionEvent ev) {
                float r = 12 * d, top = r + 2 * d, bottom = getHeight() - r - 2 * d;
                int value = Math.round((bottom - ev.getY()) / (bottom - top) * 100);
                value = Math.max(0, Math.min(100, value));
                int action = ev.getActionMasked();
                if (value != v[0]) {
                    v[0] = value;
                    shown.setText(value + "%");
                    listener.onStrength(value, false);
                    invalidate();
                }
                if (action == MotionEvent.ACTION_UP) listener.onStrength(v[0], true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        };
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams((int) (60 * d), (int) (170 * d));
        slp.topMargin = (int) (6 * d);
        slp.bottomMargin = (int) (4 * d);
        box.addView(slider, slp);
        box.addView(plain("▶", 12, SUB));
        PopupWindow popup = new PopupWindow(box, Math.max(anchor.getWidth(), (int) (76 * d)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(16 * d);
        popup.showAsDropDown(anchor, 0, (int) (4 * d));
    }

    private void preview(int feel, int strength) {
        Haptics.play(this, feel, strength);
        previewPill.setText(feel == Haptics.OFF || strength == 0
                ? ("Off") : String.format("▶ Preview: %s", (Haptics.FEEL_NAMES[feel]) + ", " + strength + "%"));
        previewPill.setVisibility(View.VISIBLE);
        previewPill.removeCallbacks(hidePill);
        previewPill.postDelayed(hidePill, 1500);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, (int) (6 * d), 0, (int) (4 * d));
        return t;
    }

    private void tint(SeekBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(0x2e, 0x32, 0x3b)));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT_TEXT));
    }

    /** A card with a title, a gray line under it, and a switch on the right; the whole card toggles. */
    private void toggle(String title, String sub, final String key, boolean value, final boolean restartEngine) {
        LinearLayout c = card();
        c.setFocusable(true);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(plain(title, 16, Color.WHITE));
        TextView s = plain(sub, 12, SUB);
        s.setPadding(0, (int) (2 * d), 0, 0);
        words.addView(s);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setFocusable(false);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[] {Ui.ACCENT_TEXT, Color.rgb(0x9a, 0xa0, 0xab)}));
        sw.setTrackTintList(new ColorStateList(states, new int[] {Color.rgb(0x2b, 0x6e, 0x99), Color.rgb(0x3a, 0x3e, 0x48)}));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                SharedPreferences.Editor e = prefs.raw().edit();
                e.putBoolean(key, checked);
                e.apply();
                Haptics.play(SettingsActivity.this, Haptics.SETTING);
                if (restartEngine) changed();
            }
        });
        LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swp.leftMargin = (int) (12 * d);
        row.addView(sw, swp);
        c.addView(row);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
    }
}
