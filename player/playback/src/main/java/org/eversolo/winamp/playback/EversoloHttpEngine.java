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

            return new PlaybackState(
                    PlaybackState.fromDeviceState(o.optInt("state", -1)),
                    title,
                    artist,
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

    /**
     * Start feeding the visualiser.
     *
     * Five times a second, not thirty: this is a small Android box that is also decoding
     * audio, and API_FINDINGS puts the safe request spacing at 0.15 s. The window eases
     * between the frames it gets, which is what makes it look continuous.
     */
    public void startSpectrum(final SpectrumListener listener, final int bars) {
        stopSpectrum();
        final Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && running) {
                float[] values = fetchSpectrum(bars);
                if (values != null) {
                    try { listener.onSpectrum(values); } catch (Exception ignored) {}
                }
                try { Thread.sleep(180); } catch (InterruptedException e) { return; }
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
        if (body == null) return null;
        try {
            if (!loggedSpectrumShape) {
                loggedSpectrumShape = true;
                Logs.i(TAG, "getSpectrum first response: "
                        + body.substring(0, Math.min(400, body.length())));
            }
            JSONObject o = new JSONObject(body);
            JSONArray a = firstArray(o, "fft_value", "fft_level", "freqs_value", "value");
            if (a == null || a.length() == 0) return null;

            // Resample whatever came back into the number of bars the window draws.
            float[] raw = new float[a.length()];
            float max = 0f;
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (float) Math.abs(a.optDouble(i, 0));
                max = Math.max(max, raw[i]);
            }
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

    private static JSONArray firstArray(JSONObject o, String... names) {
        for (String n : names) {
            JSONArray a = o.optJSONArray(n);
            if (a != null) return a;
            JSONObject nested = o.optJSONObject(n);
            if (nested != null) {
                for (String m : names) {
                    JSONArray inner = nested.optJSONArray(m);
                    if (inner != null) return inner;
                }
            }
        }
        return null;
    }

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
