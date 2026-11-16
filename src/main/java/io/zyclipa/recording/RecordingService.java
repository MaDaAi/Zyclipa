package io.zyclipa.recording;

import io.zyclipa.clips.ClipLibrary;
import io.zyclipa.core.EventBus;
import io.zyclipa.core.Log;
import io.zyclipa.core.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Manual recording. A small state machine (IDLE -> STARTING -> RECORDING -> STOPPING -> IDLE)
 * serialised on one worker thread so rapid start/stop presses can't overlap.
 */
public final class RecordingService {
    public enum State { IDLE, STARTING, RECORDING, STOPPING }

    private static final Logger LOG = Log.get(RecordingService.class);
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    /** Refuse to start when the clips drive has less than this free. */
    public static final long MIN_FREE_BYTES = 1L << 30;

    private final Settings settings;
    private final CaptureEngine engine;
    private final ClipLibrary library;
    private final EventBus bus;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "recorder");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.IDLE;
    private volatile long startedAt;
    private volatile Path current;
    private volatile String source = "Desktop";
    private volatile String lastError = "";
    private FfmpegProcess proc;
    private boolean holdingTracks;

    private void releaseTracks() {
        if (holdingTracks) { holdingTracks = false; engine.tracks().release(); }
    }

    public RecordingService(Settings settings, CaptureEngine engine, ClipLibrary library, EventBus bus) {
        this.settings = settings;
        this.engine = engine;
        this.library = library;
        this.bus = bus;
    }

    public State state() { return state; }
    public boolean recording() { return state == State.RECORDING; }
    public long elapsedMs() { return state == State.RECORDING ? System.currentTimeMillis() - startedAt : 0; }
    public Path currentFile() { return current; }
    public String lastError() { return lastError; }

    public Future<?> toggle() { return state == State.IDLE ? start() : stop(); }

    public Future<?> start() {
        return worker.submit(() -> {
            if (state != State.IDLE) return;
            setState(State.STARTING);
            try {
                Ffmpeg ff = engine.require();
                Settings.Recording r = settings.recording;
                Path dir = Path.of(r.clipsDir);
                Files.createDirectories(dir);
                long free = Files.getFileStore(dir).getUsableSpace();
                if (free < MIN_FREE_BYTES) throw new IOException("Less than 1 GB free on the clips drive.");
                source = engine.sourceName();
                Path out = uniqueName(dir, source, r.container);
                List<AudioTracks.Track> tracks = engine.tracks().acquire();
                holdingTracks = true;
                List<String> args = new ArrayList<>(CaptureArgs.inputs(r, engine.display(), tracks));
                args.addAll(CaptureArgs.mapping(r, tracks));
                args.addAll(CaptureArgs.encode(r, engine.encoder()));
                if ("mp4".equals(r.container)) args.addAll(List.of("-movflags", "+faststart"));
                args.add(out.toString());
                proc = new FfmpegProcess(ff, args, "record", code -> worker.submit(() -> crashed(code)));
                Thread.sleep(800); // FFmpeg fails within this window when a device/window is missing
                if (!proc.alive()) throw new IOException("Capture could not start: " + tidy(proc.lastOutput()));
                current = out;
                startedAt = System.currentTimeMillis();
                lastError = "";
                setState(State.RECORDING);
            } catch (Exception e) {
                lastError = e.getMessage();
                LOG.warning("Start recording failed: " + e);
                if (proc != null) proc.kill();
                proc = null;
                releaseTracks();
                setState(State.IDLE);
                bus.emit(EventBus.ERROR, "Recording: " + lastError);
            }
        });
    }

    public Future<?> stop() {
        return worker.submit(() -> {
            if (state != State.RECORDING) return;
            setState(State.STOPPING);
            int code = proc.stop(15_000);
            proc = null;
            releaseTracks();
            Path f = current;
            setState(State.IDLE);
            if (f != null && Files.exists(f) && size(f) > 0) {
                library.add(f, "recording", source);
                bus.emit(EventBus.NOTIFY, "Recording saved: " + f.getFileName());
            } else {
                lastError = "Recording produced no file (FFmpeg exit " + code + ")";
                bus.emit(EventBus.ERROR, lastError);
            }
        });
    }

    private void crashed(int code) {
        if (state != State.RECORDING) return;
        proc = null;
        releaseTracks();
        Path f = current;
        lastError = "Recording stopped unexpectedly (FFmpeg exit " + code + ")";
        setState(State.IDLE);
        if (f != null && Files.exists(f) && size(f) > 0) library.add(f, "recording", source);
        bus.emit(EventBus.ERROR, lastError);
    }

    private void setState(State s) {
        state = s;
        bus.emit(EventBus.RECORDING_CHANGED, s);
    }

    static Path uniqueName(Path dir, String source, String ext) {
        String safe = source.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (safe.isEmpty()) safe = "Clip";
        if (safe.length() > 60) safe = safe.substring(0, 60).trim();
        String base = safe + "_" + LocalDateTime.now().format(STAMP);
        Path p = dir.resolve(base + "." + ext);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + "_" + i + "." + ext);
        return p;
    }

    static String tidy(String s) {
        if (s == null || s.isBlank()) return "no details from FFmpeg";
        String[] lines = s.strip().split("\\R");
        return lines[lines.length - 1];
    }

    private static long size(Path f) {
        try { return Files.size(f); } catch (IOException e) { return 0; }
    }

    /** Stops cleanly at shutdown so the file is playable. */
    public void shutdown() {
        try { if (state == State.RECORDING) stop().get(); } catch (Exception ignored) { }
        worker.shutdown();
    }
}
