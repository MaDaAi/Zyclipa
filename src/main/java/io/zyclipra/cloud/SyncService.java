package io.zyclipra.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.zyclipra.clips.Clip;
import io.zyclipra.clips.ClipLibrary;
import io.zyclipra.core.EventBus;
import io.zyclipra.core.Json;
import io.zyclipra.core.Log;
import io.zyclipra.core.Settings;
import io.zyclipra.core.SettingsStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Optional Google Drive copies of clips. The clips folder on this PC is always the real library;
 * Drive is a backup destination. Every clip that has been put forward for upload has an entry in a
 * queue that is saved after every change ({@code sync-queue.json}), so it survives restarts:
 * <pre>
 *   (no entry) Local only → Queued → Uploading → Synced
 *                                   ↘ Failed (retry)      Synced → Cloud file unavailable (deleted in Drive)
 * </pre>
 * Uploads are resumable (an interrupted upload continues where it stopped, even after a restart),
 * at most two run at once, transient errors and rate limits back off exponentially, and a file only
 * counts as Synced when Drive reports the same size and MD5 checksum as the local file. Each file is
 * tagged with the clip's id (appProperties), so a retry finds an earlier upload instead of duplicating it.
 * Nothing but clip media is uploaded. Local files are only removed after a verified upload, and only
 * when the user turned "keep local copies" off.
 */
public final class SyncService {
    private static final Logger LOG = Log.get(SyncService.class);
    public static final int MAX_PARALLEL = 2, MAX_ATTEMPTS = 6;

    public static final String QUEUED = "queued", UPLOADING = "uploading", SYNCED = "synced", FAILED = "failed", UNAVAILABLE = "unavailable";

    /** One clip's sync record (persisted). */
    public static final class Item {
        public String clipId, title = "", name = "", category = "Clips", kind = "", source = "", relPath = "";
        public long size, createdAt;
        public String state = QUEUED;
        public String driveFileId = "";
        public String session = "";
        public long done;
        public String error = "";
        public int attempts;
        public long updatedAt, nextTryAt;
        /** The local file was moved to the Recycle Bin after a verified upload (keep-local off). */
        public boolean localRemoved;

        Item copy() { return Json.GSON.fromJson(Json.GSON.toJson(this), Item.class); }
    }

    private final SettingsStore store;
    private final Settings.Cloud cfg;
    private final ClipLibrary library;
    private final EventBus bus;
    private final GoogleAuth auth;
    private final DriveClient drive;
    private final Path stateFile;
    private final Map<String, Item> items = new LinkedHashMap<>();
    private final Map<String, String> folders = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService uploads = Executors.newScheduledThreadPool(MAX_PARALLEL + 1, r -> {
        Thread t = new Thread(r, "drive-sync");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final List<Consumer<Item>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Sends a copy of the changed item to every listener (on a background thread). */
    private final Consumer<Item> listener = i -> { for (Consumer<Item> l : listeners) { try { l.accept(i); } catch (RuntimeException e) { LOG.warning("sync listener: " + e); } } };
    /** First retry waits this long, doubling each time (with jitter), up to 10 minutes. */
    long backoffBaseMs = 5_000;

    /** Tests: shorten the retry wait. */
    public void backoff(long firstWaitMs) { backoffBaseMs = firstWaitMs; }
    private volatile boolean paused;
    private volatile String lastProblem = "";

    public SyncService(SettingsStore store, ClipLibrary library, EventBus bus, Secrets tokens, Path avatarFile, Path stateFile) {
        this(store, library, bus, tokens, avatarFile, stateFile,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build(),
                "https://www.googleapis.com");
    }

    /** Full form, used by tests to talk to a local fake of Google's servers. */
    public SyncService(SettingsStore store, ClipLibrary library, EventBus bus, Secrets tokens, Path avatarFile, Path stateFile,
                       HttpClient http, String driveApi) {
        this.store = store;
        this.cfg = store.get().cloud;
        this.library = library;
        this.bus = bus;
        this.stateFile = stateFile;
        this.auth = new GoogleAuth(cfg, tokens, http, avatarFile);
        this.drive = new DriveClient(http, new DriveClient.Tokens() {
            public String get() throws IOException { return auth.accessToken(); }
            public void invalidate() { auth.invalidate(); }
        }, driveApi);
        auth.onChange(() -> { store.save(); bus.emit(EventBus.CLIPS_CHANGED, null); if (auth.signedIn()) pump(); });
        load();
        bus.on(EventBus.CLIPS_CHANGED, p -> { if (p instanceof Clip c) autoQueue(c); });
        uploads.scheduleWithFixedDelay(this::pump, 30, 30, TimeUnit.SECONDS);
        uploads.scheduleWithFixedDelay(() -> { if (connected()) verifyCloudCopies(); }, 60, 15 * 60, TimeUnit.SECONDS);
    }

    public GoogleAuth auth() { return auth; }
    /** Adds a listener; returns how to remove it again. */
    public Runnable onChange(Consumer<Item> l) { listeners.add(l); return () -> listeners.remove(l); }
    public boolean connected() { return auth.signedIn(); }
    public String lastProblem() { return lastProblem; }

    // ---------------- persistence ----------------

    private void load() {
        try {
            if (Files.exists(stateFile)) {
                List<Item> list = Json.GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), new TypeToken<List<Item>>() { }.getType());
                if (list != null) for (Item i : list) {
                    if (i == null || i.clipId == null) continue;
                    if (UPLOADING.equals(i.state)) i.state = QUEUED;   // the app stopped mid-upload: resume
                    items.put(i.clipId, i);
                }
            }
        } catch (Exception e) {
            LOG.warning("Upload queue unreadable, starting empty: " + e.getMessage());
        }
        // Clips picked for upload with the old per-clip switch join the queue once.
        if (!cfg.selectedClips.isEmpty()) {
            for (String id : List.copyOf(cfg.selectedClips)) library.byId(id).ifPresent(c -> { if (c.driveFileId.isBlank()) put(c); });
            cfg.selectedClips.clear();
            store.save();
        }
        // Clips uploaded by an older version are already on Drive.
        for (Clip c : library.all()) {
            if (!c.driveFileId.isBlank() && !items.containsKey(c.id)) {
                Item i = itemFor(c);
                i.state = SYNCED;
                i.driveFileId = c.driveFileId;
                i.done = i.size;
                items.put(c.id, i);
            }
        }
        save();
    }

    private synchronized void save() {
        try { Json.writeAtomic(stateFile, new ArrayList<>(items.values())); }
        catch (IOException e) { LOG.warning("Couldn't save the upload queue: " + e.getMessage()); }
    }

    private void changed(Item i) {
        i.updatedAt = System.currentTimeMillis();
        save();
        listener.accept(i.copy());
    }

    // ---------------- queue ----------------

    public static String categoryOf(Clip c) {
        return switch (c.kind) {
            case "screenshot" -> "Screenshots";
            case "recording" -> "Recordings";
            default -> "Clips";
        };
    }

    private Item itemFor(Clip c) {
        Item i = new Item();
        i.clipId = c.id;
        i.title = c.title;
        i.name = c.fileName();
        i.relPath = c.relPath;
        i.kind = c.kind;
        i.source = c.source;
        i.category = categoryOf(c);
        i.size = c.sizeBytes;
        i.createdAt = c.createdAt;
        return i;
    }

    private synchronized Item put(Clip c) {
        Item i = items.get(c.id);
        if (i == null) { i = itemFor(c); items.put(c.id, i); }
        i.state = QUEUED;
        i.error = "";
        i.attempts = 0;
        i.nextTryAt = 0;
        changed(i);
        return i;
    }

    /** Whether new clips of this kind are uploaded automatically. */
    boolean wantsAuto(Clip c) {
        if (!cfg.autoUpload) return false;
        return switch (categoryOf(c)) {
            case "Screenshots" -> cfg.uploadScreenshots;
            case "Recordings" -> cfg.uploadRecordings;
            default -> cfg.uploadClips;
        };
    }

    /** New clips (saved in the last 10 minutes) go into the queue when automatic upload is on. */
    private void autoQueue(Clip c) {
        if (c.createdAt < System.currentTimeMillis() - 600_000L || !wantsAuto(c)) return;
        synchronized (this) { if (items.containsKey(c.id)) return; }
        put(c);
        pump();
    }

    /** Puts a clip in the upload queue (a manual request; also re-queues a failed or missing one). */
    public synchronized String upload(Clip c) {
        Item i = items.get(c.id);
        if (i != null && (QUEUED.equals(i.state) || UPLOADING.equals(i.state))) return "Already waiting to upload.";
        if (i != null && SYNCED.equals(i.state)) return "Already on Google Drive.";
        if (!Files.isRegularFile(library.file(c))) throw new IllegalStateException("The clip's file isn't on this PC anymore.");
        put(c);
        uploads.execute(this::pump);
        return connected() ? "Added to the upload queue." : "Added to the queue – it uploads once Google Drive is connected.";
    }

    /** Takes a clip out of the queue (stopping it if it's uploading). The clip itself is untouched. */
    public String cancel(String clipId) {
        Item i;
        synchronized (this) {
            i = items.get(clipId);
            if (i == null || SYNCED.equals(i.state) || UNAVAILABLE.equals(i.state)) throw new IllegalStateException("That clip isn't waiting to upload.");
            items.remove(clipId);
        }
        Future<?> f = running.remove(clipId);
        if (f != null) f.cancel(true);
        save();
        Item gone = i.copy();
        gone.state = "local";
        listener.accept(gone);
        return "Upload cancelled. The clip stays on this PC.";
    }

    /** Re-queues one failed or unavailable upload, or all of them when {@code clipId} is null. */
    public synchronized int retry(String clipId) {
        int n = 0;
        for (Item i : items.values()) {
            if (clipId != null && !clipId.equals(i.clipId)) continue;
            if (FAILED.equals(i.state) || UNAVAILABLE.equals(i.state) || (QUEUED.equals(i.state) && i.nextTryAt > 0)) {
                if (UNAVAILABLE.equals(i.state)) { i.driveFileId = ""; i.session = ""; }
                if (!i.localRemoved && library.byId(i.clipId).isEmpty()) continue; // nothing left to upload
                i.state = QUEUED;
                i.error = "";
                i.attempts = 0;
                i.nextTryAt = 0;
                changed(i);
                n++;
            }
        }
        uploads.execute(this::pump);
        return n;
    }

    /** Starts queued uploads that are due, up to {@link #MAX_PARALLEL} at once. */
    public void pump() {
        if (!connected() || paused) return;
        List<Item> due = new ArrayList<>();
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Item i : items.values())
                if (QUEUED.equals(i.state) && i.nextTryAt <= now && !running.containsKey(i.clipId)) due.add(i);
        }
        due.sort(Comparator.comparingLong(i -> i.createdAt));
        // Holding the map's lock while submitting means a task can't remove itself before it was recorded.
        synchronized (running) {
            for (Item i : due) {
                if (running.size() >= MAX_PARALLEL) break;
                if (running.containsKey(i.clipId)) continue;
                String id = i.clipId;
                running.put(id, uploads.submit(() -> {
                    try { runUpload(i); }
                    finally {
                        synchronized (running) { running.remove(id); }
                        if (!Thread.currentThread().isInterrupted()) pump();
                    }
                }));
            }
        }
    }

    // ---------------- upload ----------------

    private String folderFor(String category) throws IOException {
        String key = cfg.folderName + "/" + category;
        String id = folders.get(key);
        if (id != null) return id;
        synchronized (folders) {
            id = folders.get(key);
            if (id != null) return id;
            String root = folders.get(cfg.folderName);
            if (root == null) { root = drive.ensureFolder(cfg.folderName, null); folders.put(cfg.folderName, root); }
            id = drive.ensureFolder(category, root);
            folders.put(key, id);
            return id;
        }
    }

    private void runUpload(Item i) {
        Optional<Clip> clip = library.byId(i.clipId);
        Path file = clip.map(library::file).orElse(null);
        synchronized (this) {
            if (!items.containsKey(i.clipId) || !QUEUED.equals(i.state)) return;
            if (file == null || !Files.isRegularFile(file)) {
                i.state = FAILED;
                i.error = "The clip's file isn't on this PC anymore.";
                changed(i);
                return;
            }
            i.state = UPLOADING;
            i.error = "";
            changed(i);
        }
        try {
            uploadOnce(i, clip.get(), file);
            lastProblem = "";
        } catch (GoogleAuth.AuthException e) {
            // Signed out or the sign-in was revoked: keep it queued; it continues after reconnecting.
            synchronized (this) {
                if (!items.containsKey(i.clipId)) return;
                i.state = QUEUED;
                i.error = e.getMessage();
                changed(i);
            }
            lastProblem = e.getMessage();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || !items.containsKey(i.clipId)) return; // cancelled
            boolean retryable = e instanceof DriveClient.DriveException d ? d.retryable : !(e instanceof IllegalStateException);
            synchronized (this) {
                i.attempts++;
                if (retryable && i.attempts < MAX_ATTEMPTS) {
                    long wait = Math.min(600_000L, backoffBaseMs << (i.attempts - 1));
                    wait += ThreadLocalRandom.current().nextLong(Math.max(1, wait / 4));
                    i.state = QUEUED;
                    i.nextTryAt = System.currentTimeMillis() + wait;
                    i.error = e.getMessage() + " – trying again in " + Math.max(1, wait / 1000) + " s";
                    uploads.schedule(this::pump, wait + 50, TimeUnit.MILLISECONDS);
                } else {
                    i.state = FAILED;
                    i.error = e.getMessage();
                    bus.emit(EventBus.ERROR, "Google Drive upload of “" + i.title + "” failed: " + e.getMessage());
                }
                changed(i);
            }
            lastProblem = e.getMessage();
        }
    }

    private void uploadOnce(Item i, Clip c, Path file) throws IOException {
        long total = Files.size(file);
        String folder = folderFor(i.category);
        if (total != i.size) { i.size = total; i.session = ""; }

        // A previous attempt may already have finished (e.g. the app closed just as it completed).
        DriveClient.RemoteFile done = null;
        Optional<DriveClient.RemoteFile> existing = drive.findByClipId(i.clipId);
        if (existing.isPresent() && existing.get().size() == total && i.session.isBlank()) done = existing.get();

        if (done == null) {
            long offset = 0;
            if (!i.session.isBlank()) {
                offset = drive.uploadedBytes(i.session, total);
                if (offset == -1) { i.session = ""; offset = 0; }
                if (offset == -2) { done = drive.findByClipId(i.clipId).orElse(null); if (done == null) { i.session = ""; offset = 0; } }
            }
            if (done == null) {
                if (i.session.isBlank()) {
                    i.session = drive.startUpload(file, i.name, folder, Map.of(DriveClient.APP_KEY, i.clipId,
                            "zyclipraKind", c.kind, "zyclipraCreated", String.valueOf(c.createdAt)), mimeType(i.name));
                    changed(i);
                }
                final long[] lastSave = {0};
                done = drive.continueUpload(i.session, file, Math.max(0, offset), (d, t) -> {
                    i.done = d;
                    long now = System.currentTimeMillis();
                    if (now - lastSave[0] > 400) { lastSave[0] = now; listener.accept(i.copy()); }
                });
            }
        }

        // Only a matching size and checksum count as success.
        String localMd5 = md5(file);
        if (done.size() != total || (!done.md5().isBlank() && !done.md5().equalsIgnoreCase(localMd5))) {
            try { drive.delete(done.id()); } catch (IOException ignored) { }
            i.session = "";
            throw new DriveClient.DriveException(0, true, "Drive's copy didn't match the file on this PC, so it was removed and will be uploaded again");
        }
        // An older version of this clip (edited since) is replaced, never kept as a duplicate.
        if (existing.isPresent() && !existing.get().id().equals(done.id())) {
            try { drive.delete(existing.get().id()); } catch (IOException e) { LOG.info("Old cloud copy not removed: " + e.getMessage()); }
        }
        synchronized (this) {
            if (!items.containsKey(i.clipId)) return; // cancelled at the very end: the cloud copy stays, it'll be found next time
            i.state = SYNCED;
            i.driveFileId = done.id();
            i.session = "";
            i.done = total;
            i.error = "";
            i.attempts = 0;
            changed(i);
        }
        c.driveFileId = done.id();
        c.driveSyncedSize = total;
        library.persist();
        cfg.lastSyncAt = System.currentTimeMillis();
        store.save();
        bus.emit(EventBus.CLIPS_CHANGED, null);
        if (!cfg.keepLocal) removeLocal(i, c, file);
    }

    /** Recycle Bin, not a permanent delete, and only after the verified upload above. */
    private void removeLocal(Item i, Clip c, Path file) {
        try {
            boolean ok = java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
                    && java.awt.Desktop.getDesktop().moveToTrash(file.toFile());
            if (ok) {
                i.localRemoved = true;
                changed(i);
                library.scanAsync();
            }
        } catch (Exception e) {
            LOG.info("Local copy kept (couldn't move it to the Recycle Bin): " + e.getMessage());
        }
    }

    static String mimeType(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    static String md5(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    // ---------------- cloud copies ----------------

    /** Marks synced clips whose Drive file was deleted or binned as "cloud file unavailable". */
    public int verifyCloudCopies() {
        int missing = 0;
        for (Item i : items()) {
            if (!SYNCED.equals(i.state) || i.driveFileId.isBlank()) continue;
            try {
                if (drive.get(i.driveFileId).isEmpty()) {
                    synchronized (this) {
                        Item live = items.get(i.clipId);
                        if (live == null) continue;
                        live.state = UNAVAILABLE;
                        live.error = "The copy on Google Drive was deleted or moved to the bin.";
                        changed(live);
                    }
                    library.byId(i.clipId).ifPresent(c -> { c.driveFileId = ""; library.persist(); });
                    missing++;
                }
            } catch (IOException e) {
                lastProblem = e.getMessage();
                break; // offline or signed out: try again later
            }
        }
        return missing;
    }

    /** Deletes the Drive copy (after the user confirmed). The clip on this PC is untouched. */
    public void deleteCloudCopy(String clipId) throws IOException {
        Item i;
        synchronized (this) { i = items.get(clipId); }
        if (i == null || i.driveFileId.isBlank()) throw new IllegalStateException("This clip has no copy on Google Drive.");
        drive.delete(i.driveFileId);
        synchronized (this) { items.remove(clipId); }
        save();
        library.byId(clipId).ifPresent(c -> { c.driveFileId = ""; c.driveSyncedSize = 0; library.persist(); });
        Item gone = i.copy();
        gone.state = "local";
        gone.driveFileId = "";
        listener.accept(gone);
        bus.emit(EventBus.CLIPS_CHANGED, null);
    }

    /**
     * Copies a clip from Drive back to this PC – into its old place if that's free, otherwise into
     * "From Google Drive" – and adds it to the library. Resumes a partly downloaded file.
     */
    public CompletableFuture<Path> download(String clipId, Consumer<Double> progress) {
        return CompletableFuture.supplyAsync(() -> {
            Item i;
            synchronized (this) { i = items.get(clipId); }
            if (i == null || i.driveFileId.isBlank()) throw new IllegalStateException("This clip isn't on Google Drive.");
            try {
                Path root = library.root().toAbsolutePath().normalize();
                String safe = i.name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                if (safe.isBlank() || safe.startsWith(".")) safe = "clip_" + clipId + ".mp4";
                Path target = root.resolve(i.relPath.isBlank() ? safe : i.relPath).normalize();
                if (!target.startsWith(root) || Files.exists(target)) target = root.resolve("From Google Drive").resolve(safe).normalize();
                if (!target.startsWith(root)) throw new IOException("Unsafe file name");
                Files.createDirectories(target.getParent());
                drive.download(i.driveFileId, i.size, target, (d, t) -> progress.accept(t == 0 ? 1 : d / (double) t));
                Clip c = library.add(target, i.kind.isBlank() ? "imported" : i.kind, i.source.isBlank() ? "Google Drive" : i.source);
                c.driveFileId = i.driveFileId;
                c.driveSyncedSize = i.size;
                library.persist();
                synchronized (this) {
                    items.remove(clipId);
                    Item n = itemFor(c);
                    n.state = SYNCED;
                    n.driveFileId = i.driveFileId;
                    n.done = n.size;
                    items.put(c.id, n);
                    changed(n);
                }
                return target;
            } catch (IOException e) { throw new IllegalStateException(e.getMessage(), e); }
        }, uploads);
    }

    // ---------------- status ----------------

    public synchronized List<Item> items() {
        List<Item> l = new ArrayList<>();
        for (Item i : items.values()) l.add(i.copy());
        return l;
    }

    public synchronized Optional<Item> item(String clipId) {
        Item i = items.get(clipId);
        return i == null ? Optional.empty() : Optional.of(i.copy());
    }

    /** "local", "queued", "uploading", "synced", "failed" or "unavailable". */
    public String stateOf(String clipId) { return item(clipId).map(i -> i.state).orElse("local"); }

    /** Synced copies whose clip is no longer on this PC (shown as "On Google Drive only"). */
    public List<Item> cloudOnly() {
        List<Item> out = new ArrayList<>();
        for (Item i : items()) if (SYNCED.equals(i.state) && library.byId(i.clipId).isEmpty()) out.add(i);
        return out;
    }

    public String folderUrl() {
        String id = folders.get(cfg.folderName);
        return id == null ? "https://drive.google.com/drive/my-drive" : "https://drive.google.com/drive/folders/" + id;
    }

    /** Everything the settings page and the dashboard show. Never contains tokens or upload session URLs. */
    public JsonObject statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("available", true);
        o.addProperty("configured", auth.available());
        o.addProperty("connected", connected());
        o.addProperty("needsReauth", cfg.needsReauth);
        o.addProperty("name", cfg.accountName);
        o.addProperty("email", cfg.accountEmail);
        o.addProperty("hasAvatar", auth.avatar() != null && Files.isRegularFile(auth.avatar()));
        o.addProperty("autoUpload", cfg.autoUpload);
        o.addProperty("uploadClips", cfg.uploadClips);
        o.addProperty("uploadRecordings", cfg.uploadRecordings);
        o.addProperty("uploadScreenshots", cfg.uploadScreenshots);
        o.addProperty("keepLocal", cfg.keepLocal);
        o.addProperty("lastSyncAt", cfg.lastSyncAt);
        o.addProperty("folder", cfg.folderName);
        o.addProperty("folderUrl", folderUrl());
        o.addProperty("problem", lastProblem);
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray queue = new JsonArray();
        for (Item i : items()) {
            counts.merge(i.state, 1, Integer::sum);
            if (SYNCED.equals(i.state) && !library.byId(i.clipId).isEmpty()) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", i.clipId);
            j.addProperty("title", i.title);
            j.addProperty("category", i.category);
            j.addProperty("state", SYNCED.equals(i.state) ? "cloudOnly" : i.state);
            j.addProperty("done", i.done);
            j.addProperty("size", i.size);
            j.addProperty("error", i.error);
            j.addProperty("updatedAt", i.updatedAt);
            queue.add(j);
        }
        JsonObject c = new JsonObject();
        counts.forEach(c::addProperty);
        o.add("counts", c);
        o.add("queue", queue);
        return o;
    }

    /** Pauses or resumes uploading (disconnect pauses; nothing in the queue is lost). */
    public void pause(boolean p) { paused = p; if (!p) pump(); }

    /**
     * Signs out of Google: revokes the token and forgets it on this PC. Uploads in progress stop and stay
     * queued. Cloud files, local clips and the queue are all kept; clips already on Drive stay marked.
     */
    public void disconnect() throws IOException {
        for (Future<?> f : running.values()) f.cancel(true);
        running.clear();
        synchronized (this) {
            for (Item i : items.values()) {
                i.session = ""; // sessions belong to the account
                if (UPLOADING.equals(i.state)) { i.state = QUEUED; i.done = 0; }
            }
        }
        folders.clear();
        save();
        auth.disconnect();
        store.save();
    }

    public void close() { uploads.shutdownNow(); }
}
