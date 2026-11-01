package com.padbridge;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Emulators the setup tour knows, and how to point each one at the PadBridge player pads. Each
 * emulator keeps its controller settings to itself, so PadBridge opens it on the other screen and
 * shows the steps next to it. Once set, they stay right: the pads keep their names and order.
 */
final class Emulators {
    private Emulators() {}

    static final class Emulator {
        final String name;
        final String[] packages;
        /** The steps, one per line. */
        final String steps;
        String installed;

        Emulator(String name, String steps, String... packages) {
            this.name = name;
            this.steps = steps;
            this.packages = packages;
        }
    }

    private static final String MANY = "\nDo the same for Player 2 to 4 with PadBridge Player 2 to 4.";

    static List<Emulator> all() {
        List<Emulator> l = new ArrayList<>();
        l.add(new Emulator("RetroArch",
                "Open Settings, then Input, then RetroPad Binds.\n"
                        + "Open Port 1 Controls and set Device Index to PadBridge Player 1."
                        + "\nDo the same for Ports 2 to 4 with PadBridge Player 2 to 4.\n"
                        + "Back in Main Menu, open Configuration File and tap Save Current Configuration.",
                "com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"));
        l.add(new Emulator("Dolphin",
                "Open Settings, then GameCube Input.\n"
                        + "Set Port 1 to Standard Controller, open it, choose PadBridge Player 1 as the device and tap Default."
                        + "\nDo the same for Ports 2 to 4 with PadBridge Player 2 to 4.\n"
                        + "Wii Input works the same way.",
                "org.dolphinemu.dolphinemu", "org.mm.jr"));
        l.add(new Emulator("PPSSPP",
                "Open Settings, then Controls, then Control mapping.\n"
                        + "Tap each button and press it once on the controller you play with.\n"
                        + "PPSSPP is one player, and the mapping works for every PadBridge pad.",
                "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"));
        l.add(new Emulator("AetherSX2 / NetherSX2",
                "Open Settings, then Controller Settings.\n"
                        + "Open Controller Port 1, tap Automatic Mapping and pick PadBridge Player 1."
                        + "\nDo the same for Port 2 with PadBridge Player 2.",
                "xyz.aethersx2.android", "xyz.aethersx2.custom"));
        l.add(new Emulator("DuckStation",
                "Open Settings, then Controllers.\n"
                        + "Open Port 1, tap Automatic Mapping and pick PadBridge Player 1."
                        + "\nDo the same for Port 2 with PadBridge Player 2.",
                "com.github.stenzek.duckstation"));
        l.add(new Emulator("Switch emulator",
                "Open Settings, then Input (or Controls).\n"
                        + "For Player 1, pick PadBridge Player 1 as the input device. If it asks, press each button once."
                        + MANY,
                "dev.eden.eden_emulator", "org.citron.citron_emu", "org.sudachi.sudachi_emu", "org.yuzu.yuzu_emu"));
        l.add(new Emulator("3DS emulator",
                "Open Settings, then Controls.\n"
                        + "Tap each button and press it once on the controller you play with.\n"
                        + "3DS games are one player.",
                "org.azahar_emu.azahar", "io.github.lime3ds.android", "org.citra.citra_emu", "org.citra.emu"));
        l.add(new Emulator("Lemuroid",
                "Lemuroid gives players out in the order pads appear, so PadBridge Player 1 is its Player 1.\n"
                        + "To check, open Settings, then Gamepad, and look at Player 1.",
                "com.swordfish.lemuroid"));
        l.add(new Emulator("Flycast",
                "Open Settings, then Controls.\n"
                        + "Set Port A to PadBridge Player 1." + "\nDo the same for Ports B to D with PadBridge Player 2 to 4.",
                "com.flycast.emulator"));
        l.add(new Emulator("melonDS",
                "Open Settings, then Input mapping.\n"
                        + "Tap each button and press it once on the controller you play with.",
                "me.magnum.melonds"));
        l.add(new Emulator("Mupen64Plus FZ",
                "Open Settings, then Controller profiles, and choose PadBridge Player 1 for Player 1." + MANY,
                "org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro"));
        return l;
    }

    /** The known emulators that are installed, each with the package that's there. */
    static List<Emulator> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        List<Emulator> out = new ArrayList<>();
        for (Emulator e : all()) {
            for (String p : e.packages) {
                if (pm.getLaunchIntentForPackage(p) != null) {
                    e.installed = p;
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    /** For any other emulator. */
    static final String OTHER = "Open its controller settings.\n"
            + "For Player 1, pick PadBridge Player 1. For Player 2, PadBridge Player 2, and so on.\n"
            + "If it asks you to press buttons, press each one once. You only do this once.";
}
