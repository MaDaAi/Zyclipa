package io.zyclipa.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Reading the Flatpak portal's progress signals and turning errors into helpful messages (like MultiMini). */
class FlatpakUpdaterTest {
    static final String PROGRESS = "/org/freedesktop/portal/Flatpak/update_monitor/1_42/x: org.freedesktop.portal.Flatpak.UpdateMonitor.Progress "
            + "({'n_ops': <uint32 2>, 'op': <uint32 1>, 'progress': <uint32 57>, 'status': <uint32 0>},)";
    static final String DONE = "/org/x: org.freedesktop.portal.Flatpak.UpdateMonitor.Progress ({'status': <uint32 2>, 'progress': <uint32 100>},)";
    static final String FAIL = "/org/x: org.freedesktop.portal.Flatpak.UpdateMonitor.Progress ({'status': <uint32 3>, "
            + "'error': <'org.freedesktop.Flatpak.Error.RemoteNotFound'>, 'error_message': <'No remote origin for this app'>},)";

    @Test void readsProgressSignals() {
        StringBuilder err = new StringBuilder();
        assertArrayEquals(new int[]{FlatpakUpdater.RUNNING, 57}, FlatpakUpdater.parseProgress(PROGRESS, err));
        assertArrayEquals(new int[]{FlatpakUpdater.DONE, 100}, FlatpakUpdater.parseProgress(DONE, err));
        assertNull(FlatpakUpdater.parseProgress("/org/x: org.freedesktop.DBus.Properties.PropertiesChanged ()", err));
        int[] f = FlatpakUpdater.parseProgress(FAIL, err);
        assertEquals(FlatpakUpdater.FAILED, f[0]);
        assertEquals("No remote origin for this app", err.toString());
    }

    @Test void explainsFailures() {
        assertTrue(FlatpakUpdater.describeFailure("No remote origin for this app").contains("flatpak install --user --reinstall"),
                "file installs without an update source get the one-time install command");
        assertTrue(FlatpakUpdater.describeFailure("needs new permission").contains("flatpak update io.github.MaDaAi.Zyclipa"));
        assertEquals("disk full", FlatpakUpdater.describeFailure("disk full"));
        assertFalse(FlatpakUpdater.describeFailure("").isBlank());
    }
}
