package org.eversolo.winamp.tags;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last-resort metadata derived from where a file sits and what it is called.
 *
 * This library is consistently organised, e.g.
 *   /storage/EF42-73B2/Leonard Cohen/[M] Old Ideas [32570025] [2012]/02 - Leonard Cohen - Amen.flac
 *   /storage/EF42-73B2/Gipsy Kings - The Real Gipsy Kings-3CD-2014 [FLAC]/CD2/01. Bem, bem, Maria.flac
 *
 * IMPORTANT: this only ever fills gaps. It must never override a real tag - see
 * TagReaders.read(), which applies it via fillGapsFrom().
 */
public final class PathTagReader implements TagReader {

    /** "CD2", "CD 2", "Disc 3", "Disk 1" - a disc folder, not an album folder. */
    private static final Pattern DISC_DIR =
            Pattern.compile("^(?:cd|disc|disk)\\s*[-_]?\\s*(\\d{1,2})$", Pattern.CASE_INSENSITIVE);

    /** "02 - Artist - Title", "02. Title", "02.-Title", "02 Title" */
    private static final Pattern TRACK_PREFIX =
            Pattern.compile("^\\s*(\\d{1,3})\\s*[-._)]*\\s*(.*)$");

    /** Bracketed junk: [32570025], [2012], [FLAC], [24-96 Vinyl] */
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private final Set<String> roots = new HashSet<>();

    /**
     * @param scanRoots the volume roots being scanned. When a file's artist folder IS a
     *                  root, the album folder is assumed to carry "Artist - Album".
     */
    public PathTagReader(Set<String> scanRoots) {
        if (scanRoots != null) {
            for (String r : scanRoots) roots.add(normalise(r));
        }
    }

    @Override
    public boolean supports(String fileName) {
        return true;   // works for anything
    }

    @Override
    public TrackTags read(File file) {
        try {
            TrackTags t = new TrackTags();
            t.source = "path";

            String base = file.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);

            // ---- filename -> track number, artist, title ----
            String rest = base;
            Matcher m = TRACK_PREFIX.matcher(base);
            if (m.matches()) {
                Integer n = TrackTags.parseNumber(m.group(1));
                String tail = m.group(2).trim();
                // Only treat it as a track number if something is actually left over,
                // otherwise a track called "1999" loses its title.
                if (n != null && !tail.isEmpty()) {
                    t.trackNumber = n;
                    rest = tail;
                }
            }

            String[] parts = rest.split("\\s+-\\s+");
            if (parts.length >= 3) {
                t.artist = parts[1].trim();
                t.title = join(parts, 2).trim();
            } else if (parts.length == 2) {
                t.artist = parts[0].trim();
                t.title = parts[1].trim();
            } else {
                t.title = rest.trim();
            }

            // ---- folders -> album, artist, disc ----
            File parent = file.getParentFile();
            if (parent == null) return t;

            File albumDir = parent;
            Matcher disc = DISC_DIR.matcher(parent.getName().trim());
            if (disc.matches()) {
                t.discNumber = TrackTags.parseNumber(disc.group(1));
                File up = parent.getParentFile();
                if (up != null) albumDir = up;
            }

            String rawAlbum = albumDir.getName();
            t.year = TrackTags.parseYear(rawAlbum);
            String album = clean(rawAlbum);

            File artistDir = albumDir.getParentFile();
            boolean artistDirIsRoot = artistDir == null
                    || roots.contains(normalise(artistDir.getAbsolutePath()));

            if (artistDirIsRoot) {
                // e.g. "Gipsy Kings - The Real Gipsy Kings-3CD-2014" sitting at the volume root
                int sep = album.indexOf(" - ");
                if (sep > 0) {
                    if (!TrackTags.notBlank(t.artist)) t.artist = album.substring(0, sep).trim();
                    t.album = album.substring(sep + 3).trim();
                } else {
                    t.album = album;
                }
            } else {
                t.album = album;
                String folderArtist = clean(artistDir.getName());
                // Prefer the folder's artist: it is more consistent than filenames.
                if (TrackTags.notBlank(folderArtist)) t.artist = folderArtist;
            }

            if (!TrackTags.notBlank(t.album)) t.album = null;
            if (!TrackTags.notBlank(t.artist)) t.artist = null;
            if (!TrackTags.notBlank(t.title)) t.title = null;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** Strips the "[M] " prefix these rips use, bracketed junk, and tidies whitespace. */
    static String clean(String s) {
        if (s == null) return null;
        String out = s.trim();
        if (out.startsWith("[M]")) out = out.substring(3);
        out = BRACKETS.matcher(out).replaceAll(" ");
        out = out.replaceAll("\\s+", " ").trim();
        out = out.replaceAll("[-_\\s]+$", "").trim();
        return out;
    }

    private static String join(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String normalise(String path) {
        if (path == null) return "";
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
