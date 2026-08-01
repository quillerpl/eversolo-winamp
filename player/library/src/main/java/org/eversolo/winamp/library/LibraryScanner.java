package org.eversolo.winamp.library;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.tags.M3uParser;
import org.eversolo.winamp.tags.TagReaders;
import org.eversolo.winamp.tags.TrackTags;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Walks the volumes and reads tags. Run this off the UI thread.
 *
 * Directory walking alone is very fast on this device - 5,037 files in 366 ms measured
 * during feasibility testing. Reading tags means opening every file, which is the part
 * worth watching; the result carries timings for both so we can see it rather than guess.
 */
public final class LibraryScanner {

    private static final String TAG = "Scanner";
    private static final int MAX_DEPTH = 12;

    public interface Progress {
        /** Called periodically from the scanning thread. */
        void onProgress(int filesFound, int tagsRead, String currentDir);
    }

    public static final class Result {
        public final List<Track> tracks = new ArrayList<>();
        public int directoriesSeen;
        public int audioFilesSeen;
        public int unreadableDirs;
        public long walkMs;
        public long tagMs;
        public int withoutDuration;
        /** .m3u / .m3u8 files found while walking, for the playlist importer. */
        public final List<File> playlists = new ArrayList<>();
        public final Map<String, Integer> byExtension = new TreeMap<>();
        public final Map<String, Integer> byTagSource = new TreeMap<>();

        public long totalMs() { return walkMs + tagMs; }
    }

    private LibraryScanner() {}

    public static Result scan(List<File> roots, Progress progress) {
        Result r = new Result();

        // ---- pass 1: walk ----
        long t0 = System.currentTimeMillis();
        List<File> audioFiles = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (File root : roots) {
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(new Node(root, 0, true));

            while (!stack.isEmpty()) {
                Node node = stack.pop();
                if (node.depth > MAX_DEPTH) continue;

                String canonical = safeCanonical(node.dir);
                if (!visited.add(canonical)) continue;   // guards against symlink loops

                File[] children = node.dir.listFiles();
                if (children == null) {
                    r.unreadableDirs++;
                    continue;
                }
                r.directoriesSeen++;

                for (File c : children) {
                    String name = c.getName();
                    if (name.startsWith(".")) continue;              // hidden / macOS sidecars
                    if (c.isDirectory()) {
                        if (node.isRoot && VolumeDiscovery.skipAtRoot(name)) continue;
                        stack.push(new Node(c, node.depth + 1, false));
                    } else if (TagReaders.isAudioFile(name)) {
                        audioFiles.add(c);
                    } else if (M3uParser.isPlaylist(name)) {
                        r.playlists.add(c);
                    }
                }

                if (progress != null && r.directoriesSeen % 25 == 0) {
                    progress.onProgress(audioFiles.size(), 0, node.dir.getName());
                }
            }
        }
        r.walkMs = System.currentTimeMillis() - t0;
        r.audioFilesSeen = audioFiles.size();
        if (!r.playlists.isEmpty()) Logs.i(TAG, "found " + r.playlists.size() + " .m3u playlist file(s)");
        Logs.i(TAG, "walk: " + r.audioFilesSeen + " audio files in "
                + r.directoriesSeen + " dirs in " + r.walkMs + " ms"
                + (r.unreadableDirs > 0 ? " (" + r.unreadableDirs + " unreadable)" : ""));

        // ---- pass 2: tags ----
        Set<String> rootPaths = new HashSet<>();
        for (File root : roots) rootPaths.add(root.getAbsolutePath());
        // Artwork is skipped here on purpose: it is large and only needed when something
        // is actually displayed. Fetch it on demand with a reader constructed with true.
        TagReaders readers = new TagReaders(rootPaths, false);

        long t1 = System.currentTimeMillis();
        int n = 0;
        for (File f : audioFiles) {
            TrackTags tags = readers.read(f);
            Track track = new Track(f, tags);
            r.tracks.add(track);

            bump(r.byExtension, track.extension);
            bump(r.byTagSource, tags.source == null ? "none" : tags.source);
            if (track.durationMs <= 0) r.withoutDuration++;

            n++;
            if (progress != null && n % 100 == 0) {
                progress.onProgress(r.audioFilesSeen, n, track.artist);
            }
        }
        r.tagMs = System.currentTimeMillis() - t1;

        Logs.i(TAG, "tags: " + r.tracks.size() + " read in " + r.tagMs + " ms"
                + " (" + (r.tracks.isEmpty() ? 0 : r.tagMs * 1000 / Math.max(1, r.tracks.size())) + " us/file)");
        Logs.i(TAG, "by extension: " + r.byExtension);
        Logs.i(TAG, "by tag source: " + r.byTagSource);
        if (r.withoutDuration > 0) {
            Logs.i(TAG, r.withoutDuration + " tracks have no duration "
                    + "(expected for MP3 - ID3 does not carry it)");
        }
        return r;
    }

    private static void bump(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static String safeCanonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    private static final class Node {
        final File dir; final int depth; final boolean isRoot;
        Node(File dir, int depth, boolean isRoot) {
            this.dir = dir; this.depth = depth; this.isRoot = isRoot;
        }
    }
}
