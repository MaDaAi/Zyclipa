package io.zyclipra;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.zyclipra.remote.Commands;
import io.zyclipra.remote.RemoteControl;
import io.zyclipra.remote.RtcBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The website's actions over WebRTC/HTTPS reach the PC the same way the dashboard's do. */
class RtcRouteTest {
    @TempDir Path dir;
    final List<String> calls = new ArrayList<>();

    RemoteControl fake(Path clip, Path thumb) {
        return (RemoteControl) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteControl.class}, (p, m, a) -> {
            calls.add(m.getName() + (a == null ? "" : java.util.Arrays.toString(a)));
            if (m.getName().equals("clipFile")) return Optional.ofNullable("c1".equals(a[0]) ? clip : null);
            if (m.getName().equals("clipThumbnail")) return Optional.ofNullable("c1".equals(a[0]) ? thumb : null);
            if (m.getReturnType() == JsonElement.class) { JsonObject o = new JsonObject(); o.addProperty("ok", m.getName()); return o; }
            return null;
        });
    }

    static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test void controlsReachThePc() throws Exception {
        RemoteControl pc = fake(null, null);
        Commands cmds = new Commands(pc);
        String[][] cases = {
                {"replay/save", "{}", "saveReplay"}, {"replay/on", "{}", "setReplay[true]"}, {"replay/off", "{}", "setReplay[false]"},
                {"replay/length", "{\"seconds\":60}", "setReplaySeconds[60]"}, {"recording/start", "{}", "startRecording"},
                {"recording/stop", "{}", "stopRecording"}, {"screenshot", "{}", "screenshot"}, {"status", "{}", "status"},
                {"sounds", "{}", "sounds"}, {"clips", "{\"limit\":5}", "clips[5]"}, {"sounds/stopAll", "{}", "stopSounds"},
        };
        int n = 0;
        for (String[] c : cases) {
            calls.clear();
            Commands.Reply r = RtcBridge.route(pc, cmds, "rtc:t", "cmd-" + (100000 + n++), c[0], args(c[1]));
            assertEquals(200, r.status(), c[0] + " → " + r.body());
            assertTrue(calls.contains(c[2]), c[0] + " called " + calls);
        }
    }

    @Test void blockedAndInvalid() {
        RemoteControl pc = fake(null, null);
        Commands cmds = new Commands(pc);
        assertEquals(403, RtcBridge.route(pc, cmds, "rtc:t", null, "cloud/disconnect", new JsonObject()).status());
        assertEquals(403, RtcBridge.route(pc, cmds, "rtc:t", null, "sessions", new JsonObject()).status());
        assertEquals(400, RtcBridge.route(pc, cmds, "rtc:t", null, "clip/part", args("{\"id\":\"../x\"}")).status());
        assertEquals(400, RtcBridge.route(pc, cmds, "rtc:t", "cmd-200000", "replay/length", args("{\"seconds\":7}")).status());
        assertEquals(404, RtcBridge.route(pc, cmds, "rtc:t", null, "clip/thumb", args("{\"id\":\"nope\"}")).status());
    }

    @Test void sameCommandIdRunsOnce() {
        RemoteControl pc = fake(null, null);
        Commands cmds = new Commands(pc);
        RtcBridge.route(pc, cmds, "rtc:t", "cmd-300000", "replay/save", new JsonObject());
        RtcBridge.route(pc, cmds, "rtc:t", "cmd-300000", "replay/save", new JsonObject());
        assertEquals(1, calls.stream().filter("saveReplay"::equals).count());
    }

    @Test void clipIsReadInParts() throws Exception {
        byte[] data = new byte[100_000];
        new java.util.Random(1).nextBytes(data);
        Path clip = Files.write(dir.resolve("c1.mp4"), data);
        Path thumb = Files.write(dir.resolve("c1.jpg"), new byte[]{1, 2, 3});
        RemoteControl pc = fake(clip, thumb);
        Commands cmds = new Commands(pc);
        java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
        long offset = 0, size = -1;
        while (size < 0 || offset < size) {
            Commands.Reply r = RtcBridge.route(pc, cmds, "rtc:t", null, "clip/part", args("{\"id\":\"c1\",\"offset\":" + offset + "}"));
            assertEquals(200, r.status());
            JsonObject o = r.body().getAsJsonObject();
            size = o.get("size").getAsLong();
            byte[] b = Base64.getDecoder().decode(o.get("data").getAsString());
            assertTrue(b.length > 0 && b.length <= 36 * 1024);
            got.writeBytes(b);
            offset += b.length;
        }
        assertArrayEquals(data, got.toByteArray());
        JsonObject t = RtcBridge.route(pc, cmds, "rtc:t", null, "clip/thumb", args("{\"id\":\"c1\"}")).body().getAsJsonObject();
        assertArrayEquals(new byte[]{1, 2, 3}, Base64.getDecoder().decode(t.get("data").getAsString()));
    }
}
