package com.padbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** User settings, and the controllers PadBridge has seen (for Settings > Controllers). */
final class Prefs {
    static final String KEY_ENABLED = "enabled";
    static final String KEY_MODE = "mode";
    static final String KEY_KEEP_PLAYERS = "keep_players";
    static final String KEY_STYLE = "style";
    static final String KEY_AUTO_START = "auto_start";
    static final String KEY_BOTH_SCREENS = "both_screens";
    static final String KEY_ANNOUNCE = "announce";
    static final String KEY_SWAP_SCREENS = "swap_screens";
    static final String KEY_TOUR_OFFERED = "tour_offered", KEY_TOUR_SKIPPED = "tour_skipped";
    private static final String KEY_PINS = "pins", KEY_NINTENDO = "nintendo", KEY_BUILTIN = "builtin",
            KEY_KNOWN = "known", KEY_PADS = "pads", KEY_PROFILES = "profiles", KEY_HOTKEYS = "hotkeys";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences("padbridge", Context.MODE_PRIVATE);
    }

    SharedPreferences raw() { return sp; }

    /** PadBridge is switched on: the engine should be running. */
    boolean enabled() { return sp.getBoolean(KEY_ENABLED, true); }
    void setEnabled(boolean on) { sp.edit().putBoolean(KEY_ENABLED, on).apply(); }

    String mode() { return sp.getString(KEY_MODE, Routing.THOR_FIRST); }
    void setMode(String mode) { sp.edit().putString(KEY_MODE, mode).apply(); }

    int keepPlayers() { return sp.getInt(KEY_KEEP_PLAYERS, 2); }
    String style() { return sp.getString(KEY_STYLE, Routing.STANDARD); }
    /** Start the engine whenever Shizuku starts, so it's back after a reboot. */
    boolean autoStart() { return sp.getBoolean(KEY_AUTO_START, true); }
    /** The controller test opens on the other screen. */
    boolean bothScreens() { return sp.getBoolean(KEY_BOTH_SCREENS, true); }
    /** PadBridge on the top screen, the test on the bottom. */
    boolean swapScreens() { return sp.getBoolean(KEY_SWAP_SCREENS, false); }

    /** The setup tour was offered (or run), so the main screen stops offering it. */
    boolean tourOffered() { return sp.getBoolean(KEY_TOUR_OFFERED, false); }
    void setTourOffered() { sp.edit().putBoolean(KEY_TOUR_OFFERED, true).apply(); }

    /** The tour's steps the user skipped, to finish later from Settings > About. */
    java.util.List<String> tourSkipped() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : sp.getString(KEY_TOUR_SKIPPED, "").split(","))
            // Steps the tour no longer has are left out.
            if (!id.isEmpty() && !id.equals("welcome") && !id.equals("controllers") && !id.equals("hotkeys")) out.add(id);
        return out;
    }

    void setTourSkipped(String step, boolean skipped) {
        java.util.List<String> all = tourSkipped();
        all.remove(step);
        if (skipped) all.add(step);
        sp.edit().putString(KEY_TOUR_SKIPPED, android.text.TextUtils.join(",", all)).apply();
    }

    void clearTourSkipped() { sp.edit().remove(KEY_TOUR_SKIPPED).apply(); }

    /** The player a controller is fixed to, Routing.AUTO, or Routing.OFF. */
    int pin(String key) { return object(KEY_PINS).optInt(key, Routing.AUTO); }

    void setPin(String key, int player) {
        JSONObject pins = object(KEY_PINS);
        try {
            if (player == Routing.AUTO) pins.remove(key);
            else pins.put(key, player);
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY_PINS, pins.toString()).apply();
    }

    boolean nintendo(String key) { return object(KEY_NINTENDO).optBoolean(key, false); }

    void setNintendo(String key, boolean on) {
        JSONObject n = object(KEY_NINTENDO);
        try {
            if (on) n.put(key, true);
            else n.remove(key);
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY_NINTENDO, n.toString()).apply();
    }

    /** A controller's rumble, deadzone and stick settings. */
    Routing.PadSettings padSettings(String key) { return Routing.PadSettings.fromJson(object(KEY_PADS).optJSONObject(key)); }

    void setPadSettings(String key, Routing.PadSettings s) {
        JSONObject all = object(KEY_PADS);
        try {
            if (s == null || s.isDefault()) all.remove(key);
            else all.put(key, s.toJson());
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY_PADS, all.toString()).apply();
    }

    /** Games' own settings, by package name, in the order they were added. */
    java.util.LinkedHashMap<String, Routing.Profile> profiles() {
        java.util.LinkedHashMap<String, Routing.Profile> out = new java.util.LinkedHashMap<>();
        JSONObject all = object(KEY_PROFILES);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, Routing.Profile.fromJson(all.optJSONObject(k)));
        }
        return out;
    }

    /** Saves a game's settings, or with null removes them. */
    void setProfile(String pkg, Routing.Profile p) {
        JSONObject all = object(KEY_PROFILES);
        try {
            if (p == null) all.remove(pkg);
            else all.put(pkg, p.toJson());
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY_PROFILES, all.toString()).apply();
    }

    /** The combo for a hotkey action, or "" when it's off. */
    String hotkey(String action) { return object(KEY_HOTKEYS).optString(action, ""); }

    /** Sets an action's combo ("" for off). A combo belongs to one action: others using it lose it. */
    void setHotkey(String action, String combo) {
        JSONObject all = object(KEY_HOTKEYS);
        try {
            for (String a : Hotkeys.ACTIONS) if (combo.equals(all.optString(a)) && !a.equals(action)) all.remove(a);
            if (combo.isEmpty()) all.remove(action);
            else all.put(action, combo);
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY_HOTKEYS, all.toString()).apply();
    }

    /** The controller marked as the Thor's own controls, or "" to detect them. */
    String builtin() { return sp.getString(KEY_BUILTIN, ""); }
    void setBuiltin(String key) { sp.edit().putString(KEY_BUILTIN, key).apply(); }

    /** Controllers seen so far: key to name and connection. */
    JSONObject known() { return object(KEY_KNOWN); }

    void remember(String key, String name, String connection) {
        JSONObject k = known();
        JSONObject entry = k.optJSONObject(key);
        if (entry != null && name.equals(entry.optString("name")) && connection.equals(entry.optString("connection"))) return;
        try {
            k.put(key, new JSONObject().put("name", name).put("connection", connection));
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY_KNOWN, k.toString()).apply();
    }

    void forget(String key) {
        JSONObject k = known();
        k.remove(key);
        sp.edit().putString(KEY_KNOWN, k.toString()).apply();
        setPin(key, Routing.AUTO);
        setNintendo(key, false);
        setPadSettings(key, null);
        if (key.equals(builtin())) setBuiltin("");
    }

    /** Everything the engine needs, as its config.json. */
    Routing.Config engineConfig() {
        Routing.Config c = new Routing.Config();
        c.enabled = enabled();
        c.mode = mode();
        c.keepPlayers = keepPlayers();
        c.style = style();
        JSONObject pins = object(KEY_PINS);
        for (Iterator<String> it = pins.keys(); it.hasNext(); ) {
            String k = it.next();
            c.pins.put(k, pins.optInt(k));
        }
        JSONObject n = object(KEY_NINTENDO);
        Set<String> keys = new HashSet<>();
        for (Iterator<String> it = n.keys(); it.hasNext(); ) keys.add(it.next());
        c.nintendo.addAll(keys);
        c.builtin = builtin();
        c.announce = sp.getBoolean(KEY_ANNOUNCE, true);
        JSONObject pads = object(KEY_PADS);
        for (Iterator<String> it = pads.keys(); it.hasNext(); ) {
            String k = it.next();
            c.pads.put(k, Routing.PadSettings.fromJson(pads.optJSONObject(k)));
        }
        c.profiles.putAll(profiles());
        JSONObject hk = object(KEY_HOTKEYS);
        for (Iterator<String> it = hk.keys(); it.hasNext(); ) {
            String k = it.next();
            if (Hotkeys.mask(hk.optString(k)) != 0) c.hotkeys.put(k, hk.optString(k));
        }
        return c;
    }

    private JSONObject object(String key) {
        try {
            return new JSONObject(sp.getString(key, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
