package org.eversolo.winamp;

import android.content.Context;
import android.content.SharedPreferences;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.library.VolumeDiscovery;
import org.eversolo.winamp.skin.Skin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds the skins on this device and remembers which one the user picked.
 *
 * The app used to take the first `.wsz` it happened to find and fall back to one bundled in
 * the APK. The bundled one is Nullsoft's artwork and is not ours to hand out - see
 * `THIRD-PARTY-NOTICES.md` - so release builds ship without it and the user supplies their
 * own. That makes "which skin?" a real question with a real answer to store, rather than
 * whatever `listFiles` returned first.
 *
 * Skins are looked for on **every** volume, not just internal storage: on this device the
 * obvious way to get a file across is a USB stick, and the obvious place to leave it is
 * wherever it landed.
 */
public final class SkinStore {

    private static final String TAG = "SkinStore";
    private static final String PREFS = "winamp";
    private static final String PREF_CHOSEN = "skinPath";

    /** Where the app suggests keeping them, and the first place it looks. */
    public static final String HOME = "/storage/emulated/0/EverSoloWinamp/skins";

    /** Directories worth walking on each volume. Walking a whole SSD to find a skin is not. */
    private static final String[] LIKELY = {
            "EverSoloWinamp/skins", "skins", "Download", "Downloads", "",
    };

    private final Context ctx;

    public SkinStore(Context ctx) { this.ctx = ctx; }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The file the user chose, if it is still there. */
    public File chosen() {
        String path = prefs().getString(PREF_CHOSEN, null);
        if (path == null) return null;
        File f = new File(path);
        return f.isFile() ? f : null;
    }

    public void choose(File skin) {
        prefs().edit().putString(PREF_CHOSEN, skin.getAbsolutePath()).apply();
        Logs.i(TAG, "skin chosen: " + skin.getAbsolutePath());
    }

    /**
     * The skin to draw with: the chosen one, else any that can be found, else the bundled
     * one if this build has it. Null when there is nothing to draw with at all, which is
     * what sends a first-time user to the onboarding screen.
     */
    public Skin load() {
        File c = chosen();
        if (c != null) {
            Skin s = Skin.fromArchive(c);
            if (s.isUsable()) return s;
            Logs.w(TAG, "chosen skin is not usable: " + c);
        }
        for (File f : findAll()) {
            Skin s = Skin.fromArchive(f);
            if (s.isUsable()) {
                choose(f);              // first run with a skin already on the device
                return s;
            }
            Logs.w(TAG, "skin not usable, skipping: " + f);
        }
        Skin bundled = Skin.fromAssetArchive(ctx, "skins/base-2.91.wsz");
        return bundled != null && bundled.isUsable() ? bundled : null;
    }

    /** Every `.wsz`/`.zip` on every volume, in the places a skin plausibly is. */
    public List<File> findAll() {
        List<File> found = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        roots.add(new File("/storage/emulated/0"));
        for (File v : VolumeDiscovery.findRoots()) {
            if (!roots.contains(v)) roots.add(v);
        }
        for (File root : roots) {
            for (String sub : LIKELY) {
                File dir = sub.isEmpty() ? root : new File(root, sub);
                collect(dir, found);
            }
        }
        Collections.sort(found);
        Logs.i(TAG, "found " + found.size() + " skin file(s)");
        return found;
    }

    /** One directory, not a recursive walk: a skin sitting three folders deep is not found. */
    private void collect(File dir, List<File> into) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if ((n.endsWith(".wsz") || n.endsWith(".zip")) && !into.contains(f)) into.add(f);
        }
    }
}
