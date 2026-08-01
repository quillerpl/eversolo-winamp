package org.eversolo.winamp.tags;

/**
 * Metadata read from an audio file.
 *
 * Deliberately plain Java with no Android dependencies, so the parsers can be
 * unit-tested on a desktop JVM. There is no debugger on the target device, so
 * anything that can be proven off-device should be.
 *
 * Any field may be null / 0 when the source did not provide it.
 */
public final class TrackTags {

    public String title;
    public String artist;
    public String album;
    public String albumArtist;
    public String genre;

    public Integer trackNumber;
    public Integer discNumber;
    public Integer year;

    /** Milliseconds. 0 when unknown (e.g. MP3, where duration is not in the tags). */
    public long durationMs;

    public int sampleRate;
    public int bitDepth;
    public int channels;

    /** Embedded cover art bytes, or null. */
    public byte[] artwork;

    /** Which reader produced this - useful in the on-device log console. */
    public String source;

    public boolean hasCoreTags() {
        return notBlank(title) || notBlank(artist) || notBlank(album);
    }

    /** Fill any missing field in this from other. Never overwrites something we already have. */
    public void fillGapsFrom(TrackTags other) {
        if (other == null) return;
        if (!notBlank(title)) title = other.title;
        if (!notBlank(artist)) artist = other.artist;
        if (!notBlank(album)) album = other.album;
        if (!notBlank(albumArtist)) albumArtist = other.albumArtist;
        if (!notBlank(genre)) genre = other.genre;
        if (trackNumber == null) trackNumber = other.trackNumber;
        if (discNumber == null) discNumber = other.discNumber;
        if (year == null) year = other.year;
        if (durationMs <= 0) durationMs = other.durationMs;
        if (sampleRate <= 0) sampleRate = other.sampleRate;
        if (bitDepth <= 0) bitDepth = other.bitDepth;
        if (channels <= 0) channels = other.channels;
        if (artwork == null) artwork = other.artwork;
    }

    public static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Parses "5", "5/12", " 5 " -> 5. Returns null if there is no usable number. */
    public static Integer parseNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash).trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return null;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Pulls a plausible 4-digit year out of free text such as "2012-05-01" or "[2012]". */
    public static Integer parseYear(String raw) {
        if (raw == null) return null;
        for (int i = 0; i + 4 <= raw.length(); i++) {
            String w = raw.substring(i, i + 4);
            if (w.chars().allMatch(Character::isDigit)) {
                int y = Integer.parseInt(w);
                if (y >= 1900 && y <= 2100) return y;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "TrackTags{" + artist + " - " + album + " - " + title
                + " trk=" + trackNumber + " yr=" + year
                + " " + durationMs + "ms " + sampleRate + "Hz/" + bitDepth + "bit/" + channels + "ch"
                + (artwork != null ? " art=" + artwork.length + "B" : "")
                + " via=" + source + '}';
    }
}
