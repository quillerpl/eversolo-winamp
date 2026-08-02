package org.eversolo.winamp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.eversolo.winamp.core.CrashHandler;
import org.eversolo.winamp.core.LogShipper;
import org.eversolo.winamp.core.Logs;

/**
 * Hosts the player in a window drawn above every other app.
 *
 * Necessary because both ways of starting playback on this device - openFile and DLNA -
 * bring the Eversolo's stock player to the front, and an ordinary app cannot stop that.
 * An overlay does not have to: the stock player comes forward underneath us and is never
 * seen. See API_FINDINGS.md §2 and ANSWERS_Q1_Q7.md.
 */
public class OverlayService extends Service {

    private static final String TAG = "Overlay";
    private static final String CHANNEL = "player";
    private static final int NOTIF_ID = 1;
    public static final String ACTION_STOP = "org.eversolo.winamp.STOP_OVERLAY";

    private WindowManager wm;
    private View overlay;
    private WinampUi playerUi;
    private FullScreen fullScreen;
    private WindowManager.LayoutParams overlayParams;

    public static boolean canDraw(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        startForeground(NOTIF_ID, buildNotification());

        if (!canDraw(this)) {
            Logs.w(TAG, "overlay permission not granted; stopping");
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        playerUi = new WinampUi(this, this::stopSelf);

        // A focusable, full-screen overlay: it IS the interface while it is up, so the
        // stock player never becomes visible. The ✕ button and the notification action
        // both dismiss it, so the device is never left unusable.
        FrameLayout host = new FrameLayout(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                // Every touch, wherever it lands, brings the device's side bar back. Read
                // here rather than in the windows so that a tap on empty background counts
                // too - the bar must never be something only a gesture can recover.
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && fullScreen != null) {
                    fullScreen.onUserTouch();
                }
                return super.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    if (playerUi != null && playerUi.handleBack()) return true;
                    stopSelf();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        host.addView(playerUi.build());
        overlay = host;

        fullScreen = new FullScreen(host);
        fullScreen.setWindowSizer(this::pinOverlayWidth);
        playerUi.attachFullScreen(fullScreen);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        overlayParams = lp;

        try {
            wm.addView(overlay, lp);
            Logs.i(TAG, "overlay window added (type=" + type + ")");
            playerUi.startScanIfNeeded();
        } catch (Throwable t) {
            Logs.e(TAG, "could not add overlay window", t);
            LogShipper.shipBuffer();
            stopSelf();
        }
    }

    /**
     * Hold the window at {@code px} wide, or go back to MATCH_PARENT when {@code px} is 0.
     *
     * Needed because MATCH_PARENT is resolved against a frame this firmware shrinks whenever
     * the side bar is showing: the window was going 2160 -> 2000 -> 2160 on every touch, and
     * the whole Winamp layout was being re-scaled with it. FLAG_LAYOUT_NO_LIMITS goes on at
     * the same time, because without it the system clips the window back to that same frame.
     *
     * The width is one measured on this device, never a guess - see FullScreen.
     */
    private void pinOverlayWidth(int px) {
        if (wm == null || overlay == null || overlayParams == null) return;
        int wanted = px > 0 ? px : WindowManager.LayoutParams.MATCH_PARENT;
        if (overlayParams.width == wanted) return;
        overlayParams.width = wanted;
        if (px > 0) {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        } else {
            overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }
        try {
            wm.updateViewLayout(overlay, overlayParams);
            Logs.i(TAG, "overlay width pinned to " + (px > 0 ? px + "px" : "MATCH_PARENT"));
        } catch (Throwable t) {
            Logs.w(TAG, "could not repin the overlay width: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (overlay != null && wm != null) wm.removeView(overlay);
        } catch (Throwable t) {
            Logs.w(TAG, "removeView failed: " + t);
        }
        if (fullScreen != null) fullScreen.destroy();
        if (playerUi != null) playerUi.destroy();
        Logs.i(TAG, "overlay stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Player", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps the Winamp window on screen");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Eversolo Winamp")
                .setContentText("Player window is on screen")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "Close player", pi).build())
                .build();
    }
}
