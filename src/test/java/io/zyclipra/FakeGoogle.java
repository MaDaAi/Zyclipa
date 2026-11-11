package io.zyclipra;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small local stand-in for the parts of Google's OAuth, userinfo and Drive v3 APIs Zyclipra uses.
 * It is a test double: it checks the bearer token, keeps files in memory and can be told to fail.
 * Nothing here talks to Google.
 */
final class FakeGoogle implements AutoCloseable {
    static final class File {
        String id, name, mime, parent;
        Map<String, String> app = new LinkedHashMap<>();
        byte[] data = new byte[0];
        boolean trashed;
    }

    private static final class Session {
        String name, parent, mime;
        Map<String, String> app = new LinkedHashMap<>();
        long total;
        ByteArrayOutputStream got = new ByteArrayOutputStream();
    }

    final HttpServer server;
    final String base;
    final Map<String, File> files = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    final AtomicInteger ids = new AtomicInteger(), uploadsStarted = new AtomicInteger(), revoked = new AtomicInteger(), tokenCalls = new AtomicInteger();
    /** Next N upload chunks answer 503 (a dropped/overloaded server). */
    final AtomicInteger failChunks = new AtomicInteger();
    /** When true the token endpoint says the refresh token was revoked. */
    volatile boolean revokeGrant;
    volatile String lastAuthCode;

    FakeGoogle() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", this::token);
        server.createContext("/userinfo", ex -> {
            if (!authorized(ex)) return;
            JsonObject o = new JsonObject();
            o.addProperty("email", "player@example.com");
            o.addProperty("name", "Test Player");
            o.addProperty("picture", base + "/avatar.jpg");
            send(ex, 200, o.toString());
        });
        server.createContext("/avatar.jpg", ex -> {
            // Like Google's default letter avatar: a PNG with transparency.
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(48, 48, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(10, 10, 0xFF4285F4);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", png);
            byte[] b = png.toByteArray();
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/revoke", ex -> { revoked.incrementAndGet(); send(ex, 200, "{}"); });
        server.createContext("/drive/v3/files", this::files);
        server.createContext("/upload/drive/v3/files", this::startUpload);
        server.createContext("/session/", this::chunk);
        server.start();
    }

    @Override public void close() { server.stop(0); }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void token(HttpExchange ex) throws IOException {
        tokenCalls.incrementAndGet();
        Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if ("authorization_code".equals(f.get("grant_type"))) {
            lastAuthCode = f.get("code");
            if (f.get("code_verifier") == null || f.get("code_verifier").length() < 43) { send(ex, 400, "{\"error\":\"invalid_request\"}"); return; }
            send(ex, 200, "{\"access_token\":\"access-1\",\"expires_in\":3600,\"refresh_token\":\"refresh-1\",\"scope\":\"openid email profile https://www.googleapis.com/auth/drive.file\"}");
        } else if ("refresh_token".equals(f.get("grant_type"))) {
            if (revokeGrant || !"refresh-1".equals(f.get("refresh_token"))) { send(ex, 400, "{\"error\":\"invalid_grant\"}"); return; }
            send(ex, 200, "{\"access_token\":\"access-1\",\"expires_in\":3600}");
        } else send(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
    }

    private boolean authorized(HttpExchange ex) throws IOException {
        if ("Bearer access-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) return true;
        send(ex, 401, "{\"error\":{\"code\":401}}");
        return false;
    }

    static JsonObject json(File f) {
        JsonObject o = new JsonObject();
        o.addProperty("id", f.id);
        o.addProperty("name", f.name);
        o.addProperty("size", String.valueOf(f.data.length));
        o.addProperty("trashed", f.trashed);
        if (!"application/vnd.google-apps.folder".equals(f.mime)) o.addProperty("md5Checksum", md5(f.data));
        JsonObject ap = new JsonObject();
        f.app.forEach(ap::addProperty);
        o.add("appProperties", ap);
        return o;
    }

    static String md5(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final Pattern NAME = Pattern.compile("name='([^']*)'"), PARENT = Pattern.compile("'([^']+)' in parents"),
            APP = Pattern.compile("value='([^']*)'");

    private void files(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        String path = ex.getRequestURI().getPath();
        String rawQuery = ex.getRequestURI().getRawQuery();
        Map<String, String> qp = rawQuery == null ? Map.of() : form(rawQuery);
        String method = ex.getRequestMethod();
        if (path.equals("/drive/v3/files") && method.equals("GET")) {
            String q = qp.getOrDefault("q", "");
            JsonArray arr = new JsonArray();
            for (File f : files.values()) {
                if (q.contains("trashed=false") && f.trashed) continue;
                if (q.contains("mimeType='application/vnd.google-apps.folder'") && !"application/vnd.google-apps.folder".equals(f.mime)) continue;
                Matcher n = NAME.matcher(q);
                if (n.find() && !n.group(1).equals(f.name)) continue;
                Matcher p = PARENT.matcher(q);
                if (p.find() && !p.group(1).equals(f.parent == null ? "root" : f.parent)) continue;
                if (q.contains("appProperties has")) { Matcher a = APP.matcher(q); if (!a.find() || !a.group(1).equals(f.app.get("zyclipraId"))) continue; }
                arr.add(json(f));
            }
            JsonObject o = new JsonObject();
            o.add("files", arr);
            send(ex, 200, o.toString());
        } else if (path.equals("/drive/v3/files") && method.equals("POST")) {
            JsonObject meta = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            File f = new File();
            f.id = "f" + ids.incrementAndGet();
            f.name = meta.get("name").getAsString();
            f.mime = meta.has("mimeType") ? meta.get("mimeType").getAsString() : "";
            f.parent = meta.has("parents") ? meta.getAsJsonArray("parents").get(0).getAsString() : null;
            files.put(f.id, f);
            send(ex, 200, "{\"id\":\"" + f.id + "\"}");
        } else {
            String id = path.substring("/drive/v3/files/".length());
            File f = files.get(id);
            if (f == null) { send(ex, 404, "{\"error\":{\"code\":404}}"); return; }
            if (method.equals("DELETE")) { files.remove(id); ex.sendResponseHeaders(204, -1); ex.close(); return; }
            if ("media".equals(qp.get("alt"))) {
                String range = ex.getRequestHeaders().getFirst("Range");
                int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
                byte[] part = java.util.Arrays.copyOfRange(f.data, start, f.data.length);
                ex.sendResponseHeaders(range == null ? 200 : 206, part.length);
                ex.getResponseBody().write(part);
                ex.close();
                return;
            }
            send(ex, 200, json(f).toString());
        }
    }

    private void startUpload(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        JsonObject meta = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        Session s = new Session();
        s.name = meta.get("name").getAsString();
        s.parent = meta.getAsJsonArray("parents").get(0).getAsString();
        s.mime = ex.getRequestHeaders().getFirst("X-Upload-Content-Type");
        s.total = Long.parseLong(ex.getRequestHeaders().getFirst("X-Upload-Content-Length"));
        if (meta.has("appProperties")) for (var e : meta.getAsJsonObject("appProperties").entrySet()) s.app.put(e.getKey(), e.getValue().getAsString());
        String sid = "s" + uploadsStarted.incrementAndGet();
        sessions.put(sid, s);
        ex.getResponseHeaders().add("Location", base + "/session/" + sid);
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    private void chunk(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        String sid = ex.getRequestURI().getPath().substring("/session/".length());
        Session s = sessions.get(sid);
        byte[] body = ex.getRequestBody().readAllBytes();
        if (s == null) { send(ex, 404, "{}"); return; }
        String range = ex.getRequestHeaders().getFirst("Content-Range");
        if (range.startsWith("bytes */")) {
            if (s.got.size() > 0) ex.getResponseHeaders().add("Range", "bytes=0-" + (s.got.size() - 1));
            ex.sendResponseHeaders(308, -1);
            ex.close();
            return;
        }
        if (failChunks.get() > 0 && failChunks.getAndDecrement() > 0) { send(ex, 503, "{\"error\":{\"code\":503}}"); return; }
        long start = Long.parseLong(range.substring(6, range.indexOf('-')));
        if (start != s.got.size()) { send(ex, 400, "{}"); return; }
        s.got.write(body);
        if (s.got.size() == s.total) {
            File f = new File();
            f.id = "f" + ids.incrementAndGet();
            f.name = s.name;
            f.parent = s.parent;
            f.mime = s.mime;
            f.app = s.app;
            f.data = s.got.toByteArray();
            files.put(f.id, f);
            sessions.remove(sid);
            send(ex, 200, json(f).toString());
        } else {
            ex.getResponseHeaders().add("Range", "bytes=0-" + (s.got.size() - 1));
            ex.sendResponseHeaders(308, -1);
            ex.close();
        }
    }

    File folder(String name, String parent) {
        for (File f : files.values()) if (name.equals(f.name) && "application/vnd.google-apps.folder".equals(f.mime) && java.util.Objects.equals(parent, f.parent)) return f;
        return null;
    }

    long mediaFiles() { return files.values().stream().filter(f -> !"application/vnd.google-apps.folder".equals(f.mime)).count(); }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
