package io.zyclipra.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.zyclipra.audio.AudioPlatform;
import io.zyclipra.audio.MicProcessor;
import io.zyclipra.audio.MixerService;
import io.zyclipra.clips.Clip;
import io.zyclipra.clips.ClipLibrary;
import io.zyclipra.cloud.SecretStore;
import io.zyclipra.cloud.SyncService;
import io.zyclipra.core.AppPaths;
import io.zyclipra.core.EventBus;
import io.zyclipra.core.Log;
import io.zyclipra.core.Settings;
import io.zyclipra.core.SettingsStore;
import io.zyclipra.hotkeys.HotkeyService;
import io.zyclipra.platform.Desktop;
import io.zyclipra.recording.CaptureEngine;
import io.zyclipra.recording.RecordingService;
import io.zyclipra.recording.ReplayBuffer;
import io.zyclipra.recording.ScreenshotService;
import io.zyclipra.remote.DashboardAuth;
import io.zyclipra.remote.DashboardServer;
import io.zyclipra.remote.RemoteControl;
import io.zyclipra.soundboard.SoundboardService;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Builds every module and wires them together. The UI only talks to this. */
public final class AppContext implements RemoteControl {
    private static final Logger LOG = Log.get(AppContext.class);
    public static final String VERSION = "0.2.0";

    public final AppPaths paths;
    public final SettingsStore store;
    public final Settings settings;
    public final EventBus bus = new EventBus();
    public final Desktop desktop;
    public final CaptureEngine capture;
    public final ClipLibrary library;
    public final RecordingService recorder;
    public final ReplayBuffer replay;
    public final ScreenshotService screenshots;
    public final HotkeyService hotkeys;
    public final MicProcessor mic;
    public final MixerService mixer;
    public final SoundboardService soundboard;
    public final SyncService sync;
    public final DashboardAuth dashboardAuth;
    public final DashboardServer dashboard;
    public final io.zyclipra.update.Updater updater;
    /** A newer signed release found by the last check, or null. */
    public volatile io.zyclipra.update.Updater.Release availableUpdate;
    public final io.zyclipra.remote.WebsiteLink website;
    private final Map<String, String> hotkeyErrors = new LinkedHashMap<>();

    public AppContext(Path dataDir, boolean enableHotkeys) {
        paths = new AppPaths(dataDir);
        Log.init(paths.logs());
        store = new SettingsStore(paths.settingsFile());
        settings = store.get();
        desktop = Desktop.create();
        capture = new CaptureEngine(settings, desktop);
        library = new ClipLibrary(() -> Path.of(settings.recording.clipsDir), paths.root().resolve("library.json"),
                paths.thumbnails(), capture::ffmpeg, bus);
        recorder = new RecordingService(settings, capture, library, bus);
        replay = new ReplayBuffer(settings, capture, library, bus, paths.replayDir());
        screenshots = new ScreenshotService(settings, capture, library, bus);
        hotkeys = HotkeyService.create(); // automated check runs never bind, so they can't steal the person's shortcuts
        mic = new MicProcessor(settings, bus);
        mic.setRouteDevice(settings.mic.routeDevice);
        mixer = new MixerService(store, AudioPlatform.create(), mic);
        mixer.setDryRun(!enableHotkeys);
        soundboard = new SoundboardService(store, paths.soundsDir(), capture::ffmpeg, bus);
        soundboard.setRecordingTap(() -> capture.tracks().soundboardFeed());
        // The Google refresh token lives in Windows Credential Manager (falls back to a DPAPI-encrypted file).
        sync = new SyncService(store, library, bus,
                new io.zyclipra.cloud.CredentialStore("Zyclipra/google-drive", new SecretStore(paths.tokenFile())),
                paths.root().resolve("google-avatar.jpg"), paths.root().resolve("sync-queue.json"));
        // The Google OAuth client now comes built into the app; forget copies an older version kept in settings.json.
        if ("built-in".equals(sync.auth().client().source()) && (!settings.cloud.clientId.isBlank() || !settings.cloud.clientSecret.isBlank())) {
            settings.cloud.clientId = "";
            settings.cloud.clientSecret = "";
            store.saveNow();
            LOG.info("Google sign-in now uses the built-in client; removed the old client details from settings");
        }
        // Signed in by an older version (or the picture failed before): fetch name and picture in the background.
        if (sync.connected() && (sync.auth().avatar() == null || !java.nio.file.Files.exists(sync.auth().avatar())))
            java.util.concurrent.CompletableFuture.runAsync(() -> { sync.auth().loadProfile(); store.save(); });
        dashboardAuth = new DashboardAuth(settings.remote, store::saveNow);
        dashboardAuth.onAlert(m -> bus.emit(EventBus.ERROR, m));
        dashboard = new DashboardServer(settings.remote, dashboardAuth, this, paths.keystore(),
                new SecretStore(paths.root().resolve("dashboard-tls.key")));
        website = new io.zyclipra.remote.WebsiteLink(settings.remote, store::saveNow,
                new io.zyclipra.cloud.CredentialStore("Zyclipra/website-link", new SecretStore(paths.root().resolve("website-link.key"))),
                sync, dashboard, dashboardAuth, settings.remote.pcId, new io.zyclipra.remote.RtcBridge(dashboard.commands(), this));
        if (enableHotkeys) bindHotkeys();
        library.scanAsync();
        if (settings.mic.enabled) mic.start();
        updater = new io.zyclipra.update.Updater(VERSION, paths.root().resolve("updates"));
        if (enableHotkeys && settings.general.checkUpdates) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(8000);
                    checkForUpdate();
                } catch (Exception e) { LOG.info("update check: " + e.getMessage()); }
            });
        }
        if (settings.remote.enabled) {
            try { dashboard.start(); } catch (Exception e) { LOG.warning("Dashboard failed to start: " + e); }
        }
        LOG.info("Zyclipra " + VERSION + " started, data in " + paths.root());
    }

    public static final Map<String, String> HOTKEY_LABELS = new LinkedHashMap<>();
    static {
        HOTKEY_LABELS.put("toggleRecording", "Start / stop recording");
        HOTKEY_LABELS.put("saveReplay", "Save instant replay");
        HOTKEY_LABELS.put("toggleReplay", "Turn instant replay on / off");
        HOTKEY_LABELS.put("screenshot", "Take screenshot");
        HOTKEY_LABELS.put("stopSounds", "Stop all sounds");
        HOTKEY_LABELS.put("toggleMicMute", "Mute / unmute microphone");
    }

    private Runnable action(String id) {
        return switch (id) {
            case "toggleRecording" -> recorder::toggle;
            case "saveReplay" -> this::clipNow;
            case "toggleReplay" -> replay::toggle;
            case "screenshot" -> screenshots::capture;
            case "stopSounds" -> soundboard::stopAll;
            case "toggleMicMute" -> () -> {
                Settings.Channel c = mixer.channel("mic");
                mixer.setChannel("mic", c.volume, !c.muted);
            };
            default -> () -> { };
        };
    }

    /**
     * The "save clip" key. Saves the last moments when instant replay is running; when it isn't,
     * it turns replay on so the next press works, and says so (instead of silently doing nothing).
     */
    public void clipNow() {
        if (replay.running()) { replay.save(0); return; }
        if (capture.ffmpeg().isEmpty()) { bus.emit(EventBus.ERROR, "Can't clip: FFmpeg wasn't found (Settings › Capture and Sound)."); return; }
        replay.start();
        bus.emit(EventBus.NOTIFY, "Instant replay was off, so it's on now. Press " + settings.hotkeys.get("saveReplay")
                + " again in a few seconds to save a clip.");
    }

    /** (Re)registers all global shortcuts; returns problems per action. */
    public Map<String, String> bindHotkeys() {
        hotkeyErrors.clear();
        for (String id : HOTKEY_LABELS.keySet()) {
            String err = hotkeys.bind(id, settings.hotkeys.get(id), action(id));
            if (err != null) hotkeyErrors.put(id, err);
        }
        for (Settings.Sound s : settings.soundboard.sounds) {
            String err = hotkeys.bind("sound:" + s.id, s.hotkey, () -> soundboard.toggle(s.id));
            if (err != null) hotkeyErrors.put("sound:" + s.id, err);
        }
        return Map.copyOf(hotkeyErrors);
    }

    public Map<String, String> hotkeyErrors() { return Map.copyOf(hotkeyErrors); }

    public void shutdown() {
        LOG.info("Shutting down");
        try { recorder.shutdown(); } catch (Exception ignored) { }
        try { replay.shutdown(); } catch (Exception ignored) { }
        soundboard.stopAll();
        mic.stop();
        dashboard.stop();
        website.shutdown();
        hotkeys.close();
        mixer.close();
        sync.close();
        library.close();
        store.close();
    }

    // ---------------- RemoteControl ----------------

    @Override public JsonElement status() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("recording", recorder.recording());
        o.addProperty("recordingState", recorder.state().name());
        o.addProperty("elapsedMs", recorder.elapsedMs());
        o.addProperty("replay", replay.running());
        o.addProperty("replaySeconds", settings.recording.replaySeconds);
        o.addProperty("replayAvailable", replay.availableSeconds());
        o.addProperty("ffmpeg", capture.ffmpeg().isPresent());
        o.addProperty("micProcessing", mic.running());
        o.addProperty("audioControl", mixer.available());
        o.addProperty("clips", library.all().size());
        o.addProperty("storageBytes", library.totalBytes());
        Runtime rt = Runtime.getRuntime();
        o.addProperty("memoryMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        o.addProperty("cpuLoad", cpuLoad());
        return o;
    }

    static double cpuLoad() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return Math.round(os.getProcessCpuLoad() * 1000) / 10.0;
        } catch (Throwable t) { return -1; }
    }

    /** Asks GitHub for a newer signed release; tells the person once when one is found. */
    public java.util.Optional<io.zyclipra.update.Updater.Release> checkForUpdate() throws java.io.IOException {
        var r = updater.check();
        boolean isNew = r.isPresent() && (availableUpdate == null || !availableUpdate.version().equals(r.get().version()));
        availableUpdate = r.orElse(null);
        if (isNew) bus.emit(EventBus.NOTIFY, "Zyclipra " + r.get().version() + " is available – install it in Settings › About and Privacy.");
        return r;
    }

    @Override public JsonElement clips(int limit) {
        JsonArray a = new JsonArray();
        for (Clip c : library.all()) {
            if (a.size() >= limit) break;
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id);
            o.addProperty("title", c.title);
            o.addProperty("source", c.source);
            o.addProperty("kind", c.kind);
            o.addProperty("createdAt", c.createdAt);
            o.addProperty("durationSec", c.durationSec);
            o.addProperty("sizeBytes", c.sizeBytes);
            o.addProperty("width", c.width);
            o.addProperty("height", c.height);
            o.addProperty("fps", c.fps);
            o.addProperty("favorite", c.favorite);
            o.addProperty("video", c.isVideo());
            o.addProperty("audioTracks", c.audioTracks);
            o.addProperty("description", c.description);
            o.addProperty("sync", sync.stateOf(c.id));
            a.add(o);
        }
        return a;
    }

    @Override public java.util.Optional<java.nio.file.Path> clipFile(String id) {
        return library.byId(id).map(library::file).filter(java.nio.file.Files::isRegularFile);
    }

    @Override public java.util.Optional<java.nio.file.Path> clipThumbnail(String id) {
        return library.byId(id).map(library::thumbnail).filter(java.nio.file.Files::isRegularFile);
    }

    @Override public JsonElement levels() {
        JsonObject o = new JsonObject();
        mixer.levels().forEach(o::addProperty);
        return o;
    }

    @Override public JsonElement mixer() {
        JsonArray a = new JsonArray();
        for (String id : MixerService.CHANNEL_IDS) {
            Settings.Channel c = mixer.channel(id);
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", MixerService.CHANNEL_NAMES.get(id));
            o.addProperty("volume", c.volume);
            o.addProperty("muted", c.muted);
            a.add(o);
        }
        return a;
    }

    /** The whole soundboard as dashboards show it: buttons, pages, volumes and what's playing. */
    @Override public JsonElement sounds() {
        Settings.Soundboard cfg = soundboard.config();
        JsonObject root = new JsonObject();
        root.addProperty("master", cfg.masterVolume);
        root.addProperty("limitTo100", cfg.limitTo100);
        root.addProperty("output", cfg.outputDevice);
        JsonArray pages = new JsonArray();
        cfg.categories.forEach(pages::add);
        root.add("pages", pages);
        JsonArray a = new JsonArray();
        for (Settings.Sound s : soundboard.sounds()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id);
            o.addProperty("name", s.name);
            o.addProperty("category", s.category);
            o.addProperty("volume", s.volume);
            o.addProperty("color", s.color);
            o.addProperty("icon", s.icon);
            o.addProperty("loop", s.loop);
            o.addProperty("hotkey", s.hotkey);
            o.addProperty("duration", Math.round(soundboard.durationSec(s) * 10) / 10.0);
            o.addProperty("playing", soundboard.isPlaying(s.id));
            o.addProperty("progress", soundboard.progress(s.id));
            a.add(o);
        }
        root.add("sounds", a);
        return root;
    }

    private java.util.List<String> outputsCache = java.util.List.of();
    private long outputsAt;

    /** Playback devices (cached for 30 s; listing them can take a moment). */
    @Override public JsonElement soundOutputs() {
        if (System.currentTimeMillis() - outputsAt > 30_000) {
            outputsCache = io.zyclipra.audio.SoundDevices.outputs().stream().filter(d -> !d.startsWith("Primary Sound")).toList();
            outputsAt = System.currentTimeMillis();
        }
        JsonArray a = new JsonArray();
        outputsCache.forEach(a::add);
        return a;
    }

    @Override public JsonElement soundPlay(String id) {
        Settings.Sound s = soundboard.find(id).orElseThrow(() -> new IllegalArgumentException("That sound doesn't exist (any more)."));
        soundboard.play(id);
        if (!soundboard.isPlaying(id)) throw new IllegalStateException("“" + s.name + "” couldn't play on the PC – check the soundboard output in Zyclipra.");
        return result("Playing “" + s.name + "” on your PC.");
    }

    @Override public JsonElement soundStop(String id) {
        soundboard.stop(id);
        return result("Stopped.");
    }

    @Override public JsonElement soundVolume(String id, double gain) {
        soundboard.setVolume(id, gain);
        return result("Volume " + Math.round(io.zyclipra.soundboard.SoundboardService.clampGain(gain) * 100) + " %");
    }

    @Override public JsonElement soundMaster(double gain) {
        soundboard.setMasterVolume(gain);
        return result("Soundboard volume " + Math.round(io.zyclipra.soundboard.SoundboardService.clampGain(gain) * 100) + " %");
    }

    @Override public JsonElement soundLimit(boolean on) {
        soundboard.setLimitTo100(on);
        return result(on ? "Sounds are now never louder than 100 %." : "Amplification up to 300 % allowed.");
    }

    @Override public JsonElement soundLook(String id, String color, String icon) { soundboard.setLook(id, color, icon); return result("Saved."); }

    @Override public JsonElement soundMove(String id, int index) { soundboard.move(id, index); return result("Moved."); }

    @Override public JsonElement soundPage(String id, String page) { soundboard.setCategory(id, page); return result("Moved to " + page + "."); }

    @Override public JsonElement soundOutput(String device) {
        if (!device.isEmpty()) {
            soundOutputs();
            if (!outputsCache.contains(device)) throw new IllegalArgumentException("That playback device isn't available on the PC.");
        }
        soundboard.config().outputDevice = device;
        store.save();
        bus.emit(EventBus.SOUNDS_CHANGED, null);
        return result("Sounds play on " + (device.isEmpty() ? "the Windows default device" : device) + ".");
    }

    @Override public JsonElement startRecording() {
        if (recorder.state() == RecordingService.State.RECORDING) return result("Already recording.");
        return toggleRecording();
    }

    @Override public JsonElement stopRecording() {
        if (recorder.state() == RecordingService.State.IDLE) return result("Not recording.");
        return toggleRecording();
    }

    @Override public JsonElement setReplay(boolean on) {
        if (replay.running() == on) return result(on ? "Instant replay is already on." : "Instant replay is already off.");
        return toggleReplay();
    }

    @Override public JsonElement setReplaySeconds(int seconds) {
        settings.recording.replaySeconds = Math.max(15, Math.min(300, seconds));
        store.save();
        if (replay.running()) {
            await(replay.stop(), 20, "Restarting instant replay");
            await(replay.start(), 20, "Restarting instant replay");
            if (!replay.running()) throw new IllegalStateException("Instant replay didn't restart" + (replay.lastError().isBlank() ? "." : ": " + replay.lastError()));
        }
        return result("Instant replay keeps the last " + settings.recording.replaySeconds + " s.");
    }

    // ---- dashboard actions: they run on this PC and report what really happened

    private static <T> T await(java.util.concurrent.Future<T> f, int seconds, String what) {
        try { return f.get(seconds, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) { throw new IllegalStateException(what + " is taking longer than expected – check Zyclipra on the PC."); }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(c.getMessage() == null ? what + " failed." : c.getMessage());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(what + " was interrupted."); }
    }

    private static JsonObject result(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", message);
        return o;
    }

    private static JsonObject clipJson(Clip c) {
        JsonObject j = new JsonObject();
        j.addProperty("id", c.id);
        j.addProperty("title", c.title);
        return j;
    }

    @Override public JsonElement toggleRecording() {
        boolean wasRecording = recorder.state() != RecordingService.State.IDLE;
        await(recorder.toggle(), 30, wasRecording ? "Stopping the recording" : "Starting the recording");
        if (!wasRecording && recorder.state() != RecordingService.State.RECORDING)
            throw new IllegalStateException(recorder.lastError().isBlank() ? "The recording didn't start." : recorder.lastError());
        return result(wasRecording ? "Recording stopped and saved." : "Recording started on your PC.");
    }

    @Override public JsonElement toggleReplay() {
        boolean was = replay.running();
        await(replay.toggle(), 20, was ? "Turning instant replay off" : "Turning instant replay on");
        if (!was && !replay.running()) throw new IllegalStateException("Instant replay didn't start" + (replay.lastError().isBlank() ? "." : ": " + replay.lastError()));
        return result(was ? "Instant replay is off." : "Instant replay is on – press Clip it whenever something good happens.");
    }

    @Override public JsonElement saveReplay() {
        Clip c = await(replay.save(0), 120, "Saving the clip");
        JsonObject o = result("Clipped! “" + c.title + "” is saved on your PC" + (sync.stateOf(c.id).equals("local") ? "." : " and queued for Google Drive."));
        o.add("clip", clipJson(c));
        return o;
    }

    @Override public JsonElement screenshot() {
        Clip c = await(screenshots.capture(), 30, "Taking the screenshot");
        JsonObject o = result("Screenshot saved.");
        o.add("clip", clipJson(c));
        return o;
    }

    // ---- Google Drive for the dashboard (preferences it may change: automatic upload and which kinds)

    @Override public JsonElement cloudStatus() { return sync.statusJson(); }

    @Override public JsonElement cloudSettings(JsonObject changes) {
        Settings.Cloud c = settings.cloud;
        for (String k : changes.keySet()) {
            if (!changes.get(k).isJsonPrimitive() || !changes.get(k).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(k + " must be true or false");
            boolean v = changes.get(k).getAsBoolean();
            switch (k) {
                case "autoUpload" -> c.autoUpload = v;
                case "uploadClips" -> c.uploadClips = v;
                case "uploadRecordings" -> c.uploadRecordings = v;
                case "uploadScreenshots" -> c.uploadScreenshots = v;
                default -> throw new IllegalArgumentException("That setting can only be changed in the Zyclipra app.");
            }
        }
        store.save();
        bus.emit(EventBus.CLIPS_CHANGED, null);
        return sync.statusJson();
    }

    @Override public JsonElement cloudUpload(String clipId) {
        Clip c = library.byId(clipId).orElseThrow(() -> new IllegalArgumentException("unknown clip"));
        return result(sync.upload(c));
    }

    @Override public JsonElement cloudRetry(String clipId) {
        int n = sync.retry(clipId);
        if (n == 0) throw new IllegalStateException(clipId == null ? "Nothing to retry." : "That clip isn't a failed upload.");
        return result(n == 1 ? "Trying again." : "Trying " + n + " uploads again.");
    }

    @Override public JsonElement cloudCancel(String clipId) { return result(sync.cancel(clipId)); }

    private volatile boolean signingIn;

    @Override public JsonElement cloudConnect() {
        if (!sync.auth().available()) throw new IllegalStateException("This copy of Zyclipra was built without Google sign-in.");
        if (sync.connected()) return result("Already connected to Google Drive.");
        if (signingIn) return result("Google sign-in is already open in the browser on your PC.");
        signingIn = true;
        sync.auth().signIn(url -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
            catch (Exception e) { bus.emit(EventBus.ERROR, "Couldn't open the browser for Google sign-in: " + e.getMessage()); }
        }).whenComplete((email, err) -> {
            signingIn = false;
            store.save();
            if (err != null) bus.emit(EventBus.ERROR, "Google sign-in: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
            else bus.emit(EventBus.NOTIFY, "Connected to Google Drive as " + email);
        });
        return result("Google sign-in opened in the browser on your PC – choose your account there and allow access.");
    }

    @Override public JsonElement cloudDisconnect() {
        try { sync.disconnect(); } catch (java.io.IOException e) { throw new IllegalStateException("Disconnect: " + e.getMessage()); }
        return result("Google Drive disconnected. Nothing was deleted on Google Drive or your PC.");
    }

    @Override public java.util.Optional<java.nio.file.Path> cloudAvatar() {
        java.nio.file.Path p = sync.auth().avatar();
        return p != null && java.nio.file.Files.isRegularFile(p) ? java.util.Optional.of(p) : java.util.Optional.empty();
    }
    @Override public void setChannel(String id, double v, boolean m) { mixer.setChannel(id, v, m); }
    @Override public void playSound(String id) {
        if (soundboard.find(id).isEmpty()) throw new IllegalArgumentException("unknown sound");
        soundboard.toggle(id);
    }
    @Override public void stopSounds() { soundboard.stopAll(); }
}
