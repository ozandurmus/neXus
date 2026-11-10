package com.securityexpert.nexus.ui2.worker.backup.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

/** Lifecycle read, group A, Cisco ASA (PO 2026-11-10): {@code show license all} in the inventory path only. */
class LifecycleReadsAsaTest {

    private static final String DEVICE = "dev-1";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

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
            if (spec.command().equals(CiscoAsaPlan.SHOW_LICENSE_ALL)) {
                if (licenseThrows) throw new IllegalStateException("synthetic");
                if (licenseTimesOut) return new ExecResult.TimedOut(false);
            }
            String answer = answers.get(spec.command());
            if (answer == null) throw new AssertionError("unexpected command " + spec.command());
            return new ExecResult.Completed(answer, 0);
        }

        public void disconnect(TransportSession s) { }
        public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { throw new AssertionError("unexpected exec"); }
        public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { throw new AssertionError("unexpected fetch"); }
        public XmlApiResult xmlApiCall(ApiTarget t, XmlApiSpec s, Duration d) { throw new AssertionError("unexpected API"); }
    }

    private static LifecycleReadSwitch on() {
        return LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS", "asa_show_license_all", "UI2_LIFECYCLE_READ_DEVICES", DEVICE)::get);
    }

    private static String outcome(CiscoAsaExecutor executor, String deviceId) {
        var r = executor.inventory(new Target("192.0.2.10", 22), "cred", deviceId);
        assertTrue(r instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(r));
        return r.toString().replaceAll("(?i)" + UUID_RE, "ID");
    }

    @Test
    void switchOffOrAnotherDeviceSendsTheSameCommandsAsBefore() {
        Shell off = new Shell();
        String offOutcome = outcome(new CiscoAsaExecutor(off, null), DEVICE);
        Shell otherDevice = new Shell();
        outcome(new CiscoAsaExecutor(otherDevice, null).withLifecycleReads(on()), "dev-2");
        Shell legacy = new Shell();
        var legacyResult = new CiscoAsaExecutor(legacy, null).withLifecycleReads(on()).inventory(new Target("192.0.2.10", 22), "cred");
        assertTrue(legacyResult instanceof HttpsVendorExecutor.InventoryOutcome.Completed);
        assertEquals(off.commands, otherDevice.commands);
        assertEquals(off.commands, legacy.commands, "no device id: nothing is sent unless every device is listed");
        assertFalse(off.commands.contains(CiscoAsaPlan.SHOW_LICENSE_ALL));
        assertEquals(CiscoAsaPlan.SHOW_MODE, off.commands.get(off.commands.size() - 1));
        assertTrue(offOutcome.contains("Completed"));
    }

    @Test
    void switchOnSendsShowLicenseAllLastInTheSameSessionAndKeepsTheOutcome() {
        Shell off = new Shell();
        String offOutcome = outcome(new CiscoAsaExecutor(off, null), DEVICE);
        Shell shell = new Shell();
        shell.answers.put(CiscoAsaPlan.SHOW_LICENSE_ALL, "Smart Licensing Status\nRegistration:\n  Status: REGISTERED\n  Smart Account: Acme" + "Holdings\n"
                + "  Export-Controlled Functionality: Allowed\nUDI: PID:FPR4112,SN:SYNTH0001\n");
        List<String> lines = new ArrayList<>();
        String onOutcome = outcome(new CiscoAsaExecutor(shell, null).withLifecycleReads(on()).withLifecycleSink(lines::add), DEVICE);
        assertEquals(offOutcome, onOutcome);
        assertEquals(off.commands, shell.commands.subList(0, off.commands.size()));
        assertEquals(List.of(CiscoAsaPlan.SHOW_LICENSE_ALL), shell.commands.subList(off.commands.size(), shell.commands.size()));
        assertEquals(Duration.ofSeconds(60), shell.timeouts.get(shell.timeouts.size() - 1));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("[LIFECYCLE_MEASURE] kind=asa_show_license_all device=dev-1 outcome=OK "), lines.get(0));
        assertTrue(lines.get(0).endsWith(" mode=single"), lines.get(0));
        assertFalse(lines.get(0).contains("Acme") || lines.get(0).contains("SYNTH0001") || lines.get(0).contains("192.0.2"), lines.get(0));
    }

    @Test
    void aRefusedThrowingOrTimedOutReadLeavesTheOutcomeUnchanged() {
        String offOutcome = outcome(new CiscoAsaExecutor(new Shell(), null), DEVICE);
        Shell refused = new Shell();
        refused.answers.put(CiscoAsaPlan.SHOW_LICENSE_ALL, "ERROR: % Invalid input detected at '^' marker.");
        Shell threw = new Shell();
        threw.licenseThrows = true;
        Shell timedOut = new Shell();
        timedOut.licenseTimesOut = true;
        Shell standby = new Shell();
        standby.answers.put(CiscoAsaPlan.SHOW_LICENSE_ALL, "Not applicable in standby state\n");
        List<String> lines = new ArrayList<>();
        for (Shell s : List.of(refused, threw, timedOut, standby)) {
            assertEquals(offOutcome, outcome(new CiscoAsaExecutor(s, null).withLifecycleReads(on()).withLifecycleSink(lines::add), DEVICE));
            assertEquals(CiscoAsaPlan.SHOW_LICENSE_ALL, s.commands.get(s.commands.size() - 1));
        }
        assertTrue(lines.get(0).contains("outcome=ERROR:CliRefusal"), lines.get(0));
        assertTrue(lines.get(1).contains("outcome=ERROR:IllegalStateException"), lines.get(1));
        assertTrue(lines.get(2).contains("outcome=ERROR:TimedOut"), lines.get(2));
        assertTrue(lines.get(3).contains("outcome=OK") && lines.get(3).contains("shape=Not applicable in standby state"), lines.get(3));
    }
}
