package io.zyclipa;

import io.zyclipa.soundboard.SoundboardService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Soundboard amplification (0–300 %) and its clipping protection, on generated audio. */
class SoundboardGainTest {
    static float[] sine(float amp, int frames, int channels) {
        float[] f = new float[frames * channels];
        for (int i = 0; i < frames; i++) for (int c = 0; c < channels; c++) f[i * channels + c] = (float) (amp * Math.sin(2 * Math.PI * 440 * i / 48000.0));
        return f;
    }

    static float peak(float[] f, int from) {
        float p = 0;
        for (int i = from; i < f.length; i++) p = Math.max(p, Math.abs(f[i]));
        return p;
    }

    @Test void gainCombinesAndIsCapped() {
        assertEquals(0.0, SoundboardService.gainFor(0, 3, false), 1e-9, "0 % = muted");
        assertEquals(1.0, SoundboardService.gainFor(1, 1, false), 1e-9, "100 % = original");
        assertEquals(2.0, SoundboardService.gainFor(2, 1, false), 1e-9, "200 % = 2× amplitude");
        assertEquals(3.0, SoundboardService.gainFor(1, 3, false), 1e-9, "300 % = 3× amplitude");
        assertEquals(1.5, SoundboardService.gainFor(3, 0.5, false), 1e-9, "sound × master");
        assertEquals(3.0, SoundboardService.gainFor(3, 3, false), 1e-9, "never more than 3× in total");
        assertEquals(1.0, SoundboardService.gainFor(3, 3, true), 1e-9, "safety: never above 100 %");
        assertEquals(0.5, SoundboardService.gainFor(0.5, 1, true), 1e-9);
        assertEquals(3.0, SoundboardService.clampGain(50), 1e-9);
        assertEquals(0.0, SoundboardService.clampGain(-2), 1e-9);
    }

    @Test void quietSoundIsAmplifiedExactly() {
        float[] f = sine(0.2f, 4800, 2);
        SoundboardService.render(f, f.length, 2, 3f, 3f, new SoundboardService.PeakLimiterRef(2, 48000));
        assertEquals(0.6f, peak(f, 0), 0.01f, "0.2 × 3 = 0.6, below the ceiling, so untouched by the limiter");
    }

    @Test void loudSoundIsLimitedNotClipped() {
        float[] f = sine(0.9f, 48000, 2);
        SoundboardService.PeakLimiterRef lim = new SoundboardService.PeakLimiterRef(2, 48000);
        SoundboardService.render(f, f.length, 2, 3f, 3f, lim);
        assertTrue(peak(f, 0) <= 0.95f + 1e-6, "never above the ceiling: " + peak(f, 0));
        assertTrue(peak(f, 24000) > 0.9f, "still loud, just held at the ceiling");
        assertTrue(lim.gainReduction() < 0.5f, "limiter is working: " + lim.gainReduction());
    }

    @Test void volumeChangesRampInsteadOfJumping() {
        float[] f = new float[960];
        java.util.Arrays.fill(f, 0.1f);
        SoundboardService.render(f, f.length, 1, 1f, 3f, null);
        assertEquals(0.1f, f[0], 1e-4f, "starts at the old gain");
        assertTrue(f[480] > 0.18f && f[480] < 0.22f, "halfway between: " + f[480]);
        for (int i = 1; i < f.length; i++) assertTrue(f[i] >= f[i - 1] - 1e-6, "no jumps");
    }
}
