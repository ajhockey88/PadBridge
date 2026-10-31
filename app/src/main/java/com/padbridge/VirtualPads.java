package com.padbridge;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The player pads, made with Android's `uinput` shell tool: one tool process holds them all,
 * registered in player order so Player 1 always comes before Player 2. A pad exists while the
 * tool runs; stopping it unplugs them all.
 */
final class VirtualPads {
    private final File log;
    private Process process;
    private OutputStream stdin;
    private final List<PlayerPad> pads = new ArrayList<>();
    private String style = Routing.STANDARD;

    VirtualPads(File log) { this.log = log; }

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
            ProcessBuilder pb = new ProcessBuilder("uinput", "-");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            process = pb.start();
            stdin = process.getOutputStream();
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

    synchronized void stop() {
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {
        }
        if (process != null) process.destroy();
        process = null;
        stdin = null;
        pads.clear();
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
