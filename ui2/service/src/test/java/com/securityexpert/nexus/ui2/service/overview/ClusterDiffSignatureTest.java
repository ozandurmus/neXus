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
    void principalSectionsKeepNoComponentAtAll() {
        // the Users section carries the account name in any position, including the last
        assertThat(ConfigurationProjection.signature("Users", "Ssh Key · operator")).isEqualTo("Users > <principal>");
        assertThat(ConfigurationProjection.signature("AAA", "operator · Radius Servers")).isEqualTo("AAA > <principal>");
    }

    @Test
    void objectNamesWithSeparatorsAreMasked() {
        assertThat(ConfigurationProjection.signature("High Availability", "Link Group · Failure Condition · Edge-LinkGroup"))
                .isEqualTo("High Availability > Link Group > Failure Condition > <x>");
        assertThat(ConfigurationProjection.signature("Other Gaia Configuration", "Netflow:rule"))
                .isEqualTo("Other Gaia Configuration > <x>");
    }

    @Test
    void signaturesJsonEscapesAndSorts() {
        assertThat(ClusterDiffTask.signaturesJson(Map.of("b > \"q\"", 2, "a", 1)))
                .isEqualTo("{\"a\":1,\"b > \\\"q\\\"\":2}");
    }
}
