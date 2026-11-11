package io.zyclipra.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal Google Drive v3 client with the drive.file scope (it only ever sees files Zyclipra made):
 * folders, resumable chunked upload (survives interruptions and app restarts), lookup by Zyclipra's own
 * clip id (so a retry never makes a duplicate), existence checks, ranged download and delete.
 */
public final class DriveClient {
    public interface Tokens { String get() throws IOException; void invalidate(); }
    public interface Progress { void update(long done, long total); }
    public record RemoteFile(String id, String name, long size, String modified, String md5, Map<String, String> appProperties) {
        public RemoteFile(String id, String name, long size, String modified) { this(id, name, size, modified, "", Map.of()); }
    }

    /** A Drive error. {@code retryable} is true for rate limits, server errors and dropped connections. */
    public static final class DriveException extends IOException {
        public final int status;
        public final boolean retryable;
        DriveException(int status, boolean retryable, String msg) { super(msg); this.status = status; this.retryable = retryable; }
    }

    public static final String FOLDER = "application/vnd.google-apps.folder";
    public static final String APP_KEY = "zyclipraId";
    public static final int CHUNK = 8 * 256 * 1024; // must be a multiple of 256 KiB
    private static final String FILE_FIELDS = "id,name,size,modifiedTime,md5Checksum,appProperties,trashed";
    private final HttpClient http;
    private final Tokens tokens;
    private final String api;

    public DriveClient(HttpClient http, Tokens tokens, String apiBase) {
        this.http = http;
        this.tokens = tokens;
        this.api = apiBase;
    }

    private HttpRequest.Builder req(String url) throws IOException {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + tokens.get());
    }

    private <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) throws IOException {
        try {
            HttpResponse<T> resp = http.send(r, h);
            if (resp.statusCode() == 401) tokens.invalidate();
            return resp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IOException e) {
            throw new DriveException(0, true, "Couldn't reach Google Drive (" + e.getClass().getSimpleName() + ")");
        }
    }

    static String q(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String lit(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    /** Finds or creates a folder by name inside {@code parentId} (or the Drive root when null). */
    public String ensureFolder(String name, String parentId) throws IOException {
        String query = "mimeType=" + lit(FOLDER) + " and name=" + lit(name) + " and trashed=false"
                + (parentId == null ? " and 'root' in parents" : " and " + lit(parentId) + " in parents");
        HttpResponse<String> r = send(req(api + "/drive/v3/files?fields=files(id,name)&q=" + q(query)).GET().build(), HttpResponse.BodyHandlers.ofString());
        check(r, "find folder");
        JsonArray files = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("files");
        if (files != null && !files.isEmpty()) return files.get(0).getAsJsonObject().get("id").getAsString();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        meta.addProperty("mimeType", FOLDER);
        if (parentId != null) { JsonArray p = new JsonArray(); p.add(parentId); meta.add("parents", p); }
        r = send(req(api + "/drive/v3/files?fields=id").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(meta.toString())).build(), HttpResponse.BodyHandlers.ofString());
        check(r, "create folder");
        return JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
    }

    /** Old single-folder form (kept for callers that only need one level). */
    public String ensureFolder(String name) throws IOException { return ensureFolder(name, null); }

    /** The file Zyclipra uploaded earlier for this clip id, if it's still there (not trashed). */
    public Optional<RemoteFile> findByClipId(String clipId) throws IOException {
        String query = "appProperties has { key=" + lit(APP_KEY) + " and value=" + lit(clipId) + " } and trashed=false";
        HttpResponse<String> r = send(req(api + "/drive/v3/files?pageSize=10&fields=files(" + FILE_FIELDS + ")&q=" + q(query)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        check(r, "look up file");
        JsonArray files = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("files");
        if (files == null || files.isEmpty()) return Optional.empty();
        return Optional.of(file(files.get(0).getAsJsonObject()));
    }

    /** The file's details, or empty when it was deleted or moved to the bin. */
    public Optional<RemoteFile> get(String fileId) throws IOException {
        HttpResponse<String> r = send(req(api + "/drive/v3/files/" + q(fileId) + "?fields=" + FILE_FIELDS).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return Optional.empty();
        check(r, "check file");
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        if (o.has("trashed") && o.get("trashed").getAsBoolean()) return Optional.empty();
        return Optional.of(file(o));
    }

    /** Starts a resumable session; returns its URL (store it to resume later). */
    public String startUpload(Path file, String name, String folderId, Map<String, String> appProperties, String mimeType) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        JsonArray parents = new JsonArray();
        parents.add(folderId);
        meta.add("parents", parents);
        if (!appProperties.isEmpty()) {
            JsonObject ap = new JsonObject();
            appProperties.forEach(ap::addProperty);
            meta.add("appProperties", ap);
        }
        HttpRequest.Builder b = req(api + "/upload/drive/v3/files?uploadType=resumable&fields=" + q(FILE_FIELDS))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Length", String.valueOf(Files.size(file)));
        if (mimeType != null) b.header("X-Upload-Content-Type", mimeType);
        HttpResponse<String> r = send(b.POST(HttpRequest.BodyPublishers.ofString(meta.toString())).build(), HttpResponse.BodyHandlers.ofString());
        check(r, "start upload");
        return r.headers().firstValue("Location").orElseThrow(() -> new IOException("Drive returned no upload session"));
    }

    public String startUpload(Path file, String name, String folderId) throws IOException { return startUpload(file, name, folderId, Map.of(), null); }

    /** How many bytes the server already has for this session (-1 if the session is gone, -2 if finished). */
    public long uploadedBytes(String session, long total) throws IOException {
        HttpResponse<String> r = send(req(session).header("Content-Range", "bytes */" + total)
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200 || r.statusCode() == 201) return -2;
        if (r.statusCode() == 404 || r.statusCode() == 410) return -1;
        if (r.statusCode() != 308) throw error(r, "check upload");
        return r.headers().firstValue("Range").map(v -> Long.parseLong(v.substring(v.indexOf('-') + 1)) + 1).orElse(0L);
    }

    /** Sends the rest of the file from {@code offset}. Returns the finished file as Drive describes it. */
    public RemoteFile continueUpload(String session, Path file, long offset, Progress p) throws IOException {
        long total = Files.size(file);
        byte[] buf = new byte[CHUNK];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long pos = offset;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Upload cancelled");
                int len = (int) Math.min(CHUNK, total - pos);
                raf.seek(pos);
                raf.readFully(buf, 0, len);
                String range = len == 0 ? "bytes */" + total : "bytes " + pos + "-" + (pos + len - 1) + "/" + total;
                HttpResponse<String> r = send(req(session).header("Content-Range", range)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(buf, 0, len)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200 || r.statusCode() == 201) {
                    p.update(total, total);
                    return file(JsonParser.parseString(r.body()).getAsJsonObject());
                }
                if (r.statusCode() != 308) throw error(r, "upload");
                pos = r.headers().firstValue("Range").map(v -> Long.parseLong(v.substring(v.indexOf('-') + 1)) + 1).orElse(0L);
                p.update(pos, total);
            }
        }
    }

    public List<RemoteFile> list(String folderId) throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        String page = null;
        do {
            String url = api + "/drive/v3/files?pageSize=200&fields=nextPageToken,files(" + FILE_FIELDS + ")&q="
                    + q(lit(folderId) + " in parents and trashed=false") + (page == null ? "" : "&pageToken=" + q(page));
            HttpResponse<String> r = send(req(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(r, "list files");
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("files")) out.add(file(e.getAsJsonObject()));
            page = o.has("nextPageToken") ? o.get("nextPageToken").getAsString() : null;
        } while (page != null);
        return out;
    }

    /** Downloads into {@code target}, resuming a partial ".part" file if one exists. */
    public void download(String fileId, long size, Path target, Progress p) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long have = Files.exists(part) ? Files.size(part) : 0;
        if (have > size) { Files.delete(part); have = 0; }
        HttpRequest.Builder b = req(api + "/drive/v3/files/" + q(fileId) + "?alt=media").timeout(Duration.ofHours(2));
        if (have > 0) b.header("Range", "bytes=" + have + "-");
        HttpResponse<InputStream> r = send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() == 200) have = 0;
        else if (r.statusCode() != 206) {
            r.body().close();
            throw new DriveException(r.statusCode(), r.statusCode() == 429 || r.statusCode() >= 500,
                    r.statusCode() == 404 ? "That file is no longer on Google Drive." : "Download failed (" + r.statusCode() + ")");
        }
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                have == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long done = have;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; p.update(done, size); }
        }
        if (size > 0 && Files.size(part) != size) throw new DriveException(0, true, "Download ended early – try again to resume.");
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Deletes a file Zyclipra uploaded (straight away, not to the bin). A file that's already gone counts as deleted. */
    public void delete(String fileId) throws IOException {
        HttpResponse<String> r = send(req(api + "/drive/v3/files/" + q(fileId)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404 || r.statusCode() == 204 || r.statusCode() == 200) return;
        throw error(r, "delete");
    }

    public Optional<Long> about() throws IOException {
        HttpResponse<String> r = send(req(api + "/drive/v3/about?fields=storageQuota").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) return Optional.empty();
        JsonObject s = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("storageQuota");
        if (s == null || !s.has("limit")) return Optional.empty();
        return Optional.of(s.get("limit").getAsLong() - s.get("usage").getAsLong());
    }

    private static RemoteFile file(JsonObject f) {
        java.util.Map<String, String> ap = new java.util.LinkedHashMap<>();
        if (f.has("appProperties")) for (var e : f.getAsJsonObject("appProperties").entrySet()) ap.put(e.getKey(), e.getValue().getAsString());
        return new RemoteFile(f.get("id").getAsString(), f.has("name") ? f.get("name").getAsString() : "",
                f.has("size") ? f.get("size").getAsLong() : 0, f.has("modifiedTime") ? f.get("modifiedTime").getAsString() : "",
                f.has("md5Checksum") ? f.get("md5Checksum").getAsString() : "", ap);
    }

    private static void check(HttpResponse<String> r, String what) throws IOException {
        if (r.statusCode() / 100 != 2) throw error(r, what);
    }

    /** 429, 5xx and Drive's 403 rate-limit reasons are worth retrying; everything else isn't. */
    static DriveException error(HttpResponse<String> r, String what) {
        int s = r.statusCode();
        String body = r.body() == null ? "" : r.body();
        boolean rate = s == 403 && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded"));
        boolean retry = s == 429 || s >= 500 || rate || s == 401;
        String why = switch (s) {
            case 401 -> "Google needs you to sign in again";
            case 403 -> rate ? "Google Drive asked Zyclipra to slow down" : body.contains("storageQuotaExceeded") ? "Your Google Drive is full" : "Google Drive refused access";
            case 404 -> "not found on Google Drive";
            case 429 -> "Google Drive asked Zyclipra to slow down";
            default -> s >= 500 ? "Google Drive had a temporary problem" : "error " + s;
        };
        return new DriveException(s, retry, "Drive " + what + " failed: " + why + " (" + s + ")");
    }
}
