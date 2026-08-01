package org.eversolo.winamp.playlist;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.playback.EversoloHttpEngine;
import org.eversolo.winamp.playback.PlaybackState;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays the app's playlist in the user's order, one track at a time, through the device.
 *
 * The delicate part is the handover at the end of each track. Two measured facts make it
 * workable:
 *
 *   Q2 - setLoopMode?loop=1 is repeat-one, and it genuinely stops the device advancing on
 *        its own. Without it the device rolls into the next file in the FOLDER, which for
 *        a cross-folder playlist is the wrong track and actively fights us. With it, being
 *        late merely repeats the current track - recoverable, and we have a whole track
 *        length to notice.
 *   Q3 - openFile becomes audible in about 0.2 s, and faster over loopback.
 *
 * So: aim to fire slightly BEFORE the end (clipping a fraction of a second of what is
 * nearly always a fade tail), and keep a wrap detector as the safety net for when a stall
 * makes us late.
 */
public final class PlaylistController {

    private static final String TAG = "Sequencer";

    /**
     * How far before the end to start the next track. Derived from the ~0.2 s measured
     * over Wi-Fi (Q3); loopback is quicker, so this is deliberately generous. Tune on the
     * device - it is the one number here that is a judgement call rather than a fact.
     */
    private static final long LEAD_MS = 400;

    /** A backwards jump larger than this means the track restarted, i.e. repeat-one fired. */
    private static final long WRAP_JUMP_MS = 3000;

    public interface Listener {
        void onTrackStarted(int index, Track track);
        void onPlaylistFinished();
        void onTrackFailed(int index, Track track);
    }

    private final Playlist playlist;
    private final EversoloHttpEngine engine;
    private Listener listener;

    private volatile boolean driving = false;
    private volatile boolean advancing = false;
    private long lastPosition = 0;

    /** Winamp semantics: shuffle changes the ORDER OF PLAY, it does not reorder the list. */
    private volatile boolean shuffle = false;
    /** Repeat the whole playlist once it reaches the end. */
    private volatile boolean repeat = false;
    private final Set<Integer> playedInShuffle = new HashSet<>();
    private final Random random = new Random();

    /**
     * Start requests are serialised and superseded.
     *
     * Previously each request spawned its own thread. Two taps in quick succession - or a
     * tap landing on a row while an earlier start was still confirming - meant two threads
     * both calling openFile and both waiting for the device to agree, which produced a
     * burst of tracks each playing for a fraction of a second. One worker, and stale
     * requests bail out when a newer one has been issued.
     */
    private final ExecutorService starter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playlist-start");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();

    public PlaylistController(Playlist playlist, EversoloHttpEngine engine) {
        this.playlist = playlist;
        this.engine = engine;
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setShuffle(boolean on) {
        shuffle = on;
        playedInShuffle.clear();
        Logs.i(TAG, "shuffle " + (on ? "on" : "off"));
    }

    public void setRepeat(boolean on) {
        repeat = on;
        Logs.i(TAG, "repeat " + (on ? "on" : "off"));
    }

    public boolean isShuffle() { return shuffle; }
    public boolean isRepeat() { return repeat; }

    /**
     * Which track plays after {@code from}. Returns -1 when the playlist is finished.
     *
     * With shuffle on, picks randomly among tracks not yet played this pass, so every
     * track is heard once before any repeats - which is what Winamp does, and what people
     * actually expect from "shuffle".
     */
    private int nextIndex(int from) {
        int size = playlist.size();
        if (size == 0) return -1;

        if (shuffle) {
            playedInShuffle.add(from);
            if (playedInShuffle.size() >= size) {
                playedInShuffle.clear();
                if (!repeat) return -1;
            }
            for (int attempt = 0; attempt < 200; attempt++) {
                int candidate = random.nextInt(size);
                if (!playedInShuffle.contains(candidate)) return candidate;
            }
            for (int i = 0; i < size; i++) {
                if (!playedInShuffle.contains(i)) return i;
            }
            return repeat ? random.nextInt(size) : -1;
        }

        int next = from + 1;
        if (next >= size) return repeat ? 0 : -1;
        return next;
    }

    public boolean isDriving() { return driving; }

    /** Start the playlist at a given index. */
    public void playAt(final int index) {
        final Track t = playlist.get(index);
        if (t == null) return;
        driving = true;
        advancing = false;      // an explicit user request always wins over a pending advance
        playedInShuffle.clear();
        // Stop the device wandering into the next file in the folder by itself.
        engine.setRepeatOne(true);
        playlist.setCurrentIndex(index);
        startTrack(index, t);
    }

    /** Hand control back to the device (used when the user plays straight from the library). */
    public void stopDriving() {
        if (!driving) return;
        driving = false;
        engine.setRepeatOne(false);
        Logs.i(TAG, "stopped driving; repeat-one off");
    }

    public void next() {
        if (!driving) { engine.next(); return; }
        advanceTo(nextIndex(playlist.currentIndex()), "manual next");
    }

    public void previous() {
        if (!driving) { engine.previous(); return; }
        int i = playlist.currentIndex() - 1;
        if (i < 0) i = 0;
        advanceTo(i, "manual previous");
    }

    /** Called from the playback state poller. */
    public void onState(PlaybackState s) {
        if (!driving || s == null) return;

        long pos = s.positionMs;
        long dur = s.durationMs;

        // Safety net: the track restarted, so repeat-one fired and we were late.
        boolean wrapped = lastPosition > WRAP_JUMP_MS && pos + WRAP_JUMP_MS < lastPosition;
        lastPosition = pos;

        if (wrapped && !advancing) {
            Logs.w(TAG, "track wrapped - handover was late, advancing now");
            advanceTo(nextIndex(playlist.currentIndex()), "wrap");
            return;
        }

        if (dur > 0 && pos > 0 && !advancing) {
            long remaining = dur - pos;
            if (remaining <= LEAD_MS) {
                advanceTo(nextIndex(playlist.currentIndex()), "end of track (" + remaining + "ms left)");
            }
        }
    }

    private void advanceTo(final int index, final String why) {
        if (advancing) return;

        // Guard: the playing track may have been removed from the playlist, which leaves
        // the current index at "none". Advancing from that used to compute -1 + 1 = 0 and
        // start track one out of nowhere. Stop driving instead and leave playback alone.
        if (index < 0) {
            Logs.i(TAG, "playlist finished (" + why + ")");
            driving = false;
            engine.setRepeatOne(false);
            if (listener != null) listener.onPlaylistFinished();
            return;
        }

        if (index == 0 && playlist.currentIndex() < 0) {
            Logs.i(TAG, "current track is no longer in the playlist; stopping driving (" + why + ")");
            stopDriving();
            return;
        }

        final Track t = playlist.get(index);
        if (t == null) {
            Logs.i(TAG, "playlist finished (" + why + ")");
            driving = false;
            engine.setRepeatOne(false);
            if (listener != null) listener.onPlaylistFinished();
            return;
        }
        advancing = true;
        Logs.i(TAG, "advance -> [" + index + "] " + t.title + "  (" + why + ")");
        playlist.setCurrentIndex(index);
        startTrack(index, t);
    }

    private void startTrack(final int index, final Track t) {
        final int gen = generation.incrementAndGet();
        starter.execute(() -> {
            if (gen != generation.get()) {
                Logs.i(TAG, "start superseded before it began: " + t.title);
                return;
            }
            boolean ok = false;
            try {
                ok = engine.play(t.absolutePath, t.title);
            } catch (Throwable e) {
                Logs.e(TAG, "play threw", e);
            }
            if (gen != generation.get()) {
                // A newer request arrived while we were confirming; its result wins.
                Logs.i(TAG, "start superseded during confirm: " + t.title);
                return;
            }
            lastPosition = 0;
            advancing = false;
            if (ok) {
                if (listener != null) listener.onTrackStarted(index, t);
            } else {
                // openFile answers 200 even for files it silently refuses to play, so this
                // is a genuine failure confirmed against getState. Skip rather than stall.
                Logs.w(TAG, "could not play [" + index + "] " + t.absolutePath + " - skipping");
                if (listener != null) listener.onTrackFailed(index, t);
                if (driving) advanceTo(nextIndex(index), "previous track failed");
            }
        });
    }
}
