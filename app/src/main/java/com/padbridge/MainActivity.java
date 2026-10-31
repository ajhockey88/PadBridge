package com.padbridge;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * PadBridge's main screen, on the Thor's bottom screen: a card per player with the
 * controllers on it, the floating menu pill on top, and Shizuku setup when it's needed.
 */
public class MainActivity extends Activity implements ShizukuClient.Listener {
    private float d;
    private Prefs prefs;
    private LinearLayout content;
    private Button modeButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;
    /** What the cards show, so they're only rebuilt when something changed. */
    private String shown = "";
    private EngineControl.Status status = new EngineControl.Status();
    /** For screenshots: shows this instead of asking the engine, or the setup steps. */
    static EngineControl.Status demoStatus;
    static boolean demoSetup;
    private boolean busy;
    /** Swapping more than two players: 0 when not, -1 while picking the first, else that player. */
    private int swapFrom;
    private static final int BIG = 0, COMPACT = 1, MINI = 2;

    private static final String[][] MODES = {
            {Routing.THOR_FIRST, "📱", "Thor first", "The Thor's controls are Player 1. Controllers you connect are Player 2 and up"},
            {Routing.CONTROLLER_FIRST, "🎮", "Controller first", "A connected controller is Player 1 and the Thor's controls are the next player"},
            {Routing.SHARED, "🤝", "Both Player 1", "The Thor and your first controller both drive Player 1. More controllers are Player 2 and up"}};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().setBackgroundColor(Ui.BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (14 * d), (int) (78 * d), (int) (14 * d), (int) (12 * d));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The menu: a floating pill near the top, as in ThorPad. PadBridge's screen isn't a work
        // surface, so it stays shown.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        GradientDrawable barBg = Ui.round(Ui.CARD, 26 * d);
        barBg.setStroke(Math.max(1, (int) (0.8f * d)), Ui.OUTLINE);
        bar.setBackground(barBg);
        bar.setElevation(14 * d);
        modeButton = barButton(null, "", new View.OnClickListener() {
            @Override public void onClick(View v) { showModeMenu(); }
        });
        // The mode button stands out: it says who is Player 1.
        modeButton.setBackground(Ui.round(Ui.SELECTED, 22 * d));
        modeButton.setTextColor(Ui.ACCENT_TEXT);
        bar.addView(modeButton, weight(1.35f));
        bar.addView(separator());
        bar.addView(barButton("🔁", "Swap", new View.OnClickListener() {
            @Override public void onClick(View v) { swapPressed(); }
        }), weight(1f));
        bar.addView(separator());
        bar.addView(barButton("🧪", "Test", new View.OnClickListener() {
            @Override public void onClick(View v) { openTest(); }
        }), weight(1f));
        bar.addView(separator());
        bar.addView(barButton("⚙️", "Settings", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }), weight(1.1f));
        bar.addView(separator());
        Button close = barButton("✕", "Close", new View.OnClickListener() {
            @Override public void onClick(View v) { finishAndRemoveTask(); }
        });
        close.setTextColor(Ui.RED);
        bar.addView(close, weight(1f));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (54 * d), Gravity.TOP);
        blp.setMargins((int) (10 * d), (int) (10 * d), (int) (10 * d), 0);
        root.addView(bar, blp);
        setContentView(root);
        ShizukuClient.addListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        quietUpdateCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = "";
        poll.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    @Override
    protected void onStop() {
        started = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ShizukuClient.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onShizukuChanged() {
        shown = "";
        poll.run();
    }

    /** Reads what the engine is doing, (re)starts it if needed, and redraws when it changed. */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            if (demoStatus != null) {
                status = demoStatus;
                refresh();
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    EngineControl.ensureRunning(MainActivity.this);
                    final EngineControl.Status s = EngineControl.status(MainActivity.this);
                    for (EngineControl.Controller c : s.controllers) prefs.remember(c.key, c.name, c.connection);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            status = s;
                            refresh();
                        }
                    });
                }
            }, "PadBridge status").start();
            handler.postDelayed(this, 800);
        }
    };

    private boolean needsSetup() {
        return demoSetup || (demoStatus == null && !ShizukuClient.hasPermission());
    }

    private void refresh() {
        modeButton.setText(MODES[modeIndex()][1] + "  " + MODES[modeIndex()][2] + " ▾");
        StringBuilder key = new StringBuilder();
        key.append(needsSetup()).append(EngineControl.stuck()).append(ShizukuClient.isRunning()).append(prefs.enabled()).append(status.alive)
                .append(prefs.keepPlayers()).append(busy).append(status.error).append(swapFrom);
        for (EngineControl.Controller c : status.controllers) {
            key.append('|').append(c.key).append(c.player).append(c.active).append(c.builtin).append(c.error)
                    .append(prefs.nintendo(c.key)).append(prefs.pin(c.key));
        }
        if (key.toString().equals(shown)) return;
        shown = key.toString();
        content.removeAllViews();
        if (needsSetup()) {
            setupCard();
            return;
        }
        statusCard();
        playerCards();
        leftAlone();
    }

    // ---- Status ---------------------------------------------------------------------------------

    private void statusCard() {
        LinearLayout c = card(content);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean on = prefs.enabled();
        boolean running = on && status.alive;
        TextView dot = Ui.plain(this, running ? "🟢" : on ? "🟡" : "⚪", 16, Color.WHITE);
        row.addView(dot);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        int count = 0;
        for (EngineControl.Controller ctl : status.controllers) if (ctl.active) count++;
        boolean stuck = on && !running && demoStatus == null && EngineControl.stuck();
        String title = running ? "PadBridge is on" + (count > 0 ? " · " + count + (count == 1 ? " controller" : " controllers") : "")
                : stuck ? "PadBridge couldn't start" : on ? "Starting PadBridge…" : "PadBridge is off";
        String sub = running ? "Games see " + players(Math.max(status.players, 1)) + ", the same way every time"
                : stuck ? "Turn it off and on to try again. Long-press a heading in Settings to save diagnostics"
                : on ? "Starting the engine through Shizuku"
                : "Controllers work as usual. Turn it on to give them fixed players";
        if (stuck) dot.setText("🔴");
        TextView t = Ui.plain(this, title, 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(t);
        words.addView(Ui.plain(this, sub, 12, Ui.SUB));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = (int) (10 * d);
        row.addView(words, wlp);
        TextView power = Ui.button(this, on ? "🔌  Turn off" : "▶  Turn on");
        if (!on) {
            power.setBackground(Ui.round(Ui.SELECTED, 18 * d));
            power.setTextColor(Ui.ACCENT_TEXT);
        }
        power.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setOn(!prefs.enabled()); }
        });
        row.addView(power);
        c.addView(row);
        if (running && !status.error.isEmpty()) {
            TextView e = Ui.plain(this, "⚠️ " + status.error, 12, Ui.RED);
            e.setPadding(0, (int) (6 * d), 0, 0);
            c.addView(e);
        }
    }

    private static String players(int n) {
        if (n > 3) return "Players 1 to " + n;
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= n; p++) {
            if (p > 1) sb.append(p == n ? " and " : ", ");
            sb.append("Player ").append(p);
        }
        return sb.toString();
    }

    private void setOn(final boolean on) {
        Haptics.play(this, Haptics.POWER);
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                if (on) {
                    prefs.setEnabled(true);
                    EngineControl.retry();
                    EngineControl.ensureRunning(MainActivity.this);
                } else {
                    EngineControl.stop(MainActivity.this);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        shown = "";
                        poll.run();
                    }
                });
            }
        }, "PadBridge power").start();
    }

    // ---- Players --------------------------------------------------------------------------------

    /** Player cards shown: the players always ready, and any player a controller is on. */
    private int playerCount() {
        return Math.min(Routing.MAX_PLAYERS, Math.max(prefs.keepPlayers(), usedPlayers()));
    }

    private int usedPlayers() {
        int most = 0;
        for (EngineControl.Controller c : status.controllers) most = Math.max(most, c.player);
        return most;
    }

    private void playerCards() {
        int count = playerCount();
        boolean on = prefs.enabled() && status.alive;
        playersHeader(count);
        // Up to three side by side, four in a square, more in two rows.
        int cols = count == 4 ? 2 : count <= 3 ? count : (count + 1) / 2;
        int size = count <= 2 ? BIG : count <= 4 ? COMPACT : MINI;
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int p = 1; p <= count; p++) {
            int col = (p - 1) % cols;
            if (col == 0) {
                row = new LinearLayout(this);
                rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            row.addView(playerCard(p, on, size), cell(col, cols));
        }
        // Keep the last row's cards as wide as the others.
        for (int col = (count - 1) % cols + 1; col < cols; col++) row.addView(new View(this), cell(col, cols));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = (int) (2 * d);
        content.addView(rows, lp);
    }

    private LinearLayout.LayoutParams cell(int col, int cols) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(col == 0 ? 0 : (int) (4 * d), 0, col == cols - 1 ? 0 : (int) (4 * d), (int) (8 * d));
        return lp;
    }

    /** "PLAYERS", with − and + for how many are ready, or the swap prompt while swapping. */
    private void playersHeader(final int count) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (4 * d), 0, 0, (int) (6 * d));
        if (swapFrom != 0) {
            TextView t = Ui.plain(this, swapFrom < 0 ? "🔁  Tap the first player to swap"
                    : "🔁  Now tap the player to swap with Player " + swapFrom, 14, Ui.ACCENT_TEXT);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView cancel = Ui.button(this, "Cancel");
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    swapFrom = 0;
                    changed();
                }
            });
            row.addView(cancel);
            content.addView(row);
            return;
        }
        TextView label = Ui.plain(this, "PLAYERS", 11, Ui.DIM);
        label.setLetterSpacing(0.1f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final int least = Math.max(1, usedPlayers());
        TextView minus = stepper("−", count > least);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPlayers(count - 1); }
        });
        row.addView(minus);
        TextView value = Ui.plain(this, count + (count == 1 ? " player" : " players"), 14, Ui.TEXT);
        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams((int) (86 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView plus = stepper("+", count < Routing.MAX_PLAYERS);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPlayers(count + 1); }
        });
        row.addView(plus);
        content.addView(row);
    }

    private TextView stepper(String s, boolean enabled) {
        TextView t = Ui.button(this, s);
        t.setTextSize(17);
        t.setPadding(0, 0, 0, 0);
        t.setEnabled(enabled);
        t.setTextColor(enabled ? Ui.TEXT : Ui.DIM);
        if (!enabled) t.setBackground(Ui.round(Ui.CARD, 18 * d));
        t.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * d), (int) (30 * d)));
        return t;
    }

    /** How many player pads games always see; the rest come and go with controllers. */
    private void setPlayers(int n) {
        n = Math.max(Math.max(1, usedPlayers()), Math.min(Routing.MAX_PLAYERS, n));
        Haptics.play(this, Haptics.PICK);
        prefs.raw().edit().putInt(Prefs.KEY_KEEP_PLAYERS, n).apply();
        changed();
    }

    private View playerCard(final int p, boolean on, int size) {
        boolean compact = size != BIG, mini = size == MINI;
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable bg = Ui.round(Ui.CARD, 14 * d);
        boolean picked = swapFrom == p;
        bg.setStroke(Math.max(1, (int) ((picked ? 2.5f : 1.2f) * d)),
                picked ? Ui.player(p) : on ? blend(Ui.player(p), Ui.CARD, 0.35f) : Ui.OUTLINE);
        frame.setBackground(bg);
        if (swapFrom != 0) {
            // Swapping: the whole card is the button.
            frame.setFocusable(true);
            frame.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickSwap(p); }
            });
        }
        // The player's number, big and faint in the corner.
        TextView mark = Ui.plain(this, String.valueOf(p), mini ? 64 : compact ? 80 : 150,
                blend(on ? Ui.player(p) : Ui.DIM, Ui.CARD, 0.10f));
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setIncludeFontPadding(false);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        mlp.setMargins(0, 0, (int) ((mini ? 8 : 14) * d), (int) (2 * d));
        frame.addView(mark, mlp);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) ((mini ? 9 : 14) * d);
        c.setPadding(pad, (int) ((mini ? 9 : 12) * d), pad, pad);
        frame.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Ui.plain(this, String.valueOf(p), mini ? 13 : 15, Color.BLACK);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(on ? Ui.player(p) : Ui.DIM, (mini ? 7 : 9) * d));
        int bs = (int) ((mini ? 22 : 28) * d);
        head.addView(badge, new LinearLayout.LayoutParams(bs, bs));
        TextView name = Ui.plain(this, "Player " + p, mini ? 15 : compact ? 18 : 21, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = (int) ((mini ? 7 : 10) * d);
        head.addView(name, nlp);
        c.addView(head);
        if (!mini) {
            TextView sees = Ui.plain(this, "Games see “PadBridge Player " + p + "”", 12, Ui.DIM);
            sees.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
            sees.setSingleLine(true);
            sees.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(sees);
        } else {
            c.addView(new View(this), new LinearLayout.LayoutParams(1, (int) (8 * d)));
        }

        boolean any = false;
        for (final EngineControl.Controller ctl : status.controllers) {
            if (ctl.player != p) continue;
            any = true;
            c.addView(controllerRow(ctl, Ui.player(p), mini));
        }
        if (!any) {
            TextView empty = Ui.plain(this, on ? (mini ? "Free" : "No controller yet") : "—", mini ? 13 : 15, Ui.SUB);
            empty.setPadding(0, (int) ((mini ? 2 : 6) * d), 0, 0);
            c.addView(empty);
            if (on && !compact) {
                TextView hint = Ui.plain(this, p == 1 && Routing.THOR_FIRST.equals(prefs.mode())
                        ? "The Thor's controls land here" : "Connect a controller, or tap 🔁 Swap", 12, Ui.DIM);
                hint.setPadding(0, (int) (2 * d), 0, 0);
                c.addView(hint);
            }
        }
        return frame;
    }

    private View controllerRow(final EngineControl.Controller ctl, int color, boolean mini) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) ((mini ? 6 : 8) * d);
        row.setPadding((int) ((mini ? 7 : 10) * d), pad, pad, pad);
        row.setBackground(Ui.round(Color.rgb(0x16, 0x19, 0x1f), 10 * d));
        // While swapping, the card takes the tap.
        row.setFocusable(swapFrom == 0);
        TextView icon = Ui.plain(this, ctl.builtin ? "📱" : "🎮", mini ? 15 : 20, Color.WHITE);
        row.addView(icon);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView n = Ui.plain(this, ctl.builtin ? "Thor controls" : ctl.name, mini ? 12 : 15, Color.WHITE);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(n);
        if (mini) {
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wlp.leftMargin = (int) (6 * d);
            row.addView(words, wlp);
            if (prefs.pin(ctl.key) > 0) row.addView(Ui.plain(this, "📌", 10, Color.WHITE));
            if (!ctl.error.isEmpty()) row.addView(Ui.plain(this, "⚠️", 10, Color.WHITE));
            return finishRow(row, ctl, 4);
        }
        List<String> notes = new ArrayList<>();
        notes.add(ctl.builtin ? "Built-in" : ctl.connection);
        if (prefs.pin(ctl.key) > 0) notes.add("📌 Always here");
        if (prefs.nintendo(ctl.key)) notes.add("A/B swapped");
        String speed = ControllerSettings.shortSpeed(ctl);
        if (!speed.isEmpty()) notes.add(speed);
        TextView s = Ui.plain(this, TextUtils.join(" · ", notes), 12, Ui.SUB);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(s);
        if (!ctl.error.isEmpty()) words.addView(Ui.plain(this, "⚠️ " + ctl.error, 12, Ui.RED));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = (int) (10 * d);
        row.addView(words, wlp);
        row.addView(Ui.plain(this, "⋯", 18, Ui.SUB));
        return finishRow(row, ctl, 6);
    }

    private View finishRow(LinearLayout row, final EngineControl.Controller ctl, int gap) {
        if (swapFrom == 0) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { controllerMenu(ctl); }
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (gap * d);
        row.setLayoutParams(lp);
        return row;
    }

    /** Controllers PadBridge isn't bridging, with why. */
    private void leftAlone() {
        List<String> names = new ArrayList<>();
        for (final EngineControl.Controller ctl : status.controllers) {
            if (ctl.player <= 0) names.add(ctl.builtin ? "Thor controls" : ctl.name);
        }
        if (names.isEmpty()) {
            TextView tip = Ui.plain(this, "💡 Map PadBridge Player 1 to port 1, Player 2 to port 2 and so on, once in each "
                    + "emulator. They stay put whatever controllers you hold.", 12, Ui.DIM);
            tip.setPadding((int) (4 * d), (int) (2 * d), 0, 0);
            content.addView(tip);
            return;
        }
        TextView t = Ui.plain(this, "Left to Android: " + TextUtils.join(", ", names) + ". Tap to change.", 12, Ui.SUB);
        t.setPadding((int) (4 * d), (int) (2 * d), 0, 0);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (EngineControl.Controller ctl : status.controllers) {
                    if (ctl.player <= 0) {
                        controllerMenu(ctl);
                        return;
                    }
                }
            }
        });
        content.addView(t);
    }

    /**
     * Pick a player for a controller (a chip per player), its button layout, or mark it as the
     * Thor's own.
     */
    private void controllerMenu(final EngineControl.Controller ctl) {
        Haptics.play(this, Haptics.BAR);
        final int pin = prefs.pin(ctl.key);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (4 * d), (int) (24 * d), 0);
        TextView head = Ui.plain(this, "ALWAYS PLAYER", 11, Ui.DIM);
        head.setLetterSpacing(0.1f);
        head.setPadding(0, 0, 0, (int) (8 * d));
        box.addView(head);
        final AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout chips = new LinearLayout(this);
        for (int p = 1; p <= Routing.MAX_PLAYERS; p++) {
            final int player = p;
            boolean sel = pin == p;
            TextView chip = Ui.plain(this, String.valueOf(p), 17, sel ? Color.BLACK : Ui.player(p));
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            GradientDrawable bg = Ui.round(sel ? Ui.player(p) : Ui.BUTTON, 12 * d);
            if (!sel) bg.setStroke(Math.max(1, (int) (1 * d)), blend(Ui.player(p), Ui.BUTTON, 0.35f));
            chip.setBackground(bg);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog[0].dismiss();
                    pickFor(ctl, player);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (44 * d), 1f);
            lp.setMargins(p == 1 ? 0 : (int) (3 * d), 0, p == Routing.MAX_PLAYERS ? 0 : (int) (3 * d), 0);
            chips.addView(chip, lp);
        }
        box.addView(chips);
        View gap = new View(this);
        box.addView(gap, new LinearLayout.LayoutParams(1, (int) (10 * d)));
        menuItem(box, dialog, pin == Routing.AUTO, "✨", "Automatic (follows " + MODES[modeIndex()][2] + ")", ctl, Routing.AUTO);
        menuItem(box, dialog, pin == Routing.OFF, "🚫", "Leave it to Android", ctl, Routing.OFF);
        menuItem(box, dialog, false, "🎛️", "Rumble, sticks and buttons…", ctl, 102);
        menuItem(box, dialog, ctl.builtin, "📱", "These are the Thor's own controls", ctl, 101);
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle(ctl.builtin ? "Thor controls" : ctl.name)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void menuItem(LinearLayout box, final AlertDialog[] dialog, boolean checked, String icon, String text,
            final EngineControl.Controller ctl, final int action) {
        TextView t = Ui.plain(this, (checked ? "✅" : icon) + "  " + text, 17, Color.WHITE);
        t.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        t.setFocusable(true);
        t.setBackground(Ui.round(Color.TRANSPARENT, 10 * d));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog[0].dismiss();
                pickFor(ctl, action);
            }
        });
        box.addView(t);
    }

    /**
     * A player (or Automatic, or Leave it to Android), 100 for Nintendo layout, 101 for Thor
     * controls, 102 for the controller's settings.
     */
    private void pickFor(EngineControl.Controller ctl, int a) {
        if (a == 102) {
            ControllerSettings.show(this, ctl.key, ctl.builtin ? "Thor controls" : ctl.name, new Runnable() {
                @Override public void run() {
                    shown = "";
                    refresh();
                }
            });
            return;
        }
        if (a == 100) prefs.setNintendo(ctl.key, !prefs.nintendo(ctl.key));
        else if (a == 101) prefs.setBuiltin(ctl.builtin && ctl.key.equals(prefs.builtin()) ? "" : ctl.key);
        else prefs.setPin(ctl.key, a);
        Haptics.play(MainActivity.this, Haptics.PICK);
        changed();
    }

    // ---- Mode and swap --------------------------------------------------------------------------

    private int modeIndex() {
        String m = prefs.mode();
        for (int i = 0; i < MODES.length; i++) if (MODES[i][0].equals(m)) return i;
        return 0;
    }

    private void showModeMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
        GradientDrawable bg = Ui.round(Color.rgb(0x16, 0x19, 0x1f), 16 * d);
        bg.setStroke(Math.max(1, (int) (0.8f * d)), Color.rgb(0x2a, 0x2f, 0x3a));
        box.setBackground(bg);
        TextView head = Ui.plain(this, "WHO IS PLAYER 1", 11, Ui.DIM);
        head.setLetterSpacing(0.1f);
        head.setPadding((int) (10 * d), (int) (6 * d), 0, (int) (4 * d));
        box.addView(head);
        final PopupWindow popup = new PopupWindow(box, (int) (330 * d), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        for (int i = 0; i < MODES.length; i++) {
            final int which = i;
            boolean sel = i == modeIndex();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (10 * d), (int) (9 * d), (int) (10 * d), (int) (9 * d));
            row.setFocusable(true);
            if (sel) row.setBackground(Ui.round(Ui.SELECTED, 12 * d));
            row.addView(Ui.plain(this, MODES[i][1], 20, Color.WHITE));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView t = Ui.plain(this, MODES[i][2], 15, sel ? Ui.ACCENT_TEXT : Color.WHITE);
            words.addView(t);
            words.addView(Ui.plain(this, MODES[i][3], 12, Ui.SUB));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            wlp.leftMargin = (int) (10 * d);
            row.addView(words, wlp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    popup.dismiss();
                    Haptics.play(MainActivity.this, Haptics.PICK);
                    prefs.setMode(MODES[which][0]);
                    changed();
                }
            });
            box.addView(row);
        }
        popup.setOutsideTouchable(true);
        popup.setElevation(16 * d);
        popup.showAsDropDown(modeButton, 0, (int) (8 * d));
    }

    /**
     * 🔁 Swap: with two players it flips them right away; with more, you tap the two players
     * to swap.
     */
    private void swapPressed() {
        if (swapFrom != 0 || playerCount() <= 2) {
            if (swapFrom != 0) swapFrom = 0;
            else swapOneAndTwo();
            changed();
            return;
        }
        Haptics.play(this, Haptics.SWAP);
        swapFrom = -1;
        changed();
    }

    private void pickSwap(int p) {
        Haptics.play(this, Haptics.PICK);
        if (swapFrom < 0) swapFrom = p;
        else if (swapFrom == p) swapFrom = -1;
        else {
            swapPlayers(swapFrom, p);
            swapFrom = 0;
        }
        changed();
    }

    /** Swaps two players by fixing their controllers to each other's player. */
    private void swapPlayers(int a, int b) {
        Haptics.play(this, Haptics.SWAP);
        for (EngineControl.Controller ctl : status.controllers) {
            if (ctl.player == a) prefs.setPin(ctl.key, b);
            else if (ctl.player == b) prefs.setPin(ctl.key, a);
        }
        Toast.makeText(this, "Swapped Player " + Math.min(a, b) + " and Player " + Math.max(a, b)
                + " 📌", Toast.LENGTH_SHORT).show();
    }

    /** Swaps Players 1 and 2: flips Thor first and Controller first, and any fixed players. */
    private void swapOneAndTwo() {
        Haptics.play(this, Haptics.SWAP);
        String m = prefs.mode();
        prefs.setMode(Routing.THOR_FIRST.equals(m) ? Routing.CONTROLLER_FIRST : Routing.THOR_FIRST);
        for (EngineControl.Controller ctl : status.controllers) {
            int pin = prefs.pin(ctl.key);
            if (pin == 1) prefs.setPin(ctl.key, 2);
            else if (pin == 2) prefs.setPin(ctl.key, 1);
        }
        Toast.makeText(this, "Player 1 is now " + (Routing.THOR_FIRST.equals(prefs.mode())
                ? "the Thor's controls" : "your controller"), Toast.LENGTH_SHORT).show();
    }

    /** A setting changed: tell the engine and redraw. */
    private void changed() {
        EngineControl.pushConfig(this);
        shown = "";
        refresh();
        handler.postDelayed(poll, 600);
    }

    // ---- Controller test, on the other screen ---------------------------------------------------

    private void openTest() {
        Intent intent = new Intent(this, TestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Display target = Displays.testDisplay(this);
        try {
            if (target != null && target.getDisplayId() != getDisplay().getDisplayId()) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(target.getDisplayId());
                startActivity(intent, options.toBundle());
                return;
            }
        } catch (RuntimeException ignored) {
            // The system refused that screen; open it here instead.
        }
        startActivity(intent);
    }

    // ---- Setup ----------------------------------------------------------------------------------

    private void setupCard() {
        TextView h = Ui.plain(this, "Set up PadBridge", 22, Color.WHITE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(h);
        TextView lead = Ui.plain(this, "PadBridge needs Shizuku to take over controllers and make the player pads. "
                + "It's free, and many Thor owners already use it.", 13, Ui.SUB);
        lead.setPadding(0, (int) (2 * d), 0, (int) (12 * d));
        content.addView(lead);
        boolean installed = shizukuInstalled();
        boolean running = ShizukuClient.isRunning();
        step(1, "Install Shizuku", "From GitHub or the Play Store", installed,
                installed ? null : "Get Shizuku", new Runnable() {
                    @Override public void run() {
                        open("https://github.com/RikkaApps/Shizuku/releases/latest");
                    }
                });
        step(2, "Start Shizuku", "Open Shizuku and start it with Wireless debugging", running,
                running || !installed ? null : "Open Shizuku", new Runnable() {
                    @Override public void run() {
                        Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (i != null) startActivity(i);
                    }
                });
        step(3, "Allow PadBridge", "Shizuku asks once; PadBridge starts right after", ShizukuClient.hasPermission(),
                running ? "Allow" : null, new Runnable() {
                    @Override public void run() { ShizukuClient.requestPermission(); }
                });
        TextView note = Ui.plain(this, "After a restart, start Shizuku again and PadBridge comes back by itself.", 12, Ui.DIM);
        note.setPadding((int) (4 * d), (int) (6 * d), 0, 0);
        content.addView(note);
    }

    private void step(int n, String title, String sub, boolean done, String action, final Runnable run) {
        LinearLayout c = card(content);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = Ui.plain(this, done ? "✓" : String.valueOf(n), 15, done ? Color.BLACK : Color.WHITE);
        num.setGravity(Gravity.CENTER);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setBackground(Ui.round(done ? Ui.GREEN : Ui.BUTTON, 14 * d));
        row.addView(num, new LinearLayout.LayoutParams((int) (28 * d), (int) (28 * d)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(Ui.plain(this, title, 16, done ? Ui.SUB : Color.WHITE));
        words.addView(Ui.plain(this, sub, 12, Ui.SUB));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = (int) (12 * d);
        row.addView(words, wlp);
        if (action != null && !done) {
            TextView b = Ui.button(this, action);
            b.setBackground(Ui.round(Ui.SELECTED, 18 * d));
            b.setTextColor(Ui.ACCENT_TEXT);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.play(MainActivity.this, Haptics.BAR);
                    run.run();
                }
            });
            row.addView(b);
        }
        c.addView(row);
    }

    private boolean shizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException ignored) {
        }
    }

    // ---- Updates --------------------------------------------------------------------------------

    /**
     * Checked each time PadBridge opens, and when it comes back after an hour or more. Android
     * keeps the process around after Close, so this can't be once per process.
     */
    private boolean updateChecked;
    private static long lastUpdateCheckAt;
    private static final long UPDATE_RECHECK_MS = 60 * 60 * 1000;
    private static final String SKIPPED_UPDATE = "skipped_update";

    /**
     * Looks for a newer release when PadBridge opens and says nothing unless there is one.
     * "Not now" stops it asking again about that version; Settings > Check for updates still offers it.
     */
    private void quietUpdateCheck() {
        if (demoStatus != null) return;
        long now = SystemClock.elapsedRealtime();
        if (updateChecked && lastUpdateCheckAt != 0 && now - lastUpdateCheckAt < UPDATE_RECHECK_MS) return;
        updateChecked = true;
        lastUpdateCheckAt = now;
        final SharedPreferences sp = prefs.raw();
        final boolean[] installing = {false};
        final Updater[] updater = new Updater[1];
        updater[0] = new Updater(this, new Updater.Listener() {
            @Override public void onUpdateStatus(String s, boolean done) {
                // Silent while checking (even offline); once the user picked Update, say how it goes.
                if (!installing[0] && !done && !s.equals("Checking for updates…")) {
                    installing[0] = true;
                    Toast.makeText(MainActivity.this, "Downloading the update…", Toast.LENGTH_SHORT).show();
                } else if (installing[0] && done) {
                    Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show();
                }
            }

            @Override public void onUpdateAvailable(final Updater.Release r) {
                if (!started || isFinishing() || r.version.equals(sp.getString(SKIPPED_UPDATE, null))) return;
                UpdateDialog.show(MainActivity.this, r, updater[0], new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        sp.edit().putString(SKIPPED_UPDATE, r.version).apply();
                    }
                });
            }
        });
        updater[0].check();
    }

    // ---- Pieces ---------------------------------------------------------------------------------

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        c.setBackground(Ui.round(Ui.CARD, 12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * d);
        parent.addView(c, lp);
        return c;
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * t + Color.red(b) * (1 - t)),
                (int) (Color.green(a) * t + Color.green(b) * (1 - t)),
                (int) (Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }

    private static LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.setMargins(2, 0, 2, 0);
        return lp;
    }

    /** The thin line between two buttons in the menu pill. */
    private View separator() {
        View v = new View(this);
        v.setBackgroundColor(Ui.OUTLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(Math.max(1, (int) (0.8f * d)), (int) (20 * d)));
        return v;
    }

    /** A menu button: an emoji, then its label. */
    private Button barButton(String icon, String text, final View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(icon == null ? text : icon + "  " + text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextSize(14);
        b.setTextColor(Ui.TEXT);
        b.setPadding(0, 0, 0, 0);
        b.setStateListAnimator(null);
        b.setBackground(Ui.round(Color.TRANSPARENT, 22 * d));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(MainActivity.this, Haptics.BAR);
                listener.onClick(v);
            }
        });
        return b;
    }
}
