package io.zyclipa.audio.dsp;

/**
 * Second-order IIR filter (RBJ "Audio EQ Cookbook" formulas), transposed direct form II,
 * with independent state per channel.
 */
public final class Biquad {
    private double b0 = 1, b1, b2, a1, a2;
    private final double[] z1, z2;

    public Biquad(int channels) {
        z1 = new double[channels];
        z2 = new double[channels];
    }

    public enum Type { PEAK, LOW_SHELF, HIGH_SHELF }

    public static Type type(String s) {
        return switch (s) { case "lowshelf" -> Type.LOW_SHELF; case "highshelf" -> Type.HIGH_SHELF; default -> Type.PEAK; };
    }

    public void set(Type type, double sampleRate, double freq, double gainDb, double q) {
        double[] c = coefficients(type, sampleRate, freq, gainDb, q);
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4];
    }

    /** Normalised {b0, b1, b2, a1, a2}. */
    public static double[] coefficients(Type type, double fs, double f0, double gainDb, double q) {
        double f = Math.min(f0, fs * 0.45);
        double A = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * f / fs;
        double cos = Math.cos(w0), sin = Math.sin(w0);
        double alpha = sin / (2 * Math.max(q, 0.05));
        double b0, b1, b2, a0, a1, a2;
        switch (type) {
            case LOW_SHELF -> {
                double s = 2 * Math.sqrt(A) * alpha;
                b0 = A * ((A + 1) - (A - 1) * cos + s);
                b1 = 2 * A * ((A - 1) - (A + 1) * cos);
                b2 = A * ((A + 1) - (A - 1) * cos - s);
                a0 = (A + 1) + (A - 1) * cos + s;
                a1 = -2 * ((A - 1) + (A + 1) * cos);
                a2 = (A + 1) + (A - 1) * cos - s;
            }
            case HIGH_SHELF -> {
                double s = 2 * Math.sqrt(A) * alpha;
                b0 = A * ((A + 1) + (A - 1) * cos + s);
                b1 = -2 * A * ((A - 1) + (A + 1) * cos);
                b2 = A * ((A + 1) + (A - 1) * cos - s);
                a0 = (A + 1) - (A - 1) * cos + s;
                a1 = 2 * ((A - 1) - (A + 1) * cos);
                a2 = (A + 1) - (A - 1) * cos - s;
            }
            default -> {
                b0 = 1 + alpha * A;
                b1 = -2 * cos;
                b2 = 1 - alpha * A;
                a0 = 1 + alpha / A;
                a1 = -2 * cos;
                a2 = 1 - alpha / A;
            }
        }
        return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    /** Magnitude in dB at {@code freq} for coefficients c. */
    public static double responseDb(double[] c, double fs, double freq) {
        double w = 2 * Math.PI * freq / fs;
        double cr1 = Math.cos(w), ci1 = -Math.sin(w), cr2 = Math.cos(2 * w), ci2 = -Math.sin(2 * w);
        double nr = c[0] + c[1] * cr1 + c[2] * cr2, ni = c[1] * ci1 + c[2] * ci2;
        double dr = 1 + c[3] * cr1 + c[4] * cr2, di = c[3] * ci1 + c[4] * ci2;
        double mag = Math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
        return 20 * Math.log10(Math.max(mag, 1e-9));
    }

    /** In-place on interleaved samples. */
    public void process(float[] buf, int frames, int channels) {
        for (int ch = 0; ch < channels; ch++) {
            double s1 = z1[ch], s2 = z2[ch];
            for (int i = 0, idx = ch; i < frames; i++, idx += channels) {
                double x = buf[idx];
                double y = b0 * x + s1;
                s1 = b1 * x - a1 * y + s2;
                s2 = b2 * x - a2 * y;
                buf[idx] = (float) y;
            }
            // flush denormals
            z1[ch] = Math.abs(s1) < 1e-20 ? 0 : s1;
            z2[ch] = Math.abs(s2) < 1e-20 ? 0 : s2;
        }
    }
}
