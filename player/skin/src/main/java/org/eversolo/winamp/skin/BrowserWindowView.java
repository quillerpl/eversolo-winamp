package org.eversolo.winamp.skin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import org.eversolo.winamp.core.Logs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The library browser, wearing Winamp's generic window frame (gen.bmp).
 *
 * This is a browser and nothing else. There are no transport controls and tapping a track
 * does not play it - that was the point of the rework: you come here to put things in the
 * playlist, and the playlist is where you play them.
 *
 * Tapping a folder, artist or album opens it. Tapping a track selects it. ADD puts the
 * selection in the playlist, or everything currently listed if nothing is selected, which
 * is how you add a whole album without tapping thirteen rows.
 *
 * Like the playlist window it knows nothing about the music library: it is handed rows and
 * reports what was done to them.
 */
public final class BrowserWindowView extends View {

    private static final String TAG = "BrowserWindow";
    private static final float DRAG_SLOP = 4f;
    /** How long "Added 13 tracks" stays on the bottom bar. */
    private static final long FLASH_MS = 3000;

    public interface Callbacks {
        /** A container row: open it. */
        void onOpen(int index);
        /** The "up one level" row at the top of the list. */
        void onUp();
        void onTab(int tab);
        /** ADD: the selected rows, or every row when the selection is empty. */
        void onAdd(List<Integer> selected);
        void onClose();
        void onFocused();
        /** An index into {@link ZoomChooser#LEVELS}. */
        void onZoom(int level);
    }

    /** Nothing of this row is in the playlist / some of it is / all of it is. */
    public static final int NOT_ADDED = 0;
    public static final int PART_ADDED = 1;
    public static final int ALL_ADDED = 2;

    /** One line. The view does not know or care what it stands for. */
    public static final class Row {
        public final String label;
        public final String meta;
        /** Containers open when tapped; leaves select. */
        public final boolean container;
        /** NOT_ADDED / PART_ADDED / ALL_ADDED — drawn as a marker down the left edge. */
        public final int added;
        public Row(String label, String meta, boolean container, int added) {
            this.label = label == null ? "" : label;
            this.meta = meta == null ? "" : meta;
            this.container = container;
            this.added = added;
        }
    }

    private static final String[] TABS = {"ARTIST", "ALBUM", "FOLDER", "M3U"};
    /** Bottom row, right to left. */
    private static final String[] BUTTONS = {"DONE", "ADD", "OPTIONS"};
    /** Space down the left of every row for the "already added" mark. */
    private static final int MARK_GUTTER = 8;

    // ---- model ----
    private Skin skin;
    private PleditStyle style = PleditStyle.classic();
    private GenGeometry geo = new GenGeometry(275, 232);
    private int scale = 3;
    private Callbacks callbacks;

    private String title = "LIBRARY";
    private String where = "";              // the "up one level" row, empty when at the top
    private String status = "";             // shown instead of the list while scanning
    private String flash = "";              // "Added 13 tracks", for a few seconds
    private final ZoomChooser zoom = new ZoomChooser();
    private List<Row> rows = new ArrayList<>();
    private int tab = 0;
    private int offset = 0;
    private final Set<Integer> selected = new LinkedHashSet<>();

    /** Where the title plate ended up, so the text can be centred on it. */
    private int titlePlateX, titlePlateW;

    private String pressed = null;
    private int zoomPressed = -1;
    private boolean draggingScrollbar, draggingList;
    private float dragStartY;
    private int dragStartOffset;

    private final Paint blit = new Paint();
    private final Paint fill = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final BitmapFont bitmapFont = new BitmapFont();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    public BrowserWindowView(Context ctx) {
        super(ctx);
        blit.setFilterBitmap(false);
        blit.setAntiAlias(false);
        blit.setDither(false);
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        text.setTextSize(9f);
        setBackgroundColor(Color.BLACK);
    }

    public void setSkin(Skin s) {
        this.skin = s;
        this.style = s == null ? PleditStyle.classic() : s.pledit();
        invalidate();
    }

    public void setScale(int s) { this.scale = Math.max(1, s); requestLayout(); invalidate(); }

    public void setGeometry(GenGeometry g) { this.geo = g; requestLayout(); invalidate(); }

    public void setCallbacks(Callbacks c) { this.callbacks = c; }

    /**
     * Replace the list.
     *
     * {@code keepView} is the difference between navigating somewhere new, which starts at
     * the top with nothing selected, and the same list being handed back with fresh
     * "already added" marks - where losing your scroll position would be maddening.
     */
    public void setRows(String title, String where, List<Row> newRows, int tab,
                        boolean keepView) {
        this.title = title == null ? "" : title;
        this.where = where == null ? "" : where;
        this.rows = newRows == null ? new ArrayList<Row>() : newRows;
        this.tab = tab;
        if (keepView) {
            offset = geo.clampOffset(offset, rows.size());
        } else {
            offset = 0;
            selected.clear();
        }
        invalidate();
    }

    /** A short-lived line along the bottom: what just happened. */
    public void flash(String message) {
        this.flash = message == null ? "" : message;
        invalidate();
        removeCallbacks(clearFlash);
        postDelayed(clearFlash, FLASH_MS);
    }

    private final Runnable clearFlash = () -> { flash = ""; invalidate(); };

    public void setZoom(int index) {
        zoom.setCurrent(index);
        invalidate();
    }

    /** Shown in place of the list - "scanning", or why the list is empty. */
    public void setStatus(String s) {
        this.status = s == null ? "" : s;
        invalidate();
    }

    public int tab() { return tab; }

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
        drawTabs(canvas);
        drawList(canvas);
        drawScrollbar(canvas);
        drawBottomButtons(canvas);

        canvas.restore();
    }

    private void drawFrame(Canvas c) {
        final int W = geo.width, H = geo.height;
        // content background first; the frame is drawn over its edges
        fill.setColor(style.normalBg);
        c.drawRect(GenSprites.LEFT_W, GenSprites.TITLE_H,
                W - GenSprites.RIGHT_W, H - GenSprites.BOTTOM_H, fill);

        drawTitleBar(c, W);

        tileDown(c, "GEN_LEFT_BORDER", 0, GenSprites.TITLE_H,
                H - GenSprites.TITLE_H - GenSprites.BOTTOM_H);
        tileDown(c, "GEN_RIGHT_BORDER", W - GenSprites.RIGHT_W, GenSprites.TITLE_H,
                H - GenSprites.TITLE_H - GenSprites.BOTTOM_H);

        int by = H - GenSprites.BOTTOM_H;
        tileAcross(c, "GEN_BOTTOM_FILL", GenSprites.PIECE_W, by, W - 2 * GenSprites.PIECE_W);
        sprite(c, "GEN_BOTTOM_LEFT", 0, by);
        sprite(c, "GEN_BOTTOM_RIGHT", W - GenSprites.PIECE_W, by);

        if ("close".equals(pressed)) {
            PlaylistGeometry.Box b = geo.closeButton();
            sprite(c, "GEN_CLOSE_PRESSED", b.x, b.y);
        }
    }

    /**
     * The title bar: two corners, the gold bar tiled between them, and a dark plate in the
     * middle for the text.
     *
     * The plate is built to fit the title rather than being a fixed width, which is what
     * Winamp does and what stops a long title running out over the gold lines. Only two
     * pieces of the bar repeat seamlessly - the plate and the bar itself - so those are the
     * only two that get tiled; the rest are the transitions between them.
     */
    private void drawTitleBar(Canvas c, int W) {
        final int piece = GenSprites.PIECE_W;
        int plateW = Math.max(piece, GenSprites.textWidth(
                title.toUpperCase(java.util.Locale.UK)) + 10);
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
    }

    /** The window title, in gen.bmp's own alphabet. It has capitals only, so upper-case it. */
    private void drawTitle(Canvas c) {
        Bitmap gen = skin.bmp("gen.bmp");
        if (gen == null) return;
        String s = title.toUpperCase(java.util.Locale.UK);
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

    private void drawTabs(Canvas c) {
        for (int i = 0; i < TABS.length; i++) {
            PlaylistGeometry.Box b = geo.tab(i);
            boolean on = i == tab || ("tab" + i).equals(pressed);
            sprite(c, on ? "GENEX_BUTTON_PRESSED" : "GENEX_BUTTON", b.x, b.y);
            buttonLabel(c, TABS[i], b);
        }
    }

    /**
     * Winamp puts dark text on these grey buttons. The skin's own bitmap font is green for
     * a black LCD and is nearly unreadable here, so button labels use the real font.
     */
    private void buttonLabel(Canvas c, String label, PlaylistGeometry.Box b) {
        text.setColor(0xFF101010);
        text.setTextAlign(Paint.Align.CENTER);
        text.getFontMetrics(metrics);
        float y = b.y + (b.h - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent;
        c.drawText(label, b.x + b.w / 2f, y, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawList(Canvas c) {
        final int x = geo.listX(), y0 = geo.listY(), w = geo.listW();
        c.save();
        c.clipRect(x, y0, x + w, y0 + geo.listH());

        text.getFontMetrics(metrics);
        float lift = (GenGeometry.ROW_H - (metrics.descent - metrics.ascent)) / 2f
                - metrics.ascent;

        if (!status.isEmpty() && rows.isEmpty()) {
            text.setColor(style.normal);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(status, x + 4, y0 + lift, text);
            c.restore();
            return;
        }

        int last = Math.min(rows.size(), offset + geo.visibleRows());
        for (int i = offset; i < last; i++) {
            Row r = rows.get(i);
            int y = y0 + (i - offset) * GenGeometry.ROW_H;
            if (selected.contains(i)) {
                fill.setColor(style.selectedBg);
                c.drawRect(x, y, x + w, y + GenGeometry.ROW_H, fill);
            }
            drawAddedMark(c, r.added, x + 3, y);
            // Folders and albums are what you open; Winamp's own library shows them
            // brighter than the tracks inside them.
            text.setColor(r.container ? style.current : style.normal);
            text.setTextAlign(Paint.Align.LEFT);
            float metaW = r.meta.isEmpty() ? 0 : text.measureText(r.meta) + 6;
            c.drawText(fit(r.label, w - metaW - MARK_GUTTER - 8), x + MARK_GUTTER + 3,
                    y + lift, text);
            if (!r.meta.isEmpty()) {
                text.setTextAlign(Paint.Align.RIGHT);
                c.drawText(r.meta, x + w - 4, y + lift, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    /**
     * The "this is already in the playlist" mark: a filled square for all of it, a hollow
     * one for some of it, nothing otherwise.
     *
     * Drawn rather than written, because a tick or a bullet is at the mercy of whatever
     * glyphs the device's font happens to carry, and this is the answer to "did my tap do
     * anything?" - it has to be there.
     */
    private void drawAddedMark(Canvas c, int added, int x, int rowY) {
        if (added == NOT_ADDED) return;
        int size = 5;
        int y = rowY + (GenGeometry.ROW_H - size) / 2;
        fill.setColor(style.current);
        if (added == ALL_ADDED) {
            c.drawRect(x, y, x + size, y + size, fill);
        } else {
            c.drawRect(x, y, x + size, y + 1, fill);
            c.drawRect(x, y + size - 1, x + size, y + size, fill);
            c.drawRect(x, y, x + 1, y + size, fill);
            c.drawRect(x + size - 1, y, x + size, y + size, fill);
        }
    }

    private void drawScrollbar(Canvas c) {
        if (geo.maxOffset(rows.size()) <= 0) return;
        sprite(c, draggingScrollbar ? "GENEX_SCROLL_HANDLE_PRESSED" : "GENEX_SCROLL_HANDLE",
                geo.scrollX(), geo.scrollHandleY(offset, rows.size()));
    }

    private void drawBottomButtons(Canvas c) {
        for (int i = 0; i < BUTTONS.length; i++) {
            PlaylistGeometry.Box b = geo.bottomButton(i);
            boolean held = BUTTONS[i].toLowerCase(java.util.Locale.UK).equals(pressed);
            sprite(c, held ? "GENEX_BUTTON_PRESSED" : "GENEX_BUTTON", b.x, b.y);
            buttonLabel(c, BUTTONS[i], b);
        }
        zoom.draw(c, skin, blit, text, geo.bottomButton(2), zoomPressed);

        // The bottom line: what just happened, or where we are. Any characters at all can
        // appear in an album name, so this uses the real font rather than gen.bmp's
        // capitals-only one.
        String line = !flash.isEmpty() ? flash
                // Plain ASCII on purpose: this is the way back out of a folder, and a glyph
                // the device's font happened not to carry would leave a box where the arrow
                // goes.
                : (where.isEmpty() ? "" : "<< " + where);
        if (!line.isEmpty()) {
            text.setColor(flash.isEmpty() ? style.normal : style.current);
            text.setTextAlign(Paint.Align.LEFT);
            text.getFontMetrics(metrics);
            float y = geo.buttonRowY() + (GenSprites.BUTTON_H
                    - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent;
            float room = geo.bottomButton(2).x - GenSprites.LEFT_W - 12;
            c.drawText(fit(line, room), GenSprites.LEFT_W + 4, y, text);
        }
    }

    // ------------------------------------------------------------------ blitting

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

    private String fit(String s, float maxW) {
        if (maxW <= 0) return "";
        if (text.measureText(s) <= maxW) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (text.measureText(s.substring(0, mid) + "…") <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo) + "…";
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: return down(x, y);
            case MotionEvent.ACTION_MOVE: return move(y);
            case MotionEvent.ACTION_UP:   return up(x, y);
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                draggingScrollbar = draggingList = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private boolean down(float x, float y) {
        if (callbacks != null) callbacks.onFocused();

        // An open chooser swallows the touch: an item, or a tap anywhere to dismiss it.
        if (zoom.isOpen()) {
            zoomPressed = zoom.hit(geo.bottomButton(2), x, y);
            if (zoomPressed < 0) zoom.close();
            invalidate();
            return true;
        }

        if (geo.closeButton().contains(x, y)) { pressed = "close"; invalidate(); return true; }
        for (int i = 0; i < TABS.length; i++) {
            if (geo.tab(i).contains(x, y)) { pressed = "tab" + i; invalidate(); return true; }
        }
        for (int i = 0; i < BUTTONS.length; i++) {
            if (geo.bottomButton(i).contains(x, y)) {
                pressed = BUTTONS[i].toLowerCase(java.util.Locale.UK);
                invalidate();
                return true;
            }
        }
        // The whole bottom-left strip is the "up one level" target: it shows where you are,
        // and a big target beats a small one on a touchscreen.
        if (!where.isEmpty() && y >= geo.buttonRowY() && x < geo.bottomButton(1).x) {
            pressed = "up";
            return true;
        }
        if (x >= geo.scrollX() - 3 && x <= geo.scrollX() + GenGeometry.SCROLL_W + 3
                && y >= geo.scrollTop() && y < geo.contentBottom()) {
            draggingScrollbar = true;
            offset = geo.offsetForHandleY(y, rows.size());
            invalidate();
            return true;
        }
        if (y >= geo.listY() && y < geo.listY() + geo.listH()
                && x >= geo.listX() && x < geo.listX() + geo.listW()) {
            draggingList = true;
            dragStartY = y;
            dragStartOffset = offset;
            pressed = "list";
            return true;
        }
        return true;
    }

    private boolean move(float y) {
        if (draggingScrollbar) {
            offset = geo.offsetForHandleY(y, rows.size());
            invalidate();
        } else if (draggingList) {
            float moved = y - dragStartY;
            if (Math.abs(moved) >= DRAG_SLOP) {
                pressed = null;
                offset = geo.clampOffset(dragStartOffset - (int) (moved / GenGeometry.ROW_H),
                        rows.size());
                invalidate();
            }
        }
        return true;
    }

    private boolean up(float x, float y) {
        // A held zoom item fires when the finger comes up on it.
        if (zoom.isOpen()) {
            int which = zoom.hit(geo.bottomButton(2), x, y);
            if (which >= 0 && which == zoomPressed && callbacks != null) {
                zoom.setCurrent(which);
                zoom.close();
                callbacks.onZoom(which);
            }
            zoomPressed = -1;
            invalidate();
            return true;
        }

        String was = pressed;
        pressed = null;
        draggingScrollbar = false;
        boolean wasList = draggingList;
        draggingList = false;
        if (was == null) { invalidate(); return true; }

        if ("list".equals(was) && wasList) {
            tapRow(y);
        } else if ("close".equals(was) && geo.closeButton().contains(x, y)) {
            if (callbacks != null) callbacks.onClose();
        } else if ("done".equals(was) && geo.bottomButton(0).contains(x, y)) {
            if (callbacks != null) callbacks.onClose();
        } else if ("add".equals(was) && geo.bottomButton(1).contains(x, y)) {
            if (callbacks != null) callbacks.onAdd(sortedSelection());
        } else if ("options".equals(was) && geo.bottomButton(2).contains(x, y)) {
            zoom.open();
        } else if ("up".equals(was)) {
            if (callbacks != null) callbacks.onUp();
        } else if (was.startsWith("tab")) {
            int i = was.charAt(3) - '0';
            if (geo.tab(i).contains(x, y) && callbacks != null) callbacks.onTab(i);
        }
        invalidate();
        return true;
    }

    private void tapRow(float y) {
        int index = geo.rowAt(y, offset, rows.size());
        if (index < 0) return;
        Row r = rows.get(index);
        if (r.container) {
            Logs.i(TAG, "open row " + index + ": " + r.label);
            if (callbacks != null) callbacks.onOpen(index);
        } else if (!selected.remove(index)) {
            selected.add(index);        // tapping a selected row again lets go of it
        }
    }

    private List<Integer> sortedSelection() {
        List<Integer> out = new ArrayList<>(selected);
        Collections.sort(out);
        return out;
    }
}
