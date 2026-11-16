package io.zyclipa.hotkeys;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** A parsed shortcut like "Ctrl+Alt+F9". Modifier bits match Win32 MOD_* values. */
public record Hotkey(int modifiers, int vk, String text) {
    public static final int ALT = 1, CTRL = 2, SHIFT = 4, WIN = 8;

    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("SPACE", 0x20), Map.entry("PAGEUP", 0x21), Map.entry("PAGEDOWN", 0x22), Map.entry("END", 0x23),
            Map.entry("HOME", 0x24), Map.entry("LEFT", 0x25), Map.entry("UP", 0x26), Map.entry("RIGHT", 0x27),
            Map.entry("DOWN", 0x28), Map.entry("PRINTSCREEN", 0x2C), Map.entry("INSERT", 0x2D), Map.entry("DELETE", 0x2E),
            Map.entry("PAUSE", 0x13), Map.entry("SCROLLLOCK", 0x91), Map.entry("TAB", 0x09), Map.entry("BACKSPACE", 0x08),
            Map.entry("ENTER", 0x0D), Map.entry("MINUS", 0xBD), Map.entry("EQUALS", 0xBB), Map.entry("COMMA", 0xBC),
            Map.entry("PERIOD", 0xBE), Map.entry("SLASH", 0xBF), Map.entry("BACKQUOTE", 0xC0), Map.entry("SEMICOLON", 0xBA),
            Map.entry("OPENBRACKET", 0xDB), Map.entry("CLOSEBRACKET", 0xDD), Map.entry("BACKSLASH", 0xDC), Map.entry("QUOTE", 0xDE));

    /** Parses "Ctrl+Shift+F9", "Alt+1", "NumPad5", or a single key like "Delete". */
    public static Optional<Hotkey> parse(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        int mods = 0;
        Integer vk = null;
        String[] parts = s.toUpperCase(Locale.ROOT).replace(" ", "").split("\\+");
        for (String p : parts) {
            switch (p) {
                case "CTRL", "CONTROL" -> mods |= CTRL;
                case "ALT" -> mods |= ALT;
                case "SHIFT" -> mods |= SHIFT;
                case "WIN", "META", "WINDOWS" -> mods |= WIN;
                default -> {
                    if (vk != null) return Optional.empty();
                    vk = key(p);
                    if (vk == null) return Optional.empty();
                }
            }
        }
        if (vk == null) return Optional.empty();
        return Optional.of(new Hotkey(mods, vk, normalise(mods, s)));
    }

    static Integer key(String p) {
        if (p.length() == 1) {
            char c = p.charAt(0);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return (int) c;
            return null;
        }
        if (p.matches("F([1-9]|1[0-9]|2[0-4])")) return 0x70 + Integer.parseInt(p.substring(1)) - 1;
        if (p.matches("NUMPAD[0-9]")) return 0x60 + (p.charAt(6) - '0');
        if (p.startsWith("DIGIT") && p.length() == 6) return key(p.substring(5));
        return NAMED.get(p);
    }

    private static String normalise(int mods, String s) {
        StringBuilder b = new StringBuilder();
        if ((mods & CTRL) != 0) b.append("Ctrl+");
        if ((mods & ALT) != 0) b.append("Alt+");
        if ((mods & SHIFT) != 0) b.append("Shift+");
        if ((mods & WIN) != 0) b.append("Win+");
        String[] parts = s.split("\\+");
        String k = parts[parts.length - 1].trim();
        b.append(k.length() == 1 ? k.toUpperCase(Locale.ROOT) : Character.toUpperCase(k.charAt(0)) + k.substring(1));
        return b.toString();
    }
}
