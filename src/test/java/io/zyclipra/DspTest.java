package io.zyclipra;

import io.zyclipra.audio.dsp.Biquad;
import io.zyclipra.audio.dsp.Dynamics;
import io.zyclipra.audio.dsp.Equalizer;
import io.zyclipra.audio.dsp.NoiseSuppressor;
import io.zyclipra.core.Settings;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DspTest {
    static final double FS = 48000;

    static float[] sine(double f, double amp, int n) {
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (amp * Math.sin(2 * Math.PI * f * i / FS));
        return x;
    }

    static double rms(float[] x, int from) {
        double s = 0;
        for (int i = from; i < x.length; i++) s += x[i] * x[i];
        return Math.sqrt(s / (x.length - from));
    }

    @Test void peakingBandBoostsItsFrequencyOnly() {
        double[] c = Biquad.coefficients(Biquad.Type.PEAK, FS, 1000, 6, 1);
        assertEquals(6, Biquad.responseDb(c, FS, 1000), 0.05);
        assertEquals(0, Biquad.responseDb(c, FS, 50), 0.3);
        assertEquals(0, Biquad.responseDb(c, FS, 18000), 0.3);
    }

    @Test void filterActuallyChangesSignalLevel() {
        Biquad b = new Biquad(1);
        b.set(Biquad.Type.PEAK, FS, 1000, 12, 1);
        float[] x = sine(1000, 0.1, 48000);
        double before = rms(x, 0);
        b.process(x, x.length, 1);
        double gainDb = 20 * Math.log10(rms(x, 4800) / before);
        assertEquals(12, gainDb, 0.3);
    }

    @Test void shelvesAndEqualizerResponse() {
        double[] lo = Biquad.coefficients(Biquad.Type.LOW_SHELF, FS, 100, 6, 0.707);
        assertEquals(6, Biquad.responseDb(lo, FS, 20), 0.5);
        assertEquals(0, Biquad.responseDb(lo, FS, 5000), 0.2);
        Settings.Equalizer eq = new Settings.Equalizer();
        eq.enabled = true;
        eq.bands.get(5).gainDb = -9; // 1 kHz cut
        assertEquals(-9, Equalizer.responseDb(eq.bands, FS, 1000), 0.2);
        float[] x = sine(1000, 0.2, 48000);
        double before = rms(x, 0);
        new Equalizer(1, FS, eq).process(x, x.length);
        assertEquals(-9, 20 * Math.log10(rms(x, 4800) / before), 0.4);
    }

    @Test void disabledEqualizerPassesThrough() {
        Settings.Equalizer eq = new Settings.Equalizer();
        eq.bands.get(3).gainDb = 12;
        eq.enabled = false;
        float[] x = sine(250, 0.2, 4800);
        float[] y = x.clone();
        new Equalizer(1, FS, eq).process(y, y.length);
        assertArrayEquals(x, y);
    }

    @Test void limiterNeverExceedsCeiling() {
        float[] x = sine(200, 1.5, 48000);
        new Dynamics.Limiter(FS).process(x, x.length, -1);
        double ceil = Math.pow(10, -1 / 20.0);
        for (float v : x) assertTrue(Math.abs(v) <= ceil + 1e-6, "sample " + v);
    }

    @Test void gateSilencesQuietAndPassesLoud() {
        Dynamics.Gate g = new Dynamics.Gate(FS);
        float[] quiet = sine(300, 0.001, 48000); // -60 dBFS
        g.process(quiet, quiet.length, -40);
        assertTrue(rms(quiet, 24000) < 1e-5);
        float[] loud = sine(300, 0.3, 48000);
        double before = rms(loud, 0);
        g.process(loud, loud.length, -40);
        assertEquals(before, rms(loud, 24000), before * 0.02);
    }

    @Test void compressorReducesDynamicRange() {
        Dynamics.Compressor c = new Dynamics.Compressor(FS);
        float[] loud = sine(300, 0.9, 48000);
        float[] soft = sine(300, 0.05, 48000);
        double rangeBefore = 20 * Math.log10(rms(loud, 0) / rms(soft, 0));
        c.process(loud, loud.length, -20, 4);
        Dynamics.Compressor c2 = new Dynamics.Compressor(FS);
        c2.process(soft, soft.length, -20, 4);
        double rangeAfter = 20 * Math.log10(rms(loud, 24000) / rms(soft, 24000));
        assertTrue(rangeAfter < rangeBefore - 8, "before " + rangeBefore + " after " + rangeAfter);
    }

    @Test void noiseSuppressorReducesSteadyNoiseButKeepsTone() {
        Random r = new Random(1);
        int n = 48000 * 3;
        float[] noise = new float[n];
        for (int i = 0; i < n; i++) noise[i] = (float) (r.nextGaussian() * 0.02);
        float[] noisy = noise.clone();
        new NoiseSuppressor().process(noisy, n, 20);
        double reduction = 20 * Math.log10(rms(noisy, 48000) / rms(noise, 48000));
        assertTrue(reduction < -8, "noise only reduced by " + reduction + " dB");

        // Speech-like bursts (on 300 ms / off 300 ms) on top of the same noise should survive mostly intact.
        // (A perfectly steady tone is treated like hum and removed, as noise suppressors are meant to.)
        float[] tone = sine(600, 0.3, n);
        float[] mix = new float[n];
        for (int i = 0; i < n; i++) {
            boolean on = (i / 14400) % 2 == 1;
            mix[i] = (on ? tone[i] : 0) + noise[i];
        }
        new NoiseSuppressor().process(mix, n, 20);
        int delay = NoiseSuppressor.FRAME / 2;
        int from = 14400 * 5 + 2400 + delay, to = 14400 * 6 - 2400 + delay; // inside a burst, after warm-up
        double sIn = 0, sOut = 0;
        for (int i = from; i < to; i++) { double t = tone[i - delay] + noise[i - delay]; sIn += t * t; sOut += mix[i] * mix[i]; }
        double change = 10 * Math.log10(sOut / sIn);
        assertTrue(change > -2, "speech burst lost " + change + " dB");
    }
}
