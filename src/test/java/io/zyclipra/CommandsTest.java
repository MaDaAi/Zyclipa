package io.zyclipra;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.zyclipra.remote.Commands;
import io.zyclipra.remote.RemoteControl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The shared remote-command rules every dashboard goes through (fake PC, no audio or capture). */
class CommandsTest {
    final AtomicInteger recordings = new AtomicInteger(), plays = new AtomicInteger();
    double master = 1;

    final RemoteControl pc = new RemoteControl() {
        public JsonElement status() { return new JsonObject(); }
        public JsonElement clips(int l) { return new JsonArray(); }
        public JsonElement levels() { return new JsonObject(); }
        public JsonElement mixer() { return new JsonArray(); }
        public JsonElement sounds() { return new JsonObject(); }
        public JsonElement toggleRecording() { recordings.incrementAndGet(); return ok("Recording started on your PC."); }
        public JsonElement toggleReplay() { return ok(""); }
        public JsonElement saveReplay() { throw new IllegalStateException("Instant replay is off."); }
        public JsonElement screenshot() { return ok(""); }
        public void setChannel(String id, double v, boolean m) { }
        public void playSound(String id) { }
        public void stopSounds() { }
        public JsonElement startRecording() { return toggleRecording(); }
        public JsonElement soundPlay(String id) { plays.incrementAndGet(); return ok("Playing"); }
        public JsonElement soundMaster(double g) { master = g; return ok("Soundboard volume " + Math.round(g * 100) + " %"); }
    };

    static JsonObject ok(String m) { JsonObject o = new JsonObject(); o.addProperty("ok", m); return o; }
    static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test void duplicateCommandIdsRunOnce() {
        Commands c = new Commands(pc);
        Commands.Reply a = c.run("phone", "cmd-0001-abcdef", "recording/start", new JsonObject());
        Commands.Reply b = c.run("phone", "cmd-0001-abcdef", "recording/start", new JsonObject());
        assertEquals(200, a.status());
        assertEquals(a, b, "the repeat gets the first answer");
        assertEquals(1, recordings.get(), "recording started once");
        c.run("phone", "cmd-0002-abcdef", "recording/start", new JsonObject());
        assertEquals(2, recordings.get(), "a new id is a new command");
        c.run("other-device", "cmd-0001-abcdef", "recording/start", new JsonObject());
        assertEquals(3, recordings.get(), "ids are per caller, so another device can't block or replay yours");
        assertEquals(400, c.run("phone", "x", "recording/start", new JsonObject()).status(), "malformed ids are refused");
    }

    @Test void failuresAreReportedNotFaked() {
        Commands.Reply r = new Commands(pc).run("phone", null, "replay/save", new JsonObject());
        assertEquals(409, r.status());
        assertEquals("Instant replay is off.", r.body().getAsJsonObject().get("error").getAsString());
    }

    @Test void soundTriggersAreRateLimited() {
        Commands c = new Commands(pc);
        int okCount = 0, limited = 0;
        for (int i = 0; i < 15; i++) {
            int s = c.run("phone", null, "sounds/play", args("{\"id\":\"s1\"}")).status();
            if (s == 200) okCount++; else if (s == 429) limited++;
        }
        assertEquals(10, okCount, "10 sounds per 5 seconds");
        assertEquals(5, limited);
        assertEquals(10, plays.get(), "limited requests never reach the PC");
        assertEquals(200, c.run("laptop", null, "sounds/play", args("{\"id\":\"s1\"}")).status(), "limit is per caller");
    }

    @Test void gainIsValidated() {
        Commands c = new Commands(pc);
        assertEquals(200, c.run("phone", null, "sounds/master", args("{\"gain\":3}")).status());
        assertEquals(3.0, master);
        for (String bad : new String[]{"{\"gain\":3.01}", "{\"gain\":-1}", "{}", "{\"gain\":\"loud\"}"})
            assertEquals(400, c.run("phone", null, "sounds/master", args(bad)).status(), bad);
        assertEquals(3.0, master, "bad values change nothing");
        assertEquals(400, c.run("phone", null, "sounds/play", args("{\"id\":\"../../x\"}")).status(), "ids are checked");
        assertEquals(400, c.run("phone", null, "replay/length", args("{\"seconds\":5000}")).status());
        assertFalse(Commands.isCommand("account/password"), "account changes aren't remote commands");
    }
}
