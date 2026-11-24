package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the masking behaviour of {@link ReportProjectionMasker}: names, free text, addresses, clusters, devices and
 * serials through the same pseudonymizer and subnet-preserving masker the JSON responses use. Synthetic values only.
 */
class ReportProjectionMaskerTest {

    private static final String RAW_NAME = "FW-TANGO-04";
    private static final String RAW_CLUSTER = "FW-CKP-EXAMPLEBANKMOBAPP-AA-CLS";
    private static final String RAW_MEMBER = "FW-CKP-EXAMPLEBANKMOBAPP-AA-1";
    private static final String RAW_IP = "198.51.100.17";

    private ReportProjectionMasker masker;

    @BeforeEach
    void setUp() {
        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        masker = new ReportProjectionMasker(new TopologyNamePseudonymizer(key), new SubnetPreservingIpMasker(key));
    }

    @Test
    void isAMaskingProjection() {
        assertThat(masker.masking()).isTrue();
    }

    @Test
    void constructorRequiresANamesPseudonymizer() {
        assertThatThrownBy(() -> new ReportProjectionMasker(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullBlankAndEmptyNamesPassThroughUnchanged() {
        assertThat(masker.name(null)).isNull();
        assertThat(masker.name("")).isEmpty();
        assertThat(masker.name("   ")).isEqualTo("   ");
    }

    @Test
    void deviceNameIsPseudonymizedAndDeterministic() {
        String masked = masker.name(RAW_NAME);
        assertThat(masked).startsWith("FW-").doesNotContain("TANGO");
        assertThat(masker.name(RAW_NAME)).isEqualTo(masked);
    }

    @Test
    void addressShapedNameBecomesTheGapTokenNotTheAddress() {
        assertThat(masker.name(RAW_IP)).isEqualTo("Unknown");
    }

    @Test
    void explicitUnknownNameSurvives() {
        assertThat(masker.name("Unknown")).isEqualTo("Unknown");
    }

    @Test
    void nullAndBlankTextPassThroughUnchanged() {
        assertThat(masker.text(null)).isNull();
        assertThat(masker.text("")).isEmpty();
        assertThat(masker.text("  ")).isEqualTo("  ");
    }

    @Test
    void embeddedAddressInFreeTextIsMasked() {
        String masked = masker.text("peer " + RAW_IP + " went down");
        assertThat(masked).doesNotContain(RAW_IP).startsWith("peer ").endsWith(" went down");
    }

    @Test
    void textRewritesAMemberNameAlreadySeenByName() {
        String pseudonym = masker.name(RAW_NAME);
        String masked = masker.text("failover from " + RAW_NAME + " to standby");
        assertThat(masked).doesNotContain(RAW_NAME).contains(pseudonym);
    }

    @Test
    void addressIsMaskedAndKeepsItsCidrSuffix() {
        String host = masker.address(RAW_IP);
        assertThat(host).isNotEqualTo(RAW_IP).doesNotContain(RAW_IP);

        String cidr = masker.address("198.51.100.0/24");
        assertThat(cidr).doesNotContain("198.51.100.0").endsWith("/24");
    }

    @Test
    void nullAndBlankAddressPassThroughUnchanged() {
        assertThat(masker.address(null)).isNull();
        assertThat(masker.address("")).isEmpty();
        assertThat(masker.address("  ")).isEqualTo("  ");
    }

    @Test
    void addressIsWithheldWhenNoAddressMaskerIsConfigured() {
        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        ReportProjectionMasker noAddresses = new ReportProjectionMasker(new TopologyNamePseudonymizer(key), null);
        assertThat(noAddresses.address(RAW_IP)).isEqualTo("MASKED");
    }

    @Test
    void clusterNameIsPseudonymizedWithClusterPrefix() {
        String cluster = masker.cluster(RAW_CLUSTER);
        assertThat(cluster).startsWith("CLS-").doesNotContain("EXAMPLEBANK");
    }

    @Test
    void clusterMemberKeepsItsClusterBaseAndOrdinal() {
        String cluster = masker.cluster(RAW_CLUSTER);
        String member = masker.device(RAW_MEMBER, RAW_CLUSTER, "member-1");
        assertThat(member).startsWith("FW-" + cluster.substring(4) + "-M").doesNotContain("EXAMPLEBANK");
    }

    @Test
    void serialIsKeyedTokenNotTheRawValue() {
        String raw = "SYNTH-SN-0001";
        String token = masker.serial(raw);
        assertThat(token).startsWith("SN-").doesNotContain(raw);
        assertThat(masker.serial(raw)).isEqualTo(token);
    }
}
