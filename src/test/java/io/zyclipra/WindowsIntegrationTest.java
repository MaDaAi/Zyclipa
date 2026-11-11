package io.zyclipra;

import io.zyclipra.audio.AudioPlatform;
import io.zyclipra.hotkeys.HotkeyService;
import io.zyclipra.platform.Desktop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only checks against the real Windows APIs on the machine running the tests. Nothing is changed. */
@EnabledOnOs(OS.WINDOWS)
class WindowsIntegrationTest {

    @Test void coreAudioEnumeratesDevicesAndSessions() {
        try (AudioPlatform a = AudioPlatform.create()) {
            assertTrue(a.available(), "Core Audio should start on Windows");
            List<AudioPlatform.Endpoint> eps = a.endpoints();
            System.out.println("Endpoints: " + eps.size());
            eps.forEach(e -> System.out.println("  " + (e.output() ? "out " : "in  ") + (e.isDefault() ? "* " : "  ") + e.name()));
            List<AudioPlatform.Session> ss = a.sessions();
            System.out.println("Sessions: " + ss.size());
            ss.forEach(s -> System.out.println("  " + s.process() + " vol=" + s.volume() + " muted=" + s.muted() + " on " + s.endpointName()));
            float v = a.masterVolume();
            assertTrue(v >= 0 && v <= 1);
            float p = a.masterPeak();
            assertTrue(p >= 0 && p <= 1);
            for (AudioPlatform.Session s : ss) {
                assertFalse(s.process().isBlank());
                float sp = a.sessionPeak(s.key());
                assertTrue(sp >= 0 && sp <= 1, s.process() + " peak " + sp);
            }
            if (!eps.isEmpty()) assertFalse(a.defaultOutputId().isBlank());
        }
    }

    @Test void hotkeyRegistersAndReportsConflicts() {
        try (HotkeyService h = HotkeyService.create()) {
            assertTrue(h.supported());
            assertNull(h.bind("t1", "Ctrl+Alt+Shift+F23", () -> { }));
            assertNull(h.bind("t2", "Ctrl+Alt+Shift+F23", () -> { }), "a shortcut another program holds still works (watched instead)");
            assertNull(h.bind("t4", "Delete", () -> { }), "single keys work");
            h.unbind("t4");
            assertNotNull(h.bind("t3", "NotAKey", () -> { }));
            h.unbind("t1");
            assertNull(h.bind("t2", "Ctrl+Alt+Shift+F23", () -> { }), "free again after unbind");
            h.unbind("t2");
        }
    }

    @Test void displaysAndWindowsAreListed() {
        Desktop d = Desktop.create();
        List<Desktop.Display> ds = d.displays();
        assertFalse(ds.isEmpty());
        assertTrue(ds.get(0).width() > 0 && ds.get(0).height() > 0);
        assertTrue(ds.get(0).primary());
        System.out.println("Displays: " + ds + "; windows: " + d.windows().size());
    }
}
