package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/**
 * Lifecycle reads, group A, Check Point (PO 2026-11-10): with the switch off nothing new is sent; with it on each form
 * sends exactly its gate literal after every other read, and no outcome of the new read changes the inventory result.
 */
class LifecycleReadsCheckPointTest {

    private static final String DEVICE = "dev-1";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static LifecycleReadSwitch all() {
        return LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS",
                "cp_cplic_print,cp_cplp_list,cp_spark_show_license", "UI2_LIFECYCLE_READ_DEVICES", DEVICE)::get);
    }

    /** Wraps the scripted transport: logs what it is sent and can answer chosen commands with a timeout. */
    private static final class Spy implements DeviceTransport {
        final ScriptedCheckPointInventoryTransport inner;
        final Set<String> timeOut;
        final List<String> sent = new ArrayList<>();
        final Map<String, Duration> timeouts = new HashMap<>();

        Spy(ScriptedCheckPointInventoryTransport inner, Set<String> timeOut) {
            this.inner = inner;
            this.timeOut = timeOut;
        }

        @Override public ConnectResult connect(ConnectionTarget t, ConnectSpec s, Duration d) { return inner.connect(t, s, d); }

        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            sent.add(spec.command());
            timeouts.put(spec.command(), timeout);
            return timeOut.contains(spec.command()) ? new ExecResult.TimedOut(false) : inner.exec(session, spec, timeout);
        }

        @Override public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            sent.add("(interactive) " + spec.command());
            timeouts.put(spec.command(), timeout);
            return timeOut.contains(spec.command()) ? new ExecResult.TimedOut(false) : inner.execInteractive(session, spec, timeout);
        }

        @Override public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { return inner.fetch(s, f, d); }
        @Override public XmlApiResult xmlApiCall(ApiTarget t, XmlApiSpec s, Duration d) { return inner.xmlApiCall(t, s, d); }
        @Override public void disconnect(TransportSession s) { inner.disconnect(s); }
    }

    private record Run(String result, List<String> sent, List<String> lines, Map<String, Duration> timeouts) {
    }

    private static Run run(Map<String, String> script, InventoryRequest request, LifecycleReadSwitch sw, Set<String> timeOut,
            boolean rejectExec) {
        return run(script, request, sw, timeOut, rejectExec, null);
    }

    private static Run run(Map<String, String> script, InventoryRequest request, LifecycleReadSwitch sw, Set<String> timeOut,
            boolean rejectExec, LifecycleResultSink sink) {
        ScriptedCheckPointInventoryTransport scripted = new ScriptedCheckPointInventoryTransport(script);
        scripted.allowExecInteractiveWithoutRejection();
        Spy spy = new Spy(scripted, timeOut);
        List<String> lines = new ArrayList<>();
        InventoryCapabilityExecutor executor = new InventoryCapabilityExecutor(spy, ref -> { throw new IllegalStateException("not used"); })
                .withLifecycleReads(sw).withLifecycleSink(lines::add);
        if (sink != null) {
            executor.withLifecycleResults(sink);
        }
        InventoryResult result = executor.collect(request, Optional.empty(), false, DEVICE);
        assertTrue(result instanceof InventoryResult.Completed, "expected Completed, got " + result);
        return new Run(result.toString().replaceAll(UUID_RE, "ID"), spy.sent, lines, spy.timeouts);
    }

    // ---------------------------------------------------------------------------------------------- scripts

    private static Map<String, String> gatewayScript() {
        Map<String, String> m = new HashMap<>();
        m.put(InventoryReadPlan.CP_FW_GETIFS, "localhost eth0 192.0.2.10 255.255.255.0\n");
        m.put(InventoryReadPlan.CP_IP_ROUTE_SHOW, "default via 192.0.2.1 dev eth0 proto 7\n");
        m.put(InventoryReadPlan.CP_CPHAPROB_STAT, "Cluster is not enabled\n");
        m.put(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF, "Cluster is not enabled\n");
        m.put(InventoryReadPlan.CP_IP_ADDR_SHOW_STATE_ONLY,
                "1: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n    inet 192.0.2.10/24 scope global eth0\n");
        return m;
    }

    private static Map<String, String> vsxScript() {
        String z = "bash -lc 'vsenv 0 && ";
        Map<String, String> m = new HashMap<>();
        m.put(InventoryReadPlan.CP_VSX_STAT, "ID | Type | Name\n0 | S | VS0\n");
        m.put(z + InventoryReadPlan.CP_FW_GETIFS + "'", "localhost eth0 192.0.2.10 255.255.255.0\n");
        m.put(z + InventoryReadPlan.CP_IP_ROUTE_SHOW + "'", "default via 192.0.2.1 dev eth0 proto 7\n");
        m.put(z + InventoryReadPlan.CP_CPHAPROB_STAT + "'", "Cluster is not enabled\n");
        m.put("bash -lc 'vsenv 0 >/dev/null 2>&1 || true; " + InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF + "'", "");
        m.put(z + InventoryReadPlan.CP_IP_ADDR_SHOW_STATE_ONLY + "'",
                "1: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n    inet 192.0.2.10/24 scope global eth0\n");
        m.put(z + InventoryReadPlan.CP_IP_LINK_SHOW + "'", "");
        return m;
    }

    private static Map<String, String> mdsScript() {
        Map<String, String> m = new HashMap<>();
        m.put(InventoryReadPlan.CP_FW_GETIFS, "");
        m.put("show interfaces all", "");
        m.put("show interfaces table", "");
        m.put("clish -c 'show interfaces all'", "");
        m.put(InventoryReadPlan.CP_IP_ROUTE_SHOW, "default via 192.0.2.1 dev Mgmt proto 7\n");
        m.put(InventoryReadPlan.CP_IP_ADDR_SHOW_STATE_ONLY,
                "2: Mgmt: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n    inet 192.0.2.30/24 brd 192.0.2.255 scope global Mgmt\n");
        return m;
    }

    private static Map<String, String> sparkScript() {
        Map<String, String> m = new HashMap<>();
        m.put(InventoryReadPlan.CP_FW_GETIFS, "localhost LAN1 192.0.2.10 255.255.255.0\n");
        m.put(InventoryReadPlan.CP_IP_ROUTE_SHOW, "default via 192.0.2.1 dev LAN1 proto 7\n");
        m.put(InventoryReadPlan.CP_CPHAPROB_STAT, "Cluster is not enabled\n");
        m.put(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF, "Cluster is not enabled\n");
        m.put(InventoryReadPlan.CP_IP_ADDR_SHOW_STATE_ONLY,
                "1: LAN1: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n    inet 192.0.2.10/24 scope global LAN1\n");
        m.put("show software-version", "This is Check Point's Synthetic Appliance R81.10.10 - Build 123");
        m.put("show diag", "Current image version: R81.10.10\nHW version : H2\nUnit model: V0");
        m.put(InventoryCapabilityExecutor.buildCheckPointBatchCommand(false), "batch format not scripted here on purpose");
        return m;
    }

    private static Map<String, String> clishLandingScript() {
        Map<String, String> m = new HashMap<>();
        m.put(InventoryReadPlan.CP_FW_GETIFS, "");
        m.put("show interfaces all", "Name Addr\nsomething unparsed\n");
        m.put(InventoryReadPlan.CP_IP_ROUTE_SHOW, "default via 192.0.2.1 dev eth0 proto 7\n");
        return m;
    }

    private static InventoryRequest gateway() {
        return InventoryRequest.checkPoint(new ConnectionTarget("ep-1", "gw-a-host", 22), "cred-1", "trust-1");
    }

    private static InventoryRequest mds() {
        return InventoryRequest.checkPointManagementServer(new ConnectionTarget("ep-1", "192.0.2.30", 22), "cred-1", "trust-1", Optional.empty());
    }

    private static InventoryRequest spark() {
        return InventoryRequest.checkPoint(new ConnectionTarget("ep-1", "gw-a-host", 22), "cred-1", "trust-1",
                Optional.of("Check Point 1570/1590 Appliances"));
    }

    private static final String CPLIC = "bash -lc 'cplic print'";
    private static final String CPLP = "bash -lc 'cplp list'";

    private static Map<String, String> with(Map<String, String> base, String... kv) {
        Map<String, String> m = new HashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<String> extra(Run on, Run off) {
        assertEquals(off.sent(), on.sent().subList(0, off.sent().size()), "every earlier command is unchanged and the new ones come last");
        return on.sent().subList(off.sent().size(), on.sent().size());
    }

    // ---------------------------------------------------------------------------------------------- tests

    @Test
    void withTheSwitchUnsetNoNewCommandIsEverSent() {
        for (var scenario : List.of(
                Map.entry(gateway(), gatewayScript()), Map.entry(mds(), mdsScript()), Map.entry(spark(), sparkScript()),
                Map.entry(gateway(), vsxScript()))) {
            Map<String, String> script = with(scenario.getValue(), CPLIC, "x", CPLP, "x", "cplic print", "x", "cplp list", "x",
                    "show license", "x", "bash -lc 'vsenv 0 && cplic print'", "x", "bash -lc 'vsenv 0 && cplp list'", "x");
            Run unset = run(script, scenario.getKey(), LifecycleReadSwitch.from(k -> null), Set.of(), false);
            Run off = run(script, scenario.getKey(), LifecycleReadSwitch.OFF, Set.of(), false);
            assertEquals(off.sent(), unset.sent());
            assertTrue(unset.lines().isEmpty());
            assertTrue(unset.sent().stream().noneMatch(c -> c.contains("cplic") || c.contains("cplp") || c.contains("show license")), unset.sent().toString());
        }
        // a kind enabled for another device, or no kind for this one: still nothing
        Run other = run(with(gatewayScript(), CPLIC, "x"), gateway(), LifecycleReadSwitch.from(Map.of(
                "UI2_LIFECYCLE_READS", "cp_cplic_print", "UI2_LIFECYCLE_READ_DEVICES", "dev-2")::get), Set.of(), false);
        assertTrue(other.sent().stream().noneMatch(c -> c.contains("cplic")));
    }

    @Test
    void aGatewaySendsExactlyTheTwoLoginShellLiteralsLastAndTheResultIsUnchanged() {
        Map<String, String> script = with(gatewayScript(), CPLIC, "Host Expiration Features\n192.0.2.9 04Dec2030 CK-ABCDEF0123 sk12345\n",
                CPLP, "ID Patch Status\n1 abc Installed\n");
        Run off = run(script, gateway(), LifecycleReadSwitch.OFF, Set.of(), false);
        Run on = run(script, gateway(), all(), Set.of(), false);
        assertEquals(List.of(CPLIC, CPLP), extra(on, off));
        assertEquals(Duration.ofSeconds(60), on.timeouts().get(CPLIC));
        assertEquals(Duration.ofSeconds(60), on.timeouts().get(CPLP));
        assertEquals(off.result(), on.result());
        assertEquals(2, on.lines().size());
        assertTrue(on.lines().get(0).startsWith("[LIFECYCLE_MEASURE] kind=cp_cplic_print device=dev-1 outcome=OK "), on.lines().get(0));
        assertTrue(on.lines().get(1).startsWith("[LIFECYCLE_MEASURE] kind=cp_cplp_list device=dev-1 outcome=OK "), on.lines().get(1));
        String all = String.join("\n", on.lines());
        assertFalse(all.contains("192.0.2.9") || all.contains("ABCDEF0123") || all.contains("gw-a-host") || all.contains("12345"), all);
    }

    @Test
    void aFailingEmptyOrMissingNewReadLeavesTheResultByteForByteTheSame() {
        Map<String, String> base = gatewayScript();
        Run off = run(base, gateway(), LifecycleReadSwitch.OFF, Set.of(), false);
        // not scripted: the double throws; empty answer; timeout
        Run threw = run(base, gateway(), all(), Set.of(), false);
        Run empty = run(with(base, CPLIC, "", CPLP, "  \n"), gateway(), all(), Set.of(), false);
        Run timedOut = run(with(base, CPLIC, "x", CPLP, "x"), gateway(), all(), Set.of(CPLIC, CPLP), false);
        for (Run r : List.of(threw, empty, timedOut)) {
            assertEquals(off.result(), r.result());
            assertEquals(List.of(CPLIC, CPLP), extra(r, off));
        }
        assertTrue(threw.lines().get(0).contains("outcome=ERROR:IllegalStateException"), threw.lines().toString());
        assertTrue(empty.lines().get(0).contains("outcome=EMPTY"), empty.lines().toString());
        assertTrue(timedOut.lines().get(0).contains("outcome=ERROR:TimedOut"), timedOut.lines().toString());
    }

    @Test
    void aVsxHostSendsTheVsenvZeroForms() {
        Map<String, String> script = with(vsxScript(), "bash -lc 'vsenv 0 && cplic print'", "Host Expiration\n", "bash -lc 'vsenv 0 && cplp list'", "ID Patch\n");
        Run off = run(script, gateway(), LifecycleReadSwitch.OFF, Set.of(), false);
        Run on = run(script, gateway(), all(), Set.of(), false);
        assertEquals(List.of("bash -lc 'vsenv 0 && cplic print'", "bash -lc 'vsenv 0 && cplp list'"), extra(on, off));
        assertEquals(off.result(), on.result());
    }

    @Test
    void aManagementServerTypesTheBareLiteralsInTheInteractiveExpertShell() {
        Map<String, String> script = with(mdsScript(), "cplic print", "Host Expiration\n", "cplp list", "ID Patch\n");
        Run off = run(script, mds(), LifecycleReadSwitch.OFF, Set.of(), false);
        Run on = run(script, mds(), all(), Set.of(), false);
        assertEquals(List.of("(interactive) cplic print", "(interactive) cplp list"), extra(on, off));
        assertEquals(Duration.ofSeconds(60), on.timeouts().get("cplic print"));
        assertEquals(off.result(), on.result());
        assertTrue(on.lines().stream().anyMatch(l -> l.contains("kind=cp_spark_show_license") && l.contains("SKIPPED:management_server")), on.lines().toString());
    }

    @Test
    void aSparkIdentifiedInThisRunGetsOnlyShowLicenseInteractively() {
        Map<String, String> script = with(sparkScript(), "show license", "Host Expiration Features\n", CPLIC, "x", "cplic print", "x");
        Run off = run(script, spark(), LifecycleReadSwitch.OFF, Set.of(), false);
        Run on = run(script, spark(), all(), Set.of(), false);
        assertEquals(List.of("(interactive) show license"), extra(on, off));
        assertEquals(Duration.ofSeconds(60), on.timeouts().get("show license"));
        assertEquals(off.result(), on.result());
        assertTrue(on.sent().stream().noneMatch(c -> c.contains("cplic") || c.contains("cplp")));
    }

    @Test
    void aClishLandingThatIsNotASparkGetsNoneOfThem() {
        Run on = run(clishLandingScript(), gateway(), all(), Set.of(), false);
        assertTrue(on.sent().stream().noneMatch(c -> c.contains("cplic") || c.contains("cplp") || c.contains("show license")), on.sent().toString());
        assertEquals(3, on.lines().size());
        assertTrue(on.lines().stream().allMatch(l -> l.contains("outcome=SKIPPED:no_approved_form_for_landing_shell")), on.lines().toString());
        Run off = run(clishLandingScript(), gateway(), LifecycleReadSwitch.OFF, Set.of(), false);
        assertEquals(off.result(), on.result());
    }

    // ------------------------------------------------------------------------------------ result sink (storage contract s.3)

    @Test
    void theResultSinkIsNeverCalledWithTheSwitchOffOrForAnotherDevice() {
        RecordingLifecycleSink sink = new RecordingLifecycleSink();
        Map<String, String> script = with(gatewayScript(), CPLIC, "x", CPLP, "x");
        run(script, gateway(), LifecycleReadSwitch.OFF, Set.of(), false, sink);
        run(script, gateway(), LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS", "cp_cplic_print,cp_cplp_list",
                "UI2_LIFECYCLE_READ_DEVICES", "dev-2")::get), Set.of(), false, sink);
        assertTrue(sink.calls.isEmpty(), sink.calls.toString());
    }

    @Test
    void theResultSinkGetsEachAnswerOnceWithTheRightKindAndDevice() {
        String cplic = "Host Expiration Features\n192.0.2.9 never CPSB-FW CK-ABCDEF0123\n";
        String cplp = "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\n";
        Map<String, String> script = with(gatewayScript(), CPLIC, cplic, CPLP, cplp);
        Run plain = run(script, gateway(), all(), Set.of(), false);
        RecordingLifecycleSink sink = new RecordingLifecycleSink();
        Run withSink = run(script, gateway(), all(), Set.of(), false, sink);
        assertEquals(plain.result(), withSink.result());
        assertEquals(plain.lines(), withSink.lines(), "the shape lines are exactly as before");
        assertEquals(2, sink.calls.size(), sink.calls.toString());
        assertEquals(new RecordingLifecycleSink.Call("ANSWER", LifecycleReadSwitch.Kind.CP_CPLIC_PRINT, DEVICE, null, cplic, "ANSWER", null),
                sink.calls.get(0));
        assertEquals(new RecordingLifecycleSink.Call("ANSWER", LifecycleReadSwitch.Kind.CP_CPLP_LIST, DEVICE, null, cplp, "ANSWER", null),
                sink.calls.get(1));
    }

    @Test
    void aThrowingResultSinkChangesNothing() {
        Map<String, String> script = with(gatewayScript(), CPLIC, "x", CPLP, "y");
        Run plain = run(script, gateway(), all(), Set.of(), false);
        RecordingLifecycleSink sink = RecordingLifecycleSink.throwing();
        Run thrown = run(script, gateway(), all(), Set.of(), false, sink);
        assertEquals(plain.result(), thrown.result());
        assertEquals(plain.lines(), thrown.lines());
        assertEquals(plain.sent(), thrown.sent());
        assertEquals(2, sink.calls.size(), "the second read is still sent and handed on after the first sink call threw");
    }

    @Test
    void timeoutThrowAndSkipPathsCallNotCollectedWithTheirTokens() {
        RecordingLifecycleSink timedOut = new RecordingLifecycleSink();
        run(with(gatewayScript(), CPLIC, "x", CPLP, "x"), gateway(), all(), Set.of(CPLIC, CPLP), false, timedOut);
        assertEquals(List.of(new RecordingLifecycleSink.Call("NOT_COLLECTED", LifecycleReadSwitch.Kind.CP_CPLIC_PRINT, DEVICE, null, null, "FAILED", "TimedOut"),
                new RecordingLifecycleSink.Call("NOT_COLLECTED", LifecycleReadSwitch.Kind.CP_CPLP_LIST, DEVICE, null, null, "FAILED", "TimedOut")), timedOut.calls);

        RecordingLifecycleSink threw = new RecordingLifecycleSink();
        run(gatewayScript(), gateway(), all(), Set.of(), false, threw);
        assertEquals(2, threw.calls.size());
        assertTrue(threw.calls.stream().allMatch(c -> "FAILED".equals(c.outcome()) && "IllegalStateException".equals(c.reason())), threw.calls.toString());

        RecordingLifecycleSink skipped = new RecordingLifecycleSink();
        run(with(mdsScript(), "cplic print", "x", "cplp list", "x"), mds(), all(), Set.of(), false, skipped);
        assertEquals(new RecordingLifecycleSink.Call("NOT_COLLECTED", LifecycleReadSwitch.Kind.CP_SPARK_SHOW_LICENSE, DEVICE, null, null, "SKIPPED", "management_server"),
                skipped.calls.get(0));
        assertEquals(3, skipped.calls.size());

        RecordingLifecycleSink landing = new RecordingLifecycleSink();
        run(clishLandingScript(), gateway(), all(), Set.of(), false, landing);
        assertEquals(3, landing.calls.size());
        assertTrue(landing.calls.stream().allMatch(c -> "SKIPPED".equals(c.outcome()) && "no_approved_form_for_landing_shell".equals(c.reason())));
    }
}
