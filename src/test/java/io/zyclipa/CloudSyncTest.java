package io.zyclipa;

import io.zyclipa.clips.Clip;
import io.zyclipa.clips.ClipLibrary;
import io.zyclipa.cloud.GoogleAuth;
import io.zyclipa.cloud.Secrets;
import io.zyclipa.cloud.SyncService;
import io.zyclipa.core.EventBus;
import io.zyclipa.core.SettingsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Google sign-in and Drive sync against {@link FakeGoogle}, a local stand-in for Google's servers.
 * These are MOCKED tests: they prove Zyclipa's side of the protocol (PKCE, refresh, resumable
 * uploads, verification, queue persistence). They do not prove a real Google account works.
 */
class CloudSyncTest {
    @TempDir Path tmp;
    FakeGoogle google;
    SettingsStore store;
    EventBus bus;
    ClipLibrary library;
    MemSecrets tokens;
    SyncService sync;
    Path clips;

    static final class MemSecrets implements Secrets {
        volatile String value;
        public void save(String s) { value = s; }
        public String load() { return value; }
        public void clear() { value = null; }
    }

    @BeforeEach void setUp() throws Exception {
        google = new FakeGoogle();
        clips = Files.createDirectories(tmp.resolve("clips"));
        store = new SettingsStore(tmp.resolve("settings.json"));
        store.get().cloud.clientId = "test-client.apps.googleusercontent.com";
        bus = new EventBus();
        library = new ClipLibrary(() -> clips, tmp.resolve("library.json"), tmp.resolve("thumbs"), Optional::empty, bus);
        tokens = new MemSecrets();
        sync = newSync();
    }

    SyncService newSync() {
        SyncService s = new SyncService(store, library, bus, tokens, tmp.resolve("avatar.jpg"), tmp.resolve("sync-queue.json"),
                HttpClient.newHttpClient(), google.base);
        s.auth().endpoints(google.base);
        s.auth().client(new io.zyclipa.cloud.GoogleClient("test-client.apps.googleusercontent.com", "test-secret", "test")); // never the real built-in client
        s.backoff(50);
        return s;
    }

    @AfterEach void tearDown() {
        sync.close();
        google.close();
    }

    Clip clip(String name, String kind, int size) throws Exception {
        byte[] b = new byte[size];
        new Random(name.hashCode()).nextBytes(b);
        Path f = clips.resolve(name);
        Files.write(f, b);
        return library.add(f, kind, "Roblox");
    }

    static void waitFor(BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for: " + what);
            Thread.sleep(50);
        }
    }

    /** Plays the browser: checks the consent URL, then follows the redirect with the given state. */
    static Map<String, String> params(String url) {
        Map<String, String> m = new HashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            int i = kv.indexOf('=');
            m.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return m;
    }

    void signIn() throws Exception {
        String email = sync.auth().signIn(url -> {
            Map<String, String> p = params(url);
            new Thread(() -> {
                try {
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(p.get("redirect_uri") + "/?code=good-code&state=" + p.get("state"))).build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) { throw new RuntimeException(e); }
            }).start();
        }).get(20, TimeUnit.SECONDS);
        assertEquals("player@example.com", email);
    }

    @Test void oauthUsesPkceLoopbackAndMinimalScopes() throws Exception {
        String[] seen = new String[1];
        String email = sync.auth().signIn(url -> {
            seen[0] = url;
            Map<String, String> p = params(url);
            new Thread(() -> {
                try {
                    // A stray browser request (e.g. favicon) must not end the sign-in.
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(p.get("redirect_uri") + "/favicon.ico")).build(), HttpResponse.BodyHandlers.discarding());
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(p.get("redirect_uri") + "/?code=abc&state=" + p.get("state"))).build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) { throw new RuntimeException(e); }
            }).start();
        }).get(20, TimeUnit.SECONDS);
        Map<String, String> p = params(seen[0]);
        assertEquals("S256", p.get("code_challenge_method"));
        assertTrue(p.get("code_challenge").length() >= 43);
        assertTrue(p.get("redirect_uri").startsWith("http://127.0.0.1:"), "loopback redirect in the user's own browser");
        assertEquals("openid email profile https://www.googleapis.com/auth/drive.file", p.get("scope"), "drive.file only – no full Drive access");
        assertEquals("offline", p.get("access_type"));
        assertEquals("abc", google.lastAuthCode);
        assertEquals("player@example.com", email);
        assertEquals("Test Player", store.get().cloud.accountName);
        assertEquals("refresh-1", tokens.load(), "refresh token kept in the secret store");
        assertTrue(Files.isRegularFile(tmp.resolve("avatar.jpg")), "profile picture cached");
        assertNotNull(javax.imageio.ImageIO.read(tmp.resolve("avatar.jpg").toFile()), "a PNG avatar is stored as a readable JPEG");
        store.saveNow();
        assertFalse(Files.readString(tmp.resolve("settings.json")).contains("refresh-1"), "never in settings.json");
    }

    @Test void oauthRejectsAMismatchedState() {
        var f = sync.auth().signIn(url -> {
            Map<String, String> p = params(url);
            new Thread(() -> {
                try {
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(p.get("redirect_uri") + "/?code=evil&state=forged")).build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) { throw new RuntimeException(e); }
            }).start();
        });
        Exception e = assertThrows(Exception.class, () -> f.get(20, TimeUnit.SECONDS));
        assertTrue(e.getMessage().contains("didn't match"), e.getMessage());
        assertNull(tokens.load());
        assertNull(google.lastAuthCode, "a forged reply is never exchanged for tokens");
    }

    @Test void refreshAndRevokedGrant() throws Exception {
        tokens.save("refresh-1");
        assertEquals("access-1", sync.auth().accessToken(), "access token refreshed from the stored refresh token");
        sync.auth().invalidate();
        google.revokeGrant = true;
        GoogleAuth.AuthException e = assertThrows(GoogleAuth.AuthException.class, () -> sync.auth().accessToken());
        assertTrue(e.getMessage().contains("connect again"));
        assertTrue(store.get().cloud.needsReauth, "the UI asks to reconnect");
        assertNull(tokens.load(), "the dead token is forgotten");
    }

    @Test void uploadsIntoFoldersAndVerifies() throws Exception {
        signIn();
        Clip replay = clip("Roblox_replay_1.mp4", "replay", 3 * 1024 * 1024 + 17);
        Clip shot = clip("shot.png", "screenshot", 5000);
        Clip rec = clip("Roblox_rec.mp4", "recording", 1000);
        sync.upload(replay);
        sync.upload(shot);
        sync.upload(rec);
        waitFor(() -> sync.stateOf(replay.id).equals("synced") && sync.stateOf(shot.id).equals("synced") && sync.stateOf(rec.id).equals("synced"), "3 uploads");
        FakeGoogle.File root = google.folder("Zyclipa", null);
        assertNotNull(root, "Zyclipa folder in My Drive");
        for (String sub : new String[]{"Clips", "Recordings", "Screenshots"}) assertNotNull(google.folder(sub, root.id), sub);
        assertEquals(1, google.files.values().stream().filter(f -> f.name.equals("Zyclipa")).count(), "no duplicate folders");
        FakeGoogle.File up = google.files.get(sync.item(replay.id).orElseThrow().driveFileId);
        assertArrayEquals(Files.readAllBytes(clips.resolve("Roblox_replay_1.mp4")), up.data);
        assertEquals(google.folder("Clips", root.id).id, up.parent);
        assertEquals(replay.id, up.app.get("zyclipraId"));
        assertEquals(google.folder("Screenshots", root.id).id, google.files.get(sync.item(shot.id).orElseThrow().driveFileId).parent);
        assertEquals(up.id, library.byId(replay.id).orElseThrow().driveFileId, "clip metadata updated");
        assertTrue(Files.exists(clips.resolve("Roblox_replay_1.mp4")), "local file kept");
        assertTrue(store.get().cloud.lastSyncAt > 0);
    }

    @Test void interruptedUploadResumesWithoutDuplicates() throws Exception {
        signIn();
        Clip c = clip("big.mp4", "replay", 5 * 1024 * 1024);
        google.failChunks.set(2);
        sync.upload(c);
        waitFor(() -> sync.stateOf(c.id).equals("synced"), "upload after two dropped chunks");
        assertEquals(1, google.mediaFiles(), "exactly one copy on Drive");
        assertEquals(1, google.uploadsStarted.get(), "the same upload session was resumed");
        // Asking again doesn't upload a second copy.
        assertEquals("Already on Google Drive.", sync.upload(c));
        // Even if the local record is lost, the tag on the Drive file prevents a duplicate.
        Files.delete(tmp.resolve("sync-queue.json"));
        sync.close();
        library.byId(c.id).orElseThrow().driveFileId = "";
        sync = newSync();
        sync.upload(c);
        waitFor(() -> sync.stateOf(c.id).equals("synced"), "re-check");
        assertEquals(1, google.mediaFiles(), "found the earlier upload instead of duplicating it");
        assertEquals(1, google.uploadsStarted.get());
    }

    @Test void giveUpAfterRepeatedFailuresThenRetry() throws Exception {
        signIn();
        Clip c = clip("flaky.mp4", "replay", 1000);
        google.failChunks.set(100);
        sync.upload(c);
        waitFor(() -> sync.stateOf(c.id).equals("failed"), "failed after the retry limit");
        assertTrue(sync.item(c.id).orElseThrow().error.contains("temporary problem"), sync.item(c.id).orElseThrow().error);
        assertTrue(Files.exists(clips.resolve("flaky.mp4")), "a failed upload never touches the local file");
        google.failChunks.set(0);
        assertEquals(1, sync.retry(null));
        waitFor(() -> sync.stateOf(c.id).equals("synced"), "retry");
    }

    @Test void queueSurvivesRestartAndWaitsForSignIn() throws Exception {
        Clip c = clip("later.mp4", "replay", 2000);
        assertTrue(sync.upload(c).contains("once Google Drive is connected"));
        assertEquals("queued", sync.stateOf(c.id));
        sync.close();
        sync = newSync();   // app restarted
        assertEquals("queued", sync.stateOf(c.id), "still queued after a restart");
        signIn();           // connecting starts the queue
        waitFor(() -> sync.stateOf(c.id).equals("synced"), "queued upload after connecting");
    }

    @Test void automaticUploadOnlyWhenEnabled() throws Exception {
        signIn();
        Clip off = clip("not-auto.mp4", "replay", 1000);
        Thread.sleep(300);
        assertEquals("local", sync.stateOf(off.id), "automatic upload is off by default");
        store.get().cloud.autoUpload = true;
        store.get().cloud.uploadScreenshots = false;
        Clip on = clip("auto.mp4", "replay", 1000);
        Clip shot = clip("auto.png", "screenshot", 1000);
        waitFor(() -> sync.stateOf(on.id).equals("synced"), "new clip uploaded automatically");
        assertEquals("local", sync.stateOf(shot.id), "screenshots switched off");
    }

    @Test void cloudDeletionDownloadCancelAndDisconnect() throws Exception {
        signIn();
        Clip a = clip("a.mp4", "replay", 4000), b = clip("b.mp4", "replay", 4000);
        sync.upload(a);
        sync.upload(b);
        waitFor(() -> sync.stateOf(a.id).equals("synced") && sync.stateOf(b.id).equals("synced"), "uploads");

        // Deleted in Drive by the user: noticed and reported, the local clip is fine.
        google.files.remove(sync.item(a.id).orElseThrow().driveFileId);
        assertEquals(1, sync.verifyCloudCopies());
        assertEquals("unavailable", sync.stateOf(a.id));
        assertTrue(Files.exists(clips.resolve("a.mp4")));

        // Delete the cloud copy from Zyclipa (the UI asks first).
        String bId = sync.item(b.id).orElseThrow().driveFileId;
        sync.deleteCloudCopy(b.id);
        assertFalse(google.files.containsKey(bId));
        assertEquals("local", sync.stateOf(b.id));
        assertTrue(Files.exists(clips.resolve("b.mp4")), "deleting the cloud copy keeps the local clip");

        // Download back a clip that is only on Drive.
        sync.upload(b);
        waitFor(() -> sync.stateOf(b.id).equals("synced"), "b again");
        byte[] original = Files.readAllBytes(clips.resolve("b.mp4"));
        Files.delete(clips.resolve("b.mp4")); // gone from this PC (not via the Recycle Bin, so tests leave no trace)
        library.scan();
        assertEquals(1, sync.cloudOnly().size());
        Path back = sync.download(b.id, p -> { }).get(20, TimeUnit.SECONDS);
        assertArrayEquals(original, Files.readAllBytes(back));
        assertTrue(back.startsWith(clips));

        // Cancel a queued upload; disconnect keeps everything and revokes the token.
        Clip c = clip("c.mp4", "replay", 1000);
        google.failChunks.set(100);
        sync.upload(c);
        sync.cancel(c.id);
        assertEquals("local", sync.stateOf(c.id));
        google.failChunks.set(0);
        int filesBefore = google.files.size();
        sync.disconnect();
        assertNull(tokens.load());
        assertEquals(1, google.revoked.get(), "token revoked at Google");
        assertEquals("", store.get().cloud.accountEmail);
        assertEquals(filesBefore, google.files.size(), "disconnecting never deletes cloud files");
        assertTrue(Files.exists(clips.resolve("c.mp4")));
        assertFalse(sync.statusJson().toString().contains("refresh-1"), "status never contains tokens");
    }
}
