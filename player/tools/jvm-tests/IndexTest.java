import org.eversolo.winamp.library.MusicIndex;
import org.eversolo.winamp.library.Track;
import org.eversolo.winamp.playlist.Playlist;
import org.eversolo.winamp.tags.TrackTags;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Ordering, and the property that makes ordering legal.
 *
 * A user's scan died with "Comparison method violates its general contract!" — Java's
 * TimSort refusing an inconsistent comparator. It only checks above 32 elements, so a
 * comparator can be wrong for months and only fail on somebody else's larger library.
 *
 * So these tests do not check one example. They check the property: over every triple,
 * the comparator must be antisymmetric and transitive. That is what "general contract"
 * means, and it is cheap to verify exhaustively on a few dozen items.
 */
public class IndexTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label
                    + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    static void checkTrue(String label, boolean ok) { check(label, ok, true); }

    static Track track(String artist, String album, Integer year, Integer trackNo, String title) {
        TrackTags t = new TrackTags();
        t.title = title;
        t.artist = artist;
        t.album = album;
        t.albumArtist = artist;
        t.year = year;
        t.trackNumber = trackNo;
        return new Track(new File("/m/" + artist + "/" + album, title + ".flac"), t);
    }

    /** The contract itself: sgn(compare(a,b)) == -sgn(compare(b,a)), and transitivity. */
    static <T> String violation(List<T> items, Comparator<T> c) {
        for (T a : items) {
            for (T b : items) {
                if (Integer.signum(c.compare(a, b)) != -Integer.signum(c.compare(b, a))) {
                    return "not antisymmetric: " + a + " vs " + b;
                }
                for (T d : items) {
                    if (c.compare(a, b) < 0 && c.compare(b, d) < 0 && c.compare(a, d) >= 0) {
                        return "not transitive: " + a + " < " + b + " < " + d
                                + " but not " + a + " < " + d;
                    }
                }
            }
        }
        return null;
    }

    public static void main(String[] args) {
        System.out.println("=== the library a user actually had ===");
        // One artist, more than 32 albums, some dated and some not - which is ordinary for
        // a collection with a few untagged rips in it. Names ascend while years descend, so
        // the two orderings disagree, which is what exposes an inconsistent comparator.
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Integer year = (i % 2 == 0) ? 2000 - i : null;
            tracks.add(track("Various", String.format("Album %02d", i), year, 1, "Song " + i));
        }
        String error = null;
        MusicIndex index = null;
        try {
            index = new MusicIndex(tracks);
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        check("the scan does not throw", error, "null");
        if (index != null) {
            check("every album is present", index.albumsOf("Various").size(), 40);
            check("artist is listed once", index.artists().size(), 1);
        }

        System.out.println("\n=== albums are ordered by year, undated ones last ===");
        List<Track> mixed = new ArrayList<>();
        mixed.add(track("A", "Later", 2005, 1, "x"));
        mixed.add(track("A", "Undated Zzz", null, 1, "x"));
        mixed.add(track("A", "Earlier", 1995, 1, "x"));
        mixed.add(track("A", "Undated Aaa", null, 1, "x"));
        MusicIndex m = new MusicIndex(mixed);
        List<String> order = new ArrayList<>();
        for (MusicIndex.Album a : m.albumsOf("A")) order.add(a.name);
        check("dated albums first, in year order, then undated by name",
                order, "[Earlier, Later, Undated Aaa, Undated Zzz]");

        System.out.println("\n=== track order inside an album ===");
        List<Track> album = new ArrayList<>();
        album.add(track("B", "LP", 1990, 3, "third"));
        album.add(track("B", "LP", 1990, null, "untracked"));
        album.add(track("B", "LP", 1990, 1, "first"));
        MusicIndex mb = new MusicIndex(album);
        List<String> titles = new ArrayList<>();
        for (Track t : mb.albumsOf("B").get(0).tracks) titles.add(t.title);
        check("numbered tracks in order, unnumbered last", titles, "[first, third, untracked]");

        System.out.println("\n=== the comparators obey the contract, over every triple ===");
        // This is the real test. TimSort only checks above 32 elements and only notices
        // opportunistically, so "it sorted without throwing" proves nothing. Check the
        // property itself: antisymmetric, and transitive, over every triple.

        // Albums where the year order and the name order disagree, and some years missing.
        List<MusicIndex.Album> albums = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            List<Track> one = new ArrayList<>();
            one.add(track("C", "Album " + (char) ('A' + i), i % 3 == 0 ? null : 2010 - i,
                    1, "x"));
            albums.add(new MusicIndex(one).albumsOf("C").get(0));
        }
        check("album order is a total order",
                violation(albums, MusicIndex.albumOrder()), "null");

        // Tracks in ONE album - so the comparison actually reaches the track number - with
        // some numbers missing and titles that disagree with the numbering.
        List<Track> sameAlbum = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            sameAlbum.add(track("C", "One Album", 1999,
                    i % 3 == 0 ? null : i, "Track " + (char) ('Z' - i)));
        }
        check("track order is a total order",
                violation(sameAlbum, MusicIndex.trackOrder()), "null");
        check("playlist sort-by-artist is a total order",
                violation(sameAlbum, Playlist.byArtistThenTitle()), "null");

        System.out.println("\n=== and it survives a sort large enough for TimSort to check ===");
        Playlist p = new Playlist();
        for (int i = 0; i < 60; i++) {
            p.add(track("C", "One Album", 1999,
                    i % 3 == 0 ? null : i % 20, "Track " + (char) ('A' + i % 26)));
        }
        String sortError = null;
        try {
            p.sort(Playlist.byArtistThenTitle());
        } catch (Throwable t) {
            sortError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        check("SORT LIST does not throw on a 60-track playlist", sortError, "null");
        check("and keeps every track", p.size(), 60);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
