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
    /** Review 2026-11-15 finding 1.2: cp_mds_export has no "backup" in its name and was classified INVENTORY. */
    @Test void everyClaimableJobTypeHasAnExplicitPurposeAndBackupTypesAreBackup() {
        var expected = new java.util.TreeMap<String, String>();
        for (String id : WorkerClaimLoop.BACKUP_CAPABILITY_IDS) expected.put(id, "BACKUP");
        assertEquals("BACKUP", WorkerClaimLoop.purposeOf("cp_mds_export"));
        var others = java.util.Map.ofEntries(
            java.util.Map.entry("cp_policy_collect", "POLICY"), java.util.Map.entry("pan_policy_collect", "POLICY"),
            java.util.Map.entry("cp_cluster_failover", "FAILOVER_EXECUTION"), java.util.Map.entry("pan_cluster_failover", "FAILOVER_EXECUTION"),
            java.util.Map.entry("cp_failover_readiness", "FAILOVER_READINESS"), java.util.Map.entry("pan_failover_readiness", "FAILOVER_READINESS"));
        expected.putAll(others);
        var unclassified = new java.util.TreeSet<String>();
        for (String id : WorkerClaimLoop.allCapabilityIds()) {
            String purpose = WorkerClaimLoop.purposeOf(id);
            if (expected.containsKey(id)) assertEquals(expected.get(id), purpose, id);
            else if ("INVENTORY".equals(purpose) && !id.contains("inventory")) unclassified.add(id);
            if ("BACKUP".equals(purpose)) assertTrue(WorkerClaimLoop.BACKUP_CAPABILITY_IDS.contains(id), id + " is BACKUP but not a backup-pod capability");
        }
        // A new job type that falls through to the INVENTORY default must be classified on purpose.
        assertEquals(java.util.Set.of(), unclassified, "unclassified job types default to INVENTORY");
    }
}
