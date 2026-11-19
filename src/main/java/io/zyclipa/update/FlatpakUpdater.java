package io.zyclipa.update;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app Flatpak updates through the Flatpak portal (org.freedesktop.portal.Flatpak), the same way MultiMini does it.
 * The app never runs host commands: it asks the portal to update its own installation from the configured Flatpak
 * remote (the signed Zyclipa repo on GitHub Pages), reports progress, then asks the portal to start the newest
 * installed version. D-Bus is spoken through the runtime's {@code gdbus} tool.
 */
public final class FlatpakUpdater {
    static final String BUS = "org.freedesktop.portal.Flatpak", PATH = "/org/freedesktop/portal/Flatpak";
    static final String MONITOR = "org.freedesktop.portal.Flatpak.UpdateMonitor";
    static final int RUNNING = 0, EMPTY = 1, DONE = 2, FAILED = 3, SPAWN_LATEST_VERSION = 2;
    public static final String FLATPAKREF = "https://madaai.github.io/Zyclipa/zyclipa.flatpakref";
    static final String NO_SOURCE_HINT = "This copy was installed from a file without an update source. Install once with:\n"
            + "flatpak install --user --reinstall " + FLATPAKREF + "\nAfter that Zyclipa updates itself. Your saved data is kept.";

    public enum State { DONE, NONE, FAILED }
    public record Result(State state, String message) { }

    private FlatpakUpdater() { }

    /** True when running inside the Flatpak (the portal is only there). */
    public static boolean inFlatpak() { return System.getenv("FLATPAK_ID") != null; }

    static String describeFailure(String message) {
        String t = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        for (String w : List.of("remote", "origin", "no such ref", "not installed", "not found")) if (t.contains(w)) return NO_SOURCE_HINT;
        if (t.contains("permission")) return "This update needs new permissions, so Flatpak must install it: flatpak update io.github.MaDaAi.Zyclipa";
        return message == null || message.isBlank() ? "Flatpak could not update Zyclipa" : message;
    }

    private static String gdbus(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("gdbus", "call", "--session", "--dest", BUS));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException(out);
        return out;
    }

    /** Parses one Progress signal line from {@code gdbus monitor}: status, progress and error text. */
    static int[] parseProgress(String line, StringBuilder error) {
        if (!line.contains(".Progress")) return null;
        int status = intField(line, "status", RUNNING), progress = intField(line, "progress", 0);
        Matcher e = Pattern.compile("'error_message': <'([^']*)'>").matcher(line);
        if (e.find()) error.append(e.group(1));
        return new int[]{status, progress};
    }

    private static int intField(String line, String key, int def) {
        Matcher m = Pattern.compile("'" + key + "': <uint32 (\\d+)>").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    /** Updates this installation (blocking, up to 30 minutes); {@code progress} gets 0–100. */
    public static Result update(IntConsumer progress) {
        String handle = null;
        Process monitor = null;
        try {
            String created = gdbus("--object-path", PATH, "--method", BUS + ".CreateUpdateMonitor", "{}");
            Matcher h = Pattern.compile("'(/[^']+)'").matcher(created);
            if (!h.find()) return new Result(State.FAILED, describeFailure(created));
            handle = h.group(1);
            monitor = new ProcessBuilder("gdbus", "monitor", "--session", "--dest", BUS, "--object-path", handle).redirectErrorStream(true).start();
            BufferedReader in = new BufferedReader(new InputStreamReader(monitor.getInputStream(), StandardCharsets.UTF_8));
            Thread.sleep(300);                                     // listen before asking, so no signal is missed
            gdbus("--object-path", handle, "--method", MONITOR + ".Update", "", "{}");
            long end = System.currentTimeMillis() + 30 * 60_000L;
            StringBuilder error = new StringBuilder();
            for (String line; System.currentTimeMillis() < end && (line = in.readLine()) != null; ) {
                int[] p = parseProgress(line, error);
                if (p == null) continue;
                if (p[0] == RUNNING) { progress.accept(p[1]); continue; }
                if (p[0] == DONE) { progress.accept(100); return new Result(State.DONE, ""); }
                if (p[0] == EMPTY) return new Result(State.NONE, "Zyclipa is already up to date in Flatpak");
                return new Result(State.FAILED, describeFailure(error.toString()));
            }
            return new Result(State.FAILED, "Flatpak update timed out");
        } catch (Exception e) {
            return new Result(State.FAILED, describeFailure(e.getMessage()));
        } finally {
            if (monitor != null) monitor.destroy();
            if (handle != null) try { gdbus("--object-path", handle, "--method", MONITOR + ".Close"); } catch (Exception ignored) { }
        }
    }

    /** Starts the newest installed version in a fresh sandbox; the caller then quits this one. */
    public static void restart() throws Exception {
        gdbus("--object-path", PATH, "--method", BUS + ".Spawn", "b'/'", "[b'zyclipa']", "{}", "{}",
                String.valueOf(SPAWN_LATEST_VERSION), "{}");
    }
}
