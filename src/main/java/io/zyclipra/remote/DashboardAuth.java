package io.zyclipra.remote;

import io.zyclipra.cloud.Pkce;
import io.zyclipra.core.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Dashboard sign-in with a username and password the owner creates in the desktop app (there is no
 * default account). The password is stored only as an Argon2id hash. Sign-ins are rate limited per
 * address (5 tries, then a growing lock-out) and overall (50 failures in 10 minutes locks sign-in for
 * 5 minutes). A session is a random 256-bit token in an HttpOnly cookie; it ends after 12 hours idle
 * or 7 days in total, on sign-out, when revoked, or when the password changes.
 */
public final class DashboardAuth {
    static final long IDLE_MS = 12 * 3600_000L, ABSOLUTE_MS = 7 * 24 * 3600_000L;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,32}");
    /** Verified against when the username is wrong, so a wrong name takes as long as a wrong password. */
    private static final String DUMMY = PasswordHasher.hash("not-a-real-password".toCharArray());

    public enum Result { OK, WRONG, LOCKED, NOT_SET_UP }

    public record SessionInfo(String id, long created, long lastSeen, String address, String agent) { }

    private static final class Session {
        final String id;
        final long created;
        final String address, agent;
        volatile long lastSeen;
        boolean web;
        Session(String id, long created, String address, String agent) { this.id = id; this.created = created; this.lastSeen = created; this.address = address; this.agent = agent; }
    }

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DashboardAuth.class.getName());
    private volatile java.util.function.Consumer<String> alert = m -> { };

    /** Called when sign-ins get locked out (someone may be guessing the password): the app shows it. */
    public void onAlert(java.util.function.Consumer<String> a) { alert = a == null ? m -> { } : a; }

    private final Settings.Remote cfg;
    private final Runnable save;
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final List<Long> recentFailures = new ArrayList<>();
    private long globalLockUntil;
    private Result last = Result.WRONG;

    public DashboardAuth(Settings.Remote cfg, Runnable save) { this(cfg, save, System::currentTimeMillis); }

    DashboardAuth(Settings.Remote cfg, Runnable save, LongSupplier clock) {
        this.cfg = cfg;
        this.save = save;
        this.clock = clock;
    }

    /** For tests in other packages. */
    public static DashboardAuth withClock(Settings.Remote cfg, Runnable save, LongSupplier clock) { return new DashboardAuth(cfg, save, clock); }

    public boolean configured() { return !cfg.username.isBlank() && !cfg.passwordHash.isBlank(); }
    public String username() { return cfg.username; }

    /** Shortest allowed password: longer when the dashboard is reachable from the internet. */
    /** Longer when the dashboard can be reached from the internet (public mode or "outside my home network"). */
    public int minPasswordLength() { return "public".equals(cfg.mode) || cfg.vpn ? 12 : 8; }

    public static String checkUsername(String u) {
        if (u == null || !USERNAME.matcher(u).matches()) throw new IllegalArgumentException("Use 3–32 letters, digits, dots, dashes or underscores for the username.");
        return u;
    }

    private void checkPassword(String p) {
        if (p == null || p.length() < minPasswordLength()) throw new IllegalArgumentException("Use a password of at least " + minPasswordLength() + " characters.");
        if (p.length() > 200) throw new IllegalArgumentException("That password is too long (200 characters at most).");
        if (p.equalsIgnoreCase(cfg.username)) throw new IllegalArgumentException("The password can't be the same as the username.");
    }

    /** First-run setup (desktop app only) or a reset from the desktop app. Signs every browser out. */
    public synchronized void setCredentials(String username, String password, String confirm) {
        checkUsername(username);
        if (password == null || !password.equals(confirm)) throw new IllegalArgumentException("The two passwords don't match.");
        String old = cfg.username;
        cfg.username = username;
        try { checkPassword(password); } catch (IllegalArgumentException e) { cfg.username = old; throw e; }
        cfg.passwordHash = PasswordHasher.hash(password.toCharArray());
        cfg.pairingCodeHash = "";
        cfg.pairingSalt = "";
        sessions.clear();
        save.run();
    }

    /** Returns a session token on success, else null; see {@link #lastResult}. */
    public synchronized String login(String address, String agent, String username, String password) {
        long now = clock.getAsLong();
        if (!configured()) { last = Result.NOT_SET_UP; return null; }
        Long until = lockedUntil.get(address);
        if ((until != null && now < until) || now < globalLockUntil) { last = Result.LOCKED; return null; }
        boolean nameOk = username != null && MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8), cfg.username.getBytes(StandardCharsets.UTF_8));
        boolean passOk = PasswordHasher.verify(password == null ? new char[0] : password.toCharArray(), nameOk ? cfg.passwordHash : DUMMY);
        if (!(nameOk && passOk)) { fail(address, now); return null; }
        return newSession(address, agent, now, false);
    }

    /**
     * Sign-in for the hosted website: it proves it read this PC's link key from the owner's Google Drive.
     * Only the key's SHA-256 is kept here; failures count towards the same lock-outs as passwords.
     */
    public synchronized String webLogin(String address, String agent, String key) {
        long now = clock.getAsLong();
        if (!configured() || cfg.webKeyHash.isBlank()) { last = Result.NOT_SET_UP; return null; }
        Long until = lockedUntil.get(address);
        if ((until != null && now < until) || now < globalLockUntil) { last = Result.LOCKED; return null; }
        boolean ok = key != null && key.length() <= 100
                && MessageDigest.isEqual(sha256(key).getBytes(StandardCharsets.UTF_8), cfg.webKeyHash.getBytes(StandardCharsets.UTF_8));
        if (!ok) { fail(address, now); return null; }
        return newSession(address, "Website · " + (agent == null ? "" : agent), now, true);
    }

    /** Whether the session behind this token was opened by the hosted website. */
    public boolean isWeb(String token) {
        Session s = token == null ? null : sessions.get(token);
        return s != null && s.web;
    }

    /** Signs out every website session (used when the link key is reset). */
    public void revokeWeb() { sessions.values().removeIf(s -> s.web); }

    public static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void fail(String address, long now) {
        int[] f = failures.computeIfAbsent(address, k -> new int[1]);
        f[0]++;
        LOG.info("dashboard: failed sign-in from " + address + " (" + f[0] + " in a row)");
        if (f[0] >= 5) {
            lockedUntil.put(address, now + Math.min(3600_000L, 30_000L << Math.min(7, f[0] - 5)));
            LOG.warning("dashboard: sign-in locked for " + address + " after " + f[0] + " failed attempts");
            if (f[0] == 5) alert.accept("Dashboard: " + address + " entered a wrong password 5 times and is locked out for now. "
                    + "If that wasn't you, change your dashboard password (Settings › Hosting).");
        }
        recentFailures.add(now);
        recentFailures.removeIf(t -> t < now - 600_000L);
        if (recentFailures.size() >= 50 && now >= globalLockUntil) {
            globalLockUntil = now + 300_000L;
            LOG.warning("dashboard: all sign-ins paused for 5 minutes after 50 failures in 10 minutes");
            alert.accept("Dashboard: many wrong passwords from different places – all sign-ins are paused for 5 minutes.");
        }
        last = Result.WRONG;
    }

    private String newSession(String address, String agent, long now, boolean web) {
        failures.remove(address);
        lockedUntil.remove(address);
        String token = Pkce.random(32);
        String agentShort = agent == null ? "" : agent.replaceAll("[^\\x20-\\x7E]", "");
        agentShort = agentShort.substring(0, Math.min(120, agentShort.length()));
        Session s = new Session(idOf(token), now, address, agentShort);
        LOG.info("dashboard: " + (web ? "website" : "password") + " sign-in from " + address + " (session " + s.id + ")");
        s.web = web;
        sessions.put(token, s);
        last = Result.OK;
        return token;
    }

    public synchronized Result lastResult() { return last; }

    /** Public, non-secret handle for a session (shown in the session list, used to revoke it). */
    static String idOf(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public boolean valid(String token) { return sessionId(token) != null; }

    /** The session's id if the token is live (and marks it as used now), else null. */
    public String sessionId(String token) {
        if (token == null || !configured()) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = clock.getAsLong();
        if (now - s.lastSeen > IDLE_MS || now - s.created > ABSOLUTE_MS) { sessions.remove(token); return null; }
        s.lastSeen = now;
        return s.id;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    public List<SessionInfo> sessions() {
        long now = clock.getAsLong();
        sessions.values().removeIf(s -> now - s.lastSeen > IDLE_MS || now - s.created > ABSOLUTE_MS);
        List<SessionInfo> out = new ArrayList<>();
        for (Session s : sessions.values()) out.add(new SessionInfo(s.id, s.created, s.lastSeen, s.address, s.agent));
        out.sort(Comparator.comparingLong(SessionInfo::lastSeen).reversed());
        return out;
    }

    public boolean revoke(String id) { return sessions.values().removeIf(s -> s.id.equals(id)); }

    public void revokeAll() { sessions.clear(); }

    public boolean passwordMatches(String password) {
        return PasswordHasher.verify(password == null ? new char[0] : password.toCharArray(), cfg.passwordHash);
    }

    /** Needs the current password. Signs out every other session. */
    public synchronized void changePassword(String keepToken, String current, String next, String confirm) {
        if (!passwordMatches(current)) throw new SecurityException("The current password is wrong.");
        if (next == null || !next.equals(confirm)) throw new IllegalArgumentException("The two new passwords don't match.");
        checkPassword(next);
        cfg.passwordHash = PasswordHasher.hash(next.toCharArray());
        sessions.keySet().removeIf(t -> !t.equals(keepToken));
        save.run();
    }

    /** Needs the current password. */
    public synchronized void changeUsername(String current, String newName) {
        if (!passwordMatches(current)) throw new SecurityException("The password is wrong.");
        cfg.username = checkUsername(newName);
        save.run();
    }
}
