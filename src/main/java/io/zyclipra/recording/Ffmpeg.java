package io.zyclipra.recording;

import io.zyclipra.core.Log;
import io.zyclipra.core.Platform;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Finds FFmpeg/FFprobe and runs short jobs. FFmpeg is an external, optional tool: Zyclipra
 * only starts it as a child process with an explicit argument list (never through a shell).
 */
public final class Ffmpeg {
    private static final Logger LOG = Log.get(Ffmpeg.class);
    private final Path ffmpeg;
    private final Path ffprobe;

    private Ffmpeg(Path ffmpeg) {
        this.ffmpeg = ffmpeg;
        Path probe = ffmpeg.resolveSibling(exe("ffprobe"));
        this.ffprobe = Files.isRegularFile(probe) ? probe : null;
    }

    public Path path() { return ffmpeg; }
    public Optional<Path> ffprobe() { return Optional.ofNullable(ffprobe); }

    private static String exe(String n) { return Platform.isWindows() ? n + ".exe" : n; }

    /** Looks in: the configured path, ZYCLIPRA_FFMPEG, next to the app, the dev tools folder, then PATH. */
    public static Optional<Ffmpeg> locate(String configured) {
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        String env = System.getenv("ZYCLIPRA_FFMPEG");
        if (env != null && !env.isBlank()) candidates.add(Path.of(env));
        Path base = appDir();
        if (base != null) {
            candidates.add(base.resolve("ffmpeg").resolve(exe("ffmpeg")));
            candidates.add(base.resolve("app").resolve("ffmpeg").resolve(exe("ffmpeg")));
            candidates.add(base.resolve("ffmpeg").resolve("bin").resolve(exe("ffmpeg")));
            // Look in a ".tools" folder next to the app and in the folders above it: the packaged exe sits in
            // app\dist\Zyclipra, three levels below the project's app\.tools, while the dev jar is one level below.
            List<Path> toolDirs = new ArrayList<>();
            for (Path p = base; p != null && toolDirs.size() < 5; p = p.getParent()) toolDirs.add(p.resolve(".tools"));
            for (Path dir : toolDirs) {
                if (!Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ffmpeg*")) {
                    for (Path p : ds) candidates.add(p.resolve("bin").resolve(exe("ffmpeg")));
                } catch (IOException ignored) { }
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) for (String d : pathEnv.split(File.pathSeparator)) {
            if (!d.isBlank()) {
                try { candidates.add(Path.of(d.trim()).resolve(exe("ffmpeg"))); } catch (RuntimeException ignored) { }
            }
        }
        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c) && Files.isExecutable(c)) {
                LOG.info("Using FFmpeg at " + c);
                return Optional.of(new Ffmpeg(c.toAbsolutePath()));
            }
        }
        return Optional.empty();
    }

    /** Folder holding the running jar (or classes folder). */
    static Path appDir() {
        try {
            Path p = Path.of(Ffmpeg.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(p) ? p.getParent() : p.getParent().getParent();
        } catch (Exception e) {
            return null;
        }
    }

    public record Result(int exitCode, String output) {
        public boolean ok() { return exitCode == 0; }
    }

    public Result run(List<String> args, long timeoutSec) throws IOException, InterruptedException {
        return runTool(ffmpeg, args, timeoutSec);
    }

    public Result probe(List<String> args, long timeoutSec) throws IOException, InterruptedException {
        if (ffprobe == null) throw new IOException("ffprobe was not found next to ffmpeg");
        return runTool(ffprobe, args, timeoutSec);
    }

    private static Result runTool(Path tool, List<String> args, long timeoutSec) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(tool.toString());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        byte[][] out = new byte[1][];
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) { out[0] = in.readAllBytes(); } catch (IOException e) { out[0] = new byte[0]; }
        }, "ffmpeg-out");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(1000);
            return new Result(-1, "timed out");
        }
        reader.join(2000);
        return new Result(p.exitValue(), out[0] == null ? "" : new String(out[0], StandardCharsets.UTF_8));
    }

    /** Lists DirectShow audio capture devices (Windows) by their friendly names. */
    public List<String> listAudioDevices() {
        List<String> names = new ArrayList<>();
        if (!Platform.isWindows()) return names;
        try {
            Result r = run(List.of("-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"), 15);
            for (String line : r.output().split("\\R")) {
                int a = line.indexOf('"');
                int b = line.lastIndexOf('"');
                if (a >= 0 && b > a && line.contains("(audio)")) names.add(line.substring(a + 1, b));
            }
        } catch (Exception e) {
            LOG.warning("Could not list audio devices: " + e);
        }
        return names;
    }
}
