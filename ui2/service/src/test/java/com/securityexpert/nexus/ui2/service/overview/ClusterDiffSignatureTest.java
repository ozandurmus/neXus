package com.securityexpert.nexus.ui2.service.overview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ClusterDiffSignatureTest {

    @Test
    void digitBearingTokensAreMaskedAndNoValueIsKept() {
        assertThat(ConfigurationProjection.signature("Interfaces", "eth1-03 · Auto Negotiation"))
                .isEqualTo("Interfaces > <x> > Auto Negotiation");
        assertThat(ConfigurationProjection.signature("Routing", "Static route 10.0.0.0/8 · Next hop"))
                .isEqualTo("Routing > Static route <x> > Next hop");
    }

    @Test
    void principalNamesAreNeverKept() {
        assertThat(ConfigurationProjection.signature("Users", "operator · Shell · Role"))
                .isEqualTo("Users > <item> > <item> > Role");
        assertThat(ConfigurationProjection.signature("Users", "operator · Shell"))
                .isEqualTo("Users > <item> > Shell");
        assertThat(ConfigurationProjection.signature("Users", "operator")).isEqualTo("Users > <item>");
    }

    @Test
    void signaturesJsonEscapesAndSorts() {
        assertThat(ClusterDiffTask.signaturesJson(Map.of("b > \"q\"", 2, "a", 1)))
                .isEqualTo("{\"a\":1,\"b > \\\"q\\\"\":2}");
    }
}
