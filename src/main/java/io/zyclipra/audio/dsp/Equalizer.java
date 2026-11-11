package io.zyclipra.audio.dsp;

import io.zyclipra.core.Settings;

import java.util.List;

/** Nine-band parametric EQ built from biquads. Thread-safe re-configuration via a version stamp. */
public final class Equalizer {
    private final Biquad[] bands = new Biquad[9];
    private final int channels;
    private final double sampleRate;
    private volatile Settings.Equalizer cfg;
    private volatile long cfgVersion;
    private long appliedVersion = -1;

    public Equalizer(int channels, double sampleRate, Settings.Equalizer cfg) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        for (int i = 0; i < bands.length; i++) bands[i] = new Biquad(channels);
        update(cfg);
    }

    /** Call after changing band values; the audio thread picks them up on its next block. */
    public void update(Settings.Equalizer c) {
        cfg = c;
        cfgVersion++;
    }

    public void process(float[] buf, int frames) {
        Settings.Equalizer c = cfg;
        if (c == null || !c.enabled) return;
        if (appliedVersion != cfgVersion) {
            appliedVersion = cfgVersion;
            List<Settings.EqBand> b = c.bands;
            for (int i = 0; i < bands.length && i < b.size(); i++) {
                Settings.EqBand x = b.get(i);
                bands[i].set(Biquad.type(x.type), sampleRate, x.freq, x.gainDb, x.q);
            }
        }
        for (int i = 0; i < bands.length && i < c.bands.size(); i++) {
            if (Math.abs(c.bands.get(i).gainDb) > 0.01) bands[i].process(buf, frames, channels);
        }
    }

    /** Combined response in dB of all bands at the given frequency (for the curve display). */
    public static double responseDb(List<Settings.EqBand> b, double fs, double freq) {
        double sum = 0;
        for (Settings.EqBand x : b) sum += Biquad.responseDb(Biquad.coefficients(Biquad.type(x.type), fs, x.freq, x.gainDb, x.q), fs, freq);
        return sum;
    }
}
