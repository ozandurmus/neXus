package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Kind;

/** V246 (PO 2026-11-17): the install-history read is sent at every Check Point gateway inventory run, switch default on. */
class InventoryInstallHistoryTest {

    private static final String PROP = "ui2.cp.install-history.read";
    private static final String DEVICE_ID = "device-enrolled-1";
    private static final String ANSWER = "2024-02-23T12:33:58 install_fw1_R81.20.elg \n"
            + "2024-07-24T22:02:57 install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN.log Bundle_T65\n";

    @AfterEach
    void clear() {
        System.clearProperty(PROP);
    }

    @Test
    void theLiteralEqualsTheApprovedStringByteForByteAndIsNotWrapped() {
        String approved = "unset BASH_ENV ENV; cd /opt/CPInstLog 2>/dev/null || exit 0; for f in $(ls -1tr install_fw1_R*.elg install_SecurePlatform_HOTFIX_*JUMBO*.log 2>/dev/null | tail -n 20); do echo \"$(date -r \"$f\" +%Y-%m-%dT%H:%M:%S) $f $(grep -a -o -m1 -E 'Bundle_T[0-9]+' \"$f\" | head -n1)\"; done";
        assertEquals(approved, InventoryReadPlan.CP_INSTALL_HISTORY);
        assertFalse(InventoryReadPlan.CP_INSTALL_HISTORY.startsWith("bash"));
        assertFalse(InventoryReadPlan.CP_INSTALL_HISTORY.contains("bash -lc"));
    }

    @Test
    void theSkipReasonsAreTheApprovedExclusionsAndTheSwitch() {
        assertEquals(Optional.empty(), InventoryCapabilityExecutor.installHistorySkipReason(true, false, false, false));
        assertEquals(Optional.of("switch_off"), InventoryCapabilityExecutor.installHistorySkipReason(false, false, false, false));
        assertEquals(Optional.of("management_server"), InventoryCapabilityExecutor.installHistorySkipReason(true, true, false, false));
        assertEquals(Optional.of("vsx_host"), InventoryCapabilityExecutor.installHistorySkipReason(true, false, true, false));
        assertEquals(Optional.of("clish_landing"), InventoryCapabilityExecutor.installHistorySkipReason(true, false, false, true));
    }

    @Test
    void theSwitchIsOnByDefaultAndOnlyZeroTurnsItOff() {
        assertTrue(InventoryCapabilityExecutor.installHistoryReadEnabled());
        System.setProperty(PROP, "0");
        assertFalse(InventoryCapabilityExecutor.installHistoryReadEnabled());
        System.setProperty(PROP, "1");
        assertTrue(InventoryCapabilityExecutor.installHistoryReadEnabled());
    }

    private record Run(java.util.List<String> commands, JobOutcome outcome,
            java.util.List<com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Observed> stored) {
    }

    private Run run(String vsxStat, String historyAnswer, boolean failHistory) {
        Map<String, String> scripted = new HashMap<>(Map.of(
                InventoryReadPlan.CP_VSX_STAT, vsxStat.isEmpty() ? "VSX is not supported on this platform\n" : vsxStat,
                InventoryReadPlan.CP_FW_GETIFS, "localhost eth0 192.0.2.20 255.255.255.0\n",
                InventoryReadPlan.CP_IP_ROUTE_SHOW, "default via 192.0.2.1 dev eth0 proto 7\n",
                InventoryReadPlan.CP_CPHAPROB_STAT, "Cluster is not enabled\n"));
        if (!failHistory) {
            scripted.put(InventoryReadPlan.CP_INSTALL_HISTORY, historyAnswer);
        }
        scripted.put("bash -lc 'vsenv 0 && " + InventoryReadPlan.CP_FW_GETIFS + "'", "localhost eth0 192.0.2.20 255.255.255.0\n");
        scripted.put("bash -lc 'vsenv 0 && " + InventoryReadPlan.CP_IP_ROUTE_SHOW + "'", "default via 192.0.2.1 dev eth0 proto 7\n");
        scripted.put("bash -lc 'vsenv 0 && " + InventoryReadPlan.CP_CPHAPROB_STAT + "'", "Cluster is not enabled\n");
        scripted.put("bash -lc 'vsenv 0 >/dev/null 2>&1 || true; " + InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF + "'", "");
        ScriptedCheckPointInventoryTransport transport = new ScriptedCheckPointInventoryTransport(scripted);
        var stored = new java.util.ArrayList<com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Observed>();
        InventoryJobExecutor executor = new InventoryJobExecutor(
                new InventoryJobExecutorFakes.FakeLeaseRepository("job-1", 1L, JobState.CLAIMED),
                new InventoryJobExecutorFakes.FakeStepAttemptRepository(),
                new InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort(),
                new InventoryJobExecutorFakes.FakeDeviceRepository(),
                new InventoryJobExecutorFakes.FakeDeviceInventoryRepository(),
                new InventoryCapabilityExecutor(transport, ref -> { throw new IllegalStateException("not used"); }))
                .withInstallEventRepository(new com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository() {
                    @Override public int record(String deviceId, java.util.List<Observed> events) {
                        assertEquals(DEVICE_ID, deviceId);
                        stored.addAll(events);
                        return events.size();
                    }
                    @Override public java.util.List<Event> forDevice(String deviceId) { return java.util.List.of(); }
                    @Override public java.util.List<Event> installedSince(java.time.LocalDateTime s, int l) { return java.util.List.of(); }
                });
        JobOutcome outcome = executor.execute("job-1", 1L, DEVICE_ID,
                InventoryRequest.checkPoint(new ConnectionTarget("ep-1", "gw-a-host", 22), "cred-1", "trust-1"), false);
        return new Run(transport.commands, outcome, stored);
    }

    @Test
    void sentOnceInThePlainShellAndTheParsedEventsAreStored() {
        Run r = run("", ANSWER, false);
        assertTrue(r.outcome() instanceof JobOutcome.Completed, "expected Completed, got " + r.outcome());
        assertEquals(1, r.commands().stream().filter(InventoryReadPlan.CP_INSTALL_HISTORY::equals).count(), r.commands().toString());
        assertTrue(r.commands().stream().filter(c -> c.contains("CPInstLog")).noneMatch(c -> c.startsWith("bash")));
        assertEquals(2, r.stored().size());
        assertEquals(Kind.BASE_INSTALL, r.stored().get(0).kind());
        assertEquals(Optional.of(65), r.stored().get(1).jumboTake());
    }

    @Test
    void notSentWhenTheSwitchIsOffOrOnAVsxHost() {
        System.setProperty(PROP, "0");
        Run off = run("", ANSWER, false);
        assertFalse(off.commands().contains(InventoryReadPlan.CP_INSTALL_HISTORY));
        assertTrue(off.stored().isEmpty());
        System.clearProperty(PROP);
        Run vsx = run("ID | Type | Name\n0 | S | VS0\n3 | S | vs-lab\n", ANSWER, false);
        assertTrue(vsx.commands().stream().noneMatch(c -> c.contains("CPInstLog")), vsx.commands().toString());
        assertTrue(vsx.stored().isEmpty());
    }

    @Test
    void aReadWithNoUsableAnswerStoresNothingAndTheInventoryStillCompletes() {
        Run garbage = run("", "bash: nothing useful\n", false);
        assertTrue(garbage.outcome() instanceof JobOutcome.Completed);
        assertTrue(garbage.stored().isEmpty());
    }
}
