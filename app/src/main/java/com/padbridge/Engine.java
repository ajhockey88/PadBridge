package com.padbridge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PadBridge's engine. It runs on its own as the shell user (started through Shizuku with
 * app_process), so it keeps going after the app closes. Every second it looks for controllers
 * coming and going, takes over the ones that get a player, and drives the player pads.
 *
 * It talks to the app through two files in the app's folder: the app writes config.json, the
 * engine writes status.json.
 */
public final class Engine implements PhysicalPad.Listener, VirtualPads.Listener {
    /** Bumped when the engine changes; the app restarts an engine that doesn't match. */
    static final int VERSION = 11;
    /** struct input_event: 24 bytes with 64-bit timestamps, 16 with 32-bit ones. */
    static final int EVENT_SIZE = System.getProperty("os.arch", "").contains("64") ? 24 : 16;
    private static final int BITS_PER_WORD = EVENT_SIZE == 24 ? 64 : 32;

    private final File dir, config, status, apk;
    private final VirtualPads players;
    /** The settings in effect: config.json, with the profile of the game in front (if any) on top. */
    private Routing.Config cfg = new Routing.Config();
    private String configText = "";
    /** The game whose profile is on (its package), or "". */
    private String profile = "";
    /** Each hotkey action's buttons (Hotkeys.ACTIONS order), 0 when off. */
    private long[] hotkeyMasks = new long[Hotkeys.ACTIONS.length];
    private boolean anyHotkey;
    /** Fewer players were asked for: remove the spare player pads on the next route. */
    private boolean fewerPlayers;
    /** Controllers by node, in the order they connected. */
    private final Map<String, PhysicalPad> pads = new HashMap<>();
    private final PadState scratch = new PadState(), merged = new PadState();
    private String lastStatus = "";
    private long lastStatusAt;
    private String error = "";

    private Engine(File dir, File apk) {
        this.dir = dir;
        this.apk = apk;
        config = new File(dir, "config.json");
        status = new File(dir, "status.json");
        players = new VirtualPads(new File(dir, "uinput.log"), this);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: Engine <folder> <apk> [native library folder]");
            System.exit(2);
        }
        log("PadBridge engine " + VERSION + " starting, " + EVENT_SIZE + "-byte events");
        if (args.length > 2) Native.load(args[2]);
        new Engine(new File(args[0]), new File(args[1])).run();
    }

    static void log(String s) {
        System.out.println(System.currentTimeMillis() + " " + s);
        System.out.flush();
    }

    /**
     * Unplugs player pads left by an engine that was stopped without cleaning up: its `uinput`
     * tool keeps them, so games would see a second Player 1. Those are tools marked as
     * PadBridge's, and, from before the mark, `uinput` tools whose parent is gone, which nothing
     * can drive any more.
     */
    private static void unplugLeftovers() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        int me = android.os.Process.myPid();
        for (File p : procs) {
            int pid;
            try {
                pid = Integer.parseInt(p.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == me) continue;
            String env = read(new File(p, "environ"));
            String cmd = read(new File(p, "cmdline"));
            if (cmd == null || !cmd.contains("uinput")) continue;
            boolean ours = env != null && env.contains(VirtualPads.MARK + "=1");
            boolean orphan = parent(p) == 1;
            if (ours || orphan) {
                log("unplugging leftover player pads (pid " + pid + ")");
                android.os.Process.killProcess(pid);
            }
        }
    }

    private static int parent(File proc) {
        String stat = read(new File(proc, "stat"));
        if (stat == null) return -1;
        // pid (name) state ppid ...: the name can hold spaces, so start after its ')'.
        String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        try {
            return Integer.parseInt(f[1]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void run() {
        unplugLeftovers();
        // Stopped with pkill (SIGTERM): unplug the pads on the way out.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { players.stop(); }
        }, "PadBridge shutdown"));
        long tick = 0;
        while (true) {
            if (tick % 20 == 0 && !apk.exists()) {
                // PadBridge was updated or uninstalled: this engine is stale.
                log("app changed, stopping");
                break;
            }
            if (loadConfig() && !cfg.enabled) {
                log("turned off");
                break;
            }
            if (tick % 2 == 0) {
                try {
                    scan();
                    route();
                } catch (Exception e) {
                    error = String.valueOf(e);
                    log("scan failed: " + e);
                }
            }
            // The game in front: every half second (every 2 s the slow way).
            if (Foreground.quick() || tick % 4 == 0) checkForeground();
            if (tick % 20 == 0) readBatteries();
            writeStatus(false);
            tick++;
            sleep(500);
        }
        shutdown();
    }

    /** Low: the notice shows at or below this, and clears once the controller charges past OK. */
    static final int BATTERY_LOW = 15, BATTERY_OK = 25;

    /** Reads every controller's battery (every 10 s), and gives notice when one runs low. */
    private void readBatteries() {
        List<PhysicalPad> list;
        synchronized (this) {
            list = new ArrayList<>(pads.values());
        }
        for (PhysicalPad p : list) {
            if (p.builtin) continue; // the Thor's own battery is the Thor's
            Battery b = Battery.read(p.dev.sysfs);
            p.battery = b;
            if (b == null || b.percent < 0) continue;
            if (b.charging || b.percent > BATTERY_OK) {
                p.warnedLow = false;
            } else if (b.percent <= BATTERY_LOW && !p.warnedLow) {
                p.warnedLow = true;
                log("low battery: " + p.dev.name + " " + b.percent + "%");
                String who = p.player > 0 ? "Player " + p.player + "'s " + p.dev.name : p.dev.name;
                exec("cmd", "notification", "post", "-S", "bigtext", "-t", "🔋 Controller battery low",
                        "padbridge-battery-" + Math.abs(p.dev.key().hashCode()),
                        who + " is at " + b.percent + "%. Charge it soon");
            }
        }
    }

    private void shutdown() {
        synchronized (this) {
            for (PhysicalPad p : pads.values()) p.close();
            pads.clear();
        }
        players.stop();
        cfg.enabled = false;
        writeStatus(true);
        System.exit(0);
    }

    /** Switches a game's profile on or off as it comes to the front or leaves. */
    private void checkForeground() {
        String pkg = "";
        if (!cfg.profiles.isEmpty()) pkg = Foreground.pick(Foreground.visible(), cfg.profiles.keySet());
        if (pkg.equals(profile)) return;
        profile = pkg;
        Routing.Profile p = cfg.profiles.get(pkg);
        log(pkg.isEmpty() ? "no game profile" : "game profile: " + (p.label.isEmpty() ? pkg : p.label));
        applyConfig(Routing.Config.fromJson(configText));
        try {
            route(); // a different Player 1 at once
        } catch (Exception e) {
            log("route failed: " + e);
        }
    }

    /** Takes these settings, with the current game's profile on top. */
    private synchronized void applyConfig(Routing.Config next) {
        Routing.Profile p = next.profiles.get(profile);
        if (p != null && !p.mode.isEmpty()) next.mode = p.mode;
        if (next.keepPlayers < cfg.keepPlayers) fewerPlayers = true;
        cfg = next;
        for (PhysicalPad pad : pads.values()) pad.mapper.apply(cfg.nintendo.contains(pad.dev.key()), settings(pad.dev.key()));
        anyHotkey = false;
        for (int i = 0; i < Hotkeys.ACTIONS.length; i++) {
            String combo = cfg.hotkeys.get(Hotkeys.ACTIONS[i]);
            hotkeyMasks[i] = combo == null ? 0 : Hotkeys.mask(combo);
            anyHotkey |= hotkeyMasks[i] != 0;
        }
    }

    /** A controller's settings, with the game profile's button map after its own. */
    private Routing.PadSettings settings(String key) {
        Routing.PadSettings own = cfg.pad(key);
        Routing.Profile p = cfg.profiles.get(profile);
        if (p == null || p.buttons.map == null) return own;
        Routing.PadSettings s = new Routing.PadSettings();
        s.rumble = own.rumble;
        s.deadzone = own.deadzone;
        s.invertRy = own.invertRy;
        s.map = Routing.compose(own.map, p.buttons.map);
        return s;
    }

    /**
     * True when config.json changed. Compared by content: the shared storage it lives on can
     * keep whole-second times, so two changes in one second (− tapped twice) look like one.
     */
    private boolean loadConfig() {
        String text = read(config);
        if (text == null || text.equals(configText)) return false;
        configText = text;
        applyConfig(Routing.Config.fromJson(text));
        log("config: " + text.replace('\n', ' '));
        return true;
    }

    /** Finds controllers that connected or disconnected. */
    private void scan() throws IOException {
        String text = read(new File("/proc/bus/input/devices"));
        if (text == null) throw new IOException("can't read /proc/bus/input/devices");
        List<InputScan.Device> found = InputScan.parseProcDevices(text, BITS_PER_WORD);
        Set<String> present = new HashSet<>();
        long now = System.currentTimeMillis();
        for (InputScan.Device d : found) {
            present.add(d.path + "|" + d.name);
            if (!d.isGamepad()) continue;
            String id = d.path + "|" + d.name;
            if (pads.containsKey(id)) continue;
            String ranges = exec("getevent", "-p", d.path);
            if (ranges == null) continue; // not readable yet; try again next scan
            InputScan.applyRanges(d, ranges);
            PhysicalPad p = new PhysicalPad(d, now);
            synchronized (this) {
                p.mapper.apply(cfg.nintendo.contains(d.key()), settings(d.key()));
                pads.put(id, p);
            }
            log("controller connected: " + d.name + " " + d.key() + " " + d.path);
        }
        synchronized (this) {
            for (java.util.Iterator<Map.Entry<String, PhysicalPad>> it = pads.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, PhysicalPad> e = it.next();
                if (!present.contains(e.getKey())) {
                    log("controller disconnected: " + e.getValue().dev.name);
                    e.getValue().close();
                    it.remove();
                }
            }
        }
    }

    /** Gives each controller its player, taking over or letting go as needed. */
    private void route() throws IOException {
        List<PhysicalPad> order;
        int most = 0;
        String style;
        boolean shrink;
        synchronized (this) {
            order = ordered();
            List<InputScan.Device> devs = new ArrayList<>();
            for (PhysicalPad p : order) devs.add(p.dev);
            int[] assigned = Routing.assign(devs, cfg);
            int builtin = Routing.builtinIndex(devs, cfg);
            for (int i = 0; i < order.size(); i++) {
                order.get(i).player = assigned[i];
                order.get(i).builtin = i == builtin;
                most = Math.max(most, assigned[i]);
            }
            most = Math.max(most, cfg.keepPlayers);
            style = cfg.style;
            shrink = fewerPlayers;
            fewerPlayers = false;
        }
        // Pads are only removed when asked: one left over from a controller that disconnected
        // stays, so a game never loses a player mid-session.
        if (shrink && most < players.count()) {
            log("fewer players: " + players.count() + " -> " + most);
            players.shrink(most);
        }
        // Player pads first, so a controller is never taken from Android before its pad exists.
        players.ensure(most, style);
        for (PhysicalPad p : order) {
            if (p.player > 0 && !p.isOpen()) {
                if (p.open(this)) {
                    log("took over " + p.dev.name + " as player " + p.player);
                    if (p.builtin) players.systemKeys(p.dev);
                }
                else log(p.dev.name + ": " + p.error);
            } else if (p.player <= 0 && p.isOpen()) {
                p.close();
                log("gave " + p.dev.name + " back to Android");
            }
        }
        for (int player = 1; player <= players.count(); player++) refresh(player);
    }

    private List<PhysicalPad> ordered() {
        List<PhysicalPad> order = new ArrayList<>(pads.values());
        java.util.Collections.sort(order, new java.util.Comparator<PhysicalPad>() {
            @Override public int compare(PhysicalPad a, PhysicalPad b) {
                return a.connectedAt != b.connectedAt ? Long.compare(a.connectedAt, b.connectedAt)
                        : a.dev.path.compareTo(b.dev.path);
            }
        });
        return order;
    }

    @Override
    public void onFrame(PhysicalPad pad) {
        if (anyHotkey) hotkeys(pad);
        refresh(pad.player);
    }

    private final java.util.concurrent.ExecutorService actions = java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * Runs a hotkey when its buttons are all held on this controller, once per press. Its
     * buttons are then held back from the player pad until let go, so the game doesn't get them.
     */
    private synchronized void hotkeys(PhysicalPad p) {
        p.mapper.normalizedInto(scratch);
        long held = scratch.buttons;
        p.suppress &= held;
        for (int i = 0; i < hotkeyMasks.length; i++) {
            long m = hotkeyMasks[i];
            if (m == 0) continue;
            boolean all = (held & m) == m, latched = (p.hotkeyLatch & (1 << i)) != 0;
            if (all && !latched) {
                p.hotkeyLatch |= 1 << i;
                p.suppress |= m;
                final String action = Hotkeys.ACTIONS[i];
                log("hotkey: " + action);
                actions.execute(new Runnable() {
                    @Override public void run() {
                        String[] cmd = Hotkeys.command(action);
                        if (cmd != null) exec(cmd);
                    }
                });
            } else if (!all) {
                p.hotkeyLatch &= ~(1 << i);
            }
        }
    }

    @Override
    public void onSystemKey(int code, boolean down) {
        players.systemKey(code, down);
    }

    private final java.util.concurrent.ExecutorService motor = java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * A game rumbled a player pad: rumble every controller on that player, as strongly as the
     * game asked, scaled by each controller's own rumble strength.
     */
    @Override
    public void onRumble(int player, boolean on, int strong, int weak, int ms) {
        List<PhysicalPad> targets = new ArrayList<>();
        synchronized (this) {
            for (PhysicalPad p : pads.values()) if (p.player == player && p.isOpen()) targets.add(p);
        }
        final int length = ms > 0 ? Math.min(ms, 5000) : 5000;
        for (PhysicalPad p : targets) {
            int strength = cfg.pad(p.dev.key()).rumble;
            if (strength <= 0) continue;
            final int s = Math.round(strong * strength / 100f), w = Math.round(weak * strength / 100f);
            final boolean go = on && (s > 0 || w > 0);
            if (!p.rumble(go, s, w, length) && p.builtin) {
                // The Thor's controls have no rumble of their own: use the Thor's motor.
                motor.execute(new Runnable() {
                    @Override public void run() {
                        if (go) exec("cmd", "vibrator_manager", "synced", "oneshot", "-a", String.valueOf(length),
                                String.valueOf(Math.max(1, Math.min(255, Math.round(255 * Math.max(s, w) / 65535f)))));
                        else exec("cmd", "vibrator_manager", "cancel");
                    }
                });
            }
        }
    }

    /** Sends a player's state: every open controller on that player, merged. */
    private synchronized void refresh(int player) {
        if (player <= 0) return;
        merged.clear();
        for (PhysicalPad p : pads.values()) {
            if (p.player != player || !p.isOpen()) continue;
            p.mapper.normalizedInto(scratch);
            if (p.suppress != 0) scratch.buttons &= ~p.suppress;
            merged.merge(scratch);
        }
        players.send(player, merged);
    }

    private void writeStatus(boolean force) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", VERSION);
            o.put("running", cfg.enabled);
            o.put("players", players.count());
            o.put("style", players.style());
            o.put("direct", players.direct());
            o.put("profile", profile);
            o.put("error", error);
            JSONArray list = new JSONArray();
            synchronized (this) {
                for (PhysicalPad p : ordered()) {
                    JSONObject d = new JSONObject();
                    d.put("key", p.dev.key());
                    d.put("name", p.dev.name);
                    d.put("connection", p.dev.connection());
                    d.put("builtin", p.builtin);
                    d.put("player", p.player);
                    d.put("active", p.isOpen());
                    d.put("error", p.error);
                    d.put("intervalMs", Math.round(p.intervalMs * 10) / 10.0);
                    d.put("addedMs", Math.round(p.addedMs * 1000) / 1000.0);
                    d.put("cantRumble", p.cantRumble && !p.builtin);
                    d.put("pressed", p.lastPressed);
                    d.put("pressSeq", p.pressSeq);
                    Battery b = p.battery;
                    if (b != null) {
                        d.put("battery", b.percent);
                        d.put("charging", b.charging);
                    }
                    list.put(d);
                }
            }
            o.put("controllers", list);
            String body = o.toString();
            long now = System.currentTimeMillis();
            if (!force && body.equals(lastStatus) && now - lastStatusAt < 1000) return;
            lastStatus = body;
            lastStatusAt = now;
            o.put("time", now);
            File tmp = new File(dir, "status.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            if (!tmp.renameTo(status)) log("couldn't write status");
        } catch (Exception e) {
            log("status failed: " + e);
        }
    }

    static String read(File f) {
        try {
            return readAll(new FileInputStream(f));
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /** Runs a command and returns what it printed, or null if it failed. */
    static String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return p.waitFor() == 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
