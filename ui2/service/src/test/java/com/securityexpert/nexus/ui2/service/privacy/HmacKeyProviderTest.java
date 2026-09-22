package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The deployed pseudonymizer key (25-secret-privacy-hmac-key.yaml) is read as-is or refused, never replaced. */
class HmacKeyProviderTest {

    @TempDir
    Path dir;

    @Test
    void readsABase64KeyFileTheWayTheOperatorProcedureWritesIt() throws Exception {
        byte[] material = new byte[32];
        for (int i = 0; i < material.length; i++) {
            material[i] = (byte) (i * 7 + 3);
        }
        Path file = dir.resolve("key");
        Files.writeString(file, Base64.getEncoder().encodeToString(material), StandardCharsets.US_ASCII);
        assertThat(HmacKeyProvider.readKeyFile(file)).isEqualTo(material);
    }

    @Test
    void theSameFileGivesTheSameKeySoPseudonymsDoNotMove() throws Exception {
        Path file = dir.resolve("key");
        Files.writeString(file, Base64.getEncoder().encodeToString(new byte[48]) + "\n", StandardCharsets.US_ASCII);
        TopologyNamePseudonymizer first = new TopologyNamePseudonymizer(HmacKeyProvider.readKeyFile(file));
        TopologyNamePseudonymizer second = new TopologyNamePseudonymizer(HmacKeyProvider.readKeyFile(file));
        assertThat(first.maskClusterName("EXAMPLE-CLUSTER-A")).isEqualTo(second.maskClusterName("EXAMPLE-CLUSTER-A"));
    }

    @Test
    void refusesAMissingFileInsteadOfFallingBackToARandomKey() {
        assertThatThrownBy(() -> HmacKeyProvider.readKeyFile(dir.resolve("absent")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unreadable");
    }

    @Test
    void refusesAShortKey() throws Exception {
        Path file = dir.resolve("key");
        Files.writeString(file, Base64.getEncoder().encodeToString(new byte[16]), StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> HmacKeyProvider.readKeyFile(file))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fewer than 32 bytes");
    }
}
