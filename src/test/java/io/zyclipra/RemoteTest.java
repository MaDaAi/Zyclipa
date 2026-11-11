package io.zyclipra;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dashboard server over real HTTPS on loopback, with a fake desktop app behind it (no recording
 * hardware involved): sign-in, sessions, CSRF rules, hosting rules, clip files and the action results.
 */
class RemoteTest {
    @TempDir Path tmp;
    DashboardServer server;
    DashboardAuth auth;
    String base;
    final AtomicInteger toggles = new AtomicInteger(), clips = new AtomicInteger();
    final Settings.Remote cfg = new Settings.Remote();
    Path media;
    volatile boolean replayOn = true;

    @BeforeEach void start() throws Exception {
        media = tmp.resolve("clip.mp4");
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        java.nio.file.Files.write(media, data);
        try (ServerSocket s = new ServerSocket(0)) { cfg.port = s.getLocalPort(); }
        auth = new DashboardAuth(cfg, () -> { });
        auth.setCredentials("owner", "correct horse 1", "correct horse 1");
        RemoteControl rc = new RemoteControl() {
            public JsonElement status() { JsonObject o = new JsonObject(); o.addProperty("recording", false); return o; }
            public JsonElement clips(int l) { return new JsonArray(); }
            public JsonElement levels() { return new JsonObject(); }
            public JsonElement mixer() { return new JsonArray(); }
            public JsonElement sounds() { return new JsonArray(); }
            public JsonElement toggleRecording() { toggles.incrementAndGet(); JsonObject o = new JsonObject(); o.addProperty("ok", "Recording started on your PC."); return o; }
            public JsonElement toggleReplay() { return new JsonObject(); }
            public JsonElement saveReplay() {
                if (!replayOn) throw new IllegalStateException("Instant replay is off.");
                clips.incrementAndGet();
                JsonObject o = new JsonObject(); o.addProperty("ok", "Clipped!"); return o;
            }
            public JsonElement screenshot() { return new JsonObject(); }
            public void setChannel(String id, double v, boolean m) { if (!id.equals("game")) throw new IllegalArgumentException("Unknown channel"); }
            public void playSound(String id) { }
            public void stopSounds() { }
            public Optional<Path> clipFile(String id) { return id.equals("clip1") ? Optional.of(media) : Optional.empty(); }
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

    HttpResponse<String> get(HttpClient c, String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String login(HttpClient c, String user, String pass) throws Exception {
        HttpResponse<String> r = post(c, "/api/login", "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}", null, true);
        assertEquals(200, r.statusCode(), r.body());
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("Secure") && setCookie.contains("SameSite=Strict"));
        return setCookie.split(";")[0];
    }

    @Test void apiRequiresLoginAndCsrfHeader() throws Exception {
        HttpClient c = insecureClient();
        for (String p : new String[]{"/api/status", "/api/clips", "/api/account", "/api/cloud", "/api/clip/media?id=clip1"})
            assertEquals(401, get(c, p, null).statusCode(), "signed-out GET " + p);
        assertEquals(401, post(c, "/api/recording/toggle", "{}", null, true).statusCode());
        assertEquals(401, post(c, "/api/replay/save", "{}", null, true).statusCode());
        assertEquals(401, get(c, "/api/status", "zs=madeUpToken").statusCode(), "a guessed token is useless");
        assertEquals(0, toggles.get());

        assertEquals(401, post(c, "/api/login", "{\"username\":\"owner\",\"password\":\"wrong password\"}", null, true).statusCode());
        assertEquals(401, post(c, "/api/login", "{\"username\":\"nobody\",\"password\":\"correct horse 1\"}", null, true).statusCode());
        String cookie = login(c, "owner", "correct horse 1");

        assertEquals(403, post(c, "/api/recording/toggle", "{}", cookie, false).statusCode(), "missing CSRF header must be refused");
        HttpRequest crossSite = HttpRequest.newBuilder(URI.create(base + "/api/recording/toggle")).POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("X-Zyclipra", "1").header("Cookie", cookie).header("Origin", "https://evil.example").build();
        assertEquals(403, c.send(crossSite, HttpResponse.BodyHandlers.ofString()).statusCode(), "other origins are refused");
        HttpResponse<String> ok = post(c, "/api/recording/toggle", "{}", cookie, true);
        assertEquals(200, ok.statusCode());
        assertEquals("Recording started on your PC.", JsonParser.parseString(ok.body()).getAsJsonObject().get("ok").getAsString(), "the desktop's own message");
        assertEquals(1, toggles.get());
        assertEquals(400, post(c, "/api/mixer", "{\"channel\":\"game\",\"volume\":7}", cookie, true).statusCode());
        assertEquals(400, post(c, "/api/mixer", "not json", cookie, true).statusCode());
        assertEquals(400, post(c, "/api/mixer", "{\"channel\":\"nope\",\"volume\":0.5}", cookie, true).statusCode());
        assertEquals(200, post(c, "/api/mixer", "{\"channel\":\"game\",\"volume\":0.5}", cookie, true).statusCode());
        assertEquals(400, post(c, "/api/mixer", "{\"channel\":\"" + "x".repeat(5000) + "\"}", cookie, true).statusCode(), "oversized body");
        HttpResponse<String> page = get(c, "/", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>Zyclipra</title>") && page.body().contains("Password"), "dashboard page with the sign-in form");
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
        assertEquals(404, get(c, "/../pom.xml", null).statusCode());

        assertEquals(200, post(c, "/api/logout", "{}", cookie, true).statusCode());
        assertEquals(401, get(c, "/api/status", cookie).statusCode(), "signed out");
    }

    @Test void clipItReachesTheDesktopAndReportsRealFailures() throws Exception {
        HttpClient c = insecureClient();
        String cookie = login(c, "owner", "correct horse 1");
        HttpResponse<String> r = post(c, "/api/replay/save", "{}", cookie, true);
        assertEquals(200, r.statusCode());
        assertEquals(1, clips.get(), "the request reached the desktop's replay service");
        replayOn = false;
        r = post(c, "/api/replay/save", "{}", cookie, true);
        assertEquals(409, r.statusCode(), "a failure is reported as a failure");
        assertEquals("Instant replay is off.", JsonParser.parseString(r.body()).getAsJsonObject().get("error").getAsString());
        assertEquals(1, clips.get());
    }

    @Test void accountSettingsAndSessions() throws Exception {
        HttpClient c = insecureClient();
        String a = login(c, "owner", "correct horse 1");
        String b = login(c, "owner", "correct horse 1");
        JsonObject acc = JsonParser.parseString(get(c, "/api/account", a).body()).getAsJsonObject();
        assertEquals("owner", acc.get("username").getAsString());
        assertEquals(2, acc.getAsJsonArray("sessions").size());
        assertFalse(acc.toString().contains("argon2"), "the password hash is never sent");
        assertFalse(acc.toString().contains(b.substring(3)), "session tokens are never sent");
        assertEquals("local", acc.getAsJsonObject("hosting").get("mode").getAsString());
        assertFalse(acc.getAsJsonObject("hosting").get("remote").getAsBoolean());

        // revoke the other session by its id
        String otherId = null;
        for (JsonElement s : acc.getAsJsonArray("sessions")) if (!s.getAsJsonObject().get("current").getAsBoolean()) otherId = s.getAsJsonObject().get("id").getAsString();
        assertEquals(200, post(c, "/api/account/revoke", "{\"id\":\"" + otherId + "\"}", a, true).statusCode());
        assertEquals(401, get(c, "/api/status", b).statusCode(), "revoked");

        // password change needs the current password and keeps only this session
        String c2 = login(c, "owner", "correct horse 1");
        assertEquals(403, post(c, "/api/account/password", "{\"current\":\"nope\",\"password\":\"new password 22\",\"confirm\":\"new password 22\"}", a, true).statusCode());
        assertEquals(400, post(c, "/api/account/password", "{\"current\":\"correct horse 1\",\"password\":\"short\",\"confirm\":\"short\"}", a, true).statusCode());
        assertEquals(200, post(c, "/api/account/password", "{\"current\":\"correct horse 1\",\"password\":\"new password 22\",\"confirm\":\"new password 22\"}", a, true).statusCode());
        assertEquals(200, get(c, "/api/status", a).statusCode(), "the session that changed it stays signed in");
        assertEquals(401, get(c, "/api/status", c2).statusCode(), "every other session is signed out");
        assertEquals(401, post(c, "/api/login", "{\"username\":\"owner\",\"password\":\"correct horse 1\"}", null, true).statusCode(), "old password no longer works");
        login(c, "owner", "new password 22");

        assertEquals(200, post(c, "/api/account/username", "{\"current\":\"new password 22\",\"username\":\"gamer.one\"}", a, true).statusCode());
        login(c, "gamer.one", "new password 22");
    }

    @Test void clipMediaNeedsLoginAndSupportsRanges() throws Exception {
        HttpClient c = insecureClient();
        HttpRequest anon = HttpRequest.newBuilder(URI.create(base + "/api/clip/media?id=clip1")).build();
        assertEquals(401, c.send(anon, HttpResponse.BodyHandlers.ofByteArray()).statusCode(), "clips are private");
        String cookie = login(c, "owner", "correct horse 1");

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
        for (int i = 0; i < 5; i++) post(c, "/api/login", "{\"username\":\"owner\",\"password\":\"guess " + i + "\"}", null, true);
        assertEquals(429, post(c, "/api/login", "{\"username\":\"owner\",\"password\":\"correct horse 1\"}", null, true).statusCode(),
                "even the right password is refused while locked");
    }

    @Test void hostingRules() throws Exception {
        Settings.Remote r = new Settings.Remote();
        assertEquals("local", r.mode, "public hosting is off by default");
        assertFalse(r.lan || r.vpn || r.enabled, "nothing beyond this PC by default");
        InetAddress loop = InetAddress.getByName("127.0.0.1"), lan = InetAddress.getByName("192.168.1.20"),
                ts = InetAddress.getByName("100.101.102.103"), ts6 = InetAddress.getByName("fd7a:115c:a1e0::1"), pub = InetAddress.getByName("8.8.8.8");
        assertTrue(DashboardServer.allowed(loop, r));
        assertFalse(DashboardServer.allowed(lan, r), "home network only when switched on");
        assertFalse(DashboardServer.allowed(ts, r), "VPN only when switched on");
        r.lan = true;
        assertTrue(DashboardServer.allowed(lan, r));
        assertFalse(DashboardServer.allowed(ts, r), "home network doesn't include the VPN");
        r.lan = false;
        r.vpn = true;
        assertTrue(DashboardServer.allowed(ts, r));
        assertTrue(DashboardServer.allowed(ts6, r));
        assertTrue(DashboardServer.allowed(pub, r), "outside access: the owner's own port forwarding or VPN");
        r.vpn = false;
        r.lan = true;
        assertFalse(DashboardServer.allowed(pub, r), "the home network switch never lets the internet in");
        r.mode = "public";
        assertTrue(DashboardServer.allowed(loop, r), "public mode: only the owner's reverse proxy on this PC");
        assertFalse(DashboardServer.allowed(lan, r));
        assertFalse(DashboardServer.allowed(pub, r));

        for (String ok : new String[]{"127.0.0.1", "::1", "192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.4", "100.101.102.103", "fd7a:115c:a1e0::1"})
            assertTrue(DashboardServer.privateAddress(InetAddress.getByName(ok)), ok);
        for (String bad : new String[]{"8.8.8.8", "100.128.0.1", "1.1.1.1", "172.32.0.1", "2001:4860:4860::8888"})
            assertFalse(DashboardServer.privateAddress(InetAddress.getByName(bad)), bad);
    }

    @Test void listensOnLoopbackOnlyByDefaultAndCanRestart() throws Exception {
        assertEquals(1, server.addresses().size(), "only the This PC address is shown");
        assertTrue(server.addresses().get(0).url().startsWith("https://127.0.0.1:"));
        // Nothing listens on the machine's LAN address: connecting there must fail.
        InetAddress lanAddr = null;
        for (var ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
            for (var a : java.util.Collections.list(ni.getInetAddresses())) if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) lanAddr = a;
        if (lanAddr != null) {
            try (java.net.Socket s = new java.net.Socket()) {
                InetAddress target = lanAddr;
                assertThrows(java.io.IOException.class, () -> s.connect(new java.net.InetSocketAddress(target, cfg.port), 1500), "not reachable from the LAN");
            }
        }
        server.restart();
        assertTrue(server.running());
        server.stop();
        assertFalse(server.running());
        server.start();
        assertTrue(server.running());
    }

    @Test void portConflictIsReported() throws Exception {
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Settings.Remote other = new Settings.Remote();
            other.port = busy.getLocalPort();
            DashboardAuth a = new DashboardAuth(other, () -> { });
            a.setCredentials("owner", "correct horse 1", "correct horse 1");
            DashboardServer s = new DashboardServer(other, a, null, tmp.resolve("tls.p12"), new SecretStore(tmp.resolve("tls.key")));
            assertTrue(DashboardServer.portInUse(other.port));
            IllegalStateException e = assertThrows(IllegalStateException.class, s::start);
            assertTrue(e.getMessage().contains("already used"), e.getMessage());
            assertFalse(s.running());
        }
    }

    @Test void cannotStartBeforeFirstRunSetup() {
        Settings.Remote fresh = new Settings.Remote();
        DashboardAuth a = new DashboardAuth(fresh, () -> { });
        assertFalse(a.configured(), "there is no default account");
        DashboardServer s = new DashboardServer(fresh, a, null, tmp.resolve("tls2.p12"), new SecretStore(tmp.resolve("tls2.key")));
        assertThrows(IllegalStateException.class, s::start);
        assertNull(a.login("127.0.0.1", "", "admin", "admin"));
        assertEquals(DashboardAuth.Result.NOT_SET_UP, a.lastResult());
    }
}
