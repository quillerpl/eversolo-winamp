package android.util;

/**
 * Just enough android.util.Log to compile the real Logs class on a desktop JVM.
 *
 * The alternative was to make Logs Android-free, but its whole job is to be the thing that
 * writes to the device's log - and the sequencer under test does log. A stub keeps the
 * production code exactly as it ships and lets the tests see what it would have written.
 */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6;

    /** Quiet by default: the tests print their own results. -Dlog=on to see the app's. */
    private static final boolean SHOW = "on".equals(System.getProperty("log"));

    public static int println(int priority, String tag, String msg) {
        if (SHOW) System.out.println("    [" + tag + "] " + msg);
        return 0;
    }

    public static int i(String tag, String msg) { return println(INFO, tag, msg); }
    public static int w(String tag, String msg) { return println(WARN, tag, msg); }
    public static int e(String tag, String msg) { return println(ERROR, tag, msg); }
    public static String getStackTraceString(Throwable t) { return String.valueOf(t); }

    private Log() {}
}
