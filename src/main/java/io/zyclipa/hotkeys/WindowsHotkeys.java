package io.zyclipa.hotkeys;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import io.zyclipa.core.Log;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Global shortcuts on Windows, on one message-loop thread.
 *
 * <p>Shortcuts with Ctrl/Alt/Shift/Win use {@code RegisterHotKey}. Single keys (e.g. "Delete")
 * and shortcuts another program already holds (e.g. Alt+F10 taken by a graphics-card overlay)
 * use a low-level keyboard hook instead. The hook only <em>watches</em>: every key is passed on
 * untouched, nothing is stored, and only key codes that match a bound shortcut cause an action.
 * It is installed only while such a shortcut exists.</p>
 */
final class WindowsHotkeys implements HotkeyService {
    private static final Logger LOG = Log.get(WindowsHotkeys.class);
    private static final int MOD_NOREPEAT = 0x4000;
    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_APP_WAKE = 0x8000 + 7;
    private static final int WH_KEYBOARD_LL = 13;
    private static final int WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_SYSKEYDOWN = 0x104, WM_SYSKEYUP = 0x105;

    private record HookBinding(Hotkey key, Runnable handler) { }

    private final Map<String, Integer> idsByAction = new ConcurrentHashMap<>();
    private final Map<Integer, Runnable> handlers = new ConcurrentHashMap<>();
    private final Map<String, HookBinding> hookBindings = new ConcurrentHashMap<>();
    private final Set<Integer> down = ConcurrentHashMap.newKeySet();
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Thread thread;
    private volatile int threadId;
    private volatile boolean running = true;
    private WinUser.HHOOK hook;
    private final WinUser.LowLevelKeyboardProc proc = this::onKey; // strong reference: the hook must not be GC'd

    WindowsHotkeys() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        thread = new Thread(() -> loop(ready), "hotkeys");
        thread.setDaemon(true);
        thread.start();
        try { ready.get(5, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void loop(CompletableFuture<Void> ready) {
        threadId = Kernel32.INSTANCE.GetCurrentThreadId();
        WinUser.MSG msg = new WinUser.MSG();
        User32.INSTANCE.PeekMessage(msg, null, 0, 0, 0); // creates the message queue
        ready.complete(null);
        while (running && User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            if (msg.message == WM_HOTKEY) fire(handlers.get(msg.wParam.intValue()));
            Runnable t;
            while ((t = tasks.poll()) != null) t.run();
        }
        for (int id : handlers.keySet()) User32.INSTANCE.UnregisterHotKey(null, id);
        if (hook != null) User32.INSTANCE.UnhookWindowsHookEx(hook);
    }

    private static void fire(Runnable h) {
        if (h == null) return;
        Thread.ofVirtual().start(() -> {
            try { h.run(); } catch (RuntimeException e) { LOG.warning("Hotkey action failed: " + e); }
        });
    }

    private static boolean pressed(int vk) { return (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0; }

    private static int currentModifiers() {
        int m = 0;
        if (pressed(0x11)) m |= Hotkey.CTRL;
        if (pressed(0x12)) m |= Hotkey.ALT;
        if (pressed(0x10)) m |= Hotkey.SHIFT;
        if (pressed(0x5B) || pressed(0x5C)) m |= Hotkey.WIN;
        return m;
    }

    /** Low-level hook: compare the key with our shortcuts, never block or record it. */
    private WinDef.LRESULT onKey(int nCode, WinDef.WPARAM wParam, WinUser.KBDLLHOOKSTRUCT info) {
        if (nCode >= 0) {
            int msg = wParam.intValue();
            int vk = info.vkCode;
            if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) {
                if (down.add(vk)) { // ignore auto-repeat while held
                    int mods = currentModifiers();
                    for (HookBinding b : hookBindings.values()) {
                        if (b.key().vk() == vk && b.key().modifiers() == mods) fire(b.handler());
                    }
                }
            } else if (msg == WM_KEYUP || msg == WM_SYSKEYUP) {
                down.remove(vk);
            }
        }
        return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
    }

    private void updateHook() {
        if (!hookBindings.isEmpty() && hook == null) {
            hook = User32.INSTANCE.SetWindowsHookEx(WH_KEYBOARD_LL, proc, Kernel32.INSTANCE.GetModuleHandle(null), 0);
            if (hook == null) LOG.warning("Keyboard hook could not be installed");
        } else if (hookBindings.isEmpty() && hook != null) {
            User32.INSTANCE.UnhookWindowsHookEx(hook);
            hook = null;
            down.clear();
        }
    }

    private <T> T onLoop(Supplier<T> s) {
        CompletableFuture<T> f = new CompletableFuture<>();
        tasks.add(() -> {
            try { f.complete(s.get()); } catch (RuntimeException e) { f.completeExceptionally(e); }
        });
        User32.INSTANCE.PostThreadMessage(threadId, WM_APP_WAKE, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        try { return f.get(3, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public String bind(String action, String shortcut, Runnable handler) {
        unbind(action);
        if (shortcut == null || shortcut.isBlank()) return null;
        Hotkey hk = Hotkey.parse(shortcut).orElse(null);
        if (hk == null) return "\"" + shortcut + "\" isn't a key Zyclipa knows.";
        if (hk.modifiers() != 0) {
            int id = nextId.getAndIncrement();
            boolean ok = onLoop(() -> User32.INSTANCE.RegisterHotKey(null, id, hk.modifiers() | MOD_NOREPEAT, hk.vk()));
            if (ok) {
                idsByAction.put(action, id);
                handlers.put(id, handler);
                return null;
            }
            LOG.info(hk.text() + " is held by another program; watching for it instead");
        }
        hookBindings.put(action, new HookBinding(hk, handler));
        boolean installed = onLoop(() -> { updateHook(); return hook != null; });
        return installed ? null : "Windows didn't allow Zyclipa to watch for " + hk.text() + ".";
    }

    @Override public void unbind(String action) {
        Integer id = idsByAction.remove(action);
        if (id != null) {
            handlers.remove(id);
            onLoop(() -> User32.INSTANCE.UnregisterHotKey(null, id));
        }
        if (hookBindings.remove(action) != null) onLoop(() -> { updateHook(); return null; });
    }

    @Override public boolean supported() { return true; }

    @Override public void close() {
        running = false;
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
    }
}
