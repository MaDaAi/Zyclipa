package io.zyclipa.remote;

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

    /** Starts a recording if none is running (idempotent); waits until it really started. */
    default JsonElement startRecording() { throw new IllegalStateException("Not available here."); }
    /** Stops the recording if one is running (idempotent); waits until the file is saved. */
    default JsonElement stopRecording() { throw new IllegalStateException("Not available here."); }
    /** Turns instant replay on or off (idempotent). */
    default JsonElement setReplay(boolean on) { throw new IllegalStateException("Not available here."); }
    /** Sets how many seconds instant replay keeps (15–300); restarts the buffer if it's running. */
    default JsonElement setReplaySeconds(int seconds) { throw new IllegalStateException("Not available here."); }

    // ---------------- soundboard (the PC plays; dashboards only ask) ----------------

    /** Plays (or restarts) a sound on the PC's soundboard output. */
    default JsonElement soundPlay(String id) { throw new IllegalStateException("Not available here."); }
    default JsonElement soundStop(String id) { throw new IllegalStateException("Not available here."); }
    /** One sound's gain, 0–3 (0–300 %). */
    default JsonElement soundVolume(String id, double gain) { throw new IllegalStateException("Not available here."); }
    /** The master soundboard gain, 0–3. */
    default JsonElement soundMaster(double gain) { throw new IllegalStateException("Not available here."); }
    /** The "never louder than 100 %" safety setting. */
    default JsonElement soundLimit(boolean on) { throw new IllegalStateException("Not available here."); }
    /** Button colour (#RRGGBB or "") and/or icon (emoji or up to 2 letters); null leaves it as it is. */
    default JsonElement soundLook(String id, String color, String icon) { throw new IllegalStateException("Not available here."); }
    default JsonElement soundMove(String id, int index) { throw new IllegalStateException("Not available here."); }
    default JsonElement soundPage(String id, String page) { throw new IllegalStateException("Not available here."); }
    /** Where the PC plays sounds ("" = Windows default); must be one of the listed outputs. */
    default JsonElement soundOutput(String device) { throw new IllegalStateException("Not available here."); }
    /** The PC's playback devices, for the output picker. */
    default JsonElement soundOutputs() { return new com.google.gson.JsonArray(); }

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
    /** Opens Google's sign-in in the browser on the PC (the result comes back to the PC, not the phone). */
    default JsonElement cloudConnect() { throw new IllegalStateException("Google Drive isn't available here."); }
    /** Signs out of Google Drive. Nothing is deleted on Drive or the PC. */
    default JsonElement cloudDisconnect() { throw new IllegalStateException("Google Drive isn't available here."); }
    /** The Google profile picture, cached on this PC. */
    default Optional<Path> cloudAvatar() { return Optional.empty(); }
}
