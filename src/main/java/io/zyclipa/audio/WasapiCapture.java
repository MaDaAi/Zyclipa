package io.zyclipa.audio;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import io.zyclipa.core.Log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Captures sound with Windows WASAPI as 48 kHz 16-bit stereo PCM:
 * <ul>
 * <li>loopback of an output device ("what you hear" on that device),</li>
 * <li>loopback of every app except one process tree (Windows 10 2004+), so Zyclipa's own
 *     soundboard and monitoring stay out of the game track,</li>
 * <li>a microphone (capture endpoint).</li>
 * </ul>
 * Each capture runs on its own thread in the COM multithreaded apartment.
 */
public final class WasapiCapture implements AutoCloseable {
    private static final Logger LOG = Log.get(WasapiCapture.class);
    private static final GUID CLSID_MMDeviceEnumerator = GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final GUID IID_IMMDeviceEnumerator = GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final GUID IID_IAudioClient = GUID.fromString("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}");
    private static final GUID IID_IAudioCaptureClient = GUID.fromString("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}");
    private static final String IID_IUNKNOWN = "00000000-0000-0000-c000-000000000046";
    private static final String IID_HANDLER = "41d949ab-9862-444a-80f6-c261334da5eb";
    private static final String IID_AGILE = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90";
    private static final int LOOPBACK = 0x00020000, EVENTCALLBACK = 0x00040000, AUTOCONVERTPCM = 0x80000000, SRC_DEFAULT_QUALITY = 0x08000000;
    private static final int BUFFERFLAGS_SILENT = 2;
    public static final int RATE = 48000, BYTES_PER_FRAME = 4;

    public enum Kind { OUTPUT_LOOPBACK, EXCLUDE_PROCESS_LOOPBACK, INCLUDE_PROCESS_LOOPBACK, MICROPHONE }

    private final Kind kind;
    private final String deviceId;   // null = default device for the kind
    private final int role;          // 0 console, 2 communications
    private final long pid;
    private final BiConsumer<byte[], Integer> sink;
    private final Thread thread;
    private volatile boolean run = true;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private volatile String error = "";

    private WasapiCapture(Kind kind, String deviceId, int role, long pid, BiConsumer<byte[], Integer> sink) {
        this.kind = kind;
        this.deviceId = deviceId;
        this.role = role;
        this.pid = pid;
        this.sink = sink;
        thread = new Thread(this::loop, "wasapi-" + kind.name().toLowerCase());
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
    }

    /** Starts capturing; throws if Windows refuses (with a readable reason). */
    public static WasapiCapture start(Kind kind, String deviceId, int role, long pid, BiConsumer<byte[], Integer> sink) {
        WasapiCapture c = new WasapiCapture(kind, deviceId, role, pid, sink);
        c.thread.start();
        try {
            c.started.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            c.close();
            Throwable t = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(t.getMessage(), t);
        }
        return c;
    }

    public String error() { return error; }
    public boolean running() { return thread.isAlive(); }

    @Override public void close() {
        run = false;
        try { thread.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // ---------------------------------------------------------------- COM helpers

    static int call(Pointer obj, int slot, Object... args) {
        Pointer vtbl = obj.getPointer(0);
        Function f = Function.getFunction(vtbl.getPointer((long) slot * Native.POINTER_SIZE), Function.C_CONVENTION);
        Object[] a = new Object[args.length + 1];
        a[0] = obj;
        System.arraycopy(args, 0, a, 1, args.length);
        return f.invokeInt(a);
    }

    static void release(Pointer p) { if (p != null) call(p, 2); }

    static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed (0x" + Integer.toHexString(hr) + ")");
    }

    private static Memory waveFormat() {
        Memory wf = new Memory(18);
        wf.clear();
        wf.setShort(0, (short) 1);          // WAVE_FORMAT_PCM
        wf.setShort(2, (short) 2);          // channels
        wf.setInt(4, RATE);
        wf.setInt(8, RATE * BYTES_PER_FRAME);
        wf.setShort(12, (short) BYTES_PER_FRAME);
        wf.setShort(14, (short) 16);
        wf.setShort(16, (short) 0);
        return wf;
    }

    private Pointer endpointClient() {
        PointerByReference pp = new PointerByReference();
        check(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, 23, IID_IMMDeviceEnumerator, pp).intValue(), "MMDeviceEnumerator");
        Pointer en = pp.getValue();
        try {
            PointerByReference dev = new PointerByReference();
            if (deviceId != null && !deviceId.isBlank()) check(call(en, 5, new WString(deviceId), dev), "GetDevice");
            else check(call(en, 4, kind == Kind.MICROPHONE ? 1 : 0, role, dev), "GetDefaultAudioEndpoint");
            Pointer d = dev.getValue();
            try {
                PointerByReference client = new PointerByReference();
                check(call(d, 3, IID_IAudioClient, 23, Pointer.NULL, client), "Activate(IAudioClient)");
                return client.getValue();
            } finally { release(d); }
        } finally { release(en); }
    }

    /** Id of the default device for flow (0 output, 1 input) and role (0 console, 2 communications), or "". */
    public static String defaultId(int flow, int role) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
            try {
                PointerByReference pp = new PointerByReference();
                check(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, 23, IID_IMMDeviceEnumerator, pp).intValue(), "enum");
                Pointer en = pp.getValue();
                PointerByReference dev = new PointerByReference();
                if (call(en, 4, flow, role, dev) == 0) {
                    PointerByReference id = new PointerByReference();
                    call(dev.getValue(), 5, id);
                    f.complete(id.getValue().getWideString(0));
                    Ole32.INSTANCE.CoTaskMemFree(id.getValue());
                    release(dev.getValue());
                } else f.complete("");
                release(en);
            } catch (Throwable e) { f.complete(""); }
            finally { Ole32.INSTANCE.CoUninitialize(); }
        }, "wasapi-default");
        t.setDaemon(true);
        t.start();
        try { return f.get(3, TimeUnit.SECONDS); } catch (Exception e) { return ""; }
    }

    // keep callbacks reachable while Windows may call them
    private interface Qi extends Callback { int invoke(Pointer self, Pointer riid, Pointer ppv); }
    private interface Ref extends Callback { int invoke(Pointer self); }
    private interface Done extends Callback { int invoke(Pointer self, Pointer op); }
    private Object[] keep;

    /** Process loopback needs ActivateAudioInterfaceAsync and a small COM completion handler. */
    private Pointer processClient(boolean include) throws Exception {
        CompletableFuture<Pointer> result = new CompletableFuture<>();
        Qi qi = (self, riid, ppv) -> {
            String id = new GUID(riid).toGuidString().replaceAll("[{}]", "").toLowerCase();
            if (id.equals(IID_IUNKNOWN) || id.equals(IID_HANDLER) || id.equals(IID_AGILE)) { ppv.setPointer(0, self); return 0; }
            ppv.setPointer(0, Pointer.NULL);
            return 0x80004002;
        };
        Ref addRef = self -> 1, rel = self -> 1;
        Done done = (self, op) -> {
            IntByReference hr = new IntByReference();
            PointerByReference unk = new PointerByReference();
            int r = call(op, 3, hr, unk);
            if (r < 0 || hr.getValue() < 0) result.completeExceptionally(new IllegalStateException("process loopback activation failed (0x"
                    + Integer.toHexString(r < 0 ? r : hr.getValue()) + ")"));
            else result.complete(unk.getValue());
            return 0;
        };
        Memory vtbl = new Memory(4L * Native.POINTER_SIZE);
        vtbl.setPointer(0, CallbackReference.getFunctionPointer(qi));
        vtbl.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
        vtbl.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(rel));
        vtbl.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(done));
        Memory handler = new Memory(16);
        handler.setPointer(0, vtbl);
        Memory params = new Memory(12);
        params.setInt(0, 1);                 // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
        params.setInt(4, (int) pid);
        params.setInt(8, include ? 0 : 1);   // INCLUDE / EXCLUDE target process tree
        Memory pv = new Memory(24);
        pv.clear();
        pv.setShort(0, (short) 65);          // VT_BLOB
        pv.setInt(8, 12);
        pv.setPointer(16, params);
        keep = new Object[]{qi, addRef, rel, done, vtbl, handler, params, pv};
        PointerByReference op = new PointerByReference();
        Function activate = NativeLibrary.getInstance("Mmdevapi").getFunction("ActivateAudioInterfaceAsync");
        check(activate.invokeInt(new Object[]{new WString("VAD\\Process_Loopback"), IID_IAudioClient, pv, handler, op}), "ActivateAudioInterfaceAsync");
        try {
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            if (op.getValue() != null) release(op.getValue());
        }
    }

    // ---------------------------------------------------------------- capture loop

    private void loop() {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
        Pointer client = null, capture = null;
        WinNT.HANDLE event = null;
        try {
            int flags = EVENTCALLBACK | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY;
            client = switch (kind) {
                case EXCLUDE_PROCESS_LOOPBACK -> processClient(false);
                case INCLUDE_PROCESS_LOOPBACK -> processClient(true);
                default -> endpointClient();
            };
            if (kind != Kind.MICROPHONE) flags |= LOOPBACK;
            check(call(client, 3, 0, flags, 2_000_000L, 0L, waveFormat(), Pointer.NULL), "IAudioClient.Initialize");
            event = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
            check(call(client, 13, event), "SetEventHandle");
            PointerByReference cc = new PointerByReference();
            check(call(client, 14, IID_IAudioCaptureClient, cc), "GetService(IAudioCaptureClient)");
            capture = cc.getValue();
            check(call(client, 10), "Start");
            started.complete(null);
            PointerByReference data = new PointerByReference();
            IntByReference frames = new IntByReference(), bufFlags = new IntByReference(), next = new IntByReference();
            LongByReference devPos = new LongByReference(), qpc = new LongByReference();
            byte[] buf = new byte[RATE * BYTES_PER_FRAME];
            while (run) {
                Kernel32.INSTANCE.WaitForSingleObject(event, 50);
                while (run && call(capture, 5, next) >= 0 && next.getValue() > 0) {
                    if (call(capture, 3, data, frames, bufFlags, devPos, qpc) < 0) break;
                    int n = Math.min(frames.getValue(), buf.length / BYTES_PER_FRAME);
                    int bytes = n * BYTES_PER_FRAME;
                    if ((bufFlags.getValue() & BUFFERFLAGS_SILENT) != 0 || data.getValue() == null) java.util.Arrays.fill(buf, 0, bytes, (byte) 0);
                    else data.getValue().read(0, buf, 0, bytes);
                    call(capture, 4, frames.getValue());
                    sink.accept(buf, bytes);
                }
            }
            call(client, 11);
        } catch (Throwable t) {
            error = t.getMessage();
            LOG.warning(kind + " capture: " + t.getMessage());
            started.completeExceptionally(t);
        } finally {
            release(capture);
            release(client);
            if (event != null) Kernel32.INSTANCE.CloseHandle(event);
            Ole32.INSTANCE.CoUninitialize();
        }
    }
}
