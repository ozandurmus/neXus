package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;

/**
 * AC-4: {@code cphaprob stat} -- the two measured mode strings, the local
 * row's role, and (VSLS) the "Virtual Devices Status on each Cluster
 * Member" table parsed into a per-VSID local role; an interleaved syslog/
 * kernel line does not disturb either read (PR-5).
 */
class CheckPointHaStateParserTest {

    @Test
    void readsTheLocalRowsRoleAndTheHighAvailabilityClusterMode() {
        CheckPointHaStateParser.HaState state = CheckPointHaStateParser.parse(Fixtures.read("cp/cphaprob_stat.txt"));

        assertEquals("ACTIVE", state.role());
        assertEquals("High Availability", state.clusterMode().orElseThrow());
        assertEquals(Map.of(), state.perVsidLocalRole(), "no VSLS table on a plain HA cluster member");
    }

    @Test
    void vslsFixtureYieldsThePerVsidLocalRoleFromTheLocalMarkedColumn() {
        CheckPointHaStateParser.HaState state =
                CheckPointHaStateParser.parse(Fixtures.read("cp/cphaprob_stat_vsls.txt"));

        assertEquals("ACTIVE", state.role());
        assertEquals("Virtual System Load Sharing", state.clusterMode().orElseThrow());
        assertEquals(Map.of("2", "ACTIVE", "5", "STANDBY"), state.perVsidLocalRole(),
                "the '[local]' marker on the gw-a column selects that column's state per VSID row");
    }

    @Test
    void standaloneGatewayReportsStandaloneWithNoClusterModeOrVsidRoles() {
        CheckPointHaStateParser.HaState state =
                CheckPointHaStateParser.parse("HA is not applicable, not enabled on this machine\n");

        assertEquals("STANDALONE", state.role());
        assertTrue(state.clusterMode().isEmpty());
        assertEquals(Map.of(), state.perVsidLocalRole());
    }
}
