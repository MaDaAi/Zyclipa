package io.zyclipra.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.zyclipra.core.Log;
import io.zyclipra.core.Settings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Google sign-in for installed apps: OAuth 2.0 authorization code flow with PKCE and a
 * loopback redirect (http://127.0.0.1:random-port). No client secret is shipped with
 * Zyclipra: the user creates their own free OAuth client (Desktop type) and pastes its id.
 * Scope is drive.file, so Zyclipra can only see files it created itself.
 */
public final class GoogleAuth {
    private static final Logger LOG = Log.get(GoogleAuth.class);
    public static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN = "https://oauth2.googleapis.com/token";
    public static final String REVOKE = "https://oauth2.googleapis.com/revoke";
    public static final String SCOPES = "https://www.googleapis.com/auth/drive.file openid email";

    private final Settings.Cloud cfg;
    private final SecretStore secrets;
    private final HttpClient http;
    private String accessToken;
    private long accessExpiry;
    private String tokenEndpoint = TOKEN;

    public GoogleAuth(Settings.Cloud cfg, SecretStore secrets, HttpClient http) {
        this.cfg = cfg;
        this.secrets = secrets;
        this.http = http;
    }

    /** For tests: point token refresh at a local fake server. */
    void tokenEndpoint(String url) { tokenEndpoint = url; }

    public boolean signedIn() { return secrets.load() != null; }

    /**
     * Opens the browser for consent and waits (up to 5 minutes) for Google to redirect back.
     * {@code openBrowser} receives the URL to show.
     */
    public CompletableFuture<String> signIn(Consumer<String> openBrowser) {
        return CompletableFuture.supplyAsync(() -> {
            if (cfg.clientId.isBlank()) throw new IllegalStateException("Enter your Google OAuth client ID first.");
            Pkce pkce = new Pkce();
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) { throw new IllegalStateException(e); }
            String redirect = "http://127.0.0.1:" + server.getAddress().getPort();
            CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
            server.createContext("/", ex -> {
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                boolean ok = q.containsKey("code") && pkce.state.equals(q.get("state"));
                byte[] body = ("<html><body style='font-family:sans-serif;background:#111;color:#eee;padding:40px'><h2>"
                        + (ok ? "Zyclipra is connected. You can close this tab." : "Sign-in was cancelled or failed.")
                        + "</h2></body></html>").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                callback.complete(q);
            });
            server.start();
            try {
                String url = AUTH + "?response_type=code&client_id=" + enc(cfg.clientId) + "&redirect_uri=" + enc(redirect)
                        + "&scope=" + enc(SCOPES) + "&code_challenge=" + pkce.challenge + "&code_challenge_method=S256"
                        + "&state=" + pkce.state + "&access_type=offline&prompt=consent";
                openBrowser.accept(url);
                Map<String, String> q = callback.get(5, TimeUnit.MINUTES);
                if (!pkce.state.equals(q.get("state"))) throw new IllegalStateException("Sign-in response did not match (state).");
                if (q.containsKey("error")) throw new IllegalStateException("Google said: " + q.get("error"));
                String form = "grant_type=authorization_code&code=" + enc(q.get("code")) + "&client_id=" + enc(cfg.clientId)
                        + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + pkce.verifier
                        + (cfg.clientSecret.isBlank() ? "" : "&client_secret=" + enc(cfg.clientSecret));
                JsonObject tok = post(tokenEndpoint, form);
                if (!tok.has("refresh_token")) throw new IllegalStateException("Google did not return a refresh token.");
                secrets.save(tok.get("refresh_token").getAsString());
                accessToken = tok.get("access_token").getAsString();
                accessExpiry = System.currentTimeMillis() + tok.get("expires_in").getAsLong() * 1000 - 60_000;
                String email = tok.has("id_token") ? emailFromIdToken(tok.get("id_token").getAsString()) : "";
                cfg.accountEmail = email;
                return email;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                server.stop(0);
            }
        });
    }

    /** A valid access token, refreshed when needed. */
    public synchronized String accessToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < accessExpiry) return accessToken;
        String refresh = secrets.load();
        if (refresh == null) throw new IOException("Not signed in to Google.");
        String form = "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(cfg.clientId)
                + (cfg.clientSecret.isBlank() ? "" : "&client_secret=" + enc(cfg.clientSecret));
        try {
            JsonObject tok = post(tokenEndpoint, form);
            accessToken = tok.get("access_token").getAsString();
            accessExpiry = System.currentTimeMillis() + tok.get("expires_in").getAsLong() * 1000 - 60_000;
            return accessToken;
        } catch (AuthException e) {
            if (e.status == 400 || e.status == 401) { secrets.clear(); cfg.accountEmail = ""; }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public synchronized void invalidate() { accessToken = null; }

    /** Revokes the token at Google and forgets it locally. */
    public void disconnect() throws IOException {
        String refresh = secrets.load();
        secrets.clear();
        accessToken = null;
        cfg.accountEmail = "";
        if (refresh != null) {
            try {
                http.send(HttpRequest.newBuilder(URI.create(REVOKE)).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("token=" + enc(refresh))).build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                LOG.info("Token revoke call failed (token is deleted locally anyway): " + e);
            }
        }
    }

    static final class AuthException extends IOException {
        final int status;
        AuthException(int status, String msg) { super(msg); this.status = status; }
    }

    private JsonObject post(String url, String form) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new AuthException(r.statusCode(), "Google token request failed (" + r.statusCode() + ")");
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    static String emailFromIdToken(String jwt) {
        try {
            String[] p = jwt.split("\\.");
            JsonObject o = JsonParser.parseString(new String(java.util.Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            return o.has("email") ? o.get("email").getAsString() : "";
        } catch (Exception e) { return ""; }
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
