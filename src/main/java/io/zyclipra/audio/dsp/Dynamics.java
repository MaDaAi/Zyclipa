package io.zyclipra.audio.dsp;

/** Noise gate, compressor and limiter on mono float samples (range -1..1). */
public final class Dynamics {
    private Dynamics() {}

    static double dbToLin(double db) { return Math.pow(10, db / 20); }
    static double linToDb(double v) { return 20 * Math.log10(Math.max(v, 1e-9)); }
    static double coef(double ms, double fs) { return Math.exp(-1.0 / (Math.max(ms, 0.01) * 0.001 * fs)); }

    /** Gate with hysteresis, hold and smooth gain so it doesn't click. */
    public static final class Gate {
        private final double fs;
        private double env, gain, holdLeft;
        public Gate(double fs) { this.fs = fs; }

        public void process(float[] x, int n, double thresholdDb) {
            double open = dbToLin(thresholdDb), close = dbToLin(thresholdDb - 6);
            double envA = coef(1, fs), envR = coef(60, fs), gA = coef(2, fs), gR = coef(80, fs);
            int hold = (int) (0.12 * fs);
            for (int i = 0; i < n; i++) {
                double a = Math.abs(x[i]);
                env = a > env ? envA * env + (1 - envA) * a : envR * env + (1 - envR) * a;
                double target;
                if (env > open) { target = 1; holdLeft = hold; }
                else if (holdLeft > 0) { target = 1; holdLeft--; }
                else target = env < close ? 0 : gain;
                gain = target > gain ? gA * gain + (1 - gA) * target : gR * gain + (1 - gR) * target;
                x[i] *= (float) gain;
            }
        }
        public boolean open() { return gain > 0.5; }
    }

    /** Feed-forward compressor with soft knee and automatic make-up gain. */
    public static final class Compressor {
        private final double fs;
        private double envDb = -120;
        public Compressor(double fs) { this.fs = fs; }

        public void process(float[] x, int n, double thresholdDb, double ratio) {
            double a = coef(5, fs), r = coef(120, fs), knee = 6;
            double makeup = -(thresholdDb - thresholdDb / ratio) * 0.5;
            for (int i = 0; i < n; i++) {
                double inDb = linToDb(Math.abs(x[i]));
                envDb = inDb > envDb ? a * envDb + (1 - a) * inDb : r * envDb + (1 - r) * inDb;
                double over = envDb - thresholdDb;
                double reduce;
                if (2 * over < -knee) reduce = 0;
                else if (2 * Math.abs(over) <= knee) reduce = (1 / ratio - 1) * Math.pow(over + knee / 2, 2) / (2 * knee);
                else reduce = (1 / ratio - 1) * over;
                x[i] *= (float) dbToLin(reduce + makeup);
            }
        }
    }

    /** Fast-attack peak limiter: output never exceeds the ceiling. */
    public static final class Limiter {
        private final double fs;
        private double gain = 1;
        public Limiter(double fs) { this.fs = fs; }

        public void process(float[] x, int n, double ceilingDb) {
            double ceil = dbToLin(ceilingDb), rel = coef(50, fs);
            for (int i = 0; i < n; i++) {
                double a = Math.abs(x[i]);
                double need = a * gain > ceil ? ceil / a : 1;
                gain = need < gain ? need : rel * gain + (1 - rel) * 1.0;
                if (a * gain > ceil) gain = ceil / a;
                x[i] *= (float) gain;
            }
        }
    }
}
