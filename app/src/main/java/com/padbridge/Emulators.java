package com.padbridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Emulators the setup tour knows, how to point each one at the PadBridge player pads, and what to
 * light up on the top screen while you do it. Emulators are found by name (in their app id or
 * their name on the home screen), so forks and nightly builds show up too, each on its own.
 */
final class Emulators {
    private Emulators() {}

    /** One thing to tap in the emulator: what the guide says, and the words on it to look for. */
    static final class Spot {
        static final int TOP_LEFT = 1, TOP_RIGHT = 2;
        final String hint;
        final String[] labels;
        /** For an icon button: the corner it's in, so it's found even with no words on it. 0: anywhere. */
        final int corner;
        /** Only something you can tap counts (not a heading with the same words). */
        boolean tap = true;

        Spot(String hint, String... labels) { this(0, hint, labels); }

        Spot(int corner, String hint, String... labels) {
            this.corner = corner;
            this.hint = hint;
            this.labels = labels;
        }

        /** A spot that's a setting's name rather than a button: lit up whether or not it's tappable. */
        static Spot look(String hint, String... labels) {
            Spot s = new Spot(hint, labels);
            s.tap = false;
            return s;
        }
    }

    static final class Emulator {
        final String kind;
        /** Words in the app id or app name that mean it's this emulator. */
        final String[] words;
        /** The steps, one per line. */
        final String steps;
        final Spot[] spots;
        /** What the guide says when its steps are done. */
        String done = "All set. Repeat the process for any additional players.";
        /** Once found: the app's name and app id. */
        String name, pkg;

        Emulator(String kind, String[] words, String steps, Spot... spots) {
            this.kind = kind;
            this.words = words;
            this.steps = steps;
            this.spots = spots;
        }

        Emulator found(String name, String pkg) {
            Emulator e = new Emulator(kind, words, steps, spots);
            e.done = done;
            e.name = name;
            e.pkg = pkg;
            return e;
        }
    }

    private static String[] w(String... words) { return words; }

    private static final String MANY = "\nDo the same for Player 2 to 4 with PadBridge Player 2 to 4.";

    static List<Emulator> all() {
        List<Emulator> l = new ArrayList<>();
        l.add(new Emulator("Switch emulator", w("eden", "yuzu", "citron", "sudachi", "suyu", "torzu", "strato", "skyline"),
                "Tap the cog at the top right (Settings), then Input (or Controls).\n"
                        + "Open Player 1, tap Auto-map a controller (pick PadBridge Player 1 if it asks), then tap the back "
                        + "arrow at the top left." + MANY,
                new Spot(Spot.TOP_RIGHT, "Tap the cog at the top right (Settings)", "settings", "options"),
                new Spot("Open the controller settings", "input", "controls", "input settings"),
                new Spot("Open Player 1", "player 1"),
                new Spot("Tap Auto-map a controller. If it asks which one, pick PadBridge Player 1",
                        "auto-map a controller", "auto map a controller", "auto-map"),
                new Spot(Spot.TOP_LEFT, "Tap the back arrow at the top left", "navigate up", "back", "go back"),
                new Spot("Now Player 2: open it", "player 2"),
                new Spot("Tap Auto-map a controller. If it asks which one, pick PadBridge Player 2",
                        "auto-map a controller", "auto map a controller", "auto-map"),
                new Spot(Spot.TOP_LEFT, "Tap the back arrow at the top left", "navigate up", "back", "go back")));
        l.add(new Emulator("RetroArch", w("retroarch"),
                "Open Settings, then Input, then RetroPad Binds.\n"
                        + "Open Port 1 Controls and set Device Index to PadBridge Player 1."
                        + "\nDo the same for Ports 2 to 4 with PadBridge Player 2 to 4.\n"
                        + "Back in Main Menu, open Configuration File and tap Save Current Configuration.",
                new Spot("Open Settings", "settings"), new Spot("Open Input", "input"),
                new Spot("Open RetroPad Binds", "retropad binds"), new Spot("Open Port 1 Controls", "port 1 controls"),
                new Spot("Set Device Index to PadBridge Player 1", "device index")));
        l.add(new Emulator("Dolphin", w("dolphinemu", "jr"),
                "Open Settings, then GameCube Input.\n"
                        + "Set Port 1 to Standard Controller, open it, choose PadBridge Player 1 as the device and tap Default."
                        + "\nDo the same for Ports 2 to 4 with PadBridge Player 2 to 4.\n"
                        + "Wii Input works the same way.",
                new Spot("Open Settings", "settings", "config"), new Spot("Open GameCube Input", "gamecube input"),
                new Spot("Open Port 1", "port 1"), new Spot("Pick the device", "device"),
                new Spot("Choose PadBridge Player 1", "padbridge player 1"), new Spot("Tap Default", "default")));
        l.add(new Emulator("PPSSPP", w("ppsspp"),
                "Open Settings, then Controls, then Control mapping.\n"
                        + "Tap each button and press it once on the controller you play with.\n"
                        + "PPSSPP is one player, and the mapping works for every PadBridge pad.",
                new Spot("Open Settings", "settings"), new Spot("Open Controls", "controls"),
                new Spot("Open Control mapping", "control mapping")));
        l.add(new Emulator("PS2 emulator", w("aethersx2", "nethersx2", "pcsx2"),
                "Open Settings, then Controller Settings.\n"
                        + "Open Controller Port 1, tap Automatic Mapping and pick PadBridge Player 1."
                        + "\nDo the same for Port 2 with PadBridge Player 2.",
                new Spot("Open Settings", "settings"), new Spot("Open Controller Settings", "controller settings", "controllers"),
                new Spot("Open Controller Port 1", "controller port 1", "port 1"),
                new Spot("Tap Automatic Mapping", "automatic mapping"), new Spot("Choose PadBridge Player 1", "padbridge player 1")));
        l.add(new Emulator("ARMSX2", w("armsx2"),
                "On the Library screen, tap the menu button at the top left, then Settings.\n"
                        + "Open the Controls tab.\n"
                        + "Open Player & Rumble.\n"
                        + "For 2 or more players, make sure All controllers play as Player 1 is off.\n"
                        + "Optional, for 3 or more players: turn on Multitap (up to 8 players).\n"
                        + "Find PadBridge Player 1 in the controller list and set it to Player 1.\n"
                        + "Set PadBridge Player 2 to Player 2. Leave rumble on Auto.\n"
                        + "If you turned on Multitap: set PadBridge Player 3 to Player 3, Player 4 to Player 4, "
                        + "and so on for each controller.\n"
                        + "Buttons: under Editing, pick Player 1, tap an action, then press that button on your controller. "
                        + "Usually they're already right.",
                new Spot(Spot.TOP_LEFT, "Tap the menu button at the top left", "open navigation drawer",
                        "navigation drawer", "open menu", "menu", "navigation"),
                new Spot("Tap Settings", "settings"),
                new Spot("Open the Controls tab", "controls"),
                new Spot("Open Player & Rumble", "player & rumble", "player and rumble"),
                Spot.look("For 2 or more players, turn this off", "all controllers play as player 1"),
                Spot.look("Optional, for 3 or more players: turn on Multitap. Otherwise tap Next",
                        "multitap (up to 8 players)", "multitap"),
                Spot.look("Set PadBridge Player 1 to Player 1", "padbridge player 1"),
                Spot.look("Set PadBridge Player 2 to Player 2", "padbridge player 2"),
                Spot.look("With Multitap on: set PadBridge Player 3 to Player 3, and so on. Otherwise tap Next to finish",
                        "padbridge player 3")));
        l.add(new Emulator("DuckStation", w("duckstation"),
                "Open Settings, then Controllers.\n"
                        + "Open Port 1, tap Automatic Mapping and pick PadBridge Player 1."
                        + "\nDo the same for Port 2 with PadBridge Player 2.",
                new Spot("Open Settings", "settings"), new Spot("Open Controllers", "controllers", "controller settings"),
                new Spot("Open Port 1", "port 1", "controller port 1"), new Spot("Tap Automatic Mapping", "automatic mapping"),
                new Spot("Choose PadBridge Player 1", "padbridge player 1")));
        l.add(new Emulator("3DS emulator", w("azahar", "lime3ds", "citra", "borked3ds", "mandarine", "pabloland"),
                "Open Settings, then Controls.\n"
                        + "Tap each button and press it once on the controller you play with.\n"
                        + "3DS games are one player.",
                new Spot("Open Settings", "settings"), new Spot("Open Controls", "controls", "input")));
        l.add(new Emulator("Lemuroid", w("lemuroid"),
                "Lemuroid gives players out in the order pads appear, so PadBridge Player 1 is its Player 1.\n"
                        + "To check, open Settings, then Gamepad, and look at Player 1.",
                new Spot("Open Settings", "settings"), new Spot("Open Gamepad", "gamepad", "gamepad settings")));
        l.add(new Emulator("Flycast", w("flycast"),
                "Open Settings, then Controls.\n"
                        + "Set Port A to PadBridge Player 1.\nDo the same for Ports B to D with PadBridge Player 2 to 4.",
                new Spot("Open Settings", "settings"), new Spot("Open Controls", "controls")));
        l.add(new Emulator("melonDS", w("melonds"),
                "Open Settings, then Input mapping.\n"
                        + "Tap each button and press it once on the controller you play with.",
                new Spot("Open Settings", "settings"), new Spot("Open Input mapping", "input mapping", "input")));
        l.add(new Emulator("Vita3K", w("vita3k"),
                "Vita3K uses the first pad it sees, which is PadBridge Player 1. Nothing to map.",
                new Spot("Open Settings", "settings")));
        l.add(new Emulator("N64 emulator", w("mupen64plusae", "mupen64plus"),
                "Open Settings, then Controller profiles, and choose PadBridge Player 1 for Player 1." + MANY,
                new Spot("Open Settings", "settings"), new Spot("Open Controller profiles", "controller profiles")));
        return l;
    }

    /** The emulators that are installed, one entry per app (so a nightly build has its own). */
    static List<Emulator> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<Emulator> known = all(), out = new ArrayList<>();
        Emulator other = new Emulator("Emulator", w(), OTHER,
                new Spot("Open Settings", "settings", "options", "config"),
                new Spot("Open the controller settings", "input", "controls", "controllers", "controller", "gamepad"),
                new Spot("Open Player 1", "player 1", "port 1", "controller 1"),
                new Spot("Choose PadBridge Player 1", "padbridge player 1"));
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
            String label = String.valueOf(r.loadLabel(pm));
            Emulator e = match(known, pkg, label);
            if (e == null && looksLikeEmulator(pkg, label)) e = other;
            if (e != null) out.add(e.found(label, pkg));
        }
        java.util.Collections.sort(out, new java.util.Comparator<Emulator>() {
            @Override public int compare(Emulator a, Emulator b) { return a.name.compareToIgnoreCase(b.name); }
        });
        return out;
    }

    /** The emulator an app is, going by whole words in its app id and name. */
    static Emulator match(List<Emulator> known, String pkg, String label) {
        Set<String> tokens = new HashSet<>();
        for (String s : (pkg + " " + label).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (!s.isEmpty()) tokens.add(s);
        String joined = pkg.toLowerCase(Locale.ROOT);
        for (Emulator e : known) {
            for (String word : e.words) {
                // "jr" alone is too common: it only means Dolphin MMJR in "org.mm.jr".
                if (word.equals("jr") ? joined.equals("org.mm.jr") : tokens.contains(word)) return e;
            }
        }
        return null;
    }

    /** An emulator PadBridge doesn't know by name: "emu" or "emulator" in its id or name, or "...sx2". */
    static boolean looksLikeEmulator(String pkg, String label) {
        for (String s : (pkg + " " + label).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (s.equals("emu") || s.startsWith("emulator") || s.endsWith("emu") && s.length() >= 4
                    || s.endsWith("sx2") || s.endsWith("emulation")) return true;
        }
        return false;
    }

    /** For any other emulator. */
    static final String OTHER = "Open its controller settings.\n"
            + "For Player 1, pick PadBridge Player 1. For Player 2, PadBridge Player 2, and so on.\n"
            + "If it asks you to press buttons, press each one once. You only do this once.";
}
