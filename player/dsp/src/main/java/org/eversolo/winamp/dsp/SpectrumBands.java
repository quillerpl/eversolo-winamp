package org.eversolo.winamp.dsp;

/**
 * Turns FFT bins into the analyser's bars.
 *
 * Two things make the difference between a display that looks like Winamp and one that
 * looks wrong. The bands are spaced **logarithmically**, because hearing is - linear
 * spacing puts fifteen of nineteen bars above 5 kHz, where there is usually nothing, and
 * the whole display huddles at the left. And the magnitudes are converted to **decibels**,
 * because linear amplitude makes everything but the bass line look flat.
 */
public final class SpectrumBands {

    /** The range worth drawing. Below 40 Hz is rumble; above 16 kHz nothing much happens. */
    public static final float LOW_HZ = 40f;
    public static final float HIGH_HZ = 16000f;
    /** Anything this far under the loudest recent peak is silence as far as a bar knows. */
    public static final float RANGE_DB = 55f;

    private final int bandCount;
    private final int[] from;
    private final int[] to;

    /**
     * @param bins       how many FFT bins there are (transform size / 2)
     * @param sampleRate of the audio the bins came from
     */
    public SpectrumBands(int bandCount, int bins, int sampleRate) {
        this.bandCount = bandCount;
        this.from = new int[bandCount];
        this.to = new int[bandCount];
        float hzPerBin = sampleRate / 2f / bins;
        double ratio = Math.log(HIGH_HZ / LOW_HZ) / bandCount;
        int previous = Math.max(1, (int) (LOW_HZ / hzPerBin));
        for (int b = 0; b < bandCount; b++) {
            float edge = (float) (LOW_HZ * Math.exp(ratio * (b + 1)));
            int bin = (int) (edge / hzPerBin);
            from[b] = Math.min(previous, bins - 1);
            // Every band has to own at least one bin, or the low ones come out empty
            // because they are narrower than the resolution of the transform.
            to[b] = Math.max(from[b] + 1, Math.min(bin, bins));
            previous = to[b];
        }
    }

    public int bandCount() { return bandCount; }

    /** First and last bin of a band, for tests and for sanity. */
    public int firstBin(int band) { return from[band]; }
    public int lastBin(int band) { return to[band] - 1; }

    /**
     * Fill {@code out} with 0..1 per band.
     *
     * @param peak the loudest magnitude seen recently, which the caller decays slowly;
     *             the display self-calibrates rather than assuming a scale
     */
    public void fill(float[] magnitudes, float peak, float[] out) {
        float reference = Math.max(peak, 1e-6f);
        for (int b = 0; b < bandCount; b++) {
            // The loudest bin in the band, not the average of them. Averaging buries a
            // tone: the upper bands are dozens of bins wide, so one strong partial among
            // fifty quiet ones comes out looking like silence. Tested with a 1 kHz sine,
            // which should drive its bar to full and did not until this changed.
            float loudest = 0f;
            for (int i = from[b]; i < to[b] && i < magnitudes.length; i++) {
                if (magnitudes[i] > loudest) loudest = magnitudes[i];
            }
            float db = (float) (20 * Math.log10(Math.max(loudest, 1e-9f) / reference));
            float level = 1f + db / RANGE_DB;              // 0 at -RANGE_DB, 1 at the peak
            out[b] = level < 0f ? 0f : (level > 1f ? 1f : level);
        }
    }

    /** The loudest magnitude in a frame, for the caller's decaying peak. */
    public static float loudest(float[] magnitudes) {
        float max = 0f;
        for (float m : magnitudes) if (m > max) max = m;
        return max;
    }
}
