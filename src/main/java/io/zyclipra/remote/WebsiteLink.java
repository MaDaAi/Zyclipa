package io.zyclipra.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.zyclipra.cloud.Pkce;
import io.zyclipra.cloud.Secrets;
import io.zyclipra.cloud.SyncService;
import io.zyclipra.core.Settings;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Lets the hosted Zyclipra website (GitHub Pages) use this PC's controls while you're on the same network.
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
    private volatile String problem = "";

    public WebsiteLink(Settings.Remote cfg, Runnable save, Secrets keyStore, SyncService sync, DashboardServer dashboard, DashboardAuth auth, String pcId) {
        this.cfg = cfg;
        this.save = save;
        this.keyStore = keyStore;
        this.sync = sync;
        this.dashboard = dashboard;
        this.auth = auth;
        this.pcId = pcId;
        // Addresses can change (new Wi-Fi, DHCP): re-check now and then and rewrite the file only if they did.
        worker.scheduleWithFixedDelay(this::publishIfChanged, 5, 600, TimeUnit.SECONDS);
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
        return o.toString();
    }

    private void publishIfChanged() {
        try {
            String json = linkJson();
            if (json == null || json.equals(published) || !sync.connected()) return;
            sync.publishLink(pcId, json.getBytes(StandardCharsets.UTF_8));
            published = json;
            problem = "";
            LOG.info("website link: published to Google Drive");
        } catch (Exception e) {
            problem = "Couldn't save the link to Google Drive: " + e.getMessage();
            LOG.warning("website link: " + e);
        }
    }

    public void shutdown() { worker.shutdownNow(); }
}
