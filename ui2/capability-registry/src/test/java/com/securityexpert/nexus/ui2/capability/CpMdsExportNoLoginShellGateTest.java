package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Check Point remote shell normalization, phase 1 (docs/design/CP_REMOTE_SHELL_NORMALIZATION_CONTRACT_2026_11_11.md
 * section 6.1). The PO approved exactly these three literals on 2026-11-11; any other spelling is a new command and
 * needs its own approval. Proven against the gate fixture and V206.
 */
class CpMdsExportNoLoginShellGateTest {

    private static final String PREFIX = "unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ";

    static final Map<String, String> APPROVED = Map.of(
            "mds_backup_start", PREFIX + "cd /var/log && setsid nohup bash --noprofile --norc -c \"$CPMDIR/scripts/mds_backup -b -l -d %1$s"
                    + " > %1$s/mds_backup.log 2>&1; echo \\$? > %1$s/mds_backup.rc\" </dev/null >/dev/null 2>&1 &",
            "mds_mdsstat", PREFIX + "mdsstat > %s/mdsstat.txt 2>&1",
            "mds_cplic_print", PREFIX + "cplic print -x > %s/cplic.txt 2>&1");

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(CpMdsExportNoLoginShellGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    @Test
    void theThreeFixtureRowsCarryTheApprovedLiteralsExactly() {
        List<GateRow> rows = fixture();
        APPROVED.forEach((gateId, literal) -> {
            GateRow row = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow();
            assertEquals(literal, row.canonicalCommandKey(), gateId);
            assertEquals("cp_multi_domain_server", row.platformRoleScope(), gateId);
            assertEquals(SignOffState.SIGNED_OFF, row.signOffState(), gateId);
        });
    }

    @Test
    void noMdsExportRowStartsALoginShell() {
        for (GateRow row : fixture()) {
            if (!row.gateId().startsWith("mds_")) continue;
            String key = row.canonicalCommandKey();
            assertFalse(key.contains("bash -lc") || key.contains("bash -l ") || key.contains("--login"), row.gateId() + ": " + key);
        }
    }

    @Test
    void v206RewritesExactlyTheseThreeRowsToTheSameLiterals() throws Exception {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V206__cp_mds_export_no_login_shell.sql"));
        APPROVED.forEach((gateId, literal) -> {
            assertTrue(sql.contains("'" + literal.replace("'", "''") + "'"), gateId);
            assertTrue(sql.contains("WHERE gate_id = '" + gateId + "';"), gateId);
        });
        assertEquals(3, sql.split("UPDATE gate_registry", -1).length - 1);
        assertFalse(sql.contains("bash -lc"));
    }
}
