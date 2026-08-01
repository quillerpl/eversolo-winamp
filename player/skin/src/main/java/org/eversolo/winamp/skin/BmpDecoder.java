package org.eversolo.winamp.skin;

import android.graphics.Bitmap;

/**
 * A BMP decoder that handles what Winamp skins actually contain.
 *
 * This exists because Android's BitmapFactory cannot read them. The classic base-2.91
 * skin stores most of its art as **BI_RLE8** - run-length-encoded 8-bit BMPs - and
 * BitmapFactory returns null for those. MAIN.BMP, CBUTTONS.BMP, TEXT.BMP, NUMBERS.BMP,
 * SHUFREP.BMP, POSBAR.BMP and MONOSTER.BMP are all RLE8 in that skin, so relying on the
 * platform decoder would render an empty window.
 *
 * Supports BI_RGB at 4/8/24/32 bpp and BI_RLE8, top-down or bottom-up. That covers every
 * skin encountered so far; anything else returns null and the caller falls back.
 */
public final class BmpDecoder {

    private BmpDecoder() {}

    private static final int BI_RGB = 0;
    private static final int BI_RLE8 = 1;

    public static Bitmap decode(byte[] d) {
        try {
            if (d == null || d.length < 54) return null;
            if (d[0] != 'B' || d[1] != 'M') return null;

            int dataOff = le32(d, 10);
            int hdrSize = le32(d, 14);
            int width = le32(d, 18);
            int rawHeight = le32(d, 22);
            int bpp = le16(d, 28);
            int comp = le32(d, 30);
            int colorsUsed = le32(d, 46);

            boolean topDown = rawHeight < 0;
            int height = Math.abs(rawHeight);
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null;

            int[] palette = null;
            if (bpp <= 8) {
                int n = colorsUsed != 0 ? colorsUsed : (1 << bpp);
                palette = new int[n];
                int p = 14 + hdrSize;
                for (int i = 0; i < n && p + 3 < d.length; i++, p += 4) {
                    palette[i] = 0xFF000000
                            | ((d[p + 2] & 0xFF) << 16)   // R
                            | ((d[p + 1] & 0xFF) << 8)    // G
                            | (d[p] & 0xFF);              // B
                }
            }

            int[] px = new int[width * height];
            // Rows are stored bottom-up unless the height was negative.
            if (comp == BI_RLE8 && bpp == 8) {
                decodeRle8(d, dataOff, width, height, palette, px);
            } else if (comp == BI_RGB) {
                decodeRgb(d, dataOff, width, height, bpp, palette, px);
            } else {
                return null;
            }

            if (!topDown) flipVertically(px, width, height);
            return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void decodeRle8(byte[] d, int p, int w, int h, int[] pal, int[] out) {
        int x = 0, y = 0;
        while (p + 1 < d.length) {
            int count = d[p] & 0xFF;
            int value = d[p + 1] & 0xFF;
            p += 2;
            if (count > 0) {
                for (int i = 0; i < count; i++) put(out, w, h, x + i, y, colour(pal, value));
                x += count;
            } else if (value == 0) {          // end of line
                x = 0;
                y++;
            } else if (value == 1) {          // end of bitmap
                return;
            } else if (value == 2) {          // delta
                if (p + 1 >= d.length) return;
                x += d[p] & 0xFF;
                y += d[p + 1] & 0xFF;
                p += 2;
            } else {                          // absolute run of literal pixels
                for (int i = 0; i < value && p + i < d.length; i++) {
                    put(out, w, h, x + i, y, colour(pal, d[p + i] & 0xFF));
                }
                x += value;
                p += value + (value & 1);     // runs are padded to a word boundary
            }
            if (y >= h) return;
        }
    }

    private static void decodeRgb(byte[] d, int dataOff, int w, int h, int bpp,
                                  int[] pal, int[] out) {
        int rowBytes = ((w * bpp + 31) / 32) * 4;
        for (int row = 0; row < h; row++) {
            int p = dataOff + row * rowBytes;
            if (p >= d.length) return;
            for (int x = 0; x < w; x++) {
                int c;
                switch (bpp) {
                    case 8: {
                        int i = p + x;
                        if (i >= d.length) return;
                        c = colour(pal, d[i] & 0xFF);
                        break;
                    }
                    case 4: {
                        int i = p + x / 2;
                        if (i >= d.length) return;
                        int b = d[i] & 0xFF;
                        c = colour(pal, (x % 2 == 0) ? (b >> 4) : (b & 0x0F));
                        break;
                    }
                    case 24: {
                        int i = p + x * 3;
                        if (i + 2 >= d.length) return;
                        c = 0xFF000000 | ((d[i + 2] & 0xFF) << 16)
                                | ((d[i + 1] & 0xFF) << 8) | (d[i] & 0xFF);
                        break;
                    }
                    case 32: {
                        int i = p + x * 4;
                        if (i + 3 >= d.length) return;
                        c = 0xFF000000 | ((d[i + 2] & 0xFF) << 16)
                                | ((d[i + 1] & 0xFF) << 8) | (d[i] & 0xFF);
                        break;
                    }
                    default:
                        return;
                }
                put(out, w, h, x, row, c);
            }
        }
    }

    private static void flipVertically(int[] px, int w, int h) {
        int[] row = new int[w];
        for (int y = 0; y < h / 2; y++) {
            int top = y * w, bottom = (h - 1 - y) * w;
            System.arraycopy(px, top, row, 0, w);
            System.arraycopy(px, bottom, px, top, w);
            System.arraycopy(row, 0, px, bottom, w);
        }
    }

    private static int colour(int[] pal, int index) {
        if (pal == null || index < 0 || index >= pal.length) return 0xFF000000;
        return pal[index];
    }

    private static void put(int[] out, int w, int h, int x, int y, int colour) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        out[y * w + x] = colour;
    }

    private static int le16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
    }
}
