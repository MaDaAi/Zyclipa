package io.zyclipra;

import com.sun.net.httpserver.HttpServer;
import io.zyclipra.cloud.DriveClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the resumable upload protocol against a local fake of the Drive upload endpoint
 * (not the real Google service), including an interruption in the middle of the transfer.
 */
class DriveClientTest {
    @TempDir Path tmp;

    @Test void resumableUploadSurvivesInterruption() throws Exception {
        byte[] data = new byte[DriveClient.CHUNK * 2 + 12345];
        new Random(7).nextBytes(data);
        Path file = tmp.resolve("clip.mp4");
        Files.write(file, data);

        ByteArrayOutputStream received = new ByteArrayOutputStream();
        AtomicInteger puts = new AtomicInteger();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + fake.getAddress().getPort();
        fake.createContext("/upload/drive/v3/files", ex -> {
            assertEquals("Bearer t0ken", ex.getRequestHeaders().getFirst("Authorization"));
            ex.getResponseHeaders().add("Location", base + "/session/1");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        fake.createContext("/session/1", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            String range = ex.getRequestHeaders().getFirst("Content-Range");
            int n = puts.incrementAndGet();
            if (n == 2) { ex.sendResponseHeaders(503, -1); ex.close(); return; } // simulated drop on the 2nd chunk
            if (range.startsWith("bytes */")) {
                int have = received.size();
                if (have > 0) ex.getResponseHeaders().add("Range", "bytes=0-" + (have - 1));
                ex.sendResponseHeaders(308, -1);
                ex.close();
                return;
            }
            long start = Long.parseLong(range.substring(6, range.indexOf('-')));
            if (start != received.size()) { ex.sendResponseHeaders(400, -1); ex.close(); return; }
            received.write(body);
            if (received.size() == data.length) {
                byte[] ok = "{\"id\":\"file123\",\"size\":\"1\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, ok.length);
                ex.getResponseBody().write(ok);
            } else {
                ex.getResponseHeaders().add("Range", "bytes=0-" + (received.size() - 1));
                ex.sendResponseHeaders(308, -1);
            }
            ex.close();
        });
        fake.start();
        try {
            DriveClient dc = new DriveClient(HttpClient.newHttpClient(), new DriveClient.Tokens() {
                public String get() { return "t0ken"; }
                public void invalidate() { }
            }, base);
            String session = dc.startUpload(file, "clip.mp4", "folder");
            assertThrows(IOException.class, () -> dc.continueUpload(session, file, 0, (d, t) -> { }));
            long have = dc.uploadedBytes(session, data.length);
            assertEquals(DriveClient.CHUNK, have, "server kept the first chunk");
            long[] last = new long[1];
            String id = dc.continueUpload(session, file, have, (d, t) -> last[0] = d);
            assertEquals("file123", id);
            assertArrayEquals(data, received.toByteArray());
            assertEquals(data.length, last[0]);
        } finally {
            fake.stop(0);
        }
    }
}
