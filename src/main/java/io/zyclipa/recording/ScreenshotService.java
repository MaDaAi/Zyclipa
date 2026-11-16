package io.zyclipa.recording;

import io.zyclipa.clips.Clip;
import io.zyclipa.clips.ClipLibrary;
import io.zyclipa.core.EventBus;
import io.zyclipa.core.Settings;
import io.zyclipa.platform.Desktop;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Screenshots into <clips>/Screenshots. Uses FFmpeg's screen grabber, or AWT when FFmpeg is missing. */
public final class ScreenshotService {
    private final Settings settings;
    private final CaptureEngine engine;
    private final ClipLibrary library;
    private final EventBus bus;

    public ScreenshotService(Settings settings, CaptureEngine engine, ClipLibrary library, EventBus bus) {
        this.settings = settings;
        this.engine = engine;
        this.library = library;
        this.bus = bus;
    }

    public CompletableFuture<Clip> capture() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path dir = Path.of(settings.recording.clipsDir).resolve("Screenshots");
                Files.createDirectories(dir);
                String source = engine.sourceName();
                Path out = RecordingService.uniqueName(dir, source, "png");
                Desktop.Display d = engine.display();
                boolean ok = false;
                if (engine.ffmpeg().isPresent()) {
                    Settings.Recording r = settings.recording;
                    List<String> in = "window".equals(r.captureMode) && !r.windowTitle.isBlank()
                            ? List.of("-f", "gdigrab", "-i", "title=" + r.windowTitle)
                            : List.of("-f", "gdigrab", "-offset_x", "" + d.x(), "-offset_y", "" + d.y(),
                            "-video_size", d.width() + "x" + d.height(), "-i", "desktop");
                    if (io.zyclipa.core.Platform.isWindows()) {
                        List<String> args = new java.util.ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
                        args.addAll(in);
                        args.addAll(List.of("-frames:v", "1", out.toString()));
                        ok = engine.require().run(args, 20).ok() && Files.exists(out);
                    }
                }
                if (!ok) {
                    BufferedImage img = new Robot().createScreenCapture(new Rectangle(d.x(), d.y(), d.width(), d.height()));
                    ImageIO.write(img, "png", out.toFile());
                }
                Clip c = library.add(out, "screenshot", source);
                bus.emit(EventBus.NOTIFY, "Screenshot saved: " + out.getFileName());
                return c;
            } catch (Exception e) {
                bus.emit(EventBus.ERROR, "Screenshot: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}
