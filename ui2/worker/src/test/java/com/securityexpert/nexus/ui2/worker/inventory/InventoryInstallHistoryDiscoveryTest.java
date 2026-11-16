package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;

/** V245 one-off (PO 2026-11-16): the install-history discovery read is sent only for the named device. */
class InventoryInstallHistoryDiscoveryTest {

    private static final String PROP = "ui2.cp.install-history.discovery-device";
    private static final String DEVICE_ID = "device-enrolled-1";

    @AfterEach
    void clear() {
        System.clearProperty(PROP);
    }

    @Test
    void theLiteralEqualsTheApprovedStringByteForByteAndIsNotWrapped() {
        String approved = "unset BASH_ENV ENV; ls -l --time-style=+%Y-%m-%dT%H:%M:%S /opt/CPInstLog 2>&1 | tail -n 80; "
                + "find /opt/CPda /var/log/CPda -maxdepth 3 -name 'DA_Actions*' 2>/dev/null | head -5";
        assertEquals(approved, InventoryReadPlan.CP_INSTALL_HISTORY_DISCOVERY_ONCE);
        assertFalse(InventoryReadPlan.CP_INSTALL_HISTORY_DISCOVERY_ONCE.startsWith("bash"));
    }

    @Test
    void theDecisionRequiresTheMatchingDeviceAndAPlainGatewayContext() {
        assertTrue(InventoryCapabilityExecutor.installHistoryDiscoveryApplies("d1", "d1", false, false, false));
        assertFalse(InventoryCapabilityExecutor.installHistoryDiscoveryApplies("d1", "d2", false, false, false));
        assertFalse(InventoryCapabilityExecutor.installHistoryDiscoveryApplies("", "d1", false, false, false));
        assertFalse(InventoryCapabilityExecutor.installHistoryDiscoveryApplies(null, "d1", false, false, false));
        assertFalse(InventoryCapabilityExecutor.installHistoryDiscoveryApplies("d1", "d1", false, true, false), "VSX host");
        assertFalse(InventoryCapabilityExecutor.installHistoryDiscoveryApplies("d1", "d1", true, false, false), "management server");
        assertFalse(InventoryCapabilityExecutor.installHistoryDiscoveryApplies("d1", "d1", false, false, true), "clish landing");
    }

    private java.util.List<String> run(String vsxStat) {
        java.util.Map<String, String> scripted = new java.util.HashMap<>(Map.of(
                InventoryReadPlan.CP_VSX_STAT, vsxStat.isEmpty() ? "VSX is not supported on this platform\n" : vsxStat,
                InventoryReadPlan.CP_FW_GETIFS, "localhost eth0 192.0.2.20 255.255.255.0\n",
                InventoryReadPlan.CP_IP_ROUTE_SHOW, "default via 192.0.2.1 dev eth0 proto 7\n",
                InventoryReadPlan.CP_CPHAPROB_STAT, "Cluster is not enabled\n",
                InventoryReadPlan.CP_INSTALL_HISTORY_DISCOVERY_ONCE, "synthetic listing"));
        scripted.put("bash -lc 'vsenv 0 && " + InventoryReadPlan.CP_FW_GETIFS + "'", "localhost eth0 192.0.2.20 255.255.255.0\n");
        scripted.put("bash -lc 'vsenv 0 && " + InventoryReadPlan.CP_IP_ROUTE_SHOW + "'", "default via 192.0.2.1 dev eth0 proto 7\n");
        scripted.put("bash -lc 'vsenv 0 && " + InventoryReadPlan.CP_CPHAPROB_STAT + "'", "Cluster is not enabled\n");
        scripted.put("bash -lc 'vsenv 0 >/dev/null 2>&1 || true; " + InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF + "'", "");
        ScriptedCheckPointInventoryTransport transport = new ScriptedCheckPointInventoryTransport(scripted);
        InventoryJobExecutor executor = new InventoryJobExecutor(
                new InventoryJobExecutorFakes.FakeLeaseRepository("job-1", 1L, JobState.CLAIMED),
                new InventoryJobExecutorFakes.FakeStepAttemptRepository(),
                new InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort(),
                new InventoryJobExecutorFakes.FakeDeviceRepository(),
                new InventoryJobExecutorFakes.FakeDeviceInventoryRepository(),
                new InventoryCapabilityExecutor(transport, ref -> { throw new IllegalStateException("not used"); }));
        JobOutcome outcome = executor.execute("job-1", 1L, DEVICE_ID,
                InventoryRequest.checkPoint(new ConnectionTarget("ep-1", "gw-a-host", 22), "cred-1", "trust-1"), false);
        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        return transport.commands;
    }

    @Test
    void neverSentWhenTheSwitchIsUnset() {
        assertFalse(run("").contains(InventoryReadPlan.CP_INSTALL_HISTORY_DISCOVERY_ONCE));
    }

    @Test
    void neverSentForAnotherDevice() {
        System.setProperty(PROP, "some-other-device");
        assertFalse(run("").contains(InventoryReadPlan.CP_INSTALL_HISTORY_DISCOVERY_ONCE));
    }

    @Test
    void sentOnceInThePlainShellForTheMatchingDevice() {
        System.setProperty(PROP, DEVICE_ID);
        var commands = run("");
        assertEquals(1, commands.stream().filter(InventoryReadPlan.CP_INSTALL_HISTORY_DISCOVERY_ONCE::equals).count(), commands.toString());
        assertTrue(commands.stream().filter(c -> c.contains("DA_Actions")).noneMatch(c -> c.startsWith("bash")), commands.toString());
    }

    @Test
    void notSentOnAVsxHostEvenForTheMatchingDevice() {
        System.setProperty(PROP, DEVICE_ID);
        var commands = run("ID | Type | Name\n0 | S | VS0\n3 | S | vs-lab\n");
        assertTrue(commands.stream().noneMatch(c -> c.contains("DA_Actions")), commands.toString());
    }

    @Test
    void logLinesKeepOnlyFileNameAndDateCharactersAndAreBounded() {
        String out = "total 8\n-rw-r--r-- 1 u g 12 2026-10-01T02:03:04 install_jumbo_T119.log\n\n weird$`line\n"
                + "x".repeat(300) + "\n" + "a\n".repeat(200);
        var lines = InventoryCapabilityExecutor.installHistoryDiscoveryLogLines(out);
        org.junit.jupiter.api.Assertions.assertEquals("-rw-r--r-- 1 u g 12 2026-10-01T02:03:04 install_jumbo_T119.log", lines.get(1));
        org.junit.jupiter.api.Assertions.assertEquals("weird??line", lines.get(2));
        org.junit.jupiter.api.Assertions.assertEquals(160, lines.get(3).length());
        org.junit.jupiter.api.Assertions.assertEquals(90, lines.size());
        org.junit.jupiter.api.Assertions.assertTrue(InventoryCapabilityExecutor.installHistoryDiscoveryLogLines(null).isEmpty());
    }
}
