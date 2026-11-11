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
    public static final String VERSION = "0.1.0";

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
        sync = new SyncService(store, library, bus, paths.tokenFile(), paths.root().resolve("sync-state.json"));
        dashboardAuth = new DashboardAuth(settings.remote);
        dashboard = new DashboardServer(settings.remote, dashboardAuth, this, paths.keystore(),
                new SecretStore(paths.root().resolve("dashboard-tls.key")));
        if (enableHotkeys) bindHotkeys();
        library.scanAsync();
        if (settings.mic.enabled) mic.start();
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

    @Override public JsonElement sounds() {
        JsonArray a = new JsonArray();
        for (Settings.Sound s : soundboard.sounds()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id);
            o.addProperty("name", s.name);
            o.addProperty("category", s.category);
            o.addProperty("playing", soundboard.isPlaying(s.id));
            a.add(o);
        }
        return a;
    }

    @Override public void toggleRecording() { recorder.toggle(); }
    @Override public void toggleReplay() { replay.toggle(); }
    @Override public void saveReplay() { replay.save(0); }
    @Override public void screenshot() { screenshots.capture(); }
    @Override public void setChannel(String id, double v, boolean m) { mixer.setChannel(id, v, m); }
    @Override public void playSound(String id) {
        if (soundboard.find(id).isEmpty()) throw new IllegalArgumentException("unknown sound");
        soundboard.toggle(id);
    }
    @Override public void stopSounds() { soundboard.stopAll(); }
}
