package org.eversolo.winamp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.playback.EversoloHttpEngine;
import org.eversolo.winamp.playback.PlaybackEngine;
import org.eversolo.winamp.playback.PlaybackState;
import org.eversolo.winamp.playlist.Playlist;
import org.eversolo.winamp.playlist.PlaylistController;
import org.eversolo.winamp.skin.BrowserWindowView;
import org.eversolo.winamp.skin.GenGeometry;
import org.eversolo.winamp.skin.MainWindowView;
import org.eversolo.winamp.skin.PlaylistGeometry;
import org.eversolo.winamp.skin.PlaylistWindowView;
import org.eversolo.winamp.skin.Skin;
import org.eversolo.winamp.skin.SkinSprites;
import org.eversolo.winamp.tags.M3uParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The player as the user sees it: two Winamp windows and, behind a button, the library.
 *
 * The main window IS the player. PL swaps it for the playlist editor, and the playlist's
 * own X brings the player back - on a 6-inch screen there is not room for both at a size
 * where a track row can be tapped reliably, so they take turns. The library browser is a
 * third layer that covers whichever window is showing, opened by ADD FILE or by eject.
 */
public final class WinampUi implements PlaybackEngine.Listener, Playlist.Listener {

    private static final String TAG = "WinampUi";

    /** Marquee scroll rate. Winamp's own is roughly this. */
    private static final long MARQUEE_MS = 220;

    /**
     * How many tracks the playlist window should show before we stop making it bigger.
     * The window scales in whole steps, so this decides the step: on the Eversolo's screen
     * twelve rows lands on x4, which keeps a row 52 px tall - about a finger's width and
     * the same size as the rows in the library browser.
     */
    private static final int WANTED_ROWS = 12;

    private final Context ctx;
    private final Runnable onQuit;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final EversoloHttpEngine engine = new EversoloHttpEngine();
    private final Playlist playlist = new Playlist();
    private final PlaylistController controller = new PlaylistController(playlist, engine);

    private final MusicLibrary library = new MusicLibrary();

    private MainWindowView mainWindow;
    private PlaylistWindowView playlistWindow;
    private BrowserWindowView browserWindow;
    private LibraryBrowser browser;
    private FrameLayout root;

    private boolean playlistOpen = false;
    private boolean browserOpen = false;
    private boolean marqueeRunning = false;
    private boolean shuffleOn = false;
    private boolean repeatOn = false;
    private int mainScale = 4, playlistScale = 4;
    private int laidOutW, laidOutH;
    private final String version;

    public WinampUi(Context ctx, Runnable onQuit) {
        this.ctx = ctx;
        this.onQuit = onQuit;
        this.version = versionName(ctx);
        // First line in the log, so "which build is actually running?" is never a guess
        // again - the last install silently did not replace the app.
        Logs.i(TAG, "EversoloWinamp " + version + " starting");
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public View build() {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);

        Skin skin = loadSkin();
        // A first guess, so the first frame is sane; the real size arrives at layout time.
        computeScales(ctx.getResources().getDisplayMetrics().widthPixels,
                ctx.getResources().getDisplayMetrics().heightPixels);

        mainWindow = new MainWindowView(ctx);
        mainWindow.setSkin(skin);
        mainWindow.setScale(mainScale);
        mainWindow.setCallbacks(new Transport());
        root.addView(mainWindow, centred());

        playlistWindow = new PlaylistWindowView(ctx);
        playlistWindow.setSkin(skin);
        playlistWindow.setScale(playlistScale);
        playlistWindow.setGeometry(playlistGeometry());
        playlistWindow.setCallbacks(new PlaylistActions());
        playlistWindow.setVisibility(View.GONE);
        root.addView(playlistWindow, centred());

        browserWindow = new BrowserWindowView(ctx);
        browserWindow.setSkin(skin);
        browserWindow.setScale(playlistScale);
        browserWindow.setGeometry(browserGeometry());
        browserWindow.setCallbacks(new BrowserActions());
        browserWindow.setVisibility(View.GONE);
        root.addView(browserWindow, centred());

        browser = new LibraryBrowser(library, new BrowserHost());

        // Size the windows from the space we actually got, not from DisplayMetrics: the
        // overlay is 2000 px wide on a 2160 px screen, and a playlist sized for 2160 would
        // hang its scrollbar and LIST OPTS button off the right-hand edge.
        // Posted, not called inline: resizing a child from inside a layout pass is the kind
        // of thing Android quietly drops, and there is no logcat here to notice it.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            final int w = r - l, h = b - t;
            root.post(() -> fit(w, h));
        });

        playlist.addListener(this);
        engine.addListener(this);
        engine.start();
        refreshPlaylistWindow();
        return root;
    }

    private void fit(int w, int h) {
        if (w <= 0 || h <= 0 || (w == laidOutW && h == laidOutH)) return;
        laidOutW = w;
        laidOutH = h;
        computeScales(w, h);
        mainWindow.setScale(mainScale);
        playlistWindow.setScale(playlistScale);
        playlistWindow.setGeometry(playlistGeometry());
        browserWindow.setScale(playlistScale);
        browserWindow.setGeometry(browserGeometry());
    }

    /**
     * The browser wears the generic frame, which has no fixed step size - unlike the
     * playlist it can be any size at all, so it simply fills the screen at the same scale.
     */
    private GenGeometry browserGeometry() {
        int w = laidOutW > 0 ? laidOutW : ctx.getResources().getDisplayMetrics().widthPixels;
        int h = laidOutH > 0 ? laidOutH : ctx.getResources().getDisplayMetrics().heightPixels;
        return new GenGeometry(w / playlistScale, h / playlistScale);
    }

    private FrameLayout.LayoutParams centred() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    /** Start the library scan. It takes about 13 seconds, so it starts before it is needed. */
    public void startScanIfNeeded() {
        library.startScanIfNeeded();
    }

    public void destroy() {
        marqueeRunning = false;
        playlist.removeListener(this);
        engine.removeListener(this);
        engine.stop();
        if (browser != null) browser.destroy();
    }

    /** Back closes one layer at a time; from the bare main window the host quits. */
    public boolean handleBack() {
        if (browserOpen) {
            if (browser.up()) return true;      // up a folder before leaving the browser
            closeBrowser();
            return true;
        }
        if (playlistOpen) {
            closePlaylist();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- skin

    private Skin loadSkin() {
        // A user-supplied .wsz on the device wins; otherwise the bundled classic skin.
        File userSkins = new File("/storage/emulated/0/EverSoloWinamp/skins");
        if (userSkins.isDirectory()) {
            File[] found = userSkins.listFiles();
            if (found != null) {
                for (File f : found) {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".wsz") || n.endsWith(".zip")) {
                        Skin s = Skin.fromArchive(f);
                        if (s.isUsable()) return s;
                        Logs.w(TAG, "skin " + f.getName() + " is not usable, ignoring");
                    }
                }
            }
        }
        return Skin.fromAssetArchive(ctx, "skins/base-2.91.wsz");
    }

    /**
     * Whole-number scales only - these are pixel art and fractional scaling ruins them.
     *
     * The main window gets as big as the screen allows. The playlist window is sized the
     * other way round: from how many tracks should be readable at once. The largest scale
     * that still shows WANTED_ROWS wins, so on a bigger screen it simply gets bigger.
     */
    private void computeScales(int w, int h) {
        mainScale = Math.max(1, Math.min(w / SkinSprites.WINDOW_W, h / SkinSprites.WINDOW_H));

        // Largest scale that still shows WANTED_ROWS. If no scale manages it the screen is
        // tiny, and x1 - which shows the most rows - is the best on offer.
        playlistScale = 1;
        for (int s = mainScale; s >= 1; s--) {
            if (PlaylistGeometry.BASE_W * s > w) continue;
            if (PlaylistGeometry.rowsIn(PlaylistGeometry.heightFor(h, s)) >= WANTED_ROWS) {
                playlistScale = s;
                break;
            }
        }

        PlaylistGeometry g = playlistGeometry();
        Logs.i(TAG, "screen " + w + "x" + h
                + " -> main x" + mainScale + " (" + SkinSprites.WINDOW_W * mainScale
                + "x" + SkinSprites.WINDOW_H * mainScale + "), playlist x" + playlistScale
                + " (" + g.width * playlistScale + "x" + g.height * playlistScale
                + ", " + g.visibleRows() + " rows of " + PlaylistGeometry.TRACK_H * playlistScale
                + "px)");
    }

    private PlaylistGeometry playlistGeometry() {
        int w = laidOutW > 0 ? laidOutW : ctx.getResources().getDisplayMetrics().widthPixels;
        int h = laidOutH > 0 ? laidOutH : ctx.getResources().getDisplayMetrics().heightPixels;
        return new PlaylistGeometry(PlaylistGeometry.widthFor(w, playlistScale),
                PlaylistGeometry.heightFor(h, playlistScale));
    }

    // ---------------------------------------------------------------- windows

    /** Only one window is up at a time: there is no room for two at a readable size. */
    private void show(boolean main, boolean playlistWin, boolean browserWin) {
        mainWindow.setVisibility(main ? View.VISIBLE : View.GONE);
        playlistWindow.setVisibility(playlistWin ? View.VISIBLE : View.GONE);
        browserWindow.setVisibility(browserWin ? View.VISIBLE : View.GONE);
        mainWindow.setFocused(main);
        playlistWindow.setFocused(playlistWin);
    }

    private void openPlaylist() {
        playlistOpen = true;
        browserOpen = false;
        show(false, true, false);
        mainWindow.setToggles(true, false, shuffleOn, repeatOn);
        library.startScanIfNeeded();        // so ADD FILE is ready when they reach for it
        refreshPlaylistWindow();
        Logs.i(TAG, "playlist window opened");
    }

    private void closePlaylist() {
        playlistOpen = false;
        show(true, false, false);
        mainWindow.setToggles(false, false, shuffleOn, repeatOn);
        Logs.i(TAG, "playlist window closed");
    }

    private void openBrowser(int tab) {
        browserOpen = true;
        show(false, false, true);
        library.startScanIfNeeded();
        browser.openTab(tab);
        Logs.i(TAG, "library browser opened on tab " + tab);
    }

    /** Back to whichever window sent us here. */
    private void closeBrowser() {
        browserOpen = false;
        show(!playlistOpen, playlistOpen, false);
        refreshPlaylistWindow();
        Logs.i(TAG, "library browser closed");
    }

    // ---------------------------------------------------------------- state

    @Override
    public void onState(final PlaybackState s) {
        controller.onState(s);      // the playlist handover lives here
        ui.post(() -> {
            String title = s.title;
            if (!s.artist.isEmpty()) title = s.artist + " - " + title;
            // Nothing playing: say which build is on the device. It costs nothing and it
            // settles the "did the install take?" question at a glance.
            if (title.trim().isEmpty()) title = "EVERSOLO WINAMP " + version;
            mainWindow.setNowPlaying(title, s.positionMs, s.durationMs,
                    s.isPlaying(), s.status == PlaybackState.Status.PAUSED);
            mainWindow.setVolumePercent(s.maxVolume > 0 ? s.volume * 100 / s.maxVolume : 0);
            mainWindow.setToggles(playlistOpen, false, shuffleOn, repeatOn);
            showQuality(s);
            if (s.isPlaying()) startMarquee();
        });
    }

    /**
     * The kbps / kHz / mono-stereo displays.
     *
     * The device reports all of this in getState, but not for every source - so when it
     * says nothing and we are driving the playlist, fall back to the file's own tags, which
     * we parsed ourselves anyway. The bitrate then comes from size over duration, which for
     * a FLAC is the honest average.
     */
    private void showQuality(PlaybackState s) {
        int kbps = s.bitrateKbps;
        int rate = s.sampleRate;
        int channels = s.channels;

        Track t = playlist.current();
        if (t != null) {
            if (rate <= 0) rate = t.sampleRate;
            if (channels <= 0) channels = t.channels;
            if (kbps <= 0 && t.fileSize > 0 && t.durationMs > 0) {
                kbps = (int) (t.fileSize * 8 / t.durationMs);
            }
        }
        mainWindow.setQuality(kbps, rate > 0 ? Math.round(rate / 1000f) : 0, channels != 1);
    }

    @Override
    public void onPlaylistChanged() {
        ui.post(this::refreshPlaylistWindow);
    }

    /** Hand the playlist window a fresh set of rows. It knows nothing about Tracks. */
    private void refreshPlaylistWindow() {
        List<Track> items = playlist.tracks();
        List<PlaylistWindowView.Row> rows = new ArrayList<>(items.size());
        for (Track t : items) {
            String artist = t.artist == null || t.artist.isEmpty() ? "" : t.artist + " - ";
            rows.add(new PlaylistWindowView.Row(artist + t.title,
                    t.formattedDuration(), t.durationMs));
        }
        playlistWindow.setTracks(rows, playlist.currentIndex());
    }

    private void startMarquee() {
        if (marqueeRunning) return;
        marqueeRunning = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!marqueeRunning) return;
                mainWindow.tickMarquee();
                ui.postDelayed(this, MARQUEE_MS);
            }
        }, MARQUEE_MS);
    }

    private void toast(String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }

    /** What both play buttons do when no particular track was asked for. */
    private void playOrResume() {
        PlaybackState s = engine.state();
        if (s.status == PlaybackState.Status.PAUSED) {
            engine.togglePlayPause();
        } else if (!s.isPlaying()) {
            if (!playlist.isEmpty()) controller.playAt(Math.max(0, playlist.currentIndex()));
            else engine.togglePlayPause();
        }
    }

    private void pause() {
        if (engine.state().isPlaying()) engine.togglePlayPause();
    }

    /** The device has no stop command - pause is the closest honest equivalent. */
    private void stop() {
        pause();
        controller.stopDriving();
    }

    // ---------------------------------------------------------------- main window

    private final class Transport implements MainWindowView.Callbacks {
        @Override public void onPrevious() { controller.previous(); }
        @Override public void onNext() { controller.next(); }

        @Override public void onPlay() { playOrResume(); }
        @Override public void onPause() { pause(); }
        @Override public void onStop() { stop(); }

        @Override
        public void onEject() {
            // Winamp's eject opens files. Here it opens the library browser.
            openBrowser(browser.tab());
        }

        @Override
        public void onShowLog() {
            Intent i = new Intent(ctx, LogActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }

        @Override public void onSeek(float f) {
            long d = engine.state().durationMs;
            if (d > 0) engine.seekTo((long) (f * d));
        }

        @Override public void onVolume(int percent) {
            engine.setVolume(percent * 200 / 100);   // device scale is 0..200
        }

        @Override public void onTogglePlaylist() { openPlaylist(); }

        @Override public void onToggleEqualizer() {
            // The equalizer window is not built yet; the device has its own EQ.
            Logs.i(TAG, "EQ button pressed - equalizer window not implemented yet");
            toast("Equalizer window not built yet");
        }

        @Override public void onShuffle() {
            shuffleOn = !shuffleOn;
            controller.setShuffle(shuffleOn);
            mainWindow.setToggles(playlistOpen, false, shuffleOn, repeatOn);
        }

        @Override public void onRepeat() {
            repeatOn = !repeatOn;
            controller.setRepeat(repeatOn);
            mainWindow.setToggles(playlistOpen, false, shuffleOn, repeatOn);
        }

        @Override public void onClose() {
            Logs.i(TAG, "main window X - quitting");
            if (onQuit != null) onQuit.run();
        }
    }

    // ---------------------------------------------------------------- playlist window

    private final class PlaylistActions implements PlaylistWindowView.Callbacks {

        @Override public void onPlayIndex(int index) { controller.playAt(index); }

        @Override
        public void onRemove(List<Integer> indices) {
            // Backwards, so the earlier indices are still valid as we go.
            for (int i = indices.size() - 1; i >= 0; i--) playlist.removeAt(indices.get(i));
        }

        @Override
        public void onKeepOnly(List<Integer> keep) {
            if (keep.isEmpty()) { toast("Nothing selected to crop to"); return; }
            for (int i = playlist.size() - 1; i >= 0; i--) {
                if (!keep.contains(i)) playlist.removeAt(i);
            }
        }

        @Override
        public void onClearList() {
            controller.stopDriving();
            playlist.clear();
        }

        @Override
        public void onSortList() {
            playlist.sort(Playlist.byArtistThenTitle());
            toast("Sorted by artist");
        }

        @Override public void onAddFiles() { openBrowser(browser.tab()); }

        @Override public void onAddUrl() {
            // Not an omission: the device's own API refuses stream URLs (API_FINDINGS §6).
            toast("The Eversolo will not play stream URLs through this API");
        }

        @Override
        public void onFileInfo(int index) {
            Track t = playlist.get(index);
            if (t == null) { toast("Select a track first"); return; }
            toast(t.title + "\n" + t.qualityLabel() + "\n" + t.absolutePath);
        }

        @Override public void onMiscOptions() { toast("No options here yet"); }

        @Override public void onSaveList() { saveM3u(); }

        @Override public void onLoadList() { openBrowser(LibraryBrowser.TAB_M3U); }

        @Override public void onPrevious() { controller.previous(); }

        /**
         * Play what is selected. This is the button that makes single-tap selection enough
         * on a touchscreen: nothing here is reachable only by double-tapping.
         */
        @Override
        public void onPlay() {
            int chosen = playlistWindow.firstSelected();
            if (chosen >= 0) controller.playAt(chosen);
            else playOrResume();
        }

        @Override public void onPause() { pause(); }
        @Override public void onStop() { stop(); }
        @Override public void onNext() { controller.next(); }

        @Override public void onClose() { closePlaylist(); }

        @Override public void onFocused() {
            playlistWindow.setFocused(true);
            mainWindow.setFocused(false);
        }
    }

    // ---------------------------------------------------------------- browser window

    /** What the browser window reports back. Nothing here starts playback. */
    private final class BrowserActions implements BrowserWindowView.Callbacks {
        @Override public void onOpen(int index) { browser.open(index); }
        @Override public void onUp() { browser.up(); }
        @Override public void onTab(int tab) { browser.openTab(tab); }
        @Override public void onAdd(List<Integer> selected) { browser.add(selected); }
        @Override public void onClose() { closeBrowser(); }
        @Override public void onFocused() { }
    }

    /** What the browser model needs from the app: the playlist, and somewhere to shout. */
    private final class BrowserHost implements LibraryBrowser.Host {
        @Override
        public void onAddTracks(List<Track> tracks, String what) {
            if (tracks.isEmpty()) { toast("Nothing to add"); return; }
            playlist.addAll(tracks);
            toast("Added " + tracks.size() + " track" + (tracks.size() == 1 ? "" : "s"));
        }

        @Override public void onImportM3u(File file) { importM3u(file); }

        @Override
        public void onRowsChanged(String title, String where,
                                  List<BrowserWindowView.Row> rows, int tab) {
            browserWindow.setRows(title, where, rows, tab);
        }

        @Override public void onStatus(String status) { browserWindow.setStatus(status); }
    }

    /**
     * Import an .m3u. The device accepts these files and silently does nothing with them
     * (decision D6), so we resolve every line against the library ourselves - and say what
     * was dropped rather than quietly importing fewer tracks than the file listed.
     */
    private void importM3u(final File f) {
        new Thread(() -> {
            final M3uParser.Result r = M3uParser.parse(f);
            final List<Track> resolved = new ArrayList<>();
            int notInLibrary = 0;
            for (String path : r.existingPaths()) {
                Track t = library.byPath(path);
                if (t != null) resolved.add(t);
                else notInLibrary++;
            }
            final int missing = r.missing, urls = r.urls, unknown = notInLibrary;
            Logs.i(TAG, "m3u " + f.getName() + ": " + r.entries.size() + " entries, "
                    + resolved.size() + " added, " + missing + " missing, "
                    + urls + " urls, " + unknown + " not in library");
            ui.post(() -> {
                playlist.addAll(resolved);
                StringBuilder sb = new StringBuilder("Added " + resolved.size());
                if (missing > 0) sb.append(", ").append(missing).append(" missing");
                if (urls > 0) sb.append(", ").append(urls).append(" web links skipped");
                if (unknown > 0) sb.append(", ").append(unknown).append(" not in library");
                toast(sb.toString());
            });
        }, "m3u-import").start();
    }

    /**
     * SAVE LIST writes a .m3u next to the skins folder, where the app's own m3u importer
     * will find it again. Paths are absolute, so it survives being moved.
     */
    private void saveM3u() {
        if (playlist.isEmpty()) { toast("Playlist is empty"); return; }
        File dir = new File("/storage/emulated/0/EverSoloWinamp/playlists");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Logs.w(TAG, "could not create " + dir);
            toast("Could not create " + dir);
            return;
        }
        File out = new File(dir, "winamp-" + System.currentTimeMillis() + ".m3u");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out),
                StandardCharsets.UTF_8)) {
            w.write("#EXTM3U\n");
            for (Track t : playlist.tracks()) {
                w.write("#EXTINF:" + (t.durationMs / 1000) + "," + t.artist + " - "
                        + t.title + "\n");
                w.write(t.absolutePath + "\n");
            }
            Logs.i(TAG, "saved playlist to " + out);
            toast("Saved " + out.getName());
        } catch (Exception e) {
            Logs.e(TAG, "could not save playlist", e);
            toast("Could not save: " + e);
        }
    }
}
