package org.eversolo.winamp.skin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * The full-screen light show, behind the EQ button.
 *
 * This is NOT MilkDrop or AVS, and it cannot be. Those read the actual waveform sixty times
 * a second - butterchurn calls getByteTimeDomainData every frame and runs its own FFT - and
 * this player never touches the audio at all: the device decodes it straight to its DACs,
 * which is the entire point of the design, and hands us a frequency snapshot over HTTP a
 * few times a second. So the presets are out.
 *
 * What is left is still worth having: the device's own FFT, eased between frames, with beat
 * detection on the bass bins driving three effects. It moves with the music. It just does
 * not know what the music looks like.
 *
 * Everything is drawn with Canvas primitives over a translucent wash rather than per-pixel,
 * because a quad-core A55 is also decoding audio while this runs. The wash is what leaves
 * the trails.
 */
public final class VisualiserView extends View {

    public interface Callbacks {
        void onNextEffect();
        void onClose();
    }

    public static final String[] EFFECTS = {"SCOPE", "BARS", "STARS"};

    private static final int TRAIL_ALPHA = 38;      // how quickly the last frame fades
    /** How long the close button stays up after a tap, and what counts as a double tap. */
    private static final long CHROME_MS = 3000;
    private static final long DOUBLE_TAP_MS = 400;
    private static final int STARS = 90;
    private static final float BEAT_THRESHOLD = 1.35f;
    private static final long BEAT_GAP_MS = 220;

    private Skin skin;
    private Callbacks callbacks;
    private int effect = 0;
    private int buttonScale = 4;

    private float[] bars = new float[19];
    private final float[] shown = new float[19];
    /** Why there is nothing to see, or null when the feed is fine. */
    private String problem = "waiting for the device";
    /** When the last real measurement arrived: after a while, run on time alone. */
    private long lastDataAt;

    private long chromeUntil, lastTapAt;
    private float energy, averageEnergy;
    private long lastBeatAt;
    private float beat;                              // 1 on a beat, decaying
    private float phase;                             // rotation / colour cycling
    private int frames;

    private final float[] starX = new float[STARS];
    private final float[] starY = new float[STARS];
    private final float[] starVx = new float[STARS];
    private final float[] starVy = new float[STARS];
    private final float[] starLife = new float[STARS];

    private final Paint wash = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blit = new Paint();
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final android.graphics.Rect dst = new android.graphics.Rect();
    private final Path path = new Path();
    private final float[] hsv = new float[3];

    public VisualiserView(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        blit.setFilterBitmap(false);
        wash.setColor(Color.argb(TRAIL_ALPHA, 0, 0, 0));
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(0xFF9AA0A6);
        hsv[1] = 1f;
        hsv[2] = 1f;
    }

    public void setSkin(Skin s) { this.skin = s; }
    public void setCallbacks(Callbacks c) { this.callbacks = c; }
    public void setButtonScale(int s) { this.buttonScale = Math.max(1, s); invalidate(); }

    /**
     * Shown across the middle when no spectrum is arriving, so the window is never just
     * dead. Set every frame by the host, and simply assigned - an earlier version only
     * accepted it until the first data arrived, and since "let the bars fall away" counted
     * as data, it silenced itself permanently and showed a black screen instead.
     */
    public void setProblem(String why) {
        problem = why;
    }

    public int effect() { return effect; }

    public void nextEffect() {
        effect = (effect + 1) % EFFECTS.length;
        invalidate();
    }

    /**
     * Fresh measurements, 0..1 per bar - and the only place a beat can be found.
     *
     * The baseline has to move at the rate the measurements arrive, not at the frame rate.
     * Updating it every frame instead was the first thing tried, and it cannot work: the
     * average converges on the eased value between measurements, so the ratio never rises
     * far enough to count as a beat and nothing ever pulses. An off-device simulation of
     * this same arithmetic is what caught it.
     */
    public void setSpectrum(float[] values) {
        if (values == null || values.length == 0) return;
        bars = values;
        lastDataAt = System.currentTimeMillis();
        problem = null;

        float bass = 0f;
        int n = Math.min(4, values.length);
        for (int i = 0; i < n; i++) bass += values[i];
        energy = bass / n;
        averageEnergy = averageEnergy == 0 ? energy : averageEnergy * 0.85f + energy * 0.15f;

        long now = System.currentTimeMillis();
        if (energy > averageEnergy * BEAT_THRESHOLD && energy > 0.08f
                && now - lastBeatAt > BEAT_GAP_MS) {
            lastBeatAt = now;
            beat = 1f;
            onBeat();
        }
    }

    /**
     * One frame: ease the bars, let the beat decay, and draw.
     *
     * With no measurements for a couple of seconds it falls back to moving on time alone.
     * That is not a visualiser and does not pretend to be - the message on screen says so -
     * but a still picture reads as broken, and this device turns out to have no spectrum
     * to give (everSoloPlayInfo.isHasSpectrum is false on every source).
     */
    public void tick() {
        boolean live = System.currentTimeMillis() - lastDataAt < 2000;
        if (!live) {
            double t = frames / 25.0;
            for (int i = 0; i < shown.length; i++) {
                shown[i] = (float) (0.25 + 0.22 * Math.sin(t * 1.7 + i * 0.45)
                        + 0.15 * Math.sin(t * 0.6 - i * 0.2));
            }
            energy = 0.3f;
            if (frames % 30 == 0) { beat = 1f; onBeat(); }
        } else {
            for (int i = 0; i < shown.length; i++) {
                float target = i < bars.length ? bars[i] : 0f;
                shown[i] = shown[i] < target ? target : Math.max(target, shown[i] - 0.08f);
            }
        }
        beat = Math.max(0f, beat - 0.06f);
        phase += 0.012f + energy * 0.05f;
        frames++;
        invalidate();
    }

    private void onBeat() {
        if (effect == 2) spawnStars();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // The wash instead of a clear: what is left of the last frame is the trail.
        c.drawRect(0, 0, w, h, wash);

        switch (effect) {
            case 1:  drawBars(c, w, h); break;
            case 2:  drawStars(c, w, h); break;
            default: drawScope(c, w, h); break;
        }
        drawProblem(c, w, h);
        drawChrome(c, w, h);
    }

    /** A polar plot of the spectrum: the shape breathes, the ring pulses on the beat. */
    private void drawScope(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float base = Math.min(w, h) * (0.16f + beat * 0.05f);
        float reach = Math.min(w, h) * 0.30f;
        final int points = 180;

        path.reset();
        for (int i = 0; i <= points; i++) {
            float a = (float) (i * 2 * Math.PI / points) + phase;
            float level = sampleAt((float) i / points);
            float r = base + level * reach;
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        stroke.setStrokeWidth(2f + beat * 4f);
        stroke.setColor(hue(phase * 20f, 1f));
        c.drawPath(path, stroke);

        // A second, quieter ring running the other way gives it depth.
        path.reset();
        for (int i = 0; i <= points; i++) {
            float a = (float) (-i * 2 * Math.PI / points) - phase * 0.6f;
            float level = sampleAt((float) i / points);
            float r = base * 0.6f + level * reach * 0.5f;
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(hue(phase * 20f + 120f, 0.7f));
        c.drawPath(path, stroke);
    }

    /** Mirrored bars from the middle out, in the skin's own visualiser colours. */
    private void drawBars(Canvas c, int w, int h) {
        VisColors colours = skin == null ? VisColors.classic() : skin.visColors();
        int n = shown.length;
        float bw = (float) w / n;
        float mid = h / 2f;
        for (int i = 0; i < n; i++) {
            float level = shown[i];
            float half = level * h * 0.45f * (1f + beat * 0.15f);
            int band = Math.min(15, Math.round(level * 15));
            fill.setColor(colours.spectrum(15 - band));
            c.drawRect(i * bw + 1, mid - half, (i + 1) * bw - 1, mid + half, fill);
        }
        fill.setColor(colours.get(VisColors.PEAK));
        c.drawRect(0, mid - 1, w, mid + 1, fill);
    }

    private void spawnStars() {
        for (int i = 0; i < STARS; i++) {
            if (starLife[i] > 0f) continue;
            double a = Math.random() * Math.PI * 2;
            float speed = (float) (2 + Math.random() * 6) * (0.5f + energy * 2f);
            starX[i] = 0f;
            starY[i] = 0f;
            starVx[i] = (float) Math.cos(a) * speed;
            starVy[i] = (float) Math.sin(a) * speed;
            starLife[i] = 1f;
            if (Math.random() < 0.35) break;         // a handful per beat, not all of them
        }
    }

    /** Particles thrown outwards on every beat, fading as they go. */
    private void drawStars(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        stroke.setStrokeWidth(3f);
        for (int i = 0; i < STARS; i++) {
            if (starLife[i] <= 0f) continue;
            float px = cx + starX[i], py = cy + starY[i];
            starX[i] += starVx[i];
            starY[i] += starVy[i];
            starLife[i] -= 0.012f;
            stroke.setColor(hue(i * 7f + phase * 30f, Math.max(0f, starLife[i])));
            c.drawLine(px, py, cx + starX[i], cy + starY[i], stroke);
        }
        fill.setColor(hue(phase * 40f, 0.5f + beat * 0.5f));
        c.drawCircle(cx, cy, 6f + beat * 30f + energy * 40f, fill);
    }

    /** Interpolated between bars, so 19 measurements make a smooth curve. */
    private float sampleAt(float t) {
        // Fold the spectrum so the shape is symmetrical rather than lopsided.
        float folded = t < 0.5f ? t * 2f : (1f - t) * 2f;
        float pos = folded * (shown.length - 1);
        int i = (int) pos;
        float frac = pos - i;
        float a = shown[Math.min(i, shown.length - 1)];
        float b = shown[Math.min(i + 1, shown.length - 1)];
        return a + (b - a) * frac;
    }

    private int hue(float degrees, float value) {
        hsv[0] = ((degrees % 360f) + 360f) % 360f;
        hsv[2] = Math.max(0f, Math.min(1f, value));
        return Color.HSVToColor(hsv);
    }

    /**
     * A close button in the corner, and only after a tap.
     *
     * The full screen is the point, so nothing sits on top of it permanently; one tap
     * brings the way out back for three seconds, and a double tap changes the effect.
     */
    private void drawChrome(Canvas c, int w, int h) {
        if (System.currentTimeMillis() > chromeUntil) return;
        int bw = GenSprites.BUTTON_W * buttonScale, bh = GenSprites.BUTTON_H * buttonScale;
        int pad = 4 * buttonScale;
        drawButton(c, w - bw - pad, pad, bw, bh, "CLOSE");

        text.setColor(0xFF9AA0A6);
        text.setTextSize(9f * buttonScale);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(EFFECTS[effect] + "  -  double tap to change", pad + 4,
                pad + bh * 0.7f, text);
    }

    /** When there is no audio data, say so along the bottom rather than pretending. */
    private void drawProblem(Canvas c, int w, int h) {
        if (problem == null) return;
        text.setColor(0xFF707880);
        text.setTextSize(8f * buttonScale);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(problem.toUpperCase(java.util.Locale.UK) + " - RUNNING ON TIME ALONE",
                w / 2f, h - 6f * buttonScale, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawButton(Canvas c, int x, int y, int w, int h, String label) {
        SkinSprites.Rect r = GenSprites.src("GENEX_BUTTON");
        android.graphics.Bitmap genex = skin == null ? null : skin.bmp("genex.bmp");
        if (r != null && genex != null) {
            // Reused rather than allocated: this runs 25 times a second.
            src.set(r.x, r.y, r.x + r.w, r.y + r.h);
            dst.set(x, y, x + w, y + h);
            c.drawBitmap(genex, src, dst, blit);
        } else {
            fill.setColor(0xFF303030);
            c.drawRect(x, y, x + w, y + h, fill);
        }
        text.setColor(0xFF101010);
        text.setTextSize(9f * buttonScale);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, x + w / 2f,
                y + (h - (fm.descent - fm.ascent)) / 2f - fm.ascent, text);
        text.setColor(0xFF9AA0A6);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        final long now = System.currentTimeMillis();
        final int bw = GenSprites.BUTTON_W * buttonScale, bh = GenSprites.BUTTON_H * buttonScale;
        final int pad = 4 * buttonScale;
        final float x = e.getX(), y = e.getY();

        // The close button, while it is up.
        if (now < chromeUntil && y <= pad + bh && x >= getWidth() - bw - pad) {
            if (callbacks != null) callbacks.onClose();
            return true;
        }
        if (now - lastTapAt < DOUBLE_TAP_MS) {
            lastTapAt = 0;
            nextEffect();
            chromeUntil = now + CHROME_MS;
            if (callbacks != null) callbacks.onNextEffect();
        } else {
            lastTapAt = now;
            chromeUntil = now + CHROME_MS;
        }
        invalidate();
        return true;
    }
}
