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
    @Test
    void shortAndNumericIdentitiesNeverBecomeTextReplacements() {
        for (String name : java.util.List.of("0", "1", "2", "123", "2026", "ab")) {
            pseudonymizer.maskVirtualSystem(name, null);
            pseudonymizer.maskDeviceName(name, null);
            pseudonymizer.maskDomainName(name);
            pseudonymizer.maskClusterName(name);
        }
        String facts = "2026-10-02T03:50:21Z · 122 devices · build-20261002-123 · 02/10/2026 GMT+3 · 2 ab";
        assertThat(pseudonymizer.maskText(facts)).isEqualTo(facts);
        assertThat(pseudonymizer.maskVirtualSystem("2", null)).startsWith("VS-");
    }

    @Test
    void masksWholeTokensWithoutTouchingLongerNamesOrBuildIds() {
        String masked = pseudonymizer.maskDeviceName("synthetic-edge", null);
        String text = "synthetic-edge-long build-synthetic-edge synthetic-edge.example.invalid Xsynthetic-edge";
        assertThat(pseudonymizer.maskText(text)).isEqualTo(text);
        assertThat(pseudonymizer.maskText("(synthetic-edge), synthetic-edge: failed"))
                .isEqualTo("(" + masked + "), " + masked + ": failed");
        assertThat(pseudonymizer.maskText(masked)).isEqualTo(masked);
    }


    @Test
    void aValueThatIsNotAHostnameIsAGapAndNeverAFirewallName() {
        // Built at runtime: synthetic documentation-range addresses and placeholder spellings only.
        String v4 = String.join(".", "198", "51", "100", "7");
        String v6 = String.join(":", "2001", "db8", "", "1");
        for (String value : new String[] {v4, v4 + "/24", v4 + ":22", v6, "[" + v6 + "]", "UNKNOWN", "Unknown hostname",
                "n/a", "-", "None", "null", "(none)"}) {
            assertThat(pseudonymizer.maskDeviceName(value, null)).as(value).isEqualTo(TopologyNamePseudonymizer.GAP);
            assertThat(pseudonymizer.maskDeviceName(value, "CLUSTER-ONE")).as(value + " with cluster")
                    .isEqualTo(TopologyNamePseudonymizer.GAP);
            assertThat(pseudonymizer.maskManagedDeviceName(value)).as(value + " managed")
                    .isEqualTo(TopologyNamePseudonymizer.GAP);
            assertThat(TopologyNamePseudonymizer.isNotAHostname(value)).as(value).isTrue();
        }
        assertThat(pseudonymizer.maskDeviceName("", null)).isEmpty();
        assertThat(pseudonymizer.maskDeviceName(null, null)).isNull();
        assertThat(TopologyNamePseudonymizer.isNotAHostname("  ")).isTrue();
    }

    @Test
    void anyRealHostnameShapeIsStillMasked() {
        for (String host : new String[] {"fw-example-01", "ARKTEST-FW", "mgmt1.example.test", "198-51-100-7", "node7", "na"}) {
            assertThat(TopologyNamePseudonymizer.isNotAHostname(host)).as(host).isFalse();
            assertThat(pseudonymizer.maskDeviceName(host, null)).as(host).startsWith("FW-");
        }
    }

    @Test
    void aNameEqualToTheDevicesOwnManagementAddressIsAGap() {
        String address = String.join(".", "203", "0", "113", "9");
        assertThat(pseudonymizer.maskDeviceName(address, null, "dev-1", address)).isEqualTo(TopologyNamePseudonymizer.GAP);
        // A dotted name that is not the address keeps its pseudonym, and no address means no comparison.
        assertThat(pseudonymizer.maskDeviceName("gw.example.test", null, "dev-1", address)).startsWith("FW-");
        assertThat(pseudonymizer.maskDeviceName("gw.example.test", null, "dev-1", null)).startsWith("FW-");
        // A name equal to an address that is not a literal (a DNS name used for management) is a gap as well.
        assertThat(pseudonymizer.maskDeviceName("Mgmt.Example.Test", null, "dev-2", "mgmt.example.test"))
                .isEqualTo(TopologyNamePseudonymizer.GAP);
    }

    @Test
    void unknownHostTokenIsStableKeyedDistinctAndIdempotent() {
        String a = pseudonymizer.maskUnknownHost("device-a");
        String b = pseudonymizer.maskUnknownHost("device-b");
        assertThat(a).matches("UNKNOWN-HOST-[0-9a-f]{6}").isEqualTo(pseudonymizer.maskUnknownHost("device-a"));
        assertThat(a).isNotEqualTo(b).doesNotContain("device-a");
        assertThat(a).doesNotStartWith("FW-");
        // Another key gives another token; the token passes through a second masking unchanged.
        TopologyNamePseudonymizer other = new TopologyNamePseudonymizer(
                "abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.UTF_8));
        assertThat(other.maskUnknownHost("device-a")).isNotEqualTo(a);
        assertThat(pseudonymizer.maskDeviceName(a, null)).isEqualTo(a);
        assertThat(TopologyNamePseudonymizer.isGap(a)).isTrue();
        assertThat(TopologyNamePseudonymizer.isGap("FW-ALPHA-01")).isFalse();
        // The gap is never registered for free-text replacement.
        assertThat(pseudonymizer.maskText("host UNKNOWN is down")).isEqualTo("host UNKNOWN is down");
    }
}
