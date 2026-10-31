package com.padbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Hotkeys the engine can't do by itself, because they change PadBridge's settings: swapping
 * Players 1 and 2. Only the shell user (PadBridge's engine) can send it: see the manifest.
 */
public class HotkeyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Hotkeys.ACTION_SWAP.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        String said = swapOneAndTwo(app, EngineControl.status(app));
        EngineControl.pushConfig(app);
        Toast.makeText(app, "🔁  " + said, Toast.LENGTH_SHORT).show();
    }

    /**
     * Swaps Players 1 and 2: flips Thor first and Controller first, and any fixed players.
     * Returns what happened, to show.
     */
    static String swapOneAndTwo(Context c, EngineControl.Status status) {
        Prefs prefs = new Prefs(c);
        String m = prefs.mode();
        prefs.setMode(Routing.THOR_FIRST.equals(m) ? Routing.CONTROLLER_FIRST : Routing.THOR_FIRST);
        for (EngineControl.Controller ctl : status.controllers) {
            int pin = prefs.pin(ctl.key);
            if (pin == 1) prefs.setPin(ctl.key, 2);
            else if (pin == 2) prefs.setPin(ctl.key, 1);
        }
        return "Player 1 is now " + (Routing.THOR_FIRST.equals(prefs.mode()) ? "the Thor's controls" : "your controller");
    }
}
