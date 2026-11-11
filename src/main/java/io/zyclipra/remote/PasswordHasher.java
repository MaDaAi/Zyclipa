package io.zyclipra.remote;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Argon2id password hashing (Bouncy Castle, already used for the dashboard certificate).
 * Parameters follow OWASP's recommendation (19 MiB, 2 passes, 1 lane). Output is the standard
 * PHC string, so the parameters can be raised later without breaking existing hashes.
 */
public final class PasswordHasher {
    static final int MEMORY_KB = 19 * 1024, ITERATIONS = 2, PARALLELISM = 1, HASH_LEN = 32, SALT_LEN = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private PasswordHasher() {}

    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] out = derive(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM, HASH_LEN);
        return "$argon2id$v=19$m=" + MEMORY_KB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(out);
    }

    /** Constant-time check against a stored PHC string; false for anything malformed. */
    public static boolean verify(char[] password, String encoded) {
        try {
            String[] p = encoded.split("\\$");
            // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
            if (p.length != 6 || !p[1].equals("argon2id") || !p[2].equals("v=19")) return false;
            int m = 0, t = 0, par = 0;
            for (String kv : p[3].split(",")) {
                String[] x = kv.split("=");
                switch (x[0]) {
                    case "m" -> m = Integer.parseInt(x[1]);
                    case "t" -> t = Integer.parseInt(x[1]);
                    case "p" -> par = Integer.parseInt(x[1]);
                    default -> { return false; }
                }
            }
            if (m < 8 || m > 1 << 20 || t < 1 || t > 20 || par < 1 || par > 16) return false;
            byte[] salt = B64D.decode(p[4]), want = B64D.decode(p[5]);
            byte[] got = derive(password, salt, m, t, par, want.length);
            return MessageDigest.isEqual(got, want);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int memKb, int iterations, int parallelism, int len) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memKb).withIterations(iterations).withParallelism(parallelism).withSalt(salt).build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] pw = new byte[bb.remaining()];
        bb.get(pw);
        byte[] out = new byte[len];
        try {
            gen.generateBytes(pw, out);
        } finally {
            Arrays.fill(pw, (byte) 0);
            if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        }
        return out;
    }
}
