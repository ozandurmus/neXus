package com.securityexpert.nexus.ui2.worker.inventory.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.inventory.Fixtures;

/** AC-2: {@code cphaprob stat} -- role and cluster mode, local row only. */
class CheckPointHaStateParserTest {

    @Test
    void readsTheLocalRowsRoleAndTheClusterMode() {
        CheckPointHaStateParser.HaState state = CheckPointHaStateParser.parse(Fixtures.read("cp/cphaprob_stat.txt"));

        assertEquals("ACTIVE", state.role());
        assertEquals("High Availability", state.clusterMode().orElseThrow());
    }

    @Test
    void standaloneGatewayReportsStandaloneWithNoClusterMode() {
        CheckPointHaStateParser.HaState state =
                CheckPointHaStateParser.parse("HA is not applicable, not enabled on this machine\n");

        assertEquals("STANDALONE", state.role());
        assertTrue(state.clusterMode().isEmpty());
    }
}
