package io.zyclipra.audio;

/**
 * Sending an app to a particular output device is done in Windows itself
 * (Settings › System › Sound › Volume mixer, per app "Output device"). Zyclipra opens that page;
 * it does not change per-app routes on its own (that interface isn't public, and in testing it
 * didn't reliably move sound).
 */
public final class AppRouting {
    private AppRouting() {}

    public static void openWindowsAppVolumeSettings() {
        try { new ProcessBuilder("explorer.exe", "ms-settings:apps-volume").start(); }
        catch (Exception ignored) { }
    }
}
