package com.padbridge;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * The Thor runs its top screen as Android's default display and the bottom screen as a
 * second built-in display. "Swap screens" in settings flips that if a firmware differs.
 */
final class Displays {
    private Displays() {}

    static Display secondary(Context context) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getState() != Display.STATE_OFF) {
                return d;
            }
        }
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    static Display primary(Context context) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    /** The screen with PadBridge's players and menu: the bottom one, so the game keeps the top. */
    static Display mainDisplay(Context context) {
        if (new Prefs(context).swapScreens()) return primary(context);
        Display s = secondary(context);
        return s != null ? s : primary(context);
    }

    /** The screen the controller test opens on: the other one, or the same one in one-screen mode. */
    static Display testDisplay(Context context) {
        Display main = mainDisplay(context);
        if (!new Prefs(context).bothScreens() || main == null) return main;
        if (main.getDisplayId() == Display.DEFAULT_DISPLAY) {
            Display s = secondary(context);
            return s != null ? s : main;
        }
        return primary(context);
    }
}
