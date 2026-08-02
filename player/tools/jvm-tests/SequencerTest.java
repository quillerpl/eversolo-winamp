import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.playback.PlaybackEngine;
import org.eversolo.winamp.playback.PlaybackState;
import org.eversolo.winamp.playlist.Playlist;
import org.eversolo.winamp.playlist.PlaylistController;
import org.eversolo.winamp.tags.TrackTags;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The sequencer's handover, against the two ways a user moves the playhead.
 *
 * Both bugs this covers were found on the device and were the same bug: the wrap detector
 * could not tell "repeat-one fired and we were late" from "the user just moved". Tapping a
 * track in the playlist, and dragging the position bar backwards, both skipped a track.
 */
public class SequencerTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label
                    + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    /**
     * Records what was asked of the device, and answers as the device would.
     *
     * The important detail: the real engine confirms a start by polling getState and
     * publishing every reading to its listeners - so the sequencer receives states from
     * inside its own call to play(). That is where the bug lived, so the fake does it too.
     * A fake that simply returned true could never have caught this.
     */
    static final class FakeEngine implements PlaybackEngine {
        final List<String> played = new ArrayList<>();
        PlaybackState state = PlaybackState.EMPTY;
        boolean repeatOne;
        PlaylistController sequencer;
        /** What getState reports while a start is being confirmed. */
        List<PlaybackState> duringConfirm = new ArrayList<>();
        /** How slowly the device confirms. It has 2.5 s before the engine gives up. */
        long confirmDelayMs = 0;

        @Override public boolean play(String path, String title) {
            played.add(path);
            if (sequencer != null) {
                for (PlaybackState s : duringConfirm) {
                    if (confirmDelayMs > 0) {
                        try { Thread.sleep(confirmDelayMs); } catch (InterruptedException e) { }
                    }
                    sequencer.onState(s);
                }
            }
            return true;
        }
        @Override public void pause() { }
        @Override public void resume() { }
        @Override public void togglePlayPause() { }
        @Override public void next() { }
        @Override public void previous() { }
        @Override public void seekTo(long ms) { }
        @Override public void setVolume(int volume) { }
        @Override public void setRepeatOne(boolean on) { repeatOne = on; }
        @Override public PlaybackState state() { return state; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void addListener(Listener l) { }
        @Override public void removeListener(Listener l) { }
    }

    static PlaybackState at(long positionMs, long durationMs) {
        return new PlaybackState(PlaybackState.Status.PLAYING, "t", "a", "al",
                positionMs, durationMs, 100, 200, false, 44100, 1411, 16, 2, false);
    }

    static Track track(String name) {
        TrackTags t = new TrackTags();
        t.title = name;
        t.artist = "A";
        t.album = "B";
        return new Track(new File("/m", name + ".flac"), t);
    }

    /** Lets the start worker finish; it runs on its own single thread. */
    static void settle() {
        try { Thread.sleep(120); } catch (InterruptedException ignored) { }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== tapping a track while another is playing ===");
        // The bug: engine.play confirms by polling getState, and those states reach the
        // sequencer while the new track is starting. The position falling from the old
        // track's 3 minutes to the new track's zero looked like repeat-one wrapping.
        Playlist p = new Playlist();
        FakeEngine e = new FakeEngine();
        PlaylistController c = new PlaylistController(p, e);
        for (int i = 0; i < 5; i++) p.add(track("t" + i));

        e.sequencer = c;
        c.playAt(0);
        settle();
        c.onState(at(182_000, 208_000));        // track 0, three minutes in

        // While the tap is being confirmed the device reports the old track for a moment,
        // then the new one from the beginning. That fall from 3 minutes to zero is what
        // used to read as repeat-one wrapping.
        e.duringConfirm.add(at(182_400, 208_000));
        e.duringConfirm.add(at(0, 240_000));
        e.duringConfirm.add(at(240, 240_000));
        c.playAt(3);                            // the user taps track 3
        settle();
        check("the tapped track is the one that plays", p.currentIndex(), 3);
        check("and it was the last thing asked of the device",
                e.played.get(e.played.size() - 1).endsWith("t3.flac"), true);
        check("nothing else was started", e.played.size(), 2);

        System.out.println("\n=== tapping a track while the current one is nearly over ===");
        // The same tap, but with the outgoing track seconds from its end - so the position
        // it reports during the confirm IS near the end, and the shape of the jump is
        // indistinguishable from a wrap. Only knowing that a start is in flight saves it.
        Playlist p7 = new Playlist();
        FakeEngine e7 = new FakeEngine();
        PlaylistController c7 = new PlaylistController(p7, e7);
        e7.sequencer = c7;
        for (int i = 0; i < 5; i++) p7.add(track("n" + i));
        c7.playAt(0);
        settle();
        Thread.sleep(2100);
        c7.onState(at(206_000, 208_000));
        e7.duringConfirm.add(at(207_200, 208_000));
        e7.duringConfirm.add(at(0, 190_000));
        e7.duringConfirm.add(at(260, 190_000));
        c7.playAt(2);
        settle();
        check("the tapped track plays, not the one after it", p7.currentIndex(), 2);
        check("nothing else was started", e7.played.size(), 2);

        System.out.println("\n=== a slow start, outlasting the settling window ===");
        // The engine allows a start 2.5 s to be confirmed, which is longer than the 2 s of
        // grace given to a moving playhead. On a device that stalls - and this one does,
        // over Wi-Fi - the states arrive after that grace expires while the start is still
        // in flight. Only knowing a start is happening covers that.
        Playlist p8 = new Playlist();
        FakeEngine e8 = new FakeEngine();
        PlaylistController c8 = new PlaylistController(p8, e8);
        e8.sequencer = c8;
        for (int i = 0; i < 5; i++) p8.add(track("w" + i));
        c8.playAt(0);
        settle();
        Thread.sleep(2100);
        c8.onState(at(205_000, 208_000));
        e8.confirmDelayMs = 1200;               // three readings, well past the 2 s window
        e8.duringConfirm.add(at(207_600, 208_000));
        e8.duringConfirm.add(at(0, 190_000));
        e8.duringConfirm.add(at(300, 190_000));
        c8.playAt(2);
        Thread.sleep(4200);                     // let the slow confirm finish
        check("the tapped track still wins", p8.currentIndex(), 2);
        check("nothing else was started", e8.played.size(), 2);

        System.out.println("\n=== dragging the position bar backwards ===");
        Playlist p2 = new Playlist();
        FakeEngine e2 = new FakeEngine();
        PlaylistController c2 = new PlaylistController(p2, e2);
        for (int i = 0; i < 5; i++) p2.add(track("s" + i));
        e2.sequencer = c2;
        c2.playAt(1);
        settle();
        Thread.sleep(2100);                     // the start has long settled
        // Dragged back from four minutes in. Nothing here says "seek" except the shape of
        // the jump, so this is the guard that has to hold on its own.
        c2.onState(at(240_000, 400_000));
        c2.onState(at(20_000, 400_000));
        c2.onState(at(21_000, 400_000));
        settle();
        check("still on the same track", p2.currentIndex(), 1);
        check("no new track was started", e2.played.size(), 1);

        System.out.println("\n=== dragging back from the very end of the track ===");
        // The hard case: a backwards jump from the last second IS the shape of a wrap, so
        // only knowing that the user did it tells the two apart. Hence onSeek.
        Playlist p6 = new Playlist();
        FakeEngine e6 = new FakeEngine();
        PlaylistController c6 = new PlaylistController(p6, e6);
        e6.sequencer = c6;
        for (int i = 0; i < 5; i++) p6.add(track("q" + i));
        c6.playAt(2);
        settle();
        Thread.sleep(2100);
        c6.onState(at(207_500, 208_000));
        c6.onSeek(5_000);
        // The device keeps reporting the old position for a poll or two before it catches
        // up - the visible lag on the position bar - so the backwards jump arrives late,
        // when nothing about it looks like a seek any more.
        c6.onState(at(207_600, 208_000));
        c6.onState(at(5_000, 208_000));
        c6.onState(at(6_000, 208_000));
        settle();
        check("still on the same track", p6.currentIndex(), 2);
        check("no new track was started", e6.played.size(), 1);

        System.out.println("\n=== a real repeat-one wrap still advances ===");
        Playlist p3 = new Playlist();
        FakeEngine e3 = new FakeEngine();
        PlaylistController c3 = new PlaylistController(p3, e3);
        for (int i = 0; i < 5; i++) p3.add(track("r" + i));
        e3.sequencer = c3;
        c3.playAt(0);
        settle();
        // Left alone long enough that the start has settled, then the track runs to its end
        // and starts again: repeat-one fired because the handover was late.
        Thread.sleep(2100);
        c3.onState(at(207_500, 208_000));
        c3.onState(at(120, 208_000));
        settle();
        check("the wrap was noticed", p3.currentIndex(), 1);
        check("and the next track was started", e3.played.size(), 2);

        System.out.println("\n=== the ordinary end-of-track handover ===");
        Playlist p4 = new Playlist();
        FakeEngine e4 = new FakeEngine();
        PlaylistController c4 = new PlaylistController(p4, e4);
        for (int i = 0; i < 3; i++) p4.add(track("h" + i));
        e4.sequencer = c4;
        c4.playAt(0);
        settle();
        Thread.sleep(2100);
        c4.onState(at(207_800, 208_000));       // 200 ms left, inside the 400 ms lead
        settle();
        check("advanced at the end of the track", p4.currentIndex(), 1);
        check("repeat-one is on while driving", e4.repeatOne, true);

        System.out.println("\n=== seeking near the end still hands over ===");
        Playlist p5 = new Playlist();
        FakeEngine e5 = new FakeEngine();
        PlaylistController c5 = new PlaylistController(p5, e5);
        for (int i = 0; i < 3; i++) p5.add(track("k" + i));
        e5.sequencer = c5;
        c5.playAt(0);
        settle();
        Thread.sleep(2100);
        c5.onSeek(200_000);
        c5.onState(at(200_000, 208_000));
        Thread.sleep(2100);                     // the seek settles
        c5.onState(at(207_900, 208_000));
        settle();
        check("the track still ends properly after a seek", p5.currentIndex(), 1);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
