package io.zyclipa;

import io.zyclipa.app.AppContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Windows and Linux builds share one version number: the pom, the app, the packaging and the Linux metadata agree. */
class VersionSyncTest {
    static String find(Path file, String regex) throws Exception {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(Files.readString(file));
        assertTrue(m.find(), "version not found in " + file);
        return m.group(1);
    }

    @Test void oneVersionEverywhere() throws Exception {
        String pom = find(Path.of("pom.xml"), "<artifactId>zyclipa</artifactId>\\s*<version>([^<]+)</version>");
        assertEquals(pom, AppContext.VERSION, "AppContext.VERSION");
        assertEquals(pom, find(Path.of("packaging/build-installer.ps1"), "\\$Version = '([^']+)'"), "Windows packaging default");
        assertEquals(pom, find(Path.of("packaging/linux/io.github.MaDaAi.Zyclipa.metainfo.xml"), "<release version=\"([^\"]+)\""), "Linux metainfo (newest release first)");
    }
}
