package io.zyclipra.audio.dsp;

/**
 * Keeps amplified audio from clipping: when a frame would go above the ceiling, the gain drops at once
 * (instant attack) and recovers smoothly (about 150 ms release). Below the ceiling it does nothing, so
 * normal-level sound passes unchanged. A final clamp guarantees no sample leaves ±ceiling.
 * <p>
 * This prevents hard digital clipping of what Zyclipra adds; it can't undo clipping already in a file,
 * and pushing a loud sound 3× still changes how it sounds (it gets compressed).
 */
public final class PeakLimiter {
    private final int channels;
    private final float ceiling;
    private final float release;
    private float gain = 1f;

    public PeakLimiter(int channels, float sampleRate, float ceiling) {
        this.channels = Math.max(1, channels);
        this.ceiling = ceiling;
        this.release = (float) (1 - Math.exp(-1.0 / (0.150 * sampleRate)));
    }

    /** Current gain reduction (1 = none); for meters and tests. */
    public float gain() { return gain; }

    /** Limits {@code samples} interleaved samples in place. */
    public void process(float[] buf, int samples) {
        for (int i = 0; i + channels <= samples; i += channels) {
            float peak = 0;
            for (int c = 0; c < channels; c++) peak = Math.max(peak, Math.abs(buf[i + c]));
            float needed = peak * gain > ceiling ? ceiling / peak : 1f;
            if (needed < gain) gain = needed;
            else gain += (Math.min(1f, needed) - gain) * release;
            for (int c = 0; c < channels; c++) {
                float v = buf[i + c] * gain;
                buf[i + c] = v > ceiling ? ceiling : v < -ceiling ? -ceiling : v;
            }
        }
    }
}
