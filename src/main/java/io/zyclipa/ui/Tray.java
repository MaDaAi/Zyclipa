package io.zyclipa.ui;

import io.zyclipa.app.AppContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.logging.Logger;

/**
 * The tray icon (by the clock) that keeps Zyclipa reachable while its window is closed. Right-click shows Zyclipa's
 * own menu (styled like the app, light or dark) instead of the plain Windows one; double-click opens the window.
 */
public final class Tray {
    private static final Logger LOG = Logger.getLogger(Tray.class.getName());
    private static TrayIcon icon;
    private static Stage menu, owner;

    private Tray() { }

    /** Adds the tray icon; false when Windows has no tray (then closing the window quits as before). */
    public static boolean install(Stage stage, AppContext ctx) {
        try {
            if (!SystemTray.isSupported()) return false;
            java.awt.Image img;
            try (var in = Tray.class.getResourceAsStream("/ui/icon-32.png")) { img = javax.imageio.ImageIO.read(in); }
            icon = new TrayIcon(img, "Zyclipa");
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> show(stage));     // double-click
            icon.addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) {
                        double x = e.getXOnScreen(), y = e.getYOnScreen();
                        Platform.runLater(() -> openMenu(stage, ctx, x, y));
                    }
                }
            });
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (Throwable e) {
            LOG.info("No tray icon: " + e);
            return false;
        }
    }

    /** Builds and shows the menu with its bottom-right corner at the cursor (above the taskbar), kept on screen. */
    static void openMenu(Stage stage, AppContext ctx, double x, double y) {
        if (menu != null) menu.hide();
        if (owner == null) {
            // An invisible utility window owns the menu so it never shows up in the taskbar.
            owner = new Stage(StageStyle.UTILITY);
            owner.setOpacity(0);
            owner.setWidth(1);
            owner.setHeight(1);
            owner.setX(-10000);
            owner.show();
        }
        menu = new Stage(StageStyle.TRANSPARENT);
        menu.initOwner(owner);
        menu.setAlwaysOnTop(true);
        VBox box = content(stage, ctx);
        box.getStyleClass().add("tray-menu");
        VBox root = new VBox(box);
        root.setPadding(new Insets(8));       // room for the shadow
        root.setStyle("-fx-background-color: transparent;");
        root.getStyleClass().add("tray-root");
        if (Theme.light()) root.getStyleClass().add("light");
        Scene sc = new Scene(root);
        sc.setFill(Color.TRANSPARENT);
        sc.getStylesheets().add(Tray.class.getResource("/ui/zyclipa.css").toExternalForm());
        menu.setScene(sc);
        menu.focusedProperty().addListener((o, a, b) -> { if (!b) menu.hide(); });
        sc.setOnKeyPressed(k -> { if (k.getCode() == javafx.scene.input.KeyCode.ESCAPE) menu.hide(); });
        menu.setOpacity(0);
        menu.show();
        Rectangle2D area = Screen.getScreensForRectangle(x, y, 1, 1).stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        double w = menu.getWidth(), h = menu.getHeight();
        menu.setX(Math.max(area.getMinX(), Math.min(x - w + 8, area.getMaxX() - w)));
        menu.setY(Math.max(area.getMinY(), Math.min(y - h + 8, area.getMaxY() - h)));
        menu.setOpacity(1);
        menu.toFront();
        menu.requestFocus();
    }

    /** The menu as it looks, for the screenshot check (no tray needed). */
    public static javafx.scene.Parent preview(AppContext ctx) {
        VBox box = content(new Stage(), ctx);
        box.getStyleClass().add("tray-menu");
        VBox root = new VBox(box);
        root.setPadding(new Insets(16));
        root.getStyleClass().add("legal-window");
        if (Theme.light()) root.getStyleClass().add("light");
        return root;
    }

    private static VBox content(Stage stage, AppContext ctx) {
        ImageView logo = new ImageView();
        try (var in = Tray.class.getResourceAsStream("/ui/logo-64.png")) { if (in != null) logo.setImage(new Image(in, 22, 22, true, true)); }
        catch (Exception ignored) { }
        boolean replayOn = ctx.replay.running();
        Label name = new Label("Zyclipa");
        name.getStyleClass().add("tray-title");
        Label state = new Label(replayOn ? "Instant replay is on" : "Instant replay is off");
        state.getStyleClass().add(replayOn ? "tray-state-on" : "tray-state");
        HBox head = new HBox(10, logo, new VBox(1, name, state));
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("tray-head");

        Button clip = item(Icons.CLIP_IT, "Clip it", hotkey(ctx), () -> ctx.clipNow());

        Button replay = item(Icons.RECORD, replayOn ? "Turn instant replay off" : "Turn instant replay on", null, () -> ctx.replay.toggle());
        Button open = item(Icons.CLIPS, "Open Zyclipa", null, () -> show(stage));
        Button quit = item(null, "Quit Zyclipa", null, () -> { remove(); Platform.exit(); });
        quit.getStyleClass().add("tray-quit");
        Region line = new Region();
        line.getStyleClass().add("tray-sep");
        Region line2 = new Region();
        line2.getStyleClass().add("tray-sep");
        VBox v = new VBox(2, head, line, open, clip, replay, line2, quit);
        v.setPrefWidth(250);
        return v;
    }

    private static String hotkey(AppContext ctx) {
        String k = ctx.settings.hotkeys.get("saveReplay");
        return k == null || k.isBlank() ? null : k;
    }

    private static Button item(String iconPath, String text, String hint, Runnable action) {
        Node graphic = iconPath == null ? new Region() : Icons.of(iconPath, 16);
        if (iconPath == null) ((Region) graphic).setPrefSize(16, 16);
        Label t = new Label(text);
        Region grow = new Region();
        javafx.scene.layout.HBox.setHgrow(grow, javafx.scene.layout.Priority.ALWAYS);
        HBox row = new HBox(10, graphic, t, grow);
        if (hint != null) { Label h = new Label(hint); h.getStyleClass().add("tray-hint"); row.getChildren().add(h); }
        row.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button(null, row);
        b.getStyleClass().setAll("tray-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> { if (menu != null) menu.hide(); action.run(); });
        return b;
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
