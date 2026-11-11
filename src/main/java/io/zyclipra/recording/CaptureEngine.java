package io.zyclipra.recording;

import io.zyclipra.core.Settings;
import io.zyclipra.platform.Desktop;

import java.util.List;
import java.util.Optional;

/** Shared facts both the recorder and the replay buffer need. */
public final class CaptureEngine {
    private final Settings settings;
    private final Desktop desktop;
    private volatile Ffmpeg ffmpeg;
    private volatile List<String> encoders;

    public CaptureEngine(Settings settings, Desktop desktop) {
        this.settings = settings;
        this.desktop = desktop;
        this.tracks = new AudioTracks(settings);
        refresh();
    }

    public void refresh() {
        ffmpeg = Ffmpeg.locate(settings.general.ffmpegPath).orElse(null);
        encoders = null;
    }

    public Optional<Ffmpeg> ffmpeg() { return Optional.ofNullable(ffmpeg); }

    public Ffmpeg require() {
        Ffmpeg f = ffmpeg;
        if (f == null) throw new IllegalStateException("FFmpeg was not found. Set its location in Settings > Recording.");
        return f;
    }

    /** Working encoders, probed once (lazily, off the UI thread). */
    public synchronized List<String> encoders() {
        if (encoders == null) encoders = ffmpeg == null ? List.of() : EncoderProbe.working(ffmpeg);
        return encoders;
    }

    public String encoder() { return EncoderProbe.choose(settings.recording.encoder, encoders()); }

    public Desktop desktop() { return desktop; }

    private final AudioTracks tracks;
    /** Native sound tracks shared by the recorder and the replay buffer. */
    public AudioTracks tracks() { return tracks; }

    public Desktop.Display display() {
        List<Desktop.Display> ds = desktop.displays();
        int i = Math.min(settings.recording.displayIndex, ds.size() - 1);
        return ds.get(Math.max(0, i));
    }

    /** Name for a new clip: the game being played (window or app in front), e.g. "Roblox". */
    public String sourceName() {
        Settings.Recording r = settings.recording;
        if ("window".equals(r.captureMode) && !r.windowTitle.isBlank()) return GameNames.shorten(r.windowTitle);
        return currentGame();
    }

    private volatile String lastGame = "Desktop";
    private volatile long lastGameAt;

    /**
     * What's being played right now. Looks at the window in front; Zyclipra itself, the desktop,
     * browsers and other everyday apps count as "Desktop". Remembers the last game for a minute so
     * clipping right after alt-tabbing to Zyclipra still names the game.
     */
    public String currentGame() {
        Desktop.Window w = desktop.foregroundWindow();
        String g = GameNames.gameOf(w.title(), w.process());
        if (g != null) { lastGame = g; lastGameAt = System.currentTimeMillis(); return g; }
        if (GameNames.isSelf(w.process()) && System.currentTimeMillis() - lastGameAt < 60_000) return lastGame;
        return "Desktop";
    }
}
