package io.zyclipa;

import io.zyclipa.clips.Clip;
import io.zyclipa.clips.ClipLibrary;
import io.zyclipa.core.EventBus;
import io.zyclipa.recording.Ffmpeg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LibraryTest {
    @TempDir Path tmp;

    ClipLibrary lib(Path clips, Optional<Ffmpeg> ff) {
        return new ClipLibrary(() -> clips, tmp.resolve("library.json"), tmp.resolve("thumbs"), () -> ff, new EventBus());
    }

    @Test void scanRenameMoveDelete() throws Exception {
        Path clips = tmp.resolve("clips");
        Files.createDirectories(clips.resolve("Sub"));
        Files.write(clips.resolve("a.mp4"), new byte[100]);
        Files.write(clips.resolve("Sub/b.png"), new byte[50]);
        Files.write(clips.resolve("notes.txt"), new byte[5]);
        ClipLibrary l = lib(clips, Optional.empty());
        l.scan();
        assertEquals(2, l.all().size());
        Clip a = l.all().stream().filter(c -> c.fileName().equals("a.mp4")).findFirst().orElseThrow();
        assertThrows(IOException.class, () -> l.rename(a, "bad/name"));
        assertThrows(IOException.class, () -> l.rename(a, "b:c"));
        l.rename(a, "Great clip");
        assertTrue(Files.exists(clips.resolve("Great clip.mp4")));
        assertEquals("Great clip", a.title);
        l.moveToFolder(a, "Best");
        assertTrue(Files.exists(clips.resolve("Best/Great clip.mp4")));
        assertEquals("Best", a.folder());
        assertThrows(IOException.class, () -> l.moveToFolder(a, "..\\..\\escape"));
        // index survives a restart
        ClipLibrary again = lib(clips, Optional.empty());
        again.scan();
        assertEquals(2, again.all().size());
        Clip moved = again.all().stream().filter(c -> c.title.equals("Great clip")).findFirst().orElseThrow();
        again.delete(moved);
        assertFalse(Files.exists(clips.resolve("Best/Great clip.mp4")));
        assertEquals(1, again.all().size());
    }

    @Test void trimWithRealFfmpeg() throws Exception {
        Optional<Ffmpeg> ff = Ffmpeg.locate("");
        assumeTrue(ff.isPresent(), "FFmpeg not available");
        Path clips = tmp.resolve("clips");
        Files.createDirectories(clips);
        Path src = clips.resolve("test.mp4");
        Ffmpeg.Result r = ff.get().run(List.of("-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440", "-t", "6", "-c:v", "libx264", "-g", "30", "-c:a", "aac", src.toString()), 60);
        assertTrue(r.ok(), r.output());
        ClipLibrary l = lib(clips, ff);
        Clip c = l.add(src, "recording", "Test");
        assertEquals(6, c.durationSec, 0.3);
        assertEquals(320, c.width);
        assertEquals(1, c.audioTracks);
        assertTrue(Files.exists(l.thumbnail(c)), "thumbnail made");
        Clip t = l.trim(c, 1, 4);
        assertEquals(3, t.durationSec, 0.6);
        assertEquals("trim", t.kind);
        assertThrows(IOException.class, () -> l.trim(c, 4, 1));
    }

    @Test void exportsApplyTheSavedEdit() throws Exception {
        Optional<Ffmpeg> ff = Ffmpeg.locate("");
        assumeTrue(ff.isPresent(), "FFmpeg not available");
        Path clips = tmp.resolve("clips3");
        Files.createDirectories(clips);
        Path src = clips.resolve("game.mp4");
        Ffmpeg.Result r = ff.get().run(List.of("-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440", "-f", "lavfi", "-i", "sine=frequency=880", "-t", "8",
                "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "libx264", "-b:v", "6000k", "-g", "30", "-c:a", "aac", src.toString()), 120);
        assertTrue(r.ok(), r.output());
        ClipLibrary l = lib(clips, ff);
        Clip c = l.add(src, "replay", "Test");
        Clip.Edit e = Clip.Edit.none(c);
        e.in = 2;
        e.out = 6;
        e.volumes = new double[]{1.5, 1};
        e.muted = new boolean[]{false, true};
        l.saveEdit(c, e);
        // The edit is remembered in the index without touching the file.
        ClipLibrary again = lib(clips, ff);
        again.scan();
        Clip back = again.all().stream().filter(x -> x.id.equals(c.id)).findFirst().orElseThrow();
        assertNotNull(back.edit);
        assertEquals(2, back.edit.in, 1e-9);
        assertTrue(back.edit.muted[1]);
        assertEquals(0, back.edit.volume(1), "muted track exports silent");

        Path discord = tmp.resolve("out/discord.mp4");
        Files.createDirectories(discord.getParent());
        l.render(c, e, ClipLibrary.Export.DISCORD, discord, "libx264");
        assertTrue(Files.size(discord) <= 10L * 1024 * 1024, "under 10 MB");
        Clip d = l.add(discord, "imported", "Test");
        assertEquals(4, d.durationSec, 0.5, "only the trimmed part");
        assertEquals(1, d.audioTracks, "tracks mixed into one");

        Path half = tmp.resolve("out/half.mp4");
        l.render(c, e, ClipLibrary.Export.Q50, half, "libx264");
        Clip h = l.add(half, "imported", "Test");
        assertTrue(h.width < 1280 && h.width >= 800, "½ quality is smaller: " + h.width);

        Path mp3 = tmp.resolve("out/sound.mp3");
        l.render(c, e, ClipLibrary.Export.AUDIO, mp3, null);
        assertTrue(Files.size(mp3) > 40_000, "about 4 s of 192k MP3");

        Path wav = tmp.resolve("out/sound.wav");
        l.renderAudioWav(c, e, wav);
        long expected = 4L * 48_000 * 4;   // 4 s, stereo 16-bit
        assertEquals(expected, Files.size(wav), expected * 0.1, "4 s WAV for the soundboard");
        assertNull(ClipLibrary.mixFilter(c, mutedAll(c)), "nothing to export when everything is muted");
    }

    static Clip.Edit mutedAll(Clip c) {
        Clip.Edit e = Clip.Edit.none(c);
        e.muted = new boolean[]{true, true};
        return e;
    }

    @Test void editorExportCropsAndDropsTracks() throws Exception {
        Optional<Ffmpeg> ff = Ffmpeg.locate("");
        assumeTrue(ff.isPresent(), "FFmpeg not available");
        Path clips = tmp.resolve("clips2");
        Files.createDirectories(clips);
        Path src = clips.resolve("two tracks.mp4");
        Ffmpeg.Result r = ff.get().run(List.of("-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440", "-f", "lavfi", "-i", "sine=frequency=880", "-t", "5",
                "-map", "0:v", "-map", "1:a", "-map", "2:a", "-metadata:s:a:0", "title=Game audio", "-metadata:s:a:1", "title=Microphone",
                "-metadata:s:a:0", "handler_name=Game audio", "-metadata:s:a:1", "handler_name=Microphone",
                "-c:v", "libx264", "-g", "30", "-c:a", "aac", src.toString()), 60);
        assertTrue(r.ok(), r.output());
        ClipLibrary l = lib(clips, ff);
        Clip c = l.add(src, "recording", "Test");
        assertEquals(List.of("Game audio", "Microphone"), c.audioTrackNames, "track names are read from the file");
        Clip v = l.exportEdit(c, 1, 3, true, new double[]{0.5, 0}, "libx264");
        assertEquals(2, v.durationSec, 0.4);
        assertEquals(1, v.audioTracks, "the removed track is gone");
        assertEquals(List.of("Game audio"), v.audioTrackNames);
        assertTrue(v.height > v.width, "9:16 output is portrait: " + v.width + "x" + v.height);
        Clip captioned = l.exportEdit(c, 0, 4, false, new double[]{1, 1},
                List.of(new ClipLibrary.TextOverlay("GG: what a shot!", 1, 3, "Bottom", 7)), "libx264");
        assertEquals(4, captioned.durationSec, 0.4);
        assertEquals(2, captioned.audioTracks, "both tracks kept");
        assertTrue(l.filmstrip(c, 8).isPresent(), "picture strip for the editor");
        Double peak = l.trackPeakDb(c, 1);
        assertNotNull(peak);
        assertTrue(peak > -30, "the 880 Hz track has sound: " + peak);
        Path[] p = l.previewFiles(c);
        assertEquals(3, p.length, "preview picture + one mp3 per sound track");
        assertTrue(Files.size(p[0]) > 0 && Files.size(p[1]) > 0 && Files.size(p[2]) > 0, "preview video + per-track mp3s made");
        Clip frame = l.grabFrame(c, 2);
        assertEquals(640, frame.width);
    }
}
