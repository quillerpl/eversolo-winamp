package org.eversolo.winamp.skin;

/**
 * How big to draw each window on a given screen.
 *
 * Winamp windows are pixel art, so they are only ever drawn at whole-number scales. That
 * makes sizing a search rather than a division: find the largest whole scale that still
 * shows enough rows to be useful, then let the zoom setting multiply it.
 *
 * Android-free, because getting this wrong produces a window drawn off the edge of a screen
 * that has no debugger attached to it.
 */
public final class WindowScales {

    private WindowScales() {}

    /** The main window has a fixed size, so it simply gets as large as the screen allows. */
    public static int main(int screenW, int screenH) {
        return Math.max(1, Math.min(screenW / SkinSprites.WINDOW_W,
                screenH / SkinSprites.WINDOW_H));
    }

    /**
     * The scale the playlist would take on its own: the largest that still shows
     * {@code wantedRows} tracks. Falls back to x1, which shows the most rows of all.
     */
    public static int natural(int screenW, int screenH, int wantedRows) {
        for (int s = main(screenW, screenH); s >= 1; s--) {
            if (PlaylistGeometry.BASE_W * s > screenW) continue;
            if (PlaylistGeometry.rowsIn(PlaylistGeometry.heightFor(screenH, s)) >= wantedRows) {
                return s;
            }
        }
        return 1;
    }

    /**
     * The natural scale multiplied by the user's zoom, backed off until the playlist's
     * smallest legal window still fits the screen. x2 of x4 is x8, which on a 2000 px wide
     * screen would need a 250 px window - narrower than Winamp's 275 px minimum - so it
     * lands on x7 instead.
     */
    public static int zoomed(int screenW, int screenH, int wantedRows, float zoom) {
        int scale = Math.max(1, Math.round(natural(screenW, screenH, wantedRows) * zoom));
        int minH = PlaylistGeometry.CHROME_H
                + PlaylistGeometry.SEG_H * PlaylistGeometry.MIN_SEGMENTS;
        while (scale > 1
                && (PlaylistGeometry.BASE_W * scale > screenW || minH * scale > screenH)) {
            scale--;
        }
        return scale;
    }
}
