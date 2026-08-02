package org.eversolo.winamp;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;

import org.eversolo.winamp.core.Logs;

/**
 * Hides the Eversolo's side bar while nobody is touching the screen.
 *
 * The display is 2160 px wide but the app window is only 2000: Android reserves the
 * right-hand 160 px, which is the strip the firmware draws its own controls in. Android only
 * reserves space like that for a system bar, so the strip is - almost certainly - registered
 * as the navigation bar, and an app is allowed to ask for that to be hidden.
 *
 * "Almost certainly" is not good enough to ship blind on a device with no debugger, so this
 * proves it at runtime instead of assuming it:
 *
 * <ol>
 *   <li>Remember how wide the window is before we touch anything. That is the 2000.</li>
 *   <li>Ask for the bar to be hidden - and deliberately <em>not</em> for a full-bleed layout,
 *       so that if the request is refused nothing ends up drawn underneath the bar.</li>
 *   <li>If the window then grows past its old width, the bar really did go, and only then do
 *       we take the full-bleed layout as well.</li>
 *   <li>If it has not grown after {@link #VERIFY_MS}, the firmware refused. Put every flag
 *       back and leave the player exactly as it was.</li>
 * </ol>
 *
 * The full-bleed layout is the reason the window is asked to stay 2160 px wide even while the
 * bar is visible: without it the whole Winamp layout would re-scale twice on every single
 * touch, which is both ugly and expensive. The bar floats over us for those few seconds
 * instead.
 *
 * Coming back is not a gesture: any touch anywhere brings the bar straight back, and the
 * FULLSCR button in MISC OPTS turns the whole thing off.
 */
// The SYSTEM_UI_FLAG_* family and getSystemWindowInset*() are deprecated in favour of
// WindowInsetsController, which arrived in API 30. This app targets 29 on purpose (see
// build.gradle), and on that target the old flags are the path the framework actually
// honours - so the deprecation is the right call here, not an oversight.
@SuppressWarnings("deprecation")
public final class FullScreen {

    private static final String TAG = "FullScreen";

    /** How long the screen must go untouched before the bar is hidden again. */
    private static final long IDLE_MS = 5_000;

    /** How long to wait for the window to grow before concluding the firmware said no. */
    private static final long VERIFY_MS = 2_500;

    /** Layout only: keeps our own measurements steady. Applied whenever we are enabled. */
    private static final int LAYOUT = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    /** Layout only: take the bar's space too. Applied once hiding is proven to work. */
    private static final int LAYOUT_FULL = LAYOUT | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    /** The one flag that actually hides anything. */
    private static final int HIDE = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;

    private final View host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideSoon = this::hideNow;
    private final Runnable giveUp = this::concludeRefused;

    /** The window's width with the bar in place - measured, never assumed. */
    private int barredWidth;

    private boolean enabled;
    private boolean proven;      // the bar demonstrably goes away on this firmware
    private boolean refused;     // it demonstrably does not, and we have stopped asking

    public FullScreen(View host) {
        this.host = host;
        host.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> onLaidOut(r - l));
        // The system puts the bar back by itself on some interactions. Whatever the reason it
        // came back, it goes away again five seconds later.
        host.setOnSystemUiVisibilityChangeListener(visibility -> {
            if (!enabled || refused) return;
            if ((visibility & HIDE) == 0) restartIdleTimer();
        });
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Turn the feature on or off. Safe to call before the window has been laid out: the first
     * layout is what tells us how wide the window is without us interfering, and nothing is
     * asked of the system until then.
     */
    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        ui.removeCallbacks(hideSoon);
        ui.removeCallbacks(giveUp);
        if (on) {
            refused = false;        // an explicit switch-on always gets a fresh try
            if (barredWidth > 0) begin();
        } else {
            host.setSystemUiVisibility(0);
            Logs.i(TAG, "full screen off; window returns to " + barredWidth + "px");
        }
    }

    /** Called for every touch that reaches the player. Brings the bar back immediately. */
    public void onUserTouch() {
        if (!enabled || refused) return;
        if (proven) host.setSystemUiVisibility(LAYOUT_FULL);
        restartIdleTimer();
    }

    public void destroy() {
        ui.removeCallbacks(hideSoon);
        ui.removeCallbacks(giveUp);
    }

    // ------------------------------------------------------------------ the proof

    private void begin() {
        // Note what is missing: LAYOUT_HIDE_NAVIGATION. If the firmware refuses, the window
        // stays 2000 px and the bar keeps its own space, rather than sitting on top of the
        // playlist's right-hand edge until someone notices.
        host.setSystemUiVisibility(LAYOUT | HIDE);
        ui.postDelayed(giveUp, VERIFY_MS);
        Logs.i(TAG, "asking for the side bar to be hidden; window is "
                + barredWidth + "px wide, insets " + insets());
    }

    private void onLaidOut(int width) {
        if (width <= 0) return;

        if (barredWidth == 0) {
            barredWidth = width;
            Logs.i(TAG, "window with the side bar in place: " + width + "px, insets " + insets());
            if (enabled) begin();
            return;
        }

        if (!enabled || proven || refused) return;
        if (width <= barredWidth) return;

        // It grew. The bar is gone and the space is ours, so now it is safe to ask to keep
        // the space even while the bar is briefly back.
        proven = true;
        ui.removeCallbacks(giveUp);
        host.setSystemUiVisibility(LAYOUT_FULL | HIDE);
        Logs.i(TAG, "side bar hidden: window " + barredWidth + " -> " + width
                + "px, insets " + insets());
    }

    private void concludeRefused() {
        if (proven || !enabled) return;
        refused = true;
        host.setSystemUiVisibility(0);
        Logs.w(TAG, "the firmware would not hide the side bar - window still "
                + barredWidth + "px after " + VERIFY_MS + "ms, insets " + insets()
                + "; leaving the player as it was");
    }

    // ------------------------------------------------------------------ hide / show

    private void restartIdleTimer() {
        ui.removeCallbacks(hideSoon);
        ui.postDelayed(hideSoon, IDLE_MS);
    }

    private void hideNow() {
        if (!enabled || refused) return;
        host.setSystemUiVisibility((proven ? LAYOUT_FULL : LAYOUT) | HIDE);
    }

    /**
     * What the system says it is reserving around us. Logged rather than acted on: if this
     * ever ships and does nothing, this line is the first thing to read off the console,
     * because it says whether the 160 px is a system bar at all.
     */
    private String insets() {
        WindowInsets w = host.getRootWindowInsets();
        if (w == null) return "unknown";
        return "l=" + w.getSystemWindowInsetLeft() + " t=" + w.getSystemWindowInsetTop()
                + " r=" + w.getSystemWindowInsetRight() + " b=" + w.getSystemWindowInsetBottom();
    }
}
