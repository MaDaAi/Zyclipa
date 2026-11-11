package io.zyclipra.ui;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import io.zyclipra.app.AppContext;
import io.zyclipra.core.EventBus;
import io.zyclipra.core.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Small always-on-top pill in the top-right corner while recording ("● REC 01:23"), and a short
 * flash when a replay or screenshot is saved. It is click-through and never takes focus.
 * Works over windowed/borderless games; exclusive full-screen games hide all overlays.
 */
public final class RecordingOverlay {
    private final AppContext ctx;
    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final Label text = new Label();
    private final Timeline tick;
    private boolean styled;
    private PauseTransition flashTimer;

    public RecordingOverlay(AppContext ctx, Stage owner) {
        this.ctx = ctx;
        Circle dot = new Circle(5);
        dot.getStyleClass().add("rec-dot");
        text.getStyleClass().add("overlay-text");
        HBox box = new HBox(8, dot, text);
        box.getStyleClass().add("overlay-box");
        Scene s = new Scene(box);
        s.setFill(Color.TRANSPARENT);
        s.getStylesheets().add(RecordingOverlay.class.getResource("/ui/zyclipra.css").toExternalForm());
        box.setStyle("-accent: " + ctx.settings.general.accent + ";");
        stage.setScene(s);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Zyclipra overlay");
        stage.initOwner(owner);
        tick = new Timeline(new KeyFrame(Duration.millis(500), e -> update()));
        tick.setCycleCount(Timeline.INDEFINITE);
        ctx.bus.on(EventBus.RECORDING_CHANGED, x -> Ui.fx(this::update));
        ctx.bus.on(EventBus.NOTIFY, m -> Ui.fx(() -> {
            String msg = String.valueOf(m);
            if (msg.startsWith("Replay saved")) flash("Replay saved");
            else if (msg.startsWith("Screenshot saved")) flash("Screenshot saved");
        }));
    }

    private void update() {
        boolean rec = ctx.recorder.recording();
        if (rec && ctx.settings.general.showOverlay) {
            text.setText("REC " + Ui.duration(ctx.recorder.elapsedMs() / 1000.0));
            showAtCorner();
            tick.play();
        } else if (flashTimer == null || flashTimer.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            tick.stop();
            stage.hide();
        }
    }

    private void flash(String msg) {
        if (!ctx.settings.general.showOverlay) return;
        text.setText(msg);
        showAtCorner();
        if (flashTimer != null) flashTimer.stop();
        flashTimer = new PauseTransition(Duration.seconds(2));
        flashTimer.setOnFinished(e -> update());
        flashTimer.play();
    }

    private void showAtCorner() {
        Rectangle2D b = Screen.getPrimary().getVisualBounds();
        stage.sizeToScene();
        if (!stage.isShowing()) stage.show();
        stage.setX(b.getMaxX() - stage.getWidth() - 24);
        stage.setY(b.getMinY() + 24);
        if (!styled && Platform.isWindows()) {
            styled = true;
            try {
                WinDef.HWND h = User32.INSTANCE.FindWindow(null, "Zyclipra overlay");
                if (h != null) {
                    int ex = User32.INSTANCE.GetWindowLong(h, WinUser.GWL_EXSTYLE);
                    // WS_EX_TRANSPARENT (click-through) | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW (no taskbar button)
                    User32.INSTANCE.SetWindowLong(h, WinUser.GWL_EXSTYLE, ex | 0x20 | 0x08000000 | 0x80);
                }
            } catch (Throwable ignored) { }
        }
    }

    public void close() { tick.stop(); stage.hide(); }
}
