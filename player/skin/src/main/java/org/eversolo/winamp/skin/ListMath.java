package org.eversolo.winamp.skin;

/**
 * Scrolling arithmetic shared by every scrolling window: the playlist and the browser.
 *
 * Kept in one place and free of Android so it can be tested on a desktop JVM - "which row
 * did that tap land on" is not something you want to be debugging on a device with no
 * logcat, and it is the same question in both windows.
 */
public final class ListMath {

    private ListMath() {}

    public static int maxOffset(int count, int visibleRows) {
        return Math.max(0, count - visibleRows);
    }

    public static int clampOffset(int offset, int count, int visibleRows) {
        int max = maxOffset(count, visibleRows);
        return offset < 0 ? 0 : (offset > max ? max : offset);
    }

    /** Scroll the least amount that brings {@code index} on screen. */
    public static int reveal(int index, int offset, int count, int visibleRows) {
        int o = offset;
        if (index < o) o = index;
        else if (index >= o + visibleRows) o = index - visibleRows + 1;
        return clampOffset(o, count, visibleRows);
    }

    /** Row index at {@code y}, or -1 if the tap missed the list. */
    public static int rowAt(float y, int top, int height, int rowHeight, int offset, int count) {
        if (y < top || y >= top + height) return -1;
        int index = offset + (int) ((y - top) / rowHeight);
        return (index >= 0 && index < count) ? index : -1;
    }

    public static int handleY(int top, int travel, int offset, int maxOffset) {
        if (maxOffset <= 0) return top;
        int clamped = offset < 0 ? 0 : (offset > maxOffset ? maxOffset : offset);
        return top + travel * clamped / maxOffset;
    }

    /** Turn a drag on the scrollbar back into an offset. */
    public static int offsetForHandleY(float y, int top, int travel, int handleHeight,
                                       int count, int visibleRows) {
        int max = maxOffset(count, visibleRows);
        if (max <= 0) return 0;
        float f = (y - top - handleHeight / 2f) / Math.max(1, travel);
        return clampOffset(Math.round(f * max), count, visibleRows);
    }
}
