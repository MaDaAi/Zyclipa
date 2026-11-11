package io.zyclipra.app;

import java.nio.file.Path;

/** Command line switches. Parsed once, before JavaFX starts. */
public final class CliOptions {
    private static Path screenshot;
    private static Path selfTest;
    private static Path dataDir;
    private static boolean minimized;
    private static String page;

    private CliOptions() {}

    public static void parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--screenshot" -> { if (i + 1 < args.length) screenshot = Path.of(args[++i]).toAbsolutePath(); }
                case "--self-test" -> { if (i + 1 < args.length) selfTest = Path.of(args[++i]).toAbsolutePath(); }
                case "--data-dir" -> { if (i + 1 < args.length) dataDir = Path.of(args[++i]).toAbsolutePath(); }
                case "--minimized" -> minimized = true;
                case "--page" -> { if (i + 1 < args.length) page = args[++i]; }
                default -> { }
            }
        }
    }

    public static Path screenshot() { return screenshot; }
    public static Path selfTest() { return selfTest; }
    public static Path dataDir() { return dataDir; }
    public static boolean minimized() { return minimized; }
    /** Section to open first (home, clips, mixer, soundboard, settings). */
    public static String page() { return page; }
    /** True when the app runs unattended for a check and must not steal focus. */
    public static boolean automated() { return screenshot != null || selfTest != null; }
}
