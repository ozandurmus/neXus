package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FailoverKeyManagementServiceTest {
    @TempDir Path directory;

    @Test void explicitBootstrapPersistsWithOwnerOnlyPermissionsAndCannotReplace() throws Exception {
        Path key = directory.resolve("schedule.key");
        FailoverKeyManagementService.initializeKey(key);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(key));
        var first = new FailoverKeyManagementService(key);
        byte[] original = first.resolveMasterSecret().orElseThrow();
        assertThrows(IllegalStateException.class, () -> FailoverKeyManagementService.initializeKey(key));
        assertArrayEquals(original, new FailoverKeyManagementService(key).resolveMasterSecret().orElseThrow());
        var envelope = new FailoverScheduleEnvelope("synthetic-schedule", "synthetic-cluster", "CHECK_POINT",
            "synthetic-family", "CONTROLLED_FAILOVER", "synthetic-member", Instant.now(), Instant.now().plusSeconds(3600),
            15, "synthetic-requester", "synthetic-approver", "synthetic-grant", "synthetic-digest", "synthetic-nonce",
            first.currentKeyId().orElseThrow(), FailoverScheduleEnvelope.DEFAULT_ALG_VERSION);
        String signature = first.resolveCryptoService(envelope.keyId()).orElseThrow().signEnvelope(envelope);
        assertTrue(new FailoverKeyManagementService(key).resolveCryptoService(envelope.keyId()).orElseThrow()
            .verifyEnvelope(envelope, signature));
    }

    @Test void unknownIdentityVersionPermissionsAndSymlinkFailClosed() throws Exception {
        Path key = directory.resolve("schedule.key");
        FailoverKeyManagementService.initializeKey(key);
        var service = new FailoverKeyManagementService(key);
        assertTrue(service.resolveCryptoService("unknown-key").isEmpty());
        assertTrue(service.resolveCryptoService(service.currentKeyId().orElseThrow(), "unknown-version").isEmpty());
        Path link = directory.resolve("link.key");
        Files.createSymbolicLink(link, key);
        assertTrue(new FailoverKeyManagementService(link).resolveMasterSecret().isEmpty());
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        assertTrue(service.resolveMasterSecret().isEmpty());
    }

    @Test void malformedOrTruncatedKeyIsUnavailable() throws Exception {
        Path key = directory.resolve("schedule.key");
        FailoverKeyManagementService.initializeKey(key);
        Files.writeString(key, "not-a-key");
        assertTrue(new FailoverKeyManagementService(key).resolveMasterSecret().isEmpty());
    }
    @Test void anotherValidKeyMountCannotResolveTheRecordedIdentity() {
        Path firstPath = directory.resolve("first.key");
        Path otherPath = directory.resolve("other.key");
        FailoverKeyManagementService.initializeKey(firstPath);
        FailoverKeyManagementService.initializeKey(otherPath);
        String id = new FailoverKeyManagementService(firstPath).currentKeyId().orElseThrow();
        assertTrue(new FailoverKeyManagementService(otherPath).resolveCryptoService(id).isEmpty());
    }

}
