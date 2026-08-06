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
import org.eversolo.winamp.skin.LyricsWindowView;
import org.eversolo.winamp.tags.LrcParser;
import org.eversolo.winamp.skin.MainWindowView;
import org.eversolo.winamp.skin.PlaylistGeometry;
import org.eversolo.winamp.skin.PlaylistWindowView;
import org.eversolo.winamp.skin.Skin;
import org.eversolo.winamp.skin.SkinSprites;
import org.eversolo.winamp.skin.WindowScales;
import org.eversolo.winamp.skin.ZoomChooser;
import org.eversolo.winamp.tags.M3uParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private static final String PREFS = "winamp";
    private static final String PREF_ZOOM = "zoom";
    private static final String PREF_VIS = "visualiser";
    private static final String PREF_REMAINING = "timeRemaining";
    private static final String PREF_FULLSCREEN = "fullScreen";
    private static final String PREF_OVERSIZE = "mainOversize";

    /** Visualiser animation frame. The device is polled slower; this smooths between. */
    private static final long VIS_FRAME_MS = 40;

    /** How long a full-screen verdict stays worth re-showing when the main window returns. */
    private static final long VERDICT_WORTH_REPEATING_MS = 120_000;

    private final Context ctx;
    private final Runnable onQuit;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final EversoloHttpEngine engine = new EversoloHttpEngine();
    private final Playlist playlist = new Playlist();
    private final PlaylistController controller = new PlaylistController(playlist, engine);

    private final MusicLibrary library = new MusicLibrary();
    private FileSpectrum spectrum;
    private PlaylistStore store;

    private MainWindowView mainWindow;
    private PlaylistWindowView playlistWindow;
    private BrowserWindowView browserWindow;
    private BrowserWindowView skinWindow;
    private LyricsWindowView lyricsWindow;
    private SkinStore skins;
    private List<File> skinFiles = new ArrayList<>();
    private LibraryBrowser browser;
    private FrameLayout root;
    private FullScreen fullScreen;

    private boolean playlistOpen = false;
    private boolean browserOpen = false;
    private boolean skinsOpen = false;
    private boolean lyricsOpen = false;
    private LrcParser.Lyrics lyrics;
    /** Null rather than empty, so the very first look always runs even with no track. */
    private String lyricsForPath = null;
    /** The last position the device reported, and when we heard it, so we can interpolate. */
    private long posMs, posHeardAt;
    private boolean lyricsTicking = false;
    private final LyricsFetcher lyricsFetcher = new LyricsFetcher();
    private boolean lyricsSearching = false;
    private boolean marqueeRunning = false;
    private boolean shuffleOn = false;
    private boolean repeatOn = false;
    private int mainScale = 4, playlistScale = 4;
    private int laidOutW, laidOutH;
    /**
     * The width the two list windows may use. The same as the window when the side bar is
     * hidden, and 160 px narrower while it is showing - the lists shrink out from under it
     * rather than being covered, because you are touching the screen the whole time you are
     * in one, which is exactly when the bar is up.
     */
    private int listW;
    private boolean fullScreenActive;
    private String appliedLayout = "";
    private int zoom = 0;                   // index into ZoomChooser.LEVELS
    private boolean visualiserOn = true;
    private boolean showFileName = false;
    private boolean spectrumRunning = false;
    private volatile String spectrumProblem;
    private boolean visAnimating = false;
    private boolean restoredPlaylist = false;
    private boolean fullScreenOn = false;
    private boolean oversizeOn = false;
    private String fullScreenVerdict;
    private long fullScreenVerdictAt;
    private final Runnable savePlaylist = new Runnable() {
        @Override public void run() { if (store != null) store.save(playlist); }
    };
    private final String version;

    public WinampUi(Context ctx, Runnable onQuit) {
        this.ctx = ctx;
        this.onQuit = onQuit;
        this.version = versionName(ctx);
        this.zoom = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(PREF_ZOOM, 0);
        this.visualiserOn = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_VIS, true);
        this.fullScreenOn = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_FULLSCREEN, false);
        this.oversizeOn = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_OVERSIZE, false);
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
        laidOutW = ctx.getResources().getDisplayMetrics().widthPixels;
        laidOutH = ctx.getResources().getDisplayMetrics().heightPixels;
        listW = laidOutW;
        computeScales();

        mainWindow = new MainWindowView(ctx);
        mainWindow.setSkin(skin);
        mainWindow.setScale(mainScale);
        mainWindow.setCallbacks(new Transport());
        mainWindow.setVisualiserOn(visualiserOn);
        mainWindow.setShowRemaining(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_REMAINING, false));
        root.addView(mainWindow, centred());

        playlistWindow = new PlaylistWindowView(ctx);
        playlistWindow.setSkin(skin);
        playlistWindow.setScale(playlistScale);
        playlistWindow.setGeometry(playlistGeometry());
        playlistWindow.setCallbacks(new PlaylistActions());
        playlistWindow.setZoom(zoom);
        playlistWindow.setVisibility(View.GONE);
        root.addView(playlistWindow, centred());

        browserWindow = new BrowserWindowView(ctx);
        browserWindow.setSkin(skin);
        browserWindow.setScale(playlistScale);
        browserWindow.setGeometry(browserGeometry());
        browserWindow.setCallbacks(new BrowserActions());
        browserWindow.setZoom(zoom);
        browserWindow.setVisibility(View.GONE);
        root.addView(browserWindow, centred());

        // The skin chooser is the same window wearing different words. Everything that makes
        // the browser work - the frame, the scrolling, the options fly-out - is identical, so
        // a second window differing only in its labels would be waste.
        skinWindow = new BrowserWindowView(ctx);
        skinWindow.setSkin(skin);
        skinWindow.setScale(playlistScale);
        skinWindow.setGeometry(browserGeometry());
        skinWindow.setChrome(new String[]{"SKINS"}, new String[]{"CLOSE", "RESCAN", "OPTIONS"});
        skinWindow.setCallbacks(new SkinActions());
        skinWindow.setZoom(zoom);
        skinWindow.setVisibility(View.GONE);
        root.addView(skinWindow, centred());

        lyricsWindow = new LyricsWindowView(ctx);
        lyricsWindow.setSkin(skin);
        lyricsWindow.setScale(playlistScale);
        lyricsWindow.setGeometry(browserGeometry());
        lyricsWindow.setCallbacks(new LyricsWindowView.Callbacks() {
            @Override public void onClose() { closeLyrics(); }
            @Override public void onFocused() { }
            @Override public void onSearch() { searchForLyrics(); }
        });
        lyricsWindow.setVisibility(View.GONE);
        root.addView(lyricsWindow, centred());

        // The analyser decodes the playing file itself: the device serves no spectrum
        // (isHasSpectrum is false on every source) but the samples are in the file, and we
        // know which file because we asked for it to be played.
        spectrum = new FileSpectrum(new FileSpectrum.Listener() {
            @Override public void onBands(float[] bands) {
                ui.post(() -> mainWindow.setSpectrum(bands));
            }
            @Override public void onProblem(String problem) {
                spectrumProblem = problem;
            }
        });

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

        // The playlist outlives the app: the device has no queue to keep it in, so it is
        // written to our own files and read back once the library scan can resolve it.
        store = new PlaylistStore(ctx.getFilesDir());
        library.addListener(new MusicLibrary.Listener() {
            @Override public void onLibraryStatus(String status) { }
            @Override public void onLibraryReady() {
                if (restoredPlaylist) return;
                restoredPlaylist = true;
                if (store.restore(playlist, library)) {
                    mainWindow.flashTitle("PLAYLIST RESTORED - " + playlist.size() + " TRACKS");
                }
            }
        });

        playlist.addListener(this);
        engine.addListener(this);
        engine.start();
        refreshPlaylistWindow();
        return root;
    }

    /**
     * Handed the overlay's full-screen controller once the window exists. Both scrolling
     * windows show the switch, so both are told where it currently stands.
     */
    public void attachFullScreen(FullScreen fs) {
        this.fullScreen = fs;
        playlistWindow.setFullScreen(fullScreenOn);
        browserWindow.setFullScreen(fullScreenOn);
        skinWindow.setFullScreen(fullScreenOn);
        playlistWindow.setOversize(oversizeOn);
        browserWindow.setOversize(oversizeOn);
        skinWindow.setOversize(oversizeOn);
        fs.setReport(this::report);
        fs.setSpace(this::onUsableWidth);
        fs.setEnabled(fullScreenOn);
    }

    /**
     * The verdict arrives about two and a half seconds after the button is tapped, by which
     * time the user may have closed the window they tapped it in. So it goes to both windows
     * that can show a message, and it is kept for the next time the main window comes up -
     * this is the one message in the app that answers a question the user actually asked.
     */
    private void report(String line) {
        ui.post(() -> {
            fullScreenVerdict = line;
            fullScreenVerdictAt = System.currentTimeMillis();
            mainWindow.flashTitle(line);
            browserWindow.flash(line);
        });
    }

    /** Re-show the verdict if the main window came back while it was still worth reading. */
    private void replayVerdict() {
        if (fullScreenVerdict == null) return;
        if (System.currentTimeMillis() - fullScreenVerdictAt > VERDICT_WORTH_REPEATING_MS) {
            fullScreenVerdict = null;
            return;
        }
        mainWindow.flashTitle(fullScreenVerdict);
        fullScreenVerdict = null;
    }

    /**
     * MAIN x8, from either window. Draws the main window one whole scale larger than fits
     * and lets the screen crop it, so the cost can be looked at rather than argued about.
     * The amount lost is reported, because that is the whole question.
     */
    private void setOversize(boolean on) {
        oversizeOn = on;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_OVERSIZE, on).apply();
        playlistWindow.setOversize(on);
        browserWindow.setOversize(on);
        skinWindow.setOversize(on);
        relayout();
        int crop = WindowScales.cropPerSide(laidOutW, mainScale);
        report("MAIN x" + mainScale + " - " + (crop > 0
                ? "CROPS " + crop + "PX EACH SIDE" : "FITS, NO CROP"));
        Logs.i(TAG, "main window oversize " + (on ? "on" : "off") + ": x" + mainScale
                + " = " + (SkinSprites.WINDOW_W * mainScale) + "px in " + laidOutW
                + "px, " + crop + "px off each side");
    }

    /** FULLSCR, from either window. Applies to both, and is remembered. */
    private void setFullScreen(boolean on) {
        fullScreenOn = on;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_FULLSCREEN, on).apply();
        playlistWindow.setFullScreen(on);
        browserWindow.setFullScreen(on);
        skinWindow.setFullScreen(on);
        if (fullScreen != null) fullScreen.setEnabled(on);
        Logs.i(TAG, "full screen " + (on ? "on" : "off"));
    }

    /** The zoom chooser, from either window. Applies to both, and is remembered. */
    private void setZoom(int level) {
        zoom = Math.max(0, Math.min(ZoomChooser.LEVELS.length - 1, level));
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(PREF_ZOOM, zoom).apply();
        playlistWindow.setZoom(zoom);
        browserWindow.setZoom(zoom);
        skinWindow.setZoom(zoom);
        relayout();
        Logs.i(TAG, "zoom x" + ZoomChooser.LABELS[zoom] + " -> scale x" + playlistScale);
    }

    private void relayout() {
        appliedLayout = "";
        applyLayout();
    }

    private void fit(int w, int h) {
        if (w <= 0 || h <= 0) return;
        laidOutW = w;
        laidOutH = h;
        applyLayout();
    }

    /**
     * How much room the app really has. Called by {@link FullScreen} as the side bar comes
     * and goes: the window itself stays pinned so nothing jumps, but the lists are laid out
     * inside whatever is not currently underneath the bar.
     */
    private void onUsableWidth(int usablePx, boolean fsActive) {
        listW = usablePx;
        fullScreenActive = fsActive;
        applyLayout();
    }

    /**
     * Everything that depends on the space available, in one place, and skipped entirely
     * when nothing that matters has changed - this runs on every side-bar transition.
     */
    private void applyLayout() {
        if (laidOutW <= 0 || laidOutH <= 0) return;
        if (listW <= 0 || listW > laidOutW) listW = laidOutW;

        String key = laidOutW + "x" + laidOutH + " list=" + listW + " fs=" + fullScreenActive
                + " big=" + oversizeOn + " zoom=" + zoom;
        if (key.equals(appliedLayout)) return;
        appliedLayout = key;

        computeScales();
        mainWindow.setScale(mainScale);
        mainWindow.setTranslationX(mainNudge());
        playlistWindow.setScale(playlistScale);
        playlistWindow.setGeometry(playlistGeometry());
        browserWindow.setScale(playlistScale);
        browserWindow.setGeometry(browserGeometry());
        skinWindow.setScale(playlistScale);
        skinWindow.setGeometry(browserGeometry());
        lyricsWindow.setScale(playlistScale);
        lyricsWindow.setGeometry(browserGeometry());
        // Sizing the lists to the usable width is only half of staying clear of the bar:
        // they are centred in the whole window, so they also have to slide over.
        float shift = WindowScales.centreShift(laidOutW, listW);
        playlistWindow.setTranslationX(shift);
        browserWindow.setTranslationX(shift);
        skinWindow.setTranslationX(shift);
        lyricsWindow.setTranslationX(shift);
    }

    /**
     * A one-skin-pixel shove to the left, and only when the main window is wider than the
     * screen. Centring an oversized window crops it evenly in screen pixels, but the two
     * edges of main.bmp are not the same drawing, so an even crop does not read as even.
     * The owner looked at it on the device and called it; this is that call.
     */
    private float mainNudge() {
        boolean oversized = SkinSprites.WINDOW_W * mainScale > laidOutW;
        return oversized ? -mainScale : 0f;
    }

    /**
     * The browser wears the generic frame, which has no fixed step size - unlike the
     * playlist it can be any size at all, so it simply fills the screen at the same scale.
     */
    private GenGeometry browserGeometry() {
        int w = listW > 0 ? listW : ctx.getResources().getDisplayMetrics().widthPixels;
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
        // Save synchronously here: this is the app being closed, and a debounced write
        // that has not fired yet would be lost.
        ui.removeCallbacks(savePlaylist);
        if (store != null) store.save(playlist);
        marqueeRunning = false;
        spectrumRunning = false;
        if (spectrum != null) spectrum.stop();
        playlist.removeListener(this);
        engine.removeListener(this);
        engine.stop();
        if (browser != null) browser.destroy();
    }

    /** Back closes one layer at a time; from the bare main window the host quits. */
    public boolean handleBack() {
        if (lyricsOpen) {
            closeLyrics();
            return true;
        }
        if (skinsOpen) {
            closeSkins();
            return true;
        }
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
        if (skins == null) skins = new SkinStore(ctx);
        return skins.load();
    }

    /**
     * SONG/FILE in the options fly-out: the title strip shows the tags or the file name.
     *
     * This used to be what tapping the title did. It lost that seat to the lyrics, which is
     * the right trade - one is a thing you do constantly, the other is a preference you set
     * once - but it is still worth having, so it moved rather than went.
     */
    private void toggleTitleMode() {
        showFileName = !showFileName;
        mainWindow.setNowPlaying(nowPlayingText(engine.state()),
                engine.state().positionMs, engine.state().durationMs,
                engine.state().isPlaying(),
                engine.state().status == PlaybackState.Status.PAUSED);
        Logs.i(TAG, "title shows " + (showFileName ? "the file name" : "the tags"));
    }

    // ---------------------------------------------------------------- lyrics

    /** LYRICS in the options fly-out. */
    private void openLyrics() {
        lyricsOpen = true;
        show(false, false, false, false, true);
        loadLyricsIfTrackChanged();
        startLyricsTicking(engine.state().isPlaying());
        Logs.i(TAG, "lyrics window opened");
    }

    private void closeLyrics() {
        lyricsOpen = false;
        show(!playlistOpen && !browserOpen, playlistOpen, browserOpen, false, false);
        Logs.i(TAG, "lyrics window closed");
    }

    /** Only re-read when the track actually changes; this is called on every poll. */
    private void loadLyricsIfTrackChanged() {
        Track t = playlist.current();
        String path = t == null ? "" : t.absolutePath;
        if (path.equals(lyricsForPath)) return;   // same track; this runs on every poll
        lyricsForPath = path;
        lyrics = path.isEmpty() ? null : LyricsStore.forTrack(ctx.getFilesDir(), path);

        showLyrics(t, lyrics);
    }

    /** Put whatever we have on screen, and say what is missing when something is. */
    private void showLyrics(Track t, LrcParser.Lyrics l) {
        lyrics = l;
        String heading = t == null ? "LYRICS" : t.title;
        if (l == null) {
            lyricsWindow.setLines(heading, new ArrayList<String>(), false);
            lyricsWindow.setStatus("No lyrics for this track");
            lyricsWindow.setSearchOffered(t != null);
            return;
        }
        lyricsWindow.setSearchOffered(false);
        List<String> text = new ArrayList<>(l.lines.size());
        for (LrcParser.Line line : l.lines) text.add(line.text);
        lyricsWindow.setLines(heading, text, l.synced);
        if (!l.synced) {
            lyricsWindow.setStatus("These lyrics have no timings, so nothing is highlighted");
        }
    }

    /**
     * SEARCH: look this one track up and keep what comes back.
     *
     * One track, on demand. The library sweep is `fetch-lyrics.py` on a laptop, where it can
     * be watched and resumed; this is for the album added last week.
     */
    private void searchForLyrics() {
        if (lyricsSearching) return;
        final Track t = playlist.current();
        if (t == null) return;
        lyricsSearching = true;
        lyricsWindow.setSearchOffered(false);
        lyricsWindow.setStatus("Searching for lyrics...");

        long duration = engine.state().durationMs;
        if (duration <= 0) duration = t.durationMs;
        final String path = t.absolutePath;
        lyricsFetcher.fetch(t.artist, t.title, t.album, duration, (lrc, message) -> {
            lyricsSearching = false;
            if (lrc == null) {
                lyricsWindow.setStatus(message == null ? "Nothing found" : message);
                lyricsWindow.setSearchOffered(true);
                Logs.i(TAG, "lyrics search found nothing for " + path);
                return;
            }
            String where = LyricsStore.save(ctx.getFilesDir(), path, lrc);
            if (where != null) {
                lyricsForPath = null;               // re-read from the file we just wrote
                loadLyricsIfTrackChanged();
                lyricsWindow.flash(where);
            } else {
                // Could not write anywhere. Show them anyway - they were found, and failing
                // to save is no reason to withhold the words for the song playing right now.
                showLyrics(t, LrcParser.parse(lrc));
                lyricsWindow.flash("Found them, but could not save the file");
                Logs.w(TAG, "found lyrics but could not save for " + path);
            }
        });
    }

    /**
     * Move the highlight between polls.
     *
     * getState arrives about twice a second, and a highlight that only moved on a 500 ms grid
     * would read as a slideshow rather than as following the singer. So the last reported
     * position is carried forward with the local clock and corrected whenever a real one
     * lands - the same trick the visualiser already uses.
     */
    private void startLyricsTicking(boolean playing) {
        if (!playing || lyricsTicking) return;
        lyricsTicking = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!lyricsOpen || !engine.state().isPlaying()) { lyricsTicking = false; return; }
                if (lyrics != null && lyrics.synced) {
                    long now = posMs + (android.os.SystemClock.elapsedRealtime() - posHeardAt);
                    lyricsWindow.setCurrent(lyrics.indexAt(now));
                }
                ui.postDelayed(this, VIS_FRAME_MS);
            }
        }, VIS_FRAME_MS);
    }

    // ---------------------------------------------------------------- the skin chooser

    /** Opened from the Winamp logo in the main window's bottom-right corner. */
    private void openSkins() {
        skinsOpen = true;
        show(false, false, false, true);
        refreshSkinList(false);
        Logs.i(TAG, "skin chooser opened");
    }

    private void closeSkins() {
        skinsOpen = false;
        show(!playlistOpen && !browserOpen, playlistOpen, browserOpen, false);
        Logs.i(TAG, "skin chooser closed");
    }

    private void refreshSkinList(boolean rescanned) {
        if (skins == null) skins = new SkinStore(ctx);
        skinFiles = skins.findAll();
        File current = skins.chosen();
        List<BrowserWindowView.Row> rows = new ArrayList<>();
        for (File f : skinFiles) {
            boolean inUse = current != null && current.equals(f);
            // container=true so a single tap fires onOpen and puts the skin on. Choosing a
            // skin is the sort of thing you want to see happen, not confirm.
            rows.add(new BrowserWindowView.Row(
                    f.getName(), f.getParentFile() == null ? "" : f.getParentFile().getName(),
                    true, inUse ? BrowserWindowView.ALL_ADDED : BrowserWindowView.NOT_ADDED));
        }
        skinWindow.setRows("CHOOSE A SKIN", skinFiles.size() + " found", rows, 0, false);
        if (skinFiles.isEmpty()) {
            skinWindow.setStatus("No skins found. Put a .wsz in " + SkinStore.HOME
                    + " or on a USB stick, then RESCAN.");
        } else if (rescanned) {
            skinWindow.flash("Found " + skinFiles.size());
        }
    }

    /** Apply a skin now, to every window, and remember it. */
    private void useSkin(int index) {
        if (index < 0 || index >= skinFiles.size()) return;
        File f = skinFiles.get(index);
        Skin s = Skin.fromArchive(f);
        if (s == null || !s.isUsable()) {
            skinWindow.flash("That file is not a usable skin");
            Logs.w(TAG, "rejected skin " + f);
            return;
        }
        skins.choose(f);
        mainWindow.setSkin(s);
        playlistWindow.setSkin(s);
        browserWindow.setSkin(s);
        skinWindow.setSkin(s);
        relayout();
        refreshSkinList(false);
        skinWindow.flash("Using " + f.getName());
        Logs.i(TAG, "skin applied: " + f);
    }

    /**
     * Whole-number scales only - these are pixel art and fractional scaling ruins them.
     *
     * The main window gets as big as the screen allows. The playlist window is sized the
     * other way round: from how many tracks should be readable at once. The largest scale
     * that still shows WANTED_ROWS wins, so on a bigger screen it simply gets bigger.
     */
    private void computeScales() {
        final int w = laidOutW, h = laidOutH;
        // The main window takes the whole window and lets the bar float over its edge; the
        // lists keep out of the bar's way. Oversize is only offered once full screen is
        // actually working - at 2000px wide, x8 would crop 100px off each side.
        mainScale = (oversizeOn && fullScreenActive)
                ? WindowScales.mainOversized(w, h) : WindowScales.main(w, h);
        // The zoom setting multiplies the natural scale. Whole numbers only - these are
        // pixel art - so x1.5 of a x4 window means drawing it at x6: same window, bigger
        // everything, fewer rows.
        playlistScale = WindowScales.zoomed(listW, h, WANTED_ROWS, ZoomChooser.LEVELS[zoom]);

        PlaylistGeometry g = playlistGeometry();
        Logs.i(TAG, "screen " + w + "x" + h + " (lists " + listW + ")"
                + " -> main x" + mainScale + " (" + SkinSprites.WINDOW_W * mainScale
                + "x" + SkinSprites.WINDOW_H * mainScale + "), playlist x" + playlistScale
                + " (" + g.width * playlistScale + "x" + g.height * playlistScale
                + ", " + g.visibleRows() + " rows of " + PlaylistGeometry.TRACK_H * playlistScale
                + "px)");
    }

    private PlaylistGeometry playlistGeometry() {
        int w = listW > 0 ? listW : ctx.getResources().getDisplayMetrics().widthPixels;
        int h = laidOutH > 0 ? laidOutH : ctx.getResources().getDisplayMetrics().heightPixels;
        return new PlaylistGeometry(PlaylistGeometry.widthFor(w, playlistScale),
                PlaylistGeometry.heightFor(h, playlistScale));
    }

    // ---------------------------------------------------------------- windows

    /** Only one window is up at a time: there is no room for two at a readable size. */
    private void show(boolean main, boolean playlistWin, boolean browserWin, boolean skinWin) {
        show(main, playlistWin, browserWin, skinWin, false);
    }

    private void show(boolean main, boolean playlistWin, boolean browserWin,
                      boolean skinWin, boolean lyricsWin) {
        lyricsWindow.setVisibility(lyricsWin ? View.VISIBLE : View.GONE);
        mainWindow.setVisibility(main ? View.VISIBLE : View.GONE);
        playlistWindow.setVisibility(playlistWin ? View.VISIBLE : View.GONE);
        browserWindow.setVisibility(browserWin ? View.VISIBLE : View.GONE);
        skinWindow.setVisibility(skinWin ? View.VISIBLE : View.GONE);
        mainWindow.setFocused(main);
        playlistWindow.setFocused(playlistWin);
        updateVisualiser();
        if (main) replayVerdict();
    }

    private void openPlaylist() {
        playlistOpen = true;
        browserOpen = false;
        show(false, true, false, false);
        mainWindow.setToggles(true, false, shuffleOn, repeatOn);
        library.startScanIfNeeded();        // so ADD FILE is ready when they reach for it
        refreshPlaylistWindow();
        Logs.i(TAG, "playlist window opened");
    }

    private void closePlaylist() {
        playlistOpen = false;
        show(true, false, false, false);
        mainWindow.setToggles(false, false, shuffleOn, repeatOn);
        Logs.i(TAG, "playlist window closed");
    }

    private void openBrowser(int tab) {
        browserOpen = true;
        show(false, false, true, false);
        library.startScanIfNeeded();
        browser.openTab(tab);
        Logs.i(TAG, "library browser opened on tab " + tab);
    }

    /** Back to whichever window sent us here. */
    private void closeBrowser() {
        browserOpen = false;
        show(!playlistOpen, playlistOpen, false, false);
        refreshPlaylistWindow();
        Logs.i(TAG, "library browser closed");
    }

    // ---------------------------------------------------------------- state

    @Override
    public void onState(final PlaybackState s) {
        controller.onState(s);      // the playlist handover lives here
        ui.post(() -> {
            String title = nowPlayingText(s);
            mainWindow.setNowPlaying(title, s.positionMs, s.durationMs,
                    s.isPlaying(), s.status == PlaybackState.Status.PAUSED);
            // Remember when we heard this, so the lyric highlight can run between polls.
            posMs = s.positionMs;
            posHeardAt = android.os.SystemClock.elapsedRealtime();
            if (lyricsOpen) { loadLyricsIfTrackChanged(); startLyricsTicking(s.isPlaying()); }
            mainWindow.setVolumePercent(s.maxVolume > 0 ? s.volume * 100 / s.maxVolume : 0);
            mainWindow.setToggles(playlistOpen, false, shuffleOn, repeatOn);
            showQuality(s);
            updateVisualiser();
            if (s.isPlaying()) startMarquee();
        });
    }

    /**
     * What the title strip says: song, album and artist, or the file name when the user has
     * tapped it over.
     *
     * The tags come from our own parsing where we have them - the whole reason the tag
     * readers exist is that Android cannot read this device's FLACs - and fall back to what
     * the device reports for anything we did not start ourselves.
     */
    private String nowPlayingText(PlaybackState s) {
        Track t = playlist.current();
        if (showFileName) {
            if (t != null) return t.fileName;
            if (!s.title.isEmpty()) return s.title;
        }
        StringBuilder sb = new StringBuilder();
        if (t != null) {
            sb.append(t.title);
            if (!t.album.isEmpty()) sb.append(" - ").append(t.album);
            if (!t.artist.isEmpty()) sb.append(" - ").append(t.artist);
        } else {
            sb.append(s.title);
            if (!s.album.isEmpty()) sb.append(" - ").append(s.album);
            if (!s.artist.isEmpty()) sb.append(" - ").append(s.artist);
        }
        String out = sb.toString().trim();
        while (out.startsWith("-")) out = out.substring(1).trim();
        // Nothing playing: say which build is on the device. It costs nothing and it
        // settles the "did the install take?" question at a glance.
        return out.isEmpty() ? "EVERSOLO WINAMP " + version : out;
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
        ui.post(() -> {
            refreshPlaylistWindow();
            // The browser marks rows that are already in the playlist, so it has to be told
            // when the playlist changes - keeping its scroll position, since this usually
            // happens because the user just pressed ADD and is still looking at the list.
            // Debounced, because adding a whole artist fires this once per album.
            ui.removeCallbacks(savePlaylist);
            ui.postDelayed(savePlaylist, 1500);

            Set<String> paths = new HashSet<>();
            for (Track t : playlist.tracks()) paths.add(t.absolutePath);
            browser.setPlaylistPaths(paths);
            if (browserOpen) browser.refresh(true);
        });
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

    // ---------------------------------------------------------------- visualiser

    /**
     * The spectrum analyser only runs when it can be seen and there is something to show:
     * the main window on top, playback going, and the user not having switched it off. It
     * costs five HTTP requests a second, so it should not run in the background.
     */
    /**
     * Keep the analyser fed while it can be seen.
     *
     * The source is the file, not the device: decode the same track a second time, in this
     * app, and do our own FFT. It only works for tracks this app started, because that is
     * when we know the path - getState does not report one.
     */
    private void updateVisualiser() {
        boolean wanted = visualiserOn && !playlistOpen && !browserOpen && !skinsOpen
                && !lyricsOpen
                && engine.state().isPlaying();
        Track playing = playlist.current();

        if (!wanted || playing == null) {
            if (spectrumRunning) {
                spectrumRunning = false;
                spectrum.stop();
                mainWindow.setSpectrum(new float[19]);
            }
            if (wanted && playing == null) {
                spectrumProblem = "only tracks started from here can be analysed";
            }
            return;
        }
        if (!spectrumRunning) {
            spectrumRunning = true;
            spectrumProblem = null;
        }
        spectrum.play(playing.absolutePath, engine.state().positionMs);
        spectrum.syncTo(engine.state().positionMs);
        startVisAnimation();
    }

    /** Eases the bars between the frames the device gives us, and lets the peaks fall. */
    private void startVisAnimation() {
        if (visAnimating) return;
        visAnimating = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!spectrumRunning) { visAnimating = false; return; }
                mainWindow.tickVisualiser();
                ui.postDelayed(this, VIS_FRAME_MS);
            }
        }, VIS_FRAME_MS);
    }

    private void setVisualiser(boolean on) {
        visualiserOn = on;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_VIS, on).apply();
        mainWindow.setVisualiserOn(on);
        updateVisualiser();
        Logs.i(TAG, "visualiser " + (on ? "on" : "off"));
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
            if (d <= 0) return;
            long target = (long) (f * d);
            // Tell the sequencer first. A backwards drag from the last seconds of a track
            // is the same shape as repeat-one wrapping, and the device reports the old
            // position for a poll or two afterwards - which used to skip the track.
            controller.onSeek(target);
            engine.seekTo(target);
            if (spectrum != null) spectrum.syncTo(target);
        }

        @Override public void onVolume(int percent) {
            engine.setVolume(percent * 200 / 100);   // device scale is 0..200
        }

        @Override public void onTogglePlaylist() { openPlaylist(); }

        /**
         * There is no equalizer to open.
         *
         * The device's API has no tone control of any kind - no EQ, no filters, nothing -
         * and this unit reports hasDspSetting=false. A Winamp EQ window here would be ten
         * sliders wired to nothing, and worse, it would promise the one thing this player
         * exists to avoid: the whole point is that the signal reaches the DACs untouched.
         * So the button says so rather than opening a decoration.
         */
        /**
         * No equalizer, and nothing behind this button. The API has no tone control of any
         * kind, the unit reports hasDspSetting=false, and a working EQ would undo the point
         * of the player. The light show that briefly lived here was removed: with no audio
         * data it could only move on a timer, which is a screensaver, not a visualiser.
         */
        @Override public void onToggleEqualizer() {
            mainWindow.flashTitle("NO EQUALISER - THIS PLAYER STAYS BIT PERFECT");
        }

        /** Winamp's clock did exactly this: click it to count down instead of up. */
        @Override public void onToggleTimeMode() {
            boolean remaining = !mainWindow.isShowRemaining();
            mainWindow.setShowRemaining(remaining);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_REMAINING, remaining).apply();
            Logs.i(TAG, "clock shows " + (remaining ? "time remaining" : "time elapsed"));
        }

        @Override public void onLogo() { openSkins(); }

        /** The song title. Big, obvious, already about the song: the place to find the words. */
        @Override public void onToggleTitleMode() {
            if (lyricsOpen) closeLyrics(); else openLyrics();
        }



        @Override public void onToggleVisualiser() {
            boolean on = !mainWindow.isVisualiserOn();
            setVisualiser(on);
            // If the feed is broken, say so here: the analyser window is 16 px tall and
            // has nowhere to put a message of its own.
            String problem = spectrumProblem;
            mainWindow.flashTitle(!on ? "ANALYSER OFF"
                    : problem == null ? "ANALYSER ON"
                    : "ANALYSER ON - " + problem.toUpperCase(java.util.Locale.UK));
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
            if (store != null) store.clear();
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

        @Override public void onZoom(int level) { setZoom(level); }

        @Override public void onFullScreen(boolean on) { setFullScreen(on); }

        @Override public void onOversize(boolean on) { setOversize(on); }

        @Override public void onTitleMode() { toggleTitleMode(); }
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
        @Override public void onZoom(int level) { setZoom(level); }

        @Override public void onFullScreen(boolean on) { setFullScreen(on); }

        @Override public void onOversize(boolean on) { setOversize(on); }

        @Override public void onTitleMode() { toggleTitleMode(); }
    }

    /** The same window as the browser, pointed at skin files instead of music. */
    private final class SkinActions implements BrowserWindowView.Callbacks {
        @Override public void onOpen(int index) { useSkin(index); }
        @Override public void onUp() { }
        @Override public void onTab(int tab) { }
        @Override public void onAdd(List<Integer> selected) { refreshSkinList(true); }
        @Override public void onClose() { closeSkins(); }
        @Override public void onFocused() { }
        @Override public void onZoom(int level) { setZoom(level); }
        @Override public void onFullScreen(boolean on) { setFullScreen(on); }
        @Override public void onOversize(boolean on) { setOversize(on); }

        @Override public void onTitleMode() { toggleTitleMode(); }
    }

    /** What the browser model needs from the app: the playlist, and somewhere to shout. */
    private final class BrowserHost implements LibraryBrowser.Host {
        /**
         * The message goes on the browser's own bottom bar, not into a Toast: this window
         * is an overlay drawn above everything, and a Toast behind it is no feedback at
         * all. The rows also gain their "already added" mark on the next refresh.
         */
        @Override
        public void onAddTracks(List<Track> tracks, String what) {
            if (tracks.isEmpty()) { browserWindow.flash("Nothing to add"); return; }
            playlist.addAll(tracks);
            // Short on purpose: at x2 zoom this line has about a hundred pixels to live in.
            browserWindow.flash("Added " + tracks.size() + " (" + playlist.size() + " in list)");
        }

        @Override public void onImportM3u(File file) { importM3u(file); }

        @Override
        public void onRowsChanged(String title, String where,
                                  List<BrowserWindowView.Row> rows, int tab, boolean keepView) {
            browserWindow.setRows(title, where, rows, tab, keepView);
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
                // In the window, not a Toast: this is an overlay drawn above everything, so
                // a Toast renders behind it and is no feedback at all. An import that
                // resolved nothing looked exactly like nothing happening.
                if (browserOpen) browserWindow.flash(sb.toString());
                else mainWindow.flashTitle(sb.toString());
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
