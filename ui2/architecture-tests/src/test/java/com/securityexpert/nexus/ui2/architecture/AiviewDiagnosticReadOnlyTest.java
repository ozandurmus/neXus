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
    void replayViewerHasOnlyDiagnosticReadExecutionAuthorityAndNoNonReadGateCanResolve() {
        var actions = new ActionRegistry();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                actions.find(ActionRegistry.FMG_DIAGNOSTIC_RUN).orElseThrow().requiredRoleTokens());
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
}
