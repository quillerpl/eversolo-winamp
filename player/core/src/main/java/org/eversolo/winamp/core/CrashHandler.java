package org.eversolo.winamp.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persists crashes instead of letting them vanish.
 *
 * With no ADB there is no other way to find out why the app died, so the stack trace is
 * written to the app's own files directory, appended to the log buffer, and pushed to the
 * dev machine if log shipping is on.
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "Crash";
    private final Thread.UncaughtExceptionHandler previous;
    private final File dir;

    private CrashHandler(Context ctx, Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
        this.dir = new File(ctx.getFilesDir(), "crashes");
        //noinspection ResultOfMethodCallIgnored
        this.dir.mkdirs();
    }

    public static void install(Context ctx) {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx.getApplicationContext(), prev));
        Logs.i(TAG, "crash handler installed");
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String trace = "CRASH on thread " + t.getName() + "\n" + sw;

            Logs.e(TAG, "uncaught exception on " + t.getName(), e);

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(new Date());
            File f = new File(dir, "crash-" + stamp + ".txt");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write((trace + "\n\n--- log buffer ---\n" + Logs.dump()).getBytes("UTF-8"));
            }

            if (LogShipper.isEnabled()) {
                LogShipper.ship(trace + "\n\n--- log buffer ---\n" + Logs.dump());
                Thread.sleep(600);   // give the shipper a moment before the process dies
            }
        } catch (Throwable ignored) {
            // Nothing useful left to do.
        }
        if (previous != null) previous.uncaughtException(t, e);
    }

    /** Most recent crash file, or null. Shown in the log console so it is not missed. */
    public static File latestCrash(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "crashes");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return null;
        File newest = files[0];
        for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
        return newest;
    }
}
