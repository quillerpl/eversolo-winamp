import org.eversolo.winamp.tags.*;

import java.io.File;
import java.util.*;

/** Desktop JVM harness. There is no debugger on the device, so prove the parsers here. */
public class TagTest {

    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        boolean ok = Objects.equals(String.valueOf(actual), String.valueOf(expected));
        if (ok) { pass++; System.out.println("    PASS  " + label + " = " + actual); }
        else { fail++; System.out.println("    FAIL  " + label + " : expected <" + expected + "> got <" + actual + ">"); }
    }

    static void checkTrue(String label, boolean b) {
        if (b) { pass++; System.out.println("    PASS  " + label); }
        else { fail++; System.out.println("    FAIL  " + label); }
    }

    public static void main(String[] args) {
        File root = new File(args[0]);
        Set<String> roots = new HashSet<>(Collections.singletonList(root.getAbsolutePath()));
        TagReaders readers = new TagReaders(roots, true);

        System.out.println("=== 1. Fully tagged 16-bit FLAC ===");
        TrackTags t = readers.read(new File(root,
                "Leonard Cohen/[M] Old Ideas [32570025] [2012]/02 - Leonard Cohen - Amen.flac"));
        System.out.println("  " + t);
        check("title", t.title, "Amen");
        check("artist", t.artist, "Leonard Cohen");
        check("album", t.album, "Old Ideas");
        check("albumArtist", t.albumArtist, "Leonard Cohen");
        check("year", t.year, 2012);
        check("track", t.trackNumber, 2);
        check("genre", t.genre, "Folk");
        check("sampleRate", t.sampleRate, 44100);
        check("bitDepth", t.bitDepth, 16);
        check("channels", t.channels, 2);
        check("source", t.source, "flac");
        checkTrue("duration ~2000ms (got " + t.durationMs + ")", Math.abs(t.durationMs - 2000) < 120);

        System.out.println("\n=== 2. UNTAGGED FLAC -> must fall back to path ===");
        t = readers.read(new File(root,
                "Leonard Cohen/[M] Old Ideas [32570025] [2012]/03 - Leonard Cohen - Show Me the Place.flac"));
        System.out.println("  " + t);
        check("title from filename", t.title, "Show Me the Place");
        check("artist from folder", t.artist, "Leonard Cohen");
        check("album cleaned of [] junk", t.album, "Old Ideas");
        check("year from folder name", t.year, 2012);
        check("track from filename", t.trackNumber, 3);
        check("sampleRate still from STREAMINFO", t.sampleRate, 44100);
        checkTrue("duration ~3000ms from STREAMINFO (got " + t.durationMs + ")",
                Math.abs(t.durationMs - 3000) < 120);

        System.out.println("\n=== 3. 24-bit FLAC, unicode, embedded art, inside CD2 ===");
        t = readers.read(new File(root,
                "Gipsy Kings - The Real Gipsy Kings-3CD-2014 [FLAC]/CD2/01. Bem, bem, María.flac"));
        System.out.println("  " + t);
        check("unicode title", t.title, "Bem, bem, María");
        check("artist", t.artist, "Gipsy Kings");
        check("album", t.album, "The Real Gipsy Kings");
        check("track from \"1/18\"", t.trackNumber, 1);
        check("disc from CD2 folder", t.discNumber, 2);
        check("year from \"2014-06-01\"", t.year, 2014);
        check("sampleRate", t.sampleRate, 48000);
        check("bitDepth", t.bitDepth, 24);
        checkTrue("artwork extracted (" + (t.artwork == null ? "null" : t.artwork.length + "B") + ")",
                t.artwork != null && t.artwork.length > 100);

        System.out.println("\n=== 4. MP3 with ID3v2.3 ===");
        t = readers.read(new File(root, "ELO/ELO - Secret Messages (CSCS 6036)/03. Bluebird.mp3"));
        System.out.println("  " + t);
        check("title", t.title, "Bluebird");
        check("artist", t.artist, "Electric Light Orchestra");
        check("album", t.album, "Secret Messages");
        check("track from \"3/11\"", t.trackNumber, 3);
        check("year", t.year, 1983);
        checkTrue("source is id3 (got " + t.source + ")", String.valueOf(t.source).startsWith("id3"));

        System.out.println("\n=== 5. MP3 with ID3v2.4 + unicode ===");
        t = readers.read(new File(root, "ELO/ELO - Secret Messages (CSCS 6036)/04. Trest.mp3"));
        System.out.println("  " + t);
        check("unicode title", t.title, "Träumerei — Ø");
        check("unicode artist", t.artist, "Tëst Ärtist");
        check("unicode album", t.album, "Ünicode Album");
        check("year", t.year, 1999);
        check("track", t.trackNumber, 4);

        System.out.println("\n=== 6. Robustness: junk input must not throw ===");
        try {
            File bogus = File.createTempFile("junk", ".flac");
            java.nio.file.Files.write(bogus.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            TrackTags j = readers.read(bogus);
            checkTrue("corrupt .flac handled, no exception", j != null);
            File missing = new File(root, "does/not/exist.flac");
            TrackTags m = readers.read(missing);
            checkTrue("missing file handled, no exception", m != null);
            bogus.delete();
        } catch (Exception e) {
            fail++;
            System.out.println("    FAIL  threw " + e);
        }

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
