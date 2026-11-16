import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Release signing for Zyclipa's updater (run with the JDK: java packaging/SignRelease.java ...).
 *
 *   pack <dir> <zip>                zip the app folder (forward-slash paths)
 *   genkey                          make private/update-signing-key.txt (keep it secret, back it up) and print the public key
 *   sign <zip> <version> [notes]    write zyclipa-update.json next to the zip, signed with that key
 *
 * The app only installs an update whose manifest is signed by this key, whose download comes from the expected
 * GitHub releases URL, and whose SHA-256 and size match – so a compromised GitHub account alone can't ship malware.
 */
public class SignRelease {
    static final Path KEY = Path.of("private", "update-signing-key.txt");
    static final String BASE = "https://github.com/MaDaAi/Zyclipa/releases/download/";

    public static void main(String[] a) throws Exception {
        if (a.length >= 1 && a[0].equals("genkey")) {
            if (Files.exists(KEY)) throw new IllegalStateException(KEY + " already exists – refusing to overwrite it");
            var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(KEY.getParent());
            Files.writeString(KEY, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n");
            System.out.println("Private key: " + KEY.toAbsolutePath() + "  (never commit or share it)");
            System.out.println("PUBLIC_KEY = \"" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\"");
            return;
        }
        if (a.length == 3 && a[0].equals("pack")) {
            // Standard zip (forward slashes), Zyclipa/... at the top, like the updater expects.
            Path dir = Path.of(a[1]), zip = Path.of(a[2]);
            try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip)); var files = Files.walk(dir)) {
                for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = dir.getFileName() + "/" + dir.relativize(f).toString().replace('\\', '/');
                    z.putNextEntry(new java.util.zip.ZipEntry(name));
                    Files.copy(f, z);
                    z.closeEntry();
                }
            }
            System.out.println("Packed " + zip);
            return;
        }
        if (a.length < 3 || !a[0].equals("sign")) {
            System.err.println("usage: genkey | sign <zip> <version> [notes]");
            System.exit(2);
        }
        Path zip = Path.of(a[1]);
        String version = a[2];
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) throw new IllegalArgumentException("version must look like 1.2.3");
        String notes = a.length > 3 ? a[3] : "";
        byte[] data = Files.readAllBytes(zip);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        String url = BASE + "v" + version + "/" + zip.getFileName();
        String canonical = "zyclipa-update|v1|" + version + "|" + url + "|" + sha + "|" + data.length;
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(KEY).trim())));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(canonical.getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(s.sign());
        String json = "{\n  \"version\": \"" + version + "\",\n  \"url\": \"" + url + "\",\n  \"sha256\": \"" + sha + "\",\n  \"size\": " + data.length
                + ",\n  \"notes\": \"" + notes.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\n  \"signature\": \"" + sig + "\"\n}\n";
        Path out = zip.resolveSibling("zyclipa-update.json");
        Files.writeString(out, json);
        System.out.println("Wrote " + out + "\n1. Upload " + zip.getFileName() + " to a GitHub release tagged v" + version
                + " in MaDaAi/zyclipa-dashboard.\n2. After it's published, put zyclipa-update.json in the website repo (that tells apps the update exists).");
    }
}
