package io.zyclipra.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The PC end of a WebRTC data channel to the Zyclipra website. Browsers accept WebRTC's own certificates
 * (their fingerprints travel in the offer/answer, which this app exchanges through the owner's Google Drive),
 * so phones connect on the home network without any certificate warning.
 * <p>
 * Messages are small JSON objects: {@code {"i": id, "a": action, "b": args}} → {@code {"i": id, "s": status, "b": body}}.
 * Actions go through {@link Commands} exactly like the dashboard's (duplicate ids, rate limits, real results).
 */
public final class RtcBridge {
    private static final Logger LOG = Logger.getLogger(RtcBridge.class.getName());
    private static final int MAX_PEERS = 4;

    private final Commands commands;
    private final RemoteControl control;
    private final ExecutorService work = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "rtc-commands");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, RTCPeerConnection> peers = new LinkedHashMap<>();
    private PeerConnectionFactory factory;

    public RtcBridge(Commands commands, RemoteControl control) {
        this.commands = commands;
        this.control = control;
    }

    private synchronized PeerConnectionFactory factory() {
        // Headless audio: the data channel never opens a microphone or speaker.
        if (factory == null) factory = PeerConnectionFactory.builder().setAudioDeviceModule(new HeadlessAudioDeviceModule()).build();
        return factory;
    }

    /** Accepts a browser's offer and returns the answer SDP (with this PC's home-network candidates). */
    public String answer(String id, String offerSdp) throws Exception {
        CompletableFuture<Void> gathered = new CompletableFuture<>();
        RTCConfiguration cfg = new RTCConfiguration();   // no STUN/TURN servers: same network only
        RTCPeerConnection[] self = new RTCPeerConnection[1];
        RTCPeerConnection pc = factory().createPeerConnection(cfg, new PeerConnectionObserver() {
            @Override public void onIceCandidate(RTCIceCandidate c) { }
            @Override public void onIceGatheringChange(RTCIceGatheringState s) { if (s == RTCIceGatheringState.COMPLETE) gathered.complete(null); }
            @Override public void onDataChannel(RTCDataChannel dc) { attach(id, dc); }
            @Override public void onConnectionChange(RTCPeerConnectionState s) {
                LOG.info("website link " + id + ": " + s);
                if (s == RTCPeerConnectionState.FAILED || s == RTCPeerConnectionState.CLOSED) drop(id);
            }
        });
        self[0] = pc;
        synchronized (this) {
            while (peers.size() >= MAX_PEERS) drop(peers.keySet().iterator().next());
            peers.put(id, pc);
        }
        await(cb -> pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, offerSdp), cb));
        CompletableFuture<RTCSessionDescription> made = new CompletableFuture<>();
        pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override public void onSuccess(RTCSessionDescription d) { made.complete(d); }
            @Override public void onFailure(String e) { made.completeExceptionally(new IllegalStateException(e)); }
        });
        RTCSessionDescription answer = made.get(5, TimeUnit.SECONDS);
        await(cb -> pc.setLocalDescription(answer, cb));
        try { gathered.get(3, TimeUnit.SECONDS); } catch (Exception timeout) { /* send what we have */ }
        return pc.getLocalDescription().sdp;
    }

    private interface Call { void run(SetSessionDescriptionObserver cb); }

    private static void await(Call c) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        c.run(new SetSessionDescriptionObserver() {
            @Override public void onSuccess() { f.complete(null); }
            @Override public void onFailure(String e) { f.completeExceptionally(new IllegalStateException(e)); }
        });
        f.get(5, TimeUnit.SECONDS);
    }

    private static final int MAX_IN_FLIGHT = 64;

    private void attach(String peer, RTCDataChannel dc) {
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        dc.registerObserver(new RTCDataChannelObserver() {
            @Override public void onBufferedAmountChange(long previous) { }
            @Override public void onStateChange() { LOG.info("website link " + peer + ": channel " + dc.getState()); }
            @Override public void onMessage(RTCDataChannelBuffer buffer) {
                // Bounded work per connection: a misbehaving page can't queue unlimited requests.
                if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) { inFlight.decrementAndGet(); return; }
                ByteBuffer b = buffer.data;
                byte[] bytes = new byte[Math.min(b.remaining(), 64 * 1024)];
                b.get(bytes);
                String text = new String(bytes, StandardCharsets.UTF_8);
                work.execute(() -> { try { reply(peer, dc, text); } finally { inFlight.decrementAndGet(); } });   // never block WebRTC's own thread
            }
        });
    }

    private void reply(String peer, RTCDataChannel dc, String text) {
        JsonObject out = new JsonObject();
        try {
            JsonObject m = JsonParser.parseString(text).getAsJsonObject();
            String id = m.has("i") ? m.get("i").getAsString() : null;
            String action = m.get("a").getAsString();
            JsonObject args = m.has("b") && m.get("b").isJsonObject() ? m.getAsJsonObject("b") : new JsonObject();
            out.addProperty("i", id);
            Commands.Reply r = handle("rtc:" + peer, id, action, args);
            out.addProperty("s", r.status());
            out.add("b", r.body());
        } catch (Exception e) {
            out.addProperty("s", 400);
            out.add("b", Commands.ok("bad message"));
        }
        try {
            dc.send(new RTCDataChannelBuffer(ByteBuffer.wrap(out.toString().getBytes(StandardCharsets.UTF_8)), false));
        } catch (Exception e) {
            LOG.info("website link " + peer + ": couldn't answer: " + e.getMessage());
        }
    }

    Commands.Reply handle(String caller, String id, String action, JsonObject args) {
        return route(control, commands, caller, id, action, args);
    }

    /** Largest clip piece per message (base64 of it stays under the 64 KB Safari accepts). */
    static final int PART = 36 * 1024;

    /**
     * What the website may do, over WebRTC or HTTPS alike: status, soundboard, the remote commands and reading
     * this PC's clips – not the account, sessions, Google Drive or settings.
     */
    public static Commands.Reply route(RemoteControl control, Commands commands, String caller, String id, String action, JsonObject args) {
        try {
            return switch (action) {
                case "status" -> new Commands.Reply(200, control.status());
                case "sounds" -> new Commands.Reply(200, control.sounds());
                case "clips" -> new Commands.Reply(200, control.clips(Math.max(1, Math.min(200, args.has("limit") ? args.get("limit").getAsInt() : 60))));
                case "clip/thumb" -> {
                    var f = control.clipThumbnail(clipId(args));
                    if (f.isEmpty()) yield new Commands.Reply(404, Commands.ok("no thumbnail"));
                    JsonObject o = new JsonObject();
                    o.addProperty("data", java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(f.get())));
                    yield new Commands.Reply(200, o);
                }
                case "clip/part" -> {
                    var f = control.clipFile(clipId(args));
                    if (f.isEmpty()) yield new Commands.Reply(404, Commands.ok("That clip isn't on the PC any more."));
                    long offset = args.has("offset") ? args.get("offset").getAsLong() : 0;
                    try (var raf = new java.io.RandomAccessFile(f.get().toFile(), "r")) {
                        long size = raf.length();
                        if (offset < 0 || offset > size) yield new Commands.Reply(400, Commands.ok("bad offset"));
                        byte[] b = new byte[(int) Math.min(PART, size - offset)];
                        raf.seek(offset);
                        raf.readFully(b);
                        JsonObject o = new JsonObject();
                        o.addProperty("size", size);
                        o.addProperty("data", java.util.Base64.getEncoder().encodeToString(b));
                        yield new Commands.Reply(200, o);
                    }
                }
                default -> Commands.isCommand(action) && !action.startsWith("cloud/")
                        ? commands.run(caller, id, action, args)
                        : new Commands.Reply(403, (JsonElement) Commands.ok("not allowed"));
            };
        } catch (IllegalArgumentException e) {
            return new Commands.Reply(400, Commands.ok(e.getMessage()));
        } catch (java.io.IOException e) {
            return new Commands.Reply(500, Commands.ok("Couldn't read that clip."));
        }
    }

    private static String clipId(JsonObject a) {
        String s = a.has("id") && a.get("id").isJsonPrimitive() ? a.get("id").getAsString() : "";
        if (!s.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("bad clip id");
        return s;
    }

    private synchronized void drop(String id) {
        RTCPeerConnection pc = peers.remove(id);
        if (pc != null) work.execute(pc::close);
    }

    public synchronized void closeAll() {
        for (String id : java.util.List.copyOf(peers.keySet())) drop(id);
    }
}
