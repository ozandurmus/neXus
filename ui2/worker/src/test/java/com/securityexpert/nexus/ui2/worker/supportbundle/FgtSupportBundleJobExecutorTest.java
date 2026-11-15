package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.supportbundle.FgtSupportBundle;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.StreamedInteractiveResult.Kind;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.StoredConfiguration;
import com.securityexpert.nexus.ui2.worker.supportbundle.AsaSupportBundleJobExecutorTest.Shell;

/** The {@code fgt_support_bundle} executor against a scripted FortiGate shell. Every value is synthetic. */
class FgtSupportBundleJobExecutorTest {
    private static ExecResult ok(String output) { return new ExecResult.Completed(output, 0); }

    private static final String STATUS_SINGLE = "Version: FortiGate-100F v7.4.10,build2829,250101 (GA.M)\nVirtual domain configuration: disable\n"
            + "Hostname: FGT-TANGO-04\nSerial-Number: SYNTHFGT0001\n";
    private static final String STATUS_MULTI = STATUS_SINGLE.replace("v7.4.10", "v7.6.6").replace("disable", "multiple");
    private static final String PERF = "CPU states: 3% user 2% system 0% nice 95% idle 0% iowait 0% irq 0% softirq\n";

    private BundleHarness harness(Path dir, String version) {
        var h = new BundleHarness(dir, "fortinet", "gateway", "ssh_exec", version);
        when(h.devices.findSummary("device-1")).thenReturn(version == null ? Optional.empty()
                : Optional.of(new DeviceSummaryRecord("device-1", "gateway", "fortinet",
                        com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED, Optional.of("FGT-TANGO-04"),
                        Optional.empty(), Optional.of(version), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
        return h;
    }

    private static Shell script(String status) {
        Shell s = new Shell();
        s.prompt = "FGT-TANGO-04 #";
        s.answers.put("get system status", ok(status));
        s.answers.put("get system performance status", ok(PERF));
        s.answers.put("config global", new ExecResult.ChannelFailed("empty output"));
        s.answers.put("end", new ExecResult.ChannelFailed("empty output"));
        s.streams.put("diagnose debug crashlog read", List.of("1: 2026-11-10 10:00:00 the killed daemon synthetic", "2: more synthetic"));
        s.streams.put("execute tac report", List.of("===== get system status =====", "config system admin",
                "    edit \"synthetic-admin\"", "        set password ENC SH2synthetichashblobsynthetichashblobsynthetichash==", "    next", "end"));
        return s;
    }

    private void run(BundleHarness h, BundleHarness.Store store, Shell shell) {
        new FgtSupportBundleJobExecutor(h.leases, h.attempts, h.devices, shell, h.gates(), store, h.artefacts,
                BundleHarness.fingerprint(), BundleHarness.clock()).execute("job-1", 1, "device-1", "192.0.2.1", 22, "credential-ref");
    }

    @Test
    void aSingleVdomDeviceRunsFiveApprovedLiteralsWithoutConfigGlobal(@TempDir Path dir) throws Exception {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog", "fgt_tac_report");
        run(h, store, shell);
        assertEquals(List.of("get system status", "get system performance status", "diagnose debug crashlog read", "execute tac report"),
                shell.commands);
        assertEquals(List.of("fgt_sb_get_system_status", "fgt_sb_performance_status", "fgt_sb_crashlog_read", "fgt_sb_tac_report"), h.gateIds);
        assertEquals(List.of("--More--", "--More--"), shell.pagers);
        assertEquals("COMPLETED", store.state("fgt_crashlog"));
        assertEquals("COMPLETED", store.state("fgt_tac_report"));
        assertEquals("COMPLETED", store.requestState);
        assertEquals("items/fgt_tac_report/tac-report.txt", store.entryNames.get(store.id("fgt_tac_report")));
        assertEquals("support_bundle", store.manifests.get(store.id("fgt_tac_report")).artefactClass());
    }

    @Test
    void aMultiVdomDeviceEntersGlobalOnceAndLeavesItAfterTheLastItem(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_MULTI);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog", "fgt_tac_report");
        run(h, store, shell);
        assertEquals(List.of("get system status", "config global", "get system performance status", "diagnose debug crashlog read",
                "execute tac report", "end"), shell.commands);
        assertEquals("COMPLETED", store.requestState);
    }

    @Test
    void aStatusRefusedAtTheTopLevelTriesConfigGlobalOnceInstead(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_MULTI);
        shell.answers.put("get system status", new ExecResult.ChannelFailed("cli error"));
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog");
        run(h, store, shell);
        assertEquals(List.of("get system status", "config global", "diagnose debug crashlog read", "end"), shell.commands);
        assertEquals(1, shell.commands.stream().filter("get system status"::equals).count(), "F1 once per run");
    }

    @Test
    void theTacReportIsRedactedLineByLineBeforeStorage(@TempDir Path dir) throws Exception {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_tac_report");
        run(h, store, shell);
        String text = new String(h.read(store.manifests.get(store.id("fgt_tac_report"))), StandardCharsets.UTF_8);
        assertFalse(text.contains("SH2synthetichashblob"), text);
        assertTrue(text.contains("===== get system status ====="));
    }

    @Test
    void aBusyCpuRefusesOnlyTheTacReport(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        shell.answers.put("get system performance status", ok("CPU states: 50% user 40% system 0% nice 10% idle\n"));
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog", "fgt_tac_report");
        run(h, store, shell);
        assertEquals("COMPLETED", store.state("fgt_crashlog"));
        assertEquals("REFUSED_PRECHECK", store.state("fgt_tac_report"));
        assertEquals("CPU_OVER_LIMIT", store.code("fgt_tac_report"));
        assertEquals("CPU 90% exceeds 80% limit", store.details.get(store.id("fgt_tac_report")));
        assertFalse(shell.commands.contains("execute tac report"));
    }

    @Test
    void anUnreadableCpuRefusesTheTacReport(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        shell.answers.put("get system performance status", ok("garbled\n"));
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_tac_report");
        run(h, store, shell);
        assertEquals("PRECHECK_UNKNOWN", store.code("fgt_tac_report"));
    }

    @Test
    void anAffectedVersionWithADosPolicyIsRefusedBeforeAnyContactButTheCrashLogStillRuns(@TempDir Path dir) throws Exception {
        var h = harness(dir, "v7.4.4");
        var shell = script(STATUS_SINGLE);
        store0(h, "config system global\nend\nconfig firewall DoS-policy\n    edit 1\n    next\nend\n");
        var store = withConfig(h, "fgt_tac_report");
        run(h, store, shell);
        assertEquals("REFUSED_PRECHECK", store.state("fgt_tac_report"));
        assertEquals("VENDOR_DEFECT_SESSION_CRASH", store.code("fgt_tac_report"));
        assertEquals("FortiOS 7.4.4 with DoS-policy refused (VENDOR_DEFECT_SESSION_CRASH)", store.details.get(store.id("fgt_tac_report")));
        assertTrue(shell.commands.isEmpty(), "only the report was requested: no device contact at all");
        assertEquals("FAILED", store.requestState);
    }

    @Test
    void theCrashLogStillRunsWhenTheReportIsRefusedByTheGuard(@TempDir Path dir) throws Exception {
        var h = harness(dir, "v7.2.8");
        var shell = script(STATUS_SINGLE.replace("v7.4.10", "v7.2.8"));
        store0(h, "config firewall DoS-policy6\nend\n");
        var store = withConfig(h, "fgt_crashlog", "fgt_tac_report");
        run(h, store, shell);
        assertEquals("COMPLETED", store.state("fgt_crashlog"));
        assertEquals("REFUSED_PRECHECK", store.state("fgt_tac_report"));
        assertFalse(shell.commands.contains("execute tac report"));
        assertEquals("PARTIAL", store.requestState);
    }

    @Test
    void anAffectedVersionWithoutADosPolicyRuns(@TempDir Path dir) throws Exception {
        var h = harness(dir, "v7.4.4");
        var shell = script(STATUS_SINGLE.replace("v7.4.10", "v7.4.4"));
        store0(h, "config system global\nend\n");
        var store = withConfig(h, "fgt_tac_report");
        run(h, store, shell);
        assertEquals("COMPLETED", store.state("fgt_tac_report"));
    }

    @Test
    void anAffectedVersionWhoseStoredConfigurationCannotBeReadIsRefused(@TempDir Path dir) {
        var h = harness(dir, "v7.0.15");
        var shell = script(STATUS_SINGLE);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_tac_report");
        run(h, store, shell);
        assertEquals("REFUSED_PRECHECK", store.state("fgt_tac_report"));
        assertTrue(store.details.get(store.id("fgt_tac_report")).contains("VENDOR_DEFECT_UNCHECKABLE"));
        assertTrue(shell.commands.isEmpty());
    }

    @Test
    void theLiveVersionCorroboratesAndAnAffectedLiveVersionIsRefused(@TempDir Path dir) throws Exception {
        var h = harness(dir, "v7.6.6"); // stored: fine; live: the device now reports an affected version
        var shell = script(STATUS_SINGLE.replace("v7.4.10", "v7.4.4"));
        store0(h, "config firewall DoS-policy\nend\n");
        var store = withConfig(h, "fgt_tac_report");
        run(h, store, shell);
        assertEquals("REFUSED_PRECHECK", store.state("fgt_tac_report"));
        assertFalse(shell.commands.contains("execute tac report"));
    }

    @Test
    void aVersionNeitherStoredNorReadIsRefusedFailClosed(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script("garbled status\n");
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_tac_report");
        run(h, store, shell);
        assertEquals("REFUSED_PRECHECK", store.state("fgt_tac_report"));
        assertFalse(shell.commands.contains("execute tac report"));
    }

    @Test
    void anUnknownActionAnswerIsUnsupported(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        shell.endings.put("diagnose debug crashlog read", Kind.CLI_ERROR);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog");
        run(h, store, shell);
        assertEquals("UNSUPPORTED", store.state("fgt_crashlog"));
        assertTrue(store.manifests.isEmpty());
    }

    @Test
    void aSessionDropAfterFourKiBIsPartialAndLaterItemsAreNotRun(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 100; i++) big.add("    set description \"synthetic padding line number " + i + " padding padding\"");
        shell.streams.put("diagnose debug crashlog read", big);
        shell.endings.put("diagnose debug crashlog read", Kind.SESSION_LOST);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog", "fgt_tac_report");
        run(h, store, shell);
        assertEquals("PARTIAL", store.state("fgt_crashlog"));
        assertEquals("SESSION_LOST", store.code("fgt_crashlog"));
        assertEquals("SESSION_CLOSED_EARLIER", store.code("fgt_tac_report"));
        assertFalse(shell.commands.contains("execute tac report"));
        assertFalse(shell.commands.contains("end"), "a closed session is not spoken to again");
    }

    @Test
    void aDeadlineIsTimedOut(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        shell.endings.put("execute tac report", Kind.TIMED_OUT);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_tac_report");
        run(h, store, shell);
        assertEquals("TIMED_OUT", store.state("fgt_tac_report"));
        assertTrue(store.manifests.isEmpty());
    }

    @Test
    void aGateThatIsNotSignedOffRejectsTheJob(@TempDir Path dir) {
        var h = harness(dir, null);
        var shell = script(STATUS_SINGLE);
        h.rows = h.rows.stream().filter(r -> !r.gateId().equals("fgt_sb_tac_report")).toList();
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog");
        run(h, store, shell);
        assertEquals("REJECTED", h.finalState);
        assertTrue(shell.commands.isEmpty());
    }

    @Test
    void aFortiManagerRoleIsRejected(@TempDir Path dir) {
        var h = new BundleHarness(dir, "fortinet", "management_server", "https", null);
        var shell = script(STATUS_SINGLE);
        var store = new BundleHarness.Store("fortinet", "fortigate", "fgt_crashlog");
        run(h, store, shell);
        assertEquals("REJECTED", h.finalState);
        assertTrue(shell.commands.isEmpty());
    }

    @Test
    void theVersionMatrixOfTheDefectGuard() {
        for (String affected : List.of("v7.0.15", "7.2.8", "v7.4.4", "v7.4.9", "v7.6.0", "v7.6.5"))
            assertTrue(FgtSupportBundle.versionAffectedByTacReportDefect(affected), affected);
        for (String fine : List.of("v7.4.10", "v7.4.11", "v7.6.6", "v7.6.7", "v8.0.0", "v7.8.1", "v6.4.14"))
            assertFalse(FgtSupportBundle.versionAffectedByTacReportDefect(fine), fine);
        assertFalse(FgtSupportBundle.versionAffectedByTacReportDefect(null));
        assertFalse(FgtSupportBundle.versionAffectedByTacReportDefect("unknown"));
    }

    @Test
    void parsersReadTheFortiOsShapes() {
        assertEquals(5, FgtSupportBundleJobExecutor.cpuPercent(PERF));
        assertEquals(95, FgtSupportBundleJobExecutor.cpuPercent("CPU0 states: 5% idle\nCPU1 states: 50% idle\n"));
        assertNull(FgtSupportBundleJobExecutor.cpuPercent("no cpu line"));
        assertEquals("7.4.4", FgtSupportBundleJobExecutor.numericVersion("v7.4.4 build2662"));
        assertNull(FgtSupportBundleJobExecutor.numericVersion("unknown"));
    }

    // ------------------------------------------------------------------ helpers

    private com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore.ArtefactMetadata stored;

    /** Puts a synthetic stored configuration in the vault (as a backup would have left it). */
    private void store0(BundleHarness h, String configuration) throws IOException {
        try (var handle = h.artefacts.open("device-1", "config-job", "fortinet", false)) {
            handle.sink().write(configuration.getBytes(StandardCharsets.UTF_8));
            stored = handle.finish();
        }
    }

    private BundleHarness.Store withConfig(BundleHarness h, String... kinds) {
        var store = new BundleHarness.Store("fortinet", "fortigate", kinds);
        store.configuration = new StoredConfiguration(stored.ref().value(), stored.wrappedDataKey(), false);
        return store;
    }
}
