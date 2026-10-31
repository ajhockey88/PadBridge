package com.padbridge;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;

/**
 * Invisible trampoline: opens PadBridge on the bottom screen, so the game on the top screen
 * stays where it is, then disappears. The controller test opens on the top screen from there.
 */
public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Display target = Displays.mainDisplay(this);
        try {
            if (target != null) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(target.getDisplayId());
                startActivity(intent, options.toBundle());
            } else {
                startActivity(intent);
            }
        } catch (RuntimeException e) {
            // The system refused to place us on that screen; open wherever we are instead.
            try {
                startActivity(intent);
            } catch (RuntimeException ignored) {
            }
        }
        finish();
    }
}
