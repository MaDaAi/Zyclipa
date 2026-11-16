package io.zyclipa.ui;

import javafx.scene.Parent;
import javafx.scene.paint.Color;

/**
 * Light / dark theme. The stylesheet switches with the "light" style class on the window's root; colours the code
 * draws itself (graphs, meters, waveforms) go through {@link #web(String)}, which converts them the same way
 * packaging/theme_css.py converts the stylesheet.
 */
public final class Theme {
    private static volatile boolean light;

    private Theme() { }

    public static boolean light() { return light; }

    /** "system" follows Windows' "app mode" setting; "light" / "dark" force one. */
    public static boolean resolve(String setting) {
        if ("light".equals(setting)) return true;
        if ("dark".equals(setting)) return false;
        if (io.zyclipa.core.Platform.isLinux()) return linuxPrefersLight();
        try {
            return com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme") == 1;
        } catch (Throwable e) {
            return false;   // not Windows, or no setting: dark
        }
    }

    /** Sets the theme before any window is built, so drawn colours start out right. */
    public static void init(String setting) { light = resolve(setting); }

    static boolean linuxPrefersLight() {
        String gtk = System.getenv("GTK_THEME");
        if (gtk != null && !gtk.isBlank()) return !gtk.toLowerCase(java.util.Locale.ROOT).contains("dark");
        try {
            Process p = new ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            return out.contains("prefer-light");
        } catch (Exception e) {
            return false;
        }
    }

    /** Applies the theme to a window's root (and remembers it for drawn colours). */
    public static void apply(Parent root, String setting) {
        light = resolve(setting);
        root.getStyleClass().remove("light");
        if (light) root.getStyleClass().add("light");
    }

    /** A drawn colour in the current theme. */
    public static Color web(String hex) {
        return Color.web(light ? light(hex) : hex);
    }

    /** Same conversion as packaging/theme_css.py (Python colorsys HLS). */
    static String light(String hex) {
        double r = Integer.parseInt(hex.substring(1, 3), 16) / 255.0, g = Integer.parseInt(hex.substring(3, 5), 16) / 255.0,
                b = Integer.parseInt(hex.substring(5, 7), 16) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), l = (max + min) / 2, h = 0, s = 0;
        if (max != min) {
            double d = max - min;
            s = l <= 0.5 ? d / (max + min) : d / (2.0 - max - min);
            double rc = (max - r) / d, gc = (max - g) / d, bc = (max - b) / d;
            h = r == max ? bc - gc : g == max ? 2.0 + rc - bc : 4.0 + gc - rc;
            h = ((h / 6.0) % 1.0 + 1.0) % 1.0;
        }
        double l2, s2;
        if (s < 0.30 || l < 0.22) {
            l2 = l < 0.5 ? 0.97 - (l - 0.08) * 0.55 : Math.max(0.10, 1.0 - l);
            s2 = s < 0.30 ? Math.min(s, 0.12) : s * 0.6;
        } else {
            l2 = Math.min(l, 0.45);
            s2 = s;
        }
        l2 = Math.max(0, Math.min(1, l2));
        s2 = Math.max(0, Math.min(1, s2));
        double m2 = l2 <= 0.5 ? l2 * (1.0 + s2) : l2 + s2 - l2 * s2, m1 = 2.0 * l2 - m2;
        double[] c = s2 == 0 ? new double[]{l2, l2, l2} : new double[]{v(m1, m2, h + 1 / 3.0), v(m1, m2, h), v(m1, m2, h - 1 / 3.0)};
        return String.format("#%02X%02X%02X", Math.round(c[0] * 255), Math.round(c[1] * 255), Math.round(c[2] * 255));
    }

    private static double v(double m1, double m2, double hue) {
        hue = ((hue % 1.0) + 1.0) % 1.0;
        if (hue < 1 / 6.0) return m1 + (m2 - m1) * hue * 6.0;
        if (hue < 0.5) return m2;
        if (hue < 2 / 3.0) return m1 + (m2 - m1) * (2 / 3.0 - hue) * 6.0;
        return m1;
    }
}
