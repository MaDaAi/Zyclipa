package io.zyclipra;

import io.zyclipra.core.Settings;
import io.zyclipra.remote.DashboardAuth;
import io.zyclipra.remote.PasswordHasher;
import io.zyclipra.remote.PublicAccess;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Dashboard sign-in rules, with a controllable clock (no network). */
class AuthTest {
    @Test void argon2idHashesAreSaltedAndVerify() {
        String a = PasswordHasher.hash("hunter2 hunter2".toCharArray());
        String b = PasswordHasher.hash("hunter2 hunter2".toCharArray());
        assertTrue(a.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), a);
        assertNotEquals(a, b, "unique salt per hash");
        assertFalse(a.contains("hunter2"));
        assertTrue(PasswordHasher.verify("hunter2 hunter2".toCharArray(), a));
        assertFalse(PasswordHasher.verify("hunter2 hunter3".toCharArray(), a));
        assertFalse(PasswordHasher.verify("x".toCharArray(), "$argon2id$v=19$m=999999999,t=2,p=1$AAAA$AAAA"), "absurd parameters are rejected");
        assertFalse(PasswordHasher.verify("x".toCharArray(), "plain-text-password"));
    }

    @Test void firstRunSetupRules() {
        Settings.Remote cfg = new Settings.Remote();
        AtomicInteger saves = new AtomicInteger();
        DashboardAuth auth = new DashboardAuth(cfg, saves::incrementAndGet);
        assertFalse(auth.configured());
        assertThrows(IllegalArgumentException.class, () -> auth.setCredentials("ab", "long enough pw", "long enough pw"), "username too short");
        assertThrows(IllegalArgumentException.class, () -> auth.setCredentials("owner", "short", "short"), "password too short");
        assertThrows(IllegalArgumentException.class, () -> auth.setCredentials("owner", "long enough pw", "different pw!!"), "confirmation must match");
        assertThrows(IllegalArgumentException.class, () -> auth.setCredentials("owner name", "long enough pw", "long enough pw"), "no spaces in usernames");
        assertFalse(auth.configured(), "nothing saved after a refused attempt");
        assertEquals("", cfg.username);
        auth.setCredentials("owner", "long enough pw", "long enough pw");
        assertTrue(auth.configured());
        assertTrue(cfg.passwordHash.startsWith("$argon2id$"));
        assertFalse(cfg.passwordHash.contains("long enough"));
        assertEquals(1, saves.get());
        cfg.mode = "public";
        assertEquals(12, auth.minPasswordLength(), "public hosting needs longer passwords");
    }

    @Test void sessionsExpireWhenIdleAndAfterSevenDays() {
        Settings.Remote cfg = new Settings.Remote();
        AtomicLong now = new AtomicLong(1_000_000);
        DashboardAuth auth = DashboardAuth.withClock(cfg, () -> { }, now::get);
        auth.setCredentials("owner", "long enough pw", "long enough pw");
        String t = auth.login("10.0.0.2", "test", "owner", "long enough pw");
        assertNotNull(t);
        now.addAndGet(11 * 3600_000L);
        assertTrue(auth.valid(t), "used within 12 h");
        now.addAndGet(13 * 3600_000L);
        assertFalse(auth.valid(t), "expired after 12 h unused");

        String u = auth.login("10.0.0.2", "test", "owner", "long enough pw");
        for (int step = 1; step <= 15; step++) { now.addAndGet(11 * 3600_000L); assertTrue(auth.valid(u), "kept alive by use every 11 h, " + step * 11 + " h"); }
        now.addAndGet(11 * 3600_000L); // 176 h since sign-in
        assertFalse(auth.valid(u), "never longer than 7 days in total");
    }

    @Test void lockoutPerAddressAndOverall() {
        Settings.Remote cfg = new Settings.Remote();
        AtomicLong now = new AtomicLong(1_000_000);
        DashboardAuth auth = DashboardAuth.withClock(cfg, () -> { }, now::get);
        auth.setCredentials("owner", "long enough pw", "long enough pw");
        for (int i = 0; i < 5; i++) assertNull(auth.login("10.0.0.9", "", "owner", "wrong " + i));
        assertNull(auth.login("10.0.0.9", "", "owner", "long enough pw"));
        assertEquals(DashboardAuth.Result.LOCKED, auth.lastResult());
        assertNotNull(auth.login("10.0.0.10", "", "owner", "long enough pw"), "other addresses aren't punished");
        now.addAndGet(31_000);
        assertNotNull(auth.login("10.0.0.9", "", "owner", "long enough pw"), "the lock wears off");

        // Many addresses guessing at once trip the overall limit.
        for (int i = 0; i < 50; i++) auth.login("10.1.0." + i, "", "owner", "guess");
        assertNull(auth.login("10.2.0.1", "", "owner", "long enough pw"));
        assertEquals(DashboardAuth.Result.LOCKED, auth.lastResult());
        now.addAndGet(301_000);
        assertNotNull(auth.login("10.2.0.1", "", "owner", "long enough pw"));
    }

    @Test void publicAddressMustBeHttpsWithAPublicName() {
        assertEquals("https://clips.example.com", PublicAccess.normalise("https://Clips.Example.com/"));
        for (String bad : new String[]{"http://clips.example.com", "https://192.168.1.5", "https://localhost", "https://clips.example.com/dash",
                "https://user@clips.example.com", "ftp://x.example.com", "clips.example.com"})
            assertThrows(IllegalArgumentException.class, () -> PublicAccess.normalise(bad), bad);
        assertNotNull(PublicAccess.verify("http://clips.example.com", "x"), "plain http is refused before any network call");
    }
}
