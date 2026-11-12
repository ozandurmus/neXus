package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.backup.cp.CheckPointSnapshotExecutor;
import com.securityexpert.nexus.ui2.worker.backup.cp.MdsExportExecutor;

/** D.3: {@code ConnectFailed.authentication} is true only when the cause is a rejected credential. */
class AuthenticationFlagTest {
    private static final BackupRequest REQUEST = new BackupRequest(new ConnectionTarget("endpoint-1", "192.0.2.10", 22),
            Optional.of("cred-1"), "utils.cp_ssh_trust");

    private interface Collect {
        BackupResult run(ScriptedBackupTransport transport);
    }

    private static void check(Collect collect) {
        var auth = new ScriptedBackupTransport();
        auth.connectOverride = new ConnectResult.AuthenticationFailed("denied");
        assertTrue(assertInstanceOf(BackupResult.ConnectFailed.class, collect.run(auth)).authentication());
        for (ConnectResult other : new ConnectResult[] {new ConnectResult.TimedOut("t"), new ConnectResult.HostKeyRejected("host_key_mismatch: x")}) {
            var transport = new ScriptedBackupTransport();
            transport.connectOverride = other;
            assertFalse(assertInstanceOf(BackupResult.ConnectFailed.class, collect.run(transport)).authentication(), other.toString());
        }
    }

    @Test
    void gaiaBackupFlagsOnlyAnAuthenticationFailure() {
        check(transport -> new BackupCapabilityExecutor(transport, Mockito.mock(ArtefactStore.class), 1L, Duration.ofMillis(1),
                Duration.ofMillis(5)).collect(REQUEST, "dev-1", "job-1"));
    }

    @Test
    void snapshotFlagsOnlyAnAuthenticationFailure() {
        check(transport -> new CheckPointSnapshotExecutor(transport, Mockito.mock(ArtefactStore.class), 1L, Duration.ofMillis(1),
                Duration.ofMillis(5)).collectSnapshot(REQUEST, "dev-1", "job-1"));
    }

    @Test
    void mdsExportFlagsOnlyAnAuthenticationFailure() {
        check(transport -> new MdsExportExecutor(transport, Mockito.mock(ArtefactStore.class), 1L, Duration.ofMillis(1),
                Duration.ofMillis(5)).collect(REQUEST, "dev-1", "job-1"));
    }

    @Test
    void theOneArgumentConstructorDefaultsToFalse() {
        assertFalse(new BackupResult.ConnectFailed("no enrolled Management Center").authentication());
        assertEquals("r", new BackupResult.ConnectFailed("r").reason());
    }
}
