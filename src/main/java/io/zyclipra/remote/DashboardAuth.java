package io.zyclipra.remote;

import io.zyclipra.cloud.Pkce;
import io.zyclipra.core.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pairing-code login for the dashboard. The code is shown only in the desktop app and stored
 * as a salted SHA-256 hash. Logins are rate limited per address (5 tries, then a growing
 * lock-out); successful logins get a random 256-bit session token that expires after 12 h.
 */
public final class DashboardAuth {
    private static final long SESSION_MS = 12 * 3600_000L;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Settings.Remote cfg;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    public DashboardAuth(Settings.Remote cfg) { this.cfg = cfg; }

    /** Makes a new 10-character pairing code, invalidating old sessions. Returns it once. */
    public String newPairingCode() {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) b.append('-');
            b.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        String code = b.toString();
        cfg.pairingSalt = Pkce.random(16);
        cfg.pairingCodeHash = hash(cfg.pairingSalt, code);
        sessions.clear();
        return code;
    }

    public boolean hasCode() { return !cfg.pairingCodeHash.isBlank(); }

    static String hash(String salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(md.digest(normalise(code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String normalise(String code) {
        return code == null ? "" : code.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    public enum Result { OK, WRONG, LOCKED }

    /** Returns a session token on success, else null; see {@link #lastResult}. */
    public synchronized String login(String address, String code) {
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(address);
        if (until != null && now < until) { last = Result.LOCKED; return null; }
        boolean ok = hasCode() && MessageDigest.isEqual(hash(cfg.pairingSalt, code).getBytes(StandardCharsets.UTF_8),
                cfg.pairingCodeHash.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            int[] f = failures.computeIfAbsent(address, k -> new int[1]);
            f[0]++;
            if (f[0] >= 5) lockedUntil.put(address, now + Math.min(3600_000L, 30_000L << Math.min(7, f[0] - 5)));
            last = Result.WRONG;
            return null;
        }
        failures.remove(address);
        lockedUntil.remove(address);
        String token = Pkce.random(32);
        sessions.put(token, now + SESSION_MS);
        last = Result.OK;
        return token;
    }

    private Result last = Result.WRONG;
    public synchronized Result lastResult() { return last; }

    public boolean valid(String token) {
        if (token == null) return false;
        Long exp = sessions.get(token);
        if (exp == null) return false;
        if (System.currentTimeMillis() > exp) { sessions.remove(token); return false; }
        return true;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }
}
