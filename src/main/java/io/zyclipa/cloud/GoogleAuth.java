package io.zyclipa.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.zyclipa.core.Log;
import io.zyclipa.core.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Google sign-in for installed apps: OAuth 2.0 authorization code flow with PKCE (S256), opened in the
 * user's own browser, with a one-time loopback redirect (http://127.0.0.1:random-port). The app's own
 * "Desktop app" OAuth client is built in ({@link GoogleClient}); users just choose their account and allow access.
 * Scopes: openid, email, profile (name and picture) and drive.file (only files Zyclipa made itself).
 * The refresh token is kept in Windows Credential Manager; access tokens live only in memory.
 */
public final class GoogleAuth {
    private static final Logger LOG = Log.get(GoogleAuth.class);
    public static final String SCOPES = "openid email profile https://www.googleapis.com/auth/drive.file";

    private final Settings.Cloud cfg;
    private final Secrets secrets;
    private final HttpClient http;
    private final Path avatarFile;
    private volatile GoogleClient client;
    private Runnable changed = () -> { };
    private String accessToken;
    private long accessExpiry;
    String authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
    String tokenEndpoint = "https://oauth2.googleapis.com/token";
    String userInfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo";
    String revokeEndpoint = "https://oauth2.googleapis.com/revoke";

    public GoogleAuth(Settings.Cloud cfg, Secrets secrets, HttpClient http, Path avatarFile) {
        this.cfg = cfg;
        this.secrets = secrets;
        this.http = http;
        this.avatarFile = avatarFile;
        this.client = GoogleClient.load(cfg);
    }

    /** The OAuth client in use (never shown to users). */
    public GoogleClient client() { return client; }

    /** Tests: use a specific client. */
    public void client(GoogleClient c) { client = c; }

    /** Whether this build can sign in to Google at all. */
    public boolean available() { return client.available(); }

    /** For tests: point every Google endpoint at a local fake. */
    public void endpoints(String base) {
        authEndpoint = base + "/auth";
        tokenEndpoint = base + "/token";
        userInfoEndpoint = base + "/userinfo";
        revokeEndpoint = base + "/revoke";
    }

    /** Called (on a background thread) whenever the sign-in state or profile changes. */
    public void onChange(Runnable r) { changed = r; }

    public boolean signedIn() { return secrets.load() != null; }
    public Path avatar() { return avatarFile; }

    /**
     * Opens the browser for consent and waits (up to 5 minutes) for Google to redirect back.
     * {@code openBrowser} receives the URL to show. Completes with the account's email.
     */
    public CompletableFuture<String> signIn(Consumer<String> openBrowser) {
        return CompletableFuture.supplyAsync(() -> {
            GoogleClient gc = client;
            if (!gc.available()) throw new IllegalStateException("This copy of Zyclipa was built without Google sign-in, so Google Drive isn't available.");
            Pkce pkce = new Pkce();
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) { throw new IllegalStateException(e); }
            String redirect = "http://127.0.0.1:" + server.getAddress().getPort();
            CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
            server.createContext("/", ex -> {
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                // Only the redirect itself counts (not e.g. the browser asking for /favicon.ico).
                boolean redirectHit = "/".equals(ex.getRequestURI().getPath()) && (q.containsKey("code") || q.containsKey("error"));
                boolean ok = redirectHit && q.containsKey("code") && pkce.state.equals(q.get("state"));
                byte[] body = ("<!doctype html><meta charset=utf-8><title>Zyclipa</title><body style='font-family:Segoe UI,sans-serif;background:#1E2228;color:#E6E8EC;padding:40px'><h2>"
                        + (ok ? "Zyclipa is connected to Google. You can close this tab." : "Sign-in was cancelled or didn't match – go back to Zyclipa and try again.")
                        + "</h2></body>").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.sendResponseHeaders(redirectHit ? 200 : 404, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                if (redirectHit) callback.complete(q);
            });
            server.start();
            try {
                String url = authEndpoint + "?response_type=code&client_id=" + enc(gc.clientId()) + "&redirect_uri=" + enc(redirect)
                        + "&scope=" + enc(SCOPES) + "&code_challenge=" + pkce.challenge + "&code_challenge_method=S256"
                        + "&state=" + pkce.state + "&access_type=offline&prompt=consent";
                openBrowser.accept(url);
                Map<String, String> q = callback.get(5, TimeUnit.MINUTES);
                if (q.containsKey("error")) throw new IllegalStateException(q.get("error").equals("access_denied")
                        ? "You didn't allow access in Google's window." : "Google said: " + q.get("error"));
                if (!pkce.state.equals(q.get("state"))) throw new IllegalStateException("The sign-in reply didn't match this request, so it was ignored. Try again.");
                String form = "grant_type=authorization_code&code=" + enc(q.get("code")) + "&client_id=" + enc(gc.clientId())
                        + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + pkce.verifier
                        + (gc.clientSecret().isBlank() ? "" : "&client_secret=" + enc(gc.clientSecret()));
                JsonObject tok = post(tokenEndpoint, form);
                if (!tok.has("refresh_token")) throw new IllegalStateException("Google didn't return a refresh token. Remove Zyclipa at myaccount.google.com › Security › Third-party access, then connect again.");
                if (!grantedDrive(tok)) throw new IllegalStateException("Google Drive access wasn't allowed. Connect again and tick the Google Drive box.");
                secrets.save(tok.get("refresh_token").getAsString());
                synchronized (this) {
                    accessToken = tok.get("access_token").getAsString();
                    accessExpiry = System.currentTimeMillis() + tok.get("expires_in").getAsLong() * 1000 - 60_000;
                }
                cfg.needsReauth = false;
                loadProfile();
                changed.run();
                return cfg.accountEmail;
            } catch (RuntimeException e) {
                throw e;
            } catch (java.util.concurrent.TimeoutException e) {
                throw new IllegalStateException("Nothing came back from Google within 5 minutes. Try again.");
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                server.stop(0);
            }
        });
    }

    private static boolean grantedDrive(JsonObject tok) {
        return !tok.has("scope") || tok.get("scope").getAsString().contains("drive.file");
    }

    /** Reads name, email and picture from Google (OpenID Connect userinfo) and caches the picture. */
    public void loadProfile() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(userInfoEndpoint)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + accessToken()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { LOG.info("Profile request: " + r.statusCode()); return; }
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            cfg.accountEmail = str(o, "email");
            cfg.accountName = str(o, "name");
            String pic = str(o, "picture");
            if (avatarFile != null) {
                Files.deleteIfExists(avatarFile);
                URI u = pic.isBlank() ? null : URI.create(pic);
                // Only Google's own picture host over HTTPS (or the local test fake), and at most 1 MB.
                if (u != null && (("https".equals(u.getScheme()) && u.getHost() != null && u.getHost().endsWith(".googleusercontent.com"))
                        || userInfoEndpoint.startsWith("http://127.0.0.1"))) {
                    HttpResponse<InputStream> img = http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream in = img.body()) {
                        byte[] b = in.readNBytes(1_000_001);
                        String type = img.headers().firstValue("Content-Type").orElse("");
                        // Google serves photos as JPEG and the default letter avatars as PNG; keep a JPEG copy either way.
                        if (img.statusCode() == 200 && b.length <= 1_000_000 && type.startsWith("image/")) saveAsJpeg(b);
                    }
                }
            }
        } catch (Exception e) {
            LOG.info("Couldn't load the Google profile: " + e.getMessage());
        }
    }

    private void saveAsJpeg(byte[] picture) throws IOException {
        java.awt.image.BufferedImage in = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(picture));
        if (in == null || in.getWidth() > 2048 || in.getHeight() > 2048) return; // not a picture Java can read, or absurdly large
        java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(in.getWidth(), in.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        g.setColor(new java.awt.Color(0x2B, 0x30, 0x39)); // card colour behind any transparency
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(in, 0, 0, null);
        g.dispose();
        Path tmp = avatarFile.resolveSibling(avatarFile.getFileName() + ".tmp");
        if (javax.imageio.ImageIO.write(rgb, "jpg", tmp.toFile())) Files.move(tmp, avatarFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        else Files.deleteIfExists(tmp);
    }

    private static String str(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; }

    /** A valid access token, refreshed when needed. */
    public synchronized String accessToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < accessExpiry) return accessToken;
        String refresh = secrets.load();
        if (refresh == null) throw new AuthException(0, cfg.needsReauth ? "Google needs you to connect again (Settings › Cloud & Sync)." : "Not connected to Google.");
        GoogleClient gc = client;
        String form = "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(gc.clientId())
                + (gc.clientSecret().isBlank() ? "" : "&client_secret=" + enc(gc.clientSecret()));
        try {
            JsonObject tok = post(tokenEndpoint, form);
            accessToken = tok.get("access_token").getAsString();
            accessExpiry = System.currentTimeMillis() + tok.get("expires_in").getAsLong() * 1000 - 60_000;
            return accessToken;
        } catch (AuthException e) {
            if (e.status == 400 || e.status == 401) {
                // Revoked, expired or the OAuth client changed: forget the token, keep the name so the UI can say who to reconnect.
                secrets.clear();
                cfg.needsReauth = true;
                changed.run();
                throw new AuthException(e.status, "Google needs you to connect again (the saved sign-in was revoked or expired).");
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public synchronized void invalidate() { accessToken = null; }

    /** Revokes the token at Google and forgets it and the profile locally. Cloud files and local clips are untouched. */
    public void disconnect() throws IOException {
        String refresh = secrets.load();
        secrets.clear();
        synchronized (this) { accessToken = null; }
        cfg.accountEmail = "";
        cfg.accountName = "";
        cfg.needsReauth = false;
        if (avatarFile != null) Files.deleteIfExists(avatarFile);
        if (refresh != null) {
            try {
                http.send(HttpRequest.newBuilder(URI.create(revokeEndpoint)).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("token=" + enc(refresh))).build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                LOG.info("Token revoke call failed (the token is deleted on this PC anyway): " + e.getMessage());
            }
        }
        changed.run();
    }

    public static final class AuthException extends IOException {
        public final int status;
        AuthException(int status, String msg) { super(msg); this.status = status; }
    }

    private JsonObject post(String url, String form) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            // Google's error codes (e.g. invalid_grant, invalid_client) help; the response never contains tokens on failure.
            String code = "";
            try { code = JsonParser.parseString(r.body()).getAsJsonObject().get("error").getAsString(); } catch (Exception ignored) { }
            throw new AuthException(r.statusCode(), "Google sign-in request failed (" + r.statusCode() + (code.isBlank() ? "" : ", " + code) + ")"
                    + (code.equals("invalid_client") ? " – Zyclipa's Google sign-in key was rejected; it may have been deleted in Google Cloud." : ""));
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
