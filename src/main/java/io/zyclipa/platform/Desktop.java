package io.zyclipa.platform;

import java.util.List;

/** Screen and window facts the recorder needs. One implementation per operating system. */
public interface Desktop {
    record Display(int index, int x, int y, int width, int height, boolean primary) {
        public String label() { return "Display " + (index + 1) + " (" + width + "x" + height + (primary ? ", main" : "") + ")"; }
    }

    record Window(String title, String process) { }

    List<Display> displays();

    /** Visible top-level windows with a title, for window capture. */
    List<Window> windows();

    /** Executable name (without extension) of the window in front, e.g. "valorant". */
    String foregroundProcess();

    /** Title and process of the window in front (empty strings when unknown). */
    default Window foregroundWindow() { return new Window("", foregroundProcess()); }

    static Desktop create() {
        if (io.zyclipa.core.Platform.isWindows()) {
            try { return new WindowsDesktop(); } catch (Throwable t) { /* fall through */ }
        }
        return new GenericDesktop();
    }
}
