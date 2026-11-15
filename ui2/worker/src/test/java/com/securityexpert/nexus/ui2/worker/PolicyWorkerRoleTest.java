package com.securityexpert.nexus.ui2.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;

class PolicyWorkerRoleTest {
    @Test void policyOwnsOnlyTheExactPair() {
        assertEquals(List.of("cp_policy_collect", "pan_policy_collect"), WorkerClaimLoop.claimCapabilities("policy", false));
    }
    @Test void backupOwnsOnlyTheBackupCapabilitySet() {
        var backup = WorkerClaimLoop.claimCapabilities("backup", false);
        assertEquals(WorkerClaimLoop.BACKUP_CAPABILITY_IDS, backup);
        assertEquals(10, backup.size());
        assertTrue(backup.contains("cp_gateway_backup"));
        assertTrue(backup.contains("credential_canary"));
        assertTrue(WorkerClaimLoop.claimCapabilities("backup", true).isEmpty());
    }
    @Test void generalExcludesPolicyUnlessExplicitFallbackIsConfigured() {
        var general = WorkerClaimLoop.claimCapabilities("general", false);
        assertFalse(general.contains("cp_policy_collect")); assertFalse(general.contains("pan_policy_collect"));
        assertTrue(general.containsAll(WorkerClaimLoop.BACKUP_CAPABILITY_IDS));
        var fallback = WorkerClaimLoop.claimCapabilities("general", true);
        assertEquals(general.size() + 2, fallback.size());
        assertTrue(fallback.containsAll(List.of("cp_policy_collect", "pan_policy_collect")));
        assertTrue(WorkerClaimLoop.claimCapabilities("policy", true).isEmpty());
        var withoutBackupFallback = WorkerClaimLoop.claimCapabilities("general", false, false);
        assertEquals(general.size() - WorkerClaimLoop.BACKUP_CAPABILITY_IDS.size(), withoutBackupFallback.size());
        assertTrue(withoutBackupFallback.stream().noneMatch(WorkerClaimLoop.BACKUP_CAPABILITY_IDS::contains));
    }
    @Test void closedRolesRejectUnknownOrConflictingSelection() {
        assertEquals("general", Ui2WorkerMain.resolveRole(new String[0], null));
        assertEquals("policy", Ui2WorkerMain.resolveRole(new String[]{"policy"}, "policy"));
        assertEquals("policy", Ui2WorkerMain.resolveRole(new String[0], "policy"));
        assertEquals("backup", Ui2WorkerMain.resolveRole(new String[]{"backup"}, "backup"));
        assertEquals("backup", Ui2WorkerMain.resolveRole(new String[0], "backup"));
        assertEquals("configuration", Ui2WorkerMain.resolveRole(new String[]{"configuration"}, null));
        assertEquals("packet_analysis", Ui2WorkerMain.resolveRole(new String[]{"packet_analysis"}, null));
        assertThrows(IllegalArgumentException.class, () -> Ui2WorkerMain.resolveRole(new String[]{"packet_analysis"}, "policy"));
        assertThrows(IllegalArgumentException.class, () -> Ui2WorkerMain.resolveRole(new String[]{"unknown"}, null));
        assertThrows(IllegalArgumentException.class, () -> Ui2WorkerMain.resolveRole(new String[]{"policy"}, "general"));
        assertThrows(IllegalArgumentException.class, () -> Ui2WorkerMain.resolveRole(new String[]{"backup"}, "general"));
        assertThrows(IllegalArgumentException.class, () -> WorkerClaimLoop.claimCapabilities("unknown", false));
    }
}
