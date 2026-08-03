package org.eversolo.winamp.skin;

/**
 * Where each lyric line sits, and how far the list has scrolled.
 *
 * Every other list in the app has rows of one height, which is what `ListMath` assumes. Here
 * no two rows need match: the line being sung is drawn at double size, and any line - at any
 * size - wraps onto as many rows as its words need. A truncated lyric is worse than no lyric,
 * so wrapping is not optional, and once lines wrap their heights can only be measured, not
 * calculated.
 *
 * So the heights are handed in and this works from a running total. Android-free, because
 * "the sung line sits in the middle" is exactly the kind of claim that looks right in a
 * screenshot and is half a line out in the hand.
 */
public final class LyricsGeometry {

    /** Height of one row of ordinary text, in skin pixels, before the window scale. */
    public static final int LINE_H = 11;

    /** And of one row of the sung line, which is drawn at double size. */
    public static final int BIG_LINE_H = LINE_H * 2;

    /** A little air above and below the sung line so it does not touch its neighbours. */
    public static final int CURRENT_PAD = 3;

    private LyricsGeometry() {}

    /** The top of line {@code i}, measured from the top of the whole list. */
    public static int topOf(int[] heights, int i) {
        int y = 0;
        for (int n = 0; n < i && n < heights.length; n++) y += heights[n];
        return y;
    }

    public static int totalHeight(int[] heights) {
        int y = 0;
        for (int h : heights) y += h;
        return y;
    }

    /**
     * How far to scroll so the sung line sits in the middle of a window {@code viewportH}
     * tall. Negative means the opening lines hang above the top edge, which is right: the
     * highlight stays put and the words travel past it.
     *
     * Before the first line is due, the list simply sits at the top.
     */
    public static int centredScroll(int[] heights, int current, int viewportH) {
        if (current < 0 || current >= heights.length) return 0;
        return topOf(heights, current) + heights[current] / 2 - viewportH / 2;
    }

    /**
     * Ease the scroll toward its target instead of jumping.
     *
     * A jump on every line change reads as a flicker; this covers a fixed fraction of what
     * is left each frame, which is quick when a line change moves the list a long way and
     * imperceptible when it does not. Always closes the last pixel, or the list creeps for
     * ever and never settles.
     */
    public static int easeScroll(int from, int target, int perFrameTenths) {
        int gap = target - from;
        if (gap == 0) return from;
        int step = gap * perFrameTenths / 10;
        if (step == 0) step = gap > 0 ? 1 : -1;
        return from + step;
    }

    /** First line that could be on screen. */
    public static int firstVisible(int[] heights, int scroll) {
        int y = 0;
        for (int i = 0; i < heights.length; i++) {
            if (y + heights[i] > scroll) return i;
            y += heights[i];
        }
        return Math.max(0, heights.length - 1);
    }

    /** One past the last line that could be on screen. */
    public static int lastVisible(int[] heights, int scroll, int viewportH) {
        int y = 0;
        for (int i = 0; i < heights.length; i++) {
            if (y > scroll + viewportH) return i;
            y += heights[i];
        }
        return heights.length;
    }
}
