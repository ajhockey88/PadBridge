package com.padbridge;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The setup tour: a few questions that pick PadBridge's best settings for how you play, and help
 * pointing your emulators at the PadBridge player pads. Offered once, after Shizuku setup, and any
 * time from Settings > About. Every step can be skipped; skipped steps keep their settings and are
 * listed in Settings > About to finish later.
 */
public class TourActivity extends Activity {
    /** Only the steps skipped last time. */
    static final String EXTRA_SKIPPED_ONLY = "skipped_only";

    static final String WELCOME = "welcome", PLAYER1 = "player1", THOR = "thor", PLAYERS = "players",
            CONTROLLERS = "controllers", RESTART = "restart", EMULATORS = "emulators", HOTKEYS = "hotkeys",
            DONE = "done";
    private static final String[] ALL = {WELCOME, PLAYER1, THOR, PLAYERS, CONTROLLERS, RESTART, EMULATORS, HOTKEYS, DONE};

    /** A step's name, as Settings > About lists the skipped ones. */
    static String stepName(String id) {
        switch (id) {
            case PLAYER1: return "Who is Player 1";
            case THOR: return "The Thor's controls";
            case PLAYERS: return "Players and pad type";
            case CONTROLLERS: return "Connect your controllers";
            case RESTART: return "After a restart";
            case EMULATORS: return "Set up your emulators";
            case HOTKEYS: return "Hotkeys";
            default: return id;
        }
    }

    private static final String OFF = "off", GUIDE = "guide", KEEP = "keep";
    private static final String SHIZUKU_ON_BOOT = "https://github.com/thedjchi/Shizuku/releases/latest";

    private float d;
    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> steps = new ArrayList<>();
    private int index;
    private LinearLayout body, nav, bars;
    private TextView stepLabel, title, lead;
    private ScrollView scroll;
    private EngineControl.Status status = new EngineControl.Status();
    private String shownStatus = "";
    private final Set<String> openEmulators = new HashSet<>();

    // The answers, starting from the current settings; saved as each step's Next is pressed.
    private String mode, style, homeBack, swap;
    private int players;
    private boolean thorNintendo, thorRead, announce, autoStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        getWindow().getDecorView().setBackgroundColor(Ui.BG);

        if (getIntent().getBooleanExtra(EXTRA_SKIPPED_ONLY, false)) {
            for (String id : ALL) if (prefs.tourSkipped().contains(id)) steps.add(id);
            steps.add(DONE);
        } else {
            steps.addAll(Arrays.asList(ALL));
        }

        // The tour needs PadBridge running: the controllers step lists what it sees, and the
        // emulators only list PadBridge's pads while they exist.
        if (!prefs.enabled()) prefs.setEnabled(true);
        EngineControl.pushConfig(this);

        mode = prefs.mode();
        style = prefs.style();
        players = prefs.keepPlayers();
        announce = prefs.raw().getBoolean(Prefs.KEY_ANNOUNCE, true);
        autoStart = prefs.autoStart();
        homeBack = prefs.hotkey(Hotkeys.HOME).isEmpty() && prefs.hotkey(Hotkeys.BACK).isEmpty() ? OFF
                : "guide_a".equals(prefs.hotkey(Hotkeys.HOME)) && "guide_b".equals(prefs.hotkey(Hotkeys.BACK)) ? GUIDE : KEEP;
        swap = prefs.hotkey(Hotkeys.SWAP).isEmpty() ? OFF : prefs.hotkey(Hotkeys.SWAP);
        if (!OFF.equals(swap) && !"guide_x".equals(swap) && !"l3_r3".equals(swap)) swap = KEEP;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding((int) (20 * d), (int) (16 * d), (int) (20 * d), (int) (12 * d));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        stepLabel = Ui.plain(this, "", 12, Ui.SUB);
        stepLabel.setLetterSpacing(0.08f);
        top.addView(stepLabel);
        bars = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, (int) (4 * d), 1f);
        blp.leftMargin = (int) (12 * d);
        top.addView(bars, blp);
        root.addView(top);

        title = Ui.plain(this, "", 23, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, (int) (12 * d), 0, 0);
        root.addView(title);
        lead = Ui.plain(this, "", 13, Ui.SUB);
        lead.setLineSpacing(0, 1.1f);
        lead.setPadding(0, (int) (4 * d), 0, (int) (10 * d));
        root.addView(lead);

        scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, (int) (10 * d), 0, 0);
        root.addView(nav);
        setContentView(root);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        poll.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    /** Keeps the controllers (and the Thor's) up to date while the tour is open. */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            new Thread(new Runnable() {
                @Override public void run() {
                    EngineControl.ensureRunning(TourActivity.this);
                    final EngineControl.Status s = EngineControl.status(TourActivity.this);
                    for (EngineControl.Controller c : s.controllers) prefs.remember(c.key, c.name, c.connection);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            status = s;
                            StringBuilder k = new StringBuilder().append(s.alive);
                            for (EngineControl.Controller c : s.controllers) {
                                k.append('|').append(c.key).append(c.player).append(c.builtin).append(c.battery);
                            }
                            if (k.toString().equals(shownStatus)) return;
                            shownStatus = k.toString();
                            String id = steps.get(index);
                            if (id.equals(CONTROLLERS) || id.equals(THOR)) fillBody(id);
                        }
                    });
                }
            }, "PadBridge tour").start();
            handler.postDelayed(this, 900);
        }
    };

    // ---- Steps ---------------------------------------------------------------------------------

    private void show() {
        String id = steps.get(index);
        int count = steps.size();
        stepLabel.setText("SETUP · " + (index + 1) + " OF " + count);
        bars.removeAllViews();
        for (int i = 0; i < count; i++) {
            View b = new View(this);
            b.setBackground(Ui.round(i <= index ? Ui.ACCENT : Ui.OUTLINE, 2 * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.leftMargin = (int) (4 * d);
            bars.addView(b, lp);
        }
        fillBody(id);
        nav.removeAllViews();
        boolean welcome = id.equals(WELCOME), done = id.equals(DONE);
        if (index > 0) nav.addView(navButton("Back", false, new Runnable() {
            @Override public void run() { go(-1); }
        }));
        if (welcome) {
            nav.addView(textButton("Skip, keep the defaults", new Runnable() {
                @Override public void run() {
                    prefs.setTourOffered();
                    finish();
                }
            }));
        } else if (!done) {
            nav.addView(textButton("Skip this step", new Runnable() {
                @Override public void run() {
                    prefs.setTourSkipped(steps.get(index), true);
                    go(1);
                }
            }));
        }
        View gap = new View(this);
        nav.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        nav.addView(navButton(welcome ? "Start" : done ? "Finish" : "Next", true, new Runnable() {
            @Override public void run() {
                String now = steps.get(index);
                if (now.equals(DONE)) {
                    prefs.setTourOffered();
                    finish();
                    return;
                }
                save(now);
                if (!now.equals(WELCOME)) prefs.setTourSkipped(now, false);
                go(1);
            }
        }));
        scroll.scrollTo(0, 0);
    }

    private void go(int by) {
        Haptics.play(this, Haptics.PICK);
        if (guiding != null) {
            guiding = null;
            GuideService.stop();
        }
        index = Math.max(0, Math.min(steps.size() - 1, index + by));
        show();
    }

    private void fillBody(String id) {
        body.removeAllViews();
        switch (id) {
            case WELCOME: welcome(); break;
            case PLAYER1: player1(); break;
            case THOR: thor(); break;
            case PLAYERS: players(); break;
            case CONTROLLERS: controllers(); break;
            case RESTART: restart(); break;
            case EMULATORS: emulators(); break;
            case HOTKEYS: hotkeys(); break;
            default: done(); break;
        }
    }

    /** Saves a step's answers. */
    private void save(String id) {
        android.content.SharedPreferences.Editor e = prefs.raw().edit();
        switch (id) {
            case PLAYER1:
                prefs.setMode(mode);
                break;
            case THOR:
                EngineControl.Controller thor = thorPad();
                if (thor != null) prefs.setNintendo(thor.key, thorNintendo);
                break;
            case PLAYERS:
                e.putInt(Prefs.KEY_KEEP_PLAYERS, players).putString(Prefs.KEY_STYLE, style);
                // Every connected controller joins in, so the next step shows them all.
                for (EngineControl.Controller c : status.controllers) {
                    if (c.player <= 0 && prefs.pin(c.key) == Routing.OFF) prefs.setPin(c.key, Routing.AUTO);
                }
                break;
            case CONTROLLERS:
                e.putBoolean(Prefs.KEY_ANNOUNCE, announce);
                break;
            case RESTART:
                e.putBoolean(Prefs.KEY_AUTO_START, autoStart);
                break;
            case HOTKEYS:
                if (!KEEP.equals(homeBack)) {
                    prefs.setHotkey(Hotkeys.HOME, GUIDE.equals(homeBack) ? "guide_a" : "");
                    prefs.setHotkey(Hotkeys.BACK, GUIDE.equals(homeBack) ? "guide_b" : "");
                }
                if (!KEEP.equals(swap)) prefs.setHotkey(Hotkeys.SWAP, OFF.equals(swap) ? "" : swap);
                break;
            default:
                return;
        }
        e.apply();
        EngineControl.pushConfig(this);
    }

    private void heading(String t, String l) {
        title.setText(t);
        lead.setText(l);
    }

    private void welcome() {
        heading("Set PadBridge up for how you play", "Seven quick steps, about two minutes. Skip any step and it "
                + "keeps its setting. Skipped steps wait for you in Settings, About.");
        String[][] topics = {{"Who is Player 1", "Handheld, docked, or both"},
                {"The Thor's controls", "Button positions"}, {"Players and pad type", "How many, Standard or Xbox 360"},
                {"Your controllers", "Connect them and check who's who"}, {"After a restart", "Coming back on by itself"},
                {"Your emulators", "Point them at the PadBridge pads"}, {"Hotkeys", "Home, Back and Swap from any controller"}};
        for (String[] t : topics) info(t[0], t[1]);
    }

    private void player1() {
        heading("Who should be Player 1?", "PadBridge keeps this order every time, so your emulators never need "
                + "remapping.");
        choice("The Thor, always", "Play handheld. Controllers you connect join as Player 2, 3 and so on.",
                Routing.THOR_FIRST.equals(mode), true, new Runnable() {
                    @Override public void run() { mode = Routing.THOR_FIRST; }
                });
        choice("A controller, when one is connected", "For docked play. The Thor's controls move to the next "
                + "player.", Routing.CONTROLLER_FIRST.equals(mode), false, new Runnable() {
                    @Override public void run() { mode = Routing.CONTROLLER_FIRST; }
                });
        choice("Both are Player 1", "The Thor and your first controller drive the same pad. Good for passing "
                + "the Thor to someone.", Routing.SHARED.equals(mode), false, new Runnable() {
                    @Override public void run() { mode = Routing.SHARED; }
                });
        tip("You can flip the first two players any time with Swap on the main screen.");
    }

    private EngineControl.Controller thorPad() {
        for (EngineControl.Controller c : status.controllers) if (c.builtin) return c;
        return null;
    }

    private void thor() {
        final EngineControl.Controller thor = thorPad();
        if (thor == null) {
            heading("The Thor's controls", "PadBridge can't see the Thor's controls yet. Make sure PadBridge is on; "
                    + "this page fills in by itself.");
            tip("You can skip this step and come back to it from Settings, About.");
            return;
        }
        if (!thorRead) {
            thorNintendo = prefs.nintendo(thor.key);
            thorRead = true;
        }
        boolean xboxStyle = thor.name.toLowerCase(java.util.Locale.ROOT).contains("xbox");
        heading("The Thor's controls", "Found: " + thor.name + (xboxStyle
                ? ". That means the Thor's handle style is set to Xbox." : "."));
        choice("Keep Xbox positions", "A is the bottom button, as printed on most controllers. Nothing changes.",
                !thorNintendo, true, new Runnable() {
                    @Override public void run() { thorNintendo = false; }
                });
        choice("Nintendo positions", "A on the right, B on the bottom. PadBridge swaps them for the Thor only.",
                thorNintendo, false, new Runnable() {
                    @Override public void run() { thorNintendo = true; }
                });
        tip(xboxStyle
                ? "Leave the Thor's handle style on Xbox in the Thor's own settings. PadBridge knows the Thor by its "
                        + "AYN id anyway, so Home and Back keep working, and games get every button."
                : "Tip: Xbox handle style, in the Thor's own settings, gives games the most buttons.");
        tip("If Home ever stops working, restart the Wayfinder accessibility service in Android's settings.");
    }

    private void players() {
        heading("How many players, and which pad type?", "Each player gets a PadBridge Player pad that stays put, "
                + "whichever controller is behind it.");
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = words("Player pads always ready", "Ready even before a controller connects. Up to "
                + Routing.MAX_PLAYERS + ".");
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = Ui.plain(this, String.valueOf(players), 22, Color.WHITE);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        row.addView(round("−", new Runnable() {
            @Override public void run() {
                players = Math.max(1, players - 1);
                value.setText(String.valueOf(players));
            }
        }));
        row.addView(value, new LinearLayout.LayoutParams((int) (40 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(round("+", new Runnable() {
            @Override public void run() {
                players = Math.min(Routing.MAX_PLAYERS, players + 1);
                value.setText(String.valueOf(players));
            }
        }));
        c.addView(row);
        section("PAD TYPE");
        choice("Standard", "Works with RetroArch, Dolphin, PPSSPP, AetherSX2 and most Android games.",
                !Routing.XBOX.equals(style), true, new Runnable() {
                    @Override public void run() { style = Routing.STANDARD; }
                });
        choice("Xbox 360", "For games and streaming apps that only accept Xbox controllers.",
                Routing.XBOX.equals(style), false, new Runnable() {
                    @Override public void run() { style = Routing.XBOX; }
                });
        tip("Rumble works with both.");
    }

    private void controllers() {
        heading("Connect your controllers", "Turn on or plug in each one now. They show up here as they connect, "
                + "with the player they got.");
        if (!status.alive) tip("PadBridge isn't running yet, so controllers can't show up. Turn it on from the "
                + "main screen.");
        boolean any = false;
        for (final EngineControl.Controller c : status.controllers) {
            if (c.player <= 0) {
                any = true;
                LinearLayout card = card();
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(words(c.builtin ? "Thor controls" : c.name, "Left to Android, so games don't see it as "
                        + "a PadBridge player"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(small("Add", true, new Runnable() {
                    @Override public void run() {
                        prefs.setPin(c.key, Routing.AUTO);
                        EngineControl.pushConfig(TourActivity.this);
                        shownStatus = "";
                    }
                }));
                card.addView(row);
                continue;
            }
            any = true;
            LinearLayout card = card();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = Ui.plain(this, String.valueOf(c.player), 14, Color.BLACK);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setGravity(Gravity.CENTER);
            n.setBackground(Ui.round(Ui.player(c.player), 15 * d));
            row.addView(n, new LinearLayout.LayoutParams((int) (30 * d), (int) (30 * d)));
            String sub = c.builtin ? "Built in" : c.connection + (c.battery >= 0 ? " · battery " + c.battery + "%" : "");
            LinearLayout w = words(c.builtin ? "Thor controls" : c.name, sub);
            w.setPadding((int) (12 * d), 0, 0, 0);
            row.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(row);
            String lower = c.name.toLowerCase(java.util.Locale.ROOT);
            boolean nintendoPad = lower.contains("pro controller") || lower.contains("joy-con")
                    || lower.contains("nintendo") || lower.contains("8bitdo");
            if (!c.builtin && nintendoPad && !prefs.nintendo(c.key)) {
                TextView t = Ui.plain(this, "Its buttons are in Nintendo positions. Tap to swap A/B and X/Y so they "
                        + "match their labels.", 12, Ui.ACCENT_TEXT);
                t.setPadding(0, (int) (8 * d), 0, 0);
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Haptics.play(TourActivity.this, Haptics.SETTING);
                        prefs.setNintendo(c.key, true);
                        EngineControl.pushConfig(TourActivity.this);
                        fillBody(CONTROLLERS);
                    }
                });
                card.addView(t);
            }
        }
        if (!any && status.alive) info("No controllers yet", "Connect one now, or skip this step.");
        toggle("Which player am I?", "A controller buzzes once per player number when it connects, and lights up "
                + "in its player's color where it can.", announce, new Runnable() {
                    @Override public void run() { announce = !announce; }
                });
    }

    private void restart() {
        heading("After the Thor restarts", "PadBridge can come back by itself, as soon as Shizuku is running.");
        toggle("Start by itself", "Starts PadBridge when the Thor turns on, after updates, and again if it ever "
                + "stops.", autoStart, new Runnable() {
                    @Override public void run() { autoStart = !autoStart; }
                });
        info("Shizuku needs one tap after a restart", "Without root, Android stops Shizuku when the Thor turns off. "
                + "PadBridge waits for it and sends you a notice that opens Shizuku. One tap and your pads are back.");
        LinearLayout c = card();
        c.addView(words("Want no taps at all?", "A Shizuku build with Start on boot (by thedjchi) can start itself "
                + "with wireless debugging. Install it in place of Shizuku, then PadBridge needs nothing after a "
                + "restart."));
        TextView get = Ui.button(this, "Get it on GitHub");
        get.setTextSize(13);
        get.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_ON_BOOT)));
                } catch (RuntimeException ignored) {
                }
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = (int) (8 * d);
        c.addView(get, glp);
    }

    private void emulators() {
        final List<Emulators.Emulator> found = Emulators.installed(this);
        heading("Set up your emulators", found.isEmpty()
                ? "PadBridge didn't find an emulator. Here's how to set up any of them."
                : "Tap one, then Guide me. It opens on the other screen, dimmed, with what to tap next lit up. "
                        + "Point each player at its PadBridge pad once, and it never needs remapping again.");
        for (Emulators.Emulator e : found) emulator(e);
        if (Emulators.ONLY_TESTED && !found.isEmpty()) return;
        Emulators.Emulator other = new Emulators.Emulator("Emulator", new String[0], Emulators.OTHER);
        other.name = found.isEmpty() ? "Any emulator" : "Another emulator";
        emulator(other);
    }

    /** The emulator being guided through on the other screen, or null. */
    private String guiding;
    private boolean guideFound;

    private void emulator(final Emulators.Emulator e) {
        final String pkg = e.pkg;
        final String id = pkg != null ? pkg : e.name;
        boolean open = openEmulators.contains(id) || pkg != null && pkg.equals(guiding);
        LinearLayout c = card();
        c.setFocusable(true);
        if (pkg != null && pkg.equals(guiding)) {
            GradientDrawable bg = Ui.round(Ui.CARD, 12 * d);
            bg.setStroke((int) (2 * d), Ui.ACCENT);
            c.setBackground(bg);
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String sub = pkg == null ? "Steps that work for most" : e.kind.equals(e.name) ? "Installed" : e.kind;
        row.addView(words(e.name, sub), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.plain(this, open ? "▴" : "▾", 16, Ui.SUB));
        c.addView(row);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(TourActivity.this, Haptics.PICK);
                if (!openEmulators.remove(id)) openEmulators.add(id);
                fillBody(EMULATORS);
            }
        });
        if (!open) return;
        if (pkg != null && pkg.equals(guiding) && e.spots.length > 0) {
            guidePanel(c, e);
            return;
        }
        String[] lines = e.steps.split("\n");
        for (int i = 0; i < lines.length; i++) {
            TextView t = Ui.plain(this, (i + 1) + ".  " + lines[i], 13, Ui.TEXT);
            t.setLineSpacing(0, 1.1f);
            t.setPadding(0, (int) (i == 0 ? 10 * d : 5 * d), 0, 0);
            c.addView(t);
        }
        if (pkg == null) return;
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, (int) (10 * d), 0, 0);
        if (e.spots.length > 0) {
            buttons.addView(small("Guide me on the other screen", true, new Runnable() {
                @Override public void run() { startGuide(e); }
            }));
        }
        buttons.addView(small("Just open it", false, new Runnable() {
            @Override public void run() { openOnOtherScreen(pkg); }
        }));
        c.addView(buttons);
    }

    /** While guiding: the spots, the one lit up now, and Tap it for me / Next / Stop. */
    private void guidePanel(LinearLayout c, Emulators.Emulator e) {
        int now = GuideService.step();
        for (int i = 0; i < e.spots.length; i++) {
            boolean cur = i == now, done = now < 0 || i < now;
            TextView t = Ui.plain(this, (done ? "✓  " : (i + 1) + ".  ") + e.spots[i].hint, 13,
                    cur ? Color.WHITE : done ? Ui.GREEN : Ui.SUB);
            if (cur) t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(0, (int) (i == 0 ? 10 * d : 5 * d), 0, 0);
            c.addView(t);
        }
        TextView state = Ui.plain(this, now < 0 ? e.done + " Then tap Done."
                : guideFound ? "Lit up on the other screen. Tap it there, or tap it for me."
                : "Looking for it on the other screen. If it's further down, scroll to it there, or tap Next if the "
                        + "emulator calls it something else.", 12, Ui.ACCENT_TEXT);
        state.setLineSpacing(0, 1.1f);
        state.setPadding(0, (int) (10 * d), 0, 0);
        c.addView(state);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, (int) (10 * d), 0, 0);
        if (now >= 0 && guideFound) {
            buttons.addView(small("Tap it for me", true, new Runnable() {
                @Override public void run() {
                    if (!GuideService.tap()) Toast.makeText(TourActivity.this, "Couldn't tap it. Tap it on the other "
                            + "screen", Toast.LENGTH_SHORT).show();
                }
            }));
        }
        if (now >= 0) buttons.addView(small("Next", false, new Runnable() {
            @Override public void run() { GuideService.next(); }
        }));
        buttons.addView(small(now < 0 ? "Done" : "Stop", now < 0, new Runnable() {
            @Override public void run() { stopGuide(); }
        }));
        c.addView(buttons);
    }

    private void startGuide(final Emulators.Emulator e) {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean on = GuideService.enable(TourActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!on) {
                            Toast.makeText(TourActivity.this, "Turn on PadBridge emulator guide here, then come back",
                                    Toast.LENGTH_LONG).show();
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            } catch (RuntimeException ignored) {
                            }
                            return;
                        }
                        guiding = e.pkg;
                        guideFound = false;
                        GuideService.start(e.pkg, e.spots, e.done, new GuideService.Listener() {
                            @Override public void onGuideStep(int step, boolean found) {
                                guideFound = found;
                                if (steps.get(index).equals(EMULATORS)) fillBody(EMULATORS);
                            }
                        });
                        openOnOtherScreen(e.pkg);
                        fillBody(EMULATORS);
                    }
                });
            }
        }, "PadBridge guide").start();
    }

    private void stopGuide() {
        guiding = null;
        GuideService.stop();
        fillBody(EMULATORS);
    }

    @Override
    protected void onDestroy() {
        GuideService.stop();
        super.onDestroy();
    }

    private TextView small(String label, boolean primary, final Runnable run) {
        TextView b = Ui.button(this, label);
        b.setTextSize(13);
        b.setSingleLine(true);
        if (primary) {
            b.setTextColor(Color.BLACK);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setBackground(Ui.round(Ui.ACCENT, 18 * d));
        }
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(TourActivity.this, Haptics.PICK);
                run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * d);
        b.setLayoutParams(lp);
        return b;
    }

    /** Opens an app on the screen PadBridge isn't on, so the steps stay in view next to it. */
    private void openOnOtherScreen(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Display here = getDisplay();
        Display other = here != null && here.getDisplayId() == Display.DEFAULT_DISPLAY
                ? Displays.secondary(this) : Displays.primary(this);
        try {
            if (other != null && here != null && other.getDisplayId() != here.getDisplayId()) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchDisplayId(other.getDisplayId());
                startActivity(i, o.toBundle());
                return;
            }
        } catch (RuntimeException ignored) {
            // That screen refused it; open it here.
        }
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    private void hotkeys() {
        heading("Hotkeys", "Button combos that work from any controller, mid-game. Off unless you pick them, so "
                + "games keep every combo.");
        section("ANDROID HOME AND BACK");
        List<String[]> hb = new ArrayList<>();
        hb.add(new String[] {OFF, "Off"});
        hb.add(new String[] {GUIDE, "Guide + A is Home, Guide + B is Back"});
        if (KEEP.equals(homeBack)) hb.add(new String[] {KEEP, "Keep mine"});
        for (final String[] o : hb) {
            choice(o[1], null, o[0].equals(homeBack), o[0].equals(GUIDE), new Runnable() {
                @Override public void run() { homeBack = o[0]; }
            });
        }
        section("SWAP PLAYERS 1 AND 2");
        List<String[]> sw = new ArrayList<>();
        sw.add(new String[] {OFF, "Off"});
        sw.add(new String[] {"guide_x", "Guide + X"});
        sw.add(new String[] {"l3_r3", "L3 + R3 (both sticks pressed)"});
        if (KEEP.equals(swap)) sw.add(new String[] {KEEP, "Keep mine"});
        for (final String[] o : sw) {
            choice(o[1], null, o[0].equals(swap), false, new Runnable() {
                @Override public void run() { swap = o[0]; }
            });
        }
        tip("More hotkeys, and settings per game, are in Settings.");
    }

    private void done() {
        heading("You're set", "Here's what PadBridge uses now. You can change any of it in Settings.");
        LinearLayout c = card();
        String m = Routing.CONTROLLER_FIRST.equals(prefs.mode()) ? "A controller, when connected"
                : Routing.SHARED.equals(prefs.mode()) ? "The Thor and a controller, together" : "The Thor, always";
        summary(c, "Player 1", m);
        EngineControl.Controller thor = thorPad();
        if (thor != null) summary(c, "Thor buttons", prefs.nintendo(thor.key) ? "Nintendo positions" : "Xbox positions");
        summary(c, "Players", prefs.keepPlayers() + " · " + (Routing.XBOX.equals(prefs.style()) ? "Xbox 360" : "Standard")
                + " pads");
        summary(c, "After a restart", prefs.autoStart() ? "Starts by itself once Shizuku runs" : "You start it");
        List<String> hk = new ArrayList<>();
        if (!prefs.hotkey(Hotkeys.HOME).isEmpty()) hk.add("Home");
        if (!prefs.hotkey(Hotkeys.BACK).isEmpty()) hk.add("Back");
        if (!prefs.hotkey(Hotkeys.SWAP).isEmpty()) hk.add("Swap");
        if (!prefs.hotkey(Hotkeys.OPEN).isEmpty()) hk.add("Open PadBridge");
        summary(c, "Hotkeys", hk.isEmpty() ? "Off" : TextUtils.join(", ", hk));
        List<String> skipped = new ArrayList<>();
        for (String id : prefs.tourSkipped()) skipped.add(stepName(id));
        if (!skipped.isEmpty()) {
            tip("Skipped: " + TextUtils.join(", ", skipped) + ". Finish them any time in Settings, About.");
        } else {
            tip("In your emulators, PadBridge Player 1 goes on port 1, Player 2 on port 2, and so on. You can run "
                    + "this tour again from Settings, About.");
        }
    }

    // ---- Pieces --------------------------------------------------------------------------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) (14 * d), (int) (11 * d), (int) (14 * d), (int) (11 * d));
        c.setBackground(Ui.round(Ui.CARD, 12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * d);
        body.addView(c, lp);
        return c;
    }

    private LinearLayout words(String t, String sub) {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.addView(Ui.plain(this, t, 15, Color.WHITE));
        if (sub != null) {
            TextView s = Ui.plain(this, sub, 12, Ui.SUB);
            s.setLineSpacing(0, 1.1f);
            s.setPadding(0, (int) (2 * d), 0, 0);
            w.addView(s);
        }
        return w;
    }

    private void info(String t, String sub) { card().addView(words(t, sub)); }

    private void section(String t) {
        TextView s = Ui.plain(this, t, 12, Ui.SUB);
        s.setLetterSpacing(0.06f);
        s.setPadding((int) (2 * d), (int) (6 * d), 0, (int) (6 * d));
        body.addView(s);
    }

    private void tip(String t) {
        TextView s = Ui.plain(this, t, 12, Color.rgb(0xbf, 0xc4, 0xcc));
        s.setLineSpacing(0, 1.15f);
        s.setPadding((int) (12 * d), (int) (6 * d), (int) (4 * d), (int) (6 * d));
        s.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                Ui.round(Ui.ACCENT, 0), insetBg()}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * d);
        body.addView(s, lp);
    }

    /** The tip's background: black, leaving a 3dp accent line on the left. */
    private android.graphics.drawable.Drawable insetBg() {
        return new android.graphics.drawable.InsetDrawable(Ui.round(Ui.BG, 0), (int) (3 * d), 0, 0, 0);
    }

    /** A pickable card: a ring that fills in when picked. */
    private void choice(String t, String sub, boolean selected, boolean recommended, final Runnable pick) {
        LinearLayout c = card();
        c.setFocusable(true);
        GradientDrawable bg = Ui.round(Ui.CARD, 12 * d);
        bg.setStroke((int) (2 * d), selected ? Ui.ACCENT : Ui.OUTLINE);
        c.setBackground(bg);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = Ui.plain(this, selected ? "✓" : "", 13, Color.BLACK);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable dot = Ui.round(selected ? Ui.ACCENT : Ui.CARD, 10 * d);
        if (!selected) dot.setStroke((int) (2 * d), Color.rgb(0x3a, 0x3f, 0x4a));
        mark.setBackground(dot);
        row.addView(mark, new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d)));
        LinearLayout w = words(recommended ? t + "  ·  Recommended" : t, sub);
        w.setPadding((int) (12 * d), 0, 0, 0);
        row.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(row);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(TourActivity.this, Haptics.PICK);
                pick.run();
                fillBody(steps.get(index));
            }
        });
    }

    private void toggle(String t, String sub, boolean on, final Runnable flip) {
        LinearLayout c = card();
        c.setFocusable(true);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(words(t, sub), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = Ui.chip(this, on ? "On" : "Off");
        if (!on) state.setTextColor(Ui.SUB);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = (int) (12 * d);
        row.addView(state, slp);
        c.addView(row);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(TourActivity.this, Haptics.SETTING);
                flip.run();
                fillBody(steps.get(index));
            }
        });
    }

    private TextView round(String label, final Runnable run) {
        TextView b = Ui.plain(this, label, 20, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        GradientDrawable bg = Ui.round(Ui.BG, 22 * d);
        bg.setStroke(Math.max(1, (int) d), Color.rgb(0x2c, 0x31, 0x3b));
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams((int) (44 * d), (int) (44 * d)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(TourActivity.this, Haptics.PICK);
                run.run();
            }
        });
        return b;
    }

    private void summary(LinearLayout c, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        row.addView(Ui.plain(this, k, 14, Ui.SUB), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = Ui.plain(this, v, 14, Color.WHITE);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        c.addView(row);
    }

    private TextView navButton(String label, boolean primary, final Runnable run) {
        TextView b = Ui.plain(this, label, 15, primary ? Color.BLACK : Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setMinHeight((int) (44 * d));
        b.setPadding((int) (22 * d), 0, (int) (22 * d), 0);
        if (primary) {
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setBackground(Ui.round(Ui.ACCENT, 22 * d));
        } else {
            GradientDrawable bg = Ui.round(Ui.BG, 22 * d);
            bg.setStroke(Math.max(1, (int) d), Color.rgb(0x2c, 0x31, 0x3b));
            b.setBackground(bg);
        }
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        return b;
    }

    private TextView textButton(String label, final Runnable run) {
        TextView b = Ui.plain(this, label, 14, Ui.SUB);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setMinHeight((int) (44 * d));
        b.setPadding((int) (14 * d), 0, (int) (8 * d), 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(TourActivity.this, Haptics.PICK);
                run.run();
            }
        });
        return b;
    }

    /** Opens the tour; with skippedOnly, just the steps skipped last time. */
    static void open(Context context, boolean skippedOnly) {
        Intent i = new Intent(context, TourActivity.class);
        i.putExtra(EXTRA_SKIPPED_ONLY, skippedOnly);
        context.startActivity(i);
    }
}
