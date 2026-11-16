package io.zyclipa.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.zyclipa.core.Settings;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Which Google OAuth client Zyclipa signs in with. Users never see or enter this.
 * <p>
 * The client is a "Desktop app" client. It is read, in this order, from:
 * <ol>
 *   <li>{@code /google-oauth.json} inside the app – packaged at build time from {@code app/private/google-oauth.json},
 *       a file that is listed in .gitignore and never committed;</li>
 *   <li>the environment variables {@code ZYCLIPA_GOOGLE_CLIENT_ID} / {@code ZYCLIPA_GOOGLE_CLIENT_SECRET} (for developers);</li>
 *   <li>values an older version saved in settings (only until they are migrated).</li>
 * </ol>
 * Google documents that a desktop app's client id and "secret" can't be kept confidential once the app is
 * distributed; security comes from PKCE, the loopback redirect and the user's own consent, not from hiding them.
 * They are kept out of the repository so they aren't published there.
 */
public record GoogleClient(String clientId, String clientSecret, String source) {
    public static final String RESOURCE = "/google-oauth.json";

    public boolean available() { return clientId != null && !clientId.isBlank(); }

    public static GoogleClient load(Settings.Cloud legacy) {
        GoogleClient built = fromResource();
        if (built != null) return built;
        String envId = System.getenv("ZYCLIPA_GOOGLE_CLIENT_ID");
        if (envId != null && !envId.isBlank()) {
            String envSecret = System.getenv("ZYCLIPA_GOOGLE_CLIENT_SECRET");
            return new GoogleClient(envId.trim(), envSecret == null ? "" : envSecret.trim(), "environment");
        }
        if (legacy != null && !legacy.clientId.isBlank()) return new GoogleClient(legacy.clientId.trim(), legacy.clientSecret.trim(), "settings");
        return new GoogleClient("", "", "none");
    }

    static GoogleClient fromResource() {
        try (InputStream in = GoogleClient.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            // Accept both our own small format and the JSON Google offers to download ({"installed": {...}}).
            if (o.has("installed")) o = o.getAsJsonObject("installed");
            String id = o.has("client_id") ? o.get("client_id").getAsString().trim() : "";
            String secret = o.has("client_secret") ? o.get("client_secret").getAsString().trim() : "";
            return id.isBlank() ? null : new GoogleClient(id, secret, "built-in");
        } catch (Exception e) {
            return null;
        }
    }
}
