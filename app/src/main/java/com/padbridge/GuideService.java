package com.padbridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * The emulator guide's spotlight. While the setup tour guides you through an emulator's
 * controller settings, this dims the emulator's screen and lights up the one thing to tap next,
 * with a note saying what it is. It moves on by itself as you go, and the tour's "Tap it for me"
 * taps it for you. It only looks at the emulator being set up, and only while a guide runs.
 */
public class GuideService extends AccessibilityService {
    interface Listener {
        /** The step on show changed, or the guide ended (step -1). found: it's lit up on screen. */
        void onGuideStep(int step, boolean found);
    }

    private static GuideService instance;
    private static String pkg;
    private static Emulators.Spot[] spots;
    private static String doneNote = "";
    /** When the guide last moved on, so it waits a moment before looking ahead. */
    private static long movedAt;
    /** The last search found its spot by its words (not a corner button by position). */
    private boolean byLabel;
    /** For a corner button: the words in the title bar when it was lit up. */
    private String foundTitle;
    private int titleStep = -1;
    private final Rect foundRect = new Rect();
    /** The window the lit-up spot is in. */
    private int foundWindow = -1;
    private static int step = -1;
    private static boolean found;
    private static Listener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Spotlight view;
    private WindowManager wm;
    private int viewDisplay = -1;
    private final Rect target = new Rect();

    /** True once Android has started the service (it's on in Accessibility settings). */
    static boolean running() { return instance != null; }

    /** Starts guiding through an emulator's spots. */
    static void start(String emulatorPkg, Emulators.Spot[] guideSpots, String done, Listener l) {
        doneNote = done;
        pkg = emulatorPkg;
        spots = guideSpots;
        step = 0;
        found = false;
        movedAt = android.os.SystemClock.uptimeMillis();
        listener = l;
        if (instance != null) instance.refreshSoon(300);
    }

    static void stop() {
        pkg = null;
        spots = null;
        step = -1;
        listener = null;
        if (instance != null) instance.hide();
    }

    /** The step on show, or -1 once the guide is finished (or not running). */
    static int step() { return spots == null || step >= spots.length ? -1 : step; }

    /** Moves to the next spot (the guide's Next), or past the last one: the guide is done. */
    static void next() {
        if (spots == null) return;
        step = Math.min(step + 1, spots.length);
        movedAt = android.os.SystemClock.uptimeMillis();
        found = false;
        if (instance != null) instance.refreshSoon(0);
        tell();
    }

    /** Taps the lit-up spot for you. False if it isn't on screen. */
    static boolean tap() {
        if (instance == null || spots == null || step < 0 || step >= spots.length) return false;
        AccessibilityNodeInfo n = instance.find(spots[step]);
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n == null) return false;
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (ok) next();
        return ok;
    }

    private static void tell() {
        if (listener != null) listener.onGuideStep(spots == null || step >= spots.length ? -1 : step, found);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        if (pkg != null) refreshSoon(0);
    }

    @Override
    public void onDestroy() {
        hide();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (pkg == null || e.getPackageName() == null || !pkg.contentEquals(e.getPackageName())) return;
        // Tapping the lit-up spot yourself moves the guide on.
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && spots != null && step < spots.length && found
                && e.getSource() != null) {
            Rect r = new Rect();
            e.getSource().getBoundsInScreen(r);
            // The tap landed on the lit-up spot (or the row it's in).
            if (Rect.intersects(r, target) || r.contains(target.centerX(), target.centerY())) {
                next();
                return;
            }
        }
        refreshSoon(150);
    }

    private void refreshSoon(long ms) {
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, ms);
    }

    /** Finds the spot on screen (or a later one, when you got ahead) and moves the light there. */
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (pkg == null || spots == null) {
                hide();
                return;
            }
            if (step >= spots.length) {
                // Finished: the closing note on the emulator's screen, with nothing dimmed.
                target.setEmpty();
                show(emulatorDisplay(), doneNote, false);
                if (view != null) view.done();
                return;
            }
            AccessibilityNodeInfo n = null;
            int at = step;
            // The step on show first. Only if it's not on screen, and only after a moment (a list
            // or dialog that a tap opened takes a moment to appear), look one step ahead in case
            // you already went past it. A step is only skipped to when its words are on screen,
            // never for a corner button found by position.
            int from = step;
            // No wait when the step was lit up and has just gone (you tapped it and its page left):
            // the next step can follow straight away.
            boolean settled = found || android.os.SystemClock.uptimeMillis() - movedAt > 2500;
            if (spots[step].giveWay && step + 1 < spots.length && find(spots[step + 1]) != null && byLabel) {
                from = step + 1; // the menu this button opens is on screen now
            }
            for (int s = from; s <= Math.min(spots.length - 1, from + (settled ? 1 : 0)); s++) {
                AccessibilityNodeInfo m = find(spots[s]);
                if (m != null && (s == from || byLabel)) {
                    n = m;
                    at = s;
                    break;
                }
            }
            // A tap step that was lit up, and whose screen then went away (a pick list that closed as
            // you picked, a page you tapped your way out of): that tap was the step, so move on.
            // Some apps send no tap event for it.
            if (n == null && found && spots[step].tap && foundWindow != -1 && topWindowId() != foundWindow) {
                foundWindow = -1;
                next();
                return;
            }
            // A corner button (a back arrow, a cog): the page's title bar changed since it was lit
            // up, so it was tapped. Apps often send no tap event for these.
            // A tab or button that stays put while the page under it changes (ARMSX2's Controls tab):
            // same for any tap step whose spot is still in the same place but the page changed.
            Rect now = new Rect();
            if (n != null) n.getBoundsInScreen(now);
            boolean samePlace = n != null && at == step && now.equals(foundRect);
            if (found && spots[step].tap && (spots[step].corner != 0 || samePlace) && titleStep == step
                    && foundTitle != null && !foundTitle.equals(titleBar())) {
                foundTitle = null;
                next();
                return;
            }
            if (n == null && scrollTo(spots[step])) {
                handler.removeCallbacks(this);
                handler.postDelayed(this, 400);
                return;
            }
            if (n == null && notedStep != step) {
                notedStep = step;
                note(spots[step]);
            }
            boolean was = found;
            int before = step;
            if (at != step) movedAt = android.os.SystemClock.uptimeMillis();
            step = at;
            found = n != null;
            if (found) foundWindow = n.getWindowId();
            if (found && titleStep != step && spots[step].tap) {
                foundTitle = titleBar();
                titleStep = step;
                n.getBoundsInScreen(foundRect);
            }
            int display = n != null ? displayOf(n) : emulatorDisplay();
            if (n != null) n.getBoundsInScreen(target);
            else target.setEmpty();
            show(display, spots[step].hint, found);
            if (was != found || before != step) tell();
            handler.removeCallbacks(this);
            handler.postDelayed(this, 700); // menus scroll and animate without events sometimes
        }
    };

    /** The node in the emulator whose words match one of the spot's labels. */
    /** The last search's best match that's off screen (further down a list), if any. */
    private AccessibilityNodeInfo offscreen;
    private int offscreenScore, scrolls, scrollStep = -1, notedStep = -1;

    private AccessibilityNodeInfo find(Emulators.Spot spot) {
        offscreen = null;
        offscreenScore = 0;
        byLabel = false;
        // Only the emulator's top window: with a list or dialog open, what's behind it doesn't count.
        AccessibilityWindowInfo top = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
            if (top == null || w.getLayer() > top.getLayer()) top = w;
        }
        if (top != null) {
            AccessibilityWindowInfo w = top;
            AccessibilityNodeInfo root = w.getRoot();
            Rect win = new Rect();
            w.getBoundsInScreen(win);
            AccessibilityNodeInfo best = null, corner = null;
            int bestScore = 0;
            long cornerDist = Long.MAX_VALUE;
            Rect r = new Rect();
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            int seen = 0;
            while (!queue.isEmpty() && seen++ < 1500) {
                AccessibilityNodeInfo n = queue.poll();
                boolean visible = n.isVisibleToUser();
                n.getBoundsInScreen(r);
                boolean inCorner = spot.corner == 0 || inCorner(r, win, spot.corner);
                int score = inCorner ? score(n, spot.labels) : 0;
                // A step that's a tap only lights up something you can tap, never a heading with
                // the same words (ARMSX2's "Settings" page title is not its Settings button).
                if (spot.tap && !tappable(n)) score = 0;
                if (!visible) {
                    // Further down a list: remembered so the guide can scroll it into view.
                    if (score > offscreenScore) {
                        offscreenScore = score;
                        offscreen = n;
                    }
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo c = n.getChild(i);
                        if (c != null) queue.add(c);
                    }
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = n;
                }
                // An icon button with no words on it (a cog, a menu button): the tappable thing
                // nearest the spot's corner.
                // Not in a dialog: its corner isn't the screen's (a pick list still open after Auto-map).
                if (spot.corner != 0 && win.left < 8 && win.top < 200 && inCorner && n.isClickable() && r.width() < win.width() / 4
                        && r.height() < win.height() / 5) {
                    long dx = spot.corner == Emulators.Spot.TOP_LEFT ? r.left - win.left : win.right - r.right;
                    long dist = dx * dx + (long) (r.top - win.top) * (r.top - win.top);
                    if (dist < cornerDist) {
                        cornerDist = dist;
                        corner = n;
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) queue.add(c);
                }
            }
            byLabel = best != null;
            if (best != null) return best;
            if (corner != null) return corner;
        }
        return null;
    }

    /**
     * The step's spot is in the emulator but scrolled out of view: scrolls it into view, a few
     * tries per step. True if it scrolled.
     */
    private boolean scrollTo(Emulators.Spot spot) {
        if (scrollStep != step) {
            scrollStep = step;
            scrolls = 0;
        }
        if (scrolls >= 12) return false;
        find(spot);
        AccessibilityNodeInfo n = offscreen;
        if (n == null) {
            // Only for a setting further down a settings page; buttons are never hunted for, so a
            // game library isn't scrolled about while you open a menu.
            if (spot.tap) return false;
            // Not loaded at all (a long list only builds rows near the screen): scroll the page down
            // to look for it, then back up.
            AccessibilityNodeInfo list = biggestScrollable();
            if (list == null) return false;
            scrolls++;
            if (scrolls <= 6 && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
            if (scrolls <= 6) scrolls = 7; // reached the bottom: go back up
            return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        scrolls++;
        if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId())) return true;
        Rect r = new Rect(), p = new Rect();
        n.getBoundsInScreen(r);
        for (AccessibilityNodeInfo a = n.getParent(); a != null; a = a.getParent()) {
            if (!a.isScrollable()) continue;
            a.getBoundsInScreen(p);
            boolean up = r.bottom <= p.top;
            return a.performAction(up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        return false;
    }

    /** The words on the emulator's top window, to notice it went to another page. */
    private String titleBar() {
        AccessibilityWindowInfo top = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
            if (top == null || w.getLayer() > top.getLayer()) top = w;
        }
        if (top == null || top.getRoot() == null) return "";
        Rect win = new Rect(), r = new Rect();
        top.getBoundsInScreen(win);
        StringBuilder b = new StringBuilder().append(top.getId()).append('|');
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(top.getRoot());
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 600) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.isVisibleToUser() && (n.getText() != null || n.getContentDescription() != null)) {
                b.append(n.getText()).append('/').append(n.getContentDescription()).append('|');
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return b.toString();
    }

    /** The id of the emulator's top window, or -1. */
    private int topWindowId() {
        AccessibilityWindowInfo top = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
            if (top == null || w.getLayer() > top.getLayer()) top = w;
        }
        return top != null ? top.getId() : -1;
    }

    /** The emulator's main scrolling list on top: the scrollable node with the most room. */
    private AccessibilityNodeInfo biggestScrollable() {
        AccessibilityWindowInfo top = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
            if (top == null || w.getLayer() > top.getLayer()) top = w;
        }
        if (top == null || top.getRoot() == null) return null;
        AccessibilityNodeInfo best = null;
        long area = 0;
        Rect r = new Rect();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(top.getRoot());
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 1500) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.isScrollable() && n.isVisibleToUser()) {
                n.getBoundsInScreen(r);
                long a = (long) r.width() * r.height();
                if (a > area) {
                    area = a;
                    best = n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return best;
    }

    /** For diagnostics: what the guide sees right now (Settings' saved diagnostics include it). */
    static void dump() {
        if (instance != null && pkg != null && spots != null && step >= 0 && step < spots.length) {
            instance.note(spots[step]);
        }
    }

    /** Writes the words on the emulator's screen, for diagnostics when a step can't be found. */
    private void note(Emulators.Spot spot) {
        StringBuilder b = new StringBuilder("step " + (step + 1) + " (" + spot.hint + ") found " + found
                + " at " + target + " in " + pkg + "\ntitle " + foundTitle + "\nnow   " + titleBar() + "\n");
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())) continue;
            b.append("window layer ").append(w.getLayer()).append('\n');
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            int seen = 0;
            while (!queue.isEmpty() && seen++ < 800) {
                AccessibilityNodeInfo n = queue.poll();
                CharSequence t = n.getText(), c = n.getContentDescription();
                if (t != null || c != null) {
                    Rect nb = new Rect();
                    n.getBoundsInScreen(nb);
                    b.append(n.isVisibleToUser() ? "  " : "  (off screen) ").append(nb.toShortString()).append(' ')
                            .append(t != null ? t : "")
                            .append(c != null ? " [" + c + "]" : "").append(n.isClickable() ? " (tap)" : "")
                            .append(n.isScrollable() ? " (scrolls)" : "").append('\n');
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = n.getChild(i);
                    if (ch != null) queue.add(ch);
                }
            }
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                new java.io.File(EngineControl.folder(this), "guide.txt"))) {
            out.write(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** In the top fifth of the window, and its left or right quarter. */
    private static boolean inCorner(Rect r, Rect win, int corner) {
        if (r.isEmpty() || r.top > win.top + win.height() / 5) return false;
        return corner == Emulators.Spot.TOP_LEFT ? r.left < win.left + win.width() / 4
                : r.right > win.right - win.width() / 4;
    }

    /** 3 for the exact words, 2 for words it starts with, 0 for no match. Earlier labels win ties. */
    private static int score(AccessibilityNodeInfo n, String[] labels) {
        String t = words(n.getText()), c = words(n.getContentDescription());
        int s = 0;
        for (String l : labels) {
            if (l.equals(t) || l.equals(c)) {
                s = 6;
                break;
            }
        }
        if (s == 0) {
            for (String l : labels) {
                if (startsWord(t, l) || startsWord(c, l) || hasWords(t, l) || hasWords(c, l)) {
                    s = 4;
                    break;
                }
            }
        }
        // Something you can tap beats a heading with the same words (a "Settings" menu item over the
        // "Settings" page title).
        if (s > 0 && tappable(n)) s += 1;
        return s;
    }

    private static boolean tappable(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 4; i++, n = n.getParent()) if (n.isClickable()) return true;
        return false;
    }

    /** The label as whole words anywhere in it, e.g. "PadBridge Player 1" in "Android/0/PadBridge Player 1". */
    private static boolean hasWords(String s, String l) {
        for (int i = s.indexOf(l); i >= 0; i = s.indexOf(l, i + 1)) {
            boolean before = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
            int end = i + l.length();
            boolean after = end == s.length() || !Character.isLetterOrDigit(s.charAt(end));
            if (before && after) return true;
        }
        return false;
    }

    private static boolean startsWord(String s, String l) {
        return s.startsWith(l) && (s.length() == l.length() || !Character.isLetterOrDigit(s.charAt(l.length())));
    }

    private static String words(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
    }

    private int displayOf(AccessibilityNodeInfo n) {
        AccessibilityWindowInfo w = n.getWindow();
        return w != null ? w.getDisplayId() : emulatorDisplay();
    }

    /** The screen the emulator is on, for the note when nothing can be lit up. */
    private int emulatorDisplay() {
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null && pkg.contentEquals(root.getPackageName())) {
                return w.getDisplayId();
            }
        }
        return -1;
    }

    // ---- The overlay ---------------------------------------------------------------------------

    private void show(int display, String hint, boolean lit) {
        if (display < 0) {
            hide();
            return;
        }
        if (view == null || viewDisplay != display) {
            hide();
            Display d = ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplay(display);
            if (d == null) return;
            Context dc = createDisplayContext(d).createWindowContext(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            wm = dc.getSystemService(WindowManager.class);
            view = new Spotlight(dc);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            try {
                wm.addView(view, lp);
                viewDisplay = display;
            } catch (RuntimeException e) {
                view = null;
                return;
            }
        }
        view.set(lit ? target : null, hint, Math.min(step + 1, spots.length), spots.length);
    }

    private void hide() {
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
        }
        view = null;
        viewDisplay = -1;
    }

    /** Dims the screen except for a rounded hole around the spot, with a note next to it. */
    private static final class Spotlight extends View {
        private final float d;
        private final Paint dim = new Paint(), clear = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), card = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hole = new RectF();
        private boolean lit;
        private String hint = "";
        private String count = "";
        private final int[] at = new int[2];

        Spotlight(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setLayerType(LAYER_TYPE_HARDWARE, null);
            dim.setColor(Color.argb(0xb8, 0, 0, 0));
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * d);
            ring.setColor(Ui.ACCENT);
            card.setColor(Color.rgb(0x0b, 0x16, 0x20));
            text.setColor(Color.WHITE);
            text.setTextSize(17 * d);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            small.setColor(Ui.ACCENT_TEXT);
            small.setTextSize(12 * d);
        }

        void set(Rect screen, String hint, int n, int of) {
            lit = screen != null && !screen.isEmpty();
            if (lit) {
                getLocationOnScreen(at);
                float pad = 6 * d;
                hole.set(screen.left - at[0] - pad, screen.top - at[1] - pad, screen.right - at[0] + pad,
                        screen.bottom - at[1] + pad);
            }
            this.hint = hint;
            this.count = "PADBRIDGE GUIDE · " + n + " OF " + of + (lit ? "" : " · LOOK FOR IT ON SCREEN");
            invalidate();
        }

        void done() {
            lit = false;
            count = "PADBRIDGE GUIDE · DONE";
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = 12 * d;
            if (lit) {
                c.drawRect(0, 0, getWidth(), getHeight(), dim);
                c.drawRoundRect(hole, r, r, clear);
                c.drawRoundRect(hole, r, r, ring);
            }
            // The note: under the spot if there's room, else above it; at the top when not lit.
            int w = (int) Math.min(getWidth() - 32 * d, 420 * d);
            StaticLayout l = StaticLayout.Builder.obtain(hint, 0, hint.length(), text, w - (int) (28 * d))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            float h = l.getHeight() + 40 * d;
            float x = lit ? Math.max(16 * d, Math.min(hole.centerX() - w / 2f, getWidth() - w - 16 * d)) : (getWidth() - w) / 2f;
            float y = !lit ? 24 * d : hole.bottom + 12 * d + h < getHeight() ? hole.bottom + 12 * d : hole.top - 12 * d - h;
            RectF box = new RectF(x, y, x + w, y + h);
            c.drawRoundRect(box, r, r, card);
            c.drawRoundRect(box, r, r, ring);
            c.drawText(count, x + 14 * d, y + 20 * d, small);
            c.save();
            c.translate(x + 14 * d, y + 28 * d);
            l.draw(c);
            c.restore();
        }
    }

    /** True if the guide is switched on in Android's accessibility settings. */
    static boolean enabled(Context context) {
        String on = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(context.getPackageName() + "/" + GuideService.class.getName());
    }

    /**
     * Switches the guide on through Shizuku, keeping every other accessibility service (such as
     * the Thor's Wayfinder) as it is. Call off the main thread. False if Shizuku couldn't.
     */
    static boolean enable(Context context) {
        if (enabled(context)) return true;
        String me = context.getPackageName() + "/" + GuideService.class.getName();
        String on = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String list = on == null || on.isEmpty() ? me : on + ":" + me;
        return ShizukuClient.runShell("settings put secure enabled_accessibility_services '" + list.replace("'", "")
                + "' && settings put secure accessibility_enabled 1");
    }
}
