package io.zyclipa.audio;

import io.zyclipa.core.Platform;

import java.util.List;

/**
 * Operating-system audio control: endpoints, per-application sessions and their meters.
 * Windows uses WASAPI (Core Audio); other systems get a no-op until implemented.
 */
public interface AudioPlatform extends AutoCloseable {
    record Endpoint(String id, String name, boolean output, boolean isDefault) { }

    record Session(String key, int pid, String process, String displayName, boolean systemSounds,
                   boolean active, float volume, boolean muted, String endpointName) { }

    boolean available();

    List<Endpoint> endpoints();

    /** Current sessions on all active output devices. */
    List<Session> sessions();

    /** Sets volume (0..1) and mute of every session whose key is listed. */
    void setSession(String key, float volume, boolean muted);

    /** Peak level 0..1 of a session right now. */
    float sessionPeak(String key);

    float masterVolume();
    boolean masterMuted();
    void setMaster(float volume, boolean muted);
    float masterPeak();

    float micVolume();
    boolean micMuted();
    void setMic(float volume, boolean muted);
    float micPeak();

    /** Id of the default output, used to notice device changes/disconnects. */
    String defaultOutputId();

    @Override void close();

    static AudioPlatform create() {
        if (Platform.isWindows()) {
            try { return new WindowsCoreAudio(); } catch (Throwable t) {
                io.zyclipa.core.Log.get(AudioPlatform.class).warning("Core Audio unavailable: " + t);
            }
        }
        return new NullAudioPlatform();
    }
}
