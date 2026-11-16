package io.zyclipa.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.zyclipa.clips.Clip;
import io.zyclipa.core.Json;
import io.zyclipa.core.Settings;
import io.zyclipa.recording.RecordingService;
import io.zyclipa.ui.MainWindow;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Unattended checks used by builds: --screenshot and --self-test. Neither shows the window or steals focus. */
final class Checks {
    private Checks() {}

    /** Lets the UI settle for ~2 s (about 120 frames at 60 Hz), then saves a PNG of the main window's scene. */
    static void screenshot(MainWindow win, Path out) {
        Thread t = new Thread(() -> {
            try {
                if (System.getProperty("zyclipa.trace") != null) traceLayout(win);
                Thread.sleep(2200);
                // Diagnostic: keep the unattended app (and its dashboard, if on) running for N more seconds.
                Thread.sleep(1000L * Math.min(1800, Math.max(0, Integer.getInteger("zyclipa.hold", 0))));
                if (System.getProperty("zyclipa.demo") != null) {
                    // Diagnostic: lean the game/chat balance and open the first device picker, so both show in the pictures.
                    onFx(() -> {
                        win.scene().getRoot().applyCss(); // builds the skins, so scroll-pane contents can be found
                        win.scene().getRoot().layout();
                        Node bal = win.scene().getRoot().lookup(".balance-slider");
                        if (bal instanceof javafx.scene.control.Slider s) s.setValue(0.45);
                        // Show the 9:16 frame with the dimmed sides, if the clip editor is open.
                        win.scene().getRoot().lookupAll(".canvas-choice").stream().skip(1).findFirst()
                                .ifPresent(n -> ((javafx.scene.control.ToggleButton) n).setSelected(true));
                        Node dev = win.scene().getRoot().lookupAll(".mix-col-device").stream().findFirst().orElse(null);
                        System.out.println("demo: balance=" + bal + " device=" + dev);
                        if (dev != null) dev.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                                javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, true, null));
                        return null;
                    });
                    Thread.sleep(1500);
                    // The window isn't shown, so a real popup can't open; draw the same panels in off-screen scenes instead.
                    for (String ch : List.of("game", "mic")) {
                        javafx.scene.Scene sc = onFx(() -> {
                            if (!(win.view("mixer") instanceof io.zyclipa.ui.MixerView mv)) return null;
                            javafx.scene.Scene s2 = new javafx.scene.Scene(mv.devicePanel(ch, () -> { }));
                            s2.getStylesheets().addAll(win.scene().getStylesheets());
                            return s2;
                        });
                        if (sc == null) continue;
                        Thread.sleep(1500); // the device list loads in the background
                        WritableImage pop = onFx(() -> sc.snapshot(null));
                        save(pop, out.resolveSibling(out.getFileName().toString().replace(".png", "-" + ch + ".png")));
                    }
                }
                WritableImage img = onFx(() -> win.scene().snapshot(null));
                save(img, out);
                System.out.println("Screenshot saved to " + out);
            } catch (Exception e) {
                e.printStackTrace();
            }
            Platform.exit();
        }, "screenshot");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Diagnostic (-Dzyclipa.trace): for ~8 s, records where every visible region sits and prints the ones
     * that move or resize, so layout "jumping" can be measured. Presses Space on the clip editor at 3 s.
     */
    static void traceLayout(MainWindow win) throws Exception {
        java.util.Map<Node, javafx.geometry.Bounds> last = new java.util.IdentityHashMap<>();
        for (int tick = 0; tick < 32; tick++) {
            int t = tick;
            String report = onFx(() -> {
                if (t == 12) {
                    Node ed = win.scene().getRoot().lookup(".editor");
                    if (ed != null) ed.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, " ", " ",
                            javafx.scene.input.KeyCode.SPACE, false, false, false, false));
                }
                if (t % 8 == 4) { // what a window resize or hover does: every container lays out again
                    java.util.ArrayDeque<Node> all = new java.util.ArrayDeque<>(List.of(win.scene().getRoot()));
                    while (!all.isEmpty()) if (all.poll() instanceof javafx.scene.Parent p) { p.requestLayout(); all.addAll(p.getChildrenUnmodifiable()); }
                }
                win.scene().getRoot().applyCss();
                win.scene().getRoot().layout(); // the window is never shown, so run the layout pass a live window would
                StringBuilder sb = new StringBuilder();
                java.util.ArrayDeque<Node> q = new java.util.ArrayDeque<>(List.of(win.scene().getRoot()));
                while (!q.isEmpty()) {
                    Node n = q.poll();
                    if (!n.isVisible()) continue;
                    if (n instanceof javafx.scene.Parent p) q.addAll(p.getChildrenUnmodifiable());
                    if (!(n instanceof javafx.scene.layout.Region)) continue;
                    javafx.geometry.Bounds b = n.localToScene(n.getLayoutBounds());
                    javafx.geometry.Bounds o = last.put(n, b);
                    if (o != null && (Math.abs(o.getMinX() - b.getMinX()) > 0.5 || Math.abs(o.getMinY() - b.getMinY()) > 0.5
                            || Math.abs(o.getWidth() - b.getWidth()) > 0.5 || Math.abs(o.getHeight() - b.getHeight()) > 0.5))
                        sb.append(String.format("  %s%s  (%.0f,%.0f %.0fx%.0f) -> (%.0f,%.0f %.0fx%.0f)%n", n.getClass().getSimpleName(),
                                n.getStyleClass(), o.getMinX(), o.getMinY(), o.getWidth(), o.getHeight(), b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
                }
                return sb.toString();
            });
            if (!report.isEmpty()) System.out.print("[trace " + (t * 250) + " ms]\n" + report);
            Thread.sleep(250);
        }
    }

    static void save(WritableImage img, Path out) throws Exception {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        ImageIO.write(bi, "png", out.toFile());
    }

    static <T> T onFx(Supplier<T> s) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { f.complete(s.get()); } catch (Throwable e) { f.completeExceptionally(e); }
        });
        return f.get(20, TimeUnit.SECONDS);
    }

    static JsonObject step(String name, boolean ok, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("step", name);
        o.addProperty("passed", ok);
        o.addProperty("detail", detail);
        return o;
    }

    /**
     * Drives the app like a person would: moves through every section with the sidebar, then
     * uses the main actions (screenshot, record, instant replay, soundboard) and checks the
     * on-screen status reflects them. Results are measured from the app's own state and files.
     */
    static void selfTest(AppContext ctx, MainWindow win, Path out) {
        Thread t = new Thread(() -> {
            JsonArray steps = new JsonArray();
            try {
                Thread.sleep(1500);
                // ---- move: navigate every section through the sidebar ----
                StringBuilder visited = new StringBuilder();
                boolean moveOk = true;
                for (String id : win.viewIds()) {
                    onFx(() -> {
                        Node n = win.root().lookupAll(".rail-item").stream()
                                .filter(b -> ((javafx.scene.control.ToggleButton) b).getAccessibleText().startsWith(win.view(id).title() + " (")).findFirst().orElseThrow();
                        ((javafx.scene.control.ToggleButton) n).fire();
                        return null;
                    });
                    Thread.sleep(250);
                    String now = onFx(win::currentId);
                    moveOk &= now.equals(id);
                    visited.append(now).append(' ');
                }
                onFx(() -> { win.show("clips"); return null; });
                steps.add(step("move", moveOk, "navigated sidebar through " + visited.toString().trim()));

                // ---- act: real actions with real output ----
                StringBuilder act = new StringBuilder();
                boolean actOk = true;
                Clip shot = ctx.screenshots.capture().get(30, TimeUnit.SECONDS);
                long shotSize = Files.size(ctx.library.file(shot));
                act.append("screenshot ").append(shot.width).append('x').append(shot.height).append(" (").append(shotSize).append(" B); ");
                actOk &= shotSize > 1000;

                boolean sawRecPill = false;
                if (ctx.capture.ffmpeg().isPresent()) {
                    ctx.recorder.start().get(30, TimeUnit.SECONDS);
                    boolean started = ctx.recorder.state() == RecordingService.State.RECORDING;
                    Thread.sleep(3200);
                    sawRecPill = onFx(() -> { win.show("clips"); return win.captureStatus(); }).contains("RECORDING");
                    ctx.recorder.stop().get(30, TimeUnit.SECONDS);
                    Clip rec = ctx.library.all().stream().filter(c -> c.kind.equals("recording")).findFirst().orElse(null);
                    boolean recOk = started && rec != null && rec.durationSec > 2 && rec.sizeBytes > 0;
                    act.append("recorded ").append(rec == null ? "nothing" : Math.round(rec.durationSec * 10) / 10.0 + " s " + rec.width + "x" + rec.height
                            + " " + rec.videoCodec + " with " + ctx.capture.encoder()
                            + ", sound tracks " + rec.audioTrackNames).append("; ");
                    actOk &= recOk;
                    // Game audio and the soundboard get their own tracks (mic/chat are off in unattended runs).
                    if (io.zyclipa.core.Platform.isWindows()) actOk &= rec.audioTrackNames.contains("Game audio") && rec.audioTrackNames.contains("Soundboard");

                    ctx.settings.recording.replaySeconds = 15;
                    ctx.replay.start().get(30, TimeUnit.SECONDS);
                    Thread.sleep(7000);
                    Clip rep = ctx.replay.save(15).get(60, TimeUnit.SECONDS);
                    ctx.replay.stop().get(30, TimeUnit.SECONDS);
                    boolean repOk = rep != null && rep.durationSec >= 4;
                    act.append("instant replay saved ").append(rep == null ? "nothing" : Math.round(rep.durationSec * 10) / 10.0 + " s").append("; ");
                    actOk &= repOk;
                } else {
                    act.append("FFmpeg missing: recording skipped; ");
                    actOk = false;
                }

                // soundboard: import a generated tone, play it, stop it
                Path wav = ctx.paths.cache().resolve("selftest-tone.wav");
                writeTone(wav);
                Settings.Sound snd = ctx.soundboard.importFile(wav, "General");
                ctx.soundboard.config().masterVolume = 0.0; // silent during the check
                ctx.soundboard.play(snd.id);
                Thread.sleep(300);
                boolean playing = ctx.soundboard.isPlaying(snd.id);
                ctx.soundboard.stopAll();
                Thread.sleep(200);
                boolean stopped = !ctx.soundboard.isPlaying(snd.id);
                act.append("soundboard imported \"").append(snd.name).append("\" (").append(Math.round(ctx.soundboard.durationSec(snd) * 10) / 10.0)
                        .append(" s), playing=").append(playing).append(", stopped=").append(stopped).append("; ");
                actOk &= playing && stopped;

                // mixer: change a channel and read it back
                double before = ctx.mixer.channel("media").volume;
                ctx.mixer.setChannel("media", 0.42, false);
                boolean mixOk = Math.abs(ctx.mixer.channel("media").volume - 0.42) < 1e-9;
                ctx.mixer.setChannel("media", before, false);
                act.append("mixer channel set=").append(mixOk).append(", ").append(ctx.mixer.apps().size()).append(" app sessions seen");
                actOk &= mixOk;
                steps.add(step("act", actOk, act.toString()));

                // ---- hud: the on-screen status shows what happened ----
                AtomicReference<String> hud = new AtomicReference<>();
                boolean hudOk = onFx(() -> {
                    win.show("clips");
                    win.root().applyCss();
                    win.root().layout();
                    String rec = win.captureStatus(), rep = win.replayStatus();
                    long cards = win.root().lookupAll(".clip-card").size();
                    boolean labels = !win.root().lookupAll(".label").stream().map(n -> ((Label) n).getText()).filter(s -> s != null && s.startsWith("ALL CLIPS (")).toList().isEmpty();
                    hud.set("capture bar [" + rec + " | " + rep + "], " + cards + " clip cards, clip count header drawn=" + labels);
                    return !rec.isBlank() && !rep.isBlank() && cards > 0 && labels;
                });
                hudOk &= sawRecPill || ctx.capture.ffmpeg().isEmpty();
                steps.add(step("hud", hudOk, hud.get() + (sawRecPill ? ", RECORDING timer shown in the capture bar while recording" : ", recording timer not seen")));
            } catch (Throwable e) {
                steps.add(step("error", false, e.toString()));
            }
            try {
                JsonObject res = new JsonObject();
                res.addProperty("version", 1);
                res.add("steps", steps);
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                Files.writeString(out, Json.GSON.toJson(res));
                System.out.println(Json.GSON.toJson(res));
            } catch (Exception e) {
                e.printStackTrace();
            }
            Platform.exit();
        }, "self-test");
        t.setDaemon(true);
        t.start();
    }

    /** One second of a 440 Hz tone, 44.1 kHz mono (so import has to convert it). */
    static void writeTone(Path f) throws Exception {
        int rate = 44100;
        byte[] pcm = new byte[rate * 2];
        for (int i = 0; i < rate; i++) {
            short s = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000);
            pcm[i * 2] = (byte) s;
            pcm[i * 2 + 1] = (byte) (s >> 8);
        }
        AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, rate)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, f.toFile());
        }
    }

    static List<String> unused() { return List.of(); }
}
