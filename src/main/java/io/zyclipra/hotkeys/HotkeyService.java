package io.zyclipra.hotkeys;

import io.zyclipra.core.Platform;

/** System-wide shortcuts that work while a game has focus. */
public interface HotkeyService extends AutoCloseable {
    /** Registers or replaces the shortcut for {@code action}. Returns an error message, or null on success. */
    String bind(String action, String shortcut, Runnable handler);

    void unbind(String action);

    boolean supported();

    @Override void close();

    static HotkeyService create() {
        if (Platform.isWindows()) {
            try { return new WindowsHotkeys(); } catch (Throwable ignored) { }
        }
        return new HotkeyService() {
            public String bind(String a, String s, Runnable h) { return "Global shortcuts are not available on this system yet."; }
            public void unbind(String a) { }
            public boolean supported() { return false; }
            public void close() { }
        };
    }
}
