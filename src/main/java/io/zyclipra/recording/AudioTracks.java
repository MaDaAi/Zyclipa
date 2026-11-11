package io.zyclipra.recording;

import io.zyclipra.audio.AudioFeed;
import io.zyclipra.audio.WasapiCapture;
import io.zyclipra.core.Log;
import io.zyclipra.core.Platform;
import io.zyclipra.core.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The sound tracks that go into recordings and replays, each captured natively and kept separate
 * so the clip editor can adjust or remove any of them:
 * <ol>
 * <li><b>Game audio</b> – everything playing on the PC except Zyclipra itself (so soundboard
 *     sounds and your own monitoring don't leak in). Falls back to the default output device on
 *     Windows versions without per-process capture.</li>
 * <li><b>Chat audio</b> – the default communications output, only when it is a different device
 *     from the default output (e.g. voice chat routed to its own headset or virtual device).</li>
 * <li><b>Microphone</b> – your default (or chosen) microphone.</li>
 * <li><b>Soundboard</b> – exactly what the soundboard played, so a clip can be saved without it.</li>
 * </ol>
 * Captures run only while something is recording (reference counted).
 */
public final class AudioTracks {
    private static final Logger LOG = Log.get(AudioTracks.class);
    public static final String GAME = "Game audio", CHAT = "Chat audio", MIC = "Microphone", SOUNDBOARD = "Soundboard";

    public record Track(String title, String url) { }

    private final Settings settings;
    private int users;
    private final List<AudioFeed> feeds = new ArrayList<>();
    private final List<WasapiCapture> captures = new ArrayList<>();
    private volatile AudioFeed soundboard;
    private volatile String status = "";

    public AudioTracks(Settings settings) { this.settings = settings; }

    /** Starts the captures (if not already running) and returns the tracks to record. */
    public synchronized List<Track> acquire() {
        if (users++ == 0) startAll();
        List<Track> t = new ArrayList<>();
        for (AudioFeed f : feeds) t.add(new Track(f.title(), f.url()));
        return t;
    }

    public synchronized void release() {
        if (users > 0 && --users == 0) stopAll();
    }

    /** What is being captured right now, for the UI. */
    public String status() { return status; }

    /** The soundboard track while recording, else null. */
    public AudioFeed soundboardFeed() { return soundboard; }

    private void startAll() {
        if (!Platform.isWindows()) return;
        Settings.Recording r = settings.recording;
        List<String> problems = new ArrayList<>();
        if (r.recordGameAudio) {
            if (!tryCapture(GAME, WasapiCapture.Kind.EXCLUDE_PROCESS_LOOPBACK, null, 0, ProcessHandle.current().pid(), problems)) {
                if (tryCapture(GAME, WasapiCapture.Kind.OUTPUT_LOOPBACK, null, 0, 0, problems)) problems.remove(problems.size() - 1);
            }
        }
        if (r.recordChatAudio) {
            String console = WasapiCapture.defaultId(0, 0), comms = WasapiCapture.defaultId(0, 2);
            if (!comms.isBlank() && !comms.equals(console)) tryCapture(CHAT, WasapiCapture.Kind.OUTPUT_LOOPBACK, comms, 2, 0, problems);
        }
        if (r.recordMic) tryCapture(MIC, WasapiCapture.Kind.MICROPHONE, null, 0, 0, problems);
        if (r.recordSoundboard) {
            try { soundboard = new AudioFeed(SOUNDBOARD); feeds.add(soundboard); }
            catch (Exception e) { problems.add("soundboard: " + e.getMessage()); }
        }
        status = feeds.stream().map(AudioFeed::title).reduce((a, b) -> a + ", " + b).orElse("no sound")
                + (problems.isEmpty() ? "" : " (couldn't capture: " + String.join("; ", problems) + ")");
        LOG.info("Recording tracks: " + status);
    }

    private boolean tryCapture(String title, WasapiCapture.Kind kind, String deviceId, int role, long pid, List<String> problems) {
        AudioFeed feed = null;
        try {
            feed = new AudioFeed(title);
            AudioFeed f = feed;
            WasapiCapture c = WasapiCapture.start(kind, deviceId, role, pid, (buf, len) -> f.push(kind, buf, len));
            captures.add(c);
            feeds.add(feed);
            return true;
        } catch (Exception e) {
            if (feed != null) feed.close();
            problems.add(title + " – " + e.getMessage());
            LOG.warning(title + " capture (" + kind + ") failed: " + e.getMessage());
            return false;
        }
    }

    private void stopAll() {
        for (WasapiCapture c : captures) c.close();
        captures.clear();
        for (AudioFeed f : feeds) f.close();
        feeds.clear();
        soundboard = null;
        status = "";
    }
}
