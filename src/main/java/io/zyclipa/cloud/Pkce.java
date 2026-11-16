package io.zyclipa.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** RFC 7636 code verifier / S256 challenge. */
public final class Pkce {
    private static final SecureRandom RNG = new SecureRandom();
    public final String verifier;
    public final String challenge;
    public final String state;

    public Pkce() {
        this.verifier = random(64);
        this.challenge = s256(verifier);
        this.state = random(24);
    }

    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String s256(String verifier) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
