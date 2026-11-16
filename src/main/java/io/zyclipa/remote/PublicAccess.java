package io.zyclipa.remote;

import com.google.gson.JsonParser;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Checks the advanced "public" hosting mode before it can be turned on: the address must be https://
 * with a public host name, its certificate must be trusted by Java's normal trust store (so a real
 * certificate, e.g. from Let's Encrypt – never a self-signed one), and a request to it must reach
 * <em>this</em> running Zyclipa (it answers with a random id that changes every run). This proves the
 * owner's reverse proxy works; it does not make public hosting safe by itself, and the UI says so.
 */
public final class PublicAccess {
    private PublicAccess() {}

    /** Normalises and validates the address; throws IllegalArgumentException with a readable reason. */
    public static String normalise(String url) {
        URI u;
        try { u = URI.create(url == null ? "" : url.trim()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("That isn't a valid web address."); }
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new IllegalArgumentException("The address must start with https:// – the public dashboard is never served without encryption.");
        if (u.getHost() == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null)
            throw new IllegalArgumentException("Enter just the address, like https://clips.example.com");
        if (u.getPath() != null && !u.getPath().isEmpty() && !u.getPath().equals("/"))
            throw new IllegalArgumentException("Serve the dashboard at the root of its own address (no path), like https://clips.example.com");
        String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".local") || host.matches("[0-9.]+") || host.contains(":"))
            throw new IllegalArgumentException("Use a public host name with a real certificate, not an IP address or a local name.");
        return "https://" + host + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /** Returns null when the address reaches this Zyclipa, otherwise what went wrong. */
    public static String verify(String url, String instanceId) {
        String base;
        try { base = normalise(url); } catch (IllegalArgumentException e) { return e.getMessage(); }
        try {
            URI u = URI.create(base);
            for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress())
                    return "That name points to a private address (" + a.getHostAddress() + "), so it isn't reachable from the internet.";
            }
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/instance")).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return "The address answered with error " + r.statusCode() + " – check that your reverse proxy forwards to this PC's dashboard.";
            String got = JsonParser.parseString(r.body()).getAsJsonObject().get("instance").getAsString();
            if (!instanceId.equals(got)) return "The address reached a different Zyclipa (or an old one), not this app.";
            return null;
        } catch (javax.net.ssl.SSLException e) {
            return "The certificate at that address isn't trusted. Your reverse proxy needs a real certificate (for example from Let's Encrypt).";
        } catch (java.net.UnknownHostException e) {
            return "That host name doesn't exist (yet). Check the DNS record.";
        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException e) {
            return "Couldn't connect to that address. Check the port forwarding, firewall and that the reverse proxy is running.";
        } catch (Exception e) {
            return "The check failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
