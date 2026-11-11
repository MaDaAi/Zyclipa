package io.zyclipra.remote;

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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Optional HTTPS dashboard. Off by default; when on it listens on 127.0.0.1 only unless
 * "allow other devices on my network" is ticked. It never opens router ports (no UPnP).
 * Every API call needs a session from the pairing-code login; state-changing calls also need
 * a custom header and same-origin, which blocks cross-site request forgery.
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
    private final Path keystore;
    private final SecretStore keystorePass;
    private HttpsServer server;
    private String fingerprint = "";

    public DashboardServer(Settings.Remote cfg, DashboardAuth auth, RemoteControl control, Path keystore, SecretStore keystorePass) {
        this.cfg = cfg;
        this.auth = auth;
        this.control = control;
        this.keystore = keystore;
        this.keystorePass = keystorePass;
    }

    public synchronized boolean running() { return server != null; }
    public String fingerprint() { return fingerprint; }
    public int port() { return server == null ? cfg.port : server.getAddress().getPort(); }

    public synchronized void start() throws Exception {
        if (server != null) return;
        TlsCertificates.Tls tls = TlsCertificates.load(keystore, keystorePass);
        fingerprint = tls.fingerprint();
        InetAddress bind = cfg.lan ? InetAddress.getByName("0.0.0.0") : InetAddress.getLoopbackAddress();
        HttpsServer s = HttpsServer.create(new InetSocketAddress(bind, cfg.port), 32);
        s.setHttpsConfigurator(new HttpsConfigurator(tls.context()) {
            @Override public void configure(HttpsParameters p) {
                SSLParameters sp = tls.context().getDefaultSSLParameters();
                sp.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                p.setSSLParameters(sp);
            }
        });
        s.createContext("/", this::handle);
        s.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "dashboard");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server = s;
        LOG.info("Dashboard listening on " + bind.getHostAddress() + ":" + port());
    }

    public synchronized void stop() {
        if (server != null) { server.stop(0); server = null; }
    }

    /** Addresses people can type into a phone browser. */
    public List<String> urls() {
        List<String> out = new ArrayList<>();
        out.add("https://localhost:" + port());
        if (!cfg.lan) return out;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) out.add("https://" + a.getHostAddress() + ":" + port());
            }
        } catch (IOException ignored) { }
        return out;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            var h = ex.getResponseHeaders();
            h.add("X-Content-Type-Options", "nosniff");
            h.add("X-Frame-Options", "DENY");
            h.add("Referrer-Policy", "no-referrer");
            h.add("Strict-Transport-Security", "max-age=31536000");
            h.add("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'");
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path.substring(5));
            else asset(ex, path);
        } catch (IllegalArgumentException e) {
            send(ex, 400, json("error", e.getMessage()));
        } catch (Exception e) {
            LOG.warning("dashboard: " + e);
            send(ex, 500, json("error", "internal error"));
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

        JsonObject body = post ? readBody(ex) : new JsonObject();
        String addr = ex.getRemoteAddress().getAddress().getHostAddress();

        if (route.equals("login") && post) {
            String code = body.has("code") ? body.get("code").getAsString() : "";
            String token = auth.login(addr, code);
            if (token == null) {
                boolean locked = auth.lastResult() == DashboardAuth.Result.LOCKED;
                send(ex, locked ? 429 : 401, json("error", locked ? "Too many attempts - try again later." : "Wrong pairing code."));
                return;
            }
            ex.getResponseHeaders().add("Set-Cookie", "zs=" + token + "; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=43200");
            send(ex, 200, json("ok", "true"));
            return;
        }
        String token = cookie(ex, "zs");
        if (!auth.valid(token)) { send(ex, 401, json("error", "Sign in with the pairing code.")); return; }

        switch (method + " " + route) {
            case "GET status" -> send(ex, 200, control.status());
            case "GET clips" -> send(ex, 200, control.clips("all".equals(query(ex, "limit")) ? 100_000 : 24));
            case "GET clip/thumb" -> serveFile(ex, control.clipThumbnail(clipId(ex)).orElse(null), false);
            case "GET clip/media" -> serveFile(ex, control.clipFile(clipId(ex)).orElse(null), "1".equals(query(ex, "download")));
            case "GET levels" -> send(ex, 200, control.levels());
            case "GET mixer" -> send(ex, 200, control.mixer());
            case "GET sounds" -> send(ex, 200, control.sounds());
            case "POST recording/toggle" -> { control.toggleRecording(); send(ex, 200, json("ok", "true")); }
            case "POST replay/toggle" -> { control.toggleReplay(); send(ex, 200, json("ok", "true")); }
            case "POST replay/save" -> { control.saveReplay(); send(ex, 200, json("ok", "true")); }
            case "POST screenshot" -> { control.screenshot(); send(ex, 200, json("ok", "true")); }
            case "POST sounds/stop" -> { control.stopSounds(); send(ex, 200, json("ok", "true")); }
            case "POST logout" -> { auth.logout(token); send(ex, 200, json("ok", "true")); }
            case "POST mixer" -> {
                String id = str(body, "channel");
                double v = body.has("volume") ? body.get("volume").getAsDouble() : -1;
                if (!(v >= 0 && v <= 1)) throw new IllegalArgumentException("volume must be 0..1");
                control.setChannel(id, v, body.has("muted") && body.get("muted").getAsBoolean());
                send(ex, 200, json("ok", "true"));
            }
            case "POST sounds/play" -> { control.playSound(str(body, "id")); send(ex, 200, json("ok", "true")); }
            default -> send(ex, 404, json("error", "unknown endpoint"));
        }
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
    private static String clipId(HttpExchange ex) {
        String id = query(ex, "id");
        if (id == null || !CLIP_ID.matcher(id).matches()) throw new IllegalArgumentException("bad clip id");
        return id;
    }

    /**
     * Sends a clip or thumbnail. Supports byte ranges so the browser's video player can seek, and streams in
     * chunks so big clips don't fill memory. Only known media types are sent.
     */
    private static void serveFile(HttpExchange ex, Path file, boolean download) throws IOException {
        if (file == null) { send(ex, 404, json("error", "not found")); return; }
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
        if ("HEAD".equals(ex.getRequestMethod())) return;
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

    private static JsonObject json(String k, String v) {
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
