package org.eversolo.winamp.library;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Finds Winamp skin archives anywhere on the given volumes.
 *
 * The first version only looked in a handful of named folders, which meant a skin one level
 * deeper than expected - `USB/Music/Winamp stuff/base.wsz` - simply did not exist as far as
 * the app was concerned, with nothing on screen to say why. For someone who has just been
 * told to "put a .wsz on a USB stick", that is the whole feature failing silently.
 *
 * So it walks properly. Cost is not the problem it sounds like: the music scanner already
 * walks about 5,000 files across the whole SSD in under a second on this hardware.
 *
 * Android-free on purpose, so the walk can be tested on a desktop JVM against a real
 * directory tree rather than against assumptions about one.
 */
public final class SkinFinder {

    /** Deep enough for anything a person would do by hand; shallow enough to stay quick. */
    public static final int MAX_DEPTH = 6;

    /** A backstop against a pathological tree, not an expected limit. */
    public static final int MAX_FILES = 60_000;

    /**
     * Directories with nothing in them for us and plenty in them to walk. `Android` in
     * particular holds every app's private data.
     */
    private static final String[] SKIP = {
            "android", "lost.dir", "system volume information", "$recycle.bin",
            ".thumbnails", ".trash", "found.000",
    };

    private SkinFinder() {}

    /** What the walk found, and how much of the disk it had to look at to find it. */
    public static final class Result {
        public final List<File> skins;
        public final int filesSeen;
        public final int foldersSeen;
        /** True if the walk hit {@link #MAX_FILES} and stopped early - so, possibly missed some. */
        public final boolean truncated;

        Result(List<File> skins, int filesSeen, int foldersSeen, boolean truncated) {
            this.skins = skins;
            this.filesSeen = filesSeen;
            this.foldersSeen = foldersSeen;
            this.truncated = truncated;
        }

        @Override public String toString() {
            return skins.size() + " skin(s) in " + foldersSeen + " folder(s), "
                    + filesSeen + " file(s) seen" + (truncated ? " (stopped early)" : "");
        }
    }

    /**
     * The biggest a file may be and still be worth opening as a skin.
     *
     * Winamp skins are a handful of small bitmaps - the classic one is 100 KB. This limit
     * exists because `.zip` is not a skin extension, it is *an* extension: the owner had a
     * 128 MB Eversolo firmware OTA package in Downloads, and the app opened it, tried to read
     * it into memory and died on startup. Extension alone is not enough evidence.
     */
    public static final long MAX_SKIN_BYTES = 20L * 1024 * 1024;

    public static boolean isSkin(File f) {
        if (f == null || !f.isFile()) return false;
        String n = f.getName().toLowerCase();
        // macOS writes these alongside the real file when copying to a stick. They are
        // metadata stubs, they carry the same name, and they are never a skin.
        if (n.startsWith("._")) return false;
        if (!n.endsWith(".wsz") && !n.endsWith(".zip")) return false;
        long len = f.length();
        return len > 0 && len <= MAX_SKIN_BYTES;
    }

    /** Breadth-first so that a skin near the top is found even if the walk is cut short. */
    public static Result find(List<File> roots) {
        List<File> skins = new ArrayList<>();
        int files = 0, folders = 0;
        boolean truncated = false;

        Deque<Entry> queue = new ArrayDeque<>();
        List<String> visited = new ArrayList<>();
        for (File r : roots) {
            if (r != null && r.isDirectory()) queue.add(new Entry(r, 0));
        }

        while (!queue.isEmpty()) {
            Entry e = queue.removeFirst();
            String path = canonical(e.dir);
            if (visited.contains(path)) continue;      // symlinked volumes point at each other
            visited.add(path);
            folders++;

            File[] kids = e.dir.listFiles();
            if (kids == null) continue;                // unreadable, which is normal and fine
            for (File k : kids) {
                if (k.isDirectory()) {
                    if (e.depth + 1 > MAX_DEPTH || skip(k.getName())) continue;
                    queue.add(new Entry(k, e.depth + 1));
                } else {
                    files++;
                    if (files > MAX_FILES) { truncated = true; break; }
                    if (isSkin(k) && !skins.contains(k)) skins.add(k);
                }
            }
            if (truncated) break;
        }

        Collections.sort(skins);
        return new Result(skins, files, folders, truncated);
    }

    private static boolean skip(String name) {
        String n = name.toLowerCase();
        if (n.startsWith(".")) return true;
        for (String s : SKIP) if (n.equals(s)) return true;
        return false;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    private static final class Entry {
        final File dir;
        final int depth;
        Entry(File dir, int depth) { this.dir = dir; this.depth = depth; }
    }
}
