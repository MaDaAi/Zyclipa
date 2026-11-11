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

    private void attach(String peer, RTCDataChannel dc) {
        dc.registerObserver(new RTCDataChannelObserver() {
            @Override public void onBufferedAmountChange(long previous) { }
            @Override public void onStateChange() { LOG.info("website link " + peer + ": channel " + dc.getState()); }
            @Override public void onMessage(RTCDataChannelBuffer buffer) {
                ByteBuffer b = buffer.data;
                byte[] bytes = new byte[Math.min(b.remaining(), 64 * 1024)];
                b.get(bytes);
                String text = new String(bytes, StandardCharsets.UTF_8);
                work.execute(() -> reply(peer, dc, text));     // never block WebRTC's own thread
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

    /** Same rules as the website's HTTPS API: status, soundboard and the remote commands – nothing else. */
    Commands.Reply handle(String caller, String id, String action, JsonObject args) {
        return switch (action) {
            case "status" -> new Commands.Reply(200, control.status());
            case "sounds" -> new Commands.Reply(200, control.sounds());
            default -> Commands.isCommand(action) && !action.startsWith("cloud/")
                    ? commands.run(caller, id, action, args)
                    : new Commands.Reply(403, (JsonElement) Commands.ok("not allowed"));
        };
    }

    private synchronized void drop(String id) {
        RTCPeerConnection pc = peers.remove(id);
        if (pc != null) work.execute(pc::close);
    }

    public synchronized void closeAll() {
        for (String id : java.util.List.copyOf(peers.keySet())) drop(id);
    }
}
