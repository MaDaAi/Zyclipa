package io.zyclipra;

import io.zyclipra.cloud.CredentialStore;
import io.zyclipra.cloud.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL integration test (not mocked): writes, reads and deletes a throw-away entry in this Windows
 * user's Credential Manager, and checks a secret in the old encrypted file is moved over.
 */
@EnabledOnOs(OS.WINDOWS)
class CredentialStoreTest {
    @TempDir Path tmp;

    @Test void roundTripInWindowsCredentialManager() throws Exception {
        assertTrue(CredentialStore.available(), "Credential Manager reachable");
        String target = "Zyclipra/test-" + UUID.randomUUID();
        Path file = tmp.resolve("legacy.bin");
        CredentialStore store = new CredentialStore(target, new SecretStore(file));
        try {
            assertNull(store.load());
            store.save("refresh-token-é-✓");
            assertEquals("refresh-token-é-✓", store.load());
            assertFalse(Files.exists(file), "nothing written to disk by Zyclipra");
            // A new instance (like after a restart) reads it back from Windows.
            assertEquals("refresh-token-é-✓", new CredentialStore(target, new SecretStore(file)).load());
            store.clear();
            assertNull(store.load());

            // Migration: a secret in the old DPAPI file moves into Credential Manager and the file goes away.
            new SecretStore(file).save("old-token");
            assertTrue(Files.exists(file));
            assertEquals("old-token", store.load());
            assertFalse(Files.exists(file), "old file removed after moving");
            assertEquals("old-token", new CredentialStore(target, new SecretStore(file)).load());
        } finally {
            store.clear();
        }
    }
}
