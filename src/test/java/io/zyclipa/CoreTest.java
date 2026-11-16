package io.zyclipa;

import io.zyclipa.cloud.Pkce;
import io.zyclipa.core.Json;
import io.zyclipa.core.Settings;
import io.zyclipa.core.SettingsStore;
import io.zyclipa.hotkeys.Hotkey;
import io.zyclipa.platform.Desktop;
import io.zyclipa.recording.CaptureArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @TempDir Path tmp;

    @Test void sanitizeRepairsBadValues() {
        Settings s = new Settings();
        s.general.accent = "red";
        s.recording.fps = 9999;
        s.recording.replaySeconds = 2;
        s.recording.container = "avi";
        s.equalizer.bands = null;
        s.mixer.channels.clear();
        s.soundboard.sounds = null;
        s.sanitize();
        assertEquals("#5B6CF9", s.general.accent);
        assertEquals(144, s.recording.fps);
        assertEquals(15, s.recording.replaySeconds);
        assertEquals("mp4", s.recording.container);
        assertEquals(9, s.equalizer.bands.size());
        assertEquals(6, s.mixer.channels.size());
        assertNotNull(s.soundboard.sounds);
        assertEquals("Alt+F9", s.hotkeys.get("toggleRecording"));
    }

    @Test void replayLengthClampedToFiveMinutes() {
        Settings s = new Settings();
        s.recording.replaySeconds = 999;
        assertEquals(300, s.sanitize().recording.replaySeconds);
    }

    @Test void damagedSettingsFileIsKeptAndDefaultsUsed() throws Exception {
        Path f = tmp.resolve("settings.json");
        Files.writeString(f, "{ this is not json");
        SettingsStore st = new SettingsStore(f);
        assertNotNull(st.get());
        assertNotNull(st.lastError());
        assertTrue(Files.exists(tmp.resolve("settings.json.broken")));
        st.get().recording.fps = 30;
        st.saveNow();
        assertEquals(30, Json.read(f, Settings.class).recording.fps);
        st.close();
    }

    @Test void hotkeysParse() {
        Hotkey h = Hotkey.parse("Ctrl+Alt+F9").orElseThrow();
        assertEquals(Hotkey.CTRL | Hotkey.ALT, h.modifiers());
        assertEquals(0x78, h.vk());
        assertEquals(0x41, Hotkey.parse("alt+a").orElseThrow().vk());
        assertEquals(0x63, Hotkey.parse("Shift+NumPad3").orElseThrow().vk());
        assertEquals(0x23, Hotkey.parse("Ctrl+Alt+End").orElseThrow().vk());
        assertEquals(0, Hotkey.parse("Delete").orElseThrow().modifiers(), "single keys are allowed (watched, never blocked)");
        assertEquals(0x27, Hotkey.parse("Right").orElseThrow().vk());
        assertTrue(Hotkey.parse("F13").isPresent());
        assertTrue(Hotkey.parse("Ctrl+").isEmpty());
        assertTrue(Hotkey.parse("Ctrl+Nope").isEmpty());
        assertTrue(Hotkey.parse("Ctrl+A+B").isEmpty());
        assertEquals("Ctrl+Alt+F9", h.text());
    }

    @Test void captureArgsForDisplayAndAudioTracks() {
        Settings s = new Settings().sanitize();
        s.recording.audioInputs = List.of("Mic A", "Cable B");
        s.recording.separateTracks = true;
        Desktop.Display d = new Desktop.Display(1, 1920, 0, 2560, 1440, false);
        List<String> in = CaptureArgs.inputs(s.recording, d);
        if (io.zyclipa.core.Platform.isWindows()) {
            assertTrue(in.contains("gdigrab"));
            assertTrue(in.contains("1920"));
            assertTrue(in.contains("2560x1440"));
            assertTrue(in.contains("audio=Mic A"));
        }
        List<String> map = CaptureArgs.mapping(s.recording);
        assertEquals(List.of("-map", "0:v", "-map", "1:a", "-map", "2:a", "-metadata:s:a:0", "title=Mic A", "-metadata:s:a:1", "title=Cable B",
                "-c:a", "aac", "-b:a", "160k", "-ar", "48000"), map);
        s.recording.separateTracks = false;
        assertTrue(String.join(" ", CaptureArgs.mapping(s.recording)).contains("amix=inputs=2"));
        List<String> enc = CaptureArgs.encode(s.recording, "h264_nvenc");
        assertTrue(enc.contains("h264_nvenc"));
        assertTrue(enc.contains("20000k"));
        assertEquals(String.valueOf(s.recording.fps * 2), enc.get(enc.indexOf("-g") + 1));
        s.recording.outputHeight = 720;
        assertTrue(CaptureArgs.encode(s.recording, "libx264").get(1).startsWith("scale=-2:720"));
    }

    @Test void windowCaptureUsesTitle() {
        Settings s = new Settings().sanitize();
        s.recording.captureMode = "window";
        s.recording.windowTitle = "My Game";
        List<String> in = CaptureArgs.inputs(s.recording, new Desktop.Display(0, 0, 0, 1920, 1080, true));
        if (io.zyclipa.core.Platform.isWindows()) assertTrue(in.contains("title=My Game"));
    }

    @Test void pkceMatchesRfc7636Example() {
        // Appendix B of RFC 7636
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        Pkce p = new Pkce();
        assertTrue(p.verifier.length() >= 43 && p.verifier.length() <= 128);
        assertNotEquals(p.verifier, new Pkce().verifier);
    }
}
