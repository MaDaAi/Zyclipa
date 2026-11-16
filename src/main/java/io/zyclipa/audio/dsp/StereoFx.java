package io.zyclipa.audio.dsp;

/**
 * Stereo effects for app channels (our own simple implementations, MIT):
 * <ul>
 * <li>{@link Spatial}: headphones = crossfeed (each ear hears a filtered, slightly delayed bit of
 *     the other side, like speakers in a room); speakers = stereo widening. "Immersion" sets how
 *     strong it is, "distance" adds a small room.</li>
 * <li>{@link SmartVolume}: slowly evens out loud and quiet parts toward a target level.</li>
 * </ul>
 */
public final class StereoFx {
    private StereoFx() {}

    public static final class Spatial {
        private final double fs;
        private final float[] dl = new float[64], dr = new float[64];
        private int dpos;
        private double lpL, lpR;
        private final Room room;

        public Spatial(double fs) { this.fs = fs; this.room = new Room(fs); }

        /** In place on interleaved stereo. */
        public void process(float[] x, int frames, boolean headphones, double immersion, double distance) {
            double lpCoef = Math.exp(-2 * Math.PI * 700 / fs);
            int delay = (int) Math.round(0.0003 * fs); // ~0.3 ms between the ears
            double feed = 0.12 + 0.33 * immersion;
            double width = 1 + 0.9 * immersion;
            for (int i = 0; i < frames; i++) {
                float l = x[2 * i], r = x[2 * i + 1];
                if (headphones) {
                    lpL = (1 - lpCoef) * l + lpCoef * lpL;
                    lpR = (1 - lpCoef) * r + lpCoef * lpR;
                    dl[dpos] = (float) lpL;
                    dr[dpos] = (float) lpR;
                    int rd = (dpos - delay + dl.length) % dl.length;
                    float nl = (float) ((l + feed * dr[rd]) / (1 + feed * 0.5));
                    float nr = (float) ((r + feed * dl[rd]) / (1 + feed * 0.5));
                    dpos = (dpos + 1) % dl.length;
                    l = nl; r = nr;
                } else {
                    double m = (l + r) * 0.5, s = (l - r) * 0.5 * width;
                    l = (float) (m + s); r = (float) (m - s);
                }
                x[2 * i] = l;
                x[2 * i + 1] = r;
            }
            double wet = 0.04 + 0.30 * distance * (0.4 + 0.6 * immersion);
            if (distance > 0.02) room.process(x, frames, wet, 0.55 + 0.35 * distance);
        }
    }

    /** Tiny Schroeder-style room: 4 parallel combs + 2 allpasses per side. */
    static final class Room {
        private final float[][] comb = new float[8][];
        private final int[] combPos = new int[8];
        private final float[][] ap = new float[4][];
        private final int[] apPos = new int[4];
        private final float[] combLp = new float[8];

        Room(double fs) {
            int[] cl = {1116, 1188, 1277, 1356};
            int[] al = {556, 441};
            double s = fs / 44100;
            for (int i = 0; i < 8; i++) comb[i] = new float[(int) ((cl[i % 4] + (i >= 4 ? 23 : 0)) * s)];
            for (int i = 0; i < 4; i++) ap[i] = new float[(int) ((al[i % 2] + (i >= 2 ? 23 : 0)) * s)];
        }

        void process(float[] x, int frames, double wet, double feedback) {
            for (int i = 0; i < frames; i++) {
                for (int ch = 0; ch < 2; ch++) {
                    float in = x[2 * i + ch] * 0.25f, out = 0;
                    for (int c = ch * 4; c < ch * 4 + 4; c++) {
                        float y = comb[c][combPos[c]];
                        combLp[c] = y * 0.7f + combLp[c] * 0.3f;
                        comb[c][combPos[c]] = in + (float) (combLp[c] * feedback);
                        combPos[c] = (combPos[c] + 1) % comb[c].length;
                        out += y;
                    }
                    for (int a = ch * 2; a < ch * 2 + 2; a++) {
                        float b = ap[a][apPos[a]];
                        float y = -out + b;
                        ap[a][apPos[a]] = out + b * 0.5f;
                        apPos[a] = (apPos[a] + 1) % ap[a].length;
                        out = y;
                    }
                    x[2 * i + ch] = (float) (x[2 * i + ch] * (1 - wet * 0.5) + out * wet);
                }
            }
        }
    }

    /** Automatic gain toward a target loudness, slow enough not to pump. */
    public static final class SmartVolume {
        private final double fs;
        private double env = 1e-4, gainDb;
        public SmartVolume(double fs) { this.fs = fs; }

        /** {@code level} 0..1 maps to a target of -30..-12 dBFS RMS. Gain limited to ±12 dB. */
        public void process(float[] x, int frames, double level) {
            double target = -30 + 18 * level;
            double envCoef = Math.exp(-1.0 / (0.4 * fs));
            double up = 1.0 / (2.0 * fs), down = 1.0 / (0.3 * fs); // dB per sample steps
            for (int i = 0; i < frames; i++) {
                double p = (x[2 * i] * x[2 * i] + x[2 * i + 1] * x[2 * i + 1]) * 0.5;
                env = envCoef * env + (1 - envCoef) * p;
                double levelDb = 10 * Math.log10(Math.max(env, 1e-10));
                double want = levelDb < -60 ? gainDb : Math.max(-12, Math.min(12, target - levelDb));
                gainDb += want > gainDb ? Math.min(want - gainDb, 12 * up) : Math.max(want - gainDb, -12 * down);
                float g = (float) Math.pow(10, gainDb / 20);
                x[2 * i] *= g;
                x[2 * i + 1] *= g;
            }
        }
    }
}
