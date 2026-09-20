package com.securityexpert.nexus.ui2.worker.backup.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupReadPlan;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;

class CheckPointSnapshotExecutorTest {

    private static final String DEVICE_ID = "dev-cp-01";
    private static final String JOB_ID = "job-snap-101";
    private static final long FREE_SPACE_THRESHOLD = 5L * 1024 * 1024 * 1024; // 5 GB

    @TempDir
    Path tempDir;

    private ScriptedSnapshotTransport transport;
    private ArtefactStore artefactStore;
    private CheckPointSnapshotExecutor executor;

    @BeforeEach
    void setUp() {
        transport = new ScriptedSnapshotTransport();
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        artefactStore = new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(base64Key));
        executor = new CheckPointSnapshotExecutor(
                transport,
                artefactStore,
                FREE_SPACE_THRESHOLD,
                Duration.ofMillis(10),
                Duration.ofSeconds(5)
        );
    }

    @Test
    void rejectsWhenFreeSpaceInsufficient() {
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "Free disk space on /var/log: 1000 MB");
        BackupRequest request = new BackupRequest(
                new ConnectionTarget("target-1", "192.0.2.1", 22),
                Optional.of("cred-backup"),
                "trust-rule-1"
        );

        BackupResult result = executor.collectSnapshot(request, DEVICE_ID, JOB_ID);

        assertTrue(result instanceof BackupResult.InsufficientFreeSpace);
    }

    @Test
    void collectSnapshotSucceedsWithScpPullAndCleanup() {
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "Free disk space on /var/log: 50000 MB");
        transport.statusSequence = "Snapshot completed successfully";

        BackupRequest request = new BackupRequest(
                new ConnectionTarget("target-1", "192.0.2.1", 22),
                Optional.of("cred-backup"),
                "trust-rule-1"
        );

        BackupResult result = executor.collectSnapshot(request, DEVICE_ID, JOB_ID);

        assertTrue(result instanceof BackupResult.Completed, "Expected Completed, got: " + result);
        BackupResult.Completed completed = (BackupResult.Completed) result;
        assertEquals(21L, completed.artefact().plaintextBytes());

        // Verify that cleanup command was issued to prevent device disk exhaustion
        boolean deleteIssued = transport.commandsIssued.stream()
                .anyMatch(cmd -> cmd.contains("delete snapshot"));
        assertTrue(deleteIssued, "Snapshot post-flight deletion command must be issued");
    }

    @Test
    void failsWhenSubmitRefused() {
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "Free disk space on /var/log: 50000 MB");
        transport.addSnapshotError = "Failed: Another snapshot is currently in progress";

        BackupRequest request = new BackupRequest(
                new ConnectionTarget("target-1", "192.0.2.1", 22),
                Optional.of("cred-backup"),
                "trust-rule-1"
        );

        BackupResult result = executor.collectSnapshot(request, DEVICE_ID, JOB_ID);

        assertTrue(result instanceof BackupResult.SubmitRefused);
    }

    @Test
    void failsWhenStatusSaysSnapshotNotCompleted() {
        transport.execOutputs.put(BackupReadPlan.CP_SHOW_DISKSPACE, "Free disk space on /var/log: 50000 MB");
        transport.statusSequence = "Operation not completed due to timeout";

        BackupRequest request = new BackupRequest(
                new ConnectionTarget("target-1", "192.0.2.1", 22),
                Optional.of("cred-backup"),
                "trust-rule-1"
        );

        BackupResult result = executor.collectSnapshot(request, DEVICE_ID, JOB_ID);
        assertTrue(result instanceof BackupResult.SubmitRefused, "Expected SubmitRefused when status says not completed, got: " + result);
    }

    private static class ScriptedSnapshotTransport implements DeviceTransport {
        final Map<String, String> execOutputs = new HashMap<>();
        final List<String> commandsIssued = new ArrayList<>();
        String statusSequence = "Completed";
        String addSnapshotError = null;

        record FakeSession(String sessionId) implements TransportSession {}

        @Override
        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return new ConnectResult.Authenticated(new FakeSession("test-session"));
        }

        @Override
        public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            commandsIssued.add(spec.command());
            if (spec.command().contains("add snapshot") && addSnapshotError != null) {
                return new ExecResult.Completed(addSnapshotError, 1);
            }
            if (BackupReadPlan.CP_SHOW_SNAPSHOT_STATUS.equals(spec.command())) {
                return new ExecResult.Completed(statusSequence, 0);
            }
            String output = execOutputs.getOrDefault(spec.command(), "Success");
            return new ExecResult.Completed(output, 0);
        }

        @Override
        public FetchStreamResult fetchStreaming(TransportSession session, FetchSpec spec, Duration timeout,
                OutputStream sink) {
            try {
                sink.write("mock-snapshot-payload".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new FetchStreamResult.Fetched(21);
        }

        @Override
        public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not exercised");
        }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not exercised");
        }

        @Override
        public void disconnect(TransportSession session) {
        }
    }
}
