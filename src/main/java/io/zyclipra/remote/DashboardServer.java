package io.zyclipra.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.zyclipra.cloud.SecretStore;
import io.zyclipra.core.Log;
import io.zyclipra.core.Settings;

import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Optional HTTPS dashboard, off by default. Who can reach it is decided by the hosting settings:
 * <ul>
 *   <li><b>Local</b> (default): this PC only – it listens on 127.0.0.1.</li>
 *   <li><b>+ Home network</b>: also devices on the LAN (10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA).</li>
 *   <li><b>+ Private VPN</b>: also devices on a Tailscale network (100.64/10, fd7a:115c:a1e0::/48).</li>
 *   <li><b>Public</b> (advanced): listens on 127.0.0.1 only, behind the owner's own HTTPS reverse proxy,
 *       and only after Zyclipra has reached itself through that proxy's public https:// address.</li>
 * </ul>
 * Connections from any other address are refused. It never opens router ports, uses UPnP/NAT-PMP or changes
 * the firewall. Every API call except sign-in needs a session; state-changing calls also need a custom header
 * and a same-origin Origin, which blocks cross-site request forgery.
 */
public final class DashboardServer {
    private static final Logger LOG = Log.get(DashboardServer.class);
    private static final int MAX_BODY = 4096;
    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "svg", "image/svg+xml", "png", "image/png");
    /** Media the clips page may send; anything else in the clips folder is never served. */
    private static final Map<String, String> MEDIA = Map.of("mp4", "video/mp4", "mov", "video/quicktime", "mkv", "video/x-matroska",
            "webm", "video/webm", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg");
    private static final java.util.regex.Pattern CLIP_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private final Settings.Remote cfg;
    private final DashboardAuth auth;
    private final RemoteControl control;
    private final Commands commands;
    private final Path keystore;
    private final SecretStore keystorePass;
    /** Random per run: lets "public" mode check that its https:// address really reaches this app. */
    private final String instanceId = UUID.randomUUID().toString();
    private HttpsServer server;
    private ExecutorService pool;
    private String fingerprint = "";
    private String certificatePem = "";
    private String lastError = "";

    public DashboardServer(Settings.Remote cfg, DashboardAuth auth, RemoteControl control, Path keystore, SecretStore keystorePass) {
        this.cfg = cfg;
        this.auth = auth;
        this.control = control;
        this.commands = new Commands(control);
        this.keystore = keystore;
        this.keystorePass = keystorePass;
    }

    public synchronized boolean running() { return server != null; }
    public String fingerprint() { return fingerprint; }
    /** The one command runner, shared with the WebRTC link so duplicate ids and rate limits apply across both. */
    public Commands commands() { return commands; }
    /** The dashboard's own certificate (public part only), for a reverse proxy that should trust it. */
    public String certificatePem() { return certificatePem; }
    public String instanceId() { return instanceId; }
    public String lastError() { return lastError; }
    public int port() { return server == null ? cfg.port : server.getAddress().getPort(); }

    /** True when the server must accept connections from other machines (home network or VPN). */
    boolean listensBeyondThisPc() { return !"public".equals(cfg.mode) && (cfg.lan || cfg.vpn); }

    public synchronized void start() throws Exception {
        if (server != null) return;
        lastError = "";
        if (!auth.configured()) {
            lastError = "Create the dashboard username and password first.";
            throw new IllegalStateException(lastError);
        }
        TlsCertificates.Tls tls = TlsCertificates.load(keystore, keystorePass);
        fingerprint = tls.fingerprint();
        certificatePem = tls.pem();
        InetAddress bind = listensBeyondThisPc() ? InetAddress.getByName("0.0.0.0") : InetAddress.getLoopbackAddress();
        HttpsServer s;
        try {
            s = HttpsServer.create(new InetSocketAddress(bind, cfg.port), 32);
        } catch (BindException e) {
            lastError = "Port " + cfg.port + " is already used by another program. Pick a different port and start again.";
            throw new IllegalStateException(lastError, e);
        }
        s.setHttpsConfigurator(new HttpsConfigurator(tls.context()) {
            @Override public void configure(HttpsParameters p) {
                SSLParameters sp = tls.context().getDefaultSSLParameters();
                sp.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                p.setSSLParameters(sp);
            }
        });
        s.createContext("/", this::handle);
        pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "dashboard");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(pool);
        s.start();
        server = s;
        LOG.info("Dashboard listening on " + bind.getHostAddress() + ":" + port() + " (mode " + cfg.mode + (cfg.lan ? ", home network" : "") + (cfg.vpn ? ", VPN" : "") + ")");
    }

    public synchronized void stop() {
        if (server != null) { server.stop(0); server = null; }
        if (pool != null) { pool.shutdownNow(); pool = null; }
    }

    public synchronized void restart() throws Exception {
        stop();
        start();
    }

    /** True if something else on this PC is already listening on {@code port}. */
    public static boolean portInUse(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    public record Url(String kind, String url) { }

    /** Addresses that really lead to the dashboard with the current settings (none are shown that aren't set up). */
    public List<Url> addresses() {
        List<Url> out = new ArrayList<>();
        out.add(new Url("This PC", "https://127.0.0.1:" + port()));
        if ("public".equals(cfg.mode)) {
            if (!cfg.publicUrl.isBlank() && cfg.publicVerifiedAt > 0) out.add(new Url("Internet (your reverse proxy)", cfg.publicUrl));
            return out;
        }
        if (!cfg.lan && !cfg.vpn) return out;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    if (cfg.lan && a.isSiteLocalAddress()) out.add(new Url("Home network", "https://" + a.getHostAddress() + ":" + port()));
                    else if (cfg.vpn && vpnAddress(a)) out.add(new Url("Tailscale (away from home)", "https://" + a.getHostAddress() + ":" + port()));
                }
            }
        } catch (IOException ignored) { }
        return out;
    }

    public List<String> urls() { return addresses().stream().map(u -> u.url() + "  (" + u.kind() + ")").toList(); }

    /** The Tailscale (100.64/10) addresses this PC has right now, if Tailscale is installed and connected. */
    public static List<String> tailscaleAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (vpnAddress(a)) out.add(a.getHostAddress());
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** Home-network ranges: RFC 1918, link-local and IPv6 unique-local (except Tailscale's own range). */
    static boolean lanAddress(InetAddress a) {
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc && !vpnAddress(a);
    }

    /** Tailscale's ranges: 100.64.0.0/10 and fd7a:115c:a1e0::/48. */
    static boolean vpnAddress(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        return (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0x5c && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
    }

    /** Any address that can only be this PC, a home network or a private VPN. */
    public static boolean privateAddress(InetAddress a) {
        return a != null && (a.isLoopbackAddress() || lanAddress(a) || vpnAddress(a));
    }

    /** Whether a connection from {@code a} is allowed with these settings. */
    public static boolean allowed(InetAddress a, Settings.Remote cfg) {
        if (a == null) return false;
        if (a.isLoopbackAddress()) return true;          // this PC, or the owner's reverse proxy on this PC
        if ("public".equals(cfg.mode)) return false;      // public mode serves only through the local proxy
        return (cfg.lan && lanAddress(a)) || (cfg.vpn && vpnAddress(a));
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            InetAddress remote = ex.getRemoteAddress().getAddress();
            if (!allowed(remote, cfg)) {
                LOG.warning("dashboard: refused a connection from " + remote.getHostAddress() + " (not allowed by the hosting settings)");
                send(ex, 403, json("error", "This device isn't allowed by Zyclipra's hosting settings."));
                return;
            }
            var h = ex.getResponseHeaders();
            h.add("X-Content-Type-Options", "nosniff");
            h.add("X-Frame-Options", "DENY");
            h.add("Referrer-Policy", "no-referrer");
            h.add("Strict-Transport-Security", "max-age=31536000");
            h.add("Cross-Origin-Opener-Policy", "same-origin");
            h.add("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            h.add("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self'; script-src 'self'; "
                    + "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
            String path = ex.getRequestURI().getPath();
            boolean web = fromWebsite(ex);
            if (web) { h.add("Access-Control-Allow-Origin", cfg.webOrigin); h.add("Vary", "Origin"); }
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                // CORS preflight from the hosted website only. Private-Network header: Chrome asks before a public
                // site may reach a device on the home network, and the device has to agree too.
                if (!web || !path.startsWith("/api/")) { send(ex, 403, json("error", "not allowed")); return; }
                h.add("Access-Control-Allow-Methods", "GET, POST");
                h.add("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Command-Id");
                h.add("Access-Control-Allow-Private-Network", "true");
                h.add("Access-Control-Max-Age", "600");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (path.startsWith("/api/")) { if (web) webApi(ex, path.substring(5)); else api(ex, path.substring(5)); }
            else asset(ex, path);
        } catch (SecurityException e) {
            send(ex, 403, json("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, json("error", e.getMessage()));
        } catch (IllegalStateException e) {
            send(ex, 409, json("error", e.getMessage()));
        } catch (Exception e) {
            LOG.warning("dashboard: " + e);
            send(ex, 500, json("error", "Something went wrong in Zyclipra. Check its log for details."));
        } finally {
            ex.close();
        }
    }

    private void asset(HttpExchange ex, String path) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, json("error", "method")); return; }
        String p = path.equals("/") ? "/index.html" : path;
        if (!p.matches("/[a-z0-9_-]+\\.(html|js|css|svg|png)")) { send(ex, 404, json("error", "not found")); return; }
        try (InputStream in = DashboardServer.class.getResourceAsStream("/web" + p)) {
            if (in == null) { send(ex, 404, json("error", "not found")); return; }
            byte[] b = in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", TYPES.get(p.substring(p.lastIndexOf('.') + 1)));
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        }
    }

    static String cookie(HttpExchange ex, String name) {
        for (String c : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : c.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        }
        return null;
    }

    /**
     * The address used for sign-in rate limiting. Behind the owner's reverse proxy every request comes
     * from 127.0.0.1, so in public mode (and only for loopback connections) the proxy's X-Forwarded-For
     * last hop is used instead; anyone else can't set it to dodge the limit because they can't connect.
     */
    private String clientAddress(HttpExchange ex) {
        InetAddress a = ex.getRemoteAddress().getAddress();
        if ("public".equals(cfg.mode) && a.isLoopbackAddress()) {
            String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (fwd != null) {
                String lastHop = fwd.substring(fwd.lastIndexOf(',') + 1).trim();
                if (lastHop.matches("[0-9a-fA-F:.]{2,45}")) return lastHop;
            }
        }
        return a.getHostAddress();
    }

    /** A request from the hosted website (exact origin match), allowed only while "website" is on. */
    private boolean fromWebsite(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return cfg.website && !cfg.webKeyHash.isBlank() && origin != null && origin.equals(cfg.webOrigin);
    }

    /**
     * The hosted website's API: it signs in with the link key it read from the owner's Google Drive and then
     * sends a bearer token (never a cookie, so other sites can't ride on it). It can only use the PC controls:
     * status, soundboard and the remote commands – not the account, sessions or settings.
     */
    private void webApi(HttpExchange ex, String route) throws IOException {
        String method = ex.getRequestMethod();
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        boolean post = "POST".equals(method);
        if (!post && !"GET".equals(method)) { send(ex, 405, json("error", "method")); return; }
        JsonObject body = post ? readBody(ex) : new JsonObject();
        if (route.equals("web/session") && post) {
            String token = auth.webLogin(clientAddress(ex), ex.getRequestHeaders().getFirst("User-Agent"), opt(body, "key"));
            if (token == null) {
                send(ex, auth.lastResult() == DashboardAuth.Result.LOCKED ? 429 : 401,
                        json("error", auth.lastResult() == DashboardAuth.Result.LOCKED ? "Too many attempts – wait a few minutes."
                                : "This PC's website link has changed. Reload the page."));
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("token", token);
            o.addProperty("pc", java.util.Objects.requireNonNullElse(System.getenv("COMPUTERNAME"), "PC"));
            send(ex, 200, o);
            return;
        }
        String auth0 = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth0 != null && auth0.startsWith("Bearer ") ? auth0.substring(7).trim() : null;
        String sessionId = auth.isWeb(token) ? auth.sessionId(token) : null;
        if (sessionId == null) { send(ex, 401, json("error", "Please reconnect.")); return; }
        if (post && Commands.isCommand(route) && !route.startsWith("cloud/")) {
            Commands.Reply r = commands.run(sessionId, ex.getRequestHeaders().getFirst("X-Command-Id"), route, body);
            send(ex, r.status(), r.body());
            return;
        }
        switch (method + " " + route) {
            case "GET status" -> send(ex, 200, control.status());
            case "GET sounds" -> send(ex, 200, control.sounds());
            case "POST web/logout" -> { auth.logout(token); send(ex, 200, json("ok", "true")); }
            default -> send(ex, 403, json("error", "Use the dashboard on the PC for that."));
        }
    }

    private void api(HttpExchange ex, String route) throws IOException {
        String method = ex.getRequestMethod();
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        boolean post = "POST".equals(method);
        if (post) {
            // CSRF defence: browsers can't add custom headers cross-site without CORS (which we never allow).
            if (!"1".equals(ex.getRequestHeaders().getFirst("X-Zyclipra"))) { send(ex, 403, json("error", "missing header")); return; }
            String origin = ex.getRequestHeaders().getFirst("Origin");
            String host = ex.getRequestHeaders().getFirst("Host");
            if (origin != null && host != null && !origin.equals("https://" + host)) { send(ex, 403, json("error", "bad origin")); return; }
        } else if (!"GET".equals(method)) { send(ex, 405, json("error", "method")); return; }

        if (route.equals("instance") && !post) { send(ex, 200, json("instance", instanceId)); return; }
        if (route.equals("setup") && !post) {
            JsonObject o = new JsonObject();
            o.addProperty("configured", auth.configured());
            send(ex, 200, o);
            return;
        }

        JsonObject body = post ? readBody(ex) : new JsonObject();

        if (route.equals("login") && post) {
            String token = auth.login(clientAddress(ex), ex.getRequestHeaders().getFirst("User-Agent"), opt(body, "username"), opt(body, "password"));
            if (token == null) {
                DashboardAuth.Result r = auth.lastResult();
                switch (r) {
                    case LOCKED -> send(ex, 429, json("error", "Too many attempts – wait a few minutes and try again."));
                    case NOT_SET_UP -> send(ex, 503, json("error", "Finish setting up the dashboard in the Zyclipra app first (Settings › Hosting)."));
                    default -> send(ex, 401, json("error", "Wrong username or password."));
                }
                return;
            }
            ex.getResponseHeaders().add("Set-Cookie", "zs=" + token + "; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=604800");
            send(ex, 200, json("ok", "true"));
            return;
        }
        String token = cookie(ex, "zs");
        String sessionId = auth.sessionId(token);
        if (sessionId == null) { send(ex, 401, json("error", auth.configured() ? "Please sign in." : "Finish setting up the dashboard in the Zyclipra app first.")); return; }

        // Remote commands: same rules for every dashboard (real results, duplicate ids answered once, sound rate limit).
        if (post && Commands.isCommand(route)) {
            Commands.Reply r = commands.run(sessionId, ex.getRequestHeaders().getFirst("X-Command-Id"), route, body);
            send(ex, r.status(), r.body());
            return;
        }

        switch (method + " " + route) {
            case "GET status" -> send(ex, 200, control.status());
            case "GET clips" -> send(ex, 200, control.clips("all".equals(query(ex, "limit")) ? 100_000 : 24));
            case "GET clip/thumb" -> serveFile(ex, control.clipThumbnail(clipId(ex)).orElse(null), false);
            case "GET clip/media" -> serveFile(ex, control.clipFile(clipId(ex)).orElse(null), "1".equals(query(ex, "download")));
            case "GET levels" -> send(ex, 200, control.levels());
            case "GET mixer" -> send(ex, 200, control.mixer());
            case "GET sounds" -> send(ex, 200, control.sounds());
            case "GET sounds/outputs" -> send(ex, 200, control.soundOutputs());

            // ---- account & security
            case "POST logout" -> { auth.logout(token); send(ex, 200, json("ok", "true")); }
            case "GET account" -> send(ex, 200, account(sessionId));
            case "POST account/password" -> {
                auth.changePassword(token, opt(body, "current"), opt(body, "password"), opt(body, "confirm"));
                send(ex, 200, json("ok", "Password changed. Other browsers were signed out."));
            }
            case "POST account/username" -> {
                auth.changeUsername(opt(body, "current"), opt(body, "username"));
                send(ex, 200, json("ok", "Username changed."));
            }
            case "POST account/revoke" -> {
                String id = str(body, "id");
                if (id.equals(sessionId)) throw new IllegalArgumentException("Use Sign out to end this session.");
                boolean ended = auth.revoke(id);
                send(ex, ended ? 200 : 404, json(ended ? "ok" : "error", ended ? "That session was signed out." : "That session had already ended."));
            }

            // ---- Google Drive (the desktop app keeps the Google sign-in; the dashboard never sees tokens)
            case "GET cloud" -> send(ex, 200, control.cloudStatus());
            case "GET cloud/avatar" -> serveFile(ex, control.cloudAvatar().orElse(null), false);
            default -> send(ex, 404, json("error", "unknown endpoint"));
        }
    }

    private JsonObject account(String currentSession) {
        JsonObject o = new JsonObject();
        o.addProperty("username", auth.username());
        JsonArray list = new JsonArray();
        for (DashboardAuth.SessionInfo s : auth.sessions()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", s.id());
            j.addProperty("created", s.created());
            j.addProperty("lastSeen", s.lastSeen());
            j.addProperty("address", s.address());
            j.addProperty("agent", s.agent());
            j.addProperty("current", s.id().equals(currentSession));
            list.add(j);
        }
        o.add("sessions", list);
        JsonObject hosting = new JsonObject();
        hosting.addProperty("mode", cfg.mode);
        hosting.addProperty("lan", cfg.lan);
        hosting.addProperty("vpn", cfg.vpn);
        hosting.addProperty("https", true);
        hosting.addProperty("remote", "public".equals(cfg.mode) || cfg.lan || cfg.vpn);
        hosting.addProperty("publicUrl", "public".equals(cfg.mode) && cfg.publicVerifiedAt > 0 ? cfg.publicUrl : "");
        o.add("hosting", hosting);
        o.addProperty("minPasswordLength", auth.minPasswordLength());
        return o;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.length() > 300) return null;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Clip ids are short letters/digits only; anything else (paths, "..", slashes) is refused before any lookup. */
    private static String clipId(HttpExchange ex) { return checkId(query(ex, "id")); }

    private static String checkId(String id) {
        if (id == null || !CLIP_ID.matcher(id).matches()) throw new IllegalArgumentException("bad clip id");
        return id;
    }

    /**
     * Sends a clip or picture. Supports byte ranges so the browser's video player can seek, and streams in
     * chunks so big clips don't fill memory. Only known media types are sent.
     */
    private static void serveFile(HttpExchange ex, Path file, boolean download) throws IOException {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) { send(ex, 404, json("error", "not found")); return; }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "";
        String type = MEDIA.get(ext);
        if (type == null) { send(ex, 404, json("error", "not found")); return; }
        long size = java.nio.file.Files.size(file), start = 0, end = size - 1;
        String range = ex.getRequestHeaders().getFirst("Range");
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String[] r = range.substring(6).split("-", 2);
            try {
                if (r[0].isEmpty()) start = Math.max(0, size - Long.parseLong(r[1]));
                else { start = Long.parseLong(r[0]); if (r.length > 1 && !r[1].isEmpty()) end = Math.min(size - 1, Long.parseLong(r[1])); }
            } catch (NumberFormatException e) { start = 0; end = size - 1; }
            if (start > end || start >= size) {
                ex.getResponseHeaders().add("Content-Range", "bytes */" + size);
                ex.sendResponseHeaders(416, -1);
                return;
            }
            partial = true;
        }
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Accept-Ranges", "bytes");
        h.set("Cache-Control", "private, no-store");
        if (download) {
            String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_");
            h.set("Content-Disposition", "attachment; filename=\"" + safe + "\"");
        }
        if (partial) h.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
        long len = end - start + 1;
        ex.sendResponseHeaders(partial ? 206 : 200, len);
        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ);
             var out = ex.getResponseBody()) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64 * 1024);
            long pos = start, left = len;
            while (left > 0) {
                buf.clear();
                if (left < buf.capacity()) buf.limit((int) left);
                int n = ch.read(buf, pos);
                if (n <= 0) break;
                out.write(buf.array(), 0, n);
                pos += n;
                left -= n;
            }
        } catch (IOException e) {
            // The browser often stops reading mid-file (seeking, closing the player); that's normal.
        }
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) throw new IllegalArgumentException(k + " is required");
        String s = o.get(k).getAsString();
        if (s.length() > 100) throw new IllegalArgumentException(k + " too long");
        return s;
    }

    /** Optional string field (passwords may be up to 200 characters). */
    private static String opt(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) return null;
        String s = o.get(k).getAsString();
        if (s.length() > 200) throw new IllegalArgumentException(k + " too long");
        return s;
    }

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new IllegalArgumentException("request too large");
        if (b.length == 0) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) throw new IllegalArgumentException("expected a JSON object");
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON");
        }
    }

    static JsonObject json(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    private static void send(HttpExchange ex, int code, JsonElement body) throws IOException {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }
}
