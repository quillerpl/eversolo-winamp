package org.eversolo.winamp;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.tags.LrcParser;

import java.io.File;
import java.io.FileInputStream;
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

    private LyricsStore() {}

    /** The `.lrc` sitting beside an audio file, or null. */
    public static File sidecarFor(String audioPath) {
        if (audioPath == null || audioPath.isEmpty()) return null;
        int dot = audioPath.lastIndexOf('.');
        int slash = audioPath.lastIndexOf('/');
        String base = dot > slash ? audioPath.substring(0, dot) : audioPath;
        File f = new File(base + ".lrc");
        return f.isFile() ? f : null;
    }

    /**
     * Read and parse the lyrics for a track, or null when there are none.
     *
     * Never throws. A file on a disk somebody else filled is not something to trust with the
     * app's startup - that lesson cost a release.
     */
    public static LrcParser.Lyrics forTrack(String audioPath) {
        File f = sidecarFor(audioPath);
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
