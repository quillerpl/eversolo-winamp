package org.eversolo.winamp;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.tags.LrcParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Finds the words for a track.
 *
 * Only sidecar `.lrc` files, on purpose. `fetch-lyrics.py` fills the library over the network
 * from a Mac, where the job can be watched, retried and undone; the player's half is then
 * small enough to be obviously correct on a device that has no debugger.
 *
 * Embedded `LYRICS` tags are deliberately ignored for now. Two thirds of this library carries
 * them and not one is timed - measured, 300 files sampled - so they can fill a panel but can
 * never move the highlight. Worth adding as a fallback; not worth pretending it is the feature.
 */
public final class LyricsStore {

    private static final String TAG = "Lyrics";

    /** No sane .lrc is bigger than this; anything that is, is not lyrics. */
    private static final long MAX_BYTES = 1024 * 1024;

    /**
     * Where lyrics go when they cannot go beside the track.
     *
     * The music lives on a removable volume, and Android 11 does not necessarily let an app
     * write to one even with legacy storage. Beside the track is much better - portable, and
     * every other player reads it - so that is tried first and this is the fallback rather
     * than the plan.
     */
    public static final String FALLBACK_DIR = "/storage/emulated/0/EverSoloWinamp/lyrics";

    private LyricsStore() {}

    /** Where a sidecar would live, whether or not it is there. */
    public static File sidecarPath(String audioPath) {
        if (audioPath == null || audioPath.isEmpty()) return null;
        int dot = audioPath.lastIndexOf('.');
        int slash = audioPath.lastIndexOf('/');
        String base = dot > slash ? audioPath.substring(0, dot) : audioPath;
        return new File(base + ".lrc");
    }

    /** A name derived from the path, so two tracks called the same thing cannot collide. */
    private static String keyFor(String audioPath) {
        return Integer.toHexString(audioPath.hashCode()) + ".lrc";
    }

    /** The shared fallback, on the primary volume. Needs write permission. */
    public static File fallbackPath(String audioPath) {
        if (audioPath == null || audioPath.isEmpty()) return null;
        return new File(FALLBACK_DIR, keyFor(audioPath));
    }

    /** The last resort, inside the app itself, where no permission is needed at all. */
    public static File privatePath(File privateDir, String audioPath) {
        if (privateDir == null || audioPath == null || audioPath.isEmpty()) return null;
        return new File(new File(privateDir, "lyrics"), keyFor(audioPath));
    }

    /** The `.lrc` for a track, wherever it managed to land. Null if there is none. */
    public static File sidecarFor(File privateDir, String audioPath) {
        for (File f : new File[]{sidecarPath(audioPath), fallbackPath(audioPath),
                                 privatePath(privateDir, audioPath)}) {
            if (f != null && f.isFile()) return f;
        }
        return null;
    }

    /**
     * Write lyrics for a track. Returns where they landed, for telling the user - "saved next
     * to the song" and "saved in the app's folder" are different promises and it should not
     * claim the first when it did the second.
     */
    public static String save(File privateDir, String audioPath, String lrc) {
        if (lrc == null || lrc.isEmpty()) return null;
        String body = lrc.endsWith("\n") ? lrc : lrc + "\n";

        File beside = sidecarPath(audioPath);
        if (beside != null && write(beside, body)) {
            Logs.i(TAG, "saved beside the track: " + beside);
            return "Saved next to the song";
        }
        File shared = fallbackPath(audioPath);
        if (shared != null && write(shared, body)) {
            Logs.i(TAG, "music volume not writable; saved to " + shared);
            return "Saved in EverSoloWinamp - the music drive is read-only";
        }
        // Inside the app's own directory. No permission governs this one, so if it fails the
        // problem is the disk, not Android.
        File mine = privatePath(privateDir, audioPath);
        if (mine != null && write(mine, body)) {
            Logs.i(TAG, "saved inside the app: " + mine);
            return "Saved inside the app - storage is read-only";
        }
        Logs.w(TAG, "could not save lyrics anywhere for " + audioPath);
        return null;
    }

    private static boolean write(File f, String body) {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            Logs.i(TAG, "cannot create " + dir);
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            Logs.i(TAG, "cannot write " + f + ": " + t);
            return false;
        }
    }

    /**
     * Read and parse the lyrics for a track, or null when there are none.
     *
     * Never throws. A file on a disk somebody else filled is not something to trust with the
     * app's startup - that lesson cost a release.
     */
    public static LrcParser.Lyrics forTrack(File privateDir, String audioPath) {
        File f = sidecarFor(privateDir, audioPath);
        if (f == null) return null;
        try {
            if (f.length() <= 0 || f.length() > MAX_BYTES) {
                Logs.w(TAG, "ignoring an implausible .lrc of " + f.length() + " bytes: " + f);
                return null;
            }
            byte[] data = readAll(f);
            LrcParser.Lyrics l = LrcParser.parse(new String(data, StandardCharsets.UTF_8));
            Logs.i(TAG, "loaded " + l.lines.size() + " line(s)"
                    + (l.synced ? " with timings" : " without timings") + " from " + f.getName());
            return l.isEmpty() ? null : l;
        } catch (Throwable t) {
            Logs.w(TAG, "could not read " + f + ": " + t);
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
