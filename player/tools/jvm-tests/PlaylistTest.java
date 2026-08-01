import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.playlist.Playlist;
import org.eversolo.winamp.tags.TrackTags;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;

public class PlaylistTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    static Track track(String folder, String name) {
        TrackTags t = new TrackTags();
        t.title = name;
        t.artist = "A";
        t.album = "B";
        return new Track(new File(folder, name + ".flac"), t);
    }

    public static void main(String[] args) {
        System.out.println("=== basic add / order ===");
        Playlist p = new Playlist();
        Track a = track("/m/one", "a"), b = track("/m/one", "b"),
              c = track("/m/two", "c"), d = track("/m/two", "d");
        p.addAll(Arrays.asList(a, b, c));
        check("size", p.size(), 3);
        check("order preserved", p.get(1).title, "b");
        check("no current yet", p.currentIndex(), -1);

        System.out.println("\n=== the current-index bookkeeping (easy to get wrong) ===");
        p.setCurrentIndex(1);                     // playing "b"
        check("current is b", p.current().title, "b");

        p.insert(0, d);                           // insert BEFORE current
        check("insert before current shifts it", p.currentIndex(), 2);
        check("still playing b", p.current().title, "b");

        p.removeAt(0);                            // remove BEFORE current
        check("remove before current shifts back", p.currentIndex(), 1);
        check("still playing b", p.current().title, "b");

        p.insert(3, d);                           // insert AFTER current
        check("insert after current does not move it", p.currentIndex(), 1);

        p.removeAt(1);                            // remove THE current track
        check("removing current clears it", p.currentIndex(), -1);

        System.out.println("\n=== move ===");
        Playlist q = new Playlist();
        q.addAll(Arrays.asList(a, b, c));
        q.setCurrentIndex(0);
        q.move(0, 2);
        check("moved track lands at 2", q.get(2).title, "a");
        check("current follows the moved track", q.currentIndex(), 2);

        System.out.println("\n=== shuffle keeps the playing track selected ===");
        Playlist s = new Playlist();
        for (int i = 0; i < 40; i++) s.add(track("/m/x", "t" + i));
        s.setCurrentIndex(7);
        String playing = s.current().absolutePath;
        s.shuffle();
        check("still 40 tracks", s.size(), 40);
        check("current still points at the same file",
                s.current() == null ? "null" : s.current().absolutePath, playing);

        System.out.println("\n=== same-folder detection (basis of the deferred D7) ===");
        Playlist f = new Playlist();
        f.addAll(Arrays.asList(a, b, c));         // a,b in /m/one ; c in /m/two
        check("a -> b same folder", f.nextIsSameFolder(0), true);
        check("b -> c different folder", f.nextIsSameFolder(1), false);
        check("past the end is false", f.nextIsSameFolder(2), false);

        System.out.println("\n=== robustness ===");
        Playlist r = new Playlist();
        r.removeAt(5);
        r.move(3, 9);
        r.setCurrentIndex(99);
        check("out-of-range ops do not throw", r.size(), 0);
        check("bad index clamps to -1", r.currentIndex(), -1);
        check("get out of range is null", r.get(4), null);
        r.add(a);
        r.setCurrentIndex(0);
        r.clear();
        check("clear resets current", r.currentIndex(), -1);
        check("indexOfPath finds it", f.indexOfPath(c.absolutePath), 2);
        check("indexOfPath misses cleanly", f.indexOfPath("/nope.flac"), -1);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
