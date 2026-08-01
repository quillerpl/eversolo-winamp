import org.eversolo.winamp.tags.M3uParser;

import java.io.File;
import java.util.Objects;

public class M3uTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    public static void main(String[] args) {
        File dir = new File(args[0], "playlists");

        System.out.println("=== 1. normal .m3u: EXTINF, relative paths, a missing file, a URL ===");
        M3uParser.Result r = M3uParser.parse(new File(dir, "normal.m3u"));
        for (M3uParser.Entry e : r.entries) System.out.println("    " + e);
        check("entries", r.entries.size(), 4);
        check("playable found", r.existingPaths().size(), 2);
        check("missing counted", r.missing, 1);
        check("urls counted", r.urls, 1);
        check("EXTINF title kept", r.entries.get(0).extinfTitle, "Leonard Cohen - Amen");
        check("EXTINF seconds kept", r.entries.get(0).extinfSeconds, 455);
        check("comment line ignored", r.entries.get(2).rawLine.startsWith("#"), false);

        System.out.println("\n=== 2. Windows backslashes + CRLF ===");
        r = M3uParser.parse(new File(dir, "windows.m3u"));
        for (M3uParser.Entry e : r.entries) System.out.println("    " + e);
        check("entries", r.entries.size(), 2);
        check("backslash paths resolved", r.existingPaths().size(), 2);

        System.out.println("\n=== 3. UTF-8 BOM + unicode filename ===");
        r = M3uParser.parse(new File(dir, "bom.m3u8"));
        for (M3uParser.Entry e : r.entries) System.out.println("    " + e);
        check("BOM did not break the first path", r.existingPaths().size(), 1);
        check("unicode EXTINF", r.entries.get(0).extinfTitle, "Gipsy Kings - Bem, bem, María");

        System.out.println("\n=== 4. Latin-1 file (invalid UTF-8) must not crash ===");
        r = M3uParser.parse(new File(dir, "latin1.m3u"));
        for (M3uParser.Entry e : r.entries) System.out.println("    " + e);
        check("still parsed", r.existingPaths().size(), 1);
        check("latin-1 decoded", r.entries.get(0).extinfTitle, "Café del Mar");

        System.out.println("\n=== 5. robustness ===");
        r = M3uParser.parse(new File(dir, "does-not-exist.m3u"));
        check("missing playlist -> empty, no crash", r.entries.size(), 0);
        check("isPlaylist(.m3u)", M3uParser.isPlaylist("x.m3u"), true);
        check("isPlaylist(.M3U8)", M3uParser.isPlaylist("x.M3U8"), true);
        check("isPlaylist(.flac)", M3uParser.isPlaylist("x.flac"), false);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }
}
