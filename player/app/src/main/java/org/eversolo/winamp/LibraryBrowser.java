package org.eversolo.winamp;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.library.MusicIndex;
import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.skin.BrowserWindowView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What the browser window is looking at, and what tapping things does.
 *
 * Four ways in - by artist, by album, by folder, or by .m3u file - because a library that
 * was ripped over twenty years is not consistently tagged, and the folder tree is sometimes
 * the only thing that makes sense. Winamp's own browser had the same escape hatch.
 *
 * Nothing here plays anything. The only outcome is tracks going into the playlist.
 */
public final class LibraryBrowser implements MusicLibrary.Listener {

    private static final String TAG = "Browser";

    public static final int TAB_ARTIST = 0;
    public static final int TAB_ALBUM = 1;
    public static final int TAB_FOLDER = 2;
    public static final int TAB_M3U = 3;

    public interface Host {
        void onAddTracks(List<Track> tracks, String what);
        void onImportM3u(File file);
        void onRowsChanged(String title, String where, List<BrowserWindowView.Row> rows, int tab);
        void onStatus(String status);
    }

    private final MusicLibrary library;
    private final Host host;

    private int tab = TAB_ARTIST;
    private String artist;                 // set while inside an artist
    private MusicIndex.Album album;        // set while inside an album
    private File folder;                   // set while inside a folder
    private List<File> folderItems = new ArrayList<>();

    public LibraryBrowser(MusicLibrary library, Host host) {
        this.library = library;
        this.host = host;
        library.addListener(this);
    }

    public void destroy() { library.removeListener(this); }

    public int tab() { return tab; }

    @Override public void onLibraryStatus(String status) { host.onStatus(status); }

    @Override public void onLibraryReady() { refresh(); }

    public void openTab(int which) {
        tab = which;
        artist = null;
        album = null;
        folder = null;
        refresh();
    }

    /** Where LOAD LIST lands. */
    public void openM3uTab() { openTab(TAB_M3U); }

    public void refresh() {
        if (!library.isReady()) {
            host.onRowsChanged(title(), "", new ArrayList<>(), tab);
            host.onStatus(library.status());
            return;
        }
        host.onRowsChanged(title(), where(), buildRows(), tab);
    }

    /** The gen.bmp title alphabet is capitals only, so these stay plain words. */
    private String title() {
        switch (tab) {
            case TAB_ALBUM:  return "ALBUMS";
            case TAB_FOLDER: return "FOLDERS";
            case TAB_M3U:    return "PLAYLISTS";
            default:         return "ARTISTS";
        }
    }

    /** The "up one level" line, empty at the top of a tab. */
    private String where() {
        if (tab == TAB_ARTIST) {
            if (album != null) return artist + "  -  " + album.name;
            if (artist != null) return artist;
        } else if (tab == TAB_ALBUM) {
            if (album != null) return album.artist + "  -  " + album.name;
        } else if (tab == TAB_FOLDER) {
            if (folder != null) return folder.getAbsolutePath();
        }
        return "";
    }

    private List<BrowserWindowView.Row> buildRows() {
        List<BrowserWindowView.Row> rows = new ArrayList<>();
        MusicIndex index = library.index();
        switch (tab) {
            case TAB_ARTIST:
                if (album != null) {
                    addTrackRows(rows, album.tracks);
                } else if (artist != null) {
                    for (MusicIndex.Album a : index.albumsOf(artist)) {
                        rows.add(new BrowserWindowView.Row(a.name,
                                a.tracks.size() + " tracks", true));
                    }
                } else {
                    for (String name : index.artists()) {
                        int tracks = 0;
                        for (MusicIndex.Album a : index.albumsOf(name)) tracks += a.tracks.size();
                        rows.add(new BrowserWindowView.Row(name, tracks + " tracks", true));
                    }
                }
                break;
            case TAB_ALBUM:
                if (album != null) {
                    addTrackRows(rows, album.tracks);
                } else {
                    for (MusicIndex.Album a : library.allAlbums()) {
                        rows.add(new BrowserWindowView.Row(a.artist + "  -  " + a.name,
                                a.tracks.size() + " tracks", true));
                    }
                }
                break;
            case TAB_FOLDER:
                folderItems = folder == null ? library.roots() : MusicLibrary.listFolder(folder);
                for (File f : folderItems) {
                    boolean dir = f.isDirectory();
                    Track t = dir ? null : library.byPath(f.getAbsolutePath());
                    rows.add(new BrowserWindowView.Row(
                            folder == null ? f.getAbsolutePath() : f.getName(),
                            dir ? "" : (t == null ? "" : t.formattedDuration()), dir));
                }
                break;
            case TAB_M3U:
                for (File f : library.m3uFiles()) {
                    rows.add(new BrowserWindowView.Row(f.getName(), f.getParent(), false));
                }
                break;
            default:
                break;
        }
        return rows;
    }

    private void addTrackRows(List<BrowserWindowView.Row> rows, List<Track> tracks) {
        for (Track t : tracks) {
            String num = t.trackNumber == null ? "" : String.format("%2d. ", t.trackNumber);
            rows.add(new BrowserWindowView.Row(num + t.title, t.formattedDuration(), false));
        }
    }

    // ---------------------------------------------------------------- navigation

    public void open(int index) {
        MusicIndex idx = library.index();
        if (idx == null) return;
        switch (tab) {
            case TAB_ARTIST:
                if (artist == null) {
                    if (index < idx.artists().size()) artist = idx.artists().get(index);
                } else if (album == null) {
                    List<MusicIndex.Album> albums = idx.albumsOf(artist);
                    if (index < albums.size()) album = albums.get(index);
                }
                break;
            case TAB_ALBUM:
                if (album == null) {
                    List<MusicIndex.Album> albums = library.allAlbums();
                    if (index < albums.size()) album = albums.get(index);
                }
                break;
            case TAB_FOLDER:
                if (index < folderItems.size() && folderItems.get(index).isDirectory()) {
                    folder = folderItems.get(index);
                }
                break;
            default:
                break;
        }
        refresh();
    }

    /** True if there was somewhere to go. */
    public boolean up() {
        switch (tab) {
            case TAB_ARTIST:
                if (album != null) { album = null; refresh(); return true; }
                if (artist != null) { artist = null; refresh(); return true; }
                return false;
            case TAB_ALBUM:
                if (album != null) { album = null; refresh(); return true; }
                return false;
            case TAB_FOLDER:
                if (folder == null) return false;
                File parent = folder.getParentFile();
                // Stop at the volume roots rather than wandering up into /storage.
                folder = (parent == null || isRoot(folder)) ? null : parent;
                refresh();
                return true;
            default:
                return false;
        }
    }

    private boolean isRoot(File f) {
        for (File r : library.roots()) {
            if (r.getAbsolutePath().equals(f.getAbsolutePath())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- adding

    /**
     * ADD with rows selected adds those; ADD with nothing selected adds everything listed.
     * That second rule is what makes "add this whole album" one tap rather than thirteen.
     */
    public void add(List<Integer> selected) {
        if (tab == TAB_M3U) {
            List<File> files = library.m3uFiles();
            List<Integer> which = selected.isEmpty() ? allIndices(files.size()) : selected;
            for (int i : which) {
                if (i < files.size()) host.onImportM3u(files.get(i));
            }
            return;
        }
        List<Track> tracks = new ArrayList<>();
        String what;
        if (selected.isEmpty()) {
            tracks.addAll(everythingHere());
            what = "everything here";
        } else {
            for (int i : selected) {
                Track t = trackAt(i);
                if (t != null) tracks.add(t);
            }
            what = tracks.size() + " selected";
        }
        Logs.i(TAG, "add: " + what + " -> " + tracks.size() + " tracks");
        host.onAddTracks(tracks, what);
    }

    private List<Integer> allIndices(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(i);
        return out;
    }

    /** The track a row stands for, or null when the row is a container. */
    private Track trackAt(int index) {
        switch (tab) {
            case TAB_ARTIST:
            case TAB_ALBUM:
                if (album != null && index < album.tracks.size()) return album.tracks.get(index);
                return null;
            case TAB_FOLDER:
                if (index < folderItems.size()) {
                    File f = folderItems.get(index);
                    if (!f.isDirectory()) return library.byPath(f.getAbsolutePath());
                }
                return null;
            default:
                return null;
        }
    }

    /** Everything below where we are standing, however deep. */
    private List<Track> everythingHere() {
        List<Track> out = new ArrayList<>();
        MusicIndex idx = library.index();
        if (idx == null) return out;
        switch (tab) {
            case TAB_ARTIST:
                if (album != null) out.addAll(album.tracks);
                else if (artist != null) {
                    for (MusicIndex.Album a : idx.albumsOf(artist)) out.addAll(a.tracks);
                } else {
                    out.addAll(idx.allTracks());
                }
                break;
            case TAB_ALBUM:
                if (album != null) out.addAll(album.tracks);
                else out.addAll(idx.allTracks());
                break;
            case TAB_FOLDER:
                for (File f : folderItems) {
                    if (f.isDirectory()) addFolderTracks(f, out);
                    else {
                        Track t = library.byPath(f.getAbsolutePath());
                        if (t != null) out.add(t);
                    }
                }
                break;
            default:
                break;
        }
        return out;
    }

    /** Recurses, so adding a folder adds the discs inside it too. */
    private void addFolderTracks(File dir, List<Track> out) {
        if (out.size() > 5000) return;         // a runaway guard, not a real limit
        for (File f : MusicLibrary.listFolder(dir)) {
            if (f.isDirectory()) addFolderTracks(f, out);
            else {
                Track t = library.byPath(f.getAbsolutePath());
                if (t != null) out.add(t);
            }
        }
    }
}
