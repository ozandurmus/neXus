package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleEnvelope;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.ScheduleCryptographicService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CF-P0.5: proves the durable-key resolution FailoverScheduleService now depends on instead of a
 * per-process ephemeral {@code SecureRandom} key.
 */
class FailoverKeyManagementServiceTest {

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(FailoverKeyManagementService.SYSPROP_MASTER_SECRET);
    }

    @Test
    @DisplayName("A key persisted to the local storage file is read back identically by a fresh instance (process restart)")
    void keyPersistsAcrossServiceRestarts(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve(".nexus_failover_master.key");
        assertFalse(Files.exists(keyFile), "precondition: no key file exists yet");

        // First "process": no key file yet, one is generated and persisted.
        FailoverKeyManagementService firstProcess = new FailoverKeyManagementService(keyFile);
        Optional<byte[]> firstSecret = firstProcess.resolveMasterSecret();
        assertTrue(firstSecret.isPresent());
        assertTrue(Files.exists(keyFile), "the generated key must be persisted to the local storage file");

        // A schedule "signed" before the restart.
        ScheduleCryptographicService firstCrypto = firstProcess.resolveCryptoService("k1").orElseThrow();
        FailoverScheduleEnvelope envelope = sampleEnvelope();
        String signature = firstCrypto.signEnvelope(envelope);

        // Second "process": a brand-new instance pointed at the same file (simulating a restart).
        FailoverKeyManagementService secondProcess = new FailoverKeyManagementService(keyFile);
        Optional<byte[]> secondSecret = secondProcess.resolveMasterSecret();
        assertTrue(secondSecret.isPresent());
        assertArrayEquals(firstSecret.get(), secondSecret.get(),
            "a process restart must read back the exact same durable key, never mint a new one");

        // CF-P0.5's actual failure mode: a signature produced before the restart must still verify
        // after it -- with the Stage 1 ephemeral-key defect, this would false-positive as tampered.
        ScheduleCryptographicService secondCrypto = secondProcess.resolveCryptoService("k1").orElseThrow();
        assertTrue(secondCrypto.verifyEnvelope(envelope, signature),
            "a signature produced by the pre-restart key must still verify after a restart");
    }

    @Test
    @DisplayName("An unknown keyId resolves to empty rather than fabricating a key")
    void unknownKeyIdResolvesToEmpty(@TempDir Path tempDir) {
        FailoverKeyManagementService service = new FailoverKeyManagementService(tempDir.resolve(".key"));
        assertTrue(service.resolveCryptoService("k2-does-not-exist").isEmpty());
    }

    @Test
    @DisplayName("A system property master secret takes precedence over the local storage file and is deterministic")
    void systemPropertyOverridesLocalFile(@TempDir Path tempDir) {
        System.setProperty(FailoverKeyManagementService.SYSPROP_MASTER_SECRET, "system-property-master-secret");

        FailoverKeyManagementService serviceA = new FailoverKeyManagementService(tempDir.resolve(".key-a"));
        FailoverKeyManagementService serviceB = new FailoverKeyManagementService(tempDir.resolve(".key-b"));

        // Two instances pointed at two different (both nonexistent) files still resolve the same
        // key, because the system property is consulted first and never touches either file.
        assertFalse(Files.exists(tempDir.resolve(".key-a")));
        assertFalse(Files.exists(tempDir.resolve(".key-b")));
        assertArrayEquals(serviceA.resolveMasterSecret().orElseThrow(), serviceB.resolveMasterSecret().orElseThrow());
    }

    @Test
    @DisplayName("A fixed test secret never touches the filesystem")
    void fixedTestSecretNeverTouchesDisk(@TempDir Path tempDir) {
        Path keyFile = tempDir.resolve(".unused.key");
        byte[] fixedSecret = "fixed-secret-for-unit-tests-32b!".getBytes();
        FailoverKeyManagementService service = FailoverKeyManagementService.withFixedSecretForTesting(fixedSecret);

        assertTrue(service.resolveCryptoService("k1").isPresent());
        assertFalse(Files.exists(keyFile));
    }

    private static FailoverScheduleEnvelope sampleEnvelope() {
        Instant now = Instant.now();
        return new FailoverScheduleEnvelope(
            "sched-001", "cls-cp-01", "CHECK_POINT", "cmd-cp-clusterxl", "CONTROLLED_FAILOVER",
            "dev-cp-1", now, now.plusSeconds(3600), 15, "operator-alice", "approver-bob",
            "grant-777", "digest-abc-123", "nonce-xyz-456", "k1", "HMAC_SHA256_V1"
        );
    }
}
