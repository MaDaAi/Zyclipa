package io.zyclipra.cloud;

import com.sun.jna.platform.win32.Crypt32Util;
import io.zyclipra.core.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Keeps a secret (the Google refresh token) on disk. On Windows it is encrypted with DPAPI,
 * which ties it to your Windows account, so another user or another PC can't read it.
 * Elsewhere the file is made readable by its owner only (an OS keychain is planned).
 */
public final class SecretStore implements Secrets {
    private final Path file;

    public SecretStore(Path file) { this.file = file; }

    @Override public void save(String secret) throws IOException {
        byte[] data = secret.getBytes(StandardCharsets.UTF_8);
        byte[] out = Platform.isWindows() ? Crypt32Util.cryptProtectData(data) : data;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out);
        if (!Platform.isWindows()) {
            try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public String load() {
        try {
            if (!Files.exists(file)) return null;
            byte[] in = Files.readAllBytes(file);
            byte[] plain = Platform.isWindows() ? Crypt32Util.cryptUnprotectData(in) : in;
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Override public void clear() throws IOException { Files.deleteIfExists(file); }
}
