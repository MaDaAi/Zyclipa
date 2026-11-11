package io.zyclipra.cloud;

import com.google.gson.reflect.TypeToken;
import io.zyclipra.clips.Clip;
import io.zyclipra.clips.ClipLibrary;
import io.zyclipra.core.EventBus;
import io.zyclipra.core.Json;
import io.zyclipra.core.Log;
import io.zyclipra.core.Settings;
import io.zyclipra.core.SettingsStore;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Uploads only the clips the user selected, one at a time, with retries and resumable
 * sessions remembered across restarts. Nothing else (settings, logs, sounds) is uploaded.
 */
public final class SyncService {
    private static final Logger LOG = Log.get(SyncService.class);

    public record Status(String clipId, String state, long done, long total, String message) { }

    private final SettingsStore store;
    private final Settings.Cloud cfg;
    private final ClipLibrary library;
    private final EventBus bus;
    private final GoogleAuth auth;
    private final DriveClient drive;
    private final Path stateFile;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, Status> status = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drive-sync");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile String folderId;
    private volatile Consumer<Status> listener = s -> { };

    public SyncService(SettingsStore store, ClipLibrary library, EventBus bus, Path tokenFile, Path stateFile) {
        this.store = store;
        this.cfg = store.get().cloud;
        this.library = library;
        this.bus = bus;
        this.stateFile = stateFile;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
        this.auth = new GoogleAuth(cfg, new SecretStore(tokenFile), http);
        this.drive = new DriveClient(http, new DriveClient.Tokens() {
            public String get() throws IOException { return auth.accessToken(); }
            public void invalidate() { auth.invalidate(); }
        }, "https://www.googleapis.com");
        loadState();
    }

    public GoogleAuth auth() { return auth; }
    public void onStatus(Consumer<Status> l) { listener = l; }
    public Map<String, Status> status() { return Map.copyOf(status); }
    public boolean connected() { return auth.signedIn(); }

    private void loadState() {
        try {
            if (Files.exists(stateFile)) {
                Map<String, String> m = Json.GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() { }.getType());
                if (m != null) sessions.putAll(m);
            }
        } catch (Exception e) { LOG.warning("sync state unreadable: " + e); }
    }

    private void saveState() {
        try { Json.writeAtomic(stateFile, new LinkedHashMap<>(sessions)); } catch (IOException e) { LOG.warning("sync state: " + e); }
    }

    private void report(Status s) {
        status.put(s.clipId(), s);
        listener.accept(s);
    }

    public void select(String clipId, boolean on) {
        if (on && !cfg.selectedClips.contains(clipId)) cfg.selectedClips.add(clipId);
        if (!on) cfg.selectedClips.remove(clipId);
        store.save();
    }

    public boolean selected(String clipId) { return cfg.selectedClips.contains(clipId); }

    /** Uploads every selected clip that isn't on Drive yet. */
    public CompletableFuture<Void> syncSelected() {
        List<String> ids = new ArrayList<>(cfg.selectedClips);
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (String id : ids) library.byId(id).ifPresent(c -> { if (c.driveFileId.isBlank()) all.add(upload(c)); });
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new));
    }

    public CompletableFuture<Void> upload(Clip c) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        report(new Status(c.id, "queued", 0, c.sizeBytes, "Waiting"));
        worker.submit(() -> {
            int attempt = 0;
            while (true) {
                try {
                    uploadOnce(c);
                    f.complete(null);
                    return;
                } catch (IOException e) {
                    attempt++;
                    if (attempt >= 5 || !auth.signedIn()) {
                        report(new Status(c.id, "failed", 0, c.sizeBytes, e.getMessage()));
                        bus.emit(EventBus.ERROR, "Drive upload of " + c.title + " failed: " + e.getMessage());
                        f.completeExceptionally(e);
                        return;
                    }
                    long wait = (long) (Math.pow(2, attempt) * 1000);
                    report(new Status(c.id, "retrying", 0, c.sizeBytes, "Retry " + attempt + " in " + wait / 1000 + " s: " + e.getMessage()));
                    try { Thread.sleep(wait); } catch (InterruptedException ie) { f.completeExceptionally(ie); return; }
                }
            }
        });
        return f;
    }

    private void uploadOnce(Clip c) throws IOException {
        Path file = library.file(c);
        if (!Files.exists(file)) throw new IOException("The clip file is missing.");
        long total = Files.size(file);
        if (folderId == null) folderId = drive.ensureFolder(cfg.folderName);
        String session = sessions.get(c.id);
        long offset = 0;
        if (session != null) {
            offset = drive.uploadedBytes(session, total);
            if (offset == -1) { session = null; offset = 0; }
        }
        if (session == null) {
            session = drive.startUpload(file, c.fileName(), folderId);
            sessions.put(c.id, session);
            saveState();
        }
        report(new Status(c.id, "uploading", offset, total, "Uploading"));
        String id = drive.continueUpload(session, file, Math.max(0, offset),
                (d, t) -> report(new Status(c.id, "uploading", d, t, "Uploading")));
        sessions.remove(c.id);
        saveState();
        c.driveFileId = id;
        c.driveSyncedSize = total;
        library.persist();
        report(new Status(c.id, "done", total, total, "On Google Drive"));
        bus.emit(EventBus.CLIPS_CHANGED, c);
    }

    /** Clips in the Drive folder (for download / status). */
    public CompletableFuture<List<DriveClient.RemoteFile>> remote() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (folderId == null) folderId = drive.ensureFolder(cfg.folderName);
                return drive.list(folderId);
            } catch (IOException e) { throw new IllegalStateException(e.getMessage(), e); }
        }, worker);
    }

    public CompletableFuture<Path> download(DriveClient.RemoteFile f, Consumer<Double> progress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String safe = f.name().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                Path target = library.root().resolve("From Drive").resolve(safe);
                Files.createDirectories(target.getParent());
                drive.download(f.id(), f.size(), target, (d, t) -> progress.accept(t == 0 ? 1 : d / (double) t));
                Clip c = library.add(target, "imported", "Google Drive");
                c.driveFileId = f.id();
                library.persist();
                return target;
            } catch (IOException e) { throw new IllegalStateException(e.getMessage(), e); }
        }, worker);
    }

    public CompletableFuture<Long> freeSpace() {
        return CompletableFuture.supplyAsync(() -> {
            try { return drive.about().orElse(-1L); } catch (IOException e) { return -1L; }
        }, worker);
    }

    public void disconnect() throws IOException {
        auth.disconnect();
        folderId = null;
        sessions.clear();
        saveState();
        store.save();
    }

    public void close() { worker.shutdownNow(); }
}
