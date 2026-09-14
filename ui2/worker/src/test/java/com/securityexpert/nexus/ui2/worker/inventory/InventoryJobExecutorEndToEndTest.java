package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;

/**
 * <b>AC-4, the decisive test.</b> An admitted {@code inventory_collect} job
 * is claimed and, over a fake transport (never a real device), records one
 * complete {@link InventoryRun} with the expected per-context interfaces,
 * addresses (roles) and routes -- for a Check Point cluster member with two
 * VSIDs, and separately for a Palo Alto firewall with two vsys.
 */
class InventoryJobExecutorEndToEndTest {

    private static final String JOB_ID = "job-inventory-1";
    private static final long LEASE_EPOCH = 4L;
    private static final String DEVICE_ID = "device-enrolled-1";

    @Test
    void checkPointClusterMemberWithTwoVsidsRecordsOneRunWithThreeContexts() {
        Map<String, String> outputByCommand = Map.of(
                InventoryReadPlan.CP_IP_ADDR_SHOW_V4,
                "1: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n"
                        + "    inet 192.0.2.10/24 brd 192.0.2.255 scope global eth0\n",
                InventoryReadPlan.CP_IP_ADDR_SHOW_V6, "",
                InventoryReadPlan.CP_IP_ROUTE_SHOW,
                "default via 192.0.2.1 dev eth0 proto static\n",
                InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF,
                "Virtual cluster interfaces:\neth0        192.0.2.1\n",
                InventoryReadPlan.CP_CPHAPROB_STAT,
                "1 (local) 192.0.2.10 100% ACTIVE gw-a\n",
                InventoryReadPlan.CP_VSX_STAT,
                "VSID | Type | Name\n0 | VS0 | VS0\n2 | VS | vs-finance\n5 | VS | vs-hr\n",
                "vsenv 2; ip -4 addr show; ip -4 route show",
                "1: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n"
                        + "    inet 203.0.113.2/29 brd 203.0.113.7 scope global eth0\n"
                        + "default via 203.0.113.1 dev eth0 proto static\n",
                "vsenv 2; cphaprob stat", "1 (local) 203.0.113.2 100% ACTIVE gw-a\n",
                "vsenv 5; ip -4 addr show; ip -4 route show",
                "1: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP\n"
                        + "    inet 198.51.100.2/29 brd 198.51.100.7 scope global eth0\n"
                        + "198.51.100.0/29 dev eth0 proto kernel scope link src 198.51.100.2\n",
                "vsenv 5; cphaprob stat", "1 (local) 198.51.100.2 100% ACTIVE gw-a\n");

        ScriptedCheckPointInventoryTransport transport = new ScriptedCheckPointInventoryTransport(outputByCommand);
        InventoryJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new InventoryJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        InventoryJobExecutorFakes.FakeStepAttemptRepository attemptRepo = new InventoryJobExecutorFakes.FakeStepAttemptRepository();
        InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        InventoryJobExecutorFakes.FakeDeviceRepository deviceRepository = new InventoryJobExecutorFakes.FakeDeviceRepository();
        InventoryJobExecutorFakes.FakeDeviceInventoryRepository inventoryRepository =
                new InventoryJobExecutorFakes.FakeDeviceInventoryRepository();

        InventoryCapabilityExecutor capabilityExecutor =
                new InventoryCapabilityExecutor(transport, ref -> { throw new IllegalStateException("not used"); });
        InventoryJobExecutor executor = new InventoryJobExecutor(leaseRepo, attemptRepo, devicePort, deviceRepository,
                inventoryRepository, capabilityExecutor);

        InventoryRequest request =
                InventoryRequest.checkPoint(new ConnectionTarget("ep-1", "gw-a-host", 22), "cred-1", "trust-1");

        JobOutcome outcome = executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request, false);

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertTrue(leaseRepo.transitions.contains("CLAIMED->EXECUTING"));
        assertTrue(leaseRepo.transitions.contains("EXECUTING->COMPLETED"));
        assertTrue(inventoryRepository.recordRunCalled);

        InventoryRun run = inventoryRepository.lastRecordedRun;
        assertEquals(DEVICE_ID, run.deviceId());
        assertEquals(JOB_ID, run.jobId());
        assertEquals(3, run.contexts().size(), "physical + VSID 2 + VSID 5, VS0 never re-entered");
        assertEquals(3, run.contextCount());

        InventoryContext physical = contextNamed(run, InventoryContext.PHYSICAL);
        assertEquals(1, physical.interfaces().size());
        assertEquals(2, physical.interfaces().get(0).addresses().size(), "member address plus the cluster VIP");
        assertTrue(physical.interfaces().get(0).addresses().stream()
                .anyMatch(a -> a.role().equals(InventoryAddress.ROLE_CLUSTER_VIRTUAL) && a.address().equals("192.0.2.1")));
        assertTrue(physical.interfaces().get(0).addresses().stream()
                .anyMatch(a -> a.role().equals(InventoryAddress.ROLE_MEMBER) && a.address().equals("192.0.2.10/24")));
        assertEquals(1, physical.routes().size());

        InventoryContext vsid2 = contextNamed(run, "2");
        assertEquals(1, vsid2.interfaces().size());
        assertEquals("203.0.113.2/29", vsid2.interfaces().get(0).addresses().get(0).address());
        assertEquals(1, vsid2.routes().size());

        InventoryContext vsid5 = contextNamed(run, "5");
        assertEquals("198.51.100.2/29", vsid5.interfaces().get(0).addresses().get(0).address());
        assertEquals(1, vsid5.routes().size());
    }

    @Test
    void paloAltoFirewallWithTwoVsysRecordsOneRunWithTwoContexts() {
        String interfaceXml = "<response><result><ifnet>"
                + "<entry><name>ethernet1/1</name><ip>192.0.2.1/24</ip><vsys>vsys1</vsys><state>up</state></entry>"
                + "<entry><name>ethernet1/2.100</name><ip>198.51.100.10/27</ip><vsys>vsys2</vsys><state>up</state></entry>"
                + "</ifnet></result></response>";
        String routeXml = "<response><result>"
                + "<entry><destination>192.0.2.0/24</destination><nexthop>0.0.0.0</nexthop>"
                + "<interface>ethernet1/1</interface><virtual-router>default</virtual-router><flags>AC</flags></entry>"
                + "<entry><destination>198.51.100.0/27</destination><nexthop>0.0.0.0</nexthop>"
                + "<interface>ethernet1/2.100</interface><virtual-router>VR-DMZ</virtual-router><flags>AC</flags></entry>"
                + "</result></response>";
        Map<String, String> outputByCmd = Map.of(
                InventoryReadPlan.PAN_SHOW_SYSTEM_INFO, "<response><result><system><serial>0011223344</serial></system></result></response>",
                InventoryReadPlan.PAN_SHOW_HA_STATE, "<response><result><enabled>no</enabled></result></response>",
                InventoryReadPlan.PAN_SHOW_INTERFACE_ALL, interfaceXml,
                InventoryReadPlan.PAN_SHOW_ROUTING_ROUTE, routeXml);

        ScriptedPaloAltoInventoryTransport transport = new ScriptedPaloAltoInventoryTransport(outputByCmd);
        InventoryJobExecutorFakes.FakeLeaseRepository leaseRepo =
                new InventoryJobExecutorFakes.FakeLeaseRepository(JOB_ID, LEASE_EPOCH, JobState.CLAIMED);
        InventoryJobExecutorFakes.FakeStepAttemptRepository attemptRepo = new InventoryJobExecutorFakes.FakeStepAttemptRepository();
        InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort devicePort = new InventoryJobExecutorFakes.FakeDeviceEnrollmentReadPort();
        InventoryJobExecutorFakes.FakeDeviceRepository deviceRepository = new InventoryJobExecutorFakes.FakeDeviceRepository();
        InventoryJobExecutorFakes.FakeDeviceInventoryRepository inventoryRepository =
                new InventoryJobExecutorFakes.FakeDeviceInventoryRepository();

        InventoryCapabilityExecutor capabilityExecutor = new InventoryCapabilityExecutor(transport, ref ->
                new com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial("user", "pw".toCharArray()));
        InventoryJobExecutor executor = new InventoryJobExecutor(leaseRepo, attemptRepo, devicePort, deviceRepository,
                inventoryRepository, capabilityExecutor);

        InventoryRequest request = InventoryRequest.paloAlto(new ApiTarget("ep-2", "https://fw.example"), "cred-2");

        JobOutcome outcome = executor.execute(JOB_ID, LEASE_EPOCH, DEVICE_ID, request, false);

        assertTrue(outcome instanceof JobOutcome.Completed, "expected Completed, got " + outcome);
        assertTrue(inventoryRepository.recordRunCalled);

        InventoryRun run = inventoryRepository.lastRecordedRun;
        assertEquals(2, run.contexts().size());

        InventoryContext vsys1 = contextNamed(run, "vsys1");
        assertEquals(1, vsys1.interfaces().size());
        assertEquals("192.0.2.1/24", vsys1.interfaces().get(0).addresses().get(0).address());
        assertEquals(1, vsys1.routes().size());

        InventoryContext vsys2 = contextNamed(run, "vsys2");
        assertEquals("ethernet1/2.100", vsys2.interfaces().get(0).name());
        assertEquals(Optional.of("ethernet1/2"), vsys2.interfaces().get(0).parent());
        assertEquals(1, vsys2.routes().size());
        assertEquals("VR-DMZ", vsys2.routes().get(0).routeTable().orElseThrow());
    }

    private static InventoryContext contextNamed(InventoryRun run, String context) {
        return run.contexts().stream().filter(c -> c.context().equals(context)).findFirst()
                .orElseThrow(() -> new AssertionError("no recorded context named " + context));
    }
}
