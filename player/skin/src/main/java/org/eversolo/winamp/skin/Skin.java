package org.eversolo.winamp.skin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.eversolo.winamp.core.Logs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A loaded Winamp skin: the bitmaps out of a .wsz (a zip of BMPs) or a plain folder.
 *
 * Skins in the wild are inconsistent about case and nesting - files turn up as MAIN.BMP,
 * Main.bmp, or inside a folder within the archive - so everything is keyed on the
 * lower-cased basename.
 */
public final class Skin {

    private static final String TAG = "Skin";

    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private PleditStyle pledit;
    private VisColors vis;
    private String name = "(none)";

    public String name() { return name; }

    public Bitmap bmp(String fileName) {
        return bitmaps.get(fileName.toLowerCase(Locale.UK));
    }

    public boolean has(String fileName) {
        return bitmaps.containsKey(fileName.toLowerCase(Locale.UK));
    }

    /** The raw contents of a .txt in the skin - pledit.txt, viscolor.txt, region.txt. */
    public String text(String fileName) {
        return texts.get(fileName.toLowerCase(Locale.UK));
    }

    /** The playlist editor's colours. Falls back to the classic ones if the skin has none. */
    public PleditStyle pledit() {
        if (pledit == null) pledit = PleditStyle.parse(text("pledit.txt"));
        return pledit;
    }

    /** The visualiser's 24-colour palette, from viscolor.txt. */
    public VisColors visColors() {
        if (vis == null) vis = VisColors.parse(text("viscolor.txt"));
        return vis;
    }

    /** Enough to draw the main window? */
    public boolean isUsable() {
        return has("main.bmp") && has("cbuttons.bmp") && has("titlebar.bmp");
    }

    public int bitmapCount() { return bitmaps.size(); }

    // ---------------------------------------------------------------- loading

    /** The largest a single file inside a skin may be before the archive is abandoned. */
    private static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024;

    /** And the largest the whole thing may be, unpacked. */
    private static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;

    /** Load a .wsz / .zip archive. Never throws: an unreadable file is simply not a skin. */
    public static Skin fromArchive(File archive) {
        Skin s = new Skin();
        s.name = archive.getName();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry e;
            long total = 0;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] data = readAll(zip);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new TooBig("archive unpacks to over "
                        + (MAX_TOTAL_BYTES / 1024 / 1024) + " MB");
                s.consume(baseName(e.getName()), data);
                zip.closeEntry();
            }
        } catch (TooBig big) {
            // Expected for things that merely end in .zip. One line, no stack trace.
            Logs.i(TAG, "not a skin, " + big.getMessage() + ": " + archive.getName());
            return s;
        } catch (Throwable ex) {
            // Throwable, not Exception: this parses files nobody vouched for, and an
            // OutOfMemoryError here used to take the whole app down on startup.
            Logs.w(TAG, "could not read skin archive " + archive + ": " + ex);
            return s;
        }
        Logs.i(TAG, "skin '" + s.name + "' loaded " + s.bitmaps.size() + " bitmaps"
                + (s.isUsable() ? "" : "  (INCOMPLETE - missing main/cbuttons/titlebar)"));
        return s;
    }

    /** Load a folder of loose bitmaps. */
    public static Skin fromFolder(File dir) {
        Skin s = new Skin();
        s.name = dir.getName();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                try (InputStream in = new FileInputStream(f)) {
                    s.consume(f.getName(), readAll(in));
                } catch (Exception ex) {
                    Logs.w(TAG, "skipped " + f.getName() + ": " + ex);
                }
            }
        }
        Logs.i(TAG, "skin folder '" + s.name + "' loaded " + s.bitmaps.size() + " bitmaps");
        return s;
    }

    /** Load from the app's own assets, e.g. the bundled classic skin. */
    public static Skin fromAssets(Context ctx, String assetDir) {
        Skin s = new Skin();
        s.name = assetDir;
        try {
            String[] names = ctx.getAssets().list(assetDir);
            if (names != null) {
                for (String n : names) {
                    try (InputStream in = ctx.getAssets().open(assetDir + "/" + n)) {
                        s.consume(n, readAll(in));
                    } catch (Exception ex) {
                        Logs.w(TAG, "asset " + n + ": " + ex);
                    }
                }
            }
        } catch (Exception ex) {
            Logs.e(TAG, "could not list assets in " + assetDir, ex);
        }
        Logs.i(TAG, "asset skin '" + s.name + "' loaded " + s.bitmaps.size() + " bitmaps");
        return s;
    }

    /** Load a .wsz packaged inside the app's assets. */
    public static Skin fromAssetArchive(Context ctx, String assetPath) {
        Skin s = new Skin();
        s.name = assetPath;
        try (ZipInputStream zip = new ZipInputStream(ctx.getAssets().open(assetPath))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                s.consume(baseName(e.getName()), readAll(zip));
                zip.closeEntry();
            }
        } catch (Throwable ex) {
            Logs.w(TAG, "could not read bundled skin " + assetPath + ": " + ex);
        }
        Logs.i(TAG, "bundled skin '" + s.name + "' loaded " + s.bitmaps.size() + " bitmaps"
                + (s.isUsable() ? "" : "  (INCOMPLETE)"));
        return s;
    }

    private void consume(String fileName, byte[] data) {
        String lower = fileName.toLowerCase(Locale.UK);
        if (data == null || data.length == 0) return;
        if (lower.endsWith(".txt")) {
            // Skin .txt files are Windows-era ASCII; Latin-1 never throws on a stray byte.
            texts.put(lower, new String(data, java.nio.charset.StandardCharsets.ISO_8859_1));
            return;
        }
        if (!lower.endsWith(".bmp")) return;       // .cur cursors are of no use to us
        try {
            // Our decoder first: Winamp skins are frequently BI_RLE8, which
            // BitmapFactory cannot read at all (it returns null).
            Bitmap b = BmpDecoder.decode(data);
            if (b == null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;                // pixel art: never let density rescale it
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            }
            if (b != null) bitmaps.put(lower, b);
            else Logs.w(TAG, "could not decode " + fileName);
        } catch (Throwable t) {
            Logs.w(TAG, "decode failed for " + fileName + ": " + t);
        }
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /**
     * Read one archive entry, refusing to grow past {@link #MAX_ENTRY_BYTES}.
     *
     * The limit is not paranoia. The app looks for skins by walking the user's storage, and
     * on the owner's device it found a 128 MB Eversolo firmware OTA package sitting in
     * Downloads, opened it because it ends in .zip, and died reading the first entry into a
     * byte array. A skin is a handful of small bitmaps; nothing legitimate comes near this.
     */
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY_BYTES) {
                throw new TooBig("entry is over " + (MAX_ENTRY_BYTES / 1024) + " KB");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Thrown to abandon an archive that is clearly not a skin. Not an error worth a stack. */
    private static final class TooBig extends Exception {
        TooBig(String why) { super(why); }
    }
}
