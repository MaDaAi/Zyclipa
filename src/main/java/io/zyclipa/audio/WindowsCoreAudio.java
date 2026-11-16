package io.zyclipa.audio;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import io.zyclipa.core.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Windows Core Audio (WASAPI) through JNA, calling the COM interfaces by vtable slot.
 * All COM work happens on one thread that joined the multithreaded apartment.
 * Interfaces used: IMMDeviceEnumerator, IMMDevice, IPropertyStore, IAudioEndpointVolume,
 * IAudioMeterInformation, IAudioSessionManager2, IAudioSessionEnumerator,
 * IAudioSessionControl(2) and ISimpleAudioVolume - all public, documented Windows APIs.
 */
final class WindowsCoreAudio implements AudioPlatform {
    private static final Logger LOG = Log.get(WindowsCoreAudio.class);

    private static final GUID CLSID_MMDeviceEnumerator = GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final GUID IID_IMMDeviceEnumerator = GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final GUID IID_IAudioSessionManager2 = GUID.fromString("{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}");
    private static final GUID IID_IAudioSessionControl2 = GUID.fromString("{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}");
    private static final GUID IID_ISimpleAudioVolume = GUID.fromString("{87CE5498-68D6-44E5-9215-6DA47EF883D8}");
    private static final GUID IID_IAudioEndpointVolume = GUID.fromString("{5CDF2C82-841E-4546-9722-0CF74078229A}");
    private static final GUID IID_IAudioMeterInformation = GUID.fromString("{C02216F6-8C67-4B5B-9D00-D008E73E0064}");
    private static final GUID PKEY_FriendlyName_fmt = GUID.fromString("{A45C254E-DF1C-4EFD-8020-67D146A850E0}");
    private static final int PKEY_FriendlyName_pid = 14;

    private static final int CLSCTX_ALL = 23;
    private static final int eRender = 0, eCapture = 1, eConsole = 0;
    private static final int DEVICE_STATE_ACTIVE = 1;

    private final ExecutorService com = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "core-audio");
        t.setDaemon(true);
        return t;
    });
    private Pointer enumerator;
    private Pointer renderVol, renderMeter, captureVol, captureMeter;
    private String renderId = "", captureId = "";
    private final Map<String, SessionRef> sessions = new HashMap<>();
    private final Map<String, String> endpointNames = new HashMap<>();

    private static final class SessionRef {
        Pointer control, volume, meter;
        int pid;
        String process, display, endpoint;
        boolean system;
    }

    WindowsCoreAudio() {
        onCom(() -> {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
            PointerByReference pp = new PointerByReference();
            check(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, pp).intValue(),
                    "CoCreateInstance(MMDeviceEnumerator)");
            enumerator = pp.getValue();
            bindDefaults();
            return null;
        });
    }

    // ---------- COM plumbing ----------

    private static int call(Pointer obj, int slot, Object... args) {
        Pointer vtbl = obj.getPointer(0);
        Function f = Function.getFunction(vtbl.getPointer((long) slot * Native.POINTER_SIZE), Function.C_CONVENTION);
        Object[] a = new Object[args.length + 1];
        a[0] = obj;
        System.arraycopy(args, 0, a, 1, args.length);
        return f.invokeInt(a);
    }

    private static void release(Pointer p) { if (p != null) call(p, 2); }

    private static Pointer qi(Pointer p, GUID iid) {
        PointerByReference out = new PointerByReference();
        return call(p, 0, iid, out) == 0 ? out.getValue() : null;
    }

    private static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hr));
    }

    private <T> T onCom(Callable<T> c) {
        try { return com.submit(c).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException(e.getCause() != null ? e.getCause() : e); }
    }

    private <T> T safe(Callable<T> c, T fallback) {
        try { return onCom(c); } catch (RuntimeException e) { LOG.fine("core audio: " + e); return fallback; }
    }

    private static String readWide(PointerByReference pp) {
        Pointer s = pp.getValue();
        if (s == null) return "";
        String v = s.getWideString(0);
        Ole32.INSTANCE.CoTaskMemFree(s);
        return v;
    }

    private static Pointer activate(Pointer device, GUID iid) {
        PointerByReference out = new PointerByReference();
        check(call(device, 3, iid, CLSCTX_ALL, Pointer.NULL, out), "IMMDevice::Activate");
        return out.getValue();
    }

    private static String deviceId(Pointer device) {
        PointerByReference id = new PointerByReference();
        return call(device, 5, id) == 0 ? readWide(id) : "";
    }

    private static String friendlyName(Pointer device) {
        PointerByReference store = new PointerByReference();
        if (call(device, 4, 0 /* STGM_READ */, store) != 0) return "Audio device";
        Pointer ps = store.getValue();
        try {
            Memory key = new Memory(20);
            byte[] g = guidBytes(PKEY_FriendlyName_fmt);
            key.write(0, g, 0, 16);
            key.setInt(16, PKEY_FriendlyName_pid);
            Memory pv = new Memory(24);
            pv.clear();
            if (call(ps, 5, key, pv) != 0) return "Audio device";
            short vt = pv.getShort(0);
            String name = vt == 31 /* VT_LPWSTR */ ? pv.getPointer(8).getWideString(0) : "Audio device";
            Function clear = NativeLibrary.getInstance("ole32").getFunction("PropVariantClear");
            clear.invokeInt(new Object[]{pv});
            return name;
        } finally {
            release(ps);
        }
    }

    private static byte[] guidBytes(GUID g) {
        g.write();
        return g.getPointer().getByteArray(0, 16);
    }

    private void bindDefaults() {
        release(renderVol); release(renderMeter); release(captureVol); release(captureMeter);
        renderVol = renderMeter = captureVol = captureMeter = null;
        renderId = captureId = "";
        PointerByReference dev = new PointerByReference();
        if (call(enumerator, 4, eRender, eConsole, dev) == 0) {
            Pointer d = dev.getValue();
            try {
                renderId = deviceId(d);
                renderVol = activate(d, IID_IAudioEndpointVolume);
                renderMeter = activate(d, IID_IAudioMeterInformation);
            } finally { release(d); }
        }
        dev = new PointerByReference();
        if (call(enumerator, 4, eCapture, eConsole, dev) == 0) {
            Pointer d = dev.getValue();
            try {
                captureId = deviceId(d);
                captureVol = activate(d, IID_IAudioEndpointVolume);
                captureMeter = activate(d, IID_IAudioMeterInformation);
            } finally { release(d); }
        }
    }

    /** Rebinds when Windows switched the default device (unplugged headset, etc.). */
    private void ensureDefaults() {
        PointerByReference dev = new PointerByReference();
        String now = "";
        if (call(enumerator, 4, eRender, eConsole, dev) == 0) {
            now = deviceId(dev.getValue());
            release(dev.getValue());
        }
        String capNow = "";
        dev = new PointerByReference();
        if (call(enumerator, 4, eCapture, eConsole, dev) == 0) {
            capNow = deviceId(dev.getValue());
            release(dev.getValue());
        }
        if (!now.equals(renderId) || !capNow.equals(captureId)) {
            LOG.info("Default audio device changed, rebinding");
            bindDefaults();
        }
    }

    // ---------- AudioPlatform ----------

    @Override public boolean available() { return enumerator != null; }

    @Override public List<Endpoint> endpoints() {
        return safe(() -> {
            List<Endpoint> out = new ArrayList<>();
            for (int flow : new int[]{eRender, eCapture}) {
                PointerByReference col = new PointerByReference();
                if (call(enumerator, 3, flow, DEVICE_STATE_ACTIVE, col) != 0) continue;
                Pointer c = col.getValue();
                try {
                    IntByReference n = new IntByReference();
                    call(c, 3, n);
                    for (int i = 0; i < n.getValue(); i++) {
                        PointerByReference item = new PointerByReference();
                        if (call(c, 4, i, item) != 0) continue;
                        Pointer d = item.getValue();
                        try {
                            String id = deviceId(d);
                            String def = flow == eRender ? renderId : captureId;
                            String name = friendlyName(d);
                            endpointNames.put(id, name);
                            out.add(new Endpoint(id, name, flow == eRender, id.equals(def)));
                        } finally { release(d); }
                    }
                } finally { release(c); }
            }
            return out;
        }, List.of());
    }

    @Override public List<Session> sessions() {
        return safe(() -> {
            ensureDefaults();
            Set<String> seen = new HashSet<>();
            List<Session> out = new ArrayList<>();
            PointerByReference col = new PointerByReference();
            if (call(enumerator, 3, eRender, DEVICE_STATE_ACTIVE, col) != 0) return out;
            Pointer c = col.getValue();
            try {
                IntByReference n = new IntByReference();
                call(c, 3, n);
                for (int i = 0; i < n.getValue(); i++) {
                    PointerByReference item = new PointerByReference();
                    if (call(c, 4, i, item) != 0) continue;
                    Pointer dev = item.getValue();
                    try { collect(dev, seen, out); } finally { release(dev); }
                }
            } finally { release(c); }
            // Drop sessions that disappeared (app closed / device unplugged).
            sessions.entrySet().removeIf(e -> {
                if (seen.contains(e.getKey())) return false;
                SessionRef r = e.getValue();
                release(r.meter); release(r.volume); release(r.control);
                return true;
            });
            return out;
        }, List.of());
    }

    private void collect(Pointer device, Set<String> seen, List<Session> out) {
        String devId = deviceId(device);
        String devName = endpointNames.computeIfAbsent(devId, k -> friendlyName(device));
        Pointer mgr = activate(device, IID_IAudioSessionManager2);
        try {
            PointerByReference en = new PointerByReference();
            if (call(mgr, 5, en) != 0) return;
            Pointer e = en.getValue();
            try {
                IntByReference n = new IntByReference();
                call(e, 3, n);
                for (int i = 0; i < n.getValue(); i++) {
                    PointerByReference ctlRef = new PointerByReference();
                    if (call(e, 4, i, ctlRef) != 0) continue;
                    Pointer ctl = ctlRef.getValue();
                    Pointer ctl2 = qi(ctl, IID_IAudioSessionControl2);
                    if (ctl2 == null) { release(ctl); continue; }
                    PointerByReference inst = new PointerByReference();
                    String key = call(ctl2, 13, inst) == 0 ? readWide(inst) : devId + "#" + i;
                    IntByReference state = new IntByReference();
                    call(ctl, 3, state);
                    if (state.getValue() == 2 /* expired */) { release(ctl2); release(ctl); continue; }
                    SessionRef ref = sessions.get(key);
                    if (ref == null) {
                        ref = new SessionRef();
                        IntByReference pid = new IntByReference();
                        call(ctl2, 14, pid);
                        ref.pid = pid.getValue();
                        ref.system = call(ctl2, 15) == 0;
                        ref.process = ref.system ? "System sounds" : processName(ref.pid);
                        PointerByReference dn = new PointerByReference();
                        ref.display = call(ctl, 4, dn) == 0 ? readWide(dn) : "";
                        ref.endpoint = devName;
                        ref.control = ctl;
                        ref.volume = qi(ctl, IID_ISimpleAudioVolume);
                        ref.meter = qi(ctl, IID_IAudioMeterInformation);
                        sessions.put(key, ref);
                    } else {
                        release(ctl);
                    }
                    release(ctl2);
                    if (!seen.add(key)) continue;
                    FloatByReference v = new FloatByReference(1);
                    IntByReference m = new IntByReference();
                    if (ref.volume != null) { call(ref.volume, 4, v); call(ref.volume, 6, m); }
                    out.add(new Session(key, ref.pid, ref.process, ref.display, ref.system, state.getValue() == 1,
                            v.getValue(), m.getValue() != 0, ref.endpoint));
                }
            } finally { release(e); }
        } finally { release(mgr); }
    }

    private static String processName(int pid) {
        String exe = io.zyclipa.platform.WindowsDesktop.processName(pid);
        if (!exe.isBlank()) return exe;
        return ProcessHandle.of(pid).flatMap(h -> h.info().command()).map(cmd -> {
            String n = cmd.substring(cmd.lastIndexOf('\\') + 1);
            return n.toLowerCase().endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
        }).orElse("pid " + pid);
    }

    @Override public void setSession(String key, float volume, boolean muted) {
        safe(() -> {
            SessionRef r = sessions.get(key);
            if (r != null && r.volume != null) {
                call(r.volume, 3, clamp(volume), Pointer.NULL);
                call(r.volume, 5, muted ? 1 : 0, Pointer.NULL);
            }
            return null;
        }, null);
    }

    @Override public float sessionPeak(String key) {
        return safe(() -> {
            SessionRef r = sessions.get(key);
            if (r == null || r.meter == null) return 0f;
            FloatByReference p = new FloatByReference();
            return call(r.meter, 3, p) == 0 ? clamp(p.getValue()) : 0f;
        }, 0f);
    }

    private float scalar(Pointer vol) {
        if (vol == null) return 1f;
        FloatByReference f = new FloatByReference(1);
        call(vol, 9, f);
        return f.getValue();
    }

    private boolean mute(Pointer vol) {
        if (vol == null) return false;
        IntByReference m = new IntByReference();
        call(vol, 15, m);
        return m.getValue() != 0;
    }

    private float peak(Pointer meter) {
        if (meter == null) return 0f;
        FloatByReference p = new FloatByReference();
        return call(meter, 3, p) == 0 ? clamp(p.getValue()) : 0f;
    }

    private void setEndpoint(Pointer vol, float v, boolean m) {
        if (vol == null) return;
        call(vol, 7, clamp(v), Pointer.NULL);
        call(vol, 14, m ? 1 : 0, Pointer.NULL);
    }

    @Override public float masterVolume() { return safe(() -> scalar(renderVol), 1f); }
    @Override public boolean masterMuted() { return safe(() -> mute(renderVol), false); }
    @Override public void setMaster(float v, boolean m) { safe(() -> { setEndpoint(renderVol, v, m); return null; }, null); }
    @Override public float masterPeak() { return safe(() -> peak(renderMeter), 0f); }
    @Override public float micVolume() { return safe(() -> scalar(captureVol), 1f); }
    @Override public boolean micMuted() { return safe(() -> mute(captureVol), false); }
    @Override public void setMic(float v, boolean m) { safe(() -> { setEndpoint(captureVol, v, m); return null; }, null); }
    @Override public float micPeak() { return safe(() -> peak(captureMeter), 0f); }
    @Override public String defaultOutputId() { return safe(() -> { ensureDefaults(); return renderId; }, ""); }

    private static float clamp(float v) { return Float.isNaN(v) ? 0 : Math.max(0f, Math.min(1f, v)); }

    @Override public void close() {
        try {
            com.submit(() -> {
                for (SessionRef r : sessions.values()) { release(r.meter); release(r.volume); release(r.control); }
                sessions.clear();
                release(renderVol); release(renderMeter); release(captureVol); release(captureMeter);
                release(enumerator);
                enumerator = null;
                Ole32.INSTANCE.CoUninitialize();
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        com.shutdownNow();
    }
}
