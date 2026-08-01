package org.eversolo.winamp.core;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The app's own logging, because this device has no usable ADB and therefore no logcat
 * (ANSWERS_Q1_Q7.md Q6). Everything goes to an in-memory ring buffer that can be read on
 * the device itself, and optionally shipped to a machine on the LAN.
 *
 * Without this, a misbehaving build on the Eversolo is a black box.
 */
public final class Logs {

    public static final int CAPACITY = 2000;

    private static final Deque<String> BUFFER = new ArrayDeque<>(CAPACITY);
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.UK);

    public interface Listener {
        void onLine(String line);
    }

    private Logs() {}

    public static void d(String tag, String msg) { add("D", tag, msg); }
    public static void i(String tag, String msg) { add("I", tag, msg); }
    public static void w(String tag, String msg) { add("W", tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        add("E", tag, msg + (t == null ? "" : " :: " + t));
        if (t != null) {
            for (StackTraceElement el : t.getStackTrace()) {
                add("E", tag, "    at " + el);
            }
        }
    }

    private static void add(String level, String tag, String msg) {
        String line = TS.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        List<Listener> snapshot;
        synchronized (BUFFER) {
            if (BUFFER.size() >= CAPACITY) BUFFER.removeFirst();
            BUFFER.addLast(line);
            snapshot = new ArrayList<>(LISTENERS);
        }
        // Also emit to logcat - harmless, and useful if ADB ever becomes available.
        Log.println(Log.INFO, tag, msg);
        for (Listener l : snapshot) {
            try { l.onLine(line); } catch (Exception ignored) {}
        }
    }

    public static List<String> snapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : snapshot()) sb.append(s).append('\n');
        return sb.toString();
    }

    public static void clear() {
        synchronized (BUFFER) { BUFFER.clear(); }
    }

    public static void addListener(Listener l) {
        synchronized (BUFFER) { LISTENERS.add(l); }
    }

    public static void removeListener(Listener l) {
        synchronized (BUFFER) { LISTENERS.remove(l); }
    }
}
