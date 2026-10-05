package com.securityexpert.nexus.ui2.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;

class PolicyWorkerRoleTest {
    @Test void policyOwnsOnlyTheExactPair() {
        assertEquals(List.of("cp_policy_collect", "pan_policy_collect"), WorkerClaimLoop.claimCapabilities("policy", false));
    }
    @Test void generalExcludesPolicyUnlessExplicitFallbackIsConfigured() {
        var general = WorkerClaimLoop.claimCapabilities("general", false);
        assertFalse(general.contains("cp_policy_collect")); assertFalse(general.contains("pan_policy_collect"));
        var fallback = WorkerClaimLoop.claimCapabilities("general", true);
        assertEquals(general.size() + 2, fallback.size());
        assertTrue(fallback.containsAll(List.of("cp_policy_collect", "pan_policy_collect")));
        assertTrue(WorkerClaimLoop.claimCapabilities("policy", true).isEmpty());
    }
    @Test void closedRolesRejectUnknownOrConflictingSelection() {
        assertEquals("general", Ui2WorkerMain.resolveRole(new String[0], null));
        assertEquals("policy", Ui2WorkerMain.resolveRole(new String[]{"policy"}, "policy"));
        assertEquals("policy", Ui2WorkerMain.resolveRole(new String[0], "policy"));
        assertEquals("configuration", Ui2WorkerMain.resolveRole(new String[]{"configuration"}, null));
        assertThrows(IllegalArgumentException.class, () -> Ui2WorkerMain.resolveRole(new String[]{"unknown"}, null));
        assertThrows(IllegalArgumentException.class, () -> Ui2WorkerMain.resolveRole(new String[]{"policy"}, "general"));
        assertThrows(IllegalArgumentException.class, () -> WorkerClaimLoop.claimCapabilities("unknown", false));
    }
}
