package io.zyclipa.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.zyclipa.cloud.Pkce;
import io.zyclipa.cloud.Secrets;
import io.zyclipa.cloud.SyncService;
import io.zyclipa.core.Settings;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Lets the hosted Zyclipa website (GitHub Pages) use this PC's controls while you're on the same network.
 * <ul>
 *   <li>A random 256-bit link key is kept in Windows Credential Manager; settings hold only its SHA-256.</li>
 *   <li>The key, this PC's home-network addresses and its certificate fingerprint go into one small file in
 *       the owner's Google Drive. Only someone signed in to that Google account can read it.</li>
 *   <li>The website trades the key for a short session with the PC directly (no relay, no open ports).
 *       Only the exact website origin gets CORS, and website sessions can only use the controls.</li>
 *   <li>Reset makes a new key and signs out every website session.</li>
 * </ul>
 */
public final class WebsiteLink {
    private static final Logger LOG = Logger.getLogger(WebsiteLink.class.getName());

    private final Settings.Remote cfg;
    private final Runnable save;
    private final Secrets keyStore;
    private final SyncService sync;
    private final DashboardServer dashboard;
    private final DashboardAuth auth;
    private final String pcId;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "website-link");
        t.setDaemon(true);
        return t;
    });
    private volatile String published = "";
    private final RtcBridge rtc;
    private volatile String signalId = "", signalSeen = "", lastOffer = "";
    private volatile String problem = "";

    public WebsiteLink(Settings.Remote cfg, Runnable save, Secrets keyStore, SyncService sync, DashboardServer dashboard, DashboardAuth auth, String pcId,
                       RtcBridge rtc) {
        this.rtc = rtc;
        this.cfg = cfg;
        this.save = save;
        this.keyStore = keyStore;
        this.sync = sync;
        this.dashboard = dashboard;
        this.auth = auth;
        this.pcId = pcId;
        // Addresses can change (new Wi-Fi, DHCP): re-check now and then and rewrite the file only if they did.
        worker.scheduleWithFixedDelay(this::publishIfChanged, 5, 600, TimeUnit.SECONDS);
        // Connection requests from the website arrive in the mailbox file; a cheap metadata check every 2 s.
        worker.scheduleWithFixedDelay(this::checkSignal, 7, 2, TimeUnit.SECONDS);
    }

    public boolean on() { return cfg.website && !cfg.webKeyHash.isBlank(); }
    public String problem() { return problem; }
    public boolean published() { return !published.isEmpty(); }

    /** Turns website controls on: makes a key if needed, opens the dashboard to the home network and publishes the link. */
    public void enable() throws Exception {
        if (keyStore.load() == null || cfg.webKeyHash.isBlank()) newKey();
        cfg.website = true;
        cfg.enabled = true;
        if (!"public".equals(cfg.mode)) cfg.lan = true;
        save.run();
        if (dashboard.running()) dashboard.restart(); else dashboard.start();
        published = "";
        worker.execute(this::publishIfChanged);
    }

    public void disable() {
        cfg.website = false;
        save.run();
        auth.revokeWeb();
        published = "";
        worker.execute(() -> {
            try { sync.removeLink(pcId); } catch (Exception e) { LOG.info("website link: couldn't remove the Drive file: " + e.getMessage()); }
        });
    }

    /** New key: every website session is signed out and the website must re-read the file. */
    public void reset() throws Exception {
        newKey();
        save.run();
        auth.revokeWeb();
        published = "";
        worker.execute(this::publishIfChanged);
    }

    private void newKey() throws Exception {
        String key = Pkce.random(32);
        keyStore.save(key);
        cfg.webKeyHash = DashboardAuth.sha256(key);
    }

    /** The file's contents (without the time), or null when there's nothing to publish. */
    String linkJson() {
        String key = keyStore.load();
        if (!on() || key == null || !DashboardAuth.sha256(key).equals(cfg.webKeyHash) || !dashboard.running()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("pc", pcId);
        o.addProperty("name", java.util.Objects.requireNonNullElse(System.getenv("COMPUTERNAME"), "PC"));
        o.addProperty("key", key);
        o.addProperty("fingerprint", dashboard.fingerprint());
        JsonArray urls = new JsonArray();
        List<DashboardServer.Url> addresses = dashboard.addresses();
        for (DashboardServer.Url u : addresses) urls.add(u.url());
        o.add("urls", urls);
        o.addProperty("origin", cfg.webOrigin);
        if (!signalId.isEmpty()) o.addProperty("signal", signalId);
        return o.toString();
    }

    private void publishIfChanged() {
        try {
            String json;
            if (!on() || !sync.connected() || !dashboard.running()) return;
            if (signalId.isEmpty()) signalId = sync.ensureSignal(pcId);
            json = linkJson();
            if (json == null || json.equals(published)) return;
            sync.publishLink(pcId, json.getBytes(StandardCharsets.UTF_8));
            published = json;
            problem = "";
            LOG.info("website link: published to Google Drive");
        } catch (Exception e) {
            problem = "Couldn't save the link to Google Drive: " + e.getMessage();
            LOG.warning("website link: " + e);
        }
    }

    /** Answers a new WebRTC offer the website left in the mailbox (only with this PC's current link key). */
    private void checkSignal() {
        try {
            if (signalId.isEmpty() || !on() || !sync.connected()) return;
            String m = sync.modified(signalId);
            if (m.isEmpty()) { signalId = ""; published = ""; return; }      // deleted: made again on next publish
            if (m.equals(signalSeen)) return;
            signalSeen = m;
            JsonObject req = com.google.gson.JsonParser.parseString(sync.readText(signalId)).getAsJsonObject();
            if (!req.has("offer") || !req.has("id") || !req.has("key")) return;
            String id = req.get("id").getAsString();
            String key = keyStore.load();
            long age = System.currentTimeMillis() - (req.has("t") ? req.get("t").getAsLong() : 0);
            if (id.equals(lastOffer) || !id.matches("[A-Za-z0-9_-]{8,64}") || age > 120_000 || age < -120_000) return;
            if (key == null || !java.security.MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), req.get("key").getAsString().getBytes(StandardCharsets.UTF_8))) {
                LOG.warning("website link: ignored a connection request with the wrong key");
                return;
            }
            lastOffer = id;
            String answer = rtc.answer(id, req.get("offer").getAsString());
            JsonObject out = new JsonObject();
            out.addProperty("v", 1);
            out.addProperty("id", id);
            out.addProperty("answer", answer);
            sync.writeSignal(signalId, out.toString());
            signalSeen = sync.modified(signalId);
            LOG.info("website link: answered connection request " + id);
        } catch (Exception e) {
            LOG.info("website link: " + e.getMessage());
        }
    }

    public void shutdown() { worker.shutdownNow(); rtc.closeAll(); }
}
