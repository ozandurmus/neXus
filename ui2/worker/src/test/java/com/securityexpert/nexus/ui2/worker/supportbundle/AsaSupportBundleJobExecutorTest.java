package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.LineSink;
import com.securityexpert.nexus.ui2.jobs.transport.StreamedInteractiveResult;
import com.securityexpert.nexus.ui2.jobs.transport.StreamedInteractiveResult.Kind;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;

/**
 * The {@code asa_support_bundle} and {@code fgt_support_bundle} executors against a scripted interactive shell (no socket,
 * host or device contact). Every value is synthetic (FW-TANGO-04, 192.0.2.x, documentation secrets).
 */
class AsaSupportBundleJobExecutorTest {

    /** A scripted interactive shell shared with the FortiGate test. */
    static final class Shell implements DeviceTransport {
        final List<String> commands = new ArrayList<>();
        final List<String> pagers = new ArrayList<>();
        final Map<String, ExecResult> answers = new HashMap<>();
        final Map<String, List<String>> streams = new HashMap<>();
        final Map<String, Kind> endings = new HashMap<>();
        String prompt = "asa-tango-04#";
        String promptAfterResync;
        int resyncs;
        boolean connectFails;
        boolean closedAfterConnect;

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            if (connectFails) return new ConnectResult.AuthenticationFailed("synthetic");
            return new ConnectResult.Authenticated(new TransportSession() {
                @Override public String sessionId() { return "s1"; }
                @Override public Optional<String> interactivePrompt() { return Optional.of(prompt); }
            });
        }
        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) { throw new AssertionError("exec"); }
        @Override public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            commands.add(spec.command());
            ExecResult answer = answers.get(spec.command());
            if (answer == null) throw new AssertionError("unexpected command " + spec.command());
            return answer;
        }
        @Override public StreamedInteractiveResult execInteractiveStreaming(TransportSession session, ExecSpec spec, Duration timeout,
                String pagerMarker, LineSink sink) {
            commands.add(spec.command());
            pagers.add(pagerMarker);
            List<String> lines = streams.get(spec.command());
            if (lines == null) throw new AssertionError("unexpected streamed command " + spec.command());
            long count = 0, bytes = 0;
            for (String line : lines) {
                try { sink.line(line); } catch (IOException aborted) {
                    return new StreamedInteractiveResult(Kind.SINK_ABORTED, count, bytes, 0);
                }
                count++; bytes += line.length() + 1L;
            }
            return new StreamedInteractiveResult(endings.getOrDefault(spec.command(), Kind.COMPLETED), count, bytes, 2);
        }
        @Override public boolean resyncPrompt(TransportSession session, Duration quiet) {
            resyncs++;
            if (promptAfterResync != null) prompt = promptAfterResync;
            return true;
        }
        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError("fetch"); }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) { throw new AssertionError("xml"); }
        @Override public void disconnect(TransportSession session) { }
    }

    private static ExecResult ok(String output) { return new ExecResult.Completed(output, 0); }

    private BundleHarness harness(Path dir) { return new BundleHarness(dir, "cisco_asa", "gateway", "ssh_exec", null); }

    private void run(BundleHarness h, BundleHarness.Store store, Shell shell) {
        new AsaSupportBundleJobExecutor(h.leases, h.attempts, h.devices, shell, h.gates(), store, h.artefacts,
                BundleHarness.fingerprint(), BundleHarness.clock()).execute("job-1", 1, "device-1", "192.0.2.1", 22, "credential-ref");
    }

    private static Shell script() {
        Shell s = new Shell();
        s.answers.put("show cpu usage", ok("CPU utilization for 5 seconds = 4%; 1 minute: 3%; 5 minutes: 2%\n"));
        s.streams.put("show crashinfo", List.of("Crashinfo for module 0", "enable password synthetic-secret-1", "end of crashinfo"));
        s.streams.put("show tech-support", List.of("------------------ show clock ------------------",
                "snmp-server community synthetic-community-9", "interface GigabitEthernet0/0", " ip address 192.0.2.10 255.255.255.0"));
        return s;
    }

    @Test
    void sendsOnlyTheFourApprovedLiteralsAndNeverTerminalPager0(@TempDir Path dir) throws Exception {
        var h = harness(dir);
        var shell = script();
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo", "asa_show_tech");
        run(h, store, shell);
        assertEquals(List.of("show cpu usage", "show crashinfo", "show tech-support"), shell.commands);
        assertEquals(List.of("asa_sb_show_cpu_usage", "asa_sb_show_crashinfo", "asa_sb_show_tech_support"), h.gateIds);
        for (String c : shell.commands) assertFalse(c.contains("pager"), c);
        assertEquals(List.of("<--- More --->", "<--- More --->"), shell.pagers);
        assertEquals("COMPLETED", store.state("asa_crashinfo"));
        assertEquals("COMPLETED", store.state("asa_show_tech"));
        assertEquals("COMPLETED", store.requestState);
        assertEquals("COMPLETED", h.finalState);
        assertEquals("items/asa_crashinfo/crashinfo.txt", store.entryNames.get(store.id("asa_crashinfo")));
        assertEquals("support_bundle", store.manifests.get(store.id("asa_show_tech")).artefactClass());
    }

    @Test
    void everyLineIsRedactedBeforeItIsStored(@TempDir Path dir) throws Exception {
        var h = harness(dir);
        var shell = script();
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo", "asa_show_tech");
        run(h, store, shell);
        String crash = new String(h.read(store.manifests.get(store.id("asa_crashinfo"))), StandardCharsets.UTF_8);
        String tech = new String(h.read(store.manifests.get(store.id("asa_show_tech"))), StandardCharsets.UTF_8);
        assertFalse(crash.contains("synthetic-secret-1"), crash);
        assertFalse(tech.contains("synthetic-community-9"), tech);
        assertTrue(crash.contains("Crashinfo for module 0\n"));
        assertTrue(tech.contains("interface GigabitEthernet0/0\n ip address 192.0.2.10 255.255.255.0\n"));
    }

    @Test
    void aPrivateKeyBlockIsRedactedAcrossLines(@TempDir Path dir) throws Exception {
        var h = harness(dir);
        var shell = script();
        shell.streams.put("show tech-support", List.of("crypto ca trustpoint T", "-----BEGIN RSA PRIVATE KEY-----",
                "MIIBOgIBAAJBAKj34GkxFhD90vcNLYLInFEX6Ppy1tPf9Cnzj4p4WGeKLs1Pt8Q", "-----END RSA PRIVATE KEY-----", "quit"));
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_show_tech");
        run(h, store, shell);
        String tech = new String(h.read(store.manifests.get(store.id("asa_show_tech"))), StandardCharsets.UTF_8);
        assertFalse(tech.contains("MIIBOgIBAAJBAKj34"), tech);
        assertTrue(tech.contains("quit"));
    }

    @Test
    void aBusyCpuRefusesTheTechSupportReportButNotTheCrashInfo(@TempDir Path dir) throws Exception {
        var h = harness(dir);
        var shell = script();
        shell.answers.put("show cpu usage", ok("CPU utilization for 5 seconds = 91%; 1 minute: 40%; 5 minutes: 20%\n"));
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo", "asa_show_tech");
        run(h, store, shell);
        assertEquals("COMPLETED", store.state("asa_crashinfo"));
        assertEquals("REFUSED_PRECHECK", store.state("asa_show_tech"));
        assertEquals("CPU_OVER_LIMIT", store.code("asa_show_tech"));
        assertEquals("CPU 91% exceeds 80% limit", store.details.get(store.id("asa_show_tech")));
        assertFalse(shell.commands.contains("show tech-support"));
        assertEquals("PARTIAL", store.requestState);
    }

    @Test
    void anUnreadableCpuRefusesTheTechSupportReport(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        shell.answers.put("show cpu usage", ok("something else\n"));
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_show_tech");
        run(h, store, shell);
        assertEquals("PRECHECK_UNKNOWN", store.code("asa_show_tech"));
        assertFalse(shell.commands.contains("show tech-support"));
    }

    @Test
    void multipleContextsMoveToTheSystemContextOnceAndLearnThePromptAgain(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        shell.prompt = "asa-tango-04/admin#";
        shell.promptAfterResync = "asa-tango-04#";
        shell.answers.put("changeto system", new ExecResult.ChannelFailed("empty output"));
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo", "asa_show_tech");
        run(h, store, shell);
        assertEquals(List.of("show cpu usage", "changeto system", "show crashinfo", "show tech-support"), shell.commands);
        assertEquals(1, shell.resyncs);
        assertTrue(h.gateIds.contains("asa_sb_changeto_system"));
        assertTrue(store.precheck.contains("\"asa_multiple_context\":true"), store.precheck);
    }

    @Test
    void aSingleContextNeverSendsChangeto(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        run(h, store, shell);
        assertFalse(shell.commands.contains("changeto system"));
        assertEquals(0, shell.resyncs);
    }

    @Test
    void theFailoverWordsOfThePromptAreNotAContextName(@TempDir Path dir) {
        assertTrue(AsaSupportBundleJobExecutor.multipleContext("asa-tango-04/admin#"));
        assertTrue(AsaSupportBundleJobExecutor.multipleContext("asa-tango-04/ctx-a/act#"));
        assertFalse(AsaSupportBundleJobExecutor.multipleContext("asa-tango-04#"));
        assertFalse(AsaSupportBundleJobExecutor.multipleContext("asa-tango-04/pri/act#"));
        assertFalse(AsaSupportBundleJobExecutor.multipleContext("asa-tango-04/stby#"));
        assertFalse(AsaSupportBundleJobExecutor.multipleContext(null));
        var h = harness(dir);
        var shell = script();
        shell.prompt = "asa-tango-04/pri/act#";
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        run(h, store, shell);
        assertFalse(shell.commands.contains("changeto system"), "a single context device never gets changeto");
    }

    @Test
    void aLoginThatIsNotPrivilegedIsRefusedWithoutAnyCapture(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        shell.prompt = "asa-tango-04>";
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo", "asa_show_tech");
        run(h, store, shell);
        assertEquals("PRIVILEGE_REQUIRED", store.code("asa_crashinfo"));
        assertEquals("PRIVILEGE_REQUIRED", store.code("asa_show_tech"));
        assertEquals(List.of("show cpu usage"), shell.commands);
        assertEquals("FAILED", store.requestState);
    }

    @Test
    void anInvalidInputAnswerIsUnsupportedAndNothingIsStored(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        shell.endings.put("show crashinfo", Kind.CLI_ERROR);
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        run(h, store, shell);
        assertEquals("UNSUPPORTED", store.state("asa_crashinfo"));
        assertTrue(store.manifests.isEmpty());
    }

    @Test
    void aSessionThatDropsAfterFourKiBKeepsWhatArrivedAsPartialAndEndsTheRun(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 100; i++) big.add("interface Port-channel" + i + " description synthetic line number padding padding");
        shell.streams.put("show crashinfo", big);
        shell.endings.put("show crashinfo", Kind.SESSION_LOST);
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo", "asa_show_tech");
        run(h, store, shell);
        assertEquals("PARTIAL", store.state("asa_crashinfo"));
        assertEquals("SESSION_LOST", store.code("asa_crashinfo"));
        assertEquals(1, store.manifests.size());
        assertEquals("SESSION_CLOSED_EARLIER", store.code("asa_show_tech"));
        assertFalse(shell.commands.contains("show tech-support"), "no command after the session ended");
        assertEquals("PARTIAL", store.requestState);
    }

    @Test
    void aSessionThatDropsBeforeFourKiBFailsTheItemAndStoresNothing(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        shell.endings.put("show crashinfo", Kind.SESSION_LOST);
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        run(h, store, shell);
        assertEquals("FAILED", store.state("asa_crashinfo"));
        assertEquals("SESSION_LOST", store.code("asa_crashinfo"));
        assertTrue(store.manifests.isEmpty());
        assertEquals("FAILED", store.requestState);
    }

    @Test
    void aDeadlineIsTimedOutAndNothingIsStored(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        shell.endings.put("show tech-support", Kind.TIMED_OUT);
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_show_tech");
        run(h, store, shell);
        assertEquals("TIMED_OUT", store.state("asa_show_tech"));
        assertTrue(store.manifests.isEmpty());
    }

    @Test
    void aCaptureOverTheItemBoundIsTooLargeAndNothingIsStored(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        String big = "lorem ipsum ".repeat(5_000);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1200; i++) lines.add(big);
        shell.streams.put("show crashinfo", lines);
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        run(h, store, shell);
        assertEquals("TOO_LARGE", store.state("asa_crashinfo"));
        assertTrue(store.manifests.isEmpty());
    }

    @Test
    void aGateThatIsNotSignedOffRejectsTheJobBeforeAnyContact(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        h.rows = h.rows.stream().filter(r -> !r.gateId().equals("asa_sb_show_tech_support")).toList();
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        run(h, store, shell);
        assertEquals("REJECTED", h.finalState);
        assertTrue(shell.commands.isEmpty());
    }

    @Test
    void aFirewallCheckPointOrWrongRoleDeviceIsRejected(@TempDir Path dir) {
        var h = new BundleHarness(dir, "cisco_asa", "management_server", "ssh_exec", null);
        var shell = script();
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        run(h, store, shell);
        assertEquals("REJECTED", h.finalState);
        assertTrue(shell.commands.isEmpty());
    }

    @Test
    void connectionFailureFailsEveryItemWithAClosedCode(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        shell.connectFails = true;
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo", "asa_show_tech");
        run(h, store, shell);
        assertEquals("AUTHENTICATION_FAILED", store.code("asa_crashinfo"));
        assertEquals("FAILED", store.requestState);
        assertEquals("FAILED", h.finalState);
    }

    @Test
    void aCancelledRequestRunsNothing(@TempDir Path dir) {
        var h = harness(dir);
        var shell = script();
        var store = new BundleHarness.Store("cisco_asa", "cisco_asa_firewall", "asa_crashinfo");
        store.queued = false;
        run(h, store, shell);
        assertTrue(shell.commands.isEmpty());
    }
}
