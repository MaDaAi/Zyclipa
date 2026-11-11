package io.zyclipra.audio;

import io.zyclipra.audio.dsp.Dynamics;
import io.zyclipra.audio.dsp.Equalizer;
import io.zyclipra.audio.dsp.NoiseSuppressor;
import io.zyclipra.core.EventBus;
import io.zyclipra.core.Log;
import io.zyclipra.core.Settings;

import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.logging.Logger;

/**
 * Live microphone chain: gain -> noise suppression -> gate -> EQ -> compressor -> limiter.
 * The processed voice goes to an output device of your choice - typically a virtual cable
 * that chat apps use as their microphone - and optionally to your headphones (monitoring).
 * Runs on one audio thread; settings are read live so slider changes are heard at once.
 */
public final class MicProcessor {
    private static final Logger LOG = Log.get(MicProcessor.class);
    private static final int BLOCK = 480; // 10 ms at 48 kHz

    private final Settings settings;
    private final EventBus bus;
    private final Equalizer eq;
    private volatile Thread thread;
    private volatile boolean run;
    private volatile float inPeak, outPeak;
    private volatile String error = "";
    private volatile boolean gateOpen;
    private volatile float channelGain = 1f;
    private volatile boolean channelMuted;
    private volatile String routeDevice = "";
    private final java.util.concurrent.atomic.AtomicLong monitorFrames = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong routeFrames = new java.util.concurrent.atomic.AtomicLong();
    private volatile String warning = "";

    /** Frames actually handed to the monitor / route devices (proof that sound is flowing). */
    public long monitorFrames() { return monitorFrames.get(); }
    public long routeFrames() { return routeFrames.get(); }
    /** Non-fatal problem, e.g. a refused setting that would cause a feedback loop. */
    public String warning() { return warning; }

    public MicProcessor(Settings settings, EventBus bus) {
        this.settings = settings;
        this.bus = bus;
        this.eq = new Equalizer(1, SoundDevices.RATE, settings.equalizer);
    }

    public boolean running() { return thread != null && thread.isAlive(); }
    public float inputPeak() { return inPeak; }
    public float outputPeak() { return outPeak; }
    public boolean gateOpen() { return gateOpen; }
    public String error() { return error; }
    public void eqChanged() { eq.update(settings.equalizer); }
    public void setChannel(float gain, boolean muted) { channelGain = gain; channelMuted = muted; }
    /** Device the processed voice is sent to ("" = none). */
    public void setRouteDevice(String d) { routeDevice = d == null ? "" : d; }
    public String routeDevice() { return routeDevice; }

    public synchronized void start() {
        if (running()) return;
        run = true;
        error = "";
        Thread t = new Thread(this::loop, "mic-processor");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        thread = t;
        t.start();
    }

    public synchronized void stop() {
        run = false;
        Thread t = thread;
        if (t != null) {
            try { t.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        thread = null;
        inPeak = outPeak = 0;
    }

    public void restart() { stop(); start(); }

    private void loop() {
        Settings.Mic m = settings.mic;
        TargetDataLine in = null;
        SourceDataLine monitor = null, route = null;
        String openRoute = null;
        boolean openMonitor = false;
        try {
            in = SoundDevices.openInput(m.inputDevice, SoundDevices.MONO, BLOCK * 2 * 8);
        } catch (Exception e) {
            error = "Microphone unavailable: " + e.getMessage();
            bus.emit(EventBus.ERROR, error);
            LOG.warning(error);
            run = false;
            return;
        }
        double fs = SoundDevices.RATE;
        NoiseSuppressor ns = new NoiseSuppressor();
        Dynamics.Gate gate = new Dynamics.Gate(fs);
        Dynamics.Compressor comp = new Dynamics.Compressor(fs);
        Dynamics.Limiter lim = new Dynamics.Limiter(fs);
        byte[] raw = new byte[BLOCK * 2];
        float[] x = new float[BLOCK];
        byte[] mono = new byte[BLOCK * 2];
        byte[] stereo = new byte[BLOCK * 4];
        long lastDeviceCheck = System.currentTimeMillis();
        try {
            while (run) {
                int got = in.read(raw, 0, raw.length);
                if (got <= 0) {
                    if (!in.isOpen()) throw new IllegalStateException("microphone disconnected");
                    continue;
                }
                int n = got / 2;
                SoundDevices.toFloat(raw, got, x);
                float pk = 0;
                for (int i = 0; i < n; i++) pk = Math.max(pk, Math.abs(x[i]));
                inPeak = pk;

                float g = (float) Math.pow(10, m.gainDb / 20);
                for (int i = 0; i < n; i++) x[i] *= g;
                if (m.noiseSuppression) ns.process(x, n, m.noiseReductionDb);
                if (m.gate) { gate.process(x, n, m.gateThresholdDb); gateOpen = gate.open(); } else gateOpen = true;
                if (m.eq) eq.process(x, n);
                if (m.compressor) comp.process(x, n, m.compThresholdDb, m.compRatio);
                float cg = channelMuted ? 0f : channelGain;
                for (int i = 0; i < n; i++) x[i] *= cg;
                if (m.limiter) lim.process(x, n, m.limiterCeilingDb);
                float po = 0;
                for (int i = 0; i < n; i++) po = Math.max(po, Math.abs(x[i]));
                outPeak = po;

                SoundDevices.toBytes(x, n, mono);
                for (int i = 0; i < n; i++) {
                    stereo[i * 4] = mono[i * 2]; stereo[i * 4 + 1] = mono[i * 2 + 1];
                    stereo[i * 4 + 2] = mono[i * 2]; stereo[i * 4 + 3] = mono[i * 2 + 1];
                }
                // (Re)open outputs when their settings change.
                if (m.monitor != openMonitor) {
                    if (monitor != null) { monitor.close(); monitor = null; }
                    if (m.monitor) monitor = safeOpen(m.inputDevice, m.monitorDevice, "Hear myself");
                    openMonitor = m.monitor;
                }
                if (!routeDevice.equals(openRoute == null ? "" : openRoute)) {
                    if (route != null) { route.close(); route = null; }
                    if (!routeDevice.isBlank()) route = safeOpen(m.inputDevice, routeDevice, "Send my voice");
                    openRoute = routeDevice;
                }
                if (monitor != null && monitor.available() >= n * 4) { monitor.write(stereo, 0, n * 4); monitorFrames.addAndGet(n); }
                if (route != null && route.available() >= n * 4) { route.write(stereo, 0, n * 4); routeFrames.addAndGet(n); }

                if (System.currentTimeMillis() - lastDeviceCheck > 2000) {
                    lastDeviceCheck = System.currentTimeMillis();
                    if (!SoundDevices.exists(m.inputDevice)) throw new IllegalStateException("microphone disconnected");
                }
            }
        } catch (Exception e) {
            error = "Microphone stopped: " + e.getMessage();
            bus.emit(EventBus.ERROR, error);
            LOG.warning(error);
        } finally {
            in.close();
            if (monitor != null) monitor.close();
            if (route != null) route.close();
            inPeak = outPeak = 0;
            run = false;
        }
    }

    /**
     * Opens an output unless it would send the voice straight back into the microphone being
     * captured (e.g. mic = "CABLE Output" and output = "CABLE Input"), which would howl.
     */
    private SourceDataLine safeOpen(String input, String output, String what) {
        if (VirtualCables.loops(input, output)) {
            warning = what + " is off: “" + output + "” feeds back into the microphone you picked, which would cause an echo loop. Choose another device.";
            bus.emit(EventBus.ERROR, warning);
            LOG.warning(warning);
            return null;
        }
        warning = "";
        if (what.equals("Hear myself") && output.toLowerCase(java.util.Locale.ROOT).contains("speaker"))
            bus.emit(EventBus.NOTIFY, "Hearing yourself on speakers can echo into your mic – headphones are best.");
        return tryOpen(output);
    }

    private SourceDataLine tryOpen(String device) {
        try {
            return SoundDevices.openOutput(device, SoundDevices.STEREO, BLOCK * 4 * 6);
        } catch (Exception e) {
            bus.emit(EventBus.ERROR, "Can't open " + (device.isBlank() ? "default output" : device) + ": " + e.getMessage());
            return null;
        }
    }
}
