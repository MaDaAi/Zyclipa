package io.zyclipa;

import io.zyclipa.audio.AudioPlatform;
import io.zyclipa.audio.MicProcessor;
import io.zyclipa.audio.MixerService;
import io.zyclipa.audio.SoundDevices;
import io.zyclipa.audio.VirtualCables;
import io.zyclipa.core.EventBus;
import io.zyclipa.core.Settings;
import io.zyclipa.core.SettingsStore;
import io.zyclipa.recording.Ffmpeg;
import io.zyclipa.soundboard.SoundboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-hardware audio tests. Opt-in (mvn test -Dzyclipa.hw=true) because they use this PC's
 * devices. They only change the volume of the test's own audio session, never other apps or the
 * system volume, and every test tone is very quiet (about -46 dBFS) and short.
 */
@EnabledIfSystemProperty(named = "zyclipa.hw", matches = "true")
class HardwareAudioTest {
    static final float QUIET = 0.005f; // about -46 dBFS
    @TempDir Path tmp;

    /** Plays a quiet tone (or silence) on a device until stopped. */
    static final class Tone implements AutoCloseable {
        final AtomicBoolean run = new AtomicBoolean(true);
        final Thread t;
        volatile long startedNanos;
        Tone(String device, float amp, double hz) {
            t = new Thread(() -> {
                try {
                    SourceDataLine l = SoundDevices.openOutput(device, SoundDevices.MONO, 4800);
                    byte[] b = new byte[960];
                    long n = 0;
                    startedNanos = System.nanoTime();
                    while (run.get()) {
                        for (int i = 0; i < 480; i++, n++) {
                            short s = (short) (Math.sin(2 * Math.PI * hz * n / 48000.0) * amp * 32767);
                            b[i * 2] = (byte) s; b[i * 2 + 1] = (byte) (s >> 8);
                        }
                        l.write(b, 0, b.length);
                    }
                    l.drain(); l.close();
                } catch (Exception e) { throw new RuntimeException(e); }
            }, "test-tone");
            t.setDaemon(true);
            t.start();
        }
        @Override public void close() throws InterruptedException { run.set(false); t.join(2000); }
    }

    static float capturePeak(String device, int ms) throws Exception {
        TargetDataLine in = SoundDevices.openInput(device, SoundDevices.MONO, 9600);
        byte[] b = new byte[960];
        float peak = 0;
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            int n = in.read(b, 0, b.length);
            for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((b[i] & 0xff) | (b[i + 1] << 8)) / 32768f));
        }
        in.close();
        return peak;
    }

    static Optional<VirtualCables.Cable> cable() { return VirtualCables.find().stream().findFirst(); }

    // ---------------- mixer: real Windows session volume ----------------

    /** Only our own process's sessions, so the test can never touch other apps. */
    static AudioPlatform ownSessionsOnly(AudioPlatform real) {
        long me = ProcessHandle.current().pid();
        return new AudioPlatform() {
            public boolean available() { return real.available(); }
            public List<Endpoint> endpoints() { return real.endpoints(); }
            public List<Session> sessions() { return real.sessions().stream().filter(s -> s.pid() == me).toList(); }
            public void setSession(String k, float v, boolean m) { real.setSession(k, v, m); }
            public float sessionPeak(String k) { return real.sessionPeak(k); }
            public float masterVolume() { return real.masterVolume(); }
            public boolean masterMuted() { return real.masterMuted(); }
            public void setMaster(float v, boolean m) { throw new AssertionError("test must not change system volume"); }
            public float masterPeak() { return real.masterPeak(); }
            public float micVolume() { return real.micVolume(); }
            public boolean micMuted() { return real.micMuted(); }
            public void setMic(float v, boolean m) { throw new AssertionError("test must not change mic volume"); }
            public float micPeak() { return real.micPeak(); }
            public String defaultOutputId() { return real.defaultOutputId(); }
            public void close() { real.close(); }
        };
    }

    @Test void mixerChangesRealWindowsSessionVolume() throws Exception {
        AudioPlatform os = ownSessionsOnly(AudioPlatform.create());
        assumeTrue(os.available());
        try (Tone silence = new Tone("", 0f, 440)) {
            Thread.sleep(700);
            List<AudioPlatform.Session> mine = os.sessions();
            assertFalse(mine.isEmpty(), "our playing stream should appear as a Windows audio session");
            String key = mine.get(0).key();

            // Directly through the Core Audio layer
            os.setSession(key, 0.25f, false);
            float v = os.sessions().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().volume();
            System.out.println("Session volume after set 0.25 -> Windows reports " + v);
            assertEquals(0.25f, v, 0.01f);
            os.setSession(key, 0.25f, true);
            assertTrue(os.sessions().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().muted(), "mute reaches Windows");

            // Through the real mixer pipeline: channel volume x app volume -> session volume
            SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
            MicProcessor mic = new MicProcessor(store.get(), new EventBus());
            MixerService mixer = new MixerService(store, os, mic);
            mixer.refresh();
            String proc = mixer.apps().get(0).process();
            String ch = mixer.channelOf(proc);
            mixer.setApp(proc, 1.0, false);
            mixer.setChannel(ch, 0.4, false);
            Thread.sleep(400);
            float viaMixer = os.sessions().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().volume();
            System.out.println("Mixer channel '" + ch + "' at 40% -> Windows session volume " + viaMixer);
            assertEquals(0.4f, viaMixer, 0.02f);
            mixer.setChannel(ch, 0.4, true);
            Thread.sleep(400);
            assertTrue(os.sessions().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().muted(), "channel mute reaches Windows");
            mixer.setChannel(ch, 1.0, false);
            Thread.sleep(300);
            assertEquals(1.0f, os.sessions().get(0).volume(), 0.02f);
            mixer.close();
            store.close();
        }
    }

    // ---------------- native capture for recording tracks ----------------

    /** Energy of one frequency in s16le stereo PCM (Goertzel on the left channel). */
    static double tone(java.io.ByteArrayOutputStream pcm, double hz) {
        byte[] b = pcm.toByteArray();
        int n = b.length / 4;
        double k = 2 * Math.cos(2 * Math.PI * hz / 48000), s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double x = (short) ((b[i * 4] & 0xff) | (b[i * 4 + 1] << 8)) / 32768.0;
            double s0 = x + k * s1 - s2;
            s2 = s1; s1 = s0;
        }
        return Math.sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / Math.max(1, n);
    }

    static java.io.ByteArrayOutputStream captureWhile(io.zyclipa.audio.WasapiCapture.Kind kind, long pid, Runnable during) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (io.zyclipa.audio.WasapiCapture c = io.zyclipa.audio.WasapiCapture.start(kind, null, 0, pid, (b, len) -> { synchronized (out) { out.write(b, 0, len); } })) {
            during.run();
        }
        return out;
    }

    /** Plays a tone from a process created by Windows (WMI), i.e. outside Zyclipa's process tree. */
    static void playFromOtherProcess(double hz, Path dir) throws Exception {
        Path wav = dir.resolve("other.wav");
        int rate = 48000;
        byte[] pcm = new byte[rate * 2 * 2];
        for (int i = 0; i < rate * 2; i++) { short v = (short) (Math.sin(2 * Math.PI * hz * i / rate) * 0.01 * 32767); pcm[i * 2] = (byte) v; pcm[i * 2 + 1] = (byte) (v >> 8); }
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(rate, 16, 1, true, false), rate * 2)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        Path play = dir.resolve("play.ps1");
        java.nio.file.Files.writeString(play, "(New-Object Media.SoundPlayer '" + wav + "').PlaySync()");
        Path launch = dir.resolve("launch.ps1");
        java.nio.file.Files.writeString(launch, "Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='powershell -NoProfile -ExecutionPolicy Bypass -File \"" + play + "\"'} | Out-Null");
        new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", launch.toString()).inheritIO().start().waitFor();
    }

    @Test void outputLoopbackHearsWhatIsPlaying() throws Exception {
        var pcm = captureWhile(io.zyclipa.audio.WasapiCapture.Kind.OUTPUT_LOOPBACK, 0, () -> {
            try (Tone t = new Tone("", QUIET * 2, 1234)) { Thread.sleep(1200); } catch (Exception e) { throw new RuntimeException(e); }
        });
        double e = tone(pcm, 1234), ref = tone(pcm, 777);
        System.out.printf("Output loopback: %d bytes, tone energy %.6f vs off-tone %.6f%n", pcm.size(), e, ref);
        assertTrue(pcm.size() > 48000 * 4 / 2, "about a second of audio captured");
        assertTrue(e > ref * 5, "the tone we played is in the loopback capture");
    }

    @Test void gameTrackSkipsZyclipaButHearsOtherApps() throws Exception {
        long me = ProcessHandle.current().pid();
        var pcm = captureWhile(io.zyclipa.audio.WasapiCapture.Kind.EXCLUDE_PROCESS_LOOPBACK, me, () -> {
            try (Tone t = new Tone("", QUIET * 2, 1234)) {
                playFromOtherProcess(1700, tmp);
                Thread.sleep(2500);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        double own = tone(pcm, 1234), other = tone(pcm, 1700), ref = tone(pcm, 777);
        System.out.printf("Game track (all apps except Zyclipa): own tone %.6f, other app's tone %.6f, off-tone %.6f%n", own, other, ref);
        assertTrue(other > ref * 5, "another app's sound is captured");
        assertTrue(own < other / 5, "Zyclipa's own sound (soundboard, monitoring) is left out");
    }

    /** Sessions of one other process only, so the test never touches the person's apps. */
    static AudioPlatform onlyPid(AudioPlatform real, java.util.function.LongPredicate keep) {
        return new AudioPlatform() {
            public boolean available() { return real.available(); }
            public List<Endpoint> endpoints() { return real.endpoints(); }
            public List<Session> sessions() { return real.sessions().stream().filter(s -> keep.test(s.pid())).toList(); }
            public void setSession(String k, float v, boolean m) { real.setSession(k, v, m); }
            public float sessionPeak(String k) { return real.sessionPeak(k); }
            public float masterVolume() { return real.masterVolume(); }
            public boolean masterMuted() { return real.masterMuted(); }
            public void setMaster(float v, boolean m) { throw new AssertionError(); }
            public float masterPeak() { return real.masterPeak(); }
            public float micVolume() { return real.micVolume(); }
            public boolean micMuted() { return real.micMuted(); }
            public void setMic(float v, boolean m) { throw new AssertionError(); }
            public float micPeak() { return real.micPeak(); }
            public String defaultOutputId() { return real.defaultOutputId(); }
            public void close() { real.close(); }
        };
    }

    @Test void channelEffectsProcessTheSourceDevice() throws Exception {
        VirtualCables.Cable cable = cable().orElse(null);
        assumeTrue(cable != null, "needs a virtual cable as the source device");
        AudioPlatform real = AudioPlatform.create();
        String cableId = real.endpoints().stream().filter(e -> e.output() && e.name().equals(cable.playback())).map(AudioPlatform.Endpoint::id).findFirst().orElse(null);
        assumeTrue(cableId != null);
        Settings.ChannelFx fx = new Settings().sanitize().mixer.fx.get("game");
        fx.sourceDeviceId = cableId;
        fx.sourceDeviceName = cable.playback();
        double flat, boosted;
        float enginePeak;
        try (Tone t = new Tone(cable.playback(), QUIET * 2, 1700)) {   // "the game" plays into the cable
            Thread.sleep(300);
            fx.boostDb = 0.1;                                          // effects on, EQ flat
            try (io.zyclipa.audio.ChannelEngine e = new io.zyclipa.audio.ChannelEngine("game", fx, () -> 1.0)) {
                Thread.sleep(400);
                flat = tone(captureWhile(io.zyclipa.audio.WasapiCapture.Kind.OUTPUT_LOOPBACK, 0, () -> sleep(800)), 1700);
            }
            fx.eq.enabled = true;
            fx.eq.bands.get(6).freq = 1700; fx.eq.bands.get(6).gainDb = 12; fx.eq.bands.get(6).q = 1;
            try (io.zyclipa.audio.ChannelEngine e = new io.zyclipa.audio.ChannelEngine("game", fx, () -> 1.0)) {
                Thread.sleep(400);
                boosted = tone(captureWhile(io.zyclipa.audio.WasapiCapture.Kind.OUTPUT_LOOPBACK, 0, () -> sleep(800)), 1700);
                enginePeak = e.peak();
            }
        }
        System.out.printf("Channel effects via %s: heard %.6f flat, %.6f with +12 dB EQ (x%.1f), engine peak %.4f%n",
                cable.playback(), flat, boosted, boosted / Math.max(flat, 1e-9), enginePeak);
        assertTrue(flat > 1e-5, "the source device's sound comes out of the speakers");
        assertTrue(boosted > flat * 2.5, "the EQ boost is applied (about x4 expected)");
    }

    /**
     * Finding kept for reference (not run): per-process capture of another app is taken
     * <em>after</em> its Windows volume, so "mute the app and play it processed" can't work.
     */
    void findingCaptureIsAfterAppVolume() throws Exception {
        AudioPlatform real = AudioPlatform.create();
        java.util.Set<Long> before = new java.util.HashSet<>();
        for (var s : real.sessions()) before.add((long) s.pid());
        // a 6 s tone from a process outside our tree
        Path wav = tmp.resolve("long.wav");
        int rate = 48000;
        byte[] pcm = new byte[rate * 6 * 2];
        for (int i = 0; i < rate * 6; i++) { short v = (short) (Math.sin(2 * Math.PI * 1700 * i / rate) * 0.01 * 32767); pcm[i * 2] = (byte) v; pcm[i * 2 + 1] = (byte) (v >> 8); }
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(rate, 16, 1, true, false), rate * 6)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        Path play = tmp.resolve("play6.ps1");
        java.nio.file.Files.writeString(play, "(New-Object Media.SoundPlayer '" + wav + "').PlaySync()");
        Path launch = tmp.resolve("launch6.ps1");
        java.nio.file.Files.writeString(launch, "Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='powershell -NoProfile -ExecutionPolicy Bypass -File \"" + play + "\"'} | Out-Null");
        new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", launch.toString()).inheritIO().start().waitFor();
        AudioPlatform.Session other = null;
        for (int i = 0; i < 40 && other == null; i++) {
            Thread.sleep(100);
            other = real.sessions().stream().filter(s -> !before.contains((long) s.pid()) && s.process().toLowerCase().contains("powershell")).findFirst().orElse(null);
        }
        assumeTrue(other != null, "test tone player didn't appear");
        long pid = other.pid();
        AudioPlatform os = onlyPid(real, p -> p == pid);
        var direct = captureWhile(io.zyclipa.audio.WasapiCapture.Kind.INCLUDE_PROCESS_LOOPBACK, pid, () -> sleep(500));
        System.out.printf("DIRECT include capture of pid %d: %d bytes, tone %.6f%n", pid, direct.size(), tone(direct, 1700));
        String okey = other.key();
        real.setSession(okey, 1f, true);
        var mutedCap = captureWhile(io.zyclipa.audio.WasapiCapture.Kind.INCLUDE_PROCESS_LOOPBACK, pid, () -> sleep(500));
        real.setSession(okey, 0f, false);
        var zeroCap = captureWhile(io.zyclipa.audio.WasapiCapture.Kind.INCLUDE_PROCESS_LOOPBACK, pid, () -> sleep(500));
        real.setSession(okey, 1f, false);
        System.out.printf("DIRECT while muted: tone %.6f ; while volume 0: %.6f%n", tone(mutedCap, 1700), tone(zeroCap, 1700));

        SettingsStore store = new SettingsStore(tmp.resolve("fx.json"));
        MicProcessor mic = new MicProcessor(store.get(), new EventBus());
        MixerService mixer = new MixerService(store, os, mic);
        mixer.refresh();
        String ch = mixer.channelOf(other.process());
        var base = captureWhile(io.zyclipa.audio.WasapiCapture.Kind.EXCLUDE_PROCESS_LOOPBACK, ProcessHandle.current().pid(), () -> sleep(700));
        // turn on the channel's equalizer: +12 dB around 1.7 kHz
        Settings.ChannelFx fx = mixer.fx(ch);
        fx.eq.enabled = true;
        fx.eq.bands.get(6).freq = 1700; fx.eq.bands.get(6).gainDb = 12; fx.eq.bands.get(6).q = 1;
        mixer.fxChanged(ch);
        Thread.sleep(900);
        boolean muted = os.sessions().get(0).muted();
        var processed = captureWhile(io.zyclipa.audio.WasapiCapture.Kind.OUTPUT_LOOPBACK, 0, () -> sleep(900));
        float enginePeak = mixer.processedPeak(ch);
        var eng = mixer.engine(ch);
        System.out.println("ENGINE " + (eng == null ? "none" : " err=" + eng.error() + " running=" + eng.running()) + " pid=" + pid);
        fx.eq.enabled = false;
        mixer.fxChanged(ch);
        Thread.sleep(600);
        boolean unmuted = !os.sessions().get(0).muted();
        mixer.close();
        store.close();
        double e0 = tone(base, 1700), e1 = tone(processed, 1700);
        System.out.printf("Channel '%s' processing: original muted=%s, unmuted after=%s, engine peak %.4f, tone before %.6f after %.6f (x%.1f)%n",
                ch, muted, unmuted, enginePeak, e0, e1, e1 / Math.max(e0, 1e-9));
    }

    /** Finding kept for reference (not run): the undocumented per-app routing call didn't move sound here. */
    void findingPerAppRouting() throws Exception {
        VirtualCables.Cable cable = cable().orElse(null);
        assumeTrue(cable != null, "needs a virtual cable");
        AudioPlatform real = AudioPlatform.create();
        String cableId = real.endpoints().stream().filter(e -> e.output() && e.name().equals(cable.playback())).map(AudioPlatform.Endpoint::id).findFirst().orElse(null);
        assumeTrue(cableId != null);
        java.util.Set<Long> before = new java.util.HashSet<>();
        for (var s : real.sessions()) before.add((long) s.pid());
        Path wav = tmp.resolve("r.wav");
        int rate = 48000;
        byte[] pcm = new byte[rate * 6 * 2];
        for (int i = 0; i < rate * 6; i++) { short v = (short) (Math.sin(2 * Math.PI * 1700 * i / rate) * 0.01 * 32767); pcm[i * 2] = (byte) v; pcm[i * 2 + 1] = (byte) (v >> 8); }
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(rate, 16, 1, true, false), rate * 6)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        Path play = tmp.resolve("pr.ps1");
        // plays the 6 s tone in 1 s pieces, each a new audio stream (apps pick up a new route on their next stream)
        java.nio.file.Files.writeString(play, "$p = New-Object Media.SoundPlayer '" + wav + "'; 1..6 | % { $p.Play(); Start-Sleep -Milliseconds 1000; $p.Stop() }");
        Path launch = tmp.resolve("lr.ps1");
        java.nio.file.Files.writeString(launch, "Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='powershell -NoProfile -ExecutionPolicy Bypass -File \"" + play + "\"'} | Out-Null");
        new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", launch.toString()).inheritIO().start().waitFor();
        AudioPlatform.Session other = null;
        for (int i = 0; i < 40 && other == null; i++) {
            Thread.sleep(100);
            other = real.sessions().stream().filter(s -> !before.contains((long) s.pid()) && s.process().toLowerCase().contains("powershell")).findFirst().orElse(null);
        }
        assumeTrue(other != null);
        long pid = other.pid();
        var onCable = captureWhileOn(cableId, () -> sleep(700));
        System.out.printf("Tone on cable without routing: %.6f (pid %d)%n", tone(onCable, 1700), pid);
    }

    static java.io.ByteArrayOutputStream captureWhileOn(String renderId, Runnable during) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (io.zyclipa.audio.WasapiCapture c = io.zyclipa.audio.WasapiCapture.start(io.zyclipa.audio.WasapiCapture.Kind.OUTPUT_LOOPBACK, renderId, 0, 0,
                (b, len) -> { synchronized (out) { out.write(b, 0, len); } })) { during.run(); }
        return out;
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    /** Probe kept for reference (not run). */
    void probeCaptureBeforeSessionVolume() throws Exception {
        AudioPlatform os = ownSessionsOnly(AudioPlatform.create());
        long me = ProcessHandle.current().pid();
        try (Tone t = new Tone("", QUIET * 2, 1234)) {
            Thread.sleep(600);
            String key = os.sessions().get(0).key();
            os.setSession(key, 0f, false);
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            try (io.zyclipa.audio.WasapiCapture c = io.zyclipa.audio.WasapiCapture.start(io.zyclipa.audio.WasapiCapture.Kind.INCLUDE_PROCESS_LOOPBACK, null, 0, me,
                    (b, len) -> { synchronized (pcm) { pcm.write(b, 0, len); } })) { Thread.sleep(1200); }
            os.setSession(key, 0f, true);
            java.io.ByteArrayOutputStream pcm2 = new java.io.ByteArrayOutputStream();
            try (io.zyclipa.audio.WasapiCapture c = io.zyclipa.audio.WasapiCapture.start(io.zyclipa.audio.WasapiCapture.Kind.INCLUDE_PROCESS_LOOPBACK, null, 0, me,
                    (b, len) -> { synchronized (pcm2) { pcm2.write(b, 0, len); } })) { Thread.sleep(1200); }
            os.setSession(key, 1f, false);
            System.out.printf("PROBE include-loopback with session volume 0: %.6f ; muted: %.6f%n", tone(pcm, 1234), tone(pcm2, 1234));
        }
    }

    // ---------------- microphone, monitoring, routing (through a virtual cable) ----------------

    @Test void virtualCableIsDetected() {
        List<VirtualCables.Cable> cables = VirtualCables.find();
        System.out.println("Virtual cables: " + cables);
        assumeTrue(!cables.isEmpty(), "no virtual cable installed");
        assertTrue(cables.get(0).knownFreeDriver() || VirtualCables.isVirtual(cables.get(0).playback()));
    }

    @Test void micCaptureProcessingAndMonitoring() throws Exception {
        VirtualCables.Cable c = cable().orElse(null);
        assumeTrue(c != null, "needs a virtual cable to feed a known test signal");
        Settings s = new Settings().sanitize();
        s.mic.inputDevice = c.recording();
        s.mic.gate = false; s.mic.noiseSuppression = false; s.mic.compressor = false; s.mic.limiter = true;
        s.mic.monitor = true;
        s.mic.monitorDevice = ""; // default output; the tone is -46 dBFS so practically silent
        MicProcessor mic = new MicProcessor(s, new EventBus());
        try (Tone t = new Tone(c.playback(), QUIET, 1000)) {
            mic.start();
            float in = 0, out = 0;
            for (int i = 0; i < 30; i++) { Thread.sleep(50); in = Math.max(in, mic.inputPeak()); out = Math.max(out, mic.outputPeak()); }
            System.out.printf("Mic capture via %s: input peak %.4f, processed peak %.4f, monitor frames %d, error '%s'%n",
                    c.recording(), in, out, mic.monitorFrames(), mic.error());
            assertTrue(mic.running(), "microphone opened");
            assertTrue(in > 0.002 && in < 0.05, "test tone captured at the expected level");
            assertTrue(out > 0.002, "processed voice produced");
            assertTrue(mic.monitorFrames() > 24000, "monitoring delivered audio to the output");
        } finally {
            mic.stop();
        }
    }

    @Test void feedbackLoopIsRefused() throws Exception {
        VirtualCables.Cable c = cable().orElse(null);
        assumeTrue(c != null);
        Settings s = new Settings().sanitize();
        s.mic.inputDevice = c.recording();
        MicProcessor mic = new MicProcessor(s, new EventBus());
        mic.setRouteDevice(c.playback()); // would send the mic straight back into itself
        try (Tone t = new Tone(c.playback(), QUIET, 700)) {
            mic.start();
            Thread.sleep(800);
            System.out.println("Feedback guard: '" + mic.warning() + "', routed frames " + mic.routeFrames());
            assertFalse(mic.warning().isBlank());
            assertEquals(0, mic.routeFrames());
        } finally {
            mic.stop();
        }
    }

    @Test void soundboardRoutesIntoVirtualCable() throws Exception {
        VirtualCables.Cable c = cable().orElse(null);
        assumeTrue(c != null);
        SettingsStore store = new SettingsStore(tmp.resolve("sb.json"));
        Settings.Soundboard sb = store.get().soundboard;
        sb.playLocally = false;
        sb.micRouteDevice = c.playback();
        sb.masterVolume = 1.0;
        Path wav = tmp.resolve("tone.wav");
        int rate = 48000;
        byte[] pcm = new byte[rate * 2 * 2];
        for (int i = 0; i < rate * 2; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 880 * i / rate) * QUIET * 32767);
            pcm[i * 2] = (byte) v; pcm[i * 2 + 1] = (byte) (v >> 8);
        }
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(rate, 16, 1, true, false), rate * 2)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        SoundboardService service = new SoundboardService(store, tmp.resolve("sounds"), () -> Ffmpeg.locate(""), new EventBus());
        Settings.Sound snd = service.importFile(wav, "General");
        service.play(snd.id);
        float peak = capturePeak(c.recording(), 1200);
        service.stopAll();
        System.out.printf("Soundboard -> %s, heard on %s at peak %.4f%n", c.playback(), c.recording(), peak);
        assertTrue(peak > 0.002, "the sound arrived at the other end of the cable");
        store.close();
    }

    @Test void cableRoundTripLatency() throws Exception {
        VirtualCables.Cable c = cable().orElse(null);
        assumeTrue(c != null);
        int fs = 48000;
        short[] rec = new short[fs * 3];
        long[] chunkTime = new long[fs * 3 / 240 + 1]; // wall-clock time each 240-sample chunk arrived
        TargetDataLine in = SoundDevices.openInput(c.recording(), SoundDevices.MONO, 1920);
        Thread capture = new Thread(() -> {
            byte[] b = new byte[480];
            int pos = 0;
            while (pos < rec.length) {
                int n = in.read(b, 0, b.length);
                chunkTime[pos / 240] = System.nanoTime();
                for (int j = 0; j + 1 < n && pos < rec.length; j += 2) rec[pos++] = (short) ((b[j] & 0xff) | (b[j + 1] << 8));
            }
        });
        capture.start();
        Thread.sleep(200);
        SourceDataLine out = SoundDevices.openOutput(c.playback(), SoundDevices.MONO, 1920);
        byte[] sig = new byte[fs * 2]; // 0.5 s silence, 20 ms burst, rest silence
        int clickAt = fs / 2;
        for (int i = clickAt; i < clickAt + 960; i++) { short v = (short) ((i % 48 < 24 ? 1 : -1) * 0.02 * 32767); sig[i * 2] = (byte) v; sig[i * 2 + 1] = (byte) (v >> 8); }
        long playStart = System.nanoTime();
        out.write(sig, 0, sig.length);
        out.drain();
        out.close();
        capture.join(5000);
        in.close();
        int onset = -1;
        for (int i = 0; i < rec.length; i++) if (Math.abs(rec[i]) > 300) { onset = i; break; }
        assertTrue(onset >= 0, "burst came back through the cable");
        // The burst was queued after 0.5 s of silence; it was heard when its chunk arrived.
        double ms = (chunkTime[onset / 240] - playStart) / 1e6 - clickAt * 1000.0 / fs;
        System.out.printf("Virtual cable round trip (Java Sound, 20 ms buffers): about %.0f ms%n", ms);
        assertTrue(ms > -50 && ms < 400, "latency should be well under half a second");
    }
}
