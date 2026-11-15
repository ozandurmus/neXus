package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundleHeavy.Step;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.CleanupWork;

/**
 * "Remove leftover work directory" (PO approved 2026-11-16): a cleanup job sends the approved C20 literal for the recorded
 * directory and nothing else; the request and its items are not touched, and the removal is retried once at most.
 */
class CpSupportBundleCleanupExecutorTest {

    private static final String DIR = "/var/log/nexus-sb-0123456789ab";

    static final class CStore extends BundleHarness.Store {
        CleanupWork work = new CleanupWork(BundleHarness.REQUEST, "device-1", DIR);
        Boolean removed;
        String outcome;
        int cleanups;
        CStore() { super("check_point", "cp_gaia_gateway", "cp_hcp_run"); }
        @Override public Optional<CleanupWork> findCleanupByJob(String jobId) { return "cleanup-job".equals(jobId) ? Optional.ofNullable(work) : Optional.empty(); }
        @Override public void finishCleanup(UUID requestId, boolean removed, String outcome) { this.removed = removed; this.outcome = outcome; cleanups++; }
    }

    private void run(BundleHarness h, CpSupportBundleHeavyExecutorTest.Dev dev, CStore store) {
        new CpSupportBundleJobExecutor(h.leases, h.attempts, h.devices, dev, h.gates(), store, h.artefacts,
                BundleHarness.fingerprint(), BundleHarness.clock(), d -> { }).execute("cleanup-job", 1, "device-1", "192.0.2.1", 22, "credential-ref");
    }

    @Test
    void sendsOnlyTheApprovedRemoveLiteralForTheRecordedDirectory(@TempDir Path dir) {
        var h = new BundleHarness(dir, "check_point", "gateway", "ssh_exec", null);
        var dev = new CpSupportBundleHeavyExecutorTest.Dev();
        var store = new CStore();
        run(h, dev, store);
        assertEquals(List.of(Step.REMOVE.template().replace("%1$s", DIR)), dev.commands);
        assertEquals("rm -rf " + DIR + " " + DIR + ".tgz", dev.commands.get(0).replaceFirst("^.*; ", ""));
        assertTrue(store.removed);
        assertEquals("REMOVED", store.outcome);
        assertEquals("COMPLETED", h.finalState);
        assertEquals(List.of("cp_sb_remove_workdir"), h.gateIds);
        assertEquals(List.of("recovery-write"), h.classes);
        assertTrue(store.states.isEmpty(), "no item is touched");
        assertEquals(null, store.requestState, "the request is not finished again");
    }

    @Test
    void aRemovalThatFailsIsRetriedOnceAndThenRecordedAsFailed(@TempDir Path dir) {
        var h = new BundleHarness(dir, "check_point", "gateway", "ssh_exec", null);
        var dev = new CpSupportBundleHeavyExecutorTest.Dev();
        dev.removeAlwaysFails = true;
        var store = new CStore();
        run(h, dev, store);
        assertEquals(2, dev.count("rm -rf"));
        assertFalse(store.removed);
        assertEquals("REMOVE_FAILED", store.outcome);
        assertEquals("FAILED", h.finalState);
    }

    @Test
    void aDirectoryOutsideThePatternOrAnotherDeviceIsRefusedBeforeAnyCommand(@TempDir Path dir) {
        for (String bad : new String[] {"/etc", "/var/log/nexus-sb-0123456789a", "/var/log/nexus-sb-0123456789ab; id", "/var/log/../etc"}) {
            var h = new BundleHarness(dir, "check_point", "gateway", "ssh_exec", null);
            var dev = new CpSupportBundleHeavyExecutorTest.Dev();
            var store = new CStore();
            store.work = new CleanupWork(BundleHarness.REQUEST, "device-1", bad);
            run(h, dev, store);
            assertTrue(dev.commands.isEmpty(), bad);
            assertEquals("REJECTED", h.finalState, bad);
            assertFalse(store.removed);
        }
        var h = new BundleHarness(dir, "check_point", "gateway", "ssh_exec", null);
        var dev = new CpSupportBundleHeavyExecutorTest.Dev();
        var store = new CStore();
        store.work = new CleanupWork(BundleHarness.REQUEST, "device-2", DIR);
        run(h, dev, store);
        assertTrue(dev.commands.isEmpty());
        assertEquals("UNAVAILABLE", store.outcome);
    }

    @Test
    void aManagementServerUsesTheManagementScopeRow(@TempDir Path dir) {
        var h = new BundleHarness(dir, "check_point", "management_server", "ssh_exec", null);
        var dev = new CpSupportBundleHeavyExecutorTest.Dev();
        var store = new CStore();
        run(h, dev, store);
        assertEquals(List.of("cpm_sb_remove_workdir"), h.gateIds);
        assertTrue(store.removed);
    }
}
