package io.zyclipa.app;

import io.zyclipa.core.AppPaths;
import io.zyclipa.ui.MainWindow;
import io.zyclipa.ui.RecordingOverlay;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

/** JavaFX lifecycle: builds the context, shows the window, and handles --screenshot / --self-test. */
public final class ZyclipaApp extends Application {
    private AppContext ctx;
    private static volatile Stage mainStage;

    /** Brings the window back (e.g. when Zyclipa is started again while it runs in the tray). */
    public static void showWindow() {
        Stage s = mainStage;
        if (s != null) Platform.runLater(() -> { s.show(); s.setIconified(false); s.toFront(); s.requestFocus(); });
    }
    private RecordingOverlay overlay;

    @Override public void start(Stage stage) throws Exception {
        boolean automated = CliOptions.automated();
        Path data = CliOptions.dataDir();
        if (data == null) data = automated ? Files.createTempDirectory("zyclipa-check") : AppPaths.defaultRoot();
        if (CliOptions.screenshot() != null && "terms".equals(CliOptions.page())) {
            // Check mode: render the terms screen off-screen and save it.
            Platform.setImplicitExit(false);
            io.zyclipa.ui.TermsGate.show(stage, data, AppContext.VERSION, true, () -> { });
            stage.hide();
            Path out = CliOptions.screenshot();
            new Thread(() -> {
                try {
                    Thread.sleep(800);
                    javafx.scene.image.WritableImage img = Checks.onFx(() -> stage.getScene().snapshot(null));
                    Checks.save(img, out);
                } catch (Exception e) { e.printStackTrace(); }
                Runtime.getRuntime().halt(0);
            }).start();
            return;
        }
        // First run, after an update, or when the terms changed: nothing starts until the person accepts.
        if (!automated && !io.zyclipa.ui.TermsGate.accepted(data, AppContext.VERSION)) {
            boolean updated = Files.exists(data.resolve("settings.json"));
            Path d = data;
            io.zyclipa.ui.TermsGate.show(stage, data, AppContext.VERSION, updated, () -> {
                try { startApp(stage, d, false); }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
            return;
        }
        startApp(stage, data, automated);
    }

    private void startApp(Stage stage, Path data, boolean automated) throws Exception {
        mainStage = stage;
        ctx = new AppContext(data, !automated);
        if (automated && CliOptions.dataDir() == null) {
            ctx.settings.recording.clipsDir = data.resolve("Clips").toString();
            ctx.settings.recording.fps = 30;
            // Unattended checks never record the person's microphone or voice chat.
            ctx.settings.recording.recordMic = false;
            ctx.settings.recording.recordChatAudio = false;
        }
        io.zyclipa.ui.Theme.init(ctx.settings.general.theme);
        if (CliOptions.screenshot() != null && "tray".equals(CliOptions.page())) {
            // Check mode: render the tray menu and save it.
            javafx.scene.Scene sc = new javafx.scene.Scene(io.zyclipa.ui.Tray.preview(ctx));
            sc.getStylesheets().add(ZyclipaApp.class.getResource("/ui/zyclipa.css").toExternalForm());
            stage.setScene(sc);
            Path out = CliOptions.screenshot();
            new Thread(() -> {
                try { Thread.sleep(600); Checks.save(Checks.onFx(() -> sc.snapshot(null)), out); } catch (Exception e) { e.printStackTrace(); }
                Runtime.getRuntime().halt(0);
            }).start();
            return;
        }
        MainWindow win = new MainWindow(ctx);
        if (CliOptions.page() != null) {
            String[] pg = CliOptions.page().split(":");
            win.show(pg[0]);
            if (pg.length > 1 && win.view(pg[0]) instanceof io.zyclipa.ui.MixerView mv) mv.showTab(pg[1]);
            if (pg.length > 1 && win.view(pg[0]) instanceof io.zyclipa.ui.SettingsView sv) sv.open(pg[1]);
            if (pg.length > 1 && pg[1].equals("edit") && win.view(pg[0]) instanceof io.zyclipa.ui.ClipsView cv) {
                ctx.library.scan();
                ctx.library.all().stream().filter(c -> c.audioTracks > 1).findFirst()
                        .or(() -> ctx.library.all().stream().filter(io.zyclipa.clips.Clip::isVideo).findFirst())
                        .ifPresent(c -> cv.select(c.id));
            }
        }
        stage.setTitle("Zyclipa");
        stage.setMinWidth(1040);
        stage.setMinHeight(680);
        stage.setScene(win.scene());
        // Window, taskbar and Alt+Tab icon (drawn from packaging/zyclipa.svg); Windows picks the best size.
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            try (var in = ZyclipaApp.class.getResourceAsStream("/ui/icon-" + size + ".png")) {
                if (in != null) stage.getIcons().add(new Image(in));
            } catch (Exception ignored) { }
        }

        if (CliOptions.screenshot() != null) {
            // Rendered off-screen: the window is never shown, so a check can't steal focus or be hidden by other windows.
            Checks.screenshot(win, CliOptions.screenshot());
            return;
        }
        if (CliOptions.selfTest() != null) {
            Checks.selfTest(ctx, win, CliOptions.selfTest());
            return;
        }

        // Closing the window keeps Zyclipa running in the tray (hotkeys, instant replay, dashboard), unless turned off.
        boolean tray = io.zyclipa.ui.Tray.install(stage, ctx);
        if (tray) Platform.setImplicitExit(false);
        stage.setOnCloseRequest(e -> {
            if (tray && ctx.settings.general.runInBackground) {
                e.consume();
                stage.hide();
                if (!ctx.settings.general.trayHintShown) {
                    io.zyclipa.ui.Tray.message("Zyclipa is still running – it's here by the clock. Right-click it to quit.");
                    ctx.settings.general.trayHintShown = true;
                    ctx.store.save();
                }
            } else {
                io.zyclipa.ui.Tray.remove();
                Platform.exit();
            }
        });
        if (CliOptions.minimized() && tray) { /* start hidden in the tray */ }
        else {
            if (CliOptions.minimized()) stage.setIconified(true);
            stage.show();
        }
        overlay = new RecordingOverlay(ctx, stage);
        if (ctx.settings.recording.replayEnabled && ctx.capture.ffmpeg().isPresent()) ctx.replay.start();
        // Start from the volumes Windows already has, rather than forcing saved ones (avoids a sudden loud jump).
        new Thread(ctx.mixer::adoptEndpointLevels, "adopt-levels") {{ setDaemon(true); }}.start();
    }

    @Override public void stop() {
        io.zyclipa.ui.Tray.remove();
        if (overlay != null) overlay.close();
        if (ctx != null) ctx.shutdown();
        // FFmpeg children are stopped above; make sure no non-daemon thread keeps the JVM alive.
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(0);
        }, "exit-guard") {{ setDaemon(true); }}.start();
    }
}
