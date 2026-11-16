package io.zyclipa.clips;

import io.zyclipa.recording.Ffmpeg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 320-pixel-wide JPEG thumbnails, made with FFmpeg. */
final class Thumbnails {
    private Thumbnails() {}

    static boolean make(Ffmpeg ff, Path src, Path out, double atSec) {
        try {
            Files.createDirectories(out.getParent());
            Ffmpeg.Result r = ff.run(List.of("-hide_banner", "-loglevel", "error", "-y",
                    "-ss", String.format(Locale.ROOT, "%.2f", Math.max(0, atSec)), "-i", src.toString(),
                    "-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "4", out.toString()), 30);
            return r.ok() && Files.exists(out);
        } catch (Exception e) {
            return false;
        }
    }
}
