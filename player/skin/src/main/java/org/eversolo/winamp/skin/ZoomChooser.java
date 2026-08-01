package org.eversolo.winamp.skin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * The little ×1 / ×1.5 / ×2 chooser that both scrolling windows offer.
 *
 * Rows 13 skin pixels tall are about 3 mm on the Eversolo's screen, which is fine to read
 * and unpleasant to hit. Rather than pick one size for everybody, both windows can be
 * scaled up - and because these are pixel art, that means drawing the whole window at a
 * larger whole-number scale rather than stretching the text inside it. Bigger rows, fewer
 * of them.
 *
 * It draws as three genex.bmp buttons stacked above whatever opened it, the same way the
 * playlist's own menus fly out.
 */
public final class ZoomChooser {

    /** What the labels mean, as a multiplier on the window's natural scale. */
    public static final float[] LEVELS = {1f, 1.5f, 2f};
    public static final String[] LABELS = {"x1", "x1.5", "x2"};

    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    private boolean open;
    private int current;

    public boolean isOpen() { return open; }
    public void open() { open = true; }
    public void close() { open = false; }
    public void toggle() { open = !open; }
    public int current() { return current; }
    public void setCurrent(int i) { current = Math.max(0, Math.min(LEVELS.length - 1, i)); }

    /** Item {@code i}, stacked upwards from the button that opened the chooser. */
    public PlaylistGeometry.Box item(PlaylistGeometry.Box anchor, int i) {
        int h = GenSprites.BUTTON_H;
        return new PlaylistGeometry.Box(anchor.x, anchor.y + h * (1 + i - LEVELS.length),
                GenSprites.BUTTON_W, h);
    }

    /** Which item a touch landed on, or -1. */
    public int hit(PlaylistGeometry.Box anchor, float x, float y) {
        if (!open) return -1;
        for (int i = 0; i < LEVELS.length; i++) {
            if (item(anchor, i).contains(x, y)) return i;
        }
        return -1;
    }

    /**
     * @param pressed which item is being held, or -1
     */
    public void draw(Canvas c, Skin skin, Paint blit, Paint text,
                     PlaylistGeometry.Box anchor, int pressed) {
        if (!open || skin == null) return;
        Bitmap genex = skin.bmp("genex.bmp");
        if (genex == null) return;

        Paint.FontMetrics fm = text.getFontMetrics();
        for (int i = 0; i < LEVELS.length; i++) {
            PlaylistGeometry.Box b = item(anchor, i);
            SkinSprites.Rect r = GenSprites.src(
                    (i == current || i == pressed) ? "GENEX_BUTTON_PRESSED" : "GENEX_BUTTON");
            if (r == null) continue;
            src.set(r.x, r.y, r.x + r.w, r.y + r.h);
            dst.set(b.x, b.y, b.x + b.w, b.y + b.h);
            c.drawBitmap(genex, src, dst, blit);

            text.setColor(0xFF101010);
            text.setTextAlign(Paint.Align.CENTER);
            float baseline = b.y + (b.h - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            c.drawText(LABELS[i], b.x + b.w / 2f, baseline, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }
}
