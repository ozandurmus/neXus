package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TopologyNamePseudonymizerTest {

    private TopologyNamePseudonymizer pseudonymizer;

    @BeforeEach
    void setUp() {
        byte[] testKey = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        pseudonymizer = new TopologyNamePseudonymizer(testKey);
    }

    @Test
    void clusterAndMembersShareSyntheticBase() {
        String clusterName = "FW-CKP-EXAMPLEBANKMOBAPP-AA-CLS";
        String member1 = "FW-CKP-EXAMPLEBANKMOBAPP-AA-1";
        String member2 = "FW-CKP-EXAMPLEBANKMOBAPP-AA-2";

        String maskedCluster = pseudonymizer.maskClusterName(clusterName);
        String maskedM1 = pseudonymizer.maskDeviceName(member1, clusterName);
        String maskedM2 = pseudonymizer.maskDeviceName(member2, clusterName);

        assertThat(maskedCluster).startsWith("CLS-");
        assertThat(maskedM1).startsWith("FW-");
        assertThat(maskedM2).startsWith("FW-");

        String clusterBase = maskedCluster.substring(4); // e.g. ALPHA-01
        assertThat(maskedM1).startsWith("FW-" + clusterBase + "-M");
        assertThat(maskedM2).startsWith("FW-" + clusterBase + "-M");

        // Ordinals should distinguish member 1 and member 2
        assertThat(maskedM1).isNotEqualTo(maskedM2);
        assertThat(maskedM1).endsWith("-M1");
        assertThat(maskedM2).endsWith("-M2");
    }

    @Test
    void standaloneDeviceIsMasked() {
        String standalone = pseudonymizer.maskDeviceName("ARKTEST-FW", null);
        assertThat(standalone).startsWith("FW-");
        assertThat(standalone).doesNotContain("ARKTEST");
    }

    @Test
    void managementAddressIsUnknownAndNeverRegisteredForTextReplacement() {
        assertThat(pseudonymizer.maskDeviceName("192.0.2.10", null)).isEqualTo("Unknown");
        assertThat(pseudonymizer.maskText("connection to 192.0.2.10 failed"))
                .isEqualTo("connection to 192.0.2.10 failed");
    }

    @Test
    void explicitUnknownSurvivesMasking() {
        assertThat(pseudonymizer.maskDeviceName("Unknown", null)).isEqualTo("Unknown");
    }

    @Test
    void virtualSystemsShareParentBase() {
        String clusterName = "FW-CKP-EXAMPLEBANKMOBAPP-AA-CLS";
        String maskedCluster = pseudonymizer.maskClusterName(clusterName);
        String base = maskedCluster.substring(4);

        String maskedVs = pseudonymizer.maskVirtualSystem("VS-EXAMPLEBANK-APP", clusterName);
        assertThat(maskedVs).startsWith("VS-" + base + "-");
        assertThat(maskedVs).doesNotContain("EXAMPLEBANK");
    }

    @Test
    void deterministicAcrossCalls() {
        String call1 = pseudonymizer.maskClusterName("DCITSERVICESCLS");
        String call2 = pseudonymizer.maskClusterName("DCITSERVICESCLS");
        assertThat(call2).isEqualTo(call1);
    }
}
