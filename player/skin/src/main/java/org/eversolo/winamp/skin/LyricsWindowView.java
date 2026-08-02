package org.eversolo.winamp.skin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The lyrics window: the words, with the line being sung twice the size and in bold, held in
 * the middle while the rest scroll past it.
 *
 * Like the other windows it knows nothing about tracks, files or time. It is handed a list of
 * lines and told which one is current; working out which one that is belongs to `WinampUi`.
 *
 * It wears the generic frame, like the browser. The frame drawing here is deliberately a copy
 * of the browser's rather than a shared base class: that code is stable and pixel-exact, and
 * factoring it out would mean refactoring a shipped window that can only really be checked by
 * looking at it. Worth doing when something else needs the frame; not worth it for the second.
 */
public final class LyricsWindowView extends View {

    public interface Callbacks {
        void onClose();
        void onFocused();
    }

    /** How much of the remaining distance the scroll covers each frame, in tenths. */
    private static final int EASE_TENTHS = 2;

    private final Paint blit = new Paint();
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    private Skin skin;
    private GenGeometry geo = new GenGeometry(275, 200);
    private PleditStyle style = PleditStyle.classic();
    private Callbacks callbacks;
    private int scale = 1;

    private String title = "LYRICS";
    private List<String> lines = new ArrayList<>();
    private boolean synced;
    private String status = "";
    private int current = -1;

    private int scroll;                 // where the list is now
    private int targetScroll;           // where it is heading
    private int titlePlateX, titlePlateW;
    private String pressed;

    public LyricsWindowView(Context ctx) {
        super(ctx);
        blit.setFilterBitmap(false);
        blit.setAntiAlias(false);
        line.setTextSize(9f);
        big.setTextSize(18f);
        big.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        big.setFakeBoldText(true);
    }

    public void setSkin(Skin s) {
        this.skin = s;
        if (s != null) this.style = s.pledit();
        invalidate();
    }

    public void setScale(int s) { this.scale = Math.max(1, s); requestLayout(); invalidate(); }

    public void setGeometry(GenGeometry g) { this.geo = g; requestLayout(); invalidate(); }

    public void setCallbacks(Callbacks c) { this.callbacks = c; }

    /** Replace the words. Resets the scroll: this is a different song. */
    public void setLines(String songTitle, List<String> newLines, boolean isSynced) {
        this.title = songTitle == null || songTitle.isEmpty() ? "LYRICS" : songTitle;
        this.lines = newLines == null ? new ArrayList<String>() : newLines;
        this.synced = isSynced;
        this.current = -1;
        this.scroll = this.targetScroll = 0;
        invalidate();
    }

    /** Shown in the middle when there are no words to show, and why. */
    public void setStatus(String s) {
        this.status = s == null ? "" : s;
        invalidate();
    }

    /**
     * Which line is being sung, or -1. Only moves the list when the line actually changes:
     * this is called on every animation frame while a track plays.
     */
    public void setCurrent(int index) {
        if (index == current) return;
        current = index;
        targetScroll = LyricsGeometry.centredScroll(current, geo.listH());
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(geo.width * scale, geo.height * scale);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (skin == null || !skin.has("gen.bmp")) return;
        canvas.save();
        canvas.scale(scale, scale);

        drawFrame(canvas);
        drawTitle(canvas);
        drawWords(canvas);
        drawCloseButton(canvas);

        canvas.restore();

        // Ease toward the target, and keep asking for frames until it arrives. Jumping
        // straight there on a line change reads as a flicker; this glides.
        if (scroll != targetScroll) {
            scroll = LyricsGeometry.easeScroll(scroll, targetScroll, EASE_TENTHS);
            postInvalidateOnAnimation();
        }
    }

    private void drawFrame(Canvas c) {
        final int W = geo.width, H = geo.height;
        fill.setColor(style.normalBg);
        c.drawRect(GenSprites.LEFT_W, GenSprites.TITLE_H,
                W - GenSprites.RIGHT_W, H - GenSprites.BOTTOM_H, fill);

        final int piece = GenSprites.PIECE_W;
        String t = title.toUpperCase(Locale.UK);
        int plateW = Math.max(piece, GenSprites.textWidth(t) + 10);
        int plateX = Math.max(piece, (W - plateW) / 2);
        int rightCapX = Math.min(W - 2 * piece, plateX + plateW);

        tileAcross(c, "GEN_TOP_FILL", piece, 0, W - 2 * piece);
        sprite(c, "GEN_TITLE_LEFT", plateX - piece, 0);
        tileAcross(c, "GEN_TITLE_FILL", plateX, 0, rightCapX - plateX);
        sprite(c, "GEN_TITLE_RIGHT", rightCapX, 0);
        sprite(c, "GEN_TOP_LEFT", 0, 0);
        sprite(c, "GEN_TOP_RIGHT", W - piece, 0);
        titlePlateX = plateX;
        titlePlateW = rightCapX - plateX;

        tileDown(c, "GEN_LEFT_BORDER", 0, GenSprites.TITLE_H,
                H - GenSprites.TITLE_H - GenSprites.BOTTOM_H);
        tileDown(c, "GEN_RIGHT_BORDER", W - GenSprites.RIGHT_W, GenSprites.TITLE_H,
                H - GenSprites.TITLE_H - GenSprites.BOTTOM_H);

        int by = H - GenSprites.BOTTOM_H;
        tileAcross(c, "GEN_BOTTOM_FILL", piece, by, W - 2 * piece);
        sprite(c, "GEN_BOTTOM_LEFT", 0, by);
        sprite(c, "GEN_BOTTOM_RIGHT", W - piece, by);
    }

    /** The window title, in gen.bmp's own alphabet, which has capitals only. */
    private void drawTitle(Canvas c) {
        Bitmap gen = skin.bmp("gen.bmp");
        if (gen == null) return;
        String s = title.toUpperCase(Locale.UK);
        int x = titlePlateX + (titlePlateW - GenSprites.textWidth(s)) / 2;
        int y = geo.titleTextY();
        for (int i = 0; i < s.length(); i++) {
            int[] l = GenSprites.LETTERS.get(s.charAt(i));
            if (l == null) { x += GenSprites.SPACE_W + 1; continue; }
            src.set(l[0], GenSprites.LETTER_Y, l[0] + l[1],
                    GenSprites.LETTER_Y + GenSprites.LETTER_H);
            dst.set(x, y, x + l[1], y + GenSprites.LETTER_H);
            c.drawBitmap(gen, src, dst, blit);
            x += l[1] + 1;
        }
    }

    private void drawCloseButton(Canvas c) {
        if ("close".equals(pressed)) sprite(c, "GEN_CLOSE_PRESSED",
                geo.closeButton().x, geo.closeButton().y);
    }

    private void drawWords(Canvas c) {
        final int x = geo.listX(), y0 = geo.listY(), w = geo.listW(), h = geo.listH();
        c.save();
        c.clipRect(x, y0, x + w, y0 + h);

        if (lines.isEmpty()) {
            line.setColor(style.normal);
            line.setTextAlign(Paint.Align.CENTER);
            line.getFontMetrics(metrics);
            c.drawText(status.isEmpty() ? "No lyrics for this track" : status,
                    x + w / 2f, y0 + h / 2f - metrics.ascent / 2f, line);
            line.setTextAlign(Paint.Align.LEFT);
            c.restore();
            return;
        }

        int from = LyricsGeometry.firstVisible(scroll, current, lines.size());
        int to = LyricsGeometry.lastVisible(scroll, h, current, lines.size());

        for (int i = from; i < to; i++) {
            boolean isCurrent = i == current;
            Paint p = isCurrent ? big : line;
            // The sung line takes the highlight colour; everything else is dimmed so the
            // eye lands on the right place without having to hunt for it.
            p.setColor(isCurrent ? style.current : style.normal);
            p.setAlpha(isCurrent ? 255 : 150);
            p.setTextAlign(Paint.Align.CENTER);
            p.getFontMetrics(metrics);

            int top = LyricsGeometry.topOf(i, current) - scroll + y0;
            float baseline = top + (LyricsGeometry.heightOf(i, current)
                    - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent;
            c.drawText(fit(lines.get(i), w - 8, p), x + w / 2f, baseline, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setAlpha(255);
        }
        c.restore();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (callbacks != null) callbacks.onFocused();
                if (geo.closeButton().contains(x, y)) { pressed = "close"; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                boolean wasClose = "close".equals(pressed);
                pressed = null;
                invalidate();
                if (wasClose && geo.closeButton().contains(x, y) && callbacks != null) {
                    callbacks.onClose();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
        }
        return true;
    }

    // ------------------------------------------------------------------ bits

    private String fit(String s, float maxW, Paint p) {
        if (maxW <= 0) return "";
        if (p.measureText(s) <= maxW) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + "…") <= maxW) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + "…";
    }

    private void sprite(Canvas c, String name, int x, int y) {
        SkinSprites.Rect r = GenSprites.src(name);
        if (r != null) blit(c, r, x, y);
    }

    private void blit(Canvas c, SkinSprites.Rect r, int x, int y) {
        Bitmap b = skin.bmp(r.file);
        if (b == null) return;
        src.set(r.x, r.y, r.x + r.w, r.y + r.h);
        dst.set(x, y, x + r.w, y + r.h);
        c.drawBitmap(b, src, dst, blit);
    }

    private void tileAcross(Canvas c, String name, int x, int y, int w) {
        SkinSprites.Rect r = GenSprites.src(name);
        if (r == null || w <= 0) return;
        c.save();
        c.clipRect(x, y, x + w, y + r.h);
        for (int i = 0; i < w; i += r.w) blit(c, r, x + i, y);
        c.restore();
    }

    private void tileDown(Canvas c, String name, int x, int y, int h) {
        SkinSprites.Rect r = GenSprites.src(name);
        if (r == null || h <= 0) return;
        c.save();
        c.clipRect(x, y, x + r.w, y + h);
        for (int i = 0; i < h; i += r.h) blit(c, r, x, y + i);
        c.restore();
    }
}
