package com.padbridge;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** PadBridge's colors and the small pieces every screen is built from, as in ThorPad. */
final class Ui {
    private Ui() {}

    static final int BG = Color.BLACK, SIDE = Color.rgb(0x0c, 0x0d, 0x11), CARD = Color.rgb(0x11, 0x13, 0x18),
            OUTLINE = Color.rgb(0x22, 0x26, 0x2f), SELECTED = Color.rgb(0x16, 0x28, 0x3a),
            ACCENT = Color.rgb(0x78, 0xc8, 0xff), ACCENT_TEXT = Color.rgb(0xbf, 0xe6, 0xff),
            TEXT = Color.rgb(0xd8, 0xdc, 0xe3), SUB = Color.rgb(0x8a, 0x90, 0x9b), DIM = Color.rgb(0x5d, 0x63, 0x6e),
            RED = Color.rgb(0xff, 0xb3, 0xba), GREEN = Color.rgb(0x9b, 0xe0, 0xa8), CHIP = Color.rgb(0x16, 0x22, 0x2d),
            BUTTON = Color.rgb(0x1c, 0x1e, 0x24);

    /** Each player's color: blue, pink, green, yellow. */
    static final int[] PLAYER = {ACCENT, Color.rgb(0xff, 0x9e, 0xb0), Color.rgb(0x8f, 0xe3, 0x9c), Color.rgb(0xff, 0xd8, 0x75)};

    static int player(int p) { return PLAYER[(Math.max(1, p) - 1) % PLAYER.length]; }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusPx);
        return bg;
    }

    static TextView plain(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** A small rounded value chip, like the one at the end of a slider row. */
    static TextView chip(Context c, String s) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = plain(c, s, 14, Color.rgb(0x9f, 0xd2, 0xf5));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth((int) (42 * d));
        t.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        t.setBackground(round(CHIP, 9 * d));
        return t;
    }

    /** A rounded button-like chip ("← Back", "Allow"...). */
    static TextView button(Context c, String s) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = plain(c, s, 15, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (14 * d), (int) (7 * d), (int) (16 * d), (int) (7 * d));
        t.setBackground(round(BUTTON, 18 * d));
        t.setFocusable(true);
        return t;
    }
}
