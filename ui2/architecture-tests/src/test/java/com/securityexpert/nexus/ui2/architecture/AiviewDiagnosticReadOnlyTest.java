package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

class AiviewDiagnosticReadOnlyTest {
    @Test
    void replayViewerHasOnlyReadExecutionAuthorityAndNoNonReadGateCanResolve() {
        var actions = new ActionRegistry();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                actions.find(ActionRegistry.FMG_DIAGNOSTIC_RUN).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.OPERATOR, RoleToken.SECURITY_ADMIN, RoleToken.OPERATION_ADMIN, RoleToken.REPLAY_VIEWER),
                actions.find(ActionRegistry.CP_READINESS_START).orElseThrow().requiredRoleTokens());
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.ONBOARDING_ADMIN, RoleToken.REPLAY_VIEWER),
                actions.find(ActionRegistry.POLICY_COLLECT).orElseThrow().requiredRoleTokens());
        assertFalse(actions.find(ActionRegistry.JOB_CANCEL).orElseThrow().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
        for (String action : new String[]{ActionRegistry.CP_FAILOVER_APPROVE, ActionRegistry.CP_FAILOVER_START}) {
            assertFalse(actions.find(action).orElseThrow().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
        }
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates = key -> rows.stream().filter(row -> row.key().equals(key)).toList();
        assertTrue(rows.stream().anyMatch(row -> row.actionClass() != ActionClass.CLASS_0_READ));
        for (var row : rows) {
            if (row.actionClass() == ActionClass.CLASS_0_READ) continue;
            for (String role : new String[]{"gateway", "management_server"}) {
                assertTrue(DiagnosticRead.resolve(row.vendor(), role, row.platformRoleScope(), row.gateId(), null, gates).isEmpty(), row.gateId());
            }
        }
    }

    /**
     * PO decision 2026-10-18: aiview may run Packet Analysis (create a case, upload captures, request an analysis) next
     * to the actions it already held; the raw view stays out of reach. This is an exact allow-list: a new action that
     * names the replay viewer fails here until a decision record adds it on purpose.
     */
    @Test
    @SuppressWarnings("unchecked")
    void replayViewerHoldsExactlyTheDecidedActionsAndPacketAnalysisRunNeverRawView() throws Exception {
        var registry = new ActionRegistry();
        var field = ActionRegistry.class.getDeclaredField("actions");
        field.setAccessible(true);
        var all = (java.util.Map<String, com.securityexpert.nexus.ui2.service.security.ActionDescriptor>) field.get(registry);
        Set<String> held = new java.util.TreeSet<>();
        all.forEach((id, descriptor) -> {
            if (descriptor.requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER)) held.add(id);
        });
        assertEquals(new java.util.TreeSet<>(Set.of(
                // read-only measurements and reads decided on 2026-10-04 and earlier
                ActionRegistry.POLICY_COLLECT, ActionRegistry.POLICY_READ, ActionRegistry.DATA_SANITIZE_INFO,
                ActionRegistry.FMG_DIAGNOSTIC_RUN, ActionRegistry.SYSTEM_LOG_MEASURE_START, ActionRegistry.SYSTEM_LOG_READ,
                ActionRegistry.CP_READINESS_START, ActionRegistry.CP_FAILOVER_READ,
                // Packet Analysis: read, and run (PO decision 2026-10-18)
                ActionRegistry.PACKET_ANALYSIS_READ, ActionRegistry.PACKET_ANALYSIS_RUN)), held);
        assertFalse(all.get(ActionRegistry.PACKET_ANALYSIS_RAW_VIEW).requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
        assertFalse(ActionRegistry.PACKET_ANALYSIS_RAW_ROLES.contains(RoleToken.REPLAY_VIEWER));
        assertTrue(ActionRegistry.PACKET_ANALYSIS_RUN_ROLES.contains(RoleToken.REPLAY_VIEWER));
        // the data sanitizer (a different run action) stays closed to the replay viewer
        assertFalse(all.get(ActionRegistry.DATA_SANITIZE_RUN).requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
        assertFalse(all.get(ActionRegistry.DATA_SANITIZE_REVERT).requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
    }
}
