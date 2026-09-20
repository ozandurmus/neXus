package com.securityexpert.nexus.ui2.service.failover;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleEnvelope;

/**
 * CF-P0.5. Losing the signing key and being tampered with are different events, and the
 * service must not turn the first into the second. Minting a replacement key after loss
 * makes every schedule sealed under the old key fail verification, so an unmounted volume
 * or a cleaned directory becomes indistinguishable from an attacker.
 */
class FailoverKeyLossTest {

    @TempDir
    Path keyDirectory;

    private Path keyFile() {
        return keyDirectory.resolve("failover_master.key");
    }

    @Test
    @DisplayName("first provisioning mints a key and records that this location was provisioned")
    void firstProvisioningMintsAKey() {
        FailoverKeyManagementService service = new FailoverKeyManagementService(keyFile());

        assertTrue(service.resolveCryptoService(FailoverScheduleEnvelope.DEFAULT_KEY_ID).isPresent(),
            "a genuinely first start has no key to lose and may provision one");
        assertTrue(Files.exists(keyFile()));
        assertTrue(Files.exists(keyDirectory.resolve("failover_master.key.provisioned")));
    }

    @Test
    @DisplayName("a provisioned location whose key has gone missing fails closed instead of minting a replacement")
    void keyLossFailsClosed() throws IOException {
        new FailoverKeyManagementService(keyFile())
            .resolveCryptoService(FailoverScheduleEnvelope.DEFAULT_KEY_ID);
        Files.delete(keyFile());

        // A fresh instance, as a restarted pod would be, on a volume that lost the key.
        FailoverKeyManagementService afterLoss = new FailoverKeyManagementService(keyFile());

        assertFalse(afterLoss.resolveCryptoService(FailoverScheduleEnvelope.DEFAULT_KEY_ID).isPresent(),
            "key loss must surface as an unavailable key, not as a new key that makes every schedule look tampered");
        assertFalse(Files.exists(keyFile()), "no replacement key may be written after loss");
    }

    @Test
    @DisplayName("a key that is still present keeps resolving across restarts")
    void survivingKeyStillResolves() {
        new FailoverKeyManagementService(keyFile())
            .resolveCryptoService(FailoverScheduleEnvelope.DEFAULT_KEY_ID);

        FailoverKeyManagementService restarted = new FailoverKeyManagementService(keyFile());

        assertTrue(restarted.resolveCryptoService(FailoverScheduleEnvelope.DEFAULT_KEY_ID).isPresent());
    }
}
