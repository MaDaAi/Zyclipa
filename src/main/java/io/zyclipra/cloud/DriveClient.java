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
import java.util.Optional;

/**
 * Minimal Google Drive v3 client: folder lookup, resumable chunked upload (survives
 * interruptions and app restarts), listing and ranged download.
 */
public final class DriveClient {
    public interface Tokens { String get() throws IOException; void invalidate(); }
    public interface Progress { void update(long done, long total); }
    public record RemoteFile(String id, String name, long size, String modified) { }

    public static final int CHUNK = 8 * 256 * 1024; // must be a multiple of 256 KiB
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
        }
    }

    static String q(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    public String ensureFolder(String name) throws IOException {
        String query = "mimeType='application/vnd.google-apps.folder' and name='" + name.replace("\\", "\\\\").replace("'", "\\'") + "' and trashed=false";
        HttpResponse<String> r = send(req(api + "/drive/v3/files?fields=files(id,name)&q=" + q(query)).GET().build(), HttpResponse.BodyHandlers.ofString());
        check(r, "find folder");
        JsonArray files = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("files");
        if (files != null && !files.isEmpty()) return files.get(0).getAsJsonObject().get("id").getAsString();
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        meta.addProperty("mimeType", "application/vnd.google-apps.folder");
        r = send(req(api + "/drive/v3/files?fields=id").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(meta.toString())).build(), HttpResponse.BodyHandlers.ofString());
        check(r, "create folder");
        return JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
    }

    /** Starts a resumable session; returns its URL (store it to resume later). */
    public String startUpload(Path file, String name, String folderId) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        JsonArray parents = new JsonArray();
        parents.add(folderId);
        meta.add("parents", parents);
        HttpResponse<String> r = send(req(api + "/upload/drive/v3/files?uploadType=resumable&fields=id,size")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Length", String.valueOf(Files.size(file)))
                .POST(HttpRequest.BodyPublishers.ofString(meta.toString())).build(), HttpResponse.BodyHandlers.ofString());
        check(r, "start upload");
        return r.headers().firstValue("Location").orElseThrow(() -> new IOException("Drive returned no upload session"));
    }

    /** How many bytes the server already has for this session (-1 if the session is gone, -2 if finished). */
    public long uploadedBytes(String session, long total) throws IOException {
        HttpResponse<String> r = send(req(session).header("Content-Range", "bytes */" + total)
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200 || r.statusCode() == 201) return -2;
        if (r.statusCode() == 404 || r.statusCode() == 410) return -1;
        if (r.statusCode() != 308) throw new IOException("Upload status check failed (" + r.statusCode() + ")");
        return r.headers().firstValue("Range").map(v -> Long.parseLong(v.substring(v.indexOf('-') + 1)) + 1).orElse(0L);
    }

    /** Sends the rest of the file from {@code offset}. Returns the Drive file id when complete. */
    public String continueUpload(String session, Path file, long offset, Progress p) throws IOException {
        long total = Files.size(file);
        byte[] buf = new byte[CHUNK];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long pos = offset;
            while (true) {
                int len = (int) Math.min(CHUNK, total - pos);
                raf.seek(pos);
                raf.readFully(buf, 0, len);
                String range = len == 0 ? "bytes */" + total : "bytes " + pos + "-" + (pos + len - 1) + "/" + total;
                HttpResponse<String> r = send(req(session).header("Content-Range", range)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(buf, 0, len)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200 || r.statusCode() == 201) {
                    p.update(total, total);
                    return JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
                }
                if (r.statusCode() != 308) throw new IOException("Upload chunk failed (" + r.statusCode() + ")");
                pos = r.headers().firstValue("Range").map(v -> Long.parseLong(v.substring(v.indexOf('-') + 1)) + 1).orElse(pos);
                p.update(pos, total);
            }
        }
    }

    public List<RemoteFile> list(String folderId) throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        String page = null;
        do {
            String url = api + "/drive/v3/files?pageSize=200&fields=nextPageToken,files(id,name,size,modifiedTime)&q="
                    + q("'" + folderId + "' in parents and trashed=false") + (page == null ? "" : "&pageToken=" + q(page));
            HttpResponse<String> r = send(req(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(r, "list files");
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("files")) {
                JsonObject f = e.getAsJsonObject();
                out.add(new RemoteFile(f.get("id").getAsString(), f.get("name").getAsString(),
                        f.has("size") ? f.get("size").getAsLong() : 0, f.has("modifiedTime") ? f.get("modifiedTime").getAsString() : ""));
            }
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
        else if (r.statusCode() != 206) { r.body().close(); throw new IOException("Download failed (" + r.statusCode() + ")"); }
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                have == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long done = have;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; p.update(done, size); }
        }
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public Optional<Long> about() throws IOException {
        HttpResponse<String> r = send(req(api + "/drive/v3/about?fields=storageQuota").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) return Optional.empty();
        JsonObject s = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("storageQuota");
        if (s == null || !s.has("limit")) return Optional.empty();
        return Optional.of(s.get("limit").getAsLong() - s.get("usage").getAsLong());
    }

    private static void check(HttpResponse<String> r, String what) throws IOException {
        if (r.statusCode() / 100 != 2) throw new IOException("Drive " + what + " failed (" + r.statusCode() + ")");
    }
}
