import org.eversolo.winamp.dsp.Fft;
import org.eversolo.winamp.dsp.SpectrumBands;

import java.util.Objects;

/**
 * The analyser's arithmetic, proved against tones of known pitch.
 *
 * This is the part that has to be right before any of it reaches the device: if the bars
 * are in the wrong place there is no way to tell by looking, because music does not come
 * with the answer written on it. A 1 kHz sine does.
 */
public class FftTest {
    static int pass = 0, fail = 0;

    static void check(String label, Object actual, Object expected) {
        if (Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
            pass++; System.out.println("    PASS  " + label + " = " + actual);
        } else {
            fail++; System.out.println("    FAIL  " + label
                    + " : expected <" + expected + "> got <" + actual + ">");
        }
    }

    static void checkTrue(String label, boolean ok) { check(label, ok, true); }

    /** n samples of a sine at {@code hz}, sampled at {@code rate}. */
    static float[] tone(float hz, int rate, int n, float amplitude) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return out;
    }

    static int peakBin(float[] magnitudes) {
        int best = 0;
        for (int i = 1; i < magnitudes.length; i++) if (magnitudes[i] > magnitudes[best]) best = i;
        return best;
    }

    static int loudestBand(float[] bands) {
        int best = 0;
        for (int i = 1; i < bands.length; i++) if (bands[i] > bands[best]) best = i;
        return best;
    }

    public static void main(String[] args) {
        final int N = 1024, RATE = 48000;
        Fft fft = new Fft(N);
        float[] mags = new float[N / 2];

        System.out.println("=== a tone lands in the bin it should ===");
        // Bin k is k * rate / N Hz, so at 48 kHz and N=1024 each bin is 46.875 Hz.
        for (float hz : new float[]{187.5f, 1000f, 5015.625f, 10000f}) {
            fft.magnitudes(tone(hz, RATE, N, 1f), mags);
            int expected = Math.round(hz / (RATE / (float) N));
            int got = peakBin(mags);
            check(hz + " Hz -> bin " + expected, Math.abs(got - expected) <= 1, true);
        }

        System.out.println("\n=== silence and DC ===");
        fft.magnitudes(new float[N], mags);
        check("silence has no peak", SpectrumBands.loudest(mags) < 1e-6f, true);
        float[] dc = new float[N];
        java.util.Arrays.fill(dc, 0.5f);
        fft.magnitudes(dc, mags);
        check("DC lands in bin 0", peakBin(mags), 0);

        System.out.println("\n=== two tones make two peaks ===");
        float[] both = tone(500f, RATE, N, 1f);
        float[] high = tone(8000f, RATE, N, 1f);
        for (int i = 0; i < N; i++) both[i] += high[i];
        fft.magnitudes(both, mags);
        int lowBin = Math.round(500f / (RATE / (float) N));
        int highBin = Math.round(8000f / (RATE / (float) N));
        checkTrue("500 Hz peak is present", mags[lowBin] > SpectrumBands.loudest(mags) * 0.4f);
        checkTrue("8 kHz peak is present", mags[highBin] > SpectrumBands.loudest(mags) * 0.4f);
        checkTrue("nothing much in between",
                mags[(lowBin + highBin) / 2] < SpectrumBands.loudest(mags) * 0.05f);

        System.out.println("\n=== 19 bands, spaced by ear rather than by arithmetic ===");
        SpectrumBands bands = new SpectrumBands(19, N / 2, RATE);
        float[] out = new float[19];
        check("19 bands", bands.bandCount(), 19);
        checkTrue("every band owns at least one bin", everyBandNonEmpty(bands));
        checkTrue("bands climb", bands.firstBin(0) < bands.firstBin(18));
        // Log spacing: the first bands are narrow, the last ones wide. Linear spacing -
        // the obvious mistake - would make them all the same width.
        int firstWidth = bands.lastBin(0) - bands.firstBin(0) + 1;
        int lastWidth = bands.lastBin(18) - bands.firstBin(18) + 1;
        checkTrue("the top band is much wider than the bottom one", lastWidth > firstWidth * 4);

        System.out.println("\n=== a tone lights the right bar, and only it ===");
        for (float[] probe : new float[][]{{80f, 0f, 5f}, {1000f, 6f, 13f}, {9000f, 14f, 18f}}) {
            fft.magnitudes(tone(probe[0], RATE, N, 1f), mags);
            bands.fill(mags, SpectrumBands.loudest(mags), out);
            int lit = loudestBand(out);
            checkTrue((int) probe[0] + " Hz lights a bar between " + (int) probe[1]
                            + " and " + (int) probe[2] + " (got " + lit + ")",
                    lit >= probe[1] && lit <= probe[2]);
            int quiet = 0;
            for (float v : out) if (v < 0.2f) quiet++;
            checkTrue((int) probe[0] + " Hz leaves most bars low", quiet >= 14);
        }

        System.out.println("\n=== levels behave ===");
        fft.magnitudes(tone(1000f, RATE, N, 1f), mags);
        float peak = SpectrumBands.loudest(mags);
        bands.fill(mags, peak, out);
        checkTrue("the loudest bar is near full", out[loudestBand(out)] > 0.85f);
        fft.magnitudes(tone(1000f, RATE, N, 0.002f), mags);   // ~54 dB down
        bands.fill(mags, peak, out);                          // against the same peak
        checkTrue("a very quiet tone barely registers", out[loudestBand(out)] < 0.25f);
        bands.fill(new float[N / 2], peak, out);
        checkTrue("silence is all zero", loudestBand(out) == 0 && out[0] == 0f);

        System.out.println("\n================================");
        System.out.println("  " + pass + " passed, " + fail + " failed");
        System.out.println("================================");
        if (fail > 0) System.exit(1);
    }

    static boolean everyBandNonEmpty(SpectrumBands b) {
        for (int i = 0; i < b.bandCount(); i++) {
            if (b.lastBin(i) < b.firstBin(i)) return false;
        }
        return true;
    }
}
