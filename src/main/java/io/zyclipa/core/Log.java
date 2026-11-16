package io.zyclipa.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Small wrapper over java.util.logging writing a rotating file in the data folder. */
public final class Log {
    private static final Logger ROOT = Logger.getLogger("zyclipa");

    private Log() {}

    public static void init(Path logDir) {
        try {
            FileHandler fh = new FileHandler(logDir.resolve("zyclipa-%g.log").toString(), 2_000_000, 3, true);
            fh.setFormatter(new Formatter() {
                @Override public String format(LogRecord r) {
                    String t = r.getThrown() == null ? "" : " :: " + r.getThrown();
                    return Instant.ofEpochMilli(r.getMillis()) + " " + r.getLevel() + " [" + r.getLoggerName() + "] "
                            + formatMessage(r) + t + System.lineSeparator();
                }
            });
            ROOT.addHandler(fh);
            ROOT.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("Logging to file disabled: " + e.getMessage());
        }
    }

    public static Logger get(Class<?> c) { return Logger.getLogger("zyclipa." + c.getSimpleName()); }
}
