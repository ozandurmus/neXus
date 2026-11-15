package com.securityexpert.nexus.ui2.integration.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The keep-alive client the characterization suite relies on must itself tell a finished response from one whose end never
 * arrives. A loopback socket plays the server: it answers each request with a canned byte sequence and keeps the
 * connection open.
 */
class KeepAliveHttpTest {

    private static final String COMPLETE = "HTTP/1.1 403 \r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}";
    private static final String CHUNKED = "HTTP/1.1 200 \r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n";

    @Test
    void readsFramedResponsesAndReusesTheConnection() throws Exception {
        try (ServerSocket server = serve(COMPLETE, CHUNKED); KeepAliveHttp http = new KeepAliveHttp(server.getLocalPort(), 2_000)) {
            KeepAliveHttp.Response first = http.exchange("GET", "/a", Map.of(), null);
            assertEquals(403, first.status());
            assertEquals("{}", first.text());
            KeepAliveHttp.Response second = http.exchange("GET", "/b", Map.of(), null);
            assertEquals(200, second.status());
            assertEquals("abc", second.text());
        }
    }

    @Test
    void aBodyShorterThanItsContentLengthIsUnfinished() throws Exception {
        String shortBody = "HTTP/1.1 403 \r\nContent-Length: 10\r\n\r\n{}";
        try (ServerSocket server = serve(shortBody); KeepAliveHttp http = new KeepAliveHttp(server.getLocalPort(), 500)) {
            KeepAliveHttp.Unfinished e = assertThrows(KeepAliveHttp.Unfinished.class, () -> http.exchange("GET", "/a", Map.of(), null));
            assertTrue(e.received().contains("HTTP/1.1 403"), e.getMessage());
        }
    }

    @Test
    void aChunkedBodyWithoutItsLastChunkIsUnfinished() throws Exception {
        String open = "HTTP/1.1 403 \r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n";
        try (ServerSocket server = serve(open); KeepAliveHttp http = new KeepAliveHttp(server.getLocalPort(), 500)) {
            assertThrows(KeepAliveHttp.Unfinished.class, () -> http.exchange("GET", "/a", Map.of(), null));
        }
    }

    @Test
    void aResponseWithoutFramingIsUnfinishedOnAKeepAliveConnection() throws Exception {
        try (ServerSocket server = serve("HTTP/1.1 403 \r\nContent-Type: application/json\r\n\r\n{}");
                KeepAliveHttp http = new KeepAliveHttp(server.getLocalPort(), 500)) {
            assertThrows(KeepAliveHttp.Unfinished.class, () -> http.exchange("GET", "/a", Map.of(), null));
        }
    }

    /** Accepts one connection and answers its requests, in order, with {@code answers}; then holds the connection open. */
    private static ServerSocket serve(String... answers) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                for (String answer : answers) {
                    readRequestHead(in);
                    socket.getOutputStream().write(answer.getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                }
                while (in.read() >= 0) {
                    // hold the connection open until the client closes it
                }
            } catch (IOException ignored) {
                // the test closed the server
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("closed");
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }
}
