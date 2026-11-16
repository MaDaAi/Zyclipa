package io.zyclipa.recording;

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
            "winword", "excel", "powerpnt", "vlc", "java", "javaw", "zyclipa", "craft-anything");

    /** Games people often play outside the usual game folders. */
    private static final Set<String> KNOWN_GAMES = Set.of("robloxplayerbeta", "valorant-win64-shipping", "fortniteclient-win64-shipping", "cs2", "csgo",
            "league of legends", "r5apex", "r5apex_dx12", "overwatch", "rocketleague", "minecraft.windows", "gta5", "gta5_enhanced",
            "rdr2", "eldenring", "destiny2", "dota2", "rainbowsix", "rainbowsix_vulkan", "tslgame", "marvel-win64-shipping", "cod", "bf2042",
            "deadbydaylight-win64-shipping", "helldivers2", "fc25", "fc26", "eafc25", "eafc26", "warframe.x64", "pathofexile", "pathofexile_x64");
    /** Folders games are installed in (lower case). */
    private static final String[] GAME_DIRS = {"\\steamapps\\common\\", "\\epic games\\", "\\riot games\\", "\\xboxgames\\",
            "\\gog galaxy\\games\\", "\\ubisoft game launcher\\games\\", "\\ea games\\", "\\rockstar games\\", "\\roblox\\versions\\"};

    /**
     * Stricter than {@link #gameOf}: is the app in front really a game? Known games, anything in a game library folder,
     * or a full-screen app that isn't an everyday program.
     */
    public static boolean isLikelyGame(io.zyclipa.platform.Desktop.Foreground f) {
        String p = f.process() == null ? "" : f.process().toLowerCase(Locale.ROOT);
        if (p.isBlank() || NOT_GAMES.contains(p) || isSelf(p)) return false;
        if (KNOWN_GAMES.contains(p)) return true;
        String path = f.path() == null ? "" : f.path().toLowerCase(Locale.ROOT);
        for (String d : GAME_DIRS) if (path.contains(d)) return true;
        return f.fillsScreen() && !path.contains("\\windows\\") && !path.contains("\\windowsapps\\");
    }

    public static boolean isSelf(String process) {
        String p = process == null ? "" : process.toLowerCase(Locale.ROOT);
        return p.equals("java") || p.equals("javaw") || p.equals("zyclipa");
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
