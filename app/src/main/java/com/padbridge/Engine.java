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
public final class Engine implements PhysicalPad.Listener {
    /** Bumped when the engine changes; the app restarts an engine that doesn't match. */
    static final int VERSION = 2;
    /** struct input_event: 24 bytes with 64-bit timestamps, 16 with 32-bit ones. */
    static final int EVENT_SIZE = System.getProperty("os.arch", "").contains("64") ? 24 : 16;
    private static final int BITS_PER_WORD = EVENT_SIZE == 24 ? 64 : 32;

    private final File dir, config, status, apk;
    private final VirtualPads players;
    private Routing.Config cfg = new Routing.Config();
    private long configStamp = -1;
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
        players = new VirtualPads(new File(dir, "uinput.log"));
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: Engine <folder> <apk>");
            System.exit(2);
        }
        log("PadBridge engine " + VERSION + " starting, " + EVENT_SIZE + "-byte events");
        new Engine(new File(args[0]), new File(args[1])).run();
    }

    static void log(String s) {
        System.out.println(System.currentTimeMillis() + " " + s);
        System.out.flush();
    }

    private void run() {
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
            writeStatus(false);
            tick++;
            sleep(500);
        }
        shutdown();
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

    /** True when config.json changed. */
    private boolean loadConfig() {
        long stamp = config.lastModified();
        if (stamp == configStamp) return false;
        configStamp = stamp;
        String text = read(config);
        if (text == null) return false;
        Routing.Config next = Routing.Config.fromJson(text);
        synchronized (this) {
            if (next.keepPlayers < cfg.keepPlayers) fewerPlayers = true;
            cfg = next;
            for (PhysicalPad p : pads.values()) p.mapper.nintendo = cfg.nintendo.contains(p.dev.key());
        }
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
            p.mapper.nintendo = cfg.nintendo.contains(d.key());
            synchronized (this) {
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
                if (p.open(this)) log("took over " + p.dev.name + " as player " + p.player);
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
        refresh(pad.player);
    }

    /** Sends a player's state: every open controller on that player, merged. */
    private synchronized void refresh(int player) {
        if (player <= 0) return;
        merged.clear();
        for (PhysicalPad p : pads.values()) {
            if (p.player != player || !p.isOpen()) continue;
            p.mapper.normalizedInto(scratch);
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
    private static String exec(String... cmd) {
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
