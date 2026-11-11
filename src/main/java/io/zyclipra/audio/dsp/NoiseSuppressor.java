package io.zyclipra.audio.dsp;

/**
 * Spectral noise suppression (our own implementation, MIT): short-time FFT with a sqrt-Hann
 * window at 50 % overlap, a per-bin noise floor tracked with slow minimum statistics, and
 * a smoothed Wiener-style gain with a floor so voices are not muffled. Adds one frame
 * (512 samples, ~10.7 ms at 48 kHz) of latency.
 */
public final class NoiseSuppressor {
    public static final int FRAME = 512;
    private static final int HOP = FRAME / 2, BINS = FRAME / 2 + 1;

    private final Fft fft = new Fft(FRAME);
    private final double[] window = new double[FRAME];
    private final double[] inBuf = new double[FRAME];
    private final double[] outBuf = new double[FRAME];
    private final double[] re = new double[FRAME], im = new double[FRAME];
    private final double[] noise = new double[BINS], smoothPow = new double[BINS], gainPrev = new double[BINS];
    private final float[] pendingOut = new float[HOP];
    private int inFill, outRead = HOP, frames;

    public NoiseSuppressor() {
        for (int i = 0; i < FRAME; i++) window[i] = Math.sqrt(0.5 * (1 - Math.cos(2 * Math.PI * i / FRAME)));
        java.util.Arrays.fill(noise, 1e-9);
        java.util.Arrays.fill(gainPrev, 1);
    }

    /** In-place; output is delayed by one hop. {@code reductionDb} limits how far noise is pushed down. */
    public void process(float[] x, int n, double reductionDb) {
        double floor = Math.pow(10, -reductionDb / 20);
        for (int i = 0; i < n; i++) {
            inBuf[HOP + inFill] = x[i];
            inFill++;
            if (inFill == HOP) {
                processFrame(floor);
                System.arraycopy(inBuf, HOP, inBuf, 0, HOP);
                inFill = 0;
                outRead = 0;
            }
            x[i] = outRead < HOP ? pendingOut[outRead++] : 0f;
        }
    }

    // Minimum statistics: the noise floor is the lowest smoothed power seen over ~1.5 s,
    // tracked as the minimum of 8 sub-windows so it follows changes in the background noise.
    private static final int SUB = 36, SUBS = 8;
    private static final double BIAS = 2.2;   // min of a fluctuating spectrum underestimates its mean
    private static final double OVERSUB = 1.8;
    private final double[][] subMin = new double[SUBS][BINS];
    private final double[] curMin = new double[BINS];
    private int subFrame, subIdx;
    { for (double[] r : subMin) java.util.Arrays.fill(r, Double.MAX_VALUE); java.util.Arrays.fill(curMin, Double.MAX_VALUE); }

    private void processFrame(double floor) {
        for (int i = 0; i < FRAME; i++) { re[i] = inBuf[i] * window[i]; im[i] = 0; }
        fft.transform(re, im, false);
        frames++;
        boolean rollover = ++subFrame >= SUB;
        for (int k = 0; k < BINS; k++) {
            double p = re[k] * re[k] + im[k] * im[k];
            smoothPow[k] = frames == 1 ? p : 0.8 * smoothPow[k] + 0.2 * p;
            curMin[k] = Math.min(curMin[k], smoothPow[k]);
            double m = curMin[k];
            for (int j = 0; j < SUBS; j++) m = Math.min(m, subMin[j][k]);
            noise[k] = m * BIAS;
            if (rollover) { subMin[subIdx][k] = curMin[k]; curMin[k] = Double.MAX_VALUE; }
            double g = 1 - OVERSUB * noise[k] / Math.max(smoothPow[k], 1e-12);
            g = Math.max(floor, g);
            g = 0.5 * gainPrev[k] + 0.5 * g; // smoothing over time limits "musical noise"
            g = Math.min(1, g);
            gainPrev[k] = g;
            re[k] *= g; im[k] *= g;
            if (k > 0 && k < FRAME / 2) { re[FRAME - k] = re[k]; im[FRAME - k] = -im[k]; }
        }
        if (rollover) { subFrame = 0; subIdx = (subIdx + 1) % SUBS; }
        fft.transform(re, im, true);
        for (int i = 0; i < FRAME; i++) outBuf[i] += re[i] * window[i];
        for (int i = 0; i < HOP; i++) pendingOut[i] = (float) outBuf[i];
        System.arraycopy(outBuf, HOP, outBuf, 0, HOP);
        java.util.Arrays.fill(outBuf, HOP, FRAME, 0);
    }
}
