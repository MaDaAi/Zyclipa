package io.zyclipra.recording;

import io.zyclipra.core.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Finds which H.264 encoders actually work on this machine by encoding a few blank frames
 * with each one. Listing encoders is not enough: a build can include NVENC on a PC without NVIDIA.
 */
public final class EncoderProbe {
    private static final Logger LOG = Log.get(EncoderProbe.class);
    public static final List<String> HARDWARE = List.of("h264_nvenc", "h264_qsv", "h264_amf");

    private EncoderProbe() {}

    public static List<String> working(Ffmpeg ff) {
        List<String> ok = new ArrayList<>();
        for (String enc : HARDWARE) {
            if (test(ff, enc)) ok.add(enc);
        }
        if (test(ff, "libx264")) ok.add("libx264");
        LOG.info("Working encoders: " + ok);
        return ok;
    }

    static boolean test(Ffmpeg ff, String enc) {
        try {
            return ff.run(List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                    "color=black:s=256x256:d=0.2", "-c:v", enc, "-f", "null", "-"), 20).ok();
        } catch (Exception e) {
            return false;
        }
    }

    /** Picks the configured encoder if it works, otherwise the best working one. */
    public static String choose(String configured, List<String> working) {
        if (!"auto".equals(configured) && working.contains(configured)) return configured;
        return working.isEmpty() ? "libx264" : working.get(0);
    }
}
