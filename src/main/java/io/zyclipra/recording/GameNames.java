package io.zyclipra.recording;

import java.util.Locale;
import java.util.Set;

/** Turns the window in front into a short, friendly game name (or null for non-games). */
public final class GameNames {
    private GameNames() {}

    /** Everyday apps that are never treated as the game being played. */
    private static final Set<String> NOT_GAMES = Set.of("explorer", "searchhost", "startmenuexperiencehost", "shellexperiencehost",
            "applicationframehost", "textinputhost", "lockapp", "taskmgr", "systemsettings", "cmd", "powershell", "windowsterminal",
            "chrome", "msedge", "firefox", "brave", "opera", "vivaldi", "discord", "spotify", "slack", "teams", "ms-teams", "zoom",
            "code", "notepad", "notepad++", "obs64", "steam", "steamwebhelper", "epicgameslauncher", "battle.net", "outlook",
            "winword", "excel", "powerpnt", "vlc", "java", "javaw", "zyclipra", "craft-anything");

    public static boolean isSelf(String process) {
        String p = process == null ? "" : process.toLowerCase(Locale.ROOT);
        return p.equals("java") || p.equals("javaw") || p.equals("zyclipra");
    }

    /** A friendly name for a game window, or null when the app in front isn't a game. */
    public static String gameOf(String title, String process) {
        String p = process == null ? "" : process.toLowerCase(Locale.ROOT);
        if (p.isBlank() || NOT_GAMES.contains(p)) return null;
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty()) return shorten(t);
        return pretty(process);
    }

    static String pretty(String process) {
        String p = process.replaceAll("(?i)(_?(win)?(64|32)|-win64-shipping|-shipping|beta|player|launcher)$", "");
        if (p.isBlank()) p = process;
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    /** Keeps the useful part of a title: "Game - Map name" -> "Game", max 40 characters. */
    public static String shorten(String title) {
        String t = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
        for (String sep : new String[]{" - ", " | ", " — ", " – "}) {
            int i = t.indexOf(sep);
            if (i > 2) t = t.substring(0, i);
        }
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > 40) t = t.substring(0, 40).trim();
        return t.isEmpty() ? "Desktop" : t;
    }
}
