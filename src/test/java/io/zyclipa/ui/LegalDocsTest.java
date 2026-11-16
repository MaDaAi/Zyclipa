package io.zyclipa.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The legal documents shown in the app exist, have real content, and match the website's copies. */
class LegalDocsTest {
    private static final Pattern BLOCK = Pattern.compile("<(h1|h2|p|li|pre|tr)(\\s[^>]*)?>(.*?)</\\1>", Pattern.DOTALL);

    @Test void everyDocumentIsBundledWithContent() {
        for (var e : LegalDocs.DOCS.entrySet()) {
            String s = LegalDocs.read(e.getValue());
            assertTrue(s.length() > 500, e.getKey() + " is missing or empty");
            if (e.getValue().endsWith(".html")) {
                long blocks = BLOCK.matcher(s).results().count();
                assertTrue(blocks >= 5, e.getKey() + " has only " + blocks + " blocks");
                assertTrue(s.contains("notefirasupport@gmail.com") || e.getValue().equals("eula.html") == false || s.contains("mailto:"), e.getKey());
            }
        }
    }

    @Test void textStripsTagsAndEntities() {
        assertEquals("Zyclipa & you <3", LegalDocs.text("<b>Zyclipa</b> &amp; you &lt;3"));
    }

    @Test void appCopiesMatchTheWebsite() throws Exception {
        Path site = Path.of("..", "zyclipa-dashboard");
        if (!Files.isDirectory(site)) site = Path.of("..", "zyclipra-dashboard");
        Assumptions.assumeTrue(Files.isDirectory(site), "website repo not next to the app");
        for (String f : LegalDocs.DOCS.values()) {
            assertEquals(Files.readString(site.resolve(f)), Files.readString(Path.of("src/main/resources/web", f)),
                    f + " differs from the website – run python packaging/sync-legal.py");
        }
    }
}
