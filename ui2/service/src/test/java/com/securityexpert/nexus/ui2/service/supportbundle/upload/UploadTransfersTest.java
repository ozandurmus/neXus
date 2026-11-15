package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** Real SFTP and SCP exchanges against an in-process sshd, and HTTPS PUT against a local HTTP server; synthetic data only. */
class UploadTransfersTest {

    private static final char[] TOKEN = "synthetic-token-1".toCharArray();
    private SshServer sshd;
    private HttpServer http;

    @AfterEach
    void stop() throws Exception {
        if (sshd != null) sshd.stop(true);
        if (http != null) http.stop(0);
    }

    private static UploadBody body(byte[] bytes) {
        return new UploadBody() {
            @Override public long length() { return bytes.length; }
            @Override public void writeTo(OutputStream out) throws IOException { out.write(bytes, 0, bytes.length / 2); out.write(bytes, bytes.length / 2, bytes.length - bytes.length / 2); }
        };
    }

    private SshServer server(Path root, AtomicReference<byte[]> scpReceived, List<String> scpHeaders) throws Exception {
        SshServer s = SshServer.setUpDefaultServer();
        s.setPort(0);
        s.setHost("127.0.0.1");
        s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(root.resolve("hostkey.ser")));
        s.setPasswordAuthenticator((user, password, session) -> "CASE-1".equals(user) && "synthetic-token-1".equals(password));
        s.setFileSystemFactory(new VirtualFileSystemFactory(root));
        s.setSubsystemFactories(List.<SubsystemFactory>of(new SftpSubsystemFactory.Builder().build()));
        s.setCommandFactory(new ScpSink(scpReceived, scpHeaders));
        s.start();
        sshd = s;
        return s;
    }

    private static UploadTarget target(UploadDestination d, int port, String user, String name) {
        return new UploadTarget(d, "127.0.0.1", port, user, name);
    }

    @Test
    void sftpPutsTheBytesConfirmsTheSizeAndRecordsTheHostKey(@TempDir Path root) throws Exception {
        server(root, new AtomicReference<>(), new ArrayList<>());
        byte[] data = new byte[300_000];
        new java.util.Random(7).nextBytes(data);
        List<String> seen = new ArrayList<>();
        TransferResult result = new SftpTransfer().send(target(UploadDestination.GENERIC_SFTP, sshd.getPort(), "CASE-1", "bundle.tgz"),
                TOKEN.clone(), body(data), (host, port, type, fp) -> { seen.add(host + ":" + port + " " + type + " " + fp); return false; });
        assertArrayEquals(data, Files.readAllBytes(root.resolve("bundle.tgz")));
        assertTrue(result.remoteStatus().startsWith("SFTP_OK size 300000 confirmed"), result.remoteStatus());
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).contains("SHA256:"), seen.get(0));
        assertFalse(result.hostKeyChanged());
        assertNotNull(result.hostKeyFingerprint());
    }

    @Test
    void aChangedHostKeyIsReportedAndNeverRefused(@TempDir Path root) throws Exception {
        server(root, new AtomicReference<>(), new ArrayList<>());
        TransferResult result = new SftpTransfer().send(target(UploadDestination.GENERIC_SFTP, sshd.getPort(), "CASE-1", "x.tgz"),
                TOKEN.clone(), body(new byte[10]), (h, p, t, f) -> true);
        assertTrue(result.hostKeyChanged());
        assertTrue(Files.exists(root.resolve("x.tgz")), "the transfer went ahead");
    }

    @Test
    void aWrongCredentialIsAnAuthFailureThatIsNotRetried(@TempDir Path root) throws Exception {
        server(root, new AtomicReference<>(), new ArrayList<>());
        UploadException e = assertThrows(UploadException.class, () -> new SftpTransfer().send(
                target(UploadDestination.GENERIC_SFTP, sshd.getPort(), "CASE-1", "x.tgz"), "wrong".toCharArray(),
                body(new byte[10]), (h, p, t, f) -> false));
        assertEquals("AUTH_FAILED", e.code());
        assertFalse(e.retryable());
        assertFalse(e.getMessage().contains("wrong"));
    }

    @Test
    void anUnreachableDestinationSaysSoAndMentionsTheFirewall() throws Exception {
        int closed;
        try (var s = new java.net.ServerSocket(0)) { closed = s.getLocalPort(); }
        UploadException e = assertThrows(UploadException.class, () -> new SftpTransfer().send(
                target(UploadDestination.GENERIC_SFTP, closed, "CASE-1", "x.tgz"), TOKEN.clone(), body(new byte[10]), (h, p, t, f) -> false));
        assertEquals("CONNECTION_FAILED", e.code());
        assertTrue(e.retryable());
        assertTrue(e.getMessage().contains("127.0.0.1:" + closed) && e.getMessage().contains("firewall"), e.getMessage());
    }

    @Test
    void scpSendsTheHeaderWithTheExactSizeAndTheBytes(@TempDir Path root) throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        List<String> headers = new ArrayList<>();
        server(root, received, headers);
        byte[] data = new byte[70_001];
        new java.util.Random(3).nextBytes(data);
        TransferResult result = new ScpTransfer().send(target(UploadDestination.PALOALTO_SCP, sshd.getPort(), "CASE-1", "tsf.tgz"),
                TOKEN.clone(), body(data), (h, p, t, f) -> false);
        assertEquals(List.of("C0600 70001 tsf.tgz"), headers);
        assertArrayEquals(data, received.get());
        assertTrue(result.remoteStatus().startsWith("SCP_OK"));
    }

    @Test
    void anSftpServerFailureMidStreamIsAWriteFailureNotASourceFailure(@TempDir Path root) throws Exception {
        server(root, new AtomicReference<>(), new ArrayList<>());
        UploadBody dropping = new UploadBody() {
            @Override public long length() { return 100; }
            @Override public void writeTo(OutputStream out) throws IOException { out.write(new byte[50]); sshd.stop(true); throw new SftpTransfer.SourceReadException(new IOException("vault")); }
        };
        UploadException e = assertThrows(UploadException.class, () -> new SftpTransfer().send(
                target(UploadDestination.GENERIC_SFTP, sshd.getPort(), "CASE-1", "x.tgz"), TOKEN.clone(), dropping, (h, p, t, f) -> false));
        assertEquals("SOURCE_FAILED", e.code());
    }

    // ------------------------------------------------------------------ HTTPS PUT

    private record Put(String method, String path, String auth, byte[] body) { }

    private int httpServer(int status, AtomicReference<Put> seen) throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) { body = in.readAllBytes(); }
            seen.set(new Put(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        http.start();
        return http.getAddress().getPort();
    }

    @Test
    void httpsPutSendsBasicAuthTheExactBodyAndAcceptsCreated() throws Exception {
        AtomicReference<Put> seen = new AtomicReference<>();
        int port = httpServer(201, seen);
        byte[] data = "synthetic archive bytes".getBytes(StandardCharsets.UTF_8);
        TransferResult result = new HttpsPutTransfer("http").send(target(UploadDestination.CISCO_CXD_HTTPS, port, "CASE-1", "b.tgz"),
                TOKEN.clone(), body(data), (h, p, t, f) -> false);
        Put put = seen.get();
        assertEquals("PUT", put.method());
        assertEquals("/home/b.tgz", put.path());
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("CASE-1:synthetic-token-1".getBytes(StandardCharsets.UTF_8)), put.auth());
        assertArrayEquals(data, put.body());
        assertEquals("HTTP 201", result.remoteStatus());
    }

    @Test
    void httpsPutMapsRefusalsToClosedCodes() throws Exception {
        for (int[] pair : new int[][] {{401, 0}, {403, 0}, {500, 1}}) {
            AtomicReference<Put> seen = new AtomicReference<>();
            int port = httpServer(pair[0], seen);
            UploadException e = assertThrows(UploadException.class, () -> new HttpsPutTransfer("http").send(
                    target(UploadDestination.CISCO_CXD_HTTPS, port, "CASE-1", "b.tgz"), TOKEN.clone(), body(new byte[5]), (h, p, t, f) -> false));
            assertEquals(pair[1] == 0 ? "AUTH_FAILED" : "REMOTE_REJECTED", e.code());
            assertFalse(e.retryable());
            http.stop(0);
        }
    }

    @Test
    void httpsPutToAClosedPortIsARetryableConnectionFailure() throws Exception {
        int closed;
        try (var s = new java.net.ServerSocket(0)) { closed = s.getLocalPort(); }
        UploadException e = assertThrows(UploadException.class, () -> new HttpsPutTransfer("http").send(
                target(UploadDestination.CISCO_CXD_HTTPS, closed, "CASE-1", "b.tgz"), TOKEN.clone(), body(new byte[5]), (h, p, t, f) -> false));
        assertEquals("CONNECTION_FAILED", e.code());
        assertTrue(e.retryable());
    }

    // ------------------------------------------------------------------ a minimal scp sink for the test server

    /** {@code scp -t .}: ack, read the C header, ack, read exactly the announced bytes, read the end byte, ack. */
    private static final class ScpSink implements CommandFactory {
        private final AtomicReference<byte[]> received; private final List<String> headers;
        ScpSink(AtomicReference<byte[]> received, List<String> headers) { this.received = received; this.headers = headers; }

        @Override
        public Command createCommand(ChannelSession channel, String command) {
            return new Command() {
                private InputStream in; private OutputStream out; private org.apache.sshd.server.ExitCallback callback;
                @Override public void setInputStream(InputStream in) { this.in = in; }
                @Override public void setOutputStream(OutputStream out) { this.out = out; }
                @Override public void setErrorStream(OutputStream err) { }
                @Override public void setExitCallback(org.apache.sshd.server.ExitCallback callback) { this.callback = callback; }
                @Override public void start(ChannelSession c, org.apache.sshd.server.Environment env) {
                    Thread t = new Thread(() -> {
                        try {
                            if (!"scp -t .".equals(command)) { callback.onExit(2); return; }
                            out.write(0); out.flush();
                            StringBuilder header = new StringBuilder();
                            int b;
                            while ((b = in.read()) != '\n') header.append((char) b);
                            headers.add(header.toString());
                            long size = Long.parseLong(header.toString().split(" ")[1]);
                            out.write(0); out.flush();
                            ByteArrayOutputStream data = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            long left = size;
                            while (left > 0) { int n = in.read(buf, 0, (int) Math.min(buf.length, left)); if (n < 0) break; data.write(buf, 0, n); left -= n; }
                            in.read();
                            received.set(data.toByteArray());
                            out.write(0); out.flush();
                            callback.onExit(0);
                        } catch (IOException e) {
                            callback.onExit(1);
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }
                @Override public void destroy(ChannelSession c) { }
            };
        }
    }
}
