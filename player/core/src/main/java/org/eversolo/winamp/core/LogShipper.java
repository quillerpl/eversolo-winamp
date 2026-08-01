package org.eversolo.winamp.core;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Sends the log buffer to a development machine on the LAN.
 *
 * Off unless a host is set. Failure is always silent and harmless - this is a
 * convenience for development, never something the player depends on.
 *
 * On the dev machine, anything that accepts a POST will do:
 *     python3 -m http.server is not enough (it rejects POST); use a 3-line handler.
 */
public final class LogShipper {

    private static final String TAG = "LogShipper";
    private static volatile String endpoint = null;

    private LogShipper() {}

    /** e.g. "192.168.1.61:8765" - or null to disable. */
    public static void setHost(String hostAndPort) {
        endpoint = (hostAndPort == null || hostAndPort.trim().isEmpty())
                ? null : "http://" + hostAndPort.trim() + "/report";
        Logs.i(TAG, endpoint == null ? "log shipping disabled" : "log shipping to " + endpoint);
    }

    public static boolean isEnabled() {
        return endpoint != null;
    }

    /** Fire-and-forget. Never blocks the caller, never throws. */
    public static void ship(final String body) {
        final String url = endpoint;
        if (url == null) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {
                // Deliberately silent: a missing dev machine must never affect the player.
            }
        }, "log-shipper").start();
    }

    public static void shipBuffer() {
        ship(Logs.dump());
    }
}
