package com.securityexpert.nexus.ui2.worker.backup.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
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
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

/** The one-time ASA failover measurement (PO 2026-11-13, "tek çift, tek sefer"): window-gated, shape-only. */
class AsaFailoverMeasureTest {

    private static final String DEVICE = "dev-1";
    private static final Instant FROM = Instant.parse("2026-11-13T07:30:00Z");
    private static final Instant UNTIL = Instant.parse("2026-11-13T07:50:00Z");
    private static final String SETTING = "dev-1,dev-2@2026-11-13T07:30:00Z/2026-11-13T07:50:00Z";

    static final String SHOW_FAILOVER = String.join("\n",
            "Failover On",
            "Failover unit Primary",
            "Failover LAN Interface: folink GigabitEthernet0/3 (up)",
            "Version: Ours 9.18(4)50, Mate 9.18(4)50",
            "Serial Number: Ours SYNTH0001, Mate SYNTH0002",
            "Last Failover at: 10:11:12 UTC Nov 1 2026",
            "        This host: Primary - Active",
            "                Interface outside (192.0.2.10): Normal (Monitored)",
            "                Interface inside (198.51.100.1): Normal (Monitored)",
            "        Other host: Secondary - Standby Ready",
            "                Interface outside (192.0.2.11): Normal (Monitored)",
            "Stateful Failover Logical Update Statistics",
            "        Link : folink GigabitEthernet0/3 (up)",
            "        Stateful Obj    xmit       xerr       rcv        rerr",
            "        General         1234       0          567        0",
            "        mac 0011.2233.4455 fw-site-a");
    static final String STATE = "               State          Last Failure Reason      Date/Time\nThis host  -   Primary\n"
            + "               Active         None\nOther host -   Secondary\n               Standby Ready  None\n"
            + "====Configuration State===\n        Sync Done\n====Communication State===\n        Mac set\n";
    static final String HISTORY = "==========================================================================\n"
            + "From State                 To State                   Reason\n"
            + "10:11:12 UTC Nov 1 2026\nStandby Ready              Just Active                Other unit wants me Active\n";
    static final String CONN = "4321 in use, 98765 most used\n";

    private static final class Shell implements DeviceTransport {
        final List<String> commands = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        final Map<String, String> answers = new HashMap<>();
        boolean licenseThrows;
        boolean licenseTimesOut;

        Shell() {
            answers.put("terminal pager 0", "");
            answers.put(CiscoAsaPlan.SHOW_CURPRIV, "Current privilege level : 15");
            answers.put(CiscoAsaPlan.SHOW_VERSION, "Adaptive Security Appliance Software Version 9.22(1)\nHardware:   FPR4112, 1 GB\n"
                    + "Serial Number: SYNTH0001\nfw-a up 3 days 2 hours\n");
            answers.put(CiscoAsaPlan.SHOW_IP_ADDRESS, "System IP Addresses:\nInterface Name IP address Subnet mask Method\n"
                    + "Ethernet1/1 outside 192.0.2.10 255.255.255.0 CONFIG\n");
            answers.put(CiscoAsaPlan.SHOW_INTERFACE_IP_BRIEF, "Interface IP-Address OK? Method Status Protocol\nEthernet1/1 192.0.2.10 YES CONFIG up up\n");
            answers.put(CiscoAsaPlan.SHOW_ROUTE, "S*  0.0.0.0 0.0.0.0 [1/0] via 192.0.2.1, outside\n");
            answers.put(CiscoAsaPlan.SHOW_FAILOVER_THIS_HOST, "This host: Primary - Active\n");
            answers.put(CiscoAsaPlan.SHOW_MODE, "Security context mode: single\n");
        }

        public ConnectResult connect(ConnectionTarget t, ConnectSpec s, Duration d) {
            return new ConnectResult.Authenticated(() -> "s-1");
        }

        public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            commands.add(spec.command());
            timeouts.add(timeout);
            if (spec.command().equals("show failover history") && licenseTimesOut) return new ExecResult.TimedOut(false);
            if (spec.command().equals("show failover state") && licenseThrows) throw new IllegalStateException("synthetic");
            String answer = answers.get(spec.command());
            if (answer == null) throw new AssertionError("unexpected command " + spec.command());
            return new ExecResult.Completed(answer, 0);
        }

        public void disconnect(TransportSession s) { }
        public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { throw new AssertionError("unexpected exec"); }
        public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { throw new AssertionError("unexpected fetch"); }
        public XmlApiResult xmlApiCall(ApiTarget t, XmlApiSpec s, Duration d) { throw new AssertionError("unexpected API"); }
    }

    private static Shell measured() {
        Shell s = new Shell();
        s.answers.put("show failover", SHOW_FAILOVER);
        s.answers.put("show failover state", STATE);
        s.answers.put("show failover history", HISTORY);
        s.answers.put("show conn count", CONN);
        return s;
    }

    private static List<String> run(Shell shell, AsaFailoverMeasure measure, String deviceId) {
        List<String> lines = new ArrayList<>();
        var r = new CiscoAsaExecutor(shell, null).withFailoverMeasure(measure).withMeasureSink(lines::add)
                .inventory(new Target("192.0.2.10", 22), "cred", deviceId);
        assertTrue(r instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(r));
        return lines;
    }

    @Test
    void theSettingNeedsTwoAtMostDevicesAndABoundedWindow() {
        assertTrue(AsaFailoverMeasure.parse(null) == AsaFailoverMeasure.OFF);
        assertTrue(AsaFailoverMeasure.parse(" ") == AsaFailoverMeasure.OFF);
        assertTrue(AsaFailoverMeasure.parse("dev-1") == AsaFailoverMeasure.OFF);
        assertTrue(AsaFailoverMeasure.parse("dev-1@2026-11-13T07:30:00Z") == AsaFailoverMeasure.OFF);
        assertTrue(AsaFailoverMeasure.parse("dev-1@2026-11-13T07:30:00Z/2026-11-13T08:31:00Z") == AsaFailoverMeasure.OFF);
        assertTrue(AsaFailoverMeasure.parse("dev-1@2026-11-13T07:30:00Z/2026-11-13T07:30:00Z") == AsaFailoverMeasure.OFF);
        assertTrue(AsaFailoverMeasure.parse("a,b,c@2026-11-13T07:30:00Z/2026-11-13T07:40:00Z") == AsaFailoverMeasure.OFF);
        assertTrue(AsaFailoverMeasure.parse("dev 1@2026-11-13T07:30:00Z/2026-11-13T07:40:00Z") == AsaFailoverMeasure.OFF);
        assertFalse(AsaFailoverMeasure.parse(SETTING) == AsaFailoverMeasure.OFF);
    }

    @Test
    void aDeviceIsClaimedOnceAndOnlyInsideItsWindow() {
        AsaFailoverMeasure m = AsaFailoverMeasure.parse(SETTING);
        assertFalse(m.claim(DEVICE, FROM.minusSeconds(1)));
        assertFalse(m.claim(DEVICE, UNTIL));
        assertFalse(m.claim("dev-3", FROM.plusSeconds(60)));
        assertTrue(m.claim(DEVICE, FROM.plusSeconds(60)));
        assertFalse(m.claim(DEVICE, FROM.plusSeconds(120)));
        assertTrue(m.claim("dev-2", FROM.plusSeconds(120)));
        assertFalse(AsaFailoverMeasure.OFF.claim(DEVICE, FROM.plusSeconds(60)));
    }

    @Test
    void theShapeKeepsVendorWordsAndDropsEveryValue() {
        String all = String.join(" ", AsaFailoverMeasure.shape(SHOW_FAILOVER, 160));
        for (String value : List.of("192.0.2.10", "198.51.100.1", "SYNTH0001", "SYNTH0002", "0011.2233.4455", "folink",
                "outside", "inside", "fw-site-a", "9.18(4)50", "GigabitEthernet", "1234", "567")) {
            assertFalse(all.contains(value), value);
        }
        assertTrue(all.contains("This host: Primary - Active"));
        assertTrue(all.contains("Other host: Secondary - Standby Ready"));
        assertTrue(all.contains("Normal (Monitored)"));
        assertTrue(all.contains("Interface a (<ip>): Normal (Monitored)"));
        assertTrue(all.contains("xmit xerr rcv rerr"));
        assertTrue(all.contains("<mac>"));
        assertEquals(List.of("Failover On", "[more]"), AsaFailoverMeasure.shape(SHOW_FAILOVER, 1));
    }

    @Test
    void connCountGivesTheTwoCountsOnly() {
        long[] n = AsaFailoverMeasure.connCount(CONN).orElseThrow();
        assertEquals(4321L, n[0]);
        assertEquals(98765L, n[1]);
        assertTrue(AsaFailoverMeasure.connCount("something else").isEmpty());
    }

    @Test
    void offOrOutsideTheWindowSendsExactlyTheInventoryCommands() {
        Shell off = measured();
        assertTrue(run(off, AsaFailoverMeasure.OFF, DEVICE).isEmpty());
        assertFalse(off.commands.stream().anyMatch(c -> List.of("show failover", "show failover state", "show failover history",
                "show conn count").contains(c)));
        Shell other = measured();
        assertTrue(run(other, new AsaFailoverMeasure(Set.of("dev-9"), Instant.EPOCH, Instant.parse("2999-01-01T00:00:00Z")), DEVICE).isEmpty());
        assertFalse(other.commands.contains("show failover"));
    }

    @Test
    void insideTheWindowTheFourReadsGoLastOnceAndLogShapesAndCountsOnly() {
        AsaFailoverMeasure m = new AsaFailoverMeasure(Set.of(DEVICE), Instant.EPOCH, Instant.parse("2999-01-01T00:00:00Z"));
        Shell shell = measured();
        List<String> lines = run(shell, m, DEVICE);
        List<String> tail = shell.commands.subList(shell.commands.size() - 4, shell.commands.size());
        assertEquals(List.of("show failover", "show failover state", "show failover history", "show conn count"), tail);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).startsWith("[ASA] MEASURE asa_measure_show_failover: answered lines=16 shape=["));
        assertTrue(lines.get(3).contains("in_use=4321 most_used=98765"));
        String joined = String.join("\n", lines);
        for (String value : List.of("192.0.2", "198.51.100", "SYNTH", "0011.2233", "folink", "fw-site-a", "outside")) {
            assertFalse(joined.contains(value), value);
        }
        // Once per device per worker process.
        Shell again = measured();
        assertTrue(run(again, m, DEVICE).isEmpty());
        assertFalse(again.commands.contains("show failover"));
    }

    @Test
    void aFailedReadIsLoggedAndTheOthersStillRun() {
        AsaFailoverMeasure m = new AsaFailoverMeasure(Set.of(DEVICE), Instant.EPOCH, Instant.parse("2999-01-01T00:00:00Z"));
        Shell shell = measured();
        shell.licenseThrows = true;
        shell.licenseTimesOut = true;
        List<String> lines = run(shell, m, DEVICE);
        assertEquals(4, lines.size());
        assertEquals("[ASA] MEASURE asa_measure_show_failover_state: IllegalStateException", lines.get(1));
        assertEquals("[ASA] MEASURE asa_measure_show_failover_history: TimedOut", lines.get(2));
    }

    @Test
    void aMultipleContextAsaIsNeverMeasured() {
        AsaFailoverMeasure m = new AsaFailoverMeasure(Set.of(DEVICE), Instant.EPOCH, Instant.parse("2999-01-01T00:00:00Z"));
        Shell shell = measured();
        shell.answers.put(CiscoAsaPlan.SHOW_MODE, "Security context mode: multiple\n");
        assertTrue(run(shell, m, DEVICE).isEmpty());
        assertFalse(shell.commands.contains("show failover"));
    }

    @Test
    void everyReadHasItsSignedOffReadGateRowAndIsNeverADiagnosticRead() {
        var rows = com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader.loadFromStream(
                AsaFailoverMeasureTest.class.getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        for (AsaFailoverMeasure.Read read : AsaFailoverMeasure.Read.values()) {
            var row = rows.stream().filter(r -> r.gateId().equals(read.gateId())).findFirst().orElseThrow();
            assertEquals(read.command(), row.canonicalCommandKey());
            assertEquals(read.timeoutS(), row.timeoutS());
            assertEquals("cisco_asa", row.vendor());
            assertEquals("cisco_asa_firewall", row.platformRoleScope());
            assertEquals(com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ, row.actionClass());
            assertEquals(com.securityexpert.nexus.ui2.capability.SignOffState.SIGNED_OFF, row.signOffState());
            assertEquals("none", row.retryRule());
            assertTrue(com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead.DIAGNOSTIC_EXCLUDED.contains(read.gateId()));
        }
    }

    /** The measured pair's clock "HH:MM:SS" was taken for an IPv6 address and shaped as one; it is a clock. */
    @Test void aWallClockIsNotShapedAsAnIpv6Address() {
        assertEquals("Last Failover at: <time> +99 Nov 99 9999", AsaFailoverMeasure.shapeLine("Last Failover at: 10:11:12 +03 Nov 10 2026"));
    }
}
