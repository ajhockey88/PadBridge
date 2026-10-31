package com.padbridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player pads, made with Android's `uinput` shell tool: one tool process holds them all,
 * registered in player order so Player 1 always comes before Player 2. A pad exists while the
 * tool runs; stopping it unplugs them all. When a game rumbles a pad, the tool says so on its
 * output, and the engine passes it on to that player's controllers.
 */
final class VirtualPads {
    interface Listener { void onRumble(int player, boolean on); }

    /** The tool's rumble lines: {"reason":"vibrating","id":2,"status":1}, in any order. */
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)"),
            STATUS = Pattern.compile("\"status\"\\s*:\\s*(-?\\d+)");
    /** In the `uinput` tool's environment: it holds PadBridge's player pads. */
    static final String MARK = "PADBRIDGE_PADS";
    /**
     * The Thor's system buttons (Back, Home, Recents, volume) while PadBridge holds its controls:
     * a small keyboard with the Thor's own name, maker and model, so Android reads its keys with
     * the Thor's key layout and apps that watch the Thor's buttons still see them. The tool can't
     * remove one device, so a Thor that changes identity (another handle style) gets a new one.
     */
    private int keysId;
    private String keysIdentity = "";
    private final File log;
    private final Listener listener;
    private Process process;
    private OutputStream stdin;
    private final List<PlayerPad> pads = new ArrayList<>();
    private String style = Routing.STANDARD;

    VirtualPads(File log, Listener listener) {
        this.log = log;
        this.listener = listener;
    }

    synchronized int count() { return pads.size(); }

    synchronized String style() { return style; }

    /** Makes Player 1 to `players` exist (never fewer than already exist), in this style. */
    synchronized void ensure(int players, String style) throws IOException {
        if (!style.equals(this.style) || (process != null && !alive())) {
            // A different style is a different device to Android: unplug and plug in again.
            int had = pads.size();
            stop();
            this.style = style;
            players = Math.max(players, had);
        }
        if (process == null) {
            // Android numbers controllers with the lowest free number, so wait for any pads
            // just unplugged to be gone first: otherwise Player 1 gets a later number than Player 2.
            waitForUnplug();
            ProcessBuilder pb = new ProcessBuilder("uinput", "-");
            pb.redirectErrorStream(true);
            // Marks the tool as PadBridge's, so a later engine can find it if this one dies.
            pb.environment().put(MARK, "1");
            process = pb.start();
            stdin = process.getOutputStream();
            listen(process);
        }
        while (pads.size() < players) {
            PlayerPad pad = new PlayerPad(pads.size() + 1, style);
            write(pad.registerJson());
            pads.add(pad);
            // Android registers each pad in turn, so their order (and player numbers) stays put.
            sleep(250);
        }
    }

    /**
     * Leaves only Player 1 to `players`. The `uinput` tool can't remove one pad, so they're all
     * unplugged and plugged in again, in order.
     */
    synchronized void shrink(int players) throws IOException {
        if (players >= pads.size()) return;
        stop();
        ensure(Math.max(1, players), style);
    }

    /** Reports a player's new state. */
    synchronized void send(int player, PadState state) {
        if (player < 1 || player > pads.size() || stdin == null) return;
        int[] ev = pads.get(player - 1).diff(state);
        if (ev.length == 0) return;
        StringBuilder sb = new StringBuilder(32 + ev.length * 4);
        sb.append("{\"id\":").append(player).append(",\"command\":\"inject\",\"events\":[");
        for (int v : ev) sb.append(v).append(',');
        sb.append(Evdev.EV_SYN).append(",0,0]}");
        try {
            write(sb.toString());
        } catch (IOException e) {
            Engine.log("uinput write failed: " + e);
        }
    }

    /** Makes sure the Thor's system buttons have a keyboard that looks like the Thor. */
    synchronized void systemKeys(InputScan.Device thor) throws IOException {
        if (stdin == null) return;
        String identity = thor.vendor + ":" + thor.product + ":" + thor.name;
        if (identity.equals(keysIdentity)) return;
        keysId = keysId == 0 ? 100 : keysId + 1;
        keysIdentity = identity;
        write(keysJson(keysId, thor));
        Engine.log("system buttons pass on as \"" + thor.name + "\"");
    }

    /** Presses or lets go of one of the Thor's system buttons, for Android. */
    synchronized void systemKey(int code, boolean down) {
        if (stdin == null || keysId == 0) return;
        try {
            write("{\"id\":" + keysId + ",\"command\":\"inject\",\"events\":[" + Evdev.EV_KEY + "," + code + ","
                    + (down ? 1 : 0) + "," + Evdev.EV_SYN + ",0,0]}");
        } catch (IOException e) {
            Engine.log("uinput write failed: " + e);
        }
    }

    static String keysJson(int id, InputScan.Device thor) {
        StringBuilder keys = new StringBuilder();
        for (int k : Evdev.SYSTEM_KEYS) keys.append(k).append(',');
        keys.setLength(keys.length() - 1);
        return String.format(java.util.Locale.US, "{\"id\":%d,\"command\":\"register\",\"name\":%s,\"vid\":%d,\"pid\":%d,"
                        + "\"bus\":\"usb\",\"configuration\":[{\"type\":%d,\"data\":[%d]},{\"type\":%d,\"data\":[%s]}]}",
                id, org.json.JSONObject.quote(thor.name), thor.vendor, thor.product,
                Evdev.UI_SET_EVBIT, Evdev.EV_KEY, Evdev.UI_SET_KEYBIT, keys);
    }

    synchronized void stop() {
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {
        }
        if (process != null) process.destroy();
        process = null;
        stdin = null;
        pads.clear();
        keysId = 0;
        keysIdentity = "";
    }

    /** Reads the tool's output: rumble for the engine, everything else to the log. */
    private void listen(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                     FileOutputStream out = new FileOutputStream(log, true)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Matcher id = ID.matcher(line), status = STATUS.matcher(line);
                        if (line.contains("\"vibrating\"") && id.find() && status.find()) {
                            listener.onRumble(Integer.parseInt(id.group(1)), Integer.parseInt(status.group(1)) > 0);
                        } else {
                            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // The tool stopped.
                }
            }
        }, "PadBridge uinput output");
        t.setDaemon(true);
        t.start();
    }

    /** Waits (up to 3 s) until Android's input list has no PadBridge pads. */
    private static void waitForUnplug() {
        for (int i = 0; i < 30; i++) {
            try {
                String devices = new String(java.nio.file.Files.readAllBytes(new File("/proc/bus/input/devices").toPath()),
                        StandardCharsets.UTF_8);
                if (!devices.contains("PadBridge ")) {
                    if (i > 0) sleep(300); // and for Android to catch up with the kernel
                    return;
                }
            } catch (IOException e) {
                return;
            }
            sleep(100);
        }
        Engine.log("old player pads were still plugged in after 3 s");
    }

    private boolean alive() {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    private void write(String json) throws IOException {
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
