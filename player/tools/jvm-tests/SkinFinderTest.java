import org.eversolo.winamp.library.SkinFinder;

import java.io.File;
import java.io.RandomAccessFile;
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

    /**
     * A believable skin-sized file. Not zero bytes: an empty file is not a skin and the
     * finder rightly says so, which these fixtures have to respect or they test nothing.
     */
    static void file(File parent, String name) throws Exception {
        File f = new File(parent, name);
        if (!f.createNewFile() && !f.isFile()) throw new IllegalStateException("touch " + f);
        sparse(f, 100 * 1024);
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

        System.out.println("\n=== the crash of 2 August: a firmware image is also a .zip ===");
        // The owner had DMP-A6_R_v1.2.40_Beta_...ota-package.zip in Downloads. The app opened
        // it because it ends in .zip, read it into memory and died with OutOfMemoryError on
        // the main thread, before the first screen. Extension alone is not evidence.
        File downloads = dir(internal, "Download");
        File ota = new File(downloads, "DMP-A6_R_v1.2.40_Beta_202312291910_ota-package.zip");
        sparse(ota, SkinFinder.MAX_SKIN_BYTES + 1);
        // macOS leaves one of these next to the real file when you copy to a USB stick.
        file(downloads, "._DMP-A6_R_v1.2.40_Beta_202312291910_ota-package.zip");
        // ...and a genuine skin of a believable size, to be sure the filter is not just "no".
        File real = new File(downloads, "hand-me-down.zip");
        sparse(real, 120 * 1024);

        r = SkinFinder.find(roots);
        check("the firmware image is not a skin candidate", SkinFinder.isSkin(ota), false);
        check("nor is the macOS ._ stub beside it",
                names(r.skins).contains("._DMP-A6_R_v1.2.40_Beta_202312291910_ota-package.zip"),
                false);
        check("a normal-sized .zip still counts", SkinFinder.isSkin(real), true);
        File empty = new File(downloads, "zero-bytes.wsz");
        if (!empty.createNewFile() && !empty.isFile()) throw new IllegalStateException("touch");
        check("an empty file does not", SkinFinder.isSkin(empty), false);
        check("and a file that is not there does not", 
                SkinFinder.isSkin(new File(downloads, "nope.wsz")), false);
        check("the walk skips it too", names(r.skins).contains(ota.getName()), false);

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

    /** A file of a given length without writing the bytes - the OTA was 128 MB. */
    static void sparse(File f, long bytes) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(bytes);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
