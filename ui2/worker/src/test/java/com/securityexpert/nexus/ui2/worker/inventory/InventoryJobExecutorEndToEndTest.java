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
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
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
    void checkPointVsxHostWithTwoVsidsRecordsOneRunWithThreeContexts() {
        Map<String, String> outputByCommand = Map.ofEntries(
                Map.entry(InventoryReadPlan.CP_VSX_STAT,
                        "ID | Type | Name\n0 | S | VS0\n2 | S | vs-finance\n5 | S | vs-hr\n"),
                Map.entry(vsenvZero(InventoryReadPlan.CP_FW_GETIFS), "localhost eth0 192.0.2.10 255.255.255.0\n"),
                Map.entry(vsenvZero(InventoryReadPlan.CP_IP_ROUTE_SHOW), "default via 192.0.2.1 dev eth0 proto 7\n"),
                Map.entry(vsenvZero(InventoryReadPlan.CP_CPHAPROB_STAT), "1 (local) 192.0.2.10 100% ACTIVE gw-a\n"),
                Map.entry(vsenvZeroFaultTolerant(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF),
                        "Virtual cluster interfaces: 1\neth0        192.0.2.1\n"),
                Map.entry("bash -lc 'vsenv 2 && fw getifs && ip -4 route show'",
                        "default via 203.0.113.1 dev eth0 proto 7\n"),
                Map.entry("bash -lc 'vsenv 2 && cphaprob -a if'",
                        "Interface Name:  Status:\neth0        UP\n\nVirtual cluster interfaces: 1\neth0        203.0.113.2\n"),
                Map.entry("bash -lc 'vsenv 2 && cphaprob stat'", "1 (local) 203.0.113.2 100% ACTIVE gw-a\n"),
                Map.entry("bash -lc 'vsenv 5 && fw getifs && ip -4 route show'",
                        "198.51.100.0/29 dev eth0 proto kernel scope link src 198.51.100.2\n"),
                Map.entry("bash -lc 'vsenv 5 && cphaprob -a if'",
                        "Virtual cluster interfaces: 1\neth0        198.51.100.2\n"),
                Map.entry("bash -lc 'vsenv 5 && cphaprob stat'", "1 (local) 198.51.100.2 100% ACTIVE gw-a\n"));

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

        // cp_vsx_interfaces_identical_to_physical: on a genuine cluster member (role != STANDALONE),
        // a VS context's interfaces come from "cphaprob -a if"'s own "Virtual cluster interfaces"
        // section, not "fw getifs" (measured live to return an internal VSX addressing scheme on a
        // cluster member) -- bare address, no netmask. State comes from that same output's earlier
        // "Interface Name: Status:" table when present (vsid 2); an interface absent from that table
        // (vsid 5's fixture carries none) stays unknown rather than guessed.
        InventoryContext vsid2 = contextNamed(run, "2");
        assertEquals(1, vsid2.interfaces().size());
        assertEquals(1, vsid2.interfaces().get(0).addresses().size(), "this VS's own cluster-interface address only");
        assertEquals("203.0.113.2", vsid2.interfaces().get(0).addresses().get(0).address());
        assertEquals(InventoryAddress.ROLE_MEMBER, vsid2.interfaces().get(0).addresses().get(0).role());
        assertEquals(InventoryInterface.STATE_UP, vsid2.interfaces().get(0).state());
        assertEquals(1, vsid2.routes().size());

        InventoryContext vsid5 = contextNamed(run, "5");
        assertEquals(1, vsid5.interfaces().get(0).addresses().size(), "this VS's own cluster-interface address only");
        assertEquals("198.51.100.2", vsid5.interfaces().get(0).addresses().get(0).address());
        assertEquals(InventoryAddress.ROLE_MEMBER, vsid5.interfaces().get(0).addresses().get(0).role());
        assertEquals(InventoryInterface.STATE_UNKNOWN, vsid5.interfaces().get(0).state());
        assertEquals(1, vsid5.routes().size());
    }

    /** cp_vsx_interfaces_identical_to_physical: a standalone (non-clustered) VSX gateway has no
     * ClusterXL, so it keeps using "fw getifs" per VSID -- "cphaprob -a if" is never issued at that
     * level (the fake transport throws on any command outside its scripted set, so this also proves
     * the executor does not call it here). */
    @Test
    void checkPointStandaloneVsxHostUsesFwGetifsPerVsidNeverCphaprob() {
        Map<String, String> outputByCommand = Map.ofEntries(
                Map.entry(InventoryReadPlan.CP_VSX_STAT, "ID | Type | Name\n0 | S | VS0\n3 | S | vs-lab\n"),
                Map.entry(vsenvZero(InventoryReadPlan.CP_FW_GETIFS), "localhost eth0 192.0.2.20 255.255.255.0\n"),
                Map.entry(vsenvZero(InventoryReadPlan.CP_IP_ROUTE_SHOW), "default via 192.0.2.1 dev eth0 proto 7\n"),
                Map.entry(vsenvZero(InventoryReadPlan.CP_CPHAPROB_STAT), "Cluster is not enabled\n"),
                Map.entry(vsenvZeroFaultTolerant(InventoryReadPlan.CP_CPHAPROB_CLUSTER_IF), ""),
                Map.entry("bash -lc 'vsenv 3 && fw getifs && ip -4 route show'",
                        "localhost eth1 203.0.113.5 255.255.255.0\n"
                                + "default via 203.0.113.1 dev eth1 proto 7\n"),
                Map.entry("bash -lc 'vsenv 3 && cphaprob stat'", "Cluster is not enabled\n"));

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
        InventoryRun run = inventoryRepository.lastRecordedRun;
        InventoryContext vsid3 = contextNamed(run, "3");
        assertEquals(1, vsid3.interfaces().size());
        assertEquals("203.0.113.5/24", vsid3.interfaces().get(0).addresses().get(0).address());
        assertEquals(InventoryAddress.ROLE_MEMBER, vsid3.interfaces().get(0).addresses().get(0).role());
    }

    private static String vsenvZero(String read) {
        return "bash -lc 'vsenv 0 && " + read + "'";
    }

    /** cp_cluster_vip_never_observed_in_fleet: the cluster-VIP read alone uses the fault-tolerant
     * vsenv shape (vsenv 0 >/dev/null 2>&1 || true; <read>), matching InventoryCapabilityExecutor's own
     * faultTolerantVsenv0 -- a misdetected-VSX device's failing vsenv 0 no longer short-circuits it via &&. */
    private static String vsenvZeroFaultTolerant(String read) {
        return "bash -lc 'vsenv 0 >/dev/null 2>&1 || true; " + read + "'";
    }

    /**
     * 14E PM-3: {@code default} spans both vsys1 and vsys2 (each has an interface
     * forwarding through it) and its route is shown under both; {@code VR-DMZ} has
     * an interface only in vsys2, so its own route is attributed there only.
     */
    @Test
    void paloAltoFirewallWithTwoVsysAndASpanningVirtualRouterRecordsOneRunWithTwoContexts() {
        String interfaceXml = "<response><result><ifnet>"
                + "<entry><name>ethernet1/1</name><ip>192.0.2.1/24</ip><vsys>1</vsys><fwd>vr:default</fwd><state>up</state></entry>"
                + "<entry><name>ethernet1/2.100</name><ip>198.51.100.10/27</ip><vsys>2</vsys><fwd>vr:default</fwd><state>up</state></entry>"
                + "<entry><name>ethernet1/3</name><ip>203.0.113.10/24</ip><vsys>2</vsys><fwd>vr:VR-DMZ</fwd><state>up</state></entry>"
                + "</ifnet></result></response>";
        String routeXml = "<response><result>"
                + "<entry><destination>0.0.0.0/0</destination><nexthop>192.0.2.254</nexthop>"
                + "<interface>ethernet1/1</interface><virtual-router>default</virtual-router><flags>A S</flags></entry>"
                + "<entry><destination>203.0.113.0/24</destination><nexthop>0.0.0.0</nexthop>"
                + "<interface>ethernet1/3</interface><virtual-router>VR-DMZ</virtual-router><flags>A C</flags></entry>"
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
        assertEquals(3, run.contexts().size(), "physical (no hw ports in this scripted response) + vsys 1 + vsys 2");

        InventoryContext physical = contextNamed(run, InventoryContext.PHYSICAL);
        assertTrue(physical.interfaces().isEmpty(), "no <hw> block in this scripted response");
        assertTrue(physical.routes().isEmpty(), "every route here attributes to a vsys through its virtual router");

        InventoryContext vsys1 = contextNamed(run, "1");
        assertEquals(1, vsys1.interfaces().size());
        assertEquals("192.0.2.1/24", vsys1.interfaces().get(0).addresses().get(0).address());
        assertEquals(1, vsys1.routes().size(), "the default virtual router's route, spanning into vsys1");
        assertEquals("default", vsys1.routes().get(0).routeTable().orElseThrow());

        InventoryContext vsys2 = contextNamed(run, "2");
        assertEquals(2, vsys2.interfaces().size(), "ethernet1/2.100 and ethernet1/3");
        assertTrue(vsys2.interfaces().stream().anyMatch(i -> i.name().equals("ethernet1/2.100")
                && i.parent().equals(Optional.of("ethernet1/2"))));
        assertEquals(2, vsys2.routes().size(), "the spanning default route plus VR-DMZ's own route");
        assertTrue(vsys2.routes().stream().anyMatch(r -> r.routeTable().equals(Optional.of("default"))),
                "the default virtual router's route spans into vsys2 too (PM-3)");
        assertTrue(vsys2.routes().stream().anyMatch(r -> r.routeTable().equals(Optional.of("VR-DMZ"))));
    }

    private static InventoryContext contextNamed(InventoryRun run, String context) {
        return run.contexts().stream().filter(c -> c.context().equals(context)).findFirst()
                .orElseThrow(() -> new AssertionError("no recorded context named " + context));
    }
}
