package io.zyclipa.audio;

import io.zyclipa.core.Log;
import io.zyclipa.core.Settings;
import io.zyclipa.core.SettingsStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Sonar-style logical channels on top of Windows per-app audio sessions.
 * Game/Chat/Media/Aux each own a set of apps; an app's real Windows session volume is
 * set to channel volume x the app's own volume. Master drives the default output device,
 * Mic drives the default recording device (and Zyclipa's processed mic, when on).
 */
public final class MixerService implements AutoCloseable {
    private static final Logger LOG = Log.get(MixerService.class);
    public static final List<String> CHANNEL_IDS = List.of(Settings.CHANNELS);
    public static final Map<String, String> CHANNEL_NAMES = Map.of("game", "Game", "chat", "Chat", "media", "Media",
            "aux", "Aux", "mic", "Microphone", "master", "Master");
    private static final Set<String> CHAT = Set.of("discord", "discordptb", "discordcanary", "teamspeak", "ts3client_win64",
            "teamspeak3", "mumble", "skype", "zoom", "teams", "ms-teams", "slack", "element", "signal", "whatsapp", "telegram", "guilded");
    private static final Set<String> MEDIA = Set.of("spotify", "chrome", "firefox", "msedge", "brave", "opera", "vivaldi", "vlc",
            "mpv", "itunes", "applemusic", "music.ui", "foobar2000", "musicbee", "winamp", "aimp", "tidal", "deezer", "potplayer", "wmplayer");

    private static final Set<String> HIDDEN = Set.of("audiodg");
    private static final long SELF_PID = ProcessHandle.current().pid();

    /**
     * Sessions that aren't apps you'd want to mix: Windows' audio engine, Zyclipa itself (its soundboard and
     * test sounds have their own volumes), and other audio routers such as SteelSeries Sonar or Voicemeeter.
     * A router replays every app's sound, so turning it down here would change all channels at once.
     */
    static boolean ignored(AudioPlatform.Session s) {
        String p = norm(s.process()), d = norm(s.displayName());
        return HIDDEN.contains(p) || s.pid() == SELF_PID || p.startsWith("zyclipa")
                || (p.startsWith("steelseries") && p.contains("sonar")) || d.startsWith("steelseries sonar")
                || p.startsWith("voicemeeter") || p.startsWith("audiorelay");
    }

    public record App(String process, String label, String channel, double volume, boolean muted, boolean active,
                      List<String> keys, String device) { }

    private final SettingsStore store;
    private final Settings.Mixer cfg;
    private final AudioPlatform os;
    private final MicProcessor mic;
    private final ScheduledExecutorService poll = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mixer-poll");
        t.setDaemon(true);
        return t;
    });
    private volatile List<AudioPlatform.Session> sessions = List.of();
    private final Map<String, Float> applied = new ConcurrentHashMap<>();
    private volatile String outputId = "";
    private volatile Runnable onChange = () -> { };

    public MixerService(SettingsStore store, AudioPlatform os, MicProcessor mic) {
        this.store = store;
        this.cfg = store.get().mixer;
        this.os = os;
        this.mic = mic;
        Settings.Channel mc = cfg.channels.get("mic");
        mic.setChannel((float) mc.volume, mc.muted);
        poll.scheduleWithFixedDelay(this::refresh, 500, 1500, TimeUnit.MILLISECONDS);
    }

    public boolean available() { return os.available(); }
    public AudioPlatform platform() { return os; }
    public void onChange(Runnable r) { onChange = r; }

    /** Re-reads sessions and pushes our volumes onto new ones. Also detects device switches. */
    public void refresh() {
        try {
            if (!os.available()) return;
            String id = os.defaultOutputId();
            if (!id.equals(outputId)) {
                if (!outputId.isEmpty()) LOG.info("Output device changed - re-applying mixer");
                outputId = id;
                applied.clear();
            }
            sessions = os.sessions();
            for (AudioPlatform.Session s : sessions) if (!ignored(s)) apply(s, false);
            updateEngines();
            onChange.run();
        } catch (RuntimeException e) {
            LOG.warning("Mixer refresh: " + e);
        }
    }

    static String norm(String p) { return p == null ? "" : p.toLowerCase(Locale.ROOT); }

    public String channelOf(String process) {
        String p = norm(process);
        String c = cfg.appChannels.get(p);
        if (c != null) return c;
        if (CHAT.contains(p)) return "chat";
        if (MEDIA.contains(p)) return "media";
        if (p.equals("system sounds")) return "aux";
        return "game";
    }

    private volatile boolean dryRun;

    /** When on, nothing is written to Windows (used by unattended checks so they never touch your volumes). */
    public void setDryRun(boolean on) { dryRun = on; }

    private void apply(AudioPlatform.Session s, boolean force) {
        String ch = channelOf(s.process());
        Settings.Channel c = cfg.channels.get(ch);
        String key = norm(s.process());
        Settings.Channel a = cfg.appVolumes.get(key);
        boolean weMuted = cfg.mutedForProcessing.contains(s.key());
        if (a == null) {
            // First time we see this app: keep the volume it already has in Windows instead of forcing 100 %.
            double base = c.volume > 0.001 ? Math.min(1, s.volume() / c.volume) : 1;
            a = new Settings.Channel(base, s.muted() && !weMuted);
            cfg.appVolumes.put(key, a);
            store.save();
        }
        if (dryRun) return;
        float vol = (float) (c.volume * a.volume);
        boolean mute = c.muted || a.muted;
        if (weMuted) { cfg.mutedForProcessing.remove(s.key()); store.save(); } // left over from an older version
        float stamp = mute ? -1 - vol : vol;
        Float prev = applied.get(s.key());
        if (!force && prev != null && prev == stamp) return;
        os.setSession(s.key(), vol, mute);
        applied.put(s.key(), stamp);
    }

    private void applyAll() {
        for (AudioPlatform.Session s : sessions) if (!ignored(s)) apply(s, true);
        updateEngines();
    }

    // ---------------- channel processing ----------------

    private final Map<String, ChannelEngine> engines = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean processedChannel(String ch) {
        Settings.ChannelFx fx = cfg.fx.get(ch);
        return fx != null && fx.active() && !fx.sourceDeviceId.isBlank() && !dryRun && io.zyclipa.core.Platform.isWindows();
    }

    public Settings.ChannelFx fx(String channel) { return cfg.fx.get(channel); }
    public ChannelEngine engine(String channel) { return engines.get(channel); }
    private final Map<String, String> engineSource = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> engineErrors = new java.util.concurrent.ConcurrentHashMap<>();

    /** Why a channel's effects aren't running ("" when fine or not wanted). */
    public String engineError(String channel) { return engineErrors.getOrDefault(channel, ""); }

    /** Call after changing a channel's effects (turns processing on/off and refreshes the EQ). */
    public void fxChanged(String channel) {
        store.save();
        ChannelEngine e = engines.get(channel);
        if (e != null) e.eqChanged();
        poll.execute(this::updateEngines);
    }

    /** Starts, restarts or stops each channel's effects engine to match the settings. */
    private synchronized void updateEngines() {
        for (String ch : List.of("game", "chat", "media", "aux")) {
            boolean on = processedChannel(ch);
            Settings.ChannelFx fx = cfg.fx.get(ch);
            ChannelEngine e = engines.get(ch);
            boolean sourceChanged = e != null && !fx.sourceDeviceId.equals(engineSource.get(ch));
            if ((!on || sourceChanged || (e != null && !e.running())) && e != null) { engines.remove(ch).close(); e = null; }
            if (!on) { engineErrors.remove(ch); continue; }
            if (e == null) {
                if (VirtualCables.loops(fx.sourceDeviceName, fx.outputDevice) || fx.sourceDeviceName.equalsIgnoreCase(fx.outputDevice)) {
                    engineErrors.put(ch, "The source and the output are the same device – that would feed back.");
                    continue;
                }
                try {
                    engines.put(ch, new ChannelEngine(ch, fx, () -> 1.0)); // apps' own volumes already include the channel volume
                    engineSource.put(ch, fx.sourceDeviceId);
                    engineErrors.remove(ch);
                } catch (RuntimeException ex) {
                    engineErrors.put(ch, "Couldn't capture “" + fx.sourceDeviceName + "”: " + ex.getMessage());
                }
            }
        }
    }

    /** Peak level of a processed channel's output (0 when not processed). */
    public float processedPeak(String channel) {
        ChannelEngine e = engines.get(channel);
        return e == null ? 0 : e.peak();
    }

    public Settings.Channel channel(String id) { return cfg.channels.get(id); }

    public void setChannel(String id, double volume, boolean muted) {
        if (!CHANNEL_IDS.contains(id)) throw new IllegalArgumentException("Unknown channel " + id);
        Settings.Channel c = cfg.channels.get(id);
        c.volume = Math.max(0, Math.min(1, volume));
        c.muted = muted;
        store.save();
        if (dryRun) return;
        switch (id) {
            case "master" -> os.setMaster((float) c.volume, c.muted);
            case "mic" -> { os.setMic((float) c.volume, c.muted); mic.setChannel((float) c.volume, c.muted); }
            default -> poll.execute(this::applyAll);
        }
    }

    /** Applies the saved master/mic values to Windows (at start, after loading a profile). */
    public void pushEndpointLevels() {
        if (dryRun) return;
        Settings.Channel m = cfg.channels.get("master"), mc = cfg.channels.get("mic");
        os.setMaster((float) m.volume, m.muted);
        os.setMic((float) mc.volume, mc.muted);
        mic.setChannel((float) mc.volume, mc.muted);
    }

    /** Reads current master/mic levels from Windows into our channels (so we start where the user was). */
    public void adoptEndpointLevels() {
        if (!os.available()) return;
        Settings.Channel m = cfg.channels.get("master"), mc = cfg.channels.get("mic");
        m.volume = os.masterVolume(); m.muted = os.masterMuted();
        mc.volume = os.micVolume(); mc.muted = os.micMuted();
        mic.setChannel((float) mc.volume, mc.muted);
    }

    public List<App> apps() {
        Map<String, List<AudioPlatform.Session>> by = new LinkedHashMap<>();
        for (AudioPlatform.Session s : sessions) {
            if (ignored(s)) continue;
            by.computeIfAbsent(norm(s.process()), k -> new ArrayList<>()).add(s);
        }
        List<App> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            AudioPlatform.Session first = e.getValue().get(0);
            Settings.Channel a = cfg.appVolumes.getOrDefault(e.getKey(), new Settings.Channel());
            boolean active = e.getValue().stream().anyMatch(AudioPlatform.Session::active);
            String label = first.displayName() != null && !first.displayName().isBlank() && !first.displayName().startsWith("@")
                    ? first.displayName() : pretty(first.process());
            out.add(new App(e.getKey(), label, channelOf(e.getKey()), a.volume, a.muted, active,
                    e.getValue().stream().map(AudioPlatform.Session::key).toList(), first.endpointName()));
        }
        out.sort((x, y) -> x.active() != y.active() ? (x.active() ? -1 : 1) : x.label().compareToIgnoreCase(y.label()));
        return out;
    }

    static String pretty(String p) {
        if (p == null || p.isBlank()) return "App";
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    public void setAppChannel(String process, String channel) {
        if (!List.of("game", "chat", "media", "aux").contains(channel)) throw new IllegalArgumentException(channel);
        cfg.appChannels.put(norm(process), channel);
        store.save();
        poll.execute(this::applyAll);
    }

    public void setApp(String process, double volume, boolean muted) {
        Settings.Channel a = cfg.appVolumes.computeIfAbsent(norm(process), k -> new Settings.Channel());
        a.volume = Math.max(0, Math.min(1, volume));
        a.muted = muted;
        store.save();
        poll.execute(this::applyAll);
    }

    /** Live peak level 0..1 per channel. Call from a UI timer only while the levels are visible. */
    public Map<String, Float> levels() {
        Map<String, Float> lv = new LinkedHashMap<>();
        for (String c : CHANNEL_IDS) lv.put(c, 0f);
        for (AudioPlatform.Session s : sessions) {
            if (ignored(s)) continue; // a router would light up a channel with everyone's sound
            String ch = channelOf(s.process());
            float p = os.sessionPeak(s.key());
            lv.merge(ch, p, Math::max);
        }
        lv.put("master", os.masterPeak());
        lv.put("mic", mic.running() ? mic.outputPeak() : os.micPeak());
        return lv;
    }

    public float appPeak(App a) {
        float p = 0;
        for (String k : a.keys()) p = Math.max(p, os.sessionPeak(k));
        return p;
    }

    // ----- profiles -----

    public List<String> profiles() {
        List<String> l = new ArrayList<>(cfg.profiles.keySet());
        if (!l.contains("Default")) l.add(0, "Default");
        return l;
    }

    public String activeProfile() { return cfg.activeProfile; }

    public void saveProfile(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 40) throw new IllegalArgumentException("Profile names need 1-40 characters.");
        Map<String, Settings.Channel> snap = new LinkedHashMap<>();
        for (var e : cfg.channels.entrySet()) snap.put(e.getKey(), new Settings.Channel(e.getValue().volume, e.getValue().muted));
        cfg.profiles.put(n, snap);
        cfg.activeProfile = n;
        store.save();
    }

    public void loadProfile(String name) {
        Map<String, Settings.Channel> p = cfg.profiles.get(name);
        if (p == null && !"Default".equals(name)) throw new IllegalArgumentException("No profile called " + name);
        for (String c : CHANNEL_IDS) {
            Settings.Channel src = p == null ? new Settings.Channel() : p.getOrDefault(c, new Settings.Channel());
            Settings.Channel dst = cfg.channels.get(c);
            dst.volume = src.volume;
            dst.muted = src.muted;
        }
        cfg.activeProfile = name;
        store.save();
        pushEndpointLevels();
        poll.execute(this::applyAll);
    }

    public void deleteProfile(String name) {
        cfg.profiles.remove(name);
        if (name.equals(cfg.activeProfile)) cfg.activeProfile = "Default";
        store.save();
    }

    @Override public void close() {
        poll.shutdownNow();
        for (ChannelEngine e : engines.values()) e.close();
        engines.clear();
        // Give the apps their own sound back before Zyclipa exits.
        if (!dryRun) {
            for (AudioPlatform.Session s : sessions) {
                if (cfg.mutedForProcessing.contains(s.key())) {
                    Settings.Channel c = cfg.channels.get(channelOf(s.process()));
                    Settings.Channel a = cfg.appVolumes.getOrDefault(norm(s.process()), new Settings.Channel());
                    os.setSession(s.key(), (float) (c.volume * a.volume), c.muted || a.muted);
                }
            }
            cfg.mutedForProcessing.clear();
            store.save();
        }
        os.close();
    }
}
