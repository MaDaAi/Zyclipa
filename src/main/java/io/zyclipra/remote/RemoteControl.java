package io.zyclipra.remote;

import com.google.gson.JsonElement;

/** What the dashboard may do. Implemented by the app; the server knows nothing else about it. */
public interface RemoteControl {
    JsonElement status();
    JsonElement clips(int limit);
    JsonElement levels();
    JsonElement mixer();
    JsonElement sounds();
    void toggleRecording();
    void toggleReplay();
    void saveReplay();
    void screenshot();
    void setChannel(String id, double volume, boolean muted);
    void playSound(String id);
    void stopSounds();

    /** The media file of a clip in the library, looked up by its id (never by a path from the browser). */
    default java.util.Optional<java.nio.file.Path> clipFile(String id) { return java.util.Optional.empty(); }

    /** A clip's thumbnail picture, if one has been made. */
    default java.util.Optional<java.nio.file.Path> clipThumbnail(String id) { return java.util.Optional.empty(); }
}
