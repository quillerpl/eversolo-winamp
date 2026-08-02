package org.eversolo.winamp.skin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import org.eversolo.winamp.core.Logs;

import java.util.Locale;

/**
 * The classic Winamp 2.x main window, drawn from a skin's bitmaps.
 *
 * The window is 275x116 skin pixels and is scaled by a WHOLE number only - these are pixel
 * art and any fractional scaling makes them soft and wrong. Nearest-neighbour throughout.
 *
 * This is the player. The playlist is a separate window (see PL button).
 */
public final class MainWindowView extends View {

    private static final String TAG = "MainWindow";

    /** The title bar, in skin pixels, and how long a press on it opens the log console. */
    private static final int TITLE_BAR_H = 14;
    private static final long LONG_PRESS_MS = 700;

    /** The visualiser window: 19 bars of 3 px with a pixel between them fills 76. */
    private static final int VIS_X = 24, VIS_Y = 43, VIS_W = 76, VIS_H = 16;
    private static final int BARS = 19;
    /** How fast a bar falls back, and how much slower its peak marker falls, per frame. */
    private static final float FALL = 0.09f;
    private static final float PEAK_FALL = 0.02f;

    public interface Callbacks {
        void onPrevious();
        void onPlay();
        void onPause();
        void onStop();
        void onNext();
        void onEject();
        void onSeek(float fraction);
        void onVolume(int percent);
        void onToggleEqualizer();
        void onTogglePlaylist();
        void onShuffle();
        void onRepeat();
        /** The X on the title bar. In Winamp that quits, and it does here too. */
        void onClose();
        /** Long-press the title bar: the on-device log console. */
        void onShowLog();
        /** Tapping the little black window: spectrum analyser on or off. */
        void onToggleVisualiser();
        /** Tapping the clock: elapsed or remaining. */
        void onToggleTimeMode();
        /** Tapping the song title: the tags or the file name. */
        void onToggleTitleMode();
        /** Tapping the Winamp logo, bottom right: choose a skin. */
        void onLogo();
    }

    // ---- model ----
    private Skin skin;
    private int scale = 3;
    private Callbacks callbacks;

    private String songTitle = "";
    private long positionMs, durationMs;
    private int volumePercent = 100;
    private boolean playing, paused;
    private boolean playlistOpen, eqOpen, shuffleOn, repeatOn;
    private int kbps, khz;
    private boolean stereo = true;
    /** Winamp dims the title bar of whichever window is not in front. */
    private boolean focused = true;

    private String pressed = null;      // which button is currently held
    private int marqueeOffset = 0;
    private long titleBarDownAt = 0;    // for the long-press that opens the log console

    private boolean showRemaining = false;
    private String flashText = "";
    private long flashUntil = 0;
    private static final long FLASH_MS = 4000;

    private boolean visualiserOn = true;
    private float[] levels;                          // what the device last reported
    private final float[] shown = new float[BARS];   // what is drawn, easing towards it
    private final float[] peaks = new float[BARS];

    private final Paint paint = new Paint();
    private final Paint fill = new Paint();
    private final BitmapFont font = new BitmapFont();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    public MainWindowView(Context ctx) {
        super(ctx);
        paint.setFilterBitmap(false);   // nearest-neighbour: keep the pixels crisp
        paint.setAntiAlias(false);
        paint.setDither(false);
        setBackgroundColor(Color.BLACK);
    }

    public void setSkin(Skin s) { this.skin = s; invalidate(); }

    public void setScale(int s) {
        this.scale = Math.max(1, s);
        requestLayout();
        invalidate();
    }

    public int scale() { return scale; }

    public void setCallbacks(Callbacks c) { this.callbacks = c; }

    public void setNowPlaying(String title, long positionMs, long durationMs,
                              boolean playing, boolean paused) {
        this.songTitle = title == null ? "" : title;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.playing = playing;
        this.paused = paused;
        invalidate();
    }

    public void setFocused(boolean f) {
        if (focused != f) { focused = f; invalidate(); }
    }

    public void setVolumePercent(int p) { this.volumePercent = clamp(p, 0, 100); invalidate(); }
    public void setQuality(int kbps, int khz, boolean stereo) {
        this.kbps = kbps; this.khz = khz; this.stereo = stereo; invalidate();
    }
    public void setToggles(boolean playlistOpen, boolean eqOpen, boolean shuffle, boolean repeat) {
        this.playlistOpen = playlistOpen; this.eqOpen = eqOpen;
        this.shuffleOn = shuffle; this.repeatOn = repeat;
        invalidate();
    }

    /** Advance the title marquee one step. Call on a timer while playing. */
    public void tickMarquee() {
        marqueeOffset++;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(SkinSprites.WINDOW_W * scale, SkinSprites.WINDOW_H * scale);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (skin == null || !skin.isUsable()) {
            return;
        }
        canvas.save();
        canvas.scale(scale, scale);

        // background and title bar
        blitWhole(canvas, "main.bmp", 0, 0);
        sprite(canvas, focused ? "MAIN_TITLE_BAR_SELECTED" : "MAIN_TITLE_BAR", 0, 0);
        if ("close".equals(pressed)) sprite(canvas, "MAIN_CLOSE_BUTTON_DEPRESSED", 264, 3);

        // transport row
        button(canvas, "MAIN_PREVIOUS_BUTTON", "prev", 16, 88);
        button(canvas, "MAIN_PLAY_BUTTON", "play", 39, 88);
        button(canvas, "MAIN_PAUSE_BUTTON", "pause", 62, 88);
        button(canvas, "MAIN_STOP_BUTTON", "stop", 85, 88);
        button(canvas, "MAIN_NEXT_BUTTON", "next", 108, 88);
        button(canvas, "MAIN_EJECT_BUTTON", "eject", 136, 89);

        // shuffle / repeat
        sprite(canvas, shuffleOn ? "MAIN_SHUFFLE_BUTTON_SELECTED" : "MAIN_SHUFFLE_BUTTON", 164, 89);
        sprite(canvas, repeatOn ? "MAIN_REPEAT_BUTTON_SELECTED" : "MAIN_REPEAT_BUTTON", 210, 89);

        // EQ / PL toggles
        sprite(canvas, eqOpen ? "MAIN_EQ_BUTTON_SELECTED" : "MAIN_EQ_BUTTON", 219, 58);
        sprite(canvas, playlistOpen ? "MAIN_PLAYLIST_BUTTON_SELECTED" : "MAIN_PLAYLIST_BUTTON", 242, 58);

        // playback state indicator
        String ind = playing ? "MAIN_PLAYING_INDICATOR"
                : paused ? "MAIN_PAUSED_INDICATOR" : "MAIN_STOPPED_INDICATOR";
        sprite(canvas, ind, 26, 28);

        drawVisualiser(canvas);
        drawTime(canvas);
        drawMarquee(canvas);
        drawQuality(canvas);
        drawVolume(canvas);
        drawBalance(canvas);
        drawPosition(canvas);

        canvas.restore();
    }

    // ------------------------------------------------------------------ visualiser

    /**
     * The spectrum analyser in the little black window, driven by the device's own FFT.
     *
     * 19 bars, 3 px wide with a pixel between them, in the 76x16 area at 24,43 - the same
     * arrangement Winamp used, and the colours come from the skin's viscolor.txt, so it is
     * the familiar red-through-green gradient. The peak markers fall slowly, as they did.
     *
     * The bars shown lag the bars measured: the device is polled about five times a second
     * (any faster would be rude to a small Android box) and the values are eased towards on
     * every frame, which is what makes it look continuous rather than steppy.
     */
    private void drawVisualiser(Canvas c) {
        if (!visualiserOn || levels == null) return;
        VisColors colours = skin.visColors();
        fill.setColor(colours.get(VisColors.BACKGROUND));
        c.drawRect(VIS_X, VIS_Y, VIS_X + VIS_W, VIS_Y + VIS_H, fill);

        for (int i = 0; i < BARS; i++) {
            int x = VIS_X + i * 4;
            int bar = Math.round(shown[i] * VIS_H);
            for (int row = 0; row < bar; row++) {
                int y = VIS_Y + VIS_H - 1 - row;
                // Row 0 of the gradient is the top of a full-height bar.
                fill.setColor(colours.spectrum(VIS_H - 1 - row));
                c.drawRect(x, y, x + 3, y + 1, fill);
            }
            int peak = Math.round(peaks[i] * VIS_H);
            if (peak > 0) {
                fill.setColor(colours.get(VisColors.PEAK));
                int y = VIS_Y + VIS_H - 1 - Math.min(VIS_H - 1, peak);
                c.drawRect(x, y, x + 3, y + 1, fill);
            }
        }
    }

    /** New measurements from the device: 0..1 per bar. */
    public void setSpectrum(float[] bars) {
        if (bars == null) return;
        if (levels == null) levels = new float[BARS];
        for (int i = 0; i < BARS; i++) {
            levels[i] = i < bars.length ? clampF(bars[i], 0f, 1f) : 0f;
        }
        invalidateVis();
    }

    /**
     * Ease the drawn bars towards the measured ones and let the peaks fall. Called on a
     * timer while the visualiser is on; returns false when everything has settled, so the
     * caller can stop animating.
     */
    public boolean tickVisualiser() {
        if (!visualiserOn || levels == null) return false;
        boolean moving = false;
        for (int i = 0; i < BARS; i++) {
            float target = levels[i];
            if (shown[i] < target) shown[i] = target;               // attack is instant
            else shown[i] = Math.max(target, shown[i] - FALL);      // release falls
            peaks[i] = Math.max(shown[i], peaks[i] - PEAK_FALL);
            if (Math.abs(shown[i] - target) > 0.001f || peaks[i] > 0.001f) moving = true;
        }
        invalidateVis();
        return moving;
    }

    public void setVisualiserOn(boolean on) {
        if (visualiserOn == on) return;
        visualiserOn = on;
        if (!on) {
            java.util.Arrays.fill(shown, 0f);
            java.util.Arrays.fill(peaks, 0f);
        }
        invalidate();
    }

    public boolean isVisualiserOn() { return visualiserOn; }

    /** Only the little black window needs repainting, not the whole player. */
    private void invalidateVis() {
        invalidate(VIS_X * scale, VIS_Y * scale,
                (VIS_X + VIS_W) * scale, (VIS_Y + VIS_H) * scale);
    }

    /**
     * The clock, either counting up or counting down with a minus sign in front of it -
     * Winamp toggled between the two when you clicked it, and so does this.
     *
     * The minus is a 5x1 sliver at 38,32 in numbers.bmp: Winamp really does draw it as a
     * single line of pixels.
     */
    private void drawTime(Canvas c) {
        long ms = positionMs;
        boolean counting = showRemaining && durationMs > 0;
        if (counting) ms = Math.max(0, durationMs - positionMs);

        long secs = Math.max(0, ms / 1000);
        int mm = (int) (secs / 60), ss = (int) (secs % 60);
        if (mm > 99) mm = 99;
        if (counting) sprite(c, "MINUS_SIGN", 38, 32);
        digit(c, mm / 10, 48, 26);
        digit(c, mm % 10, 60, 26);
        digit(c, ss / 10, 78, 26);
        digit(c, ss % 10, 90, 26);
    }

    public void setShowRemaining(boolean b) {
        if (showRemaining == b) return;
        showRemaining = b;
        invalidate();
    }

    public boolean isShowRemaining() { return showRemaining; }

    private void digit(Canvas c, int d, int x, int y) {
        if (d < 0 || d > 9) return;
        sprite(c, "DIGIT_" + d, x, y);
    }

    /**
     * Put a message in the title area for a few seconds, over whatever is playing.
     *
     * The state poll rewrites the title twice a second, so a message written straight into
     * it would be gone before it could be read.
     */
    public void flashTitle(String message) {
        flashText = message == null ? "" : message;
        flashUntil = System.currentTimeMillis() + FLASH_MS;
        invalidate();
    }

    /** Song title, scrolling when it does not fit - the marquee area is 154x6. */
    private void drawMarquee(Canvas c) {
        Bitmap text = skin.bmp("text.bmp");
        if (text == null) return;

        String showing = System.currentTimeMillis() < flashUntil ? flashText : songTitle;
        if (showing.isEmpty()) return;

        final int areaX = 111, areaY = 24, areaW = 154;
        int charsThatFit = areaW / SkinSprites.CHAR_W;

        String s = showing.toUpperCase(Locale.UK);
        String draw;
        if (s.length() <= charsThatFit) {
            draw = s;
        } else {
            String looped = s + "   ***   ";
            int off = marqueeOffset % looped.length();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < charsThatFit; i++) {
                sb.append(looped.charAt((off + i) % looped.length()));
            }
            draw = sb.toString();
        }

        c.save();
        c.clipRect(areaX, areaY, areaX + areaW, areaY + SkinSprites.CHAR_H);
        int x = areaX;
        for (int i = 0; i < draw.length(); i++) {
            drawChar(c, text, draw.charAt(i), x, areaY);
            x += SkinSprites.CHAR_W;
        }
        c.restore();
    }

    private void drawChar(Canvas c, Bitmap text, char ch, int x, int y) {
        font.drawChar(c, text, ch, x, y, paint);
    }

    /**
     * The two little LCD boxes. Measured in main.bmp they are x 109..126 and x 154..166, so
     * the figures are right-aligned to their right-hand edge: that keeps "44" and "128"
     * sitting where Winamp puts them, and a four-digit lossless bitrate still reads.
     *
     * A zero means the device did not report it, and an empty box is more honest than a
     * made-up number.
     */
    private void drawQuality(Canvas c) {
        Bitmap text = skin.bmp("text.bmp");
        if (text != null) {
            if (kbps > 0) drawSmallRight(c, text, String.valueOf(kbps), 127, 43);
            if (khz > 0) drawSmallRight(c, text, String.valueOf(khz), 167, 43);
        }
        sprite(c, stereo ? "MAIN_STEREO_SELECTED" : "MAIN_STEREO", 239, 41);
        sprite(c, stereo ? "MAIN_MONO" : "MAIN_MONO_SELECTED", 212, 41);
    }

    private void drawSmallRight(Canvas c, Bitmap text, String s, int rightX, int y) {
        int x = rightX - s.length() * SkinSprites.CHAR_W;
        for (int i = 0; i < s.length(); i++) {
            drawChar(c, text, s.charAt(i), x + i * SkinSprites.CHAR_W, y);
        }
    }

    private void drawVolume(Canvas c) {
        Bitmap vol = skin.bmp("volume.bmp");
        if (vol == null) return;
        // volume.bmp is 28 stacked frames of 68x15; pick one by level.
        int frame = clamp(volumePercent * 27 / 100, 0, 27);
        src.set(0, frame * 15, 68, frame * 15 + 13);
        dst.set(107, 57, 107 + 68, 57 + 13);
        c.drawBitmap(vol, src, dst, paint);

        SkinSprites.Rect thumb = SkinSprites.src(
                "volume".equals(pressed) ? "MAIN_VOLUME_THUMB_SELECTED" : "MAIN_VOLUME_THUMB");
        if (thumb != null) {
            int x = 107 + (68 - 14) * volumePercent / 100;
            blit(c, thumb, x, 58);
        }
    }

    /** Balance is centred and inert: the device exposes no balance control. */
    private void drawBalance(Canvas c) {
        Bitmap bal = skin.bmp("balance.bmp");
        if (bal == null) return;
        src.set(0, 0, 38, 13);
        dst.set(177, 57, 177 + 38, 57 + 13);
        c.drawBitmap(bal, src, dst, paint);
        SkinSprites.Rect thumb = SkinSprites.src("MAIN_BALANCE_THUMB");
        if (thumb == null) thumb = SkinSprites.src("MAIN_VOLUME_THUMB");
        if (thumb != null) blit(c, thumb, 177 + (38 - 14) / 2, 58);
    }

    private void drawPosition(Canvas c) {
        SkinSprites.Rect bg = SkinSprites.src("MAIN_POSITION_SLIDER_BACKGROUND");
        if (bg != null) blit(c, bg, 16, 72);
        if (durationMs <= 0) return;
        SkinSprites.Rect thumb = SkinSprites.src(
                "position".equals(pressed) ? "MAIN_POSITION_SLIDER_THUMB_SELECTED"
                                           : "MAIN_POSITION_SLIDER_THUMB");
        if (thumb == null) return;
        float f = clampF((float) positionMs / durationMs, 0f, 1f);
        int x = 16 + (int) ((248 - 29) * f);
        blit(c, thumb, x, 72);
    }

    // ---- blit helpers ----

    private void blitWhole(Canvas c, String file, int x, int y) {
        Bitmap b = skin.bmp(file);
        if (b == null) return;
        src.set(0, 0, b.getWidth(), b.getHeight());
        dst.set(x, y, x + b.getWidth(), y + b.getHeight());
        c.drawBitmap(b, src, dst, paint);
    }

    private void sprite(Canvas c, String name, int x, int y) {
        SkinSprites.Rect r = SkinSprites.src(name);
        if (r != null) blit(c, r, x, y);
    }

    private void blit(Canvas c, SkinSprites.Rect r, int x, int y) {
        Bitmap b = skin.bmp(r.file);
        if (b == null) return;
        src.set(r.x, r.y, r.x + r.w, r.y + r.h);
        dst.set(x, y, x + r.w, y + r.h);
        c.drawBitmap(b, src, dst, paint);
    }

    private void button(Canvas c, String baseName, String id, int x, int y) {
        String n = id.equals(pressed) ? baseName + "_ACTIVE" : baseName;
        if (SkinSprites.src(n) == null) n = baseName;
        sprite(c, n, x, y);
    }

    // ------------------------------------------------------------------ input

    private static final class Hit {
        final String id; final int x, y, w, h;
        Hit(String id, int x, int y, int w, int h) {
            this.id = id; this.x = x; this.y = y; this.w = w; this.h = h;
        }
        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private static final Hit[] HITS = {
            new Hit("prev", 16, 88, 23, 18),
            new Hit("play", 39, 88, 23, 18),
            new Hit("pause", 62, 88, 23, 18),
            new Hit("stop", 85, 88, 23, 18),
            new Hit("next", 108, 88, 22, 18),
            new Hit("eject", 136, 89, 22, 16),
            new Hit("shuffle", 164, 89, 47, 15),
            new Hit("repeat", 210, 89, 28, 15),
            new Hit("eq", 219, 58, 23, 12),
            new Hit("pl", 242, 58, 23, 12),
            new Hit("volume", 107, 57, 68, 13),
            new Hit("position", 16, 72, 248, 10),
            // The X on the title bar. Winamp quits from here, and it is the only visible
            // way out of a window that covers the whole screen.
            new Hit("close", 264, 3, 9, 9),
            // Tapping the visualiser turns it off and on, as it cycled modes in Winamp.
            new Hit("vis", VIS_X, VIS_Y, VIS_W, VIS_H),
            // The clock: tap to count down instead of up, as Winamp did. 39,26 is the
            // clickable area webamp uses; a few pixels taller here for fingers.
            new Hit("time", 36, 24, 62, 17),
            // The song title: tap to see the file name instead.
            new Hit("title", 111, 20, 154, 14),
            // The lightning-bolt logo in the bottom-right corner. In Winamp it opened the
            // About box; here it is where you change the skin, which is the nearest thing
            // this player has to "about Winamp". Measured off main.bmp: the bolt occupies
            // 249..266 x 88..105, and this is a couple of pixels wider all round for fingers.
            new Hit("logo", 247, 86, 22, 22),
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Hit h = hit(x, y);
                pressed = h == null ? null : h.id;
                // The title bar is otherwise dead space; holding it opens the log console,
                // which is the only debugging this device offers.
                titleBarDownAt = (h == null && y < TITLE_BAR_H) ? e.getEventTime() : 0;
                if (h != null && ("volume".equals(h.id) || "position".equals(h.id))) drag(h, x);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if ("volume".equals(pressed) || "position".equals(pressed)) {
                    drag(hitById(pressed), x);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                Hit h = hit(x, y);
                if (h != null && h.id.equals(pressed)) fire(h.id, x);
                else if (titleBarDownAt > 0 && y < TITLE_BAR_H
                        && e.getEventTime() - titleBarDownAt >= LONG_PRESS_MS
                        && callbacks != null) {
                    Logs.i(TAG, "title bar long press - opening the log console");
                    callbacks.onShowLog();
                }
                titleBarDownAt = 0;
                pressed = null;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private Hit hit(float x, float y) {
        for (Hit h : HITS) if (h.contains(x, y)) return h;
        return null;
    }

    private Hit hitById(String id) {
        for (Hit h : HITS) if (h.id.equals(id)) return h;
        return null;
    }

    private void drag(Hit h, float x) {
        if (h == null) return;
        float f = clampF((x - h.x) / h.w, 0f, 1f);
        if ("volume".equals(h.id)) volumePercent = Math.round(f * 100);
        else if ("position".equals(h.id) && durationMs > 0) positionMs = (long) (f * durationMs);
    }

    private void fire(String id, float x) {
        if (callbacks == null) return;
        Logs.i(TAG, "tap: " + id);
        switch (id) {
            case "prev":   callbacks.onPrevious(); break;
            case "play":   callbacks.onPlay(); break;
            case "pause":  callbacks.onPause(); break;
            case "stop":   callbacks.onStop(); break;
            case "next":   callbacks.onNext(); break;
            case "eject":  callbacks.onEject(); break;
            case "eq":     callbacks.onToggleEqualizer(); break;
            case "pl":     callbacks.onTogglePlaylist(); break;
            case "close":  callbacks.onClose(); break;
            case "vis":    callbacks.onToggleVisualiser(); break;
            case "time":   callbacks.onToggleTimeMode(); break;
            case "title":  callbacks.onToggleTitleMode(); break;
            case "logo":   callbacks.onLogo(); break;
            case "shuffle": callbacks.onShuffle(); break;
            case "repeat": callbacks.onRepeat(); break;
            case "volume": callbacks.onVolume(volumePercent); break;
            case "position":
                if (durationMs > 0) callbacks.onSeek((float) positionMs / durationMs);
                break;
        }
    }

    // ------------------------------------------------------------------ misc

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float clampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
