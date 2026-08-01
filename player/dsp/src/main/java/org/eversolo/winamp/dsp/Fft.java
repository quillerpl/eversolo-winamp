package org.eversolo.winamp.dsp;

/**
 * A radix-2 FFT, and the band mapping that turns its output into Winamp's 19 bars.
 *
 * This exists because the Eversolo's own `getSpectrum` returns nothing on this unit -
 * `isHasSpectrum` is false for every source - while its stock player quite visibly has a
 * working analyser. The stock player has the samples because it is the one decoding the
 * file. So do we: we know the path, and we can decode it ourselves.
 *
 * Deliberately plain Java. The arithmetic is provable on a desktop JVM with a sine wave,
 * and on the device there is no debugger to ask why the bars are in the wrong place.
 */
public final class Fft {

    private final int n;
    private final int levels;
    private final float[] cosTable;
    private final float[] sinTable;
    private final float[] window;
    private final float[] re;
    private final float[] im;

    /** @param n transform size, a power of two */
    public Fft(int n) {
        if (n < 2 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("size must be a power of two, was " + n);
        }
        this.n = n;
        this.levels = Integer.numberOfTrailingZeros(n);
        this.cosTable = new float[n / 2];
        this.sinTable = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cosTable[i] = (float) Math.cos(2 * Math.PI * i / n);
            sinTable[i] = (float) Math.sin(2 * Math.PI * i / n);
        }
        // Hann. Without a window, a tone that does not land exactly on a bin smears its
        // energy across the whole spectrum and every bar lights up at once.
        this.window = new float[n];
        for (int i = 0; i < n; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        }
        this.re = new float[n];
        this.im = new float[n];
    }

    public int size() { return n; }

    /**
     * Magnitude of each of the first n/2 bins. Bin k covers k * sampleRate / n Hz.
     *
     * @param samples n samples, any scale
     * @param out     at least n/2 long
     */
    public void magnitudes(float[] samples, float[] out) {
        for (int i = 0; i < n; i++) {
            re[i] = (i < samples.length ? samples[i] : 0f) * window[i];
            im[i] = 0f;
        }
        transform();
        for (int i = 0; i < n / 2; i++) {
            out[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]);
        }
    }

    /** In-place Cooley-Tukey, decimation in time. */
    private void transform() {
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - levels);
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            int step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    int l = j + half;
                    float tre = re[l] * cosTable[k] + im[l] * sinTable[k];
                    float tim = -re[l] * sinTable[k] + im[l] * cosTable[k];
                    re[l] = re[j] - tre;
                    im[l] = im[j] - tim;
                    re[j] += tre;
                    im[j] += tim;
                }
            }
        }
    }
}
