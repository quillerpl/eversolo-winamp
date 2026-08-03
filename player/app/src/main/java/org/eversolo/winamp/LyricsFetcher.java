package org.eversolo.winamp;

import android.os.Handler;
import android.os.Looper;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.tags.Json;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Asks LRCLIB for the words to one track.
 *
 * One track, on demand, from the lyrics window - not a library sweep. The sweep is
 * `fetch-lyrics.py`, which runs from a Mac over the device's SMB share where it can be
 * watched, resumed and fixed without a build. This is for the album you added last week.
 *
 * LRCLIB (https://lrclib.net) is free, needs no account and no key, and is run by volunteers
 * for exactly this. We identify ourselves properly and ask for one thing at a time.
 */
public final class LyricsFetcher {

    private static final String TAG = "LyricsFetch";
    private static final String API = "https://lrclib.net/api";
    private static final String UA =
            "EversoloWinamp/1.2 (https://github.com/quillerpl/eversolo-winamp)";
    private static final int TIMEOUT_MS = 15_000;

    /** Nothing sane is bigger; a reply that is has gone wrong somewhere. */
    private static final int MAX_BYTES = 512 * 1024;

    public interface Listener {
        /** {@code lrc} is the file contents when found, else null with a reason to show. */
        void onResult(String lrc, String message);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Look one track up off the UI thread and answer back on it. */
    public void fetch(final String artist, final String title, final String album,
                      final long durationMs, final Listener listener) {
        if (artist == null || artist.isEmpty() || title == null || title.isEmpty()) {
            listener.onResult(null, "This track has no artist or title tag to search with");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String lrc = null, message;
                try {
                    lrc = lookup(artist, title, album == null ? "" : album,
                            Math.round(durationMs / 1000.0));
                    message = lrc != null ? null : "No timed lyrics found for this track";
                } catch (Throwable t) {
                    Logs.w(TAG, "lookup failed: " + t);
                    message = "Could not reach the lyrics service";
                }
                final String result = lrc, msg = message;
                ui.post(new Runnable() {
                    @Override public void run() { listener.onResult(result, msg); }
                });
            }
        }, "lyrics-fetch").start();
    }

    /**
     * Exact match first: artist, title, album and duration together are what stop you being
     * handed the words to a different recording of the same song. Failing that, a search that
     * only accepts a result within two seconds of the right length.
     */
    private String lookup(String artist, String title, String album, long seconds)
            throws Exception {
        String q = "artist_name=" + enc(artist) + "&track_name=" + enc(title)
                + "&album_name=" + enc(album) + "&duration=" + seconds;
        String body = get(API + "/get?" + q);
        String synced = body == null ? null : Json.string(body, "syncedLyrics");
        if (synced != null) {
            Logs.i(TAG, "exact match for " + artist + " - " + title);
            return synced;
        }

        body = get(API + "/search?artist_name=" + enc(artist) + "&track_name=" + enc(title));
        if (body == null) return null;
        // The search returns an array. Walk the objects and take the first that both has
        // timings and is the right length.
        for (String obj : body.split("\\},\\s*\\{")) {
            String s = Json.string(obj, "syncedLyrics");
            if (s == null) continue;
            Long dur = Json.number(obj, "duration");
            if (dur != null && Math.abs(dur - seconds) <= 2) {
                Logs.i(TAG, "search match for " + artist + " - " + title);
                return s;
            }
        }
        return null;
    }

    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            int code = c.getResponseCode();
            if (code == 404) return null;                 // simply not in the database
            if (code != 200) {
                Logs.w(TAG, "HTTP " + code + " from " + url);
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) break;
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
