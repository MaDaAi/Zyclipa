package io.zyclipa.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where Zyclipa keeps its files. Everything lives under one per-user folder. */
public final class AppPaths {
    private final Path root;

    public AppPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            for (Path p : new Path[]{this.root, logs(), cache(), soundsDir(), replayDir(), thumbnails()}) {
                Files.createDirectories(p);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data folder " + this.root, e);
        }
    }

    /** Default per-user location: %APPDATA%\Zyclipa on Windows, ~/.config/zyclipa elsewhere. */
    public static Path defaultRoot() {
        String env = System.getenv("ZYCLIPA_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        String appData = System.getenv("APPDATA");
        if (Platform.isWindows() && appData != null) return migrated(Path.of(appData, "Zyclipra"), Path.of(appData, "Zyclipa"));
        if (Platform.isMac()) return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Zyclipa");
        return Path.of(System.getProperty("user.home"), ".config", "zyclipa");
    }

    /** The app was called Zyclipra before 0.1.0: move its data folder to the new name once (same drive, so a rename). */
    static Path migrated(Path old, Path now) {
        if (Files.exists(now) || !Files.isDirectory(old)) return now;
        try {
            Files.move(old, now);
            return now;
        } catch (java.io.IOException e) {
            return old;   // in use (the old version is still open): keep using it this time
        }
    }

    /** Exports go next to the clips folder (not inside it, so they don't show up as clips). */
    public static Path defaultExportsDir() {
        return defaultClipsDir().resolveSibling("Zyclipa Exports");
    }

    /** The clips folder older versions made when the app was still called Zyclipra. */
    public static Path oldDefaultClipsDir() {
        return defaultClipsDir().resolveSibling("Zyclipra");
    }

    public static Path defaultClipsDir() {
        Path videos = Path.of(System.getProperty("user.home"), "Videos");
        return (Files.isDirectory(videos) ? videos : Path.of(System.getProperty("user.home"))).resolve("Zyclipa");
    }

    public Path root() { return root; }
    public Path settingsFile() { return root.resolve("settings.json"); }
    public Path logs() { return root.resolve("logs"); }
    public Path cache() { return root.resolve("cache"); }
    public Path soundsDir() { return root.resolve("sounds"); }
    public Path replayDir() { return cache().resolve("replay"); }
    public Path thumbnails() { return cache().resolve("thumbnails"); }
    public Path tokenFile() { return root.resolve("google-token.bin"); }
    public Path keystore() { return root.resolve("dashboard-tls.p12"); }
}
