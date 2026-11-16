package io.zyclipa.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/** Playback/capture devices as seen by Java Sound (used for streams Zyclipa itself plays or records). */
public final class SoundDevices {
    public static final float RATE = 48000f;
    public static final AudioFormat STEREO = new AudioFormat(RATE, 16, 2, true, false);
    public static final AudioFormat MONO = new AudioFormat(RATE, 16, 1, true, false);

    private SoundDevices() {}

    public static List<String> outputs() { return list(SourceDataLine.class, STEREO); }
    public static List<String> inputs() { return list(TargetDataLine.class, MONO); }

    private static List<String> list(Class<?> lineClass, AudioFormat fmt) {
        List<String> out = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(lineClass, fmt);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(mi);
                if (m.isLineSupported(info) && !mi.getName().startsWith("Port ")) out.add(mi.getName());
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    private static Mixer.Info find(String name) {
        if (name == null || name.isBlank()) return null;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) if (mi.getName().equals(name)) return mi;
        return null;
    }

    /** Opens an output line on the named device ("" = system default). Falls back to default if it vanished. */
    public static SourceDataLine openOutput(String device, AudioFormat fmt, int bufferBytes) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        Mixer.Info mi = find(device);
        SourceDataLine line = (SourceDataLine) (mi != null ? AudioSystem.getMixer(mi).getLine(info) : AudioSystem.getLine(info));
        line.open(fmt, bufferBytes);
        line.start();
        return line;
    }

    public static TargetDataLine openInput(String device, AudioFormat fmt, int bufferBytes) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        Mixer.Info mi = find(device);
        TargetDataLine line = (TargetDataLine) (mi != null ? AudioSystem.getMixer(mi).getLine(info) : AudioSystem.getLine(info));
        line.open(fmt, bufferBytes);
        line.start();
        return line;
    }

    public static boolean exists(String device) { return device == null || device.isBlank() || find(device) != null; }

    // 16-bit little-endian PCM <-> float helpers
    public static void toFloat(byte[] b, int bytes, float[] out) {
        for (int i = 0, j = 0; j + 1 < bytes; i++, j += 2) out[i] = (short) ((b[j] & 0xff) | (b[j + 1] << 8)) / 32768f;
    }

    public static void toBytes(float[] in, int samples, byte[] out) {
        for (int i = 0, j = 0; i < samples; i++, j += 2) {
            float v = Math.max(-1f, Math.min(1f, in[i]));
            int s = Math.round(v * 32767f);
            out[j] = (byte) s;
            out[j + 1] = (byte) (s >> 8);
        }
    }
}
