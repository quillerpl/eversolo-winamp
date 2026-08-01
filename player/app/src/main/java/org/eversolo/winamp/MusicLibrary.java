package org.eversolo.winamp;

import android.os.Handler;
import android.os.Looper;

import org.eversolo.winamp.core.LogShipper;
import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.library.LibraryScanner;
import org.eversolo.winamp.library.MusicIndex;
import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.library.VolumeDiscovery;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scanned library, held once and shared by whatever wants to browse it.
 *
 * This used to live inside the old browser screen, which meant the scan and the interface
 * could not be changed independently. It is a plain model now: it scans on a background
 * thread, reports progress, and answers questions.
 *
 * The scan is 570 ms of walking plus about 12 s of reading tags on this device, and there
 * is no disk cache yet, so it happens once per run and never again.
 */
public final class MusicLibrary {

    private static final String TAG = "Library";

    public interface Listener {
        /** Progress while scanning, and the final summary. */
        void onLibraryStatus(String status);
        void onLibraryReady();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Track> byPath = new HashMap<>();

    private MusicIndex index;
    private List<File> roots = new ArrayList<>();
    private List<File> m3u = new ArrayList<>();
    private String status = "";
    private boolean started = false;

    public void addListener(Listener l) {
        listeners.add(l);
        if (!status.isEmpty()) l.onLibraryStatus(status);
        if (index != null) l.onLibraryReady();
    }

    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isReady() { return index != null; }
    public String status() { return status; }
    public MusicIndex index() { return index; }
    public List<File> roots() { return roots; }
    public List<File> m3uFiles() { return m3u; }
    public Track byPath(String path) { return byPath.get(path); }

    /** Every album, across every artist, in artist order. */
    public List<MusicIndex.Album> allAlbums() {
        List<MusicIndex.Album> out = new ArrayList<>();
        if (index == null) return out;
        for (String artist : index.artists()) out.addAll(index.albumsOf(artist));
        return out;
    }

    public void startScanIfNeeded() {
        if (started) return;
        started = true;
        setStatus("Scanning...");
        new Thread(() -> {
            try {
                List<File> found = VolumeDiscovery.findRoots();
                if (found.isEmpty()) {
                    setStatus("No readable music volumes found.");
                    return;
                }
                LibraryScanner.Result r = LibraryScanner.scan(found, (files, tagged, where) ->
                        setStatus(tagged == 0
                                ? "Walking... " + files + " files"
                                : "Reading tags... " + tagged + " / " + files));
                final MusicIndex idx = new MusicIndex(r.tracks);
                for (Track t : r.tracks) byPath.put(t.absolutePath, t);
                Logs.i(TAG, "index: " + idx.artists().size() + " artists, "
                        + idx.albumCount() + " albums, " + r.tracks.size() + " tracks, "
                        + "walk " + r.walkMs + "ms, tags " + r.tagMs + "ms");
                ui.post(() -> {
                    index = idx;
                    roots = found;
                    m3u = r.playlists;
                    status = idx.artists().size() + " artists, " + idx.albumCount()
                            + " albums, " + r.tracks.size() + " tracks";
                    for (Listener l : new ArrayList<>(listeners)) {
                        l.onLibraryStatus(status);
                        l.onLibraryReady();
                    }
                });
                LogShipper.shipBuffer();
            } catch (Throwable t) {
                Logs.e(TAG, "scan failed", t);
                setStatus("Scan failed: " + t);
                LogShipper.shipBuffer();
            }
        }, "library-scan").start();
    }

    private void setStatus(String s) {
        ui.post(() -> {
            status = s;
            for (Listener l : new ArrayList<>(listeners)) l.onLibraryStatus(s);
        });
    }

    // ---------------------------------------------------------------- folders

    private static final String[] AUDIO = {
            ".flac", ".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus",
            ".ape", ".wv", ".dsf", ".dff", ".aiff", ".aif", ".alac",
    };

    public static boolean isAudio(File f) {
        String n = f.getName().toLowerCase();
        for (String ext : AUDIO) if (n.endsWith(ext)) return true;
        return false;
    }

    /** Directories first, then audio files, each in name order - as a file manager would. */
    public static List<File> listFolder(File dir) {
        List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        File[] found = dir.listFiles();
        if (found != null) {
            for (File f : found) {
                if (f.isDirectory() && !f.getName().startsWith(".")) dirs.add(f);
                else if (f.isFile() && isAudio(f)) files.add(f);
            }
        }
        Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        dirs.addAll(files);
        return dirs;
    }
}
