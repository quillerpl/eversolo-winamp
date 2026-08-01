package org.eversolo.winamp.library;

import org.eversolo.winamp.core.Logs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the volumes worth scanning.
 *
 * Deliberately discovers at runtime rather than hardcoding "EF42-73B2" - that id comes
 * from how this particular SSD was formatted and will differ on another unit
 * (CLAUDE.md, Storage).
 *
 * On the test device this finds:
 *   /storage/EF42-73B2                  ~4,869 audio files
 *   /storage/emulated/0/EverSoloMusic   ~168 audio files
 */
public final class VolumeDiscovery {

    private static final String TAG = "Volumes";

    /**
     * Skipped at a volume root. Kept deliberately minimal.
     *
     * An earlier, longer list also excluded Movies, Documents, Pictures, Podcasts and
     * friends - and silently lost 29 real audio files that were sitting in Movies. The
     * whole directory walk takes ~570 ms on this device, so skipping folders buys almost
     * nothing and risks hiding the user's music. Only exclude what genuinely cannot hold
     * a music library.
     */
    private static final Set<String> SKIP_AT_ROOT = new LinkedHashSet<>(java.util.Arrays.asList(
            "Android",           // app sandboxes: large, and never the user's own music
            "LOST.DIR",          // filesystem recovery fragments
            ".android_secure"
    ));

    /** Known music folders on the internal volume. */
    private static final String[] INTERNAL_MUSIC = {
            "/storage/emulated/0/EverSoloMusic",
            "/storage/emulated/0/Music",
    };

    private VolumeDiscovery() {}

    public static List<File> findRoots() {
        List<File> roots = new ArrayList<>();

        File storage = new File("/storage");
        File[] entries = storage.listFiles();
        if (entries != null) {
            for (File f : entries) {
                String name = f.getName();
                if ("self".equals(name) || "emulated".equals(name)) continue;
                if (!f.isDirectory() || !f.canRead()) continue;
                // A removable/secondary volume: usually XXXX-XXXX
                String[] kids = f.list();
                if (kids == null || kids.length == 0) continue;
                roots.add(f);
                Logs.i(TAG, "volume: " + f.getAbsolutePath() + " (" + kids.length + " entries)");
            }
        } else {
            Logs.w(TAG, "could not list /storage");
        }

        for (String p : INTERNAL_MUSIC) {
            File f = new File(p);
            if (f.isDirectory() && f.canRead()) {
                roots.add(f);
                Logs.i(TAG, "volume: " + p);
            }
        }

        if (roots.isEmpty()) {
            File ext = new File("/storage/emulated/0");
            if (ext.isDirectory() && ext.canRead()) {
                roots.add(ext);
                Logs.w(TAG, "no dedicated music volumes found; falling back to " + ext);
            }
        }
        return roots;
    }

    /** Directories not worth descending into, checked only at a volume root. */
    static boolean skipAtRoot(String dirName) {
        return SKIP_AT_ROOT.contains(dirName);
    }
}
