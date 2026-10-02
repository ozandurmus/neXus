package com.securityexpert.nexus.ui2.worker.backup.asa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

class CiscoAsaExecutorTest {
    private static final class SingleChannelTransport implements DeviceTransport {
        final List<TransportSession> sessions = new ArrayList<>();
        final List<ConnectSpec> specs = new ArrayList<>();
        final Set<TransportSession> shellSessions = new HashSet<>();
        final Set<TransportSession> scpSessions = new HashSet<>();
        final Set<TransportSession> closed = new HashSet<>();
        final List<String> commands = new ArrayList<>();

        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            assertEquals("192.0.2.10", target.host());
            assertEquals(22, target.port());
            assertEquals(Duration.ofSeconds(30), timeout);
            String id = "session-" + sessions.size();
            TransportSession session = () -> id;
            sessions.add(session);
            specs.add(spec);
            return new ConnectResult.Authenticated(session);
        }

        public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            assertFalse(closed.contains(session));
            assertFalse(scpSessions.contains(session), "a second channel is refused");
            shellSessions.add(session);
            commands.add(spec.command());
            String output = spec.command().equals(CiscoAsaPlan.SHOW_CURPRIV) ? "Current privilege level : 15"
                    : spec.command().startsWith("backup ") ? "Backup finished!"
                    : "ASA Version 9.22\n";
            return new ExecResult.Completed(output, 0);
        }

        void openScp(TransportSession session) throws IOException {
            assertFalse(closed.contains(session));
            if (shellSessions.contains(session) || !scpSessions.add(session)) {
                throw new IOException("scp channel: channel is not opened");
            }
        }

        public void disconnect(TransportSession session) { assertTrue(closed.add(session)); }
        public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { throw new AssertionError("unexpected exec"); }
        public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { throw new AssertionError("unexpected SFTP"); }
        public XmlApiResult xmlApiCall(ApiTarget t, XmlApiSpec s, Duration d) { throw new AssertionError("unexpected API"); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scpUsesAnUnopenedSessionAndAlwaysClosesIt(boolean transferFails, @TempDir Path dir) {
        var transport = new SingleChannelTransport();
        var store = new FileArtefactStore(dir, ArtefactStoreCipher.fromBase64Key(
                Base64.getEncoder().encodeToString(new byte[32])));
        var executor = new CiscoAsaExecutor(transport, store, (session, path, sink, maxBytes, timeout) -> {
            transport.openScp(session);
            assertEquals("disk0:/" + CiscoAsaPlan.archiveName("job-1"), path);
            assertEquals(Duration.ofSeconds(600), timeout);
            assertEquals(4L * 1024 * 1024 * 1024, maxBytes);
            if (transferFails) throw new IOException("synthetic transfer failure");
            try (var out = sink.open(3)) { out.write(new byte[] {1, 2, 3}); }
            return 3;
        });

        BackupResult result = com.securityexpert.nexus.ui2.worker.transcript.TranscriptAssertions.capture(
                () -> executor.backup(new Target("192.0.2.10", 22), "credential-1", "device-1", "job-1"),
                "opening separate SCP-only session", "sha256=", transferFails ? "synthetic transfer failure" : "Completed");

        if (transferFails) assertInstanceOf(BackupResult.Partial.class, result);
        else assertInstanceOf(BackupResult.Completed.class, result);
        assertEquals(3, transport.sessions.size());
        assertEquals(Set.of(transport.sessions.get(1)), transport.scpSessions);
        assertEquals(Set.of(transport.sessions.get(0), transport.sessions.get(2)), transport.shellSessions);
        assertEquals(new HashSet<>(transport.sessions), transport.closed);
        assertEquals(1, new HashSet<>(transport.specs).size(), "same credential and trust on all connections");
        assertTrue(transport.commands.contains(CiscoAsaPlan.deleteArchive(CiscoAsaPlan.archiveName("job-1"))));
    }
}
