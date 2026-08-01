package org.eversolo.winamp;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.eversolo.winamp.core.Logs;
import org.eversolo.winamp.dsp.Fft;
import org.eversolo.winamp.dsp.SpectrumBands;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * The spectrum analyser, computed from the file rather than asked for over the API.
 *
 * The device's own `getSpectrum` returns nothing on this unit - `isHasSpectrum` is false
 * for every source - yet its stock player has a working analyser. The explanation is
 * simple: the stock player is the one decoding the audio, so it has the samples. It just
 * does not publish them.
 *
 * We know the path of the track we asked it to play, so we can decode the same file a
 * second time and do our own FFT. The audio never leaves this class - nothing is played,
 * nothing touches the output - so the bit-perfect path is untouched. It costs one FLAC
 * decode, which is a few percent of one core.
 *
 * Staying in step is the interesting part. Decoding runs far faster than real time, so it
 * is paced against the wall clock from the position the device reported, and re-seeks if
 * the two drift apart - which they will, every time the user skips.
 */
public final class FileSpectrum {

    private static final String TAG = "FileSpectrum";

    /** Transform size. 1024 at 48 kHz is 21 ms of audio and 47 Hz per bin. */
    private static final int FFT_SIZE = 1024;
    private static final int BANDS = 19;
    /** Re-seek when the device's position and ours disagree by more than this. */
    private static final long DRIFT_MS = 400;
    /** How often a set of bars is produced. */
    private static final long FRAME_MS = 40;

    public interface Listener {
        void onBands(float[] bands);
        /** Why there is nothing to show, or null when it is working. */
        void onProblem(String problem);
    }

    private final Listener listener;
    private Thread worker;
    private volatile boolean running;
    private volatile String path;
    private volatile long seekToMs = -1;
    private volatile long positionMs;

    public FileSpectrum(Listener listener) {
        this.listener = listener;
    }

    /** Start (or restart) on a track. Safe to call with the same path repeatedly. */
    public synchronized void play(String absolutePath, long fromMs) {
        if (absolutePath == null) { stop(); return; }
        if (absolutePath.equals(path) && running) {
            syncTo(fromMs);
            return;
        }
        stop();
        path = absolutePath;
        seekToMs = Math.max(0, fromMs);
        running = true;
        worker = new Thread(this::run, "file-spectrum");
        worker.setDaemon(true);
        worker.start();
        Logs.i(TAG, "analysing " + absolutePath + " from " + fromMs + " ms");
    }

    /** The device's idea of where it is. A big disagreement means a seek or a skip. */
    public void syncTo(long deviceMs) {
        if (!running) return;
        if (Math.abs(deviceMs - positionMs) > DRIFT_MS) seekToMs = Math.max(0, deviceMs);
    }

    public synchronized void stop() {
        running = false;
        path = null;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    // ---------------------------------------------------------------- the work

    private void run() {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);

            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; format = f; break; }
            }
            if (track < 0) {
                problem("no audio track in the file");
                return;
            }
            extractor.selectTrack(track);

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            Logs.i(TAG, "decoding " + format.getString(MediaFormat.KEY_MIME) + " "
                    + sampleRate + " Hz " + channels + " ch");

            decodeLoop(extractor, codec, sampleRate, channels);
        } catch (Throwable t) {
            // A codec this firmware does not have, or a file that vanished. Say so rather
            // than leaving the analyser mysteriously flat.
            Logs.w(TAG, "cannot analyse " + path + ": " + t);
            problem("cannot decode this file");
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
        }
    }

    private void decodeLoop(MediaExtractor extractor, MediaCodec codec,
                            int sampleRate, int channels) {
        final Fft fft = new Fft(FFT_SIZE);
        final SpectrumBands bands = new SpectrumBands(BANDS, FFT_SIZE / 2, sampleRate);
        final float[] magnitudes = new float[FFT_SIZE / 2];
        final float[] out = new float[BANDS];
        final float[] mono = new float[FFT_SIZE];
        int filled = 0;
        float peak = 1e-6f;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        long startedAt = 0;
        long startPositionMs = 0;
        long lastEmit = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            long wanted = seekToMs;
            if (wanted >= 0) {
                seekToMs = -1;
                extractor.seekTo(wanted * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                codec.flush();
                inputDone = false;
                filled = 0;
                startedAt = System.currentTimeMillis();
                startPositionMs = wanted;
                positionMs = wanted;
            }

            if (!inputDone) {
                int in = codec.dequeueInputBuffer(2000);
                if (in >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(in);
                    int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = codec.dequeueOutputBuffer(info, 2000);
            if (outIndex < 0) {
                if (inputDone && info.size == 0) return;      // end of the track
                continue;
            }
            ByteBuffer buffer = codec.getOutputBuffer(outIndex);
            if (buffer != null && info.size > 0) {
                ShortBuffer pcm = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                int frames = info.size / 2 / Math.max(1, channels);
                for (int f = 0; f < frames; f++) {
                    // Mono sum: an analyser shows the music, not the stereo image.
                    float sample = 0f;
                    for (int c = 0; c < channels; c++) sample += pcm.get(f * channels + c);
                    mono[filled++] = sample / (channels * 32768f);
                    if (filled == FFT_SIZE) {
                        fft.magnitudes(mono, magnitudes);
                        peak = Math.max(SpectrumBands.loudest(magnitudes), peak * 0.995f);
                        bands.fill(magnitudes, peak, out);
                        filled = 0;
                        // Pace it: decoding runs many times faster than playback, so hold
                        // each frame back until the music would actually have reached here.
                        positionMs = info.presentationTimeUs / 1000L;
                        long due = startedAt + (positionMs - startPositionMs);
                        long wait = due - System.currentTimeMillis();
                        if (wait > 0) {
                            try { Thread.sleep(Math.min(wait, 250)); }
                            catch (InterruptedException e) { return; }
                        }
                        // One window is 21 ms of audio; the display cannot use 47 frames a
                        // second and the UI thread should not be asked to.
                        long now = System.currentTimeMillis();
                        if (now - lastEmit >= FRAME_MS) {
                            lastEmit = now;
                            emit(out);
                        }
                    }
                }
            }
            codec.releaseOutputBuffer(outIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private void emit(float[] bands) {
        float[] copy = new float[bands.length];
        System.arraycopy(bands, 0, copy, 0, bands.length);
        try {
            listener.onBands(copy);
            listener.onProblem(null);
        } catch (Exception ignored) { }
    }

    private void problem(String why) {
        try { listener.onProblem(why); } catch (Exception ignored) { }
    }
}
