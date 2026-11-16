package io.zyclipa.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * In-app updates: the signed manifest is on the website, the zip in the public GitHub releases of MaDaAi/Zyclipa.
 * <ol>
 *   <li>check: reads zyclipa-update.json from the latest release; it must be signed with Zyclipa's release key
 *       (Ed25519, public key below), point at that repository's release downloads and be newer than this version;</li>
 *   <li>download: the zip's size and SHA-256 must match the signed manifest;</li>
 *   <li>install: unpacks next to the installed folder (no "../" tricks), then a small script waits for Zyclipa to
 *       close, swaps the folders (keeping the old one as Zyclipa.previous) and starts the new version.</li>
 * </ol>
 * Nothing is installed without the person pressing the button; settings and clips live elsewhere and are untouched.
 */
public final class Updater {
    private static final Logger LOG = Logger.getLogger(Updater.class.getName());
    public static final String REPO = "MaDaAi/Zyclipa";
    /** The signed "latest version" file lives on the website, so a release only needs the zip (one download for people). */
    static final String MANIFEST_URL = "https://madaai.github.io/zyclipa-dashboard/zyclipa-update.json";
    static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    /** Zyclipa's release-signing public key (the private key never leaves the developer's PC). */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAN7I5Z2mWW6Ien8vfQaa0I5qT/h90OsJf0xoWqMeCSjQ=";
    static final long MAX_SIZE = 800L * 1024 * 1024;

    public record Release(String version, String url, String sha256, long size, String notes) { }

    private final String current;
    private final PublicKey key;
    private final HttpClient http;
    private final Path work;

    public Updater(String currentVersion, Path workDir) {
        this(currentVersion, workDir, publicKey(PUBLIC_KEY),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build());
    }

    Updater(String currentVersion, Path workDir, PublicKey key, HttpClient http) {
        this.current = currentVersion;
        this.work = workDir;
        this.key = key;
        this.http = http;
    }

    static PublicKey publicKey(String b64) {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** A newer, correctly signed release, or empty when this version is up to date. */
    public Optional<Release> check() throws IOException {
        HttpResponse<InputStream> r = send(HttpRequest.newBuilder(URI.create(MANIFEST_URL)).timeout(Duration.ofSeconds(20)).GET().build());
        if (r.statusCode() == 404) { r.body().close(); return Optional.empty(); }    // no release published yet
        if (r.statusCode() != 200) { r.body().close(); throw new IOException("GitHub answered " + r.statusCode()); }
        String json;
        try (InputStream in = r.body()) { json = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8); }
        Release rel = parse(json);
        return compare(rel.version(), current) > 0 ? Optional.of(rel) : Optional.empty();
    }

    /** Parses and verifies a manifest; throws when anything about it isn't right. */
    Release parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String version = o.get("version").getAsString(), url = o.get("url").getAsString(), sha = o.get("sha256").getAsString();
        long size = o.get("size").getAsLong();
        String notes = o.has("notes") ? o.get("notes").getAsString() : "";
        if (!version.matches("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}")) throw new SecurityException("bad version in update");
        if (!url.startsWith(DOWNLOAD_PREFIX + "v" + version + "/") || !url.matches("https://[A-Za-z0-9./_+-]+\\.zip"))
            throw new SecurityException("update isn't from Zyclipa's GitHub releases");
        if (!sha.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_SIZE) throw new SecurityException("bad update details");
        String canonical = "zyclipa-update|v1|" + version + "|" + url + "|" + sha + "|" + size;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(canonical.getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(o.get("signature").getAsString()))) throw new SecurityException("update signature is wrong");
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("update signature couldn't be checked");
        }
        return new Release(version, url, sha, size, notes.length() > 2000 ? notes.substring(0, 2000) : notes);
    }

    /** Downloads the release and checks its size and SHA-256 against the signed manifest. */
    public Path download(Release rel, DoubleConsumer progress) throws IOException {
        Files.createDirectories(work);
        Path zip = work.resolve("Zyclipa-" + rel.version() + ".zip");
        Path part = work.resolve("Zyclipa-" + rel.version() + ".zip.part");
        HttpResponse<InputStream> r = send(HttpRequest.newBuilder(URI.create(rel.url())).timeout(Duration.ofMinutes(30)).GET().build());
        if (r.statusCode() != 200) { r.body().close(); throw new IOException("download failed (" + r.statusCode() + ")"); }
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
        long done = 0;
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                done += n;
                if (done > rel.size()) throw new SecurityException("the download is bigger than the release says");
                md.update(buf, 0, n);
                out.write(buf, 0, n);
                progress.accept(done / (double) rel.size());
            }
        }
        if (done != rel.size() || !HexFormat.of().formatHex(md.digest()).equals(rel.sha256())) {
            Files.deleteIfExists(part);
            throw new SecurityException("the download doesn't match the signed release – it was not installed");
        }
        Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
        return zip;
    }

    /** Unpacks the zip into {@code target} (which must not exist); the zip must contain Zyclipa/Zyclipa.exe. */
    static Path unpack(Path zip, Path target) throws IOException {
        if (Files.exists(target)) deleteTree(target);
        Files.createDirectories(target);
        Path root = target.toRealPath();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new SecurityException("unsafe path in update: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path app = root.resolve("Zyclipa");
        if (!Files.isRegularFile(app.resolve("Zyclipa.exe")) || !Files.isRegularFile(app.resolve("app").resolve("zyclipa.jar")))
            throw new SecurityException("the update doesn't contain Zyclipa.exe");
        return app;
    }

    /** The folder this copy of Zyclipa runs from (…\Zyclipa containing Zyclipa.exe), or empty when not installed that way. */
    public static Optional<Path> installedFolder() {
        return ProcessHandle.current().info().command().map(Path::of)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase("Zyclipa.exe"))
                .map(Path::getParent)
                .filter(d -> Files.isRegularFile(d.resolve("app").resolve("zyclipa.jar")));
    }

    /**
     * Unpacks next to the installed folder and starts the swap script; the caller must then exit the app.
     * The old version is kept as "Zyclipa.previous" so it can be restored by hand.
     */
    public void install(Path zip, Path installed) throws IOException {
        Path staging = installed.resolveSibling(installed.getFileName() + ".update");
        Path fresh = unpack(zip, staging);
        Path script = work.resolve("apply-update.ps1");
        try (InputStream in = Updater.class.getResourceAsStream("/update/apply-update.ps1")) {
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.info("update: installing from " + zip.getFileName() + " into " + installed);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(),
                "-WaitPid", String.valueOf(ProcessHandle.current().pid()), "-Src", fresh.toString(), "-Dst", installed.toString(),
                "-Staging", staging.toString())
                .redirectErrorStream(true).redirectOutput(work.resolve("apply-update.log").toFile()).start();
    }

    /** Numeric x.y.z comparison. */
    public static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0, q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private HttpResponse<InputStream> send(HttpRequest req) throws IOException {
        try { return http.send(req, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted", e); }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path q : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }
}
