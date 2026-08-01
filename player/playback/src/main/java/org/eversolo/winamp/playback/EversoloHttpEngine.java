package org.eversolo.winamp.playback;

import org.eversolo.winamp.core.Logs;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the Eversolo's own playback engine over its local HTTP API.
 *
 * The app runs ON the device, so this is a loopback call to 127.0.0.1:9529. None of the
 * Wi-Fi flakiness measured from a laptop (1 request in 25 failing, 5 s stalls) applies
 * here - see ANSWERS_Q1_Q7.md Q3.
 *
 * Why go through the device instead of playing audio ourselves: its engine bypasses
 * Android's sample-rate conversion and reaches the DACs bit-perfect. That is the entire
 * point of the project.
 */
public final class EversoloHttpEngine implements PlaybackEngine {

    private static final String TAG = "Playback";
    private static final String BASE = "http://127.0.0.1:9529";

    private static final int POLL_IDLE_MS = 500;
    /** Within this long of the end, poll hard so the playlist handover is not missed. */
    private static final int NEAR_END_MS = 5000;
    private static final int POLL_NEAR_END_MS = 100;
    /** How long to wait for the device to confirm it started the track we asked for. */
    private static final int CONFIRM_TIMEOUT_MS = 2500;

    private final List<Listener> listeners = new ArrayList<>();
    private volatile PlaybackState current = PlaybackState.EMPTY;
    private volatile boolean running;
    private Thread poller;

    /**
     * Every command goes through here.
     *
     * Android forbids network calls on the UI thread, and these are all invoked from
     * button taps. Calling them inline threw NetworkOnMainThreadException, which the
     * error handling below swallowed into a log line - so the transport buttons silently
     * did nothing. Single-threaded so commands stay in the order the user pressed them.
     */
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playback-cmd");
        t.setDaemon(true);
        return t;
    });

    private void submit(Runnable r) {
        try {
            commands.execute(r);
        } catch (Throwable t) {
            Logs.w(TAG, "could not queue command: " + t);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        if (running) return;
        running = true;
        poller = new Thread(() -> {
            while (running) {
                long sleep = POLL_IDLE_MS;
                try {
                    PlaybackState s = fetchState();
                    if (s != null) {
                        publish(s);
                        // Tighten up near the end of a track: the playlist handover fires
                        // ~400 ms before the end, which a 500 ms tick would step straight
                        // over.
                        if (s.durationMs > 0 && s.durationMs - s.positionMs < NEAR_END_MS) {
                            sleep = POLL_NEAR_END_MS;
                        }
                    }
                } catch (Throwable t) {
                    Logs.w(TAG, "poll failed: " + t);
                }
                try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
            }
        }, "playback-poll");
        poller.setDaemon(true);
        poller.start();
        Logs.i(TAG, "engine started, polling " + BASE);
    }

    @Override
    public void stop() {
        running = false;
        if (poller != null) poller.interrupt();
        commands.shutdownNow();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean play(String absolutePath, String expectedTitle) {
        String url = BASE + "/ZidooFileControl/openFile?path=" + enc(absolutePath) + "&type=0";
        String response = get(url);
        if (response == null) {
            Logs.w(TAG, "openFile: no response for " + absolutePath);
            return false;
        }
        Logs.i(TAG, "openFile -> " + response.trim() + "  " + absolutePath);

        // NEVER trust status 200 here. openFile answers 200 for files it silently
        // refuses to play, so confirm against getState.
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            PlaybackState s = fetchState();
            if (s != null) {
                publish(s);
                if (s.status == PlaybackState.Status.PLAYING
                        && titleMatches(s.title, expectedTitle, absolutePath)) {
                    return true;
                }
            }
            try { Thread.sleep(120); } catch (InterruptedException e) { break; }
        }
        Logs.w(TAG, "openFile was NOT confirmed playing: wanted '" + expectedTitle
                + "', device reports '" + current.title + "'");
        return false;
    }

    /**
     * The device reports the filename stem as the title for files started via openFile,
     * whereas our tags give the real title. Accept either, compared loosely.
     */
    private static boolean titleMatches(String deviceTitle, String expectedTitle, String path) {
        if (deviceTitle == null) return false;
        String d = normalise(deviceTitle);
        if (d.isEmpty()) return false;
        if (expectedTitle != null && !normalise(expectedTitle).isEmpty()
                && d.contains(normalise(expectedTitle))) return true;
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String n = normalise(name);
        return !n.isEmpty() && (d.contains(n) || n.contains(d));
    }

    private static String normalise(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** playOrPause is a TOGGLE, not "pause" - always read the state first. */
    @Override
    public void pause() {
        if (current.isPlaying()) togglePlayPause();
    }

    @Override
    public void resume() {
        if (!current.isPlaying()) togglePlayPause();
    }

    @Override
    public void togglePlayPause() {
        submit(() -> {
            Logs.i(TAG, "playOrPause -> " + get(BASE + "/ZidooMusicControl/v2/playOrPause"));
            nudge();
        });
    }

    @Override
    public void next() {
        submit(() -> {
            Logs.i(TAG, "playNext -> " + get(BASE + "/ZidooMusicControl/v2/playNext"));
            nudge();
        });
    }

    @Override
    public void previous() {
        submit(() -> {
            Logs.i(TAG, "playLast -> " + get(BASE + "/ZidooMusicControl/v2/playLast"));
            nudge();
        });
    }

    @Override
    public void seekTo(final long ms) {
        final long target = Math.max(0, ms);
        submit(() -> {
            Logs.i(TAG, "seekTo " + target + " -> "
                    + get(BASE + "/ZidooMusicControl/v2/seekTo?time=" + target));
            nudge();
        });
    }

    @Override
    public void setVolume(int volume) {
        final int v = Math.max(0, Math.min(200, volume));
        submit(() -> {
            get(BASE + "/ZidooMusicControl/v2/setDevicesVolume?volume=" + v);
            nudge();
        });
    }

    /**
     * Repeat-one. Phase 3 will switch this on while the app is driving a playlist, so the
     * device stops advancing into the next file in the folder on its own (Q2). Left off in
     * Phase 2, where the device's own album-order advance is exactly what we want.
     */
    public void setRepeatOne(final boolean on) {
        submit(() -> get(BASE + "/ZidooMusicControl/v2/setLoopMode?loop=" + (on ? 1 : 0)));
    }

    /** Re-read state promptly after a command instead of waiting for the next poll tick. */
    private void nudge() {
        new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            PlaybackState s = fetchState();
            if (s != null) publish(s);
        }, "playback-nudge").start();
    }

    // ---------------------------------------------------------------- state

    @Override
    public PlaybackState state() { return current; }

    private PlaybackState fetchState() {
        String body = get(BASE + "/ZidooMusicControl/v2/getState");
        if (body == null) return null;
        try {
            JSONObject o = new JSONObject(body);
            JSONObject music = o.optJSONObject("playingMusic");
            JSONObject vol = o.optJSONObject("volumeData");

            String title = music == null ? "" : music.optString("title", "");
            String artist = music == null ? "" : music.optString("artist", "");
            String album = music == null ? "" : music.optString("album", "");

            return new PlaybackState(
                    PlaybackState.fromDeviceState(o.optInt("state", -1)),
                    title,
                    artist,
                    album,
                    o.optLong("position", 0),
                    o.optLong("duration", 0),
                    // note the device's own spelling: currenttVolume, two t's
                    vol == null ? 0 : vol.optInt("currenttVolume", 0),
                    vol == null ? 200 : vol.optInt("maxVolume", 200),
                    vol != null && vol.optBoolean("isMute", false),
                    music == null ? 0 : music.optInt("sampleRateNumber", 0),
                    music == null ? 0 : kbps(music.optString("bitrate", "")),
                    music == null ? 0 : music.optInt("bits", 0),
                    music == null ? 0 : music.optInt("channels", 0));
        } catch (Exception e) {
            Logs.w(TAG, "could not parse getState: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- spectrum

    public interface SpectrumListener {
        /** One value per bar, already normalised to 0..1. */
        void onSpectrum(float[] bars);
    }

    private volatile Thread spectrumPoller;
    private volatile float spectrumPeak = 1f;
    private volatile boolean loggedSpectrumShape = false;
    private volatile boolean loggedNoArray = false;
    private volatile boolean loggedZeros = false;
    private volatile boolean loggedLevelOnly = false;
    private volatile boolean loggedPlayingShape = false;
    private volatile String spectrumProblem = "waiting for the first reply";

    /**
     * Start feeding the visualiser.
     *
     * Five times a second, not thirty: this is a small Android box that is also decoding
     * audio, and API_FINDINGS puts the safe request spacing at 0.15 s. The window eases
     * between the frames it gets, which is what makes it look continuous.
     */
    public void startSpectrum(final SpectrumListener listener, final int bars,
                              final int intervalMs) {
        stopSpectrum();
        final Thread t = new Thread(() -> {
            long reported = 0;
            while (!Thread.currentThread().isInterrupted() && running) {
                long began = System.currentTimeMillis();
                float[] values = fetchSpectrum(bars);
                long took = System.currentTimeMillis() - began;
                if (values != null) {
                    try { listener.onSpectrum(values); } catch (Exception ignored) {}
                }
                // Back off on our own if the device is labouring: never spend more than
                // half the time waiting on it. API_FINDINGS' 0.15 s spacing was measured
                // over Wi-Fi from a laptop; this is a loopback call, but the box is small
                // and it is also decoding audio, so let it set the pace.
                long wait = Math.max(intervalMs, took * 2);
                if (System.currentTimeMillis() - reported > 10000) {
                    reported = System.currentTimeMillis();
                    Logs.i(TAG, "getSpectrum took " + took + " ms, polling every " + wait + " ms");
                }
                try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            }
        }, "spectrum-poll");
        t.setDaemon(true);
        spectrumPoller = t;
        t.start();
        Logs.i(TAG, "spectrum polling started");
    }

    public void stopSpectrum() {
        Thread t = spectrumPoller;
        spectrumPoller = null;
        if (t != null) {
            t.interrupt();
            Logs.i(TAG, "spectrum polling stopped");
        }
    }

    /**
     * Read getSpectrum and boil it down to {@code bars} values in 0..1.
     *
     * The exact shape of this response was never captured during the API survey, so this
     * takes whatever numeric array it can find and scales it against a peak that decays
     * slowly. That way it self-calibrates whether the device reports magnitudes or
     * decibels, and it logs the raw body once so the guesswork can be replaced with facts
     * from the on-device console.
     */
    private float[] fetchSpectrum(int bars) {
        String body = get(BASE + "/ZidooMusicControl/v2/getSpectrum");
        if (body == null) {
            spectrumProblem = "no reply from getSpectrum";
            return null;
        }
        try {
            if (!loggedSpectrumShape) {
                loggedSpectrumShape = true;
                Logs.i(TAG, "getSpectrum first response: "
                        + body.substring(0, Math.min(600, body.length())));
            }
            // The first response is usually the paused, empty one. The first response
            // taken while actually playing is the one worth having in the log.
            if (!loggedPlayingShape && current.isPlaying() && body.length() > 70) {
                loggedPlayingShape = true;
                Logs.i(TAG, "getSpectrum while playing: "
                        + body.substring(0, Math.min(900, body.length())));
            }
            JSONObject o = new JSONObject(body);
            JSONArray a = findNumericArray(o, 0);
            if (a == null || a.length() == 0) {
                // Paused or stopped, the device answers with everything empty. That is not
                // a fault, and saying so beats "no numeric array" as an explanation.
                if (!current.isPlaying()) {
                    spectrumProblem = "nothing playing";
                    return null;
                }
                // No bands, but there may still be an overall level. A single number
                // cannot make a spectrum, so this drives every bar together: a VU meter
                // wearing the analyser's clothes, which is better than a dead window.
                double level = o.optDouble("fft_level", 0);
                if (level > 0) {
                    spectrumProblem = null;
                    spectrumPeak = Math.max((float) level, spectrumPeak * 0.98f);
                    float[] flat = new float[bars];
                    float v = (float) Math.min(1.0, level / Math.max(0.0001f, spectrumPeak));
                    for (int i = 0; i < bars; i++) flat[i] = v;
                    if (!loggedLevelOnly) {
                        loggedLevelOnly = true;
                        Logs.i(TAG, "getSpectrum has no bands; driving the analyser from "
                                + "fft_level alone");
                    }
                    return flat;
                }
                spectrumProblem = "getSpectrum has no numeric array";
                if (!loggedNoArray) {
                    loggedNoArray = true;
                    Logs.w(TAG, "no numeric array in getSpectrum: "
                            + body.substring(0, Math.min(600, body.length())));
                }
                return null;
            }

            // Resample whatever came back into the number of bars the window draws.
            float[] raw = new float[a.length()];
            float max = 0f;
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (float) Math.abs(a.optDouble(i, 0));
                max = Math.max(max, raw[i]);
            }
            // dB scales arrive as negatives: optDouble's abs() above already folded them,
            // but a floor of -60 dB reads better than a raw magnitude.
            if (max <= 0.0001f) {
                // A well-formed answer full of zeros. Worth saying out loud: it probably
                // means the device only computes its FFT while its own visualiser screen
                // is up, which is a different problem from the endpoint being wrong.
                spectrumProblem = "getSpectrum returns all zeros";
                if (!loggedZeros) {
                    loggedZeros = true;
                    Logs.w(TAG, "getSpectrum returned " + raw.length
                            + " values, all zero: "
                            + body.substring(0, Math.min(300, body.length())));
                }
                return null;
            }
            spectrumProblem = null;
            spectrumPeak = Math.max(max, spectrumPeak * 0.98f);
            if (spectrumPeak <= 0.0001f) return null;

            float[] out = new float[bars];
            for (int i = 0; i < bars; i++) {
                int from = i * raw.length / bars;
                int to = Math.max(from + 1, (i + 1) * raw.length / bars);
                float sum = 0f;
                for (int j = from; j < to && j < raw.length; j++) sum += raw[j];
                out[i] = Math.min(1f, (sum / (to - from)) / spectrumPeak);
            }
            return out;
        } catch (Exception e) {
            Logs.w(TAG, "could not parse getSpectrum: " + e);
            return null;
        }
    }

    /**
     * The first run of numbers of a plausible length, anywhere in the response.
     *
     * getSpectrum turned out to nest its data twice over. Asked while paused it answers
     *
     *     {"fft_value":"{}","freqs_value":"{}","fft_level":0,"nb_freqs":0}
     *
     * so fft_value is a *string* holding JSON, and what is inside it is an object rather
     * than an array. Looking for a key called fft_value and expecting a JSONArray - which
     * is what the first version did - could never have worked. This walks everything:
     * arrays, objects keyed by index, and strings that turn out to hold more JSON.
     */
    private static JSONArray findNumericArray(Object node, int depth) {
        if (depth > 5) return null;

        // A string that is really a nested document. The device does this.
        if (node instanceof String) {
            String s = ((String) node).trim();
            if (s.length() < 2) return null;
            try {
                if (s.startsWith("[")) return findNumericArray(new JSONArray(s), depth + 1);
                if (s.startsWith("{")) return findNumericArray(new JSONObject(s), depth + 1);
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            if (a.length() >= 8 && isNumeric(a)) return a;
            for (int i = 0; i < a.length(); i++) {
                JSONArray found = findNumericArray(a.opt(i), depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            // An object whose keys are indices - {"0":12,"1":34,...} - is an array wearing
            // a disguise, and is a very likely shape for what is inside fft_value.
            JSONArray byIndex = numbersKeyedByIndex(o);
            if (byIndex != null) return byIndex;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                JSONArray found = findNumericArray(o.opt(keys.next()), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** {"0":..,"1":..} in numeric key order, or null if that is not what this is. */
    private static JSONArray numbersKeyedByIndex(JSONObject o) {
        int n = o.length();
        if (n < 8) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < n; i++) {
            Object v = o.opt(String.valueOf(i));
            if (v == null) return null;
            if (v instanceof Number) { out.put(v); continue; }
            if (v instanceof String) {
                try {
                    out.put(Double.parseDouble((String) v));
                    continue;
                } catch (NumberFormatException e) {
                    return null;
                } catch (org.json.JSONException e) {
                    return null;              // a NaN or infinity in the feed
                }
            }
            return null;
        }
        return out;
    }

    private static boolean isNumeric(JSONArray a) {
        int checked = Math.min(a.length(), 6);
        for (int i = 0; i < checked; i++) {
            Object v = a.opt(i);
            if (v instanceof Number) continue;
            if (v instanceof String) {
                try { Double.parseDouble((String) v); continue; }
                catch (NumberFormatException e) { return false; }
            }
            return false;
        }
        return true;
    }

    /** What is wrong with the spectrum feed, for the window to display. Null when fine. */
    public String spectrumProblem() { return spectrumProblem; }

    /**
     * The device reports the bitrate as text - "128.00 Kbps", or "1411.20 Kbps" for a CD
     * rip - so take the leading number. Anything unexpected reads as "not reported", which
     * the main window shows as an empty box rather than a wrong figure.
     */
    private static int kbps(String s) {
        if (s == null) return 0;
        int end = 0;
        while (end < s.length()
                && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        if (end == 0) return 0;
        try {
            return (int) Math.round(Double.parseDouble(s.substring(0, end)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void publish(PlaybackState s) {
        current = s;
        List<Listener> snapshot;
        synchronized (listeners) { snapshot = new ArrayList<>(listeners); }
        for (Listener l : snapshot) {
            try { l.onState(s); } catch (Exception ignored) {}
        }
    }

    @Override
    public void addListener(Listener l) {
        synchronized (listeners) { listeners.add(l); }
    }

    @Override
    public void removeListener(Listener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    // ---------------------------------------------------------------- http

    private static String enc(String s) {
        try {
            // URLEncoder is form encoding: spaces become '+', which this API accepts,
            // but '+' in a real filename must survive, so fix it up.
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            Logs.w(TAG, "GET failed " + url + " :: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
