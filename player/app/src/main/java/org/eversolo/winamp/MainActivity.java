package org.eversolo.winamp;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

import org.eversolo.winamp.core.CrashHandler;
import org.eversolo.winamp.core.LogShipper;
import org.eversolo.winamp.core.Logs;

/**
 * Permission gate and launcher.
 *
 * The real interface lives in {@link OverlayService}, drawn above every other app, because
 * starting playback on this device always brings the stock player to the front and an
 * ordinary app cannot stop that.
 *
 * If the overlay permission cannot be granted on this firmware, the app falls back to
 * running the same interface as a normal screen - usable, but the stock player will keep
 * appearing over it.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Main";
    private static final int REQ_STORAGE = 1;
    private static final String DEV_HOST = "192.168.1.61:8765";

    private LinearLayout gate;
    private TextView explain;
    private WinampUi inlineUi;      // only used in fallback mode
    private FullScreen inlineFullScreen;
    private boolean fallbackMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.install(this);
        LogShipper.setHost(DEV_HOST);
        logEnvironment();

        buildGate();

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (fallbackMode) return;
        if (!hasStorage() || !OverlayService.canDraw(this)) { updateGate(); return; }
        // The player draws itself from a Winamp skin and release builds carry none - the
        // classic one is Nullsoft's artwork, not ours to hand out. Without one there is
        // literally nothing to draw, so this is the only screen that can be shown, and it
        // has to be plain Android views rather than the skinned windows.
        if (new SkinStore(this).load() == null) { buildSkinGate(); return; }
        launchOverlay();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] granted) {
        updateGate();
    }

    private boolean hasStorage() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void launchOverlay() {
        Logs.i(TAG, "overlay permission present; starting overlay service");
        Intent svc = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
        finish();     // the overlay is the interface from here on
    }

    /**
     * First run with no skin on the device. Lists any that turn up and explains how to get
     * one; after that the Winamp logo in the player's bottom-right corner comes back here.
     */
    private void buildSkinGate() {
        SkinStore store = new SkinStore(this);
        List<File> found = store.findAll();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.BLACK);
        box.setPadding(dp(28), dp(24), dp(28), dp(24));

        TextView title = new TextView(this);
        title.setText("CHOOSE A SKIN");
        title.setTextColor(0xFF00FF66);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        box.addView(title);

        TextView words = new TextView(this);
        words.setTextColor(0xFFCCCCCC);
        words.setTypeface(Typeface.MONOSPACE);
        words.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        words.setPadding(0, dp(14), 0, dp(16));
        words.setText(found.isEmpty()
                ? "This player wears a classic Winamp skin, and it does not come with one -"
                  + " those are other people's artwork.\n\n"
                  + "Get a .wsz skin file (there are thousands at skins.webamp.org), put it"
                  + " on a USB stick or in this folder:\n\n    " + SkinStore.HOME + "\n\n"
                  + "then tap Look again."
                : "Tap the one you want. You can change it later from the Winamp logo in the"
                  + " bottom-right corner of the player.");
        box.addView(words);

        for (final File f : found) {
            Button b = new Button(this);
            b.setText(f.getName() + "\n" + f.getParent());
            b.setAllCaps(false);
            b.setOnClickListener(v -> { store.choose(f); launchOverlay(); });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = dp(8);
            box.addView(b, blp);
        }

        Button again = new Button(this);
        again.setText(found.isEmpty() ? "Look again" : "Look again for more");
        again.setOnClickListener(v -> buildSkinGate());
        box.addView(again);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
        Logs.i(TAG, "skin gate shown, " + found.size() + " skin(s) found");
    }

    // ------------------------------------------------------------------ gate UI

    private void buildGate() {
        gate = new LinearLayout(this);
        gate.setOrientation(LinearLayout.VERTICAL);
        gate.setBackgroundColor(Color.BLACK);
        gate.setPadding(dp(28), dp(24), dp(28), dp(24));
        gate.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("EVERSOLO WINAMP");
        title.setTextColor(0xFF00FF66);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        gate.addView(title);

        explain = new TextView(this);
        explain.setTextColor(0xFFCCCCCC);
        explain.setTypeface(Typeface.MONOSPACE);
        explain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        explain.setPadding(0, dp(14), 0, dp(18));
        gate.addView(explain);

        Button grant = new Button(this);
        grant.setText("Open the setting");
        grant.setOnClickListener(v -> openOverlaySettings());
        gate.addView(grant);

        Button skip = new Button(this);
        skip.setText("Skip - run without it");
        skip.setOnClickListener(v -> runInline());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        gate.addView(skip, lp);

        setContentView(gate);
        updateGate();
    }

    private void updateGate() {
        if (!hasStorage()) {
            explain.setText("Waiting for permission to read your music...");
            return;
        }
        explain.setText(
                "This player needs to draw over other apps.\n\n"
              + "Starting a track always brings the Eversolo's own player to the front, and "
              + "a normal app can't prevent that. Drawing on top means its player comes up "
              + "behind this window instead, so you never see it.\n\n"
              + "Tap 'Open the setting', then switch on 'Display over other apps' (sometimes "
              + "called 'Draw over other apps') for Eversolo Winamp, then come back.");
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Logs.w(TAG, "per-app overlay settings screen missing: " + e);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                Logs.e(TAG, "no overlay settings screen at all on this firmware", e2);
                explain.setText("This firmware does not expose the 'display over other apps' "
                        + "setting, so the floating window is not available.\n\n"
                        + "Tap 'Skip - run without it' to use the player as a normal screen. "
                        + "The Eversolo's own player will keep appearing over it when a "
                        + "track starts.");
                LogShipper.shipBuffer();
            }
        }
    }

    /** Fallback: run the same interface as an ordinary screen. */
    private void runInline() {
        fallbackMode = true;
        Logs.i(TAG, "running inline (no overlay)");
        inlineUi = new WinampUi(this, this::finish);
        View content = inlineUi.build();
        setContentView(content);
        inlineFullScreen = new FullScreen(content);
        inlineUi.attachFullScreen(inlineFullScreen);
        inlineUi.startScanIfNeeded();
    }

    /**
     * The overlay reads touches in its own host view; here the activity is the host. Either
     * way every touch, wherever it lands, brings the device's side bar back.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && inlineFullScreen != null) {
            inlineFullScreen.onUserTouch();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (inlineUi != null && inlineUi.handleBack()) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (inlineFullScreen != null) inlineFullScreen.destroy();
        if (inlineUi != null) inlineUi.destroy();
        super.onDestroy();
    }

    private void logEnvironment() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Logs.i(TAG, "device " + Build.MANUFACTURER + " " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Logs.i(TAG, "screen " + dm.widthPixels + "x" + dm.heightPixels
                + " density=" + dm.density + " dpi=" + dm.densityDpi);
        Logs.i(TAG, "targetSdk=" + getApplicationInfo().targetSdkVersion
                + " legacyStorage=" + Environment.isExternalStorageLegacy()
                + " canDrawOverlays=" + OverlayService.canDraw(this));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
