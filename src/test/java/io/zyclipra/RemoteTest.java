package io.zyclipra;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.zyclipra.cloud.SecretStore;
import io.zyclipra.core.Settings;
import io.zyclipra.remote.DashboardAuth;
import io.zyclipra.remote.DashboardServer;
import io.zyclipra.remote.RemoteControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteTest {
    @TempDir Path tmp;
    DashboardServer server;
    String base;
    String code;
    final AtomicInteger toggles = new AtomicInteger();
    final Settings.Remote cfg = new Settings.Remote();

    Path media;

    @BeforeEach void start() throws Exception {
        media = tmp.resolve("clip.mp4");
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        java.nio.file.Files.write(media, data);
        try (ServerSocket s = new ServerSocket(0)) { cfg.port = s.getLocalPort(); }
        DashboardAuth auth = new DashboardAuth(cfg);
        code = auth.newPairingCode();
        RemoteControl rc = new RemoteControl() {
            public JsonElement status() { JsonObject o = new JsonObject(); o.addProperty("recording", false); return o; }
            public JsonElement clips(int l) { return new JsonArray(); }
            public JsonElement levels() { return new JsonObject(); }
            public JsonElement mixer() { return new JsonArray(); }
            public JsonElement sounds() { return new JsonArray(); }
            public void toggleRecording() { toggles.incrementAndGet(); }
            public void toggleReplay() { }
            public void saveReplay() { }
            public void screenshot() { }
            public void setChannel(String id, double v, boolean m) { if (!id.equals("game")) throw new IllegalArgumentException("Unknown channel"); }
            public void playSound(String id) { }
            public void stopSounds() { }
            public java.util.Optional<Path> clipFile(String id) { return id.equals("clip1") ? java.util.Optional.of(media) : java.util.Optional.empty(); }
        };
        server = new DashboardServer(cfg, auth, rc, tmp.resolve("tls.p12"), new SecretStore(tmp.resolve("tls.key")));
        server.start();
        base = "https://localhost:" + cfg.port;
    }

    @AfterEach void stop() { server.stop(); }

    static HttpClient insecureClient() throws Exception {
        // Tests only: accept the self-signed certificate (the app shows its fingerprint to users).
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    HttpResponse<String> post(HttpClient c, String path, String body, String cookie, boolean header) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (header) b.header("X-Zyclipra", "1");
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void apiRequiresLoginAndCsrfHeader() throws Exception {
        HttpClient c = insecureClient();
        assertEquals(401, c.send(HttpRequest.newBuilder(URI.create(base + "/api/status")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, post(c, "/api/recording/toggle", "{}", null, true).statusCode());
        assertEquals(0, toggles.get());

        assertEquals(401, post(c, "/api/login", "{\"code\":\"WRONG-CODE1\"}", null, true).statusCode());
        HttpResponse<String> ok = post(c, "/api/login", "{\"code\":\"" + code.toLowerCase() + "\"}", null, true);
        assertEquals(200, ok.statusCode());
        String setCookie = ok.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("Secure") && setCookie.contains("SameSite=Strict"));
        String cookie = setCookie.split(";")[0];

        assertEquals(403, post(c, "/api/recording/toggle", "{}", cookie, false).statusCode(), "missing CSRF header must be refused");
        assertEquals(200, post(c, "/api/recording/toggle", "{}", cookie, true).statusCode());
        assertEquals(1, toggles.get());
        assertEquals(400, post(c, "/api/mixer", "{\"channel\":\"game\",\"volume\":7}", cookie, true).statusCode());
        assertEquals(400, post(c, "/api/mixer", "not json", cookie, true).statusCode());
        assertEquals(400, post(c, "/api/mixer", "{\"channel\":\"nope\",\"volume\":0.5}", cookie, true).statusCode());
        assertEquals(200, post(c, "/api/mixer", "{\"channel\":\"game\",\"volume\":0.5}", cookie, true).statusCode());
        assertEquals(400, post(c, "/api/mixer", "{\"channel\":\"" + "x".repeat(5000) + "\"}", cookie, true).statusCode(), "oversized body");
        HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>Zyclipra</title>") && page.body().contains("Pairing code"), "dashboard page with the sign-in form");
        assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());
        assertEquals(404, c.send(HttpRequest.newBuilder(URI.create(base + "/../pom.xml")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void clipMediaNeedsLoginAndSupportsRanges() throws Exception {
        HttpClient c = insecureClient();
        HttpRequest anon = HttpRequest.newBuilder(URI.create(base + "/api/clip/media?id=clip1")).build();
        assertEquals(401, c.send(anon, HttpResponse.BodyHandlers.ofByteArray()).statusCode(), "clips are private");
        String cookie = post(c, "/api/login", "{\"code\":\"" + code + "\"}", null, true).headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

        HttpResponse<byte[]> all = c.send(HttpRequest.newBuilder(URI.create(base + "/api/clip/media?id=clip1")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, all.statusCode());
        assertEquals(200_000, all.body().length);
        assertEquals("video/mp4", all.headers().firstValue("Content-Type").orElse(""));

        HttpResponse<byte[]> part = c.send(HttpRequest.newBuilder(URI.create(base + "/api/clip/media?id=clip1"))
                .header("Cookie", cookie).header("Range", "bytes=1000-1999").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, part.statusCode());
        assertEquals(1000, part.body().length);
        assertEquals((byte) 1000, part.body()[0]);
        assertEquals("bytes 1000-1999/200000", part.headers().firstValue("Content-Range").orElse(""));

        HttpResponse<byte[]> dl = c.send(HttpRequest.newBuilder(URI.create(base + "/api/clip/media?id=clip1&download=1")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(dl.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));

        for (String bad : new String[]{"..%2F..%2Fpom.xml", "clip1%2F..", "nope", "C:%5Cwindows"})
            assertNotEquals(200, c.send(HttpRequest.newBuilder(URI.create(base + "/api/clip/media?id=" + bad)).header("Cookie", cookie).build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode(), "only known clip ids are served: " + bad);
    }

    @Test void bruteForceIsLockedOut() throws Exception {
        HttpClient c = insecureClient();
        for (int i = 0; i < 5; i++) post(c, "/api/login", "{\"code\":\"BAD" + i + "\"}", null, true);
        assertEquals(429, post(c, "/api/login", "{\"code\":\"" + code + "\"}", null, true).statusCode(), "even the right code is refused while locked");
    }

    @Test void onlyPrivateAddressesAreServed() throws Exception {
        for (String ok : new String[]{"127.0.0.1", "::1", "192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.4", "100.101.102.103", "fd7a:115c:a1e0::1"})
            assertTrue(DashboardServer.privateAddress(java.net.InetAddress.getByName(ok)), ok);
        for (String bad : new String[]{"8.8.8.8", "100.128.0.1", "1.1.1.1", "172.32.0.1", "2001:4860:4860::8888"})
            assertFalse(DashboardServer.privateAddress(java.net.InetAddress.getByName(bad)), bad);
    }

    @Test void listensOnLoopbackOnlyByDefault() {
        assertFalse(cfg.lan);
        assertEquals(1, server.urls().size());
    }
}
