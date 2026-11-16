package io.zyclipa.platform;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Win32 implementation (monitor rectangles in physical pixels, window titles, foreground app). */
public final class WindowsDesktop implements Desktop {
    private static final User32 U = User32.INSTANCE;

    WindowsDesktop() {
        U.GetForegroundWindow(); // fails fast if user32 is not reachable
    }

    @Override public List<Display> displays() {
        List<Display> out = new ArrayList<>();
        U.EnumDisplayMonitors(null, null, (hMonitor, hdc, rect, data) -> {
            WinUser.MONITORINFO mi = new WinUser.MONITORINFO();
            if (U.GetMonitorInfo(hMonitor, mi).booleanValue()) {
                WinDef.RECT r = mi.rcMonitor;
                boolean primary = (mi.dwFlags & WinUser.MONITORINFOF_PRIMARY) != 0;
                out.add(new Display(0, r.left, r.top, r.right - r.left, r.bottom - r.top, primary));
            }
            return 1;
        }, new WinDef.LPARAM(0));
        out.sort((a, b) -> a.primary() != b.primary() ? (a.primary() ? -1 : 1) : Integer.compare(a.x(), b.x()));
        List<Display> indexed = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Display d = out.get(i);
            indexed.add(new Display(i, d.x(), d.y(), d.width(), d.height(), d.primary()));
        }
        return indexed.isEmpty() ? new GenericDesktop().displays() : indexed;
    }

    @Override public List<Window> windows() {
        List<Window> out = new ArrayList<>();
        U.EnumWindows((hwnd, p) -> {
            if (!U.IsWindowVisible(hwnd)) return true;
            if (U.GetWindow(hwnd, new WinDef.DWORD(WinUser.GW_OWNER)) != null) return true;
            int len = U.GetWindowTextLength(hwnd);
            if (len <= 0) return true;
            char[] buf = new char[len + 1];
            U.GetWindowText(hwnd, buf, buf.length);
            String title = Native.toString(buf).trim();
            if (title.isEmpty() || title.equals("Program Manager")) return true;
            out.add(new Window(title, processOf(hwnd)));
            return true;
        }, Pointer.NULL);
        return out;
    }

    @Override public String foregroundProcess() {
        WinDef.HWND h = U.GetForegroundWindow();
        return h == null ? "" : processOf(h);
    }

    @Override public Window foregroundWindow() {
        WinDef.HWND h = U.GetForegroundWindow();
        if (h == null) return new Window("", "");
        int len = U.GetWindowTextLength(h);
        char[] buf = new char[Math.max(1, len + 1)];
        U.GetWindowText(h, buf, buf.length);
        return new Window(Native.toString(buf).trim(), processOf(h));
    }

    static String processOf(WinDef.HWND hwnd) {
        IntByReference pid = new IntByReference();
        U.GetWindowThreadProcessId(hwnd, pid);
        return processName(pid.getValue());
    }

    public static String processName(int pid) {
        if (pid <= 0) return "";
        WinNT.HANDLE h = Kernel32.INSTANCE.OpenProcess(0x1000 /* QUERY_LIMITED_INFORMATION */, false, pid);
        if (h == null) return "";
        try {
            char[] buf = new char[1024];
            IntByReference size = new IntByReference(buf.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(h, 0, buf, size)) return "";
            String path = new String(buf, 0, size.getValue());
            String name = path.substring(path.lastIndexOf('\\') + 1);
            if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) name = name.substring(0, name.length() - 4);
            return name;
        } finally {
            Kernel32.INSTANCE.CloseHandle(h);
        }
    }
}
