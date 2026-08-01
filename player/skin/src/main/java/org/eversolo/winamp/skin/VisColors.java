package org.eversolo.winamp.skin;

import java.util.ArrayList;
import java.util.List;

/**
 * The visualiser's palette, from the skin's VISCOLOR.TXT.
 *
 * Twenty-four colours, one per line, as "r,g,b, // comment". Winamp uses them in fixed
 * roles: 0 is the background, 1 the dotted grid, 2 to 17 are the spectrum gradient from the
 * top of a bar down to its base, 18 to 22 are the oscilloscope, and 23 is the peak marker.
 *
 * The classic skin's gradient runs red at the top through orange and yellow to green at the
 * bottom, which is the look everybody remembers.
 */
public final class VisColors {

    public static final int COUNT = 24;
    public static final int BACKGROUND = 0;
    public static final int GRID = 1;
    public static final int SPECTRUM_TOP = 2;      // .. SPECTRUM_TOP + 15 is the base
    public static final int SPECTRUM_BANDS = 16;
    public static final int PEAK = 23;

    private final int[] colours;

    private VisColors(int[] colours) { this.colours = colours; }

    public int get(int i) {
        return colours[Math.max(0, Math.min(colours.length - 1, i))];
    }

    /** Colour for row {@code row} of a bar, 0 at the top. */
    public int spectrum(int row) {
        return get(SPECTRUM_TOP + Math.max(0, Math.min(SPECTRUM_BANDS - 1, row)));
    }

    /** Winamp's own colours, for a skin that ships no viscolor.txt. */
    public static VisColors classic() {
        return new VisColors(new int[]{
                0xFF000000, 0xFF182129, 0xFFEF3110, 0xFFCE2910, 0xFFD65A00, 0xFFD66600,
                0xFFD67300, 0xFFC67B08, 0xFFDEA518, 0xFFDEBA29, 0xFFDECE31, 0xFFC6D64A,
                0xFFA5D65A, 0xFF8CCE6B, 0xFF6BC673, 0xFF52BD7B, 0xFF42B58C, 0xFF31AD9C,
                0xFF21A5A5, 0xFF109CAD, 0xFF0894BD, 0xFF008CC6, 0xFF0084CE, 0xFFFFFFFF,
        });
    }

    public static VisColors parse(String text) {
        if (text == null || text.isEmpty()) return classic();
        List<Integer> found = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            int comment = line.indexOf("//");
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (line.endsWith(",")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s*,\\s*");
            if (parts.length < 3) continue;
            try {
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                found.add(0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            } catch (NumberFormatException ignored) {
                // A comment line that got this far, or a malformed skin. Skip it.
            }
        }
        if (found.size() < COUNT) return classic();
        int[] out = new int[COUNT];
        for (int i = 0; i < COUNT; i++) out[i] = found.get(i);
        return new VisColors(out);
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
