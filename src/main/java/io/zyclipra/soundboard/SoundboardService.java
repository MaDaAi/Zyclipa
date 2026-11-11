package io.zyclipra.soundboard;

import io.zyclipra.audio.SoundDevices;
import io.zyclipra.audio.dsp.Equalizer;
import io.zyclipra.core.EventBus;
import io.zyclipra.core.Log;
import io.zyclipra.core.Settings;
import io.zyclipra.core.SettingsStore;
import io.zyclipra.recording.Ffmpeg;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Soundboard: imported sounds are converted once to 48 kHz 16-bit stereo WAV and kept in the
 * data folder, then played from memory. Each sound can go to a local device (your headphones)
 * and, at the same time, to a second device such as a virtual cable that voice chat listens to.
 */
public final class SoundboardService {
    private static final Logger LOG = Log.get(SoundboardService.class);
    public static final Set<String> FORMATS = Set.of("wav", "mp3", "ogg", "flac", "m4a", "aac", "opus", "wma", "aiff", "aif");
    public static final long MAX_IMPORT_BYTES = 100L * 1024 * 1024;
    public static final double MAX_SECONDS = 600;

    private final SettingsStore store;
    private final Settings.Soundboard cfg;
    private final Settings settings;
    private final Path dir;
    private final Supplier<Optional<Ffmpeg>> ffmpeg;
    private final EventBus bus;
    private final Map<String, byte[]> pcm = new ConcurrentHashMap<>();
    private final Map<String, Playback> playing = new ConcurrentHashMap<>();
    private volatile Supplier<io.zyclipra.audio.AudioFeed> recordingTap = () -> null;

    /** Where played sounds are copied while a recording or replay is running (their own track). */
    public void setRecordingTap(Supplier<io.zyclipra.audio.AudioFeed> tap) { recordingTap = tap; }

    public SoundboardService(SettingsStore store, Path dir, Supplier<Optional<Ffmpeg>> ffmpeg, EventBus bus) {
        this.store = store;
        this.settings = store.get();
        this.cfg = settings.soundboard;
        this.dir = dir;
        this.ffmpeg = ffmpeg;
        this.bus = bus;
    }

    public List<Settings.Sound> sounds() { return List.copyOf(cfg.sounds); }
    public Settings.Soundboard config() { return cfg; }

    public Optional<Settings.Sound> find(String id) {
        return cfg.sounds.stream().filter(s -> s.id.equals(id)).findFirst();
    }

    public boolean isPlaying(String id) { return playing.containsKey(id); }

    /** How far a playing sound has got (0..1), or -1 when it isn't playing. */
    public double progress(String id) {
        Playback p = playing.get(id);
        return p == null ? -1 : p.progress();
    }

    public static final double MAX_GAIN = 3.0;

    /**
     * The gain a sound actually plays at: its own gain × the master gain, each 0–3, with the product capped
     * at 3× (or at 1× when "limit to 100%" is on). The peak limiter after it stops the result clipping.
     */
    public static double gainFor(double soundGain, double masterGain, boolean limitTo100) {
        double g = clampGain(soundGain) * clampGain(masterGain);
        return Math.min(limitTo100 ? 1.0 : MAX_GAIN, g);
    }

    public static double clampGain(double g) { return Double.isNaN(g) ? 1.0 : Math.max(0, Math.min(MAX_GAIN, g)); }

    public void setVolume(String id, double gain) {
        Settings.Sound s = find(id).orElseThrow(() -> new IllegalArgumentException("unknown sound"));
        s.volume = clampGain(gain);
        store.save();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
    }

    public void setMasterVolume(double gain) {
        cfg.masterVolume = clampGain(gain);
        store.save();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
    }

    public void setLimitTo100(boolean on) {
        cfg.limitTo100 = on;
        store.save();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
    }

    /** Colour (#RRGGBB or "") and icon (emoji or up to 2 characters, or ""). */
    public void setLook(String id, String color, String icon) {
        Settings.Sound s = find(id).orElseThrow(() -> new IllegalArgumentException("unknown sound"));
        if (color != null) {
            if (!color.isEmpty() && !color.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("Colours look like #FF8800.");
            s.color = color.toUpperCase(java.util.Locale.ROOT);
        }
        if (icon != null) {
            String t = icon.strip();
            if (t.codePointCount(0, t.length()) > 2 || t.length() > 8) throw new IllegalArgumentException("Use one emoji or up to 2 letters.");
            s.icon = t;
        }
        store.save();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
    }

    /** Moves a sound to a new place in the list (the order buttons are shown in). */
    public synchronized void move(String id, int toIndex) {
        Settings.Sound s = find(id).orElseThrow(() -> new IllegalArgumentException("unknown sound"));
        cfg.sounds.remove(s);
        cfg.sounds.add(Math.max(0, Math.min(cfg.sounds.size(), toIndex)), s);
        store.save();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
    }

    public void setCategory(String id, String category) {
        Settings.Sound s = find(id).orElseThrow(() -> new IllegalArgumentException("unknown sound"));
        if (!cfg.categories.contains(category)) throw new IllegalArgumentException("unknown page");
        s.category = category;
        store.save();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
    }
    public Set<String> playingIds() { return Set.copyOf(playing.keySet()); }

    /** Imports (copies and converts) an audio file. Throws with a clear message for bad files. */
    public Settings.Sound importFile(Path src, String category) throws IOException, InterruptedException {
        if (!Files.isRegularFile(src)) throw new IOException("File not found.");
        String name = src.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(ext)) throw new IOException("\"" + name + "\" is not a supported audio file (use MP3, WAV, OGG, FLAC, M4A...).");
        if (Files.size(src) > MAX_IMPORT_BYTES) throw new IOException("That file is larger than 100 MB.");
        Files.createDirectories(dir);
        String id = UUID.randomUUID().toString().substring(0, 12);
        Path out = dir.resolve(id + ".wav");
        Optional<Ffmpeg> ff = ffmpeg.get();
        if (ff.isPresent()) {
            Ffmpeg.Result r = ff.get().run(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", src.toString(), "-vn",
                    "-t", String.valueOf((int) MAX_SECONDS), "-ac", "2", "-ar", "48000", "-c:a", "pcm_s16le", out.toString()), 120);
            if (!r.ok() || !Files.exists(out) || Files.size(out) <= 44) {
                Files.deleteIfExists(out);
                throw new IOException("\"" + name + "\" could not be decoded - it may be damaged or not really audio.");
            }
        } else {
            if (!ext.equals("wav") && !ext.equals("aiff") && !ext.equals("aif"))
                throw new IOException("Importing ." + ext + " files needs FFmpeg (WAV works without it).");
            convertWithJava(src, out);
        }
        Settings.Sound s = new Settings.Sound();
        s.id = id;
        s.name = dot > 0 ? name.substring(0, dot) : name;
        if (s.name.length() > 60) s.name = s.name.substring(0, 60);
        s.file = out.getFileName().toString();
        s.category = category == null || category.isBlank() ? "General" : category;
        if (!cfg.categories.contains(s.category)) cfg.categories.add(s.category);
        load(s); // validates that we can read it back
        cfg.sounds.add(s);
        store.save();
        return s;
    }

    private static void convertWithJava(Path src, Path out) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(src.toFile())) {
            AudioFormat f = in.getFormat();
            AudioFormat target = new AudioFormat(f.getSampleRate(), 16, f.getChannels(), true, false);
            try (AudioInputStream conv = AudioSystem.getAudioInputStream(target, in)) {
                AudioSystem.write(conv, javax.sound.sampled.AudioFileFormat.Type.WAVE, out.toFile());
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException | IllegalArgumentException e) {
            throw new IOException("This WAV file uses an encoding Java can't read without FFmpeg.");
        }
    }

    /** Loads PCM into memory (cached). Format is whatever the stored WAV has (48 kHz stereo when converted by FFmpeg). */
    private byte[] load(Settings.Sound s) throws IOException {
        byte[] b = pcm.get(s.id);
        if (b != null) return b;
        Path f = dir.resolve(s.file);
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f.toFile())) {
            AudioFormat fmt = in.getFormat();
            if (fmt.getSampleSizeInBits() != 16 || fmt.getChannels() > 2 || fmt.isBigEndian())
                throw new IOException("Unexpected stored format " + fmt);
            b = in.readAllBytes();
            formats.put(s.id, fmt);
        } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
            throw new IOException("Stored sound is unreadable: " + e.getMessage());
        }
        pcm.put(s.id, b);
        return b;
    }

    private final Map<String, AudioFormat> formats = new ConcurrentHashMap<>();

    public double durationSec(Settings.Sound s) {
        try {
            byte[] b = load(s);
            AudioFormat f = formats.get(s.id);
            return b.length / (double) (f.getFrameSize() * f.getSampleRate());
        } catch (IOException e) {
            return 0;
        }
    }

    /** Plays (or restarts) a sound. */
    public void play(String id) {
        Settings.Sound s = find(id).orElse(null);
        if (s == null) return;
        stop(id);
        byte[] data;
        try { data = load(s); } catch (IOException e) {
            bus.emit(EventBus.ERROR, "Can't play " + s.name + ": " + e.getMessage());
            return;
        }
        Playback p = new Playback(s, data, formats.get(s.id));
        playing.put(id, p);
        p.start();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
    }

    /**
     * One block of soundboard audio: gain ramped from {@code from} to {@code to} across the block (so
     * changing the volume mid-sound never clicks or jumps), then the peak limiter.
     */
    public static void render(float[] f, int samples, int channels, float from, float to, PeakLimiterRef limiter) {
        int frames = Math.max(1, samples / Math.max(1, channels));
        for (int i = 0; i < samples; i++) {
            float t = (i / Math.max(1, channels)) / (float) frames;
            f[i] *= from + (to - from) * t;
        }
        if (limiter != null) limiter.limiter.process(f, samples);
    }

    /** Small holder so callers outside this package needn't import the DSP class. */
    public static final class PeakLimiterRef {
        final io.zyclipra.audio.dsp.PeakLimiter limiter;
        public PeakLimiterRef(int channels, float sampleRate) { limiter = new io.zyclipra.audio.dsp.PeakLimiter(channels, sampleRate, 0.95f); }
        public float gainReduction() { return limiter.gain(); }
    }

    public void toggle(String id) { if (isPlaying(id)) stop(id); else play(id); }

    public void stop(String id) {
        Playback p = playing.remove(id);
        if (p != null) { p.halt(); bus.emit(EventBus.SOUNDS_CHANGED, null); }
    }

    public void stopAll() { for (String id : List.copyOf(playing.keySet())) stop(id); }

    public void rename(String id, String name) {
        find(id).ifPresent(s -> {
            String n = name == null ? "" : name.trim();
            if (n.isEmpty() || n.length() > 60) throw new IllegalArgumentException("Names need 1-60 characters.");
            s.name = n;
            store.save();
        });
    }

    public void update(Settings.Sound s) { store.save(); }

    public void remove(String id) throws IOException {
        stop(id);
        Settings.Sound s = find(id).orElse(null);
        if (s == null) return;
        cfg.sounds.remove(s);
        pcm.remove(id);
        Files.deleteIfExists(dir.resolve(s.file));
        store.save();
    }

    public void addCategory(String c) {
        String n = c == null ? "" : c.trim();
        if (n.isEmpty() || n.length() > 30) throw new IllegalArgumentException("Collection names need 1-30 characters.");
        if (!cfg.categories.contains(n)) cfg.categories.add(n);
        store.save();
    }

    public void removeCategory(String c) {
        if ("General".equals(c)) throw new IllegalArgumentException("The General collection can't be removed.");
        cfg.categories.remove(c);
        for (Settings.Sound s : cfg.sounds) if (c.equals(s.category)) s.category = "General";
        store.save();
    }

    /** One playing sound: a thread feeding up to two output lines in 20 ms blocks. */
    private final class Playback extends Thread {
        private final Settings.Sound sound;
        private final byte[] data;
        private final AudioFormat fmt;
        private volatile boolean stop;
        private volatile int position;
        private volatile SourceDataLine heard;

        /**
         * How much has actually been heard: the output device's own playback position (audio handed to Windows
         * is buffered ahead of what comes out of the speakers). Falls back to what was sent if there's no device.
         */
        double progress() {
            if (data.length == 0) return 1;
            SourceDataLine l = heard;
            long bytes = l != null ? l.getLongFramePosition() * fmt.getFrameSize() : position;
            // One-shots stop at 100 % (the device keeps counting while the last buffer drains); loops wrap.
            return sound.loop ? (bytes % data.length) / (double) data.length : Math.min(1, bytes / (double) data.length);
        }

        Playback(Settings.Sound s, byte[] data, AudioFormat fmt) {
            super("sound-" + s.id);
            setDaemon(true);
            this.sound = s;
            this.data = data;
            this.fmt = fmt;
        }

        void halt() { stop = true; interrupt(); }

        /** Copies the block into the recording's soundboard track (48 kHz; mono is made stereo). */
        private void tap(byte[] out, int len) {
            io.zyclipra.audio.AudioFeed f = recordingTap.get();
            if (f == null || Math.round(fmt.getSampleRate()) != 48000) return;
            if (fmt.getChannels() == 2) { f.push(this, out, len); return; }
            byte[] st = new byte[len * 2];
            for (int i = 0, j = 0; i + 1 < len; i += 2, j += 4) { st[j] = out[i]; st[j + 1] = out[i + 1]; st[j + 2] = out[i]; st[j + 3] = out[i + 1]; }
            f.push(this, st, st.length);
        }

        @Override public void run() {
            List<SourceDataLine> lines = new ArrayList<>();
            int frame = fmt.getFrameSize();
            int block = (int) (fmt.getSampleRate() / 50) * frame;
            try {
                if (cfg.playLocally) lines.add(SoundDevices.openOutput(cfg.outputDevice, fmt, block * 6));
                if (!cfg.micRouteDevice.isBlank()) {
                    try { lines.add(SoundDevices.openOutput(cfg.micRouteDevice, fmt, block * 6)); }
                    catch (Exception e) { bus.emit(EventBus.ERROR, "Soundboard route device unavailable: " + e.getMessage()); }
                }
                if (lines.isEmpty()) return;
                heard = lines.get(0);
                Equalizer eq = cfg.useEq ? new Equalizer(fmt.getChannels(), fmt.getSampleRate(), settings.equalizer) : null;
                float[] f = new float[block / 2];
                byte[] out = new byte[block];
                PeakLimiterRef limiter = new PeakLimiterRef(fmt.getChannels(), fmt.getSampleRate());
                float last = (float) gainFor(sound.volume, cfg.masterVolume, cfg.limitTo100);
                do {
                    for (int pos = 0; pos < data.length && !stop; pos += block) {
                        int len = Math.min(block, data.length - pos);
                        len -= len % frame;
                        if (len <= 0) break;
                        position = pos;
                        System.arraycopy(data, pos, out, 0, len);
                        int samples = len / 2;
                        SoundDevices.toFloat(out, len, f);
                        if (eq != null) eq.process(f, samples / fmt.getChannels());
                        // Re-read every 20 ms block so volume changes (from the app or a dashboard) apply while playing.
                        float g = (float) gainFor(sound.volume, cfg.masterVolume, cfg.limitTo100);
                        render(f, samples, fmt.getChannels(), last, g, limiter);
                        last = g;
                        SoundDevices.toBytes(f, samples, out);
                        for (SourceDataLine l : lines) l.write(out, 0, len);
                        tap(out, len);
                    }
                } while (sound.loop && !stop);
                if (!stop) for (SourceDataLine l : lines) l.drain();
            } catch (Exception e) {
                if (!stop) {
                    LOG.warning("Playback failed: " + e);
                    bus.emit(EventBus.ERROR, "Can't play " + sound.name + ": " + e.getMessage());
                }
            } finally {
                io.zyclipra.audio.AudioFeed f = recordingTap.get();
                if (f != null) f.endSource(this);
                for (SourceDataLine l : lines) { l.stop(); l.flush(); l.close(); }
                if (playing.remove(sound.id, this)) bus.emit(EventBus.SOUNDS_CHANGED, null);
            }
        }
    }
}
