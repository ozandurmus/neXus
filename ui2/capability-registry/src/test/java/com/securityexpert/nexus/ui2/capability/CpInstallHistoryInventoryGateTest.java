package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** V246 (PO approval 2026-11-17): the fixture row and the migration carry the approved literal exactly. */
class CpInstallHistoryInventoryGateTest {

    static final String APPROVED = "unset BASH_ENV ENV; cd /opt/CPInstLog 2>/dev/null || exit 0; for f in $(ls -1tr install_fw1_R*.elg install_SecurePlatform_HOTFIX_*JUMBO*.log 2>/dev/null | tail -n 20); do echo \"$(date -r \"$f\" +%Y-%m-%dT%H:%M:%S) $f $(grep -a -o -m1 -E 'Bundle_T[0-9]+' \"$f\" | head -n1)\"; done";

    @Test
    void theFixtureRowIsSignedOffReadWithTheApprovedBounds() {
        GateRow row = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream()
                .filter(r -> r.gateId().equals("cp_install_history_inventory")).findFirst().orElseThrow();
        assertEquals(APPROVED, row.canonicalCommandKey());
        assertEquals(SignOffState.SIGNED_OFF, row.signOffState());
        assertEquals("cp_gaia_gateway", row.platformRoleScope());
        assertFalse(row.canonicalCommandKey().startsWith("bash"));
        assertFalse(row.canonicalCommandKey().contains("bash -lc"));
    }

    @Test
    void v246SeedsTheSameLiteral() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V246__cp_install_history_inventory.sql"));
        assertTrue(sql.contains("'" + APPROVED.replace("'", "''") + "'"));
        assertTrue(sql.contains("'read', 'SIGNED_OFF', 30, 'none', 'each inventory run'"));
        assertFalse(sql.contains("bash -lc"));
    }
}
