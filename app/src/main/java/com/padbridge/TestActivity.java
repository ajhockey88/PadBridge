package com.padbridge;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controller test, on the top screen: each controller games can see, drawn live, so you can
 * check which controller is which player before starting a game. PadBridge's player pads come
 * first; anything else here is a controller PadBridge left to Android.
 */
public class TestActivity extends Activity {
    TestView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        view = new TestView(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView close = Ui.button(this, "✕  Close");
        close.setTextColor(Ui.RED);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clp.setMargins(0, (int) (14 * d), (int) (16 * d), 0);
        root.addView(close, clp);
        setContentView(root);
        // Player pads show from the start. Other controllers show once they send something:
        // those PadBridge took over still exist to Android, but stay silent.
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && dev.getName().startsWith(InputScan.OWN_PREFIX)) view.seen(dev);
        }
    }

    private static boolean isGamepad(InputDevice dev) {
        if (dev == null || dev.isVirtual()) return false;
        int s = dev.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        InputDevice dev = e.getDevice();
        if (isGamepad(dev) && (KeyEvent.isGamepadButton(e.getKeyCode()) || isDpad(e.getKeyCode()))) {
            view.key(dev, e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN);
            return true; // B and Home are buttons here, not Back and Home
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        InputDevice dev = e.getDevice();
        if (isGamepad(dev) && e.getAction() == MotionEvent.ACTION_MOVE) {
            view.motion(dev, e);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /** One controller's live state, as Android reports it. */
    static final class Live {
        String name;
        final Set<Integer> down = new HashSet<>();
        float lx, ly, rx, ry, lt, rt, hx, hy;
        long lastInput;
    }

    static final class TestView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        final Map<Integer, Live> pads = new LinkedHashMap<>();

        TestView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        void seen(InputDevice dev) {
            if (!isGamepad(dev) || pads.containsKey(dev.getId())) return;
            Live l = new Live();
            l.name = dev.getName();
            pads.put(dev.getId(), l);
            invalidate();
        }

        private Live live(InputDevice dev) {
            seen(dev);
            Live l = pads.get(dev.getId());
            l.lastInput = System.currentTimeMillis();
            return l;
        }

        void key(InputDevice dev, int code, boolean down) {
            Live l = live(dev);
            if (down) l.down.add(code);
            else l.down.remove(code);
            invalidate();
        }

        void motion(InputDevice dev, MotionEvent e) {
            Live l = live(dev);
            l.lx = e.getAxisValue(MotionEvent.AXIS_X);
            l.ly = e.getAxisValue(MotionEvent.AXIS_Y);
            l.rx = e.getAxisValue(MotionEvent.AXIS_Z);
            l.ry = e.getAxisValue(MotionEvent.AXIS_RZ);
            l.lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            l.rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            l.hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            l.hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            invalidate();
        }

        /** Player pads first, in player order; other controllers after. */
        List<Live> ordered() {
            List<Live> list = new ArrayList<>(pads.values());
            Collections.sort(list, new Comparator<Live>() {
                @Override public int compare(Live a, Live b) {
                    boolean pa = a.name.startsWith(InputScan.OWN_PREFIX), pb = b.name.startsWith(InputScan.OWN_PREFIX);
                    if (pa != pb) return pa ? -1 : 1;
                    return a.name.compareTo(b.name);
                }
            });
            return list;
        }

        @Override
        protected void onDraw(Canvas c) {
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(24 * d);
            paint.setColor(Color.WHITE);
            c.drawText("Controller test", 24 * d, 46 * d, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(13 * d);
            paint.setColor(Ui.SUB);
            c.drawText("Press buttons and move the sticks: each player lights up here, just as games see it.", 24 * d, 70 * d, paint);

            List<Live> list = ordered();
            if (list.isEmpty()) {
                paint.setTextSize(16 * d);
                c.drawText("No controllers yet. Turn PadBridge on, or connect a controller.", 24 * d, 120 * d, paint);
                return;
            }
            int cols = list.size() <= 2 ? list.size() : list.size() <= 4 ? 2 : list.size() <= 6 ? 3 : 4;
            int rows = (list.size() + cols - 1) / cols;
            float top = 90 * d, gap = 12 * d, side = 20 * d;
            float w = (getWidth() - 2 * side - (cols - 1) * gap) / cols;
            float h = Math.min((getHeight() - top - 16 * d - (rows - 1) * gap) / rows, w * 0.62f);
            for (int i = 0; i < list.size(); i++) {
                float x = side + (i % cols) * (w + gap), y = top + (i / cols) * (h + gap);
                drawPad(c, list.get(i), new RectF(x, y, x + w, y + h));
            }
        }

        private void drawPad(Canvas c, Live l, RectF r) {
            boolean player = l.name.startsWith(InputScan.OWN_PREFIX);
            int number = 0;
            if (player) {
                try {
                    number = Integer.parseInt(l.name.substring(l.name.lastIndexOf(' ') + 1));
                } catch (NumberFormatException ignored) {
                }
            }
            int color = player ? Ui.player(number) : Ui.SUB;
            boolean active = System.currentTimeMillis() - l.lastInput < 600;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.CARD);
            c.drawRoundRect(r, 16 * d, 16 * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((active ? 2.5f : 1.2f) * d);
            paint.setColor(active ? color : Ui.OUTLINE);
            c.drawRoundRect(r, 16 * d, 16 * d, paint);
            paint.setStyle(Paint.Style.FILL);

            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(18 * d);
            paint.setColor(player ? Color.WHITE : Ui.TEXT);
            String title = player ? "Player " + number : l.name;
            c.drawText(title, r.left + 16 * d, r.top + 30 * d, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(12 * d);
            paint.setColor(player ? color : Ui.RED);
            c.drawText(player ? l.name : "Not bridged: games see this controller directly", r.left + 16 * d, r.top + 48 * d, paint);

            float s = Math.min(r.width() / 300f, (r.height() - 56 * d) / 150f);
            float cx = r.centerX(), cy = r.top + 56 * d + 75 * s;
            // Shoulders and triggers.
            bar(c, cx - 120 * s, cy - 72 * s, 70 * s, 12 * s, l.lt, l.down.contains(KeyEvent.KEYCODE_BUTTON_L2), color);
            bar(c, cx + 50 * s, cy - 72 * s, 70 * s, 12 * s, l.rt, l.down.contains(KeyEvent.KEYCODE_BUTTON_R2), color);
            pill(c, cx - 120 * s, cy - 54 * s, 70 * s, 12 * s, l.down.contains(KeyEvent.KEYCODE_BUTTON_L1), color);
            pill(c, cx + 50 * s, cy - 54 * s, 70 * s, 12 * s, l.down.contains(KeyEvent.KEYCODE_BUTTON_R1), color);
            // Sticks.
            stick(c, cx - 95 * s, cy - 8 * s, 26 * s, l.lx, l.ly, l.down.contains(KeyEvent.KEYCODE_BUTTON_THUMBL), color);
            stick(c, cx + 50 * s, cy + 40 * s, 26 * s, l.rx, l.ry, l.down.contains(KeyEvent.KEYCODE_BUTTON_THUMBR), color);
            // D-pad, from the hat or from D-pad keys.
            float dx = cx - 50 * s, dy = cy + 40 * s, k = 13 * s;
            dot(c, dx, dy - k * 1.3f, k * 0.62f, l.hy < -0.5f || l.down.contains(KeyEvent.KEYCODE_DPAD_UP), color, null);
            dot(c, dx, dy + k * 1.3f, k * 0.62f, l.hy > 0.5f || l.down.contains(KeyEvent.KEYCODE_DPAD_DOWN), color, null);
            dot(c, dx - k * 1.3f, dy, k * 0.62f, l.hx < -0.5f || l.down.contains(KeyEvent.KEYCODE_DPAD_LEFT), color, null);
            dot(c, dx + k * 1.3f, dy, k * 0.62f, l.hx > 0.5f || l.down.contains(KeyEvent.KEYCODE_DPAD_RIGHT), color, null);
            // Face buttons.
            float fx = cx + 95 * s, fy = cy - 8 * s;
            dot(c, fx, fy + k * 1.4f, k * 0.8f, l.down.contains(KeyEvent.KEYCODE_BUTTON_A), color, "A");
            dot(c, fx + k * 1.4f, fy, k * 0.8f, l.down.contains(KeyEvent.KEYCODE_BUTTON_B), color, "B");
            dot(c, fx - k * 1.4f, fy, k * 0.8f, l.down.contains(KeyEvent.KEYCODE_BUTTON_X), color, "X");
            dot(c, fx, fy - k * 1.4f, k * 0.8f, l.down.contains(KeyEvent.KEYCODE_BUTTON_Y), color, "Y");
            // Select, Home, Start.
            pill(c, cx - 36 * s, cy - 14 * s, 20 * s, 9 * s, l.down.contains(KeyEvent.KEYCODE_BUTTON_SELECT)
                    || l.down.contains(KeyEvent.KEYCODE_BACK), color);
            dot(c, cx, cy - 10 * s, 7 * s, l.down.contains(KeyEvent.KEYCODE_BUTTON_MODE), color, null);
            pill(c, cx + 16 * s, cy - 14 * s, 20 * s, 9 * s, l.down.contains(KeyEvent.KEYCODE_BUTTON_START), color);
        }

        private void dot(Canvas c, float x, float y, float r, boolean on, int color, String label) {
            paint.setColor(on ? color : Color.rgb(0x22, 0x26, 0x2f));
            c.drawCircle(x, y, r, paint);
            if (label != null) {
                paint.setColor(on ? Color.BLACK : Ui.SUB);
                paint.setTextSize(r * 1.05f);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextAlign(Paint.Align.CENTER);
                c.drawText(label, x, y + r * 0.38f, paint);
                paint.setTextAlign(Paint.Align.LEFT);
                paint.setTypeface(Typeface.DEFAULT);
            }
        }

        private void pill(Canvas c, float x, float y, float w, float h, boolean on, int color) {
            paint.setColor(on ? color : Color.rgb(0x22, 0x26, 0x2f));
            c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, paint);
        }

        private void bar(Canvas c, float x, float y, float w, float h, float value, boolean pressed, int color) {
            paint.setColor(Color.rgb(0x22, 0x26, 0x2f));
            c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, paint);
            float v = Math.max(value, pressed ? 1f : 0f);
            if (v > 0.02f) {
                paint.setColor(color);
                c.drawRoundRect(x, y, x + Math.max(h, w * v), y + h, h / 2, h / 2, paint);
            }
        }

        private void stick(Canvas c, float x, float y, float r, float sx, float sy, boolean pressed, int color) {
            paint.setColor(Color.rgb(0x1a, 0x1d, 0x24));
            c.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(pressed ? color : Color.rgb(0x2e, 0x32, 0x3b));
            c.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.FILL);
            boolean moved = Math.abs(sx) > 0.1f || Math.abs(sy) > 0.1f;
            paint.setColor(moved || pressed ? color : Ui.SUB);
            c.drawCircle(x + sx * r * 0.62f, y + sy * r * 0.62f, r * 0.38f, paint);
        }
    }
}
