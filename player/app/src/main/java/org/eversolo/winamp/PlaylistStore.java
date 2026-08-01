package org.eversolo.winamp;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.playlist.Playlist;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the playlist across restarts.
 *
 * The device gives us nothing to store it in - the API has no queue at all, which is why
 * the app owns the playlist in the first place - so it goes in the app's own files as a
 * plain list of absolute paths. Paths are the only stable identity a track has here.
 *
 * Restoring waits for the library scan, because a path is only useful once it can be turned
 * back into a Track with its tags. Anything that has since been deleted or unplugged is
 * dropped quietly, with a count in the log.
 */
public final class PlaylistStore {

    private static final String TAG = "PlaylistStore";
    private static final String FILE = "playlist.m3u";
    private static final String CURRENT = "#CURRENT:";

    private final File file;

    public PlaylistStore(File filesDir) {
        this.file = new File(filesDir, FILE);
    }

    /** Written as an .m3u so it can be read by anything, including this app's own importer. */
    public void save(Playlist playlist) {
        List<Track> tracks = playlist.tracks();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file),
                StandardCharsets.UTF_8)) {
            w.write("#EXTM3U\n");
            w.write(CURRENT + playlist.currentIndex() + "\n");
            for (Track t : tracks) {
                w.write("#EXTINF:" + (t.durationMs / 1000) + "," + t.artist + " - "
                        + t.title + "\n");
                w.write(t.absolutePath + "\n");
            }
        } catch (Exception e) {
            Logs.w(TAG, "could not save the playlist: " + e);
            return;
        }
        Logs.i(TAG, "saved " + tracks.size() + " tracks to " + file);
    }

    /** True if anything was restored. */
    public boolean restore(Playlist playlist, MusicLibrary library) {
        if (!file.isFile()) return false;
        List<Track> found = new ArrayList<>();
        int missing = 0, current = -1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(CURRENT)) {
                    try {
                        current = Integer.parseInt(line.substring(CURRENT.length()).trim());
                    } catch (NumberFormatException ignored) { }
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#")) continue;
                Track t = library.byPath(line);
                if (t != null) found.add(t);
                else missing++;
            }
        } catch (Exception e) {
            Logs.w(TAG, "could not read the saved playlist: " + e);
            return false;
        }
        if (found.isEmpty()) {
            Logs.i(TAG, "saved playlist had nothing left in it (" + missing + " gone)");
            return false;
        }
        playlist.addAll(found);
        if (current >= 0 && current < found.size()) playlist.setCurrentIndex(current);
        Logs.i(TAG, "restored " + found.size() + " tracks"
                + (missing > 0 ? ", " + missing + " no longer in the library" : ""));
        return true;
    }

    public void clear() {
        if (file.isFile() && !file.delete()) Logs.w(TAG, "could not delete " + file);
    }
}
