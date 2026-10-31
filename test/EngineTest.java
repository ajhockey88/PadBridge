package com.padbridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks the engine's logic on a desktop JVM: finding controllers, reading them, deciding
 * players, and what the player pads report. Run it as the README's Building section says.
 */
public class EngineTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        parsesDevices();
        mapsAxesAndButtons();
        triggersLookTheSame();
        routesPlayers();
        controllerSettings();
        playerPadsReportChanges();
        thorSystemKeys();
        readsBatteries();
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("All engine tests passed");
    }

    // A built-in pad (not USB/Bluetooth), an Xbox controller over Bluetooth, a touchscreen,
    // a keyboard, and one of PadBridge's own pads.
    static final String PROC =
            "I: Bus=0019 Vendor=2020 Product=0001 Version=0001\n"
            + "N: Name=\"AYN Thor Gamepad\"\n"
            + "P: Phys=\nS: Sysfs=/devices/virtual/input/input3\nU: Uniq=\n"
            + "H: Handlers=event3 js0\nB: EV=b\n"
            + "B: KEY=7fff000000000000 0 0 0 0\n"
            + "B: ABS=3003f\n\n"
            + "I: Bus=0005 Vendor=045e Product=0b13 Version=0509\n"
            + "N: Name=\"Xbox Wireless Controller\"\n"
            + "P: Phys=aa:bb\nS: Sysfs=/devices/x\nU: Uniq=44:16:22:aa:bb:cc\n"
            + "H: Handlers=kbd event12 js1\nB: EV=20001b\n"
            + "B: KEY=7fdb000000000000 0 40000000 0 0\n"
            + "B: ABS=30627\n\n"
            + "I: Bus=0000 Vendor=0000 Product=0000 Version=0000\n"
            + "N: Name=\"fts_ts\"\nH: Handlers=event2\nB: KEY=400 0 0 0 0 0\nB: ABS=20000000000003\n\n"
            + "I: Bus=0000 Vendor=0000 Product=0000 Version=0000\n"
            + "N: Name=\"gpio-keys\"\nH: Handlers=event0\nB: KEY=1c000000000000 0\n\n"
            + "I: Bus=0003 Vendor=1209 Product=5043 Version=0001\n"
            + "N: Name=\"PadBridge Player 1\"\nH: Handlers=event20\nB: KEY=7fff000000000000 0 0 0 0\nB: ABS=30627\n";

    static final String GETEVENT_XBOX_BT =
            "add device 1: /dev/input/event12\n"
            + "  name:     \"Xbox Wireless Controller\"\n"
            + "  events:\n"
            + "    KEY (0001): 009e  0130  0131  0133  0134  0136  0137  013a  013b  013c  013d  013e\n"
            + "    ABS (0003): 0000  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0\n"
            + "                0001  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0\n"
            + "                0002  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0\n"
            + "                0005  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0\n"
            + "                0009  : value 0, min 0, max 1023, fuzz 3, flat 63, resolution 0\n"
            + "                000a  : value 0, min 0, max 1023, fuzz 3, flat 63, resolution 0\n"
            + "                0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0\n"
            + "                0011  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0\n";

    static final String GETEVENT_THOR =
            "add device 1: /dev/input/event3\n"
            + "    ABS (0003): 0000  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0\n"
            + "                0001  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0\n"
            + "                0002  : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0\n"
            + "                0003  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0\n"
            + "                0004  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0\n"
            + "                0005  : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0\n"
            + "                0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0\n"
            + "                0011  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0\n";

    static List<InputScan.Device> devices() {
        List<InputScan.Device> all = InputScan.parseProcDevices(PROC, 64);
        InputScan.applyRanges(all.get(0), GETEVENT_THOR);
        InputScan.applyRanges(all.get(1), GETEVENT_XBOX_BT);
        return all;
    }

    static void parsesDevices() {
        List<InputScan.Device> all = devices();
        check("five devices", all.size() == 5);
        InputScan.Device thor = all.get(0), xbox = all.get(1);
        check("thor path", thor.path.equals("/dev/input/event3"));
        check("thor is a gamepad", thor.isGamepad());
        check("thor not external", !thor.isExternal());
        check("xbox path", xbox.path.equals("/dev/input/event12"));
        check("xbox is a gamepad", xbox.isGamepad());
        check("xbox key uses its address", xbox.key().equals("045e:0b13:44:16:22:aa:bb:cc"));
        check("xbox has BTN_SOUTH", xbox.keys.get(Evdev.BTN_SOUTH));
        check("xbox has KEY_BACK", xbox.keys.get(Evdev.KEY_BACK));
        check("xbox stick range", Arrays.equals(xbox.abs.get(Evdev.ABS_X), new int[] {0, 65535}));
        check("xbox hat range", Arrays.equals(xbox.abs.get(Evdev.ABS_HAT0X), new int[] {-1, 1}));
        check("touchscreen isn't a gamepad", !all.get(2).isGamepad());
        check("keyboard isn't a gamepad", !all.get(3).isGamepad());
        check("own pad is skipped", !all.get(4).isGamepad());
    }

    static void mapsAxesAndButtons() {
        List<InputScan.Device> all = devices();
        PadMapper xbox = new PadMapper(all.get(1));
        xbox.handle(Evdev.EV_ABS, Evdev.ABS_X, 65535);
        xbox.handle(Evdev.EV_ABS, Evdev.ABS_Z, 0); // right stick X on Bluetooth Xbox
        xbox.handle(Evdev.EV_ABS, Evdev.ABS_GAS, 1023);
        xbox.handle(Evdev.EV_ABS, Evdev.ABS_HAT0Y, -1);
        xbox.handle(Evdev.EV_KEY, Evdev.BTN_SOUTH, 1);
        xbox.handle(Evdev.EV_KEY, Evdev.KEY_BACK, 1);
        check("frame ends on SYN_REPORT", xbox.handle(Evdev.EV_SYN, Evdev.SYN_REPORT, 0));
        PadState s = xbox.state;
        check("left stick right", near(s.axes[PadState.LX], 1f));
        check("right stick left (Z on Bluetooth)", near(s.axes[PadState.RX], -1f));
        check("right trigger from GAS", near(s.axes[PadState.RT], 1f));
        check("hat up is D-pad up", s.pressed(PadState.UP) && !s.pressed(PadState.DOWN));
        check("A pressed", s.pressed(PadState.A));
        check("Back is Select", s.pressed(PadState.SELECT));

        PadMapper thor = new PadMapper(all.get(0));
        thor.handle(Evdev.EV_ABS, Evdev.ABS_RX, 32767);
        thor.handle(Evdev.EV_ABS, Evdev.ABS_Z, 255);
        check("thor right stick on RX", near(thor.state.axes[PadState.RX], 1f));
        check("thor left trigger on Z", near(thor.state.axes[PadState.LT], 1f));

        thor.nintendo = true;
        thor.handle(Evdev.EV_KEY, Evdev.BTN_SOUTH, 1);
        check("nintendo layout swaps A and B", thor.state.pressed(PadState.B) && !thor.state.pressed(PadState.A));
    }

    static void triggersLookTheSame() {
        InputScan.Device digital = new InputScan.Device();
        digital.name = "Cheap pad";
        PadMapper m = new PadMapper(digital);
        m.handle(Evdev.EV_KEY, Evdev.BTN_TL2, 1);
        PadState out = new PadState();
        m.normalizedInto(out);
        check("digital trigger pushes its axis fully", near(out.axes[PadState.LT], 1f) && out.pressed(PadState.L2));

        PadMapper analog = new PadMapper(devices().get(1));
        analog.handle(Evdev.EV_ABS, Evdev.ABS_BRAKE, 800);
        analog.normalizedInto(out);
        check("analog trigger past halfway presses L2", out.pressed(PadState.L2));
        analog.handle(Evdev.EV_ABS, Evdev.ABS_BRAKE, 100);
        analog.normalizedInto(out);
        check("analog trigger light press doesn't", !out.pressed(PadState.L2));

        PadState a = new PadState(), b = new PadState();
        a.axes[PadState.LX] = 0.2f;
        b.axes[PadState.LX] = -0.9f;
        b.set(PadState.START, true);
        a.merge(b);
        check("shared player follows the stick pushed further", near(a.axes[PadState.LX], -0.9f));
        check("shared player has both controllers' buttons", a.pressed(PadState.START));
    }

    static void routesPlayers() throws Exception {
        List<InputScan.Device> all = devices();
        List<InputScan.Device> two = new ArrayList<>(Arrays.asList(all.get(1), all.get(0))); // Xbox connected first
        Routing.Config c = new Routing.Config();
        int[] p = Routing.assign(two, c);
        check("thor first: thor is P1", p[1] == 1);
        check("thor first: xbox is P2", p[0] == 2);

        c.mode = Routing.CONTROLLER_FIRST;
        p = Routing.assign(two, c);
        check("controller first: xbox is P1", p[0] == 1);
        check("controller first: thor is P2", p[1] == 2);

        c.mode = Routing.SHARED;
        p = Routing.assign(two, c);
        check("shared: both P1", p[0] == 1 && p[1] == 1);

        c.mode = Routing.THOR_FIRST;
        c.pins.put(all.get(1).key(), 1);
        p = Routing.assign(two, c);
        check("pinned xbox keeps P1", p[0] == 1);
        check("thor moves to P2", p[1] == 2);

        c.pins.put(all.get(1).key(), Routing.OFF);
        p = Routing.assign(two, c);
        check("left alone", p[0] == Routing.OFF && p[1] == 1);

        c.pins.clear();
        c.builtin = all.get(1).key(); // the user marked the Xbox pad as the Thor's controls
        p = Routing.assign(two, c);
        check("marked built-in wins", p[0] == 1 && p[1] == 2);

        Routing.Config back = Routing.Config.fromJson(c.toJson().toString());
        check("config survives JSON", back.builtin.equals(c.builtin) && back.mode.equals(c.mode));

        // Only Player 1's controller: the Thor alone is P1 in every mode.
        for (String mode : new String[] {Routing.THOR_FIRST, Routing.CONTROLLER_FIRST, Routing.SHARED}) {
            Routing.Config only = new Routing.Config();
            only.mode = mode;
            check("thor alone is P1 in " + mode, Routing.assign(Arrays.asList(all.get(0)), only)[0] == 1);
        }

        // A party: the Thor and eight Bluetooth controllers. Players 1 to 8, the ninth is left alone.
        List<InputScan.Device> party = new ArrayList<>();
        party.add(all.get(0));
        for (int i = 0; i < 8; i++) {
            InputScan.Device d = new InputScan.Device();
            d.name = "Pad " + i;
            d.path = "/dev/input/event" + (20 + i);
            d.bus = 5;
            d.vendor = 0x045e;
            d.product = 0x0b13;
            d.uniq = "aa:bb:cc:dd:ee:0" + i;
            party.add(d);
        }
        p = Routing.assign(party, new Routing.Config());
        boolean inOrder = true;
        for (int i = 0; i < 8; i++) inOrder &= p[i] == i + 1;
        check("party: thor and seven controllers are Players 1 to 8", inOrder);
        check("party: the ninth is left to Android", p[8] == Routing.OFF);
        Routing.Config swapped = new Routing.Config();
        swapped.pins.put(party.get(0).key(), 5);
        swapped.pins.put(party.get(4).key(), 1);
        p = Routing.assign(party, swapped);
        check("party: swapped Players 1 and 5", p[0] == 5 && p[4] == 1 && p[1] == 2 && p[5] == 6);
    }

    static void controllerSettings() throws Exception {
        Routing.Config c = new Routing.Config();
        Routing.PadSettings ps = new Routing.PadSettings();
        ps.rumble = 40;
        ps.deadzone = 10;
        ps.invertRy = true;
        c.pads.put("045e:0b13:aa", ps);
        Routing.Config back = Routing.Config.fromJson(c.toJson().toString());
        Routing.PadSettings got = back.pad("045e:0b13:aa");
        check("pad settings survive JSON", got.rumble == 40 && got.deadzone == 10 && got.invertRy);
        check("other pads get the defaults", back.pad("other").rumble == 70 && back.pad("other").deadzone == 0);

        PadMapper m = new PadMapper(devices().get(1));
        m.apply(false, ps);
        PadState out = new PadState();
        m.handle(Evdev.EV_ABS, Evdev.ABS_X, 32768 + 3000); // a drifting stick, about 9% right
        m.handle(Evdev.EV_ABS, Evdev.ABS_RZ, 0); // right stick pushed up (RZ on Bluetooth Xbox)
        m.normalizedInto(out);
        check("deadzone hides drift", out.axes[PadState.LX] == 0f);
        check("inverted right stick: up reads down", near(out.axes[PadState.RY], 1f));
        m.handle(Evdev.EV_ABS, Evdev.ABS_X, 65535);
        m.normalizedInto(out);
        check("deadzone keeps the edge full", near(out.axes[PadState.LX], 1f));
    }

    static void playerPadsReportChanges() throws Exception {
        PlayerPad pad = new PlayerPad(2, Routing.STANDARD);
        String json = pad.registerJson();
        new org.json.JSONObject(json); // must be valid JSON for the uinput tool
        check("named per player", json.contains("\"PadBridge Player 2\""));
        PadState s = new PadState();
        s.set(PadState.A, true);
        s.set(PadState.LEFT, true);
        s.axes[PadState.RX] = 1f;
        int[] ev = pad.diff(s);
        check("A pressed", has(ev, Evdev.EV_KEY, Evdev.BTN_SOUTH, 1));
        check("D-pad left as hat", has(ev, Evdev.EV_ABS, Evdev.ABS_HAT0X, -1));
        check("right stick on Z in standard style", has(ev, Evdev.EV_ABS, Evdev.ABS_Z, PlayerPad.STICK_MAX));
        check("nothing new, nothing sent", pad.diff(s).length == 0);
        s.set(PadState.A, false);
        ev = pad.diff(s);
        check("only the release is sent", ev.length == 3 && has(ev, Evdev.EV_KEY, Evdev.BTN_SOUTH, 0));

        PlayerPad xbox = new PlayerPad(1, Routing.XBOX);
        s.clear();
        s.axes[PadState.LT] = 1f;
        check("xbox style: left trigger on Z", has(xbox.diff(s), Evdev.EV_ABS, Evdev.ABS_Z, PlayerPad.TRIGGER_MAX));
        new org.json.JSONObject(xbox.registerJson());
    }

    static boolean has(int[] ev, int type, int code, int value) {
        for (int i = 0; i + 2 < ev.length; i += 3) {
            if (ev[i] == type && ev[i + 1] == code && ev[i + 2] == value) return true;
        }
        return false;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }

    static void thorSystemKeys() {
        check("Back is Android's", Evdev.isSystemKey(Evdev.KEY_BACK));
        check("Home is Android's", Evdev.isSystemKey(Evdev.KEY_HOMEPAGE));
        check("Select is a player's", !Evdev.isSystemKey(Evdev.BTN_SELECT));
    }

    static void readsBatteries() throws Exception {
        List<InputScan.Device> devs = InputScan.parseProcDevices(PROC, 64);
        check("sysfs parsed", "/devices/x".equals(devs.get(1).sysfs));
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("pb");
        java.nio.file.Path hid = root.resolve("0005:045E:0B13.0001");
        java.nio.file.Path input = java.nio.file.Files.createDirectories(hid.resolve("input/input5"));
        check("no battery", Battery.near(input.toFile()) == null);
        java.nio.file.Path supply = java.nio.file.Files.createDirectories(hid.resolve("power_supply/hid-44:16-battery"));
        java.nio.file.Files.write(supply.resolve("capacity"), "42\n".getBytes());
        java.nio.file.Files.write(supply.resolve("status"), "Discharging\n".getBytes());
        java.nio.file.Files.write(supply.resolve("scope"), "Device\n".getBytes());
        Battery b = Battery.near(input.toFile());
        check("battery 42%", b != null && b.percent == 42 && !b.charging);
        java.nio.file.Files.write(supply.resolve("status"), "Charging\n".getBytes());
        check("charging", Battery.near(input.toFile()).charging);
        java.nio.file.Files.write(supply.resolve("scope"), "System\n".getBytes());
        check("the system's battery isn't a controller's", Battery.near(input.toFile()) == null);
    }

    static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
