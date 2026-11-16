package io.zyclipa;

import io.zyclipa.core.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir Path tmp;

    @Test void movesContentsAndLeavesClashes() throws Exception {
        Path from = Files.createDirectories(tmp.resolve("old")), to = Files.createDirectories(tmp.resolve("new"));
        Files.writeString(from.resolve("a.mp4"), "a");
        Files.createDirectories(from.resolve("Screenshots"));
        Files.writeString(from.resolve("Screenshots/s.png"), "s");
        Files.writeString(from.resolve("same.mp4"), "old");
        Files.writeString(to.resolve("same.mp4"), "new");
        int skipped = Storage.moveContents(from, to, p -> true);
        assertEquals(1, skipped, "the clash is left alone");
        assertEquals("a", Files.readString(to.resolve("a.mp4")));
        assertEquals("s", Files.readString(to.resolve("Screenshots/s.png")), "sub-folders move too");
        assertEquals("new", Files.readString(to.resolve("same.mp4")), "nothing is overwritten");
        assertTrue(Files.exists(from.resolve("same.mp4")));
        assertFalse(Files.exists(from.resolve("a.mp4")));
    }

    @Test void onlyChosenFilesAndNoNesting() throws Exception {
        Path from = Files.createDirectories(tmp.resolve("sounds"));
        Files.writeString(from.resolve("mine.wav"), "x");
        Files.writeString(from.resolve("other.txt"), "y");
        Storage.moveContents(from, tmp.resolve("elsewhere"), p -> p.getFileName().toString().endsWith(".wav"));
        assertTrue(Files.exists(tmp.resolve("elsewhere/mine.wav")));
        assertTrue(Files.exists(from.resolve("other.txt")), "files that aren't ours stay");
        assertThrows(IOException.class, () -> Storage.moveContents(from, from.resolve("inside"), p -> true));
    }
}
