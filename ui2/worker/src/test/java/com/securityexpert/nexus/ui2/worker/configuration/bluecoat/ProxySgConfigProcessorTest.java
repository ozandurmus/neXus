package com.securityexpert.nexus.ui2.worker.configuration.bluecoat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProxySgConfigProcessorTest {
    private static final String CONFIG = """
            !- connected to device
            SG-ALPHA#(config)show configuration
            !- Configuration date 2026-09-26 12:00:00
            !- Generated 2026-09-26 12:00:00
            !- BEGIN network
            interface 192.0.2.1
            password hidden-one
            passwd hidden-two
            secret hidden-three
            key hidden-four
            community hidden-five
            private-key hidden-six
            encrypted-password hidden-seven
            hashed-password hidden-eight
            inline certificate invented
            -----BEGIN CERTIFICATE-----
            hidden-nine
            end-random-inline
            inline keyring invented
            hidden-ten
            end-random-inline
            !- END network
            !- BEGIN policy
            allow 192.0.2.0/24
            exit
            !- END policy
            """;

    @Test
    void indexesBlocksAndWithholdsSecretsAndInlineBodies() {
        var p = ProxySgConfigProcessor.process(CONFIG);
        assertEquals("single", p.index().getFirst().context());
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Network")));
        assertTrue(p.index().stream().anyMatch(e -> e.section().equals("Policy")));
        assertEquals(15, p.withheldLineCount());
        for (String value : new String[] {"hidden-one", "hidden-two", "hidden-three", "hidden-four", "hidden-five",
                "hidden-six", "hidden-seven", "hidden-eight", "hidden-nine", "hidden-ten", "invented", "CERTIFICATE"}) {
            assertFalse(p.sanitizedText().contains(value), value);
        }
        assertTrue(p.sanitizedText().contains("interface 192.0.2.1"));
        assertFalse(p.sanitizedText().contains("SG-ALPHA"));
        assertEquals(p.canonicalHash(), ProxySgConfigProcessor.process(CONFIG.replace("12:00:00", "13:00:00")).canonicalHash());
    }

    @Test
    void usesTopLevelKeywordsWithoutMarkers() {
        var p = ProxySgConfigProcessor.process("interface 192.0.2.1\n address 192.0.2.2\npolicy allow\n");
        assertEquals(2, p.index().size());
        assertEquals("Interface", p.index().getFirst().section());
        assertEquals("Policy", p.index().get(1).section());
    }
}
