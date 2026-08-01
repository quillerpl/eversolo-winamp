package org.eversolo.winamp.skin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.Locale;

/**
 * The skin's own 5x6 pixel font out of text.bmp.
 *
 * Winamp uses it for the main window's scrolling title and for the playlist's running-time
 * line. It only has one case - the glyphs look like capitals but are indexed by the
 * lower-case letter - so anything drawn with it is effectively upper case.
 */
public final class BitmapFont {

    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    public static int width(String s) {
        return s == null ? 0 : s.length() * SkinSprites.CHAR_W;
    }

    public void draw(Canvas c, Bitmap font, String s, int x, int y, Paint paint) {
        if (font == null || s == null) return;
        for (int i = 0; i < s.length(); i++) {
            drawChar(c, font, s.charAt(i), x + i * SkinSprites.CHAR_W, y, paint);
        }
    }

    public void drawChar(Canvas c, Bitmap font, char ch, int x, int y, Paint paint) {
        int[] pos = SkinSprites.FONT.get(Character.toLowerCase(ch));
        if (pos == null) pos = SkinSprites.FONT.get(' ');
        if (pos == null) return;
        final int w = SkinSprites.CHAR_W, h = SkinSprites.CHAR_H;
        src.set(pos[1] * w, pos[0] * h, pos[1] * w + w, pos[0] * h + h);
        dst.set(x, y, x + w, y + h);
        c.drawBitmap(font, src, dst, paint);
    }

    /** Upper-cases for readability; the font has no lower-case glyphs anyway. */
    public static String prepare(String s) {
        return s == null ? "" : s.toUpperCase(Locale.UK);
    }
}
