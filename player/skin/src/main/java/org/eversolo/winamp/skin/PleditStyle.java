package org.eversolo.winamp.skin;

import java.util.Locale;

/**
 * The playlist editor's colours, from the skin's PLEDIT.TXT.
 *
 * The track list is the one part of a Winamp window that is not a bitmap: it is text drawn
 * in colours the skin author chose, in a named font. The classic skin says
 *
 *     [Text]
 *     Normal=#00FF00      green
 *     Current=#FFFFFF     the track that is playing
 *     NormalBG=#000000
 *     SelectedBG=#0000C6  the blue behind a selected row
 *     Font=Arial
 *
 * Skins in the wild vary the case of the keys and sometimes drop the '#', so parsing is
 * deliberately forgiving. Anything missing falls back to the classic values above, which
 * is also what Winamp does.
 */
public final class PleditStyle {

    public final int normal;
    public final int current;
    public final int normalBg;
    public final int selectedBg;
    public final String font;

    private PleditStyle(int normal, int current, int normalBg, int selectedBg, String font) {
        this.normal = normal;
        this.current = current;
        this.normalBg = normalBg;
        this.selectedBg = selectedBg;
        this.font = font;
    }

    /** Winamp's own defaults, used when a skin ships no PLEDIT.TXT. */
    public static PleditStyle classic() {
        return new PleditStyle(0xFF00FF00, 0xFFFFFFFF, 0xFF000000, 0xFF0000C6, "Arial");
    }

    public static PleditStyle parse(String text) {
        if (text == null || text.isEmpty()) return classic();
        PleditStyle d = classic();
        int normal = d.normal, current = d.current, bg = d.normalBg, sel = d.selectedBg;
        String font = d.font;

        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("[") || line.startsWith(";")) continue;
            String key = line.substring(0, eq).trim().toLowerCase(Locale.UK);
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "normal":     normal = colour(value, normal); break;
                case "current":    current = colour(value, current); break;
                case "normalbg":   bg = colour(value, bg); break;
                case "selectedbg": sel = colour(value, sel); break;
                case "font":       if (!value.isEmpty()) font = value; break;
                default: break;    // mbFG/mbBG belong to the minibrowser, which we do not have
            }
        }
        return new PleditStyle(normal, current, bg, sel, font);
    }

    /** "#RRGGBB", "RRGGBB", or anything else, in which case keep what we had. */
    private static int colour(String s, int fallback) {
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.length() < 6) return fallback;
        try {
            return 0xFF000000 | (int) Long.parseLong(hex.substring(0, 6), 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
