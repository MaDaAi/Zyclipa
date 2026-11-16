package io.zyclipa.ui;

import io.zyclipa.app.AppContext;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.logging.Logger;

/**
 * The tray icon (by the clock) that keeps Zyclipa reachable while its window is closed: Open, Clip it, Quit.
 * Closing the window then only hides it, so hotkeys, instant replay and the dashboard keep working.
 */
public final class Tray {
    private static final Logger LOG = Logger.getLogger(Tray.class.getName());
    private static TrayIcon icon;

    private Tray() { }

    /** Adds the tray icon; false when Windows has no tray (then closing the window quits as before). */
    public static boolean install(Stage stage, AppContext ctx) {
        try {
            if (!SystemTray.isSupported()) return false;
            java.awt.Image img;
            try (var in = Tray.class.getResourceAsStream("/ui/icon-32.png")) { img = javax.imageio.ImageIO.read(in); }
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open Zyclipa");
            open.addActionListener(e -> show(stage));
            MenuItem clip = new MenuItem("Clip it (save instant replay)");
            clip.addActionListener(e -> Platform.runLater(ctx::clipNow));
            MenuItem quit = new MenuItem("Quit Zyclipa");
            quit.addActionListener(e -> { remove(); Platform.runLater(Platform::exit); });
            menu.add(open);
            menu.add(clip);
            menu.addSeparator();
            menu.add(quit);
            icon = new TrayIcon(img, "Zyclipa", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> show(stage));     // double-click
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (Throwable e) {
            LOG.info("No tray icon: " + e);
            return false;
        }
    }

    static void show(Stage s) {
        Platform.runLater(() -> { s.show(); s.setIconified(false); s.toFront(); s.requestFocus(); });
    }

    public static void message(String text) {
        if (icon != null) icon.displayMessage("Zyclipa", text, TrayIcon.MessageType.INFO);
    }

    public static void remove() {
        if (icon != null) {
            try { SystemTray.getSystemTray().remove(icon); } catch (Throwable ignored) { }
            icon = null;
        }
    }
}
