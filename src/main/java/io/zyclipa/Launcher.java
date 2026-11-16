package io.zyclipa;

import io.zyclipa.app.CliOptions;
import io.zyclipa.app.ZyclipaApp;
import javafx.application.Application;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Plain entry point. It does not extend {@link Application} so that the shaded jar
 * can start JavaFX from the class path.
 * <p>
 * Only one Zyclipa runs at a time: the first copy listens on a local port; starting it again (e.g. while it sits in
 * the tray) asks that copy to show its window and exits. Checks and runs with their own --data-dir are exempt.
 */
public final class Launcher {
    static final int INSTANCE_PORT = 47829;

    private Launcher() {}

    public static void main(String[] args) {
        CliOptions.parse(args);
        if (!CliOptions.automated() && CliOptions.dataDir() == null && !becomeTheOnlyCopy()) {
            System.exit(0);
            return;
        }
        Application.launch(ZyclipaApp.class, args);
    }

    /** True when this is the only copy; otherwise tells the running copy to show itself and returns false. */
    static boolean becomeTheOnlyCopy() {
        try {
            ServerSocket s = new ServerSocket(INSTANCE_PORT, 4, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try (Socket c = s.accept(); InputStream in = c.getInputStream()) {
                        c.setSoTimeout(2000);
                        byte[] b = in.readNBytes(4);
                        if (new String(b, java.nio.charset.StandardCharsets.US_ASCII).equals("show")) ZyclipaApp.showWindow();
                    } catch (Exception ignored) { }
                }
            }, "single-instance");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (java.io.IOException taken) {
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), INSTANCE_PORT); OutputStream out = c.getOutputStream()) {
                out.write("show".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                return false;
            } catch (java.io.IOException notZyclipa) {
                return true;   // something else uses the port: just start normally
            }
        }
    }
}
