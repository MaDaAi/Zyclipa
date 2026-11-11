package io.zyclipra.ui;

import io.zyclipra.audio.SoundDevices;

import javax.sound.sampled.SourceDataLine;
import java.util.Random;

/** Short synthesized test sounds (no recordings, nothing copied) to hear what a channel's effects do. */
final class TestSounds {
    private TestSounds() {}

    static final String[] GAME = {"Footsteps", "Impact", "Music", "Voice"};
    static final String[] CHAT = {"Voice", "Noisy voice"};

    static void play(String kind, String device) {
        Thread t = new Thread(() -> {
            try {
                float[] st = render(kind);
                byte[] b = new byte[st.length * 2];
                for (int i = 0; i < st.length; i++) {
                    int v = Math.round(Math.max(-1, Math.min(1, st[i])) * 32767);
                    b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
                }
                SourceDataLine l = SoundDevices.openOutput(device, SoundDevices.STEREO, 19200);
                l.write(b, 0, b.length);
                l.drain();
                l.close();
            } catch (Exception ignored) { }
        }, "test-sound");
        t.setDaemon(true);
        t.start();
    }

    /** Interleaved stereo, 48 kHz, about 2.5 s, peaks around -12 dBFS. */
    static float[] render(String kind) {
        int fs = 48000, n = (int) (fs * 2.5);
        float[] s = new float[n * 2];
        Random r = new Random(7);
        switch (kind) {
            case "Footsteps" -> {
                for (int step = 0; step < 6; step++) {
                    int at = step * fs * 2 / 5;
                    double pan = step % 2 == 0 ? 0.25 : 0.75;
                    double lp = 0;
                    for (int i = 0; i < fs / 8 && at + i < n; i++) {
                        lp = 0.85 * lp + 0.15 * (r.nextDouble() * 2 - 1);
                        double v = lp * Math.exp(-i / (fs * 0.025)) * 0.9 + Math.sin(2 * Math.PI * 90 * i / fs) * Math.exp(-i / (fs * 0.03)) * 0.3;
                        s[2 * (at + i)] += (float) (v * (1 - pan));
                        s[2 * (at + i) + 1] += (float) (v * pan);
                    }
                }
            }
            case "Impact" -> {
                for (int hit = 0; hit < 3; hit++) {
                    int at = hit * fs * 3 / 4;
                    for (int i = 0; i < fs / 2 && at + i < n; i++) {
                        double v = (r.nextDouble() * 2 - 1) * Math.exp(-i / (fs * 0.06)) * 0.6 + Math.sin(2 * Math.PI * 55 * i / fs) * Math.exp(-i / (fs * 0.15)) * 0.5;
                        s[2 * (at + i)] += (float) v;
                        s[2 * (at + i) + 1] += (float) v;
                    }
                }
            }
            case "Music" -> {
                double[] notes = {261.6, 329.6, 392.0, 523.3, 392.0, 329.6, 293.7, 349.2};
                for (int k = 0; k < notes.length; k++) {
                    int at = k * n / notes.length;
                    for (int i = 0; i < n / notes.length; i++) {
                        double e = Math.exp(-i / (fs * 0.35));
                        double v = (Math.sin(2 * Math.PI * notes[k] * i / fs) + 0.4 * Math.sin(4 * Math.PI * notes[k] * i / fs)
                                + 0.5 * Math.sin(2 * Math.PI * notes[k] / 2 * i / fs)) * e * 0.18;
                        s[2 * (at + i)] += (float) v;
                        s[2 * (at + i) + 1] += (float) (v * 0.9);
                    }
                }
            }
            default -> { // vowel-like buzz with formants ("Voice"), optionally with background noise
                double f0 = 130, phase = 0;
                double[] form = {700, 1220, 2600};
                double[] z = new double[6];
                for (int i = 0; i < n; i++) {
                    double vib = f0 * (1 + 0.03 * Math.sin(2 * Math.PI * 5 * i / fs));
                    phase += vib / fs;
                    double src = (phase % 1) * 2 - 1;
                    double env = (Math.sin(2 * Math.PI * 1.6 * i / fs) > -0.3) ? 1 : 0.05;
                    double out = 0;
                    for (int k = 0; k < 3; k++) { // simple resonators
                        double w = 2 * Math.PI * form[k] / fs, rr = 0.97;
                        double y = src + 2 * rr * Math.cos(w) * z[2 * k] - rr * rr * z[2 * k + 1];
                        z[2 * k + 1] = z[2 * k]; z[2 * k] = y;
                        out += y * (k == 0 ? 0.02 : 0.012);
                    }
                    out *= env * 0.5;
                    if (kind.equals("Noisy voice")) out += (r.nextDouble() * 2 - 1) * 0.05;
                    s[2 * i] = (float) out;
                    s[2 * i + 1] = (float) out;
                }
            }
        }
        return s;
    }
}
