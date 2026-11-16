package io.zyclipa.platform;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** Portable fallback using AWT screen information. */
final class GenericDesktop implements Desktop {
    @Override public List<Display> displays() {
        List<Display> out = new ArrayList<>();
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice primary = ge.getDefaultScreenDevice();
            int i = 0;
            for (GraphicsDevice d : ge.getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                double s = d.getDefaultConfiguration().getDefaultTransform().getScaleX();
                out.add(new Display(i++, (int) (b.x * s), (int) (b.y * s), (int) (b.width * s), (int) (b.height * s), d == primary));
            }
        } catch (Throwable ignored) { }
        if (out.isEmpty()) out.add(new Display(0, 0, 0, 1920, 1080, true));
        return out;
    }

    @Override public List<Window> windows() { return List.of(); }
    @Override public String foregroundProcess() { return ""; }
}
