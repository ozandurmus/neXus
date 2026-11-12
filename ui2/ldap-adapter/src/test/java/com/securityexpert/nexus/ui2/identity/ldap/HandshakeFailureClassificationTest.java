package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * A server that accepts TCP and then fails the TLS handshake must never be reported as "could not connect": the
 * network path works, the TLS negotiation does not. Each case is one way a server refuses TLS after TCP connects.
 */
class HandshakeFailureClassificationTest {

    /** A TCP listener on loopback that reads the ClientHello and then does {@code afterHello} to the socket. */
    private static String probe(Consumer<Socket> afterHello) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    InputStream in = socket.getInputStream();
                    in.read(new byte[5]);
                    afterHello.accept(socket);
                } catch (Exception ignored) {
                    // the client side asserts the outcome
                }
            });
            server.setDaemon(true);
            server.start();
            DirectoryProfile profile = new DirectoryProfile(DirectoryProfile.DEFAULT_PROFILE_ID, List.of("127.0.0.1"),
                    listener.getLocalPort(), ActiveDirectoryFixture.BASE_DN, ActiveDirectoryFixture.ADMIN_GROUP,
                    ActiveDirectoryFixture.USERS_GROUP, ActiveDirectoryFixture.SERVICE_DN, 3, true,
                    DirectoryProfile.TrustMode.TRUST_ANY, null);
            DirectoryProbe.Report report = DirectoryProbe.run(profile, "service-secret".toCharArray(), null);
            server.join(5000);
            return report.servers().get(0).outcome();
        }
    }

    @Test
    void aResetDuringTheHandshakeIsATlsHandshakeFailure() throws Exception {
        assertEquals("tls_handshake_failed", probe(socket -> {
            try {
                socket.setSoLinger(true, 0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    @Test
    void aCloseDuringTheHandshakeIsATlsHandshakeFailure() throws Exception {
        assertEquals("tls_handshake_failed", probe(socket -> { }));
    }

    @Test
    void aHandshakeFailureAlertIsATlsHandshakeFailure() throws Exception {
        assertEquals("tls_handshake_failed", probe(socket -> {
            try {
                OutputStream out = socket.getOutputStream();
                // TLS 1.2 record: fatal alert, handshake_failure (no shared protocol version or cipher suite).
                out.write(new byte[] { 0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28 });
                out.flush();
                Thread.sleep(200);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    @Test
    void nothingListeningIsStillConnectFailed() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        DirectoryProfile profile = new DirectoryProfile(DirectoryProfile.DEFAULT_PROFILE_ID, List.of("127.0.0.1"), port,
                ActiveDirectoryFixture.BASE_DN, ActiveDirectoryFixture.ADMIN_GROUP, ActiveDirectoryFixture.USERS_GROUP,
                ActiveDirectoryFixture.SERVICE_DN, 3, true, DirectoryProfile.TrustMode.TRUST_ANY, null);
        assertEquals("connect_failed",
                DirectoryProbe.run(profile, "service-secret".toCharArray(), null).servers().get(0).outcome());
    }
}
