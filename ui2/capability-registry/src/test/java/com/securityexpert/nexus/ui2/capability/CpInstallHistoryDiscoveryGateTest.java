package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** V245 one-off (PO approval 2026-11-16): the fixture row and the migration carry the approved literal exactly. */
class CpInstallHistoryDiscoveryGateTest {

    static final String APPROVED = "unset BASH_ENV ENV; ls -l --time-style=+%Y-%m-%dT%H:%M:%S /opt/CPInstLog 2>&1 | tail -n 80; "
            + "find /opt/CPda /var/log/CPda -maxdepth 3 -name 'DA_Actions*' 2>/dev/null | head -5";

    @Test
    void theFixtureRowIsSignedOffReadWithTheApprovedBounds() {
        GateRow row = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream()
                .filter(r -> r.gateId().equals("cp_install_history_discovery_once")).findFirst().orElseThrow();
        assertEquals(APPROVED, row.canonicalCommandKey());
        assertEquals(SignOffState.SIGNED_OFF, row.signOffState());
        assertEquals("cp_gaia_gateway", row.platformRoleScope());
        assertFalse(row.canonicalCommandKey().startsWith("bash"));
        assertFalse(row.canonicalCommandKey().contains("bash -lc"));
    }

    @Test
    void v245SeedsTheSameLiteral() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V245__cp_install_history_discovery_once_gate.sql"));
        assertTrue(sql.contains("'" + APPROVED.replace("'", "''") + "'"));
        assertTrue(sql.contains("'read', 'SIGNED_OFF', 30, 'none'"));
        assertFalse(sql.contains("bash -lc"));
    }
}
