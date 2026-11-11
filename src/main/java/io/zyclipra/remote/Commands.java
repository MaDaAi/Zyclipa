package io.zyclipra.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import io.zyclipra.core.Settings;

import java.util.regex.Pattern;

/**
 * The remote commands a dashboard may send, in one place so the local dashboard and the cloud relay
 * behave identically. Each command:
 * <ul>
 *   <li>runs on this PC through {@link RemoteControl} and returns what really happened (never an early "OK");</li>
 *   <li>may carry a command id – a repeat of the same id (a retried request, a double tap, a replayed message)
 *       gets the first result back instead of running again;</li>
 *   <li>sound triggers are rate limited per caller so a stuck button or a replay can't spam the PC.</li>
 * </ul>
 */
public final class Commands {
    public record Reply(int status, JsonElement body) { }

    private static final Pattern UNSAFE_TEXT = Settings.UNSAFE_TEXT;
    private static final Pattern COMMAND_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final long KEEP_MS = 10 * 60_000L;
    static final int SOUND_BURST = 10;
    static final long SOUND_WINDOW_MS = 5_000;

    private final RemoteControl control;
    private final Map<String, Long> seenAt = new LinkedHashMap<>();
    private final Map<String, Reply> results = new LinkedHashMap<>();
    private final Map<String, Deque<Long>> soundTimes = new LinkedHashMap<>();
    private final java.util.function.LongSupplier clock;

    public Commands(RemoteControl control) { this(control, System::currentTimeMillis); }

    Commands(RemoteControl control, java.util.function.LongSupplier clock) {
        this.control = control;
        this.clock = clock;
    }

    /** Whether {@code action} is a remote command (as opposed to e.g. an account change). */
    public static boolean isCommand(String action) {
        return switch (action) {
            case "recording/toggle", "recording/start", "recording/stop", "replay/toggle", "replay/on", "replay/off", "replay/save",
                 "replay/length", "screenshot", "sounds/play", "sounds/stop", "sounds/stopAll", "sounds/volume", "sounds/master",
                 "sounds/limit", "sounds/look", "sounds/move", "sounds/page", "sounds/output", "mixer", "cloud/settings", "cloud/upload", "cloud/retry",
                 "cloud/cancel", "cloud/connect", "cloud/disconnect" -> true;
            default -> false;
        };
    }

    /**
     * Runs a command for {@code caller} (a session or paired device). {@code commandId} may be null; when
     * given, a repeat within 10 minutes returns the stored result without running anything.
     */
    public Reply run(String caller, String commandId, String action, JsonObject args) {
        String key = null;
        if (commandId != null) {
            if (!COMMAND_ID.matcher(commandId).matches()) return error(400, "bad command id");
            key = caller + "|" + commandId;
            synchronized (this) {
                prune();
                Reply done = results.get(key);
                if (done != null) return done;          // duplicate: same answer, nothing runs twice
                if (seenAt.containsKey(key)) return error(409, "That command is still running.");
                seenAt.put(key, clock.getAsLong());
            }
        }
        Reply r;
        try {
            if (action.equals("sounds/play") && !allowSound(caller))
                r = error(429, "Slow down – too many sounds in a few seconds.");
            else r = new Reply(200, execute(action, args));
        } catch (SecurityException e) {
            r = error(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            r = error(400, e.getMessage());
        } catch (IllegalStateException e) {
            r = error(409, e.getMessage());
        } catch (RuntimeException e) {
            r = error(500, "Something went wrong in Zyclipra. Check its log for details.");
        }
        if (key != null) synchronized (this) { results.put(key, r); }
        return r;
    }

    private JsonElement execute(String action, JsonObject a) {
        return switch (action) {
            case "recording/toggle" -> control.toggleRecording();
            case "recording/start" -> control.startRecording();
            case "recording/stop" -> control.stopRecording();
            case "replay/toggle" -> control.toggleReplay();
            case "replay/on" -> control.setReplay(true);
            case "replay/off" -> control.setReplay(false);
            case "replay/save" -> control.saveReplay();
            case "replay/length" -> control.setReplaySeconds(integer(a, "seconds", 15, 300));
            case "screenshot" -> control.screenshot();
            case "sounds/play" -> control.soundPlay(id(a));
            case "sounds/stop" -> control.soundStop(id(a));
            case "sounds/stopAll" -> { control.stopSounds(); yield ok("All sounds stopped."); }
            case "sounds/volume" -> control.soundVolume(id(a), gain(a));
            case "sounds/master" -> control.soundMaster(gain(a));
            case "sounds/limit" -> control.soundLimit(bool(a, "on"));
            case "sounds/look" -> control.soundLook(id(a),
                    a.has("color") ? text(a, "color", 7) : null, a.has("icon") ? text(a, "icon", 8) : null);
            case "sounds/move" -> control.soundMove(id(a), integer(a, "index", 0, 10_000));
            case "sounds/page" -> control.soundPage(id(a), text(a, "page", 30));
            case "sounds/output" -> control.soundOutput(text(a, "device", 200));
            case "mixer" -> {
                double v = a.has("volume") && a.get("volume").isJsonPrimitive() ? a.get("volume").getAsDouble() : -1;
                if (!(v >= 0 && v <= 1)) throw new IllegalArgumentException("volume must be 0..1");
                control.setChannel(text(a, "channel", 20), v, a.has("muted") && a.get("muted").getAsBoolean());
                yield ok("Saved.");
            }
            case "cloud/settings" -> control.cloudSettings(a);
            case "cloud/upload" -> control.cloudUpload(clipId(a));
            case "cloud/retry" -> control.cloudRetry(a.has("id") ? clipId(a) : null);
            case "cloud/cancel" -> control.cloudCancel(clipId(a));
            case "cloud/connect" -> control.cloudConnect();
            case "cloud/disconnect" -> control.cloudDisconnect();
            default -> throw new IllegalArgumentException("unknown command");
        };
    }

    private synchronized boolean allowSound(String caller) {
        long now = clock.getAsLong();
        Deque<Long> q = soundTimes.computeIfAbsent(caller, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst() < now - SOUND_WINDOW_MS) q.pollFirst();
        if (q.size() >= SOUND_BURST) return false;
        q.addLast(now);
        return true;
    }

    private void prune() {
        long cutoff = clock.getAsLong() - KEEP_MS;
        seenAt.entrySet().removeIf(e -> { if (e.getValue() < cutoff) { results.remove(e.getKey()); return true; } return false; });
        while (seenAt.size() > 5000) { String k = seenAt.keySet().iterator().next(); seenAt.remove(k); results.remove(k); }
    }

    static JsonObject ok(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", msg);
        return o;
    }

    private static Reply error(int status, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return new Reply(status, o);
    }

    private static String text(JsonObject a, String k, int max) {
        if (!a.has(k) || !a.get(k).isJsonPrimitive()) throw new IllegalArgumentException(k + " is required");
        String s = a.get(k).getAsString();
        if (s.length() > max) throw new IllegalArgumentException(k + " too long");
        // No control characters or text-direction tricks (they can disguise names in lists and logs).
        if (UNSAFE_TEXT.matcher(s).find()) throw new IllegalArgumentException(k + " contains characters that aren't allowed");
        return s;
    }

    private static String id(JsonObject a) {
        String s = text(a, "id", 64);
        if (!s.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("bad sound id");
        return s;
    }

    private static String clipId(JsonObject a) {
        String s = text(a, "id", 80);
        if (!s.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("bad clip id");
        return s;
    }

    private static double gain(JsonObject a) {
        if (!a.has("gain") || !a.get("gain").isJsonPrimitive()) throw new IllegalArgumentException("gain is required");
        double g = a.get("gain").getAsDouble();
        if (!(g >= 0 && g <= 3)) throw new IllegalArgumentException("gain must be between 0 and 3 (0–300 %)");
        return g;
    }

    private static int integer(JsonObject a, String k, int min, int max) {
        if (!a.has(k) || !a.get(k).isJsonPrimitive()) throw new IllegalArgumentException(k + " is required");
        double d = a.get(k).getAsDouble();
        if (d != Math.rint(d) || d < min || d > max) throw new IllegalArgumentException(k + " must be a whole number from " + min + " to " + max);
        return (int) d;
    }

    private static boolean bool(JsonObject a, String k) {
        if (!a.has(k) || !a.get(k).isJsonPrimitive() || !a.get(k).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(k + " must be true or false");
        return a.get(k).getAsBoolean();
    }
}
