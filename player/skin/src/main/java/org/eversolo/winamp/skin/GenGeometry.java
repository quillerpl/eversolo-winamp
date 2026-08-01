package org.eversolo.winamp.skin;

/**
 * Where everything sits inside a generic Winamp window - the frame the browser wears.
 *
 * Unlike the playlist, this frame has no fixed step size: the corners are 25 px and the
 * edges are tiles, so any size at all works. The browser fills the screen, so the size
 * comes straight from the space available.
 *
 * Layout, top to bottom: title bar, a row of view tabs, the list, a bottom bar of buttons.
 * Android-free so the arithmetic can be tested on a desktop JVM.
 */
public final class GenGeometry {

    public static final int ROW_H = PlaylistGeometry.TRACK_H;
    public static final int PAD = 3;
    public static final int SCROLL_W = 13;
    public static final int SCROLL_HANDLE_H = 30;
    /** Gap between the buttons along the bottom bar. */
    public static final int BUTTON_GAP = 6;

    public final int width;
    public final int height;

    public GenGeometry(int width, int height) {
        this.width = width;
        this.height = height;
    }

    // ---------------------------------------------------------------- areas

    public int contentTop() { return GenSprites.TITLE_H; }
    public int contentBottom() { return height - GenSprites.BOTTOM_H; }

    public int tabY() { return contentTop() + PAD; }

    public int tabX(int i) {
        return GenSprites.LEFT_W + PAD + i * (GenSprites.BUTTON_W + BUTTON_GAP);
    }

    /**
     * The buttons sit inside the window, not on the frame: the generic frame's bottom bar
     * is 14 px and a genex button is 15, so a button placed on it would hang over the edge.
     */
    public int buttonRowY() { return contentBottom() - PAD - GenSprites.BUTTON_H; }

    public int listX() { return GenSprites.LEFT_W; }
    public int listY() { return tabY() + GenSprites.BUTTON_H + PAD; }
    public int listW() { return width - GenSprites.LEFT_W - GenSprites.RIGHT_W - SCROLL_W; }
    public int listH() { return buttonRowY() - PAD - listY(); }

    public int visibleRows() { return Math.max(1, listH() / ROW_H); }

    /** The title text is centred on the 25 px-wide title plate in the middle of the bar. */
    public int titleTextY() { return (GenSprites.TITLE_H - GenSprites.LETTER_H) / 2; }

    // ---------------------------------------------------------------- widgets

    public PlaylistGeometry.Box closeButton() {
        return new PlaylistGeometry.Box(
                width - GenSprites.PIECE_W + GenSprites.CLOSE_DX, GenSprites.CLOSE_DY,
                GenSprites.CLOSE_W, GenSprites.CLOSE_W);
    }

    public PlaylistGeometry.Box tab(int i) {
        return new PlaylistGeometry.Box(tabX(i), tabY(),
                GenSprites.BUTTON_W, GenSprites.BUTTON_H);
    }

    /** Buttons along the bottom row, counted from the right-hand end. */
    public PlaylistGeometry.Box bottomButton(int fromRight) {
        int x = width - GenSprites.RIGHT_W - PAD
                - (fromRight + 1) * GenSprites.BUTTON_W - fromRight * BUTTON_GAP;
        return new PlaylistGeometry.Box(x, buttonRowY(),
                GenSprites.BUTTON_W, GenSprites.BUTTON_H);
    }

    // ---------------------------------------------------------------- scrolling

    public int scrollX() { return width - GenSprites.RIGHT_W - SCROLL_W; }
    public int scrollTop() { return listY(); }
    public int scrollTravel() { return Math.max(1, listH() - SCROLL_HANDLE_H); }

    public int maxOffset(int count) { return ListMath.maxOffset(count, visibleRows()); }

    public int clampOffset(int offset, int count) {
        return ListMath.clampOffset(offset, count, visibleRows());
    }

    public int rowAt(float y, int offset, int count) {
        return ListMath.rowAt(y, listY(), listH(), ROW_H, offset, count);
    }

    public int scrollHandleY(int offset, int count) {
        return ListMath.handleY(scrollTop(), scrollTravel(), offset, maxOffset(count));
    }

    public int offsetForHandleY(float y, int count) {
        return ListMath.offsetForHandleY(y, scrollTop(), scrollTravel(), SCROLL_HANDLE_H,
                count, visibleRows());
    }
}
