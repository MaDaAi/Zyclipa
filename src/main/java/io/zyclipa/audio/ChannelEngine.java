package io.zyclipa.audio;

import io.zyclipa.audio.dsp.Dynamics;
import io.zyclipa.audio.dsp.Equalizer;
import io.zyclipa.audio.dsp.NoiseSuppressor;
import io.zyclipa.audio.dsp.StereoFx;
import io.zyclipa.core.Log;
import io.zyclipa.core.Settings;

import javax.sound.sampled.SourceDataLine;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.DoubleSupplier;
import java.util.logging.Logger;

/**
 * Processes one app channel (Game, Chat, Media or Aux): the channel's apps play into a
 * <em>source</em> device (normally a virtual audio cable, which makes no sound by itself);
 * Zyclipa captures that device, applies the channel's effects and plays the result on the
 * output device (your headphones or speakers). About 50 ms of added delay.
 */
public final class ChannelEngine implements AutoCloseable {
    private static final Logger LOG = Log.get(ChannelEngine.class);
    private static final int BLOCK = 480; // 10 ms
    private static final int MAX_BACKLOG = 48000 / 6;

    private final String channel;
    private final Settings.ChannelFx fx;
    private final DoubleSupplier channelGain;
    private final Deque<short[]> queue = new ArrayDeque<>();
    private final WasapiCapture capture;
    private final Thread thread;
    private volatile boolean run = true;
    private volatile float peak, inputPeak;
    private volatile String error = "";
    private final Equalizer eq;

    /** Throws if the source device can't be captured. */
    public ChannelEngine(String channel, Settings.ChannelFx fx, DoubleSupplier channelGain) {
        this.channel = channel;
        this.fx = fx;
        this.channelGain = channelGain;
        this.eq = new Equalizer(2, 48000, fx.eq);
        capture = WasapiCapture.start(WasapiCapture.Kind.OUTPUT_LOOPBACK, fx.sourceDeviceId, 0, 0, this::push);
        thread = new Thread(this::loop, "channel-" + channel);
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public float peak() { return peak; }
    public float inputPeak() { return inputPeak; }
    public String error() { return error; }
    public void eqChanged() { eq.update(fx.eq); }
    public boolean running() { return thread.isAlive() && capture.running(); }

    private void push(byte[] b, int len) {
        short[] s = new short[len / 2];
        int pk = 0;
        for (int i = 0, j = 0; i < s.length; i++, j += 2) { s[i] = (short) ((b[j] & 0xff) | (b[j + 1] << 8)); pk = Math.max(pk, Math.abs(s[i])); }
        inputPeak = pk / 32768f;
        synchronized (queue) {
            queue.addLast(s);
            int frames = 0;
            for (short[] x : queue) frames += x.length / 2;
            while (frames > MAX_BACKLOG && queue.size() > 1) frames -= queue.removeFirst().length / 2;
        }
    }

    private void loop() {
        SourceDataLine out = null;
        String openDevice = null;
        StereoFx.Spatial spatial = new StereoFx.Spatial(48000);
        StereoFx.SmartVolume smart = new StereoFx.SmartVolume(48000);
        NoiseSuppressor nsL = new NoiseSuppressor(), nsR = new NoiseSuppressor();
        Dynamics.Gate gateL = new Dynamics.Gate(48000), gateR = new Dynamics.Gate(48000);
        Dynamics.Compressor compL = new Dynamics.Compressor(48000), compR = new Dynamics.Compressor(48000);
        Dynamics.Limiter limL = new Dynamics.Limiter(48000), limR = new Dynamics.Limiter(48000);
        float[] mix = new float[BLOCK * 2], l = new float[BLOCK], r = new float[BLOCK];
        byte[] bytes = new byte[BLOCK * 4];
        int[] off = new int[1];
        long start = System.nanoTime(), sent = 0;
        try {
            while (run) {
                if (out == null || !fx.outputDevice.equals(openDevice)) {
                    if (out != null) out.close();
                    out = SoundDevices.openOutput(fx.outputDevice, SoundDevices.STEREO, BLOCK * 4 * 4);
                    openDevice = fx.outputDevice;
                    start = System.nanoTime();
                    sent = 0;
                }
                long target = (System.nanoTime() - start) * 48000 / 1_000_000_000L;
                if (target - sent < BLOCK) { Thread.sleep(2); continue; }
                java.util.Arrays.fill(mix, 0);
                synchronized (queue) {
                    int i = 0;
                    while (i < mix.length && !queue.isEmpty()) {
                        short[] b = queue.peekFirst();
                        int take = Math.min(b.length - off[0], mix.length - i);
                        for (int k = 0; k < take; k++) mix[i + k] = b[off[0] + k] / 32768f;
                        i += take;
                        off[0] += take;
                        if (off[0] >= b.length) { queue.removeFirst(); off[0] = 0; }
                    }
                }
                eq.process(mix, BLOCK);
                if (fx.noiseCancel || fx.gate || fx.compressor) {
                    for (int i = 0; i < BLOCK; i++) { l[i] = mix[2 * i]; r[i] = mix[2 * i + 1]; }
                    if (fx.noiseCancel) { nsL.process(l, BLOCK, 6 + 30 * fx.noiseAmount); nsR.process(r, BLOCK, 6 + 30 * fx.noiseAmount); }
                    if (fx.gate) { gateL.process(l, BLOCK, fx.gateDb); gateR.process(r, BLOCK, fx.gateDb); }
                    if (fx.compressor) {
                        double thr = -10 - 30 * fx.compAmount, ratio = 1.5 + 6 * fx.compAmount;
                        compL.process(l, BLOCK, thr, ratio); compR.process(r, BLOCK, thr, ratio);
                    }
                    for (int i = 0; i < BLOCK; i++) { mix[2 * i] = l[i]; mix[2 * i + 1] = r[i]; }
                }
                if (fx.spatial) spatial.process(mix, BLOCK, fx.spatialMode.equals("headphones"), fx.immersion, fx.distance);
                if (fx.smartVolume) smart.process(mix, BLOCK, fx.smartLevel);
                float g = (float) (Math.pow(10, fx.boostDb / 20) * channelGain.getAsDouble());
                for (int i = 0; i < BLOCK; i++) { l[i] = mix[2 * i] * g; r[i] = mix[2 * i + 1] * g; }
                limL.process(l, BLOCK, -1);
                limR.process(r, BLOCK, -1);
                float pk = 0;
                for (int i = 0; i < BLOCK; i++) {
                    pk = Math.max(pk, Math.max(Math.abs(l[i]), Math.abs(r[i])));
                    int a = Math.round(Math.max(-1, Math.min(1, l[i])) * 32767), b = Math.round(Math.max(-1, Math.min(1, r[i])) * 32767);
                    bytes[4 * i] = (byte) a; bytes[4 * i + 1] = (byte) (a >> 8);
                    bytes[4 * i + 2] = (byte) b; bytes[4 * i + 3] = (byte) (b >> 8);
                }
                peak = pk;
                out.write(bytes, 0, bytes.length);
                sent += BLOCK;
                if (target - sent > 48000) sent = target - BLOCK; // device stalled: skip ahead instead of lagging
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            error = channel + " processing stopped: " + e.getMessage();
            LOG.warning(error);
        } finally {
            if (out != null) { out.stop(); out.close(); }
        }
    }

    @Override public void close() {
        run = false;
        capture.close();
        try { thread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
