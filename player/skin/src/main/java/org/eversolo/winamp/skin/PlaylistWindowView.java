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
 * The classic Winamp 2.x playlist editor, drawn from the skin's pledit.bmp.
 *
 * This is a separate window from the player, as it was in Winamp: the PL button opens and
 * closes it, and the X on its own title bar closes it too. It knows nothing about the music
 * library - it is handed a list of rows and reports what the user did with them, which
 * keeps the skin layer independent of the playlist model (ARCHITECTURE.md).
 *
 * Touch, not mouse. Winamp's playlist is driven by click-to-select and double-click-to-play,
 * and both are kept, but nothing is only reachable by a gesture: a tap selects, and the
 * window's own play button starts whatever is selected. That rule comes from v0.7, where
 * hiding per-row actions behind a long-press turned out to be a bad idea on a screen you
 * operate at arm's length.
 */
public final class PlaylistWindowView extends View {

    private static final String TAG = "PlaylistWindow";

    /** Two taps closer together than this on the same row mean "play it". */
    private static final long DOUBLE_TAP_MS = 400;

    /** A drag has to move this far, in skin pixels, before it scrolls instead of selects. */
    private static final float DRAG_SLOP = 4f;

    public interface Callbacks {
        void onPlayIndex(int index);
        void onRemove(List<Integer> indices);
        void onKeepOnly(List<Integer> indices);
        void onClearList();
        void onSortList();
        /** ADD FILE / ADD DIR: on this device that means the library browser. */
        void onAddFiles();
        void onAddUrl();
        void onFileInfo(int index);
        void onMiscOptions();
        void onSaveList();
        void onLoadList();
        void onPrevious();
        void onPlay();
        void onPause();
        void onStop();
        void onNext();
        void onClose();
        /** The user touched this window, so it should look like the active one. */
        void onFocused();
        /** An index into {@link ZoomChooser#LEVELS}, from MISC OPTS. */
        void onZoom(int level);
        /** FULLSCR in MISC OPTS: hide the device's side bar while the screen is untouched. */
        void onFullScreen(boolean on);
        /** MAIN x8: draw the main window one whole scale larger than fits, and crop it. */
        void onOversize(boolean on);
    }

    /** One line of the list, already formatted - the view does no library lookups. */
    public static final class Row {
        public final String title;
        public final String time;
        public final long durationMs;
        public Row(String title, String time, long durationMs) {
            this.title = title == null ? "" : title;
            this.time = time == null ? "" : time;
            this.durationMs = durationMs;
        }
    }

    // ---- model ----
    private Skin skin;
    private PleditStyle style = PleditStyle.classic();
    private PlaylistGeometry geo = PlaylistGeometry.base();
    private int scale = 3;
    private Callbacks callbacks;

    private List<Row> rows = new ArrayList<>();
    private int currentIndex = -1;
    private int offset = 0;
    private final Set<Integer> selected = new LinkedHashSet<>();
    private boolean focused = true;

    // ---- transient interaction state ----
    private String openMenu = null;         // "add" / "rem" / "sel" / "misc" / "list"
    private String pressed = null;          // widget or menu item being held
    /** MISC OPTS opens this; Winamp's own options were a plain dialog too. */
    private final ZoomChooser zoom = new ZoomChooser();
    private int zoomPressed = -1;
    private boolean draggingScrollbar = false;
    private boolean draggingList = false;
    private float dragStartY;
    private int dragStartOffset;
    private long lastTapAt;
    private int lastTapIndex = -1;

    private final Paint blit = new Paint();
    private final Paint fill = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BitmapFont bitmapFont = new BitmapFont();
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    public PlaylistWindowView(Context ctx) {
        super(ctx);
        blit.setFilterBitmap(false);        // nearest-neighbour: the chrome is pixel art
        blit.setAntiAlias(false);
        blit.setDither(false);
        fill.setStyle(Paint.Style.FILL);
        // The track list is the one part of a Winamp window that is real text rather than
        // bitmaps - the skin even names the font in pledit.txt - so it is drawn smoothly.
        // Arial is not on Android; the condensed sans is the closest match at this size.
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        text.setTextSize(9f);
        setBackgroundColor(Color.BLACK);
    }

    public void setSkin(Skin s) {
        this.skin = s;
        this.style = s == null ? PleditStyle.classic() : s.pledit();
        invalidate();
    }

    public void setScale(int s) {
        this.scale = Math.max(1, s);
        requestLayout();
        invalidate();
    }

    public int scale() { return scale; }

    public void setGeometry(PlaylistGeometry g) {
        this.geo = g;
        this.offset = g.clampOffset(offset, rows.size());
        requestLayout();
        invalidate();
    }

    public PlaylistGeometry geometry() { return geo; }

    public void setCallbacks(Callbacks c) { this.callbacks = c; }

    public void setFocused(boolean f) {
        if (focused != f) { focused = f; invalidate(); }
    }

    public void setZoom(int index) {
        zoom.setCurrent(index);
        invalidate();
    }

    public void setFullScreen(boolean on) {
        zoom.setFullScreen(on);
        invalidate();
    }

    public void setOversize(boolean on) {
        zoom.setOversize(on);
        invalidate();
    }

    /**
     * Replace the list. The scroll position is kept where it was, and the playing track is
     * scrolled into view, so the window does not jump about while the music advances.
     */
    public void setTracks(List<Row> newRows, int current) {
        this.rows = newRows == null ? new ArrayList<Row>() : newRows;
        this.currentIndex = current;
        // Drop any selection that no longer exists, without walking the whole list: this
        // runs on every state poll.
        for (java.util.Iterator<Integer> it = selected.iterator(); it.hasNext(); ) {
            if (it.next() >= rows.size()) it.remove();
        }
        offset = geo.clampOffset(offset, rows.size());
        if (current >= 0) offset = geo.offsetToReveal(current, offset, rows.size());
        invalidate();
    }

    /** What the play button should start: the selection, or nothing. */
    public int firstSelected() {
        for (int i : selected) return i;
        return -1;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(geo.width * scale, geo.height * scale);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (skin == null || !skin.has("pledit.bmp")) return;
        canvas.save();
        canvas.scale(scale, scale);

        drawListBackground(canvas);
        drawTopBar(canvas);
        drawBorders(canvas);
        drawBottomBar(canvas);
        drawTracks(canvas);
        drawScrollbar(canvas);
        drawTimes(canvas);
        drawOpenMenu(canvas);
        zoom.draw(canvas, skin, blit, text, geo.menuButton(3), zoomPressed);
        drawTitleButtons(canvas);

        canvas.restore();
    }

    private void drawListBackground(Canvas c) {
        fill.setColor(style.normalBg);
        c.drawRect(PlaylistGeometry.LEFT_W, PlaylistGeometry.TOP_H,
                geo.width - PlaylistGeometry.RIGHT_W, geo.bottomY(), fill);
    }

    /** Corners at each end, the tile repeated between them, the title bar in the middle. */
    private void drawTopBar(Canvas c) {
        String tile = focused ? "PLAYLIST_TOP_TILE_SELECTED" : "PLAYLIST_TOP_TILE";
        tileAcross(c, tile, PlaylistGeometry.CORNER_W, 0,
                geo.width - 2 * PlaylistGeometry.CORNER_W);
        sprite(c, focused ? "PLAYLIST_TOP_LEFT_SELECTED" : "PLAYLIST_TOP_LEFT_CORNER", 0, 0);
        sprite(c, focused ? "PLAYLIST_TOP_RIGHT_CORNER_SELECTED" : "PLAYLIST_TOP_RIGHT_CORNER",
                geo.width - PlaylistGeometry.CORNER_W, 0);
        sprite(c, focused ? "PLAYLIST_TITLE_BAR_SELECTED" : "PLAYLIST_TITLE_BAR",
                geo.titleX(), 0);
    }

    private void drawBorders(Canvas c) {
        tileDown(c, "PLAYLIST_LEFT_TILE", 0, PlaylistGeometry.TOP_H,
                geo.bottomY() - PlaylistGeometry.TOP_H);
        tileDown(c, "PLAYLIST_RIGHT_TILE", geo.width - PlaylistGeometry.RIGHT_W,
                PlaylistGeometry.TOP_H, geo.bottomY() - PlaylistGeometry.TOP_H);
    }

    private void drawBottomBar(Canvas c) {
        int y = geo.bottomY();
        tileAcross(c, "PLAYLIST_BOTTOM_TILE", PlaylistGeometry.BOTTOM_LEFT_W, y,
                geo.width - PlaylistGeometry.BOTTOM_LEFT_W - PlaylistGeometry.BOTTOM_RIGHT_W);
        sprite(c, "PLAYLIST_BOTTOM_LEFT_CORNER", 0, y);
        sprite(c, "PLAYLIST_BOTTOM_RIGHT_CORNER", geo.bottomRightX(), y);
    }

    private void drawTracks(Canvas c) {
        if (rows.isEmpty()) return;
        final int x = geo.trackX(), y0 = geo.trackY(), w = geo.trackW();
        final int visible = geo.visibleRows();

        c.save();
        c.clipRect(x, y0, x + w, y0 + geo.trackH());

        // The duration column is only as wide as it needs to be, as in Winamp.
        float timeW = 0;
        for (int i = offset; i < Math.min(rows.size(), offset + visible); i++) {
            timeW = Math.max(timeW, text.measureText(rows.get(i).time));
        }
        final float titleW = w - timeW - 9;      // 3 px padding either side of the gap

        final int last = Math.min(rows.size(), offset + visible);
        final int digits = String.valueOf(rows.size()).length();
        // Centre the text in the row from the font's own metrics rather than a guessed
        // offset, so a skin naming a different font still sits straight.
        text.getFontMetrics(metrics);
        final float lift = (PlaylistGeometry.TRACK_H - (metrics.descent - metrics.ascent)) / 2f
                - metrics.ascent;
        for (int i = offset; i < last; i++) {
            int y = y0 + (i - offset) * PlaylistGeometry.TRACK_H;
            if (selected.contains(i)) {
                fill.setColor(style.selectedBg);
                c.drawRect(x, y, x + w, y + PlaylistGeometry.TRACK_H, fill);
            }
            text.setColor(i == currentIndex ? style.current : style.normal);
            float baseline = y + lift;
            String number = pad(String.valueOf(i + 1), digits) + ". ";
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(fit(number + rows.get(i).title, titleW), x + 3, baseline, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(rows.get(i).time, x + w - 3, baseline, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    private void drawScrollbar(Canvas c) {
        if (geo.maxOffset(rows.size()) <= 0) return;    // nothing to scroll: Winamp hides it
        SkinSprites.Rect r = SkinSprites.src(draggingScrollbar
                ? "PLAYLIST_SCROLL_HANDLE_SELECTED" : "PLAYLIST_SCROLL_HANDLE");
        if (r != null) blit(c, r, geo.scrollX(), geo.scrollHandleY(offset, rows.size()));
    }

    /**
     * Winamp's running-time readout, in the skin's own 5x6 font: the length of whatever is
     * selected over the length of the whole list. Selecting nothing shows 0:00 on the left,
     * which is what Winamp does too.
     */
    private void drawTimes(Canvas c) {
        Bitmap font = skin.bmp("text.bmp");
        if (font == null) return;
        long total = 0, chosen = 0;
        for (int i = 0; i < rows.size(); i++) {
            total += rows.get(i).durationMs;
            if (selected.contains(i)) chosen += rows.get(i).durationMs;
        }
        PlaylistGeometry.Box rt = geo.runningTime();
        String running = PlaylistGeometry.duration(chosen) + "/" + PlaylistGeometry.duration(total);
        bitmapFont.draw(c, font, BitmapFont.prepare(running), rt.x, rt.y, blit);
    }

    private void drawTitleButtons(Canvas c) {
        // Unpressed, these are part of the top-right corner bitmap; only the held state
        // has a sprite of its own.
        if ("close".equals(pressed)) {
            PlaylistGeometry.Box b = geo.closeButton();
            sprite(c, "PLAYLIST_CLOSE_SELECTED", b.x, b.y);
        } else if ("shade".equals(pressed)) {
            PlaylistGeometry.Box b = geo.shadeButton();
            sprite(c, "PLAYLIST_COLLAPSE_SELECTED", b.x, b.y);
        }
    }

    // ------------------------------------------------------------------ menus

    /**
     * The fly-out menus along the bottom.
     *
     * Winamp stacks them upwards from the button, with a 3 px bar down the left, and the
     * bottom item covering the button itself.
     */
    private static final class Item {
        final String id, sprite;
        Item(String id, String sprite) { this.id = id; this.sprite = sprite; }
    }

    private static final Item[] ADD = {
            new Item("add_url", "PLAYLIST_ADD_URL"),
            new Item("add_dir", "PLAYLIST_ADD_DIR"),
            new Item("add_file", "PLAYLIST_ADD_FILE"),
    };
    private static final Item[] REM = {
            new Item("rem_all", "PLAYLIST_REMOVE_ALL"),
            new Item("crop", "PLAYLIST_CROP"),
            new Item("rem_sel", "PLAYLIST_REMOVE_SELECTED"),
            new Item("rem_misc", "PLAYLIST_REMOVE_MISC"),
    };
    private static final Item[] SEL = {
            new Item("inv_sel", "PLAYLIST_INVERT_SELECTION"),
            new Item("sel_zero", "PLAYLIST_SELECT_ZERO"),
            new Item("sel_all", "PLAYLIST_SELECT_ALL"),
    };
    private static final Item[] MISC = {
            new Item("sort_list", "PLAYLIST_SORT_LIST"),
            new Item("file_info", "PLAYLIST_FILE_INFO"),
            new Item("misc_opts", "PLAYLIST_MISC_OPTIONS"),
    };
    private static final Item[] LIST = {
            new Item("new_list", "PLAYLIST_NEW_LIST"),
            new Item("save_list", "PLAYLIST_SAVE_LIST"),
            new Item("load_list", "PLAYLIST_LOAD_LIST"),
    };

    private Item[] itemsOf(String menu) {
        if (menu == null) return null;
        switch (menu) {
            case "add":  return ADD;
            case "rem":  return REM;
            case "sel":  return SEL;
            case "misc": return MISC;
            case "list": return LIST;
            default:     return null;
        }
    }

    private String barOf(String menu) {
        switch (menu) {
            case "add":  return "PLAYLIST_ADD_MENU_BAR";
            case "rem":  return "PLAYLIST_REMOVE_MENU_BAR";
            case "sel":  return "PLAYLIST_SELECT_MENU_BAR";
            case "misc": return "PLAYLIST_MISC_MENU_BAR";
            case "list": return "PLAYLIST_LIST_BAR";
            default:     return null;
        }
    }

    private PlaylistGeometry.Box buttonOf(String menu) {
        switch (menu) {
            case "add":  return geo.menuButton(0);
            case "rem":  return geo.menuButton(1);
            case "sel":  return geo.menuButton(2);
            case "misc": return geo.menuButton(3);
            case "list": return geo.listButton();
            default:     return null;
        }
    }

    /** Where item {@code i} of an open menu sits: stacked upwards from the button. */
    private PlaylistGeometry.Box itemBox(String menu, int i, int count) {
        PlaylistGeometry.Box b = buttonOf(menu);
        int y = b.y + PlaylistGeometry.MENU_BTN_H * (1 + i - count);
        return new PlaylistGeometry.Box(b.x, y,
                PlaylistGeometry.MENU_BTN_W, PlaylistGeometry.MENU_BTN_H);
    }

    private void drawOpenMenu(Canvas c) {
        Item[] items = itemsOf(openMenu);
        if (items == null) return;
        PlaylistGeometry.Box btn = buttonOf(openMenu);
        SkinSprites.Rect bar = SkinSprites.src(barOf(openMenu));
        if (bar != null) {
            blit(c, bar, btn.x - bar.w,
                    btn.y + PlaylistGeometry.MENU_BTN_H - bar.h);
        }
        for (int i = 0; i < items.length; i++) {
            PlaylistGeometry.Box box = itemBox(openMenu, i, items.length);
            String name = items[i].id.equals(pressed)
                    ? items[i].sprite + "_SELECTED" : items[i].sprite;
            if (SkinSprites.src(name) == null) name = items[i].sprite;
            sprite(c, name, box.x, box.y);
        }
    }

    // ------------------------------------------------------------------ blitting

    private void sprite(Canvas c, String name, int x, int y) {
        SkinSprites.Rect r = SkinSprites.src(name);
        if (r != null) blit(c, r, x, y);
    }

    private void blit(Canvas c, SkinSprites.Rect r, int x, int y) {
        Bitmap b = skin.bmp(r.file);
        if (b == null) return;
        src.set(r.x, r.y, r.x + r.w, r.y + r.h);
        dst.set(x, y, x + r.w, y + r.h);
        c.drawBitmap(b, src, dst, blit);
    }

    /** Repeat a sprite horizontally, clipped to {@code w} - the borders are tiles. */
    private void tileAcross(Canvas c, String name, int x, int y, int w) {
        SkinSprites.Rect r = SkinSprites.src(name);
        if (r == null || w <= 0) return;
        c.save();
        c.clipRect(x, y, x + w, y + r.h);
        for (int i = 0; i < w; i += r.w) blit(c, r, x + i, y);
        c.restore();
    }

    private void tileDown(Canvas c, String name, int x, int y, int h) {
        SkinSprites.Rect r = SkinSprites.src(name);
        if (r == null || h <= 0) return;
        c.save();
        c.clipRect(x, y, x + r.w, y + h);
        for (int i = 0; i < h; i += r.h) blit(c, r, x, y + i);
        c.restore();
    }

    // ------------------------------------------------------------------ text helpers

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.append(s).toString();
    }

    /** Cut a title that does not fit, ending it with an ellipsis as Winamp does. */
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
            case MotionEvent.ACTION_DOWN:  return down(x, y);
            case MotionEvent.ACTION_MOVE:  return move(x, y);
            case MotionEvent.ACTION_UP:    return up(x, y);
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

        // The zoom chooser, if it is up, takes the touch before anything else.
        if (zoom.isOpen()) {
            zoomPressed = zoom.hit(geo.menuButton(3), x, y);
            if (zoomPressed < 0) zoom.close();
            invalidate();
            return true;
        }

        // An open menu swallows the next touch: either an item, or a tap to dismiss it.
        Item[] items = itemsOf(openMenu);
        if (items != null) {
            for (int i = 0; i < items.length; i++) {
                if (itemBox(openMenu, i, items.length).contains(x, y)) {
                    pressed = items[i].id;
                    invalidate();
                    return true;
                }
            }
            openMenu = null;
            invalidate();
            return true;
        }

        if (geo.closeButton().contains(x, y)) { pressed = "close"; invalidate(); return true; }
        if (geo.shadeButton().contains(x, y)) { pressed = "shade"; invalidate(); return true; }

        String[] menus = {"add", "rem", "sel", "misc", "list"};
        for (String m : menus) {
            if (buttonOf(m).contains(x, y)) { pressed = "menu:" + m; return true; }
        }

        String[] transport = {"prev", "play", "pause", "stop", "next", "eject"};
        for (int i = 0; i < transport.length; i++) {
            if (geo.miniTransport(i).contains(x, y)) { pressed = transport[i]; return true; }
        }

        if (geo.scrollUpButton().contains(x, y))   { pressed = "scroll_up"; return true; }
        if (geo.scrollDownButton().contains(x, y)) { pressed = "scroll_down"; return true; }

        // The scrollbar is a strip down the right-hand border, not just the handle: tapping
        // anywhere on it jumps there, which is far easier than hitting an 8 px handle.
        if (x >= geo.scrollX() - 3 && x <= geo.scrollX() + PlaylistGeometry.SCROLL_W + 3
                && y >= geo.scrollTop() && y < geo.bottomY()) {
            draggingScrollbar = true;
            offset = geo.offsetForHandleY(y, rows.size());
            invalidate();
            return true;
        }

        if (y >= geo.trackY() && y < geo.trackY() + geo.trackH()
                && x >= geo.trackX() && x < geo.trackX() + geo.trackW()) {
            draggingList = true;
            dragStartY = y;
            dragStartOffset = offset;
            pressed = "list";
            return true;
        }
        return true;
    }

    private boolean move(float x, float y) {
        if (draggingScrollbar) {
            offset = geo.offsetForHandleY(y, rows.size());
            invalidate();
            return true;
        }
        if (draggingList) {
            float moved = y - dragStartY;
            if (Math.abs(moved) >= DRAG_SLOP) {
                pressed = null;             // it is a scroll, not a tap on a row
                offset = geo.clampOffset(
                        dragStartOffset - (int) (moved / PlaylistGeometry.TRACK_H),
                        rows.size());
                invalidate();
            }
            return true;
        }
        return true;
    }

    private boolean up(float x, float y) {
        if (zoom.isOpen()) {
            int which = zoom.hit(geo.menuButton(3), x, y);
            if (which >= 0 && which == zoomPressed && callbacks != null) {
                zoom.close();
                if (which == ZoomChooser.FULLSCREEN) {
                    boolean on = !zoom.isFullScreen();
                    zoom.setFullScreen(on);
                    callbacks.onFullScreen(on);
                } else if (which == ZoomChooser.OVERSIZE) {
                    boolean on = !zoom.isOversize();
                    zoom.setOversize(on);
                    callbacks.onOversize(on);
                } else {
                    zoom.setCurrent(ZoomChooser.levelOf(which));
                    callbacks.onZoom(ZoomChooser.levelOf(which));
                }
            }
            zoomPressed = -1;
            invalidate();
            return true;
        }

        String was = pressed;
        boolean wasDraggingList = draggingList;
        pressed = null;
        draggingScrollbar = false;
        draggingList = false;

        if (was == null) { invalidate(); return true; }

        if ("list".equals(was) && wasDraggingList) {
            tapRow(y);
            invalidate();
            return true;
        }
        if (was.startsWith("menu:")) {
            String menu = was.substring(5);
            if (buttonOf(menu).contains(x, y)) openMenu = menu;
            invalidate();
            return true;
        }
        // Menu items and widgets only fire if the finger came up over them.
        if (fire(was, x, y)) openMenu = null;
        invalidate();
        return true;
    }

    private void tapRow(float y) {
        int index = geo.rowAt(y, offset, rows.size());
        if (index < 0) return;
        long now = System.currentTimeMillis();
        boolean second = index == lastTapIndex && now - lastTapAt < DOUBLE_TAP_MS;
        lastTapAt = now;
        lastTapIndex = index;

        selected.clear();
        selected.add(index);
        if (second && callbacks != null) {
            Logs.i(TAG, "double tap plays row " + index);
            callbacks.onPlayIndex(index);
        }
    }

    /** Returns true if the touch landed on the widget it started on. */
    private boolean fire(String id, float x, float y) {
        if (!stillOver(id, x, y)) return false;
        if (callbacks == null) return true;
        Logs.i(TAG, "playlist: " + id);
        switch (id) {
            case "close":     callbacks.onClose(); break;
            case "shade":     callbacks.onClose(); break;   // no shade mode yet: close is honest
            case "prev":      callbacks.onPrevious(); break;
            case "play":      callbacks.onPlay(); break;
            case "pause":     callbacks.onPause(); break;
            case "stop":      callbacks.onStop(); break;
            case "next":      callbacks.onNext(); break;
            case "eject":     callbacks.onAddFiles(); break;
            case "scroll_up":   scrollBy(-4); break;
            case "scroll_down": scrollBy(4); break;

            case "add_url":   callbacks.onAddUrl(); break;
            case "add_dir":
            case "add_file":  callbacks.onAddFiles(); break;

            case "rem_all":   selected.clear(); callbacks.onClearList(); break;
            case "crop":      callbacks.onKeepOnly(sortedSelection()); break;
            case "rem_sel":   callbacks.onRemove(sortedSelection()); selected.clear(); break;
            case "rem_misc":  callbacks.onMiscOptions(); break;

            case "inv_sel":   invertSelection(); break;
            case "sel_zero":  selected.clear(); break;
            case "sel_all":   selectAll(); break;

            case "sort_list": callbacks.onSortList(); break;
            case "file_info": callbacks.onFileInfo(firstSelected()); break;
            // Winamp's own options lived behind this button, so the zoom chooser does too.
            case "misc_opts": zoom.open(); break;

            case "new_list":  selected.clear(); callbacks.onClearList(); break;
            case "save_list": callbacks.onSaveList(); break;
            case "load_list": callbacks.onLoadList(); break;
            default: break;
        }
        return true;
    }

    private boolean stillOver(String id, float x, float y) {
        Item[] items = itemsOf(openMenu);
        if (items != null) {
            for (int i = 0; i < items.length; i++) {
                if (items[i].id.equals(id)) return itemBox(openMenu, i, items.length).contains(x, y);
            }
        }
        PlaylistGeometry.Box b = boxOf(id);
        return b == null || b.contains(x, y);
    }

    private PlaylistGeometry.Box boxOf(String id) {
        switch (id) {
            case "close": return geo.closeButton();
            case "shade": return geo.shadeButton();
            case "prev":  return geo.miniTransport(0);
            case "play":  return geo.miniTransport(1);
            case "pause": return geo.miniTransport(2);
            case "stop":  return geo.miniTransport(3);
            case "next":  return geo.miniTransport(4);
            case "eject": return geo.miniTransport(5);
            case "scroll_up":   return geo.scrollUpButton();
            case "scroll_down": return geo.scrollDownButton();
            default: return null;
        }
    }

    private void scrollBy(int rowsDelta) {
        offset = geo.clampOffset(offset + rowsDelta, rows.size());
    }

    private void selectAll() {
        selected.clear();
        for (int i = 0; i < rows.size(); i++) selected.add(i);
    }

    private void invertSelection() {
        Set<Integer> was = new LinkedHashSet<>(selected);
        selected.clear();
        for (int i = 0; i < rows.size(); i++) if (!was.contains(i)) selected.add(i);
    }

    private List<Integer> sortedSelection() {
        List<Integer> out = new ArrayList<>(selected);
        Collections.sort(out);
        return out;
    }
}
