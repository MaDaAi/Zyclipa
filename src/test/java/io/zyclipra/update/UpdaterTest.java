package io.zyclipra.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UpdaterTest {
    @TempDir Path dir;

    static KeyPair keys() throws Exception { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }

    static String manifest(KeyPair k, String version, String url, String sha, long size) throws Exception {
        String canonical = "zyclipra-update|v1|" + version + "|" + url + "|" + sha + "|" + size;
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        s.update(canonical.getBytes(StandardCharsets.UTF_8));
        return "{\"version\":\"" + version + "\",\"url\":\"" + url + "\",\"sha256\":\"" + sha + "\",\"size\":" + size
                + ",\"notes\":\"n\",\"signature\":\"" + Base64.getEncoder().encodeToString(s.sign()) + "\"}";
    }

    Updater updater(KeyPair k, String current) {
        return new Updater(current, dir.resolve("work"), k.getPublic(), HttpClient.newHttpClient());
    }

    static final String SHA = "a".repeat(64);
    static String url(String v) { return Updater.DOWNLOAD_PREFIX + "v" + v + "/Zyclipra-" + v + "-windows-x64.zip"; }

    @Test void signedManifestIsAccepted() throws Exception {
        KeyPair k = keys();
        Updater.Release r = updater(k, "0.1.0").parse(manifest(k, "0.2.0", url("0.2.0"), SHA, 1234));
        assertEquals("0.2.0", r.version());
        assertEquals(1234, r.size());
    }

    @Test void tamperedOrForeignManifestsAreRejected() throws Exception {
        KeyPair k = keys(), other = keys();
        Updater u = updater(k, "0.1.0");
        String good = manifest(k, "0.2.0", url("0.2.0"), SHA, 1234);
        assertThrows(SecurityException.class, () -> u.parse(good.replace("\"size\":1234", "\"size\":1235")), "changed size");
        assertThrows(SecurityException.class, () -> u.parse(good.replace(SHA, "b".repeat(64))), "changed hash");
        assertThrows(SecurityException.class, () -> u.parse(manifest(other, "0.2.0", url("0.2.0"), SHA, 1234)), "wrong key");
        assertThrows(SecurityException.class, () -> u.parse(manifest(k, "0.2.0", "https://evil.example/Zyclipra.zip", SHA, 1234)), "not GitHub");
        assertThrows(SecurityException.class, () -> u.parse(manifest(k, "0.2.0", url("0.3.0"), SHA, 1234)), "url for another version");
        assertThrows(SecurityException.class, () -> u.parse(manifest(k, "0.2", url("0.2"), SHA, 1234)), "bad version");
    }

    @Test void versionsCompareNumerically() {
        assertTrue(Updater.compare("0.10.0", "0.9.9") > 0);
        assertTrue(Updater.compare("1.0.0", "1.0.0") == 0);
        assertTrue(Updater.compare("0.1.0", "0.2.0") < 0);
    }

    @Test void downloadMustMatchTheSignedHashAndSize() throws Exception {
        byte[] body = "pretend zip".getBytes(StandardCharsets.UTF_8);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/f.zip", ex -> { ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close(); });
        s.start();
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/f.zip";
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Updater u = updater(keys(), "0.1.0");
            Path ok = u.download(new Updater.Release("0.2.0", url, sha, body.length, ""), p -> { });
            assertArrayEquals(body, Files.readAllBytes(ok));
            assertThrows(SecurityException.class, () -> u.download(new Updater.Release("0.2.1", url, "c".repeat(64), body.length, ""), p -> { }));
            assertThrows(SecurityException.class, () -> u.download(new Updater.Release("0.2.2", url, sha, body.length - 1, ""), p -> { }));
            assertFalse(Files.exists(dir.resolve("work").resolve("Zyclipra-0.2.1.zip")), "a bad download is never kept");
        } finally {
            s.stop(0);
        }
    }

    static Path zip(Path p, String... entries) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (String e : entries) { z.putNextEntry(new ZipEntry(e)); z.write(1); z.closeEntry(); }
        }
        return Files.write(p, b.toByteArray());
    }

    @Test void unpackBlocksPathTricksAndNeedsTheApp() throws Exception {
        Path evil = zip(dir.resolve("evil.zip"), "Zyclipra/Zyclipra.exe", "Zyclipra/app/zyclipra.jar", "../escaped.txt");
        assertThrows(SecurityException.class, () -> Updater.unpack(evil, dir.resolve("t1")));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
        Path empty = zip(dir.resolve("empty.zip"), "readme.txt");
        assertThrows(SecurityException.class, () -> Updater.unpack(empty, dir.resolve("t2")));
        Path good = zip(dir.resolve("good.zip"), "Zyclipra/Zyclipra.exe", "Zyclipra/app/zyclipra.jar", "Zyclipra/runtime/release");
        Path app = Updater.unpack(good, dir.resolve("t3"));
        assertTrue(Files.isRegularFile(app.resolve("Zyclipra.exe")));
    }

    @Test void builtInKeyLoads() {
        assertNotNull(Updater.publicKey(Updater.PUBLIC_KEY));
    }
}
