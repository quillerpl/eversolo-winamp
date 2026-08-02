package org.eversolo.winamp.skin;

/**
 * Where each lyric line sits, and how far the list has scrolled.
 *
 * The line being sung is drawn at twice the size of the rest, which is what makes this
 * different from every other list in the app: rows are no longer all the same height, so
 * none of `ListMath` applies. One line is tall, the others are short, and the tall one is
 * held in the middle of the window while the words move past it.
 *
 * Android-free, because "the sung line is more or less in the middle" is exactly the kind of
 * claim that looks right in a screenshot and is half a line out in the hand.
 */
public final class LyricsGeometry {

    /** Height of an ordinary line, in skin pixels, before the window scale is applied. */
    public static final int LINE_H = 11;

    /** The sung line is drawn at double size, so it takes double the room. */
    public static final int CURRENT_H = LINE_H * 2;

    /** A little air above and below the big line, so it does not touch its neighbours. */
    public static final int CURRENT_PAD = 3;

    private LyricsGeometry() {}

    /** How tall line {@code i} is, given which line is currently being sung. */
    public static int heightOf(int i, int current) {
        return i == current ? CURRENT_H + 2 * CURRENT_PAD : LINE_H;
    }

    /**
     * The top of line {@code i}, measured from the top of the whole list.
     *
     * Only one line is ever tall, so this is a multiplication rather than a loop: every line
     * is LINE_H, plus the extra belonging to the sung line if it is above this one.
     */
    public static int topOf(int i, int current) {
        int extra = CURRENT_H + 2 * CURRENT_PAD - LINE_H;
        int y = i * LINE_H;
        if (current >= 0 && current < i) y += extra;
        return y;
    }

    /** The height of the whole list. */
    public static int totalHeight(int count, int current) {
        int extra = CURRENT_H + 2 * CURRENT_PAD - LINE_H;
        return count * LINE_H + (current >= 0 && current < count ? extra : 0);
    }

    /**
     * How far to scroll so the sung line sits in the middle of a window {@code viewportH}
     * tall. Negative means the first lines hang below the top edge, which is correct and is
     * what the streaming players do: the highlight stays put and the words travel.
     *
     * Before the first line is due, the list simply sits at the top.
     */
    public static int centredScroll(int current, int viewportH) {
        if (current < 0) return 0;
        int centreOfLine = topOf(current, current) + heightOf(current, current) / 2;
        return centreOfLine - viewportH / 2;
    }

    /**
     * Ease the scroll toward its target instead of jumping.
     *
     * A jump on every line change reads as a flicker; this covers a fixed fraction of the
     * remaining distance each frame, which is quick when a line change moves the list a long
     * way and imperceptible when it does not. Always closes the last pixel, or the list
     * creeps for ever and never settles.
     */
    public static int easeScroll(int from, int target, int perFrameTenths) {
        int gap = target - from;
        if (gap == 0) return from;
        int step = gap * perFrameTenths / 10;
        if (step == 0) step = gap > 0 ? 1 : -1;
        return from + step;
    }

    /** First line that could be on screen, given the scroll. Never below zero. */
    public static int firstVisible(int scroll, int current, int count) {
        for (int i = 0; i < count; i++) {
            if (topOf(i, current) + heightOf(i, current) > scroll) return i;
        }
        return Math.max(0, count - 1);
    }

    /** One past the last line that could be on screen. */
    public static int lastVisible(int scroll, int viewportH, int current, int count) {
        for (int i = Math.max(0, firstVisible(scroll, current, count)); i < count; i++) {
            if (topOf(i, current) > scroll + viewportH) return i;
        }
        return count;
    }
}
