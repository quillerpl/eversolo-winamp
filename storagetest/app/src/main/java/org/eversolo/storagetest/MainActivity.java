package org.eversolo.storagetest;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v2 diagnostic. Adds the things v1 missed:
 *   - display metrics (Q5) - drives Winamp skin scaling
 *   - whether tags can be read straight from the files (validates D2)
 *   - the second music location found via MediaStore
 *
 * Still strictly read-only: writes nothing, plays nothing, deletes nothing.
 */
public class MainActivity extends Activity {

    private static final String REPORT_URL = "http://192.168.1.61:8765/report";
    private static final int REQ = 1;

    private TextView out;
    private final StringBuilder log = new StringBuilder();
    private final StringBuilder summary = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("EVERSOLO STORAGE TEST v2");
        title.setTextColor(Color.GREEN);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        out = new TextView(this);
        out.setTextColor(Color.WHITE);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        root.addView(sv);
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ);
        } else {
            runTests();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        runTests();
    }

    private void p(String s) { log.append(s).append('\n'); }
    private void head(String s) { summary.append(s).append('\n'); }

    private void runTests() {
        log.setLength(0);
        summary.setLength(0);

        // ---------- Q5: DISPLAY ----------
        DisplayMetrics dm = new DisplayMetrics();
        Display d = getWindowManager().getDefaultDisplay();
        d.getRealMetrics(dm);
        DisplayMetrics app = getResources().getDisplayMetrics();

        p("=== DISPLAY (Q5) ===");
        p("  real resolution   : " + dm.widthPixels + " x " + dm.heightPixels + " px");
        p("  app window        : " + app.widthPixels + " x " + app.heightPixels + " px");
        p("  densityDpi        : " + dm.densityDpi);
        p("  density (scale)   : " + dm.density);
        p("  scaledDensity     : " + dm.scaledDensity);
        p("  xdpi / ydpi       : " + dm.xdpi + " / " + dm.ydpi);
        p("  size in dp        : " + Math.round(dm.widthPixels / dm.density) + " x "
                + Math.round(dm.heightPixels / dm.density) + " dp");
        double inches = Math.sqrt(Math.pow(dm.widthPixels / dm.xdpi, 2)
                + Math.pow(dm.heightPixels / dm.ydpi, 2));
        p(String.format("  physical diagonal : %.2f inches", inches));
        p("  rotation          : " + d.getRotation());
        // Winamp main window is 275x116 - what integer scale fits?
        int sx = dm.widthPixels / 275, sy = dm.heightPixels / 116;
        p("  Winamp 275x116 fits at integer scale x" + Math.max(1, Math.min(sx, sy))
                + "  (w/275=" + sx + ", h/116=" + sy + ")");
        p("");

        p("=== DEVICE ===");
        p("  " + Build.MANUFACTURER + " " + Build.MODEL + "  Android "
                + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        p("  targetSdk=" + getApplicationInfo().targetSdkVersion
                + "  legacyStorage=" + Environment.isExternalStorageLegacy());
        p("");

        // ---------- second music location ----------
        p("=== MUSIC LOCATIONS ===");
        int ssd = count("/storage/EF42-73B2");
        int everSolo = count("/storage/emulated/0/EverSoloMusic");
        int music = count("/storage/emulated/0/Music");
        p("  /storage/EF42-73B2                  audio files (depth<=4): " + ssd);
        p("  /storage/emulated/0/EverSoloMusic   audio files (depth<=4): " + everSolo);
        p("  /storage/emulated/0/Music           audio files (depth<=4): " + music);
        p("");

        // full root listing so we can diff against what the device API exposes
        p("=== /storage/EF42-73B2 root, full listing (API showed only 68) ===");
        File[] rootEntries = new File("/storage/EF42-73B2").listFiles();
        if (rootEntries != null) {
            String[] names = new String[rootEntries.length];
            for (int i = 0; i < rootEntries.length; i++) {
                names[i] = (rootEntries[i].isDirectory() ? "[d] " : "[f] ") + rootEntries[i].getName();
            }
            Arrays.sort(names);
            p("  " + names.length + " entries:");
            for (String n : names) p("    " + n);
        }
        p("");

        // ---------- D2: can we read tags straight from the file? ----------
        p("=== TAG READING (validates reading the library ourselves) ===");
        File flac = findByExt(new File("/storage/EF42-73B2"), 0, ".flac");
        boolean tagsOk = false;
        if (flac != null) {
            p("  file: " + flac.getName());
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(flac.getAbsolutePath());
                String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String num = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
                String year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
                byte[] art = mmr.getEmbeddedPicture();
                p("    artist=" + artist);
                p("    album =" + album);
                p("    title =" + t);
                p("    track =" + num + "  year=" + year + "  durationMs=" + dur);
                p("    embedded artwork: " + (art == null ? "none" : art.length + " bytes"));
                tagsOk = artist != null || t != null;
            } catch (Exception e) {
                p("    FAILED: " + e);
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        } else {
            p("  no .flac found to test");
        }
        p("");

        // ---------- how long does a full library scan take? ----------
        p("=== FULL SCAN TIMING (unbounded depth, both volumes) ===");
        long t0 = System.currentTimeMillis();
        int total = countDeep(new File("/storage/EF42-73B2"), 0)
                + countDeep(new File("/storage/emulated/0/EverSoloMusic"), 0);
        long ms = System.currentTimeMillis() - t0;
        p("  " + total + " audio files in " + ms + " ms");

        head("SCREEN: " + dm.widthPixels + "x" + dm.heightPixels + " px, " + dm.densityDpi + " dpi, density " + dm.density);
        head("  usable dp        : " + Math.round(dm.widthPixels / dm.density) + "x"
                + Math.round(dm.heightPixels / dm.density));
        head("TAGS READABLE      : " + tagsOk);
        head("TOTAL AUDIO FILES  : " + total + "  (scanned in " + ms + " ms)");
        head("  SSD / EverSoloMusic / Music: " + ssd + " / " + everSolo + " / " + music);

        final String full = summary + "\n----------------\n" + log;
        out.setText(full);
        post(full);
    }

    private boolean isAudio(String n) {
        n = n.toLowerCase();
        return n.endsWith(".flac") || n.endsWith(".mp3") || n.endsWith(".wav")
                || n.endsWith(".dsf") || n.endsWith(".dff") || n.endsWith(".m4a")
                || n.endsWith(".ape") || n.endsWith(".ogg") || n.endsWith(".aiff");
    }

    private int count(String path) { return countAudio(new File(path), 0, 4); }
    private int countDeep(File f, int d) { return countAudio(f, d, 12); }

    private int countAudio(File dir, int depth, int max) {
        if (depth > max) return 0;
        File[] f = dir.listFiles();
        if (f == null) return 0;
        int n = 0;
        for (File x : f) {
            if (x.isFile() && isAudio(x.getName())) n++;
            else if (x.isDirectory()) n += countAudio(x, depth + 1, max);
        }
        return n;
    }

    private File findByExt(File dir, int depth, String ext) {
        if (depth > 4) return null;
        File[] f = dir.listFiles();
        if (f == null) return null;
        for (File x : f) if (x.isFile() && x.getName().toLowerCase().endsWith(ext)) return x;
        for (File x : f) {
            if (x.isDirectory()) {
                File r = findByExt(x, depth + 1, ext);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void post(final String body) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(REPORT_URL).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
                final int code = c.getResponseCode();
                runOnUiThread(() -> out.setText("[report sent: HTTP " + code + "]\n\n" + body));
            } catch (Exception e) {
                runOnUiThread(() -> out.setText("[report not sent - read below]\n\n" + body));
            }
        }).start();
    }
}
