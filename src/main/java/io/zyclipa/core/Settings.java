package io.zyclipa.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every persisted setting. Plain fields so Gson can map them; {@link #sanitize()} repairs
 * missing or out-of-range values after loading a file that may be old, edited by hand or damaged.
 */
public final class Settings {
    public int version = 1;
    public General general = new General();
    public Recording recording = new Recording();
    public Map<String, String> hotkeys = new LinkedHashMap<>();
    public Mixer mixer = new Mixer();
    public Equalizer equalizer = new Equalizer();
    public Mic mic = new Mic();
    public Soundboard soundboard = new Soundboard();
    public Cloud cloud = new Cloud();
    public Remote remote = new Remote();

    public static final class General {
        public String accent = "#5B6CF9";
        /** system (follow Windows) | dark | light */
        public String theme = "system";
        public boolean showOverlay = true;
        public boolean reduceMotion = false;
        public String ffmpegPath = "";
        /** Look for a newer Zyclipa on GitHub shortly after start (nothing is installed without asking). */
        public boolean checkUpdates = true;
        /** Closing the window hides Zyclipa in the tray instead of quitting. */
        public boolean runInBackground = true;
        public boolean trayHintShown = false;
    }

    public static final class Recording {
        public String clipsDir = "";
        /** Turn instant replay on by itself while a game is in front (and off again after the game closes). */
        public boolean autoReplayInGames = true;
        public String container = "mp4";      // mp4 | mkv
        public String captureMode = "display"; // display | window
        public int displayIndex = 0;
        public String windowTitle = "";
        public int fps = 30;
        public int outputHeight = 1080;        // 0 = native
        public int bitrateKbps = 20000;
        public String quality = "balanced";    // performance | balanced | quality
        public String encoder = "auto";        // auto | h264_nvenc | h264_qsv | h264_amf | libx264
        public List<String> audioInputs = new ArrayList<>(); // dshow device names, one track each
        public boolean separateTracks = true;
        /** Native sound tracks (see AudioTracks). */
        public boolean recordGameAudio = true;
        public boolean recordChatAudio = true;
        public boolean recordMic = true;
        public boolean recordSoundboard = true;
        public boolean replayEnabled = true;
        public int replaySeconds = 60;
        public long storageLimitMb = 0;        // 0 = unlimited
    }

    public static final class Mixer {
        public Map<String, Channel> channels = new LinkedHashMap<>();
        /** process executable name (lower case) -> logical channel id */
        public Map<String, String> appChannels = new LinkedHashMap<>();
        /** process name (lower case) -> that app's own volume inside its channel */
        public Map<String, Channel> appVolumes = new LinkedHashMap<>();
        public Map<String, Map<String, Channel>> profiles = new LinkedHashMap<>();
        public String activeProfile = "Default";
        /** Effects per app channel (game, chat, media, aux). */
        public Map<String, ChannelFx> fx = new LinkedHashMap<>();
        /** Session keys Zyclipa muted while processing their sound, restored on start-up after a crash. */
        public List<String> mutedForProcessing = new ArrayList<>();
    }

    /** Sound effects for one app channel. Any effect switched on makes Zyclipa process that channel. */
    public static final class ChannelFx {
        public Equalizer eq = new Equalizer();
        public double boostDb = 0;            // volume boost 0..12 dB
        public boolean smartVolume = false;   // automatic level evening
        public double smartLevel = 0.5;       // 0 quiet .. 1 loud target
        public boolean spatial = false;
        public String spatialMode = "headphones"; // headphones | speakers
        public double immersion = 0.5;        // 0 = performance (dry), 1 = immersion (wide/room)
        public double distance = 0.5;         // 0 near .. 1 far
        public boolean noiseCancel = false;   // chat: remove background noise from what others send
        public double noiseAmount = 0.6;
        public boolean gate = false;
        public double gateDb = -50;
        public boolean compressor = false;
        public double compAmount = 0.5;
        public List<String> favorites = new ArrayList<>();
        public String outputDevice = "";      // "" = default playback device
        /** Device the channel's apps play into (normally a virtual cable); its sound is processed. */
        public String sourceDeviceId = "";
        public String sourceDeviceName = "";

        public boolean active() {
            return eq.enabled || boostDb > 0.05 || smartVolume || spatial || noiseCancel || gate || compressor;
        }
    }

    public static final class Channel {
        public double volume = 1.0;
        public boolean muted = false;
        public Channel() {}
        public Channel(double v, boolean m) { volume = v; muted = m; }
    }

    public static final class EqBand {
        public double freq;
        public double gainDb;
        public double q;
        public String type = "peak"; // peak | lowshelf | highshelf
        public EqBand() {}
        public EqBand(double f, double g, double q, String type) { this.freq = f; this.gainDb = g; this.q = q; this.type = type; }
    }

    public static final class Equalizer {
        public boolean enabled = false;
        public String preset = "Flat";
        public List<EqBand> bands = defaultBands();
        public Map<String, List<EqBand>> presets = new LinkedHashMap<>();
    }

    public static List<EqBand> defaultBands() {
        double[] f = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000};
        List<EqBand> l = new ArrayList<>();
        for (int i = 0; i < f.length; i++) {
            String t = i == 0 ? "lowshelf" : (i == f.length - 1 ? "highshelf" : "peak");
            l.add(new EqBand(f[i], 0, 1.0, t));
        }
        return l;
    }

    public static final class Mic {
        public String inputDevice = "";
        public String monitorDevice = "";
        /** Where the processed voice is sent, e.g. a virtual cable chat apps use as microphone. */
        public String routeDevice = "";
        public boolean enabled = false;
        public double gainDb = 0;
        public boolean gate = true;
        public double gateThresholdDb = -50;
        public boolean compressor = true;
        public double compThresholdDb = -20;
        public double compRatio = 3;
        public boolean limiter = true;
        public double limiterCeilingDb = -1;
        public boolean noiseSuppression = false;
        public double noiseReductionDb = 12;
        public boolean monitor = false;
        public boolean eq = false;
    }

    public static final class Sound {
        public String id;
        public String name;
        public String file;       // stored copy inside the data folder
        public String category = "General";
        /** Gain for this sound: 0 = silent, 1 = as recorded, up to 3 = 3× amplitude. */
        public double volume = 1.0;
        public boolean loop = false;
        public String hotkey = "";
        /** Button colour (#RRGGBB, "" = default) and a short icon (an emoji or up to 2 letters). */
        public String color = "";
        public String icon = "";
    }

    public static final class Soundboard {
        public List<Sound> sounds = new ArrayList<>();
        public List<String> categories = new ArrayList<>(List.of("General"));
        public String outputDevice = "";    // local playback device ("" = default)
        public String micRouteDevice = "";  // second device, e.g. a virtual cable feeding voice chat
        /** Gain for all sounds, 0–3 (100% = 1). Combined with each sound's gain; see SoundboardService.gainFor. */
        public double masterVolume = 1.0;
        /** Safety: never play louder than the original recording (caps the combined gain at 100%). */
        public boolean limitTo100 = false;
        public boolean playLocally = true;
        public boolean useEq = false;
    }

    public static final class Cloud {
        public boolean enabled = false;
        public String clientId = "";
        /** Google requires it for "Desktop app" clients but documents it as not secret; user supplied, never shipped. */
        public String clientSecret = "";
        /** Top folder in Drive; Clips, Recordings and Screenshots go in sub-folders. */
        public String folderName = "Zyclipa";
        /** Old per-clip selection (before the upload queue); read once and moved into the queue. */
        public List<String> selectedClips = new ArrayList<>();
        public String accountEmail = "";
        public String accountName = "";
        /** Google refused the saved sign-in (revoked or expired): the user must reconnect. */
        public boolean needsReauth = false;
        public boolean autoUpload = false;
        public boolean uploadClips = true;       // instant replays, trims and imports
        public boolean uploadRecordings = true;  // manual recordings
        public boolean uploadScreenshots = true;
        /** false = after a verified upload the local file goes to the Recycle Bin. Off only by explicit choice. */
        public boolean keepLocal = true;
        public long lastSyncAt;
    }

    public static final class Remote {
        public boolean enabled = false;
        public int port = 47820;
        /** local = this PC only (plus LAN/VPN below), public = behind the user's own HTTPS reverse proxy. */
        public String mode = "local";
        public boolean lan = false;          // devices on the home network
        public boolean vpn = false;          // devices on a private VPN (Tailscale)
        public String publicUrl = "";        // https://… served by the user's reverse proxy
        public long publicVerifiedAt;        // when Zyclipa last reached itself through publicUrl
        public String username = "";
        /** Argon2id hash in PHC format ($argon2id$v=19$m=…,t=…,p=…$salt$hash); never the password itself. */
        public String passwordHash = "";
        /** The hosted website (GitHub Pages) may use this PC's controls on the home network. */
        public boolean website = false;
        public String webOrigin = "https://madaai.github.io";
        /** SHA-256 of the link key the website reads from the owner's Google Drive; the key itself isn't stored here. */
        public String webKeyHash = "";
        /** Random id of this install, so the website can tell several PCs apart. */
        public String pcId = "";
        public String pairingCodeHash = "";  // legacy (pairing-code login), no longer used
        public String pairingSalt = "";      // legacy
    }

    public static final String[] CHANNELS = {"game", "chat", "media", "aux", "mic", "master"};

    /** Control characters and text-direction overrides: never allowed in names (they can disguise text in lists and logs). */
    public static final java.util.regex.Pattern UNSAFE_TEXT = java.util.regex.Pattern.compile("[\\p{Cc}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    /** A name with unsafe characters removed, trimmed and cut to {@code max} characters. */
    public static String clean(String s, int max) {
        if (s == null) return null;
        String c = UNSAFE_TEXT.matcher(s).replaceAll("").strip();
        return c.length() > max ? c.substring(0, max) : c;
    }

    public Settings sanitize() {
        if (general == null) general = new General();
        if (recording == null) recording = new Recording();
        if (hotkeys == null) hotkeys = new LinkedHashMap<>();
        if (mixer == null) mixer = new Mixer();
        if (equalizer == null) equalizer = new Equalizer();
        if (mic == null) mic = new Mic();
        if (soundboard == null) soundboard = new Soundboard();
        if (cloud == null) cloud = new Cloud();
        if (remote == null) remote = new Remote();

        if (!"dark".equals(general.theme) && !"light".equals(general.theme)) general.theme = "system";
        if (general.accent == null || !general.accent.matches("#[0-9A-Fa-f]{6}") || general.accent.equalsIgnoreCase("#7C5CFF")) general.accent = "#5B6CF9";
        if (general.ffmpegPath == null) general.ffmpegPath = "";

        Recording r = recording;
        if (r.clipsDir == null || r.clipsDir.isBlank()) r.clipsDir = AppPaths.defaultClipsDir().toString();
        if (!"mkv".equals(r.container)) r.container = "mp4";
        if (!"window".equals(r.captureMode)) r.captureMode = "display";
        if (r.windowTitle == null) r.windowTitle = "";
        r.displayIndex = Math.max(0, r.displayIndex);
        r.fps = clamp(r.fps, 10, 144);
        if (r.outputHeight != 0) r.outputHeight = clamp(r.outputHeight, 360, 2160);
        r.bitrateKbps = clamp(r.bitrateKbps, 1000, 100_000);
        if (!List.of("performance", "balanced", "quality").contains(r.quality)) r.quality = "balanced";
        if (!List.of("auto", "h264_nvenc", "h264_qsv", "h264_amf", "libx264").contains(r.encoder)) r.encoder = "auto";
        if (r.audioInputs == null) r.audioInputs = new ArrayList<>();
        r.replaySeconds = clamp(r.replaySeconds, 15, 300);
        r.storageLimitMb = Math.max(0, r.storageLimitMb);

        hotkeys.putIfAbsent("toggleRecording", "Alt+F9");
        hotkeys.putIfAbsent("saveReplay", "Alt+F10");
        hotkeys.putIfAbsent("screenshot", "Alt+F12");
        hotkeys.putIfAbsent("toggleReplay", "Alt+Shift+F10");
        hotkeys.putIfAbsent("stopSounds", "Ctrl+Alt+End");
        hotkeys.putIfAbsent("toggleMicMute", "Ctrl+Alt+M");

        if (mixer.channels == null) mixer.channels = new LinkedHashMap<>();
        for (String c : CHANNELS) mixer.channels.putIfAbsent(c, new Channel());
        for (Channel c : mixer.channels.values()) { c.volume = clampD(c.volume, 0, 1); }
        if (mixer.appChannels == null) mixer.appChannels = new LinkedHashMap<>();
        if (mixer.appVolumes == null) mixer.appVolumes = new LinkedHashMap<>();
        mixer.appChannels.values().removeIf(v -> v == null || !List.of(CHANNELS).contains(v) || "master".equals(v) || "mic".equals(v));
        if (mixer.profiles == null) mixer.profiles = new LinkedHashMap<>();
        if (mixer.fx == null) mixer.fx = new LinkedHashMap<>();
        if (mixer.mutedForProcessing == null) mixer.mutedForProcessing = new ArrayList<>();
        for (String ch : List.of("game", "chat", "media", "aux")) {
            ChannelFx f = mixer.fx.computeIfAbsent(ch, k -> new ChannelFx());
            if (f.eq == null || f.eq.bands == null || f.eq.bands.size() != 9) f.eq = new Equalizer();
            if (f.eq.presets == null) f.eq.presets = new LinkedHashMap<>();
            if (f.eq.preset == null) f.eq.preset = "Flat";
            for (EqBand b : f.eq.bands) { b.freq = clampD(b.freq, 20, 20000); b.gainDb = clampD(b.gainDb, -12, 12); b.q = clampD(b.q, 0.1, 10); }
            f.boostDb = clampD(f.boostDb, 0, 12);
            f.smartLevel = clampD(f.smartLevel, 0, 1);
            f.immersion = clampD(f.immersion, 0, 1);
            f.distance = clampD(f.distance, 0, 1);
            f.noiseAmount = clampD(f.noiseAmount, 0, 1);
            f.gateDb = clampD(f.gateDb, -90, 0);
            f.compAmount = clampD(f.compAmount, 0, 1);
            if (!"speakers".equals(f.spatialMode)) f.spatialMode = "headphones";
            if (f.favorites == null) f.favorites = new ArrayList<>();
            if (f.outputDevice == null) f.outputDevice = "";
            if (f.sourceDeviceId == null) f.sourceDeviceId = "";
            if (f.sourceDeviceName == null) f.sourceDeviceName = "";
        }
        if (mixer.activeProfile == null) mixer.activeProfile = "Default";

        Equalizer e = equalizer;
        if (e.bands == null || e.bands.size() != 9) e.bands = defaultBands();
        for (EqBand b : e.bands) {
            b.freq = clampD(b.freq, 20, 20000);
            b.gainDb = clampD(b.gainDb, -12, 12);
            b.q = clampD(b.q, 0.1, 10);
            if (!List.of("peak", "lowshelf", "highshelf").contains(b.type)) b.type = "peak";
        }
        if (e.presets == null) e.presets = new LinkedHashMap<>();

        Mic m = mic;
        if (m.inputDevice == null) m.inputDevice = "";
        if (m.monitorDevice == null) m.monitorDevice = "";
        if (m.routeDevice == null) m.routeDevice = "";
        m.gainDb = clampD(m.gainDb, -20, 30);
        m.gateThresholdDb = clampD(m.gateThresholdDb, -90, 0);
        m.compThresholdDb = clampD(m.compThresholdDb, -60, 0);
        m.compRatio = clampD(m.compRatio, 1, 20);
        m.limiterCeilingDb = clampD(m.limiterCeilingDb, -20, 0);
        m.noiseReductionDb = clampD(m.noiseReductionDb, 0, 40);

        Soundboard s = soundboard;
        if (s.sounds == null) s.sounds = new ArrayList<>();
        s.sounds.removeIf(x -> x == null || x.id == null || x.file == null);
        if (s.categories == null || s.categories.isEmpty()) s.categories = new ArrayList<>(List.of("General"));
        s.categories.replaceAll(c -> clean(c, 30));
        s.categories.removeIf(c -> c == null || c.isBlank());
        if (s.categories.isEmpty()) s.categories.add("General");
        for (Sound x : s.sounds) {
            x.name = clean(x.name, 80);
            x.category = clean(x.category, 30);
            if (x.name == null || x.name.isBlank()) x.name = "Sound";
            if (x.category == null || x.category.isBlank()) x.category = "General";
            if (!s.categories.contains(x.category)) s.categories.add(x.category);
            x.volume = clampD(x.volume, 0, 3);   // 0–300 %
            if (x.hotkey == null) x.hotkey = "";
            if (x.color == null || !x.color.matches("#[0-9A-Fa-f]{6}")) x.color = "";
            if (x.icon == null || x.icon.length() > 8) x.icon = "";
        }
        if (s.outputDevice == null) s.outputDevice = "";
        if (s.micRouteDevice == null) s.micRouteDevice = "";
        s.masterVolume = clampD(s.masterVolume, 0, 3);

        if (cloud.clientId == null) cloud.clientId = "";
        if (cloud.clientSecret == null) cloud.clientSecret = "";
        if (cloud.folderName == null || cloud.folderName.isBlank() || cloud.folderName.length() > 80 || cloud.folderName.equals("Zyclipa Clips") /* old default */
                || cloud.folderName.equals("Zyclipra") /* the app's old name */) cloud.folderName = "Zyclipa";
        if (cloud.accountName == null) cloud.accountName = "";
        if (cloud.selectedClips == null) cloud.selectedClips = new ArrayList<>();
        if (cloud.accountEmail == null) cloud.accountEmail = "";

        remote.port = clamp(remote.port, 1024, 65535);
        if (remote.pairingCodeHash == null) remote.pairingCodeHash = "";
        if (!"local".equals(remote.mode) && !"public".equals(remote.mode)) remote.mode = "local";
        if (remote.publicUrl == null || !remote.publicUrl.startsWith("https://")) { remote.publicUrl = ""; if ("public".equals(remote.mode)) remote.mode = "local"; }
        if (remote.username == null) remote.username = "";
        if (remote.webOrigin == null || !remote.webOrigin.matches("https://[a-z0-9.-]+(:[0-9]{1,5})?")) remote.webOrigin = "https://madaai.github.io";
        if (remote.pcId == null || !remote.pcId.matches("[A-Za-z0-9_-]{8,40}")) remote.pcId = io.zyclipa.cloud.Pkce.random(12);
        if (remote.webKeyHash == null || !remote.webKeyHash.matches("[0-9a-f]{64}")) { remote.webKeyHash = ""; remote.website = false; }
        if (remote.passwordHash == null || !remote.passwordHash.startsWith("$argon2id$")) remote.passwordHash = "";
        if (remote.pairingSalt == null) remote.pairingSalt = "";
        return this;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static double clampD(double v, double lo, double hi) { return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }
}
