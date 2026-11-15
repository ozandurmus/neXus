package com.securityexpert.nexus.ui2.integration.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One persistent HTTP/1.1 connection, used the way a browser uses it: {@code Connection: keep-alive}, the request headers a
 * Chromium {@code fetch} sends, and a response read by its own framing ({@code Content-Length}, chunked, or none for a
 * HEAD/204/304) instead of until the server closes. A response whose end never arrives fails within the read timeout with
 * what was received so far, which is exactly the symptom a browser shows as a request that never finishes.
 *
 * <p>Added for the 2026-11-15 preview e2e stall (Spring Security Steps 1-3): the browser showed the aiview transcript 403
 * as a request that never finished. On the wire the service had answered completely (the stall was Chromium's handling of
 * an unread {@code Cache-Control: no-store} body, fixed in the e2e helper); these probes keep it that way, which a
 * {@code Connection: close} client (the golden suite) cannot check, since the close ends any body.</p>
 */
public final class KeepAliveHttp implements AutoCloseable {

    /** A response as framed on the wire. */
    public record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** The response did not end inside the read timeout; {@code received} is what came before the wait. */
    public static final class Unfinished extends IOException {
        private final String received;

        Unfinished(String what, byte[] received) {
            super(what + "; received so far: " + printable(received));
            this.received = printable(received);
        }

        public String received() {
            return received;
        }
    }

    private final String host;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public KeepAliveHttp(int port, int readTimeoutMillis) throws IOException {
        this.host = "127.0.0.1:" + port;
        this.socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(readTimeoutMillis);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** The headers Chromium's {@code fetch(path, {credentials: "include"})} sends for a same-origin request from the console. */
    public static Map<String, String> browserHeaders(String cookie, String origin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("sec-ch-ua-platform", "\"macOS\"");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "HeadlessChrome/141.0.0.0 Safari/537.36");
        headers.put("Accept", "*/*");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Referer", origin + "/?screen=operations&tab=jobs&job_type=backup");
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Accept-Language", "en-US");
        if (cookie != null) headers.put("Cookie", "ui2_session=" + cookie);
        return headers;
    }

    public Response exchange(String method, String target, Map<String, String> headers, byte[] body) throws IOException {
        StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append("\r\n");
        head.append("Connection: keep-alive\r\n");
        for (var header : headers.entrySet()) head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        if (body != null || "POST".equals(method) || "PUT".equals(method)) {
            head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();
        return read("HEAD".equals(method));
    }

    private Response read(boolean head) throws IOException {
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        String statusLine = line(seen);
        String[] parts = statusLine.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new Unfinished("not a status line", seen.toByteArray());
        int status = Integer.parseInt(parts[1]);
        Map<String, List<String>> headers = new TreeMap<>();
        for (String line = line(seen); !line.isEmpty(); line = line(seen)) {
            int colon = line.indexOf(':');
            headers.computeIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
        }
        Response framed = new Response(status, headers, new byte[0]);
        if (head || status == 204 || status == 304 || status / 100 == 1) return framed;
        String length = framed.header("content-length");
        String encoding = framed.header("transfer-encoding");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            while (true) {
                String size = line(seen);
                int semicolon = size.indexOf(';');
                int n = Integer.parseInt((semicolon < 0 ? size : size.substring(0, semicolon)).trim(), 16);
                if (n == 0) {
                    while (!line(seen).isEmpty()) {
                        // trailers
                    }
                    break;
                }
                payload.write(bytes(n, seen));
                line(seen);
            }
        } else if (length != null) {
            payload.write(bytes(Integer.parseInt(length), seen));
        } else {
            // Neither framing on a keep-alive connection: the body would end only at close, which a browser waits for.
            throw new Unfinished("HTTP " + status + " response without Content-Length or chunked framing", seen.toByteArray());
        }
        return new Response(status, headers, payload.toByteArray());
    }

    private String line(ByteArrayOutputStream seen) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = next(seen);
            if (previous == '\r' && b == '\n') {
                byte[] raw = line.toByteArray();
                return new String(raw, 0, raw.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            previous = b;
        }
    }

    private byte[] bytes(int n, ByteArrayOutputStream seen) throws IOException {
        byte[] result = new byte[n];
        for (int i = 0; i < n; i++) result[i] = (byte) next(seen);
        return result;
    }

    private int next(ByteArrayOutputStream seen) throws IOException {
        int b;
        try {
            b = in.read();
        } catch (SocketTimeoutException timeout) {
            throw new Unfinished("response did not finish within " + socket.getSoTimeout() + " ms", seen.toByteArray());
        }
        if (b < 0) throw new Unfinished("connection closed before the response ended", seen.toByteArray());
        seen.write(b);
        return b;
    }

    private static String printable(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        return text.replace("\r", "\\r").replace("\n", "\\n\n");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
