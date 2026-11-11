package io.zyclipra.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where Zyclipra keeps its files. Everything lives under one per-user folder. */
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

    /** Default per-user location: %APPDATA%\Zyclipra on Windows, ~/.config/zyclipra elsewhere. */
    public static Path defaultRoot() {
        String env = System.getenv("ZYCLIPRA_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        String appData = System.getenv("APPDATA");
        if (Platform.isWindows() && appData != null) return Path.of(appData, "Zyclipra");
        if (Platform.isMac()) return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Zyclipra");
        return Path.of(System.getProperty("user.home"), ".config", "zyclipra");
    }

    public static Path defaultClipsDir() {
        Path videos = Path.of(System.getProperty("user.home"), "Videos");
        return (Files.isDirectory(videos) ? videos : Path.of(System.getProperty("user.home"))).resolve("Zyclipra");
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
