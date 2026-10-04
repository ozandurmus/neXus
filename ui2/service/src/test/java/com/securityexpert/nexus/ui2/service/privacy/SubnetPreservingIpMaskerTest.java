package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubnetPreservingIpMaskerTest {

    private SubnetPreservingIpMasker masker;

    @BeforeEach
    void setUp() {
        byte[] testKey = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        masker = new SubnetPreservingIpMasker(testKey);
    }

    @Test
    void redactsNonDefaultAddressesWhosePrefixLeavesNoSyntheticNetworkBits() {
        assertThat(masker.mask("192.0.2.17/0")).isEqualTo("[REDACTED_IP]");
        assertThat(masker.maskText("destination 198.51.100.9/0"))
                .isEqualTo("destination [REDACTED_IP]");
    }

    @Test
    void confinesAllRepresentablePrefixesToReservedSpace() {
        for (int prefix = 4; prefix <= 32; prefix++) {
            for (int host = 0; host <= 255; host++) {
                String masked = masker.mask("192.0.2." + host + "/" + prefix);
                assertThat(masked).matches("(?:24[0-9]|25[0-5])\\..*/" + prefix);
                assertThat(masked).doesNotStartWith("10.").doesNotStartWith("172.").doesNotStartWith("192.");
            }
        }
        for (int prefix = 0; prefix < 4; prefix++) {
            assertThat(masker.mask("198.51.100.9/" + prefix)).isEqualTo("[REDACTED_IP]");
        }
    }

    @Test
    void preservesExactlyTheSpecialAddressClasses() {
        for (String address : new String[]{"0.0.0.0", "255.255.255.255", "127.0.0.0", "127.255.255.255",
                "169.254.0.0", "169.254.255.255", "224.0.0.0", "239.255.255.255"}) {
            assertThat(masker.mask(address)).isEqualTo(address);
            assertThat(masker.mask(address + "/24")).isEqualTo(address + "/24");
            assertThat(masker.maskText("route " + address)).isEqualTo("route " + address);
        }
        assertThat(masker.mask("999.0.0.1")).isEqualTo("[REDACTED_IP]");
        assertThat(masker.mask("127.0.0.1/33")).isEqualTo("[REDACTED_IP]");
    }

    @Test
    void preservesSubnetAndHostOffsetsForVipAndMembers() {
        String vip = masker.mask("192.0.2.1");
        String m1 = masker.mask("192.0.2.2");
        String m2 = masker.mask("192.0.2.3");

        assertThat(vip).isNotNull().matches("(?:24[0-9]|25[0-5])\\..*");
        assertThat(m1).isNotNull().matches("(?:24[0-9]|25[0-5])\\..*");
        assertThat(m2).isNotNull().matches("(?:24[0-9]|25[0-5])\\..*");

        // Subnet prefixes must match
        int lastDotVip = vip.lastIndexOf('.');
        int lastDotM1 = m1.lastIndexOf('.');
        int lastDotM2 = m2.lastIndexOf('.');

        String prefixVip = vip.substring(0, lastDotVip);
        String prefixM1 = m1.substring(0, lastDotM1);
        String prefixM2 = m2.substring(0, lastDotM2);

        assertThat(prefixM1).isEqualTo(prefixVip);
        assertThat(prefixM2).isEqualTo(prefixVip);

        // Host offsets must match original (.1, .2, .3)
        assertThat(vip.substring(lastDotVip + 1)).isEqualTo("1");
        assertThat(m1.substring(lastDotM1 + 1)).isEqualTo("2");
        assertThat(m2.substring(lastDotM2 + 1)).isEqualTo("3");
    }

    @Test
    void mapsDifferentSubnetsToDifferentSyntheticSubnets() {
        String ip1 = masker.mask("192.0.2.1");
        String ip2 = masker.mask("198.51.100.1");

        String prefix1 = ip1.substring(0, ip1.lastIndexOf('.'));
        String prefix2 = ip2.substring(0, ip2.lastIndexOf('.'));

        assertThat(prefix1).isNotEqualTo(prefix2);
    }

    @Test
    void preservesCidrSuffix() {
        String masked = masker.mask("192.0.2.1/24");
        assertThat(masked).endsWith("/24");
        assertThat(masked).matches("(?:24[0-9]|25[0-5])\\..*");
    }

    @Test
    void preservesDefaultRouteAndLoopback() {
        assertThat(masker.mask("0.0.0.0/0")).isEqualTo("0.0.0.0/0");
        assertThat(masker.mask("0.0.0.0")).isEqualTo("0.0.0.0");
        assertThat(masker.mask("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(masker.mask("255.255.255.255")).isEqualTo("255.255.255.255");
    }

    @Test
    void masksEmbeddedIpsInFreeText() {
        String log = "connect_failed to 192.0.2.2: timed out after 10000ms";
        String maskedLog = masker.maskText(log);

        assertThat(maskedLog).doesNotContain("192.0.2.2");
        assertThat(maskedLog).contains("connect_failed to " + masker.mask("192.0.2.2"));
        assertThat(maskedLog).contains(": timed out after 10000ms");
    }

    @Test
    void deterministicAcrossMultipleCalls() {
        String first = masker.mask("203.0.113.204");
        String second = masker.mask("203.0.113.204");
        assertThat(second).isEqualTo(first);
        assertThat(new SubnetPreservingIpMasker("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8))
                .mask("203.0.113.204")).isEqualTo(first);
        assertThat(new SubnetPreservingIpMasker(new byte[32]).mask("203.0.113.204")).isNotEqualTo(first);
    }

    @Test
    void inheritsContainingPrefixForBareVipInSlash23() {
        // Register /23 network
        masker.registerSubnet("192.0.2.12/23");
        String m1 = masker.mask("192.0.2.12/23");
        String m2 = masker.mask("192.0.2.13/23");

        // VIP without CIDR prefix
        String vip = masker.mask("192.0.2.11");

        assertThat(m1).endsWith("/23");
        assertThat(m2).endsWith("/23");
        assertThat(vip).doesNotContain("/");

        String m1Ip = m1.substring(0, m1.indexOf('/'));
        String[] m1Parts = m1Ip.split("\\.");
        String[] vipParts = vip.split("\\.");

        // Octets 1 and 2 match
        assertThat(vipParts[0]).isEqualTo(m1Parts[0]);
        assertThat(vipParts[1]).isEqualTo(m1Parts[1]);

        // For a /23 network, bit 0 of octet 3 is the host bit, so (octet3 & ~1) must match
        int m1O3 = Integer.parseInt(m1Parts[2]);
        int vipO3 = Integer.parseInt(vipParts[2]);
        assertThat(vipO3 & ~1).isEqualTo(m1O3 & ~1);

        // Host offsets match
        assertThat(vipParts[3]).isEqualTo("11");
        assertThat(m1Parts[3]).isEqualTo("12");
    }
}
