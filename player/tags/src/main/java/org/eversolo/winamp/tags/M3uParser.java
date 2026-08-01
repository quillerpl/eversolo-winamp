package org.eversolo.winamp.tags;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses .m3u / .m3u8 playlists.
 *
 * The device silently ignores playlist files - openFile returns 200 and does nothing
 * (API_FINDINGS.md §2) - so this is the app's job (decision D6).
 *
 * Pure Java with no Android dependencies so it can be tested on a desktop JVM.
 */
public final class M3uParser {

    public static final class Entry {
        /** The line exactly as it appeared. */
        public final String rawLine;
        /** Absolute path, resolved against the playlist's own folder. Null for URLs. */
        public final String resolvedPath;
        /** Title from the preceding #EXTINF line, if there was one. */
        public final String extinfTitle;
        /** Seconds from #EXTINF, or -1. */
        public final int extinfSeconds;
        public final boolean isUrl;
        public final boolean exists;

        Entry(String rawLine, String resolvedPath, String extinfTitle,
              int extinfSeconds, boolean isUrl, boolean exists) {
            this.rawLine = rawLine;
            this.resolvedPath = resolvedPath;
            this.extinfTitle = extinfTitle;
            this.extinfSeconds = extinfSeconds;
            this.isUrl = isUrl;
            this.exists = exists;
        }

        @Override
        public String toString() {
            return (exists ? "OK   " : isUrl ? "URL  " : "MISS ") + resolvedPath;
        }
    }

    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        public int missing;
        public int urls;

        public List<String> existingPaths() {
            List<String> out = new ArrayList<>();
            for (Entry e : entries) if (e.exists) out.add(e.resolvedPath);
            return out;
        }
    }

    private M3uParser() {}

    public static boolean isPlaylist(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".m3u") || n.endsWith(".m3u8");
    }

    public static Result parse(File playlist) {
        Result result = new Result();
        String text = readText(playlist);
        if (text == null) return result;

        File base = playlist.getParentFile();
        String pendingTitle = null;
        int pendingSeconds = -1;

        for (String rawLine : text.split("\r\n|\n|\r")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#")) {
                if (line.toUpperCase().startsWith("#EXTINF:")) {
                    String rest = line.substring(8);
                    int comma = rest.indexOf(',');
                    if (comma >= 0) {
                        pendingSeconds = TrackTags.parseNumber(rest.substring(0, comma)) == null
                                ? -1 : TrackTags.parseNumber(rest.substring(0, comma));
                        String t = rest.substring(comma + 1).trim();
                        pendingTitle = t.isEmpty() ? null : t;
                    }
                }
                continue;   // all other # lines are comments or directives
            }

            boolean isUrl = line.matches("(?i)^[a-z][a-z0-9+.-]*://.*");
            String resolved = null;
            boolean exists = false;

            if (isUrl) {
                result.urls++;
            } else {
                // Playlists written on Windows use backslashes.
                String p = line.replace('\\', '/');
                File f = p.startsWith("/") ? new File(p) : new File(base, p);
                try {
                    resolved = f.getCanonicalPath();
                } catch (Exception e) {
                    resolved = f.getAbsolutePath();
                }
                exists = f.isFile();
                if (!exists) result.missing++;
            }

            result.entries.add(new Entry(line, resolved, pendingTitle, pendingSeconds, isUrl, exists));
            pendingTitle = null;
            pendingSeconds = -1;
        }
        return result;
    }

    /** .m3u8 is UTF-8 by definition; plain .m3u is often UTF-8 but may be Latin-1. */
    private static String readText(File f) {
        byte[] bytes;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            bytes = out.toByteArray();
        } catch (Exception e) {
            return null;
        }
        // Strip a UTF-8 BOM if present.
        int off = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, off, bytes.length - off, StandardCharsets.ISO_8859_1);
        }
    }
}
