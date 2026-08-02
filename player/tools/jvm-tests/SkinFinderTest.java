import org.eversolo.winamp.library.SkinFinder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The skin walk, against a real directory tree built on disk.
 *
 * It replaced a version that only looked in a few named folders, so the cases that matter
 * are the ones that used to fail: a skin nested a few levels down, and one on a second
 * volume. Testing those against a made-up File abstraction would only prove the abstraction
 * agrees with itself.
 */
public class SkinFinderTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label
                    + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    static File dir(File parent, String path) {
        File d = new File(parent, path);
        if (!d.mkdirs() && !d.isDirectory()) throw new IllegalStateException("mkdir " + d);
        return d;
    }

    static void file(File parent, String name) throws Exception {
        File f = new File(parent, name);
        if (!f.createNewFile() && !f.isFile()) throw new IllegalStateException("touch " + f);
    }

    static List<String> names(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }

    public static void main(String[] args) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "skinfinder-test");
        deleteTree(tmp);
        File internal = dir(tmp, "emulated/0");
        File usb = dir(tmp, "EF42-73B2");

        System.out.println("=== where a person actually leaves a skin ===");
        file(dir(internal, "EverSoloWinamp/skins"), "base-2.91.wsz");
        file(dir(internal, "Download"), "purple.wsz");
        // The case the old finder missed: nested, on the stick, in a folder of their own.
        file(dir(usb, "Music/Winamp stuff"), "deep.wsz");
        file(usb, "root-level.zip");

        List<File> roots = Arrays.asList(internal, usb);
        SkinFinder.Result r = SkinFinder.find(roots);
        check("found all four", r.skins.size(), 4);
        check("including the nested one on the stick", names(r.skins).contains("deep.wsz"), true);
        check("including one at a volume root", names(r.skins).contains("root-level.zip"), true);
        check("nothing was cut short", r.truncated, false);

        System.out.println("\n=== what it refuses to walk ===");
        file(dir(internal, "Android/data/com.something/files"), "hidden-in-android.wsz");
        file(dir(internal, ".thumbnails"), "dotfolder.wsz");
        file(dir(internal, "LOST.DIR"), "lost.wsz");
        r = SkinFinder.find(roots);
        check("Android/ is skipped", names(r.skins).contains("hidden-in-android.wsz"), false);
        check("dot-folders are skipped", names(r.skins).contains("dotfolder.wsz"), false);
        check("LOST.DIR is skipped", names(r.skins).contains("lost.wsz"), false);
        check("the four real ones are still found", r.skins.size(), 4);

        System.out.println("\n=== the depth limit ===");
        StringBuilder deep = new StringBuilder("a");
        for (int i = 1; i <= SkinFinder.MAX_DEPTH + 2; i++) deep.append("/a").append(i);
        file(dir(internal, deep.toString()), "too-deep.wsz");
        // Same tree, but landing exactly on the limit rather than past it.
        StringBuilder edge = new StringBuilder("b");
        for (int i = 1; i < SkinFinder.MAX_DEPTH; i++) edge.append("/b").append(i);
        file(dir(internal, edge.toString()), "at-the-limit.wsz");
        r = SkinFinder.find(roots);
        check("past MAX_DEPTH is not found", names(r.skins).contains("too-deep.wsz"), false);
        check("at MAX_DEPTH is found", names(r.skins).contains("at-the-limit.wsz"), true);

        System.out.println("\n=== housekeeping ===");
        check("only .wsz and .zip count", SkinFinder.isSkin(new File(internal, "song.flac")), false);
        check("results are sorted", isSorted(r.skins), true);
        check("it reports what it looked at", r.foldersSeen > 0 && r.filesSeen > 0, true);
        File missing = new File(tmp, "no-such-volume");
        check("a volume that is not there is harmless",
                SkinFinder.find(Arrays.asList(missing)).skins.size(), 0);

        deleteTree(tmp);
        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }

    static boolean isSorted(List<File> files) {
        for (int i = 1; i < files.size(); i++) {
            if (files.get(i - 1).compareTo(files.get(i)) > 0) return false;
        }
        return true;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
