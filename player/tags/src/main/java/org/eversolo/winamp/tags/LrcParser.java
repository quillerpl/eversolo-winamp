package org.eversolo.winamp.tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads an `.lrc` file: lyrics with a timestamp against each line.
 *
 * This is the whole trick behind a highlighted lyric. Tidal, Spotify and Apple Music do not
 * listen to the music and work out where the singer is - they buy a database in which
 * somebody has already typed the times. So the player's job is only ever "the track is at
 * 2:14.3, which line is that?", which is a binary search.
 *
 * Plain Java, in `:tags` beside the other parsers, so it can be proved on a laptop against
 * real files rather than against an idea of what a file looks like.
 */
public final class LrcParser {

    private LrcParser() {}

    /** One line, and when it is sung. */
    public static final class Line {
        /** Milliseconds into the track, or {@link #NO_TIME} when the file carries no timings. */
        public final long timeMs;
        public final String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text == null ? "" : text;
        }
    }

    public static final long NO_TIME = -1;

    /** A parsed file. Untimed lyrics are still worth showing, just without the highlight. */
    public static final class Lyrics {
        public final List<Line> lines;
        /** True when the lines carry timestamps and the current one can be followed. */
        public final boolean synced;

        Lyrics(List<Line> lines, boolean synced) {
            this.lines = Collections.unmodifiableList(lines);
            this.synced = synced;
        }

        public boolean isEmpty() { return lines.isEmpty(); }

        /**
         * Which line is being sung at {@code positionMs}, or -1 before the first one starts.
         *
         * Binary search rather than a scan: this is asked every animation frame while a
         * track plays, and a long song is several hundred lines.
         */
        public int indexAt(long positionMs) {
            if (!synced || lines.isEmpty()) return -1;
            int lo = 0, hi = lines.size() - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (lines.get(mid).timeMs <= positionMs) { found = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            return found;
        }
    }

    /**
     * Parse the contents of an `.lrc`, or any lyrics text at all.
     *
     * Forgiving on purpose: these files come from strangers. Anything that does not look
     * like a timestamp is treated as a line of words, which is the useful failure.
     */
    public static Lyrics parse(String content) {
        List<Line> out = new ArrayList<>();
        boolean anyTimed = false;
        long offsetMs = 0;

        if (content == null) return new Lyrics(out, false);
        if (content.startsWith("﻿")) content = content.substring(1);   // BOM

        for (String raw : content.split("\r\n|\r|\n", -1)) {
            String line = raw;
            List<Long> times = new ArrayList<>();

            // A line may carry several timestamps when the same words repeat: a chorus is
            // written once and pointed at from every place it is sung.
            while (true) {
                if (line.length() < 3 || line.charAt(0) != '[') break;
                int close = line.indexOf(']');
                if (close < 0) break;
                String inside = line.substring(1, close);
                Long t = parseTime(inside);
                if (t == null) {
                    Long off = parseOffset(inside);
                    if (off != null) offsetMs = off;
                    // Either a metadata tag like [ar:...] or junk. Drop it and carry on:
                    // what is left on the line may still be words worth showing.
                    if (off != null || isMetadata(inside)) {
                        line = line.substring(close + 1);
                        continue;
                    }
                    break;
                }
                times.add(t);
                line = line.substring(close + 1);
            }

            String text = line.trim();
            if (times.isEmpty()) {
                // No timestamp. Keep it only if it says something - a plain lyrics file is
                // all of these, and blank lines between verses are not worth a row.
                if (!text.isEmpty()) out.add(new Line(NO_TIME, text));
            } else {
                anyTimed = true;
                for (long t : times) out.add(new Line(t, text));
            }
        }

        if (anyTimed) {
            // A file with timings may still have stray untimed junk in it - a title line, a
            // credit. Those cannot be placed, so they go.
            List<Line> timed = new ArrayList<>();
            for (Line l : out) if (l.timeMs != NO_TIME) timed.add(l);
            final long off = offsetMs;
            List<Line> shifted = new ArrayList<>(timed.size());
            for (Line l : timed) shifted.add(new Line(Math.max(0, l.timeMs - off), l.text));
            Collections.sort(shifted, new Comparator<Line>() {
                @Override public int compare(Line a, Line b) {
                    return Long.compare(a.timeMs, b.timeMs);
                }
            });
            out = shifted;
        }
        return new Lyrics(out, anyTimed);
    }

    /** `mm:ss`, `mm:ss.xx`, `mm:ss.xxx` or `mm:ss:xx`. Null when it is not a time at all. */
    private static Long parseTime(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return null;
        try {
            long minutes = Long.parseLong(s.substring(0, colon).trim());
            String rest = s.substring(colon + 1);
            long seconds, fraction = 0;
            int dot = indexOfAny(rest, ".:");
            if (dot < 0) {
                seconds = Long.parseLong(rest.trim());
            } else {
                seconds = Long.parseLong(rest.substring(0, dot).trim());
                String frac = rest.substring(dot + 1).trim();
                if (frac.isEmpty()) return null;
                // Two digits means hundredths, three means milliseconds.
                long v = Long.parseLong(frac);
                fraction = frac.length() >= 3 ? v : v * 10;
            }
            if (minutes < 0 || seconds < 0 || seconds > 59) return null;
            return minutes * 60_000 + seconds * 1_000 + fraction;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** `[offset:+250]` shifts every timestamp; a negative value makes lines appear later. */
    private static Long parseOffset(String inside) {
        if (!inside.regionMatches(true, 0, "offset:", 0, 7)) return null;
        try {
            String v = inside.substring(7).trim();
            if (v.startsWith("+")) v = v.substring(1);
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isMetadata(String inside) {
        int colon = inside.indexOf(':');
        if (colon <= 0) return false;
        String key = inside.substring(0, colon).trim();
        if (key.isEmpty()) return false;
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isLetter(key.charAt(i))) return false;
        }
        return true;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }
}
