package io.zyclipra.recording;

import io.zyclipra.core.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

/** A long-running FFmpeg child process that can be stopped cleanly (so MP4 files are finalised). */
final class FfmpegProcess {
    private static final Logger LOG = Log.get(FfmpegProcess.class);
    private final Process process;
    private final Deque<String> tail = new ArrayDeque<>();
    private volatile boolean stopping;

    FfmpegProcess(Ffmpeg ff, List<String> args, String name, IntConsumer onUnexpectedExit) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ff.path().toString());
        cmd.addAll(List.of("-hide_banner", "-nostats", "-loglevel", "warning", "-y"));
        cmd.addAll(args);
        LOG.info(name + ": " + String.join(" ", cmd));
        process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (tail) {
                        tail.addLast(line);
                        while (tail.size() > 40) tail.removeFirst();
                    }
                }
            } catch (IOException ignored) { }
            try {
                int code = process.waitFor();
                if (!stopping) {
                    LOG.warning(name + " exited unexpectedly with " + code + ": " + lastOutput());
                    onUnexpectedExit.accept(code);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, name + "-log");
        t.setDaemon(true);
        t.start();
    }

    boolean alive() { return process.isAlive(); }

    String lastOutput() {
        synchronized (tail) { return String.join("\n", tail); }
    }

    /** Sends "q" (FFmpeg's quit key) and waits; force-kills only if FFmpeg hangs. */
    int stop(long timeoutMs) {
        stopping = true;
        try {
            if (process.isAlive()) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write('q');
                    os.flush();
                } catch (IOException ignored) { }
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    LOG.warning("FFmpeg did not stop in time, killing it");
                    process.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
                }
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return -1;
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    void kill() {
        stopping = true;
        process.destroyForcibly();
    }
}
