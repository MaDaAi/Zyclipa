package io.zyclipra.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What the dashboard may do. Implemented by the desktop app; the server knows nothing else about it.
 * Actions that can fail return a result object, or throw {@link IllegalStateException} with a message
 * meant for the user (the dashboard shows it as is) – never a made-up success.
 */
public interface RemoteControl {
    JsonElement status();
    JsonElement clips(int limit);
    JsonElement levels();
    JsonElement mixer();
    JsonElement sounds();

    /** Starts or stops a recording on this PC; waits until it really started or stopped. */
    JsonElement toggleRecording();
    JsonElement toggleReplay();
    /** Saves the instant replay; completes with the new clip ({"clip": {...}}). */
    JsonElement saveReplay();
    JsonElement screenshot();

    void setChannel(String id, double volume, boolean muted);
    void playSound(String id);
    void stopSounds();

    /** The media file of a clip in the library, looked up by its id (never by a path from the browser). */
    default Optional<Path> clipFile(String id) { return Optional.empty(); }

    /** A clip's thumbnail picture, if one has been made. */
    default Optional<Path> clipThumbnail(String id) { return Optional.empty(); }

    // ---------------- Google Drive (all optional) ----------------

    /** Connection, account name/email, preferences, queue and counts. Never contains tokens. */
    default JsonElement cloudStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("available", false);
        return o;
    }

    /** Changes the sync preferences the dashboard may change (automatic upload and its kinds). */
    default JsonElement cloudSettings(JsonObject changes) { throw new IllegalStateException("Google Drive isn't available here."); }
    default JsonElement cloudUpload(String clipId) { throw new IllegalStateException("Google Drive isn't available here."); }
    /** Retries one failed upload, or every failed upload when {@code clipId} is null. */
    default JsonElement cloudRetry(String clipId) { throw new IllegalStateException("Google Drive isn't available here."); }
    default JsonElement cloudCancel(String clipId) { throw new IllegalStateException("Google Drive isn't available here."); }
    /** The Google profile picture, cached on this PC. */
    default Optional<Path> cloudAvatar() { return Optional.empty(); }
}
