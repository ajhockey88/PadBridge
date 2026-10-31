package com.padbridge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Decides which player each controller is. The same controllers always get the same players,
 * whatever order they connected in, so emulators mapped once to "PadBridge Player 1", "PadBridge
 * Player 2" and so on never need remapping.
 */
final class Routing {
    static final int MAX_PLAYERS = 8;
    /** A controller PadBridge leaves alone: Android and games see it as usual. */
    static final int OFF = -1;
    static final int AUTO = 0;

    static final String THOR_FIRST = "thor_first", CONTROLLER_FIRST = "controller_first", SHARED = "shared";
    static final String STANDARD = "standard", XBOX = "xbox";

    /** The settings the app hands the engine (config.json). */
    static final class Config {
        boolean enabled = true;
        String mode = THOR_FIRST;
        /** Player pads that exist even with no controller on them, so games always see them. */
        int keepPlayers = 2;
        String style = STANDARD;
        /** Controllers fixed to a player, or OFF. */
        final Map<String, Integer> pins = new HashMap<>();
        final Set<String> nintendo = new HashSet<>();
        /** The controller the user marked as the Thor's own controls; empty to detect it. */
        String builtin = "";

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("mode", mode);
            o.put("keepPlayers", keepPlayers);
            o.put("style", style);
            JSONObject p = new JSONObject();
            for (Map.Entry<String, Integer> e : pins.entrySet()) p.put(e.getKey(), e.getValue());
            o.put("pins", p);
            JSONArray n = new JSONArray();
            for (String k : nintendo) n.put(k);
            o.put("nintendo", n);
            o.put("builtin", builtin);
            return o;
        }

        static Config fromJson(String json) {
            Config c = new Config();
            try {
                JSONObject o = new JSONObject(json);
                c.enabled = o.optBoolean("enabled", true);
                c.mode = o.optString("mode", THOR_FIRST);
                c.keepPlayers = Math.max(1, Math.min(MAX_PLAYERS, o.optInt("keepPlayers", 2)));
                c.style = o.optString("style", STANDARD);
                JSONObject p = o.optJSONObject("pins");
                if (p != null) {
                    JSONArray names = p.names();
                    for (int i = 0; names != null && i < names.length(); i++) {
                        String k = names.getString(i);
                        c.pins.put(k, p.getInt(k));
                    }
                }
                JSONArray n = o.optJSONArray("nintendo");
                for (int i = 0; n != null && i < n.length(); i++) c.nintendo.add(n.getString(i));
                c.builtin = o.optString("builtin", "");
            } catch (JSONException ignored) {
            }
            return c;
        }
    }

    private Routing() {}

    /**
     * Which controller is the Thor's own controls: the one the user marked, else the first
     * that isn't USB or Bluetooth (or says AYN, Thor or Odin in its name). -1 if none.
     */
    static int builtinIndex(List<InputScan.Device> devices, Config c) {
        if (!c.builtin.isEmpty()) {
            for (int i = 0; i < devices.size(); i++) if (c.builtin.equals(devices.get(i).key())) return i;
        }
        for (int i = 0; i < devices.size(); i++) {
            InputScan.Device d = devices.get(i);
            String n = d.name.toLowerCase(Locale.US);
            if (!d.isExternal() || n.contains("ayn") || n.contains("thor") || n.contains("odin")) return i;
        }
        return -1;
    }

    /**
     * The player (1 to 8) for each controller, in the order they connected, or OFF for
     * controllers left to Android (switched off, or more controllers than players).
     */
    static int[] assign(List<InputScan.Device> devices, Config c) {
        int n = devices.size();
        int[] out = new int[n];
        int[] users = new int[MAX_PLAYERS + 1];
        // Controllers fixed to a player go first.
        for (int i = 0; i < n; i++) {
            Integer pin = c.pins.get(devices.get(i).key());
            if (pin != null && pin != AUTO) {
                out[i] = pin == OFF ? OFF : Math.max(1, Math.min(MAX_PLAYERS, pin));
                if (out[i] > 0) users[out[i]]++;
            }
        }
        int builtin = builtinIndex(devices, c);
        if (builtin >= 0 && out[builtin] != AUTO) builtin = -1; // fixed to a player already
        if (SHARED.equals(c.mode)) {
            // The Thor and the first other controller both drive Player 1.
            if (builtin >= 0) place(out, users, builtin, 1, true);
            for (int i = 0; i < n; i++) {
                if (out[i] != AUTO) continue;
                if (users[1] == 0 || (builtin >= 0 && users[1] == 1 && out[builtin] == 1)) {
                    place(out, users, i, 1, true);
                } else {
                    place(out, users, i, 1, false);
                }
            }
        } else if (CONTROLLER_FIRST.equals(c.mode)) {
            for (int i = 0; i < n; i++) if (out[i] == AUTO && i != builtin) place(out, users, i, 1, false);
            if (builtin >= 0) place(out, users, builtin, 1, false);
        } else {
            if (builtin >= 0) place(out, users, builtin, 1, false);
            for (int i = 0; i < n; i++) if (out[i] == AUTO) place(out, users, i, 1, false);
        }
        return out;
    }

    /** Gives controller i the first free player from `from` (or exactly `from` when sharing). */
    private static void place(int[] out, int[] users, int i, int from, boolean share) {
        if (share) {
            out[i] = from;
            users[from]++;
            return;
        }
        for (int p = from; p <= MAX_PLAYERS; p++) {
            if (users[p] == 0) {
                out[i] = p;
                users[p]++;
                return;
            }
        }
        out[i] = OFF; // every player is taken
    }
}
