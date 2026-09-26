package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaExecutor;
import com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaPlan;
import com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGateExecutor;
import com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGatePlan;
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

class VendorHaRoleTest {
    private static final Target TARGET = new Target("192.0.2.10", 22);

    private static final class ScriptedTransport implements DeviceTransport {
        final Map<String, String> answers;
        final List<String> commands = new ArrayList<>();

        ScriptedTransport(Map<String, String> answers) {
            this.answers = answers;
        }

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return new ConnectResult.Authenticated(() -> "synthetic-session");
        }

        @Override public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            commands.add(spec.command());
            return new ExecResult.Completed(answers.getOrDefault(spec.command(), ""), 0);
        }

        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override public void disconnect(TransportSession session) { }
    }

    @Test
    void asaInventoryAndConfirmCarryTheObservedRole() {
        for (String state : List.of("Active", "Standby Ready", "")) {
            String failover = state.isEmpty() ? "Failover Off\n" : "This host: Secondary - " + state + "\n";
            ScriptedTransport transport = new ScriptedTransport(Map.of(
                    CiscoAsaPlan.SHOW_CURPRIV, "Current privilege level : 15\n",
                    CiscoAsaPlan.SHOW_VERSION, "Cisco Adaptive Security Appliance Software Version 9.22(2)14\n"
                            + "FW-TANGO-04 up 1 day\nHardware: FPR4K-SM-44S, memory\n",
                    CiscoAsaPlan.SHOW_FAILOVER_THIS_HOST, failover));
            CiscoAsaExecutor executor = new CiscoAsaExecutor(transport, null);
            Optional<String> expected = state.isEmpty() ? Optional.empty()
                    : Optional.of(state.startsWith("Active") ? "active" : "standby");
            var confirm = (HttpsVendorExecutor.ConfirmOutcome.Confirmed) executor.confirm(TARGET, "synthetic-ref");
            var inventory = (HttpsVendorExecutor.InventoryOutcome.Completed) executor.inventory(TARGET, "synthetic-ref");
            assertEquals(expected, confirm.identity().haRole());
            assertEquals(expected, inventory.identity().haRole());
            assertEquals(2, transport.commands.stream().filter(CiscoAsaPlan.SHOW_FAILOVER_THIS_HOST::equals).count());
        }
    }

    @Test
    void fortiGateReadsHaStatusInEachExistingShell() {
        String status = "Version: FortiGate-60F v7.2.8,build1639\nSerial-Number: SYNTH00001\nHostname: FW-TANGO-04\n";
        String ha = "HA Health Status: OK\nMode: HA A-P\nPrimary: FW-TANGO-04, SYNTH00001, HA cluster index = 0\n";
        ScriptedTransport transport = new ScriptedTransport(Map.of(
                FortiGatePlan.GET_SYSTEM_STATUS, status, FortiGatePlan.GET_SYSTEM_HA_STATUS, ha));
        FortiGateExecutor executor = new FortiGateExecutor(transport, null);
        var confirm = (HttpsVendorExecutor.ConfirmOutcome.Confirmed) executor.confirm(TARGET, "synthetic-ref");
        var inventory = (HttpsVendorExecutor.InventoryOutcome.Completed) executor.inventory(TARGET, "synthetic-ref");
        assertEquals(Optional.of("primary"), confirm.identity().haRole());
        assertEquals(Optional.of("primary"), inventory.identity().haRole());
        assertEquals(2, transport.commands.stream().filter(FortiGatePlan.GET_SYSTEM_HA_STATUS::equals).count());
        assertTrue(transport.commands.contains(FortiGatePlan.GET_SYSTEM_STATUS));
    }
}
