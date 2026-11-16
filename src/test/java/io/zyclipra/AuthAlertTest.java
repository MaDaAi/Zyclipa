package io.zyclipra;

import io.zyclipra.core.Settings;
import io.zyclipra.remote.DashboardAuth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Repeated wrong passwords lock the address out and tell the owner once; a wrong website link key counts the same. */
class AuthAlertTest {
    @Test void lockoutRaisesOneAlert() {
        Settings.Remote cfg = new Settings.Remote();
        long[] now = {1_000_000};
        DashboardAuth auth = DashboardAuth.withClock(cfg, () -> { }, () -> now[0]);
        auth.setCredentials("owner", "correct horse battery", "correct horse battery");
        List<String> alerts = new ArrayList<>();
        auth.onAlert(alerts::add);
        for (int i = 0; i < 7; i++) assertNull(auth.login("10.0.0.9", "test", "owner", "wrong password " + i));
        assertEquals(1, alerts.size(), "one alert per lock-out, not one per attempt");
        assertTrue(alerts.get(0).contains("10.0.0.9"));
        assertEquals(DashboardAuth.Result.LOCKED, auth.lastResult());
        assertNull(auth.login("10.0.0.9", "test", "owner", "correct horse battery"), "still locked even with the right password");
        assertNotNull(auth.login("10.0.0.10", "test", "owner", "correct horse battery"), "other devices aren't affected");
    }

    @Test void wrongWebsiteKeyCountsTowardsLockout() {
        Settings.Remote cfg = new Settings.Remote();
        cfg.webKeyHash = DashboardAuth.sha256("the real key");
        DashboardAuth auth = DashboardAuth.withClock(cfg, () -> { }, () -> 5_000_000L);
        auth.setCredentials("owner", "correct horse battery", "correct horse battery");
        List<String> alerts = new ArrayList<>();
        auth.onAlert(alerts::add);
        for (int i = 0; i < 5; i++) assertNull(auth.webLogin("10.0.0.20", "test", "guess" + i));
        assertEquals(1, alerts.size());
        assertNull(auth.webLogin("10.0.0.20", "test", "the real key"), "locked out");
        String t = auth.webLogin("10.0.0.21", "test", "the real key");
        assertNotNull(t);
        assertTrue(auth.isWeb(t));
    }
}
