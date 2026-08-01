package org.eversolo.winamp.skin;

/**
 * Where everything sits inside the Winamp playlist editor window.
 *
 * Winamp's playlist is the one window that resizes, and it does so in whole segments: the
 * borders are repeating tiles 25 px wide and 29 px tall, so a legal size is
 * 275 + 25n wide and 58 + 29n tall. Winamp's own default is 275 x 232, which is 58 + 29*6.
 *
 * All numbers here are SKIN pixels. The view scales them by a whole number when it draws.
 *
 * This class has no Android dependencies on purpose: the arithmetic - how many rows fit,
 * where a tap landed, how far the scrollbar has moved - is exactly the kind of thing that
 * is painful to debug on a device with no logcat, so it is tested on a desktop JVM by
 * tools/jvm-tests/PlaylistGeometryTest.java.
 *
 * The layout follows webamp's playlist-window.css (MIT), which is a faithful copy of the
 * original.
 */
public final class PlaylistGeometry {

    /** Winamp's default playlist window. */
    public static final int BASE_W = 275;
    public static final int BASE_H = 232;

    /** Resize granularity: the border tiles are this big and repeat. */
    public static final int SEG_W = 25;
    public static final int SEG_H = 29;

    /** Window chrome. */
    public static final int TOP_H = 20;
    public static final int BOTTOM_H = 38;
    public static final int LEFT_W = 12;
    public static final int RIGHT_W = 20;
    public static final int CHROME_H = TOP_H + BOTTOM_H;      // 58

    /** One track row, and the 3 px breathing space above and below the list. */
    public static final int TRACK_H = 13;
    public static final int PAD = 3;

    /** Fixed-size pieces of the bottom bar. */
    public static final int BOTTOM_LEFT_W = 125;
    public static final int BOTTOM_RIGHT_W = 150;
    public static final int MENU_BTN_W = 22;
    public static final int MENU_BTN_H = 18;
    public static final int TITLE_W = 100;
    public static final int CORNER_W = 25;

    /** Scrollbar. */
    public static final int SCROLL_W = 8;
    public static final int SCROLL_HANDLE_H = 18;

    /** The smallest window we will build: 58 + 29*2, four visible rows. */
    public static final int MIN_SEGMENTS = 2;

    public final int width;
    public final int height;

    public PlaylistGeometry(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** The window Winamp itself opens with. */
    public static PlaylistGeometry base() {
        return new PlaylistGeometry(BASE_W, BASE_H);
    }

    // ---------------------------------------------------------------- sizing

    /** The widest legal window that fits in {@code availablePx} at this scale. */
    public static int widthFor(int availablePx, int scale) {
        int skin = availablePx / Math.max(1, scale);
        if (skin < BASE_W) return BASE_W;
        return BASE_W + ((skin - BASE_W) / SEG_W) * SEG_W;
    }

    /**
     * The tallest legal window that fits, never shorter than MIN_SEGMENTS segments.
     *
     * Winamp's own minimum is six segments (232 px). On a 6-inch screen that is a luxury we
     * do not always have, so this will go smaller rather than overflow the display - a
     * window drawn off the bottom edge would be worse than a short one.
     */
    public static int heightFor(int availablePx, int scale) {
        int skin = availablePx / Math.max(1, scale);
        int segments = (skin - CHROME_H) / SEG_H;
        if (segments < MIN_SEGMENTS) segments = MIN_SEGMENTS;
        return CHROME_H + segments * SEG_H;
    }

    /** Rows visible in a window of this height. */
    public static int rowsIn(int height) {
        return Math.max(1, (height - CHROME_H - 2 * PAD) / TRACK_H);
    }

    // ---------------------------------------------------------------- areas

    public int trackX() { return LEFT_W; }
    public int trackY() { return TOP_H + PAD; }
    public int trackW() { return width - LEFT_W - RIGHT_W; }
    public int trackH() { return height - CHROME_H - 2 * PAD; }

    public int visibleRows() { return rowsIn(height); }

    /** Top of the bottom bar. */
    public int bottomY() { return height - BOTTOM_H; }

    /** Left edge of the 150 px bottom-right corner piece. */
    public int bottomRightX() { return width - BOTTOM_RIGHT_W; }

    /** The title bar sprite is 100 px wide and sits in the middle of the top strip. */
    public int titleX() { return (width - TITLE_W) / 2; }

    // ---------------------------------------------------------------- scrolling

    // The arithmetic itself lives in ListMath, shared with the browser window.

    /** How far down the list can be scrolled, in rows. */
    public int maxOffset(int trackCount) {
        return ListMath.maxOffset(trackCount, visibleRows());
    }

    public int clampOffset(int offset, int trackCount) {
        return ListMath.clampOffset(offset, trackCount, visibleRows());
    }

    /**
     * Scroll so that {@code index} is on screen, moving as little as possible - the way a
     * list behaves when the track changes underneath you.
     */
    public int offsetToReveal(int index, int offset, int trackCount) {
        return ListMath.reveal(index, offset, trackCount, visibleRows());
    }

    /** Row index for a tap at {@code y} in window coordinates, or -1 if it missed. */
    public int rowAt(float y, int offset, int trackCount) {
        return ListMath.rowAt(y, trackY(), trackH(), TRACK_H, offset, trackCount);
    }

    // ---- the scrollbar down the right-hand border ----

    public int scrollX() { return width - RIGHT_W + 5; }
    public int scrollTop() { return TOP_H; }
    public int scrollTravel() { return height - CHROME_H - SCROLL_HANDLE_H; }

    public int scrollHandleY(int offset, int trackCount) {
        return ListMath.handleY(scrollTop(), scrollTravel(), offset, maxOffset(trackCount));
    }

    /** Turn a drag on the scrollbar back into a scroll offset. */
    public int offsetForHandleY(float y, int trackCount) {
        return ListMath.offsetForHandleY(y, scrollTop(), scrollTravel(), SCROLL_HANDLE_H,
                trackCount, visibleRows());
    }

    // ---------------------------------------------------------------- widgets

    /** A rectangle in window coordinates. */
    public static final class Box {
        public final int x, y, w, h;
        public Box(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        public boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    public Box closeButton() { return new Box(width - 11, 3, 9, 9); }
    public Box shadeButton() { return new Box(width - 21, 3, 9, 9); }

    /** ADD, REM, SEL and MISC sit along the bottom-left corner piece. */
    public Box menuButton(int i) {
        final int[] xs = {14, 43, 72, 101};
        return new Box(xs[i], height - 30, MENU_BTN_W, MENU_BTN_H);
    }

    /** LIST OPTS sits at the right-hand end of the bottom bar. */
    public Box listButton() {
        return new Box(width - 44, height - 30, MENU_BTN_W, MENU_BTN_H);
    }

    /** The six little transport buttons: previous, play, pause, stop, next, eject. */
    public Box miniTransport(int i) {
        return new Box(bottomRightX() + 3 + i * 10, height - 16, 10, 10);
    }

    /** Where the "elapsed/total" line is drawn, in the bitmap font. */
    public Box runningTime() {
        return new Box(bottomRightX() + 7, height - 28, 90, 6);
    }

    /** The small green time beside the mini transport. */
    public Box miniTime() {
        return new Box(bottomRightX() + 66, height - 15, 30, 6);
    }

    public Box scrollUpButton() { return new Box(width - 15, height - 36, 8, 5); }
    public Box scrollDownButton() { return new Box(width - 15, height - 30, 8, 5); }

    // ---------------------------------------------------------------- text

    /**
     * Winamp's running time: minutes and seconds, with the minutes allowed to run past 60
     * rather than rolling over into hours. A 90-minute playlist reads "90:00".
     */
    public static String duration(long ms) {
        long total = Math.max(0, ms) / 1000;
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }
}
