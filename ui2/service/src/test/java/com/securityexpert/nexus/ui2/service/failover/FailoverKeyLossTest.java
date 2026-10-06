package com.securityexpert.nexus.ui2.service.failover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FailoverKeyLossTest {
    @TempDir Path directory;

    @Test void runtimeNeverBootstrapsEvenWhenKeyAndMarkerAreBothMissing() throws Exception {
        Path key = directory.resolve("schedule.key");
        var service = new FailoverKeyManagementService(key);
        assertTrue(service.resolveMasterSecret().isEmpty());
        assertFalse(Files.exists(key));
        FailoverKeyManagementService.initializeKey(key);
        String id = service.currentKeyId().orElseThrow();
        assertTrue(service.resolveCryptoService(id).isPresent());
        Files.delete(key);
        // There is deliberately no marker dependency; a lost/mis-mounted directory is indistinguishable.
        assertTrue(service.resolveCryptoService(id).isEmpty());
        assertTrue(new FailoverKeyManagementService(key).resolveMasterSecret().isEmpty());
        assertFalse(Files.exists(key));
    }

    @Test void wrongMountAndUnconfiguredPathFailClosed() {
        assertTrue(new FailoverKeyManagementService(directory.resolve("wrong-mount/key"))
            .resolveMasterSecret().isEmpty());
        assertTrue(new FailoverKeyManagementService().resolveMasterSecret().isEmpty());
        assertTrue(new FailoverKeyManagementService(Path.of("relative.key")).resolveMasterSecret().isEmpty());
    }
}
