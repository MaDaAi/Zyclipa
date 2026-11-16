package io.zyclipa.recording;

import io.zyclipa.clips.Clip;
import io.zyclipa.clips.ClipLibrary;
import io.zyclipa.core.EventBus;
import io.zyclipa.core.Log;
import io.zyclipa.core.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Instant replay. FFmpeg writes the capture into a ring of short MPEG-TS segments on disk
 * (bounded size, no growing RAM); saving joins the newest segments into one clip with a
 * stream copy, so saving costs almost no CPU and the buffer keeps running.
 */
public final class ReplayBuffer {
    private static final Logger LOG = Log.get(ReplayBuffer.class);
    public static final int SEGMENT_SEC = 2;

    private final Settings settings;
    private final CaptureEngine engine;
    private final ClipLibrary library;
    private final EventBus bus;
    private final Path dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "replay");
        t.setDaemon(true);
        return t;
    });
    private volatile FfmpegProcess proc;
    private volatile boolean holdingTracks;
    private volatile List<String> trackTitles = List.of();

    private synchronized void releaseTracks() {
        if (holdingTracks) { holdingTracks = false; engine.tracks().release(); }
    }
    private volatile long startedAt;
    private volatile int bufferSeconds;
    private volatile String lastError = "";

    public ReplayBuffer(Settings settings, CaptureEngine engine, ClipLibrary library, EventBus bus, Path dir) {
        this.settings = settings;
        this.engine = engine;
        this.library = library;
        this.bus = bus;
        this.dir = dir;
    }

    public boolean running() { return proc != null && proc.alive(); }
    public String lastError() { return lastError; }
    public int bufferSeconds() { return bufferSeconds; }
    /** Seconds actually available to save right now. */
    public int availableSeconds() {
        if (!running()) return 0;
        return (int) Math.min(bufferSeconds, (System.currentTimeMillis() - startedAt) / 1000);
    }

    public Future<?> toggle() { return running() ? stop() : start(); }

    public Future<?> start() {
        return worker.submit(() -> {
            if (running()) return;
            try {
                Ffmpeg ff = engine.require();
                Settings.Recording r = settings.recording;
                Files.createDirectories(dir);
                clearSegments();
                bufferSeconds = r.replaySeconds;
                int wrap = r.replaySeconds / SEGMENT_SEC + 3;
                List<AudioTracks.Track> tracks = engine.tracks().acquire();
                holdingTracks = true;
                List<String> args = new ArrayList<>(CaptureArgs.inputs(r, engine.display(), tracks));
                args.addAll(CaptureArgs.mapping(r, tracks));
                trackTitles = new ArrayList<>();
                for (AudioTracks.Track t : tracks) trackTitles.add(t.title());
                for (String d : r.audioInputs) trackTitles.add(CaptureArgs.trackName(d));
                args.addAll(CaptureArgs.encode(r, engine.encoder()));
                args.addAll(List.of("-f", "segment", "-segment_time", String.valueOf(SEGMENT_SEC), "-segment_format", "mpegts",
                        "-segment_wrap", String.valueOf(wrap), "-reset_timestamps", "1",
                        dir.resolve("seg%03d.ts").toString()));
                FfmpegProcess p = new FfmpegProcess(ff, args, "replay", code -> {
                    proc = null;
                    releaseTracks();
                    lastError = "Replay buffer stopped unexpectedly (FFmpeg exit " + code + ")";
                    bus.emit(EventBus.REPLAY_CHANGED, false);
                    bus.emit(EventBus.ERROR, lastError);
                });
                Thread.sleep(800);
                if (!p.alive()) throw new IOException("Replay could not start: " + RecordingService.tidy(p.lastOutput()));
                proc = p;
                startedAt = System.currentTimeMillis();
                lastError = "";
                bus.emit(EventBus.REPLAY_CHANGED, true);
            } catch (Exception e) {
                releaseTracks();
                lastError = e.getMessage();
                LOG.warning("Replay start failed: " + e);
                bus.emit(EventBus.REPLAY_CHANGED, false);
                bus.emit(EventBus.ERROR, "Instant replay: " + lastError);
            }
        });
    }

    public Future<?> stop() {
        return worker.submit(() -> {
            FfmpegProcess p = proc;
            proc = null;
            if (p != null) p.stop(8000);
            releaseTracks();
            clearSegments();
            bus.emit(EventBus.REPLAY_CHANGED, false);
        });
    }

    /** Saves the last {@code seconds} (capped to the buffer length). Completes with the new clip. */
    public CompletableFuture<Clip> save(int seconds) {
        CompletableFuture<Clip> result = new CompletableFuture<>();
        worker.submit(() -> {
            try {
                if (!running()) throw new IOException("Instant replay is off.");
                Ffmpeg ff = engine.require();
                int want = Math.max(5, Math.min(seconds <= 0 ? bufferSeconds : seconds, bufferSeconds));
                List<Path> segs = newestSegments(want);
                if (segs.isEmpty()) throw new IOException("Nothing buffered yet - wait a few seconds.");
                Path list = dir.resolve("concat.txt");
                StringBuilder sb = new StringBuilder();
                for (Path s : segs) sb.append("file '").append(s.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''")).append("'\n");
                Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
                Settings.Recording r = settings.recording;
                Path outDir = Path.of(r.clipsDir);
                Files.createDirectories(outDir);
                String source = engine.sourceName();
                Path out = RecordingService.uniqueName(outDir, source + "_replay", r.container);
                List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0",
                        "-i", list.toString(), "-map", "0", "-c", "copy"));
                // MPEG-TS segments drop track names; put them back so the editor can label each lane.
                for (int i = 0; i < trackTitles.size(); i++) args.addAll(List.of("-metadata:s:a:" + i, "title=" + trackTitles.get(i)));
                if ("mp4".equals(r.container)) args.addAll(List.of("-bsf:a", "aac_adtstoasc", "-movflags", "+faststart"));
                args.add(out.toString());
                Ffmpeg.Result res = ff.run(args, 120);
                if (!res.ok() || !Files.exists(out)) throw new IOException("Saving replay failed: " + res.output());
                Clip c = library.add(out, "replay", source);
                bus.emit(EventBus.NOTIFY, "Replay saved: " + out.getFileName());
                result.complete(c);
            } catch (Exception e) {
                lastError = e.getMessage();
                bus.emit(EventBus.ERROR, "Save replay: " + e.getMessage());
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** Newest segments covering about {@code seconds}, oldest first; the one being written is included. */
    List<Path> newestSegments(int seconds) throws IOException {
        List<Path> all = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "seg*.ts")) {
            for (Path p : ds) if (Files.size(p) > 0) all.add(p);
        }
        all.sort(Comparator.comparingLong(ReplayBuffer::mtime));
        // Segments older than the buffer are left over from a wrap; ignore them.
        long cutoff = System.currentTimeMillis() - (bufferSeconds + 2L * SEGMENT_SEC) * 1000L;
        all.removeIf(p -> mtime(p) < cutoff);
        int need = (int) Math.ceil(seconds / (double) SEGMENT_SEC) + 1;
        return all.subList(Math.max(0, all.size() - need), all.size());
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    private void clearSegments() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "{seg*.ts,concat.txt}")) {
            for (Path p : ds) Files.deleteIfExists(p);
        } catch (IOException ignored) { }
    }

    public void shutdown() {
        try { stop().get(); } catch (Exception ignored) { }
        worker.shutdown();
    }
}
