package io.zyclipra.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Loads and saves {@link Settings}. Saves are debounced (many slider moves = one write)
 * and atomic. A damaged file is kept as settings.json.broken and defaults are used.
 */
public final class SettingsStore implements AutoCloseable {
    private static final Logger LOG = Log.get(SettingsStore.class);
    private final Path file;
    private final Settings settings;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "settings-io");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;
    private String lastError;

    public SettingsStore(Path file) {
        this.file = file;
        this.settings = load(file);
    }

    private Settings load(Path f) {
        if (!Files.exists(f)) return new Settings().sanitize();
        try {
            Settings s = Json.read(f, Settings.class);
            if (s == null) throw new IOException("empty file");
            return s.sanitize();
        } catch (Exception e) {
            lastError = "Settings file was damaged and has been reset (" + e.getMessage() + ")";
            LOG.warning(lastError);
            try {
                Files.move(f, f.resolveSibling("settings.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
            return new Settings().sanitize();
        }
    }

    public Settings get() { return settings; }
    public String lastError() { return lastError; }

    /** Schedules a save 400 ms from now; repeated calls collapse into one write. */
    public synchronized void save() {
        if (pending != null) pending.cancel(false);
        pending = io.schedule(this::saveNow, 400, TimeUnit.MILLISECONDS);
    }

    public synchronized void saveNow() {
        try {
            synchronized (settings) {
                settings.sanitize();
                Json.writeAtomic(file, settings);
            }
        } catch (IOException e) {
            lastError = "Could not save settings: " + e.getMessage();
            LOG.warning(lastError);
        }
    }

    @Override public void close() {
        saveNow();
        io.shutdown();
    }
}
