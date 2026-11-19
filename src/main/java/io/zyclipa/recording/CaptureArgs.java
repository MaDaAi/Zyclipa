package io.zyclipa.recording;

import io.zyclipa.core.Platform;
import io.zyclipa.core.Settings;
import io.zyclipa.platform.Desktop;

import java.util.ArrayList;
import java.util.List;

/** Builds FFmpeg argument lists from recording settings. Pure logic, unit tested. */
public final class CaptureArgs {
    private CaptureArgs() {}

    /** Video (and optional audio) inputs. */
    public static List<String> inputs(Settings.Recording r, Desktop.Display display) { return inputs(r, display, List.of()); }

    /** Video input, then the native sound tracks (loopback TCP streams), then any extra DirectShow devices. */
    public static List<String> inputs(Settings.Recording r, Desktop.Display display, List<AudioTracks.Track> tracks) {
        List<String> a = new ArrayList<>();
        if (Platform.isWindows()) {
            a.addAll(List.of("-f", "gdigrab", "-thread_queue_size", "512", "-framerate", String.valueOf(r.fps), "-draw_mouse", "1"));
            if ("window".equals(r.captureMode) && !r.windowTitle.isBlank()) {
                a.addAll(List.of("-i", "title=" + r.windowTitle));
            } else {
                a.addAll(List.of("-offset_x", String.valueOf(display.x()), "-offset_y", String.valueOf(display.y()),
                        "-video_size", display.width() + "x" + display.height(), "-i", "desktop"));
            }
            for (AudioTracks.Track t : tracks) {
                a.addAll(List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "-thread_queue_size", "4096", "-i", t.url()));
            }
            for (String dev : r.audioInputs) {
                a.addAll(List.of("-f", "dshow", "-thread_queue_size", "1024", "-audio_buffer_size", "50", "-i", "audio=" + dev));
            }
        } else if (Platform.isLinux()) {
            a.addAll(List.of("-f", "x11grab", "-framerate", String.valueOf(r.fps),
                    "-video_size", display.width() + "x" + display.height(), "-i", ":0.0+" + display.x() + "," + display.y()));
            for (String dev : audioDevices(r)) a.addAll(List.of("-f", "pulse", "-thread_queue_size", "1024", "-i", dev));
        } else {
            a.addAll(List.of("-f", "avfoundation", "-framerate", String.valueOf(r.fps), "-i", display.index() + ":none"));
        }
        return a;
    }

    /** Encoding options for the chosen encoder; GOP = 2 s so replay segments start on keyframes. */
    public static List<String> encode(Settings.Recording r, String encoder) {
        List<String> a = new ArrayList<>();
        String scale = r.outputHeight > 0 ? "scale=-2:" + r.outputHeight : "scale=trunc(iw/2)*2:trunc(ih/2)*2";
        a.addAll(List.of("-vf", scale + ",format=yuv420p"));
        int kbps = r.bitrateKbps;
        String bv = kbps + "k", max = (kbps * 3 / 2) + "k", buf = (kbps * 2) + "k";
        a.addAll(List.of("-c:v", encoder));
        switch (encoder) {
            case "h264_nvenc" -> a.addAll(List.of("-preset", pick(r.quality, "p1", "p4", "p6"), "-tune", "ll", "-rc", "vbr"));
            case "h264_qsv" -> a.addAll(List.of("-preset", pick(r.quality, "veryfast", "medium", "slow")));
            case "h264_amf" -> a.addAll(List.of("-quality", pick(r.quality, "speed", "balanced", "quality")));
            default -> a.addAll(List.of("-preset", pick(r.quality, "ultrafast", "veryfast", "medium"), "-tune", "zerolatency"));
        }
        a.addAll(List.of("-b:v", bv, "-maxrate", max, "-bufsize", buf, "-g", String.valueOf(r.fps * 2)));
        return a;
    }

    /** Stream mapping: one track per audio input, or a single mixed track. */
    public static List<String> mapping(Settings.Recording r) { return mapping(r, List.of()); }

    public static List<String> mapping(Settings.Recording r, List<AudioTracks.Track> tracks) {
        List<String> a = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AudioTracks.Track t : tracks) names.add(t.title());
        for (String d : audioDevices(r)) names.add(trackName(d));
        int n = names.size();
        a.addAll(List.of("-map", "0:v"));
        if (n == 0) return a;
        if (r.separateTracks || n == 1) {
            for (int i = 1; i <= n; i++) a.addAll(List.of("-map", i + ":a"));
            // Name each track so the clip editor can label the lanes.
            // Title for current FFmpeg, handler name for older ones (some only keep that in MP4).
            for (int i = 0; i < n; i++) a.addAll(List.of("-metadata:s:a:" + i, "title=" + names.get(i), "-metadata:s:a:" + i, "handler_name=" + names.get(i)));
        } else {
            StringBuilder f = new StringBuilder();
            for (int i = 1; i <= n; i++) f.append('[').append(i).append(":a]");
            f.append("amix=inputs=").append(n).append(":normalize=0[aout]");
            a.addAll(List.of("-filter_complex", f.toString(), "-map", "[aout]", "-metadata:s:a:0", "title=All audio"));
        }
        a.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ar", "48000"));
        return a;
    }

    /** PulseAudio / PipeWire names for "what the PC plays" and "the default microphone". */
    public static final String LINUX_DESKTOP_AUDIO = "@DEFAULT_MONITOR@", LINUX_MIC = "@DEFAULT_SOURCE@";

    /**
     * The extra audio devices to record. On Linux, when none are chosen, that's the PC's sound and (when microphone
     * recording is on) the default microphone, each on its own track. Windows records its tracks natively instead.
     */
    public static List<String> audioDevices(Settings.Recording r) {
        if (!Platform.isLinux() || !r.audioInputs.isEmpty()) return r.audioInputs;
        List<String> d = new ArrayList<>(List.of(LINUX_DESKTOP_AUDIO));
        if (r.recordMic) d.add(LINUX_MIC);
        return d;
    }

    /** "Microphone (Realtek(R) Audio)" -> "Microphone"; keeps names short for track labels. */
    public static String trackName(String device) {
        if (LINUX_DESKTOP_AUDIO.equals(device)) return "Game audio";
        if (LINUX_MIC.equals(device)) return "Microphone";
        String d = device.replaceAll("[\r\n=]", " ").trim();
        int p = d.indexOf(" (");
        return p > 0 ? d.substring(0, p) : d;
    }

    private static String pick(String q, String perf, String bal, String qual) {
        return switch (q) { case "performance" -> perf; case "quality" -> qual; default -> bal; };
    }
}
