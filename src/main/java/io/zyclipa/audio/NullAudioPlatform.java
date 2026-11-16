package io.zyclipa.audio;

import java.util.List;

/** Used where no OS audio control exists yet (and when Core Audio fails to start). */
final class NullAudioPlatform implements AudioPlatform {
    public boolean available() { return false; }
    public List<Endpoint> endpoints() { return List.of(); }
    public List<Session> sessions() { return List.of(); }
    public void setSession(String key, float volume, boolean muted) { }
    public float sessionPeak(String key) { return 0; }
    public float masterVolume() { return 1; }
    public boolean masterMuted() { return false; }
    public void setMaster(float volume, boolean muted) { }
    public float masterPeak() { return 0; }
    public float micVolume() { return 1; }
    public boolean micMuted() { return false; }
    public void setMic(float volume, boolean muted) { }
    public float micPeak() { return 0; }
    public String defaultOutputId() { return ""; }
    public void close() { }
}
