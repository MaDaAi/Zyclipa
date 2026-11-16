package io.zyclipa.audio;

import io.zyclipa.core.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * One audio track for recordings: a steady real-time stream of 48 kHz 16-bit stereo PCM served
 * to FFmpeg on a loopback-only TCP port (127.0.0.1). Sources push whatever they capture; a pacer
 * sends exactly one second of audio per second, mixing all sources and filling gaps with silence
 * (Windows sends nothing at all while an output is silent). FFmpeg reads it with
 * {@code -f s16le -ar 48000 -ac 2 -i tcp://127.0.0.1:port}.
 */
public final class AudioFeed implements AutoCloseable {
    private static final Logger LOG = Log.get(AudioFeed.class);
    private static final int RATE = 48000, MAX_BACKLOG_FRAMES = RATE / 2;

    private final String title;
    private final ServerSocket server;
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
    private final Map<Object, Deque<short[]>> sources = new ConcurrentHashMap<>();
    private final Map<Object, int[]> sourceOffsets = new ConcurrentHashMap<>();
    private final Thread pacer, acceptor;
    private volatile boolean run = true;
    private volatile float peak;
    private volatile boolean heardSomething;

    public AudioFeed(String title) throws IOException {
        this.title = title;
        server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::accept, "feed-accept-" + title);
        acceptor.setDaemon(true);
        acceptor.start();
        pacer = new Thread(this::pace, "feed-" + title);
        pacer.setDaemon(true);
        pacer.setPriority(Thread.MAX_PRIORITY);
        pacer.start();
    }

    public String title() { return title; }
    public int port() { return server.getLocalPort(); }
    public String url() { return "tcp://127.0.0.1:" + port(); }
    /** Loudest sample sent in the last block (0..1). */
    public float peak() { return peak; }
    /** True once anything other than silence has gone through this track. */
    public boolean heardSomething() { return heardSomething; }
    public void resetHeard() { heardSomething = false; }

    /** Adds PCM (s16le stereo) from a source; different sources are mixed together. */
    public void push(Object source, byte[] pcm, int len) {
        short[] s = new short[len / 2];
        for (int i = 0, j = 0; i < s.length; i++, j += 2) s[i] = (short) ((pcm[j] & 0xff) | (pcm[j + 1] << 8));
        Deque<short[]> q = sources.computeIfAbsent(source, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(s);
            int frames = 0;
            for (short[] b : q) frames += b.length / 2;
            while (frames > MAX_BACKLOG_FRAMES && q.size() > 1) frames -= q.removeFirst().length / 2; // never drift behind
        }
    }

    public void endSource(Object source) {
        sources.remove(source);
        sourceOffsets.remove(source);
    }

    private void accept() {
        while (run) {
            try {
                Socket s = server.accept();
                if (!s.getInetAddress().isLoopbackAddress()) { s.close(); continue; }
                s.setTcpNoDelay(true);
                clients.add(s.getOutputStream());
            } catch (IOException e) {
                if (run) LOG.fine("accept: " + e);
            }
        }
    }

    private void pace() {
        long start = System.nanoTime();
        long sent = 0;
        while (run) {
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            long target = (System.nanoTime() - start) * RATE / 1_000_000_000L;
            int frames = (int) Math.min(RATE, target - sent);
            if (frames <= 0) continue;
            int[] mix = new int[frames * 2];
            for (var e : sources.entrySet()) {
                Deque<short[]> q = e.getValue();
                int[] off = sourceOffsets.computeIfAbsent(e.getKey(), k -> new int[1]);
                synchronized (q) {
                    int i = 0;
                    while (i < mix.length && !q.isEmpty()) {
                        short[] b = q.peekFirst();
                        int take = Math.min(b.length - off[0], mix.length - i);
                        for (int k = 0; k < take; k++) mix[i + k] += b[off[0] + k];
                        i += take;
                        off[0] += take;
                        if (off[0] >= b.length) { q.removeFirst(); off[0] = 0; }
                    }
                }
            }
            byte[] out = new byte[mix.length * 2];
            int pk = 0;
            for (int i = 0, j = 0; i < mix.length; i++, j += 2) {
                int v = Math.max(-32768, Math.min(32767, mix[i]));
                pk = Math.max(pk, Math.abs(v));
                out[j] = (byte) v;
                out[j + 1] = (byte) (v >> 8);
            }
            peak = pk / 32768f;
            if (pk > 64) heardSomething = true;
            sent += frames;
            for (OutputStream o : clients) {
                try { o.write(out); } catch (IOException e) { clients.remove(o); }
            }
        }
    }

    @Override public void close() {
        run = false;
        try { server.close(); } catch (IOException ignored) { }
        for (OutputStream o : clients) { try { o.close(); } catch (IOException ignored) { } }
        clients.clear();
        pacer.interrupt();
    }
}
