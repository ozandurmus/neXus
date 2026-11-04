package com.securityexpert.nexus.ui2.worker.pathvalidation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Check;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.pathvalidation.PathValidationRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/**
 * Scripted transports only: no socket, host or device contact. Synthetic values only (FW-TANGO-04 Check Point,
 * FW-BRAVO-02 Palo Alto, RFC 5737 addresses). Proves the exact literals sent, the refusals before contact, the
 * one-attempt-per-command ledger and that nothing of an answer reaches the stored outcome.
 */
class PathValidationJobExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Values of the plans and answers below; none may reach a stored outcome. */
    private static final List<String> RAW = List.of("203.0.113.", "198.51.100.", "192.0.2.", "eth2", "eth3", "ethernet1/",
            "ZONE-", "vr-main", "allow-https", "snat-dmz", "FW-TANGO-04", "synthetic-key");

    static final HopPlan CP = new HopPlan(1, "check_point", "dev-t04", "physical", null, null, "203.0.113.10", null, null,
            6, 443, null, null, null, List.of(Check.CP_ROUTE),
            new HopPlan.Expect("IP", "eth2", "192.0.2.2", false, null, null, null, null));
    static final HopPlan PAN = new HopPlan(1, "palo_alto", "dev-b02", "vsys1", null, "vr-main", "203.0.113.10",
            "198.51.100.20", "203.0.113.10", 6, 443, "ZONE-TRANSIT", "ZONE-DMZ", "ZONE-DMZ",
            List.of(Check.PAN_FIB, Check.PAN_SECURITY, Check.PAN_NAT),
            new HopPlan.Expect("CONNECTED", "ethernet1/2", null, false, "ALLOWED", "allow-https", "TRANSLATED", "snat-dmz"));

    final class Harness {
        final JobLeaseRepository leases = mock(JobLeaseRepository.class);
        final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        final DeviceRepository devices = mock(DeviceRepository.class);
        final DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        final DeviceTransport ssh = mock(DeviceTransport.class);
        final DeviceTransport pan = mock(DeviceTransport.class);
        final List<String> sent = new ArrayList<>();
        final List<String[]> recorded = new ArrayList<>();
        final List<String> abandoned = new ArrayList<>();
        final AtomicInteger preContacts = new AtomicInteger();
        String planJson, finalState, finalReason;
        boolean pending = true;

        Harness(HopPlan plan, String vendor, String transport, InventoryContext context) throws Exception {
            planJson = JSON.writeValueAsString(plan);
            String deviceId = plan.deviceId();
            when(devices.find(deviceId)).thenReturn(Optional.of(new DeviceRecord(deviceId, "gateway", vendor, "manual",
                    Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
            when(devices.findEndpointByDeviceId(deviceId)).thenReturn(Optional.of(
                    new EndpointRecord("endpoint-1", deviceId, transport, "192.0.2.250", Instant.now())));
            when(devices.findSummary(anyString())).thenReturn(Optional.empty());
            when(inventory.findLatestRun(deviceId)).thenReturn(Optional.of(new InventoryRun("run-1", deviceId, "job-0",
                    Instant.now(), 1, List.of(context), List.of())));
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(call -> {
                finalState = call.getArgument(3).toString();
                finalReason = call.getArgument(6);
                return true;
            });
            when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
            when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                    .thenAnswer(call -> "attempt-" + preContacts.incrementAndGet());
            when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
            when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
            when(ssh.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(mock(TransportSession.class)));
        }

        Harness ssh(String output) {
            when(ssh.exec(any(), any(), any())).thenAnswer(call -> {
                sent.add(((ExecSpec) call.getArgument(1)).command());
                return new ExecResult.Completed(output, 0);
            });
            return this;
        }

        Harness api(Function<String, String> script) {
            when(pan.xmlApiCall(any(), any(), any())).thenAnswer(call -> {
                XmlApiSpec spec = call.getArgument(1);
                if ("keygen".equals(spec.type()))
                    return new XmlApiResult.Completed(200, "<response status=\"success\"><result><key>synthetic-key</key></result></response>");
                assertEquals("op", spec.type());
                assertEquals(Map.of("X-PAN-KEY", "synthetic-key"), spec.headers());
                sent.add(spec.formParams().get("cmd"));
                return new XmlApiResult.Completed(200, script.apply(spec.formParams().get("cmd")));
            });
            return this;
        }

        void run(String deviceId) {
            PathValidationRepository store = new PathValidationRepository() {
                @Override public void create(String v, String a, String t, String p, int n, List<NewHop> h) { }
                @Override public boolean attachJob(String v, int o, String j) { return true; }
                @Override public void abandon(String v, int o, String r) { }
                @Override public Optional<Pending> pendingForJob(String jobId) {
                    return pending ? Optional.of(new Pending("pv-1", 1, deviceId, planJson)) : Optional.empty();
                }
                @Override public boolean recordOutcome(String jobId, long epoch, String result, String checks, List<String> reasons) {
                    recorded.add(new String[] {result, checks, String.join(",", reasons)});
                    pending = false;
                    return true;
                }
                @Override public boolean abandonJob(String jobId, String reason) {
                    if (pending) abandoned.add(reason);
                    pending = false;
                    return true;
                }
                @Override public Optional<Validation> find(String v) { return Optional.empty(); }
            };
            var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                    .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
            GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
            new PathValidationJobExecutor(leases, attempts, devices, inventory, DevicePlatformFactsRepository.NONE, ssh, pan,
                    ref -> new PanCredentialMaterial("synthetic-user", "synthetic-pass".toCharArray()), gates, store)
                    .execute("job-1", 1, deviceId, "192.0.2.250", 22, "credential-ref");
        }

        void assertNothingRawStored() {
            for (String[] row : recorded)
                for (String field : row)
                    for (String raw : RAW) assertFalse(field.contains(raw), raw + " reached the stored outcome: " + field);
        }
    }

    static InventoryContext cpPhysical() {
        return new InventoryContext("physical", List.of(), List.of());
    }

    static InventoryContext panVsys1(String vr, String... zones) {
        List<InventoryInterface> interfaces = new ArrayList<>();
        int i = 1;
        for (String zone : zones) {
            interfaces.add(new InventoryInterface("if-" + i, "ethernet1/" + i++, Optional.empty(), "physical", "up", List.of(),
                    Optional.empty(), Optional.empty(), Optional.of(zone), Optional.of(vr), Optional.of("RUNTIME")));
        }
        return new InventoryContext("vsys1", interfaces, List.of());
    }

    @Test
    void checkPointSendsTheExactLiteralOnceAndStoresOnlyTheComparison() throws Exception {
        var h = new Harness(CP, "check_point", "ssh_exec", cpPhysical())
                .ssh("203.0.113.10 via 192.0.2.2 dev eth2 src 192.0.2.1 uid 0\n    cache\n");
        h.run("dev-t04");
        assertEquals(List.of("bash -lc 'ip -4 route get 203.0.113.10'"), h.sent);
        verify(h.ssh).exec(any(), eq(new ExecSpec("bash -lc 'ip -4 route get 203.0.113.10'", true, 0,
                PathValidation.OUTPUT_LIMIT_BYTES)), any());
        assertEquals(1, h.preContacts.get());
        assertEquals("COMPLETED", h.finalState);
        assertEquals("VALIDATION_RECORDED", h.finalReason);
        assertEquals("MATCH", h.recorded.get(0)[0]);
        assertEquals(List.of(Map.of("check", "CP_ROUTE", "result", "MATCH", "reason", "SAME_INTERFACE_AND_NEXT_HOP")),
                JSON.readValue(h.recorded.get(0)[1], List.class));
        assertTrue(h.abandoned.isEmpty());
        h.assertNothingRawStored();
        verify(h.pan, never()).xmlApiCall(any(), any(), any());
    }

    @Test
    void aVirtualSystemUsesTheVsenvFormOnlyWhenItsVsidIsInInventory() throws Exception {
        var vs = new HopPlan(1, "check_point", "dev-t04", "2", "2", null, "203.0.113.10", null, null, 6, 443, null, null,
                null, List.of(Check.CP_ROUTE), new HopPlan.Expect("IP", "eth2", "192.0.2.2", false, null, null, null, null));
        var h = new Harness(vs, "check_point", "ssh_exec", new InventoryContext("2", List.of(), List.of()))
                .ssh("Context is set to Virtual Device FW-TANGO-04_VS2 (ID 2).\n203.0.113.10 via 192.0.2.6 dev eth2\n");
        h.run("dev-t04");
        assertEquals(List.of("bash -lc 'vsenv 2 && ip -4 route get 203.0.113.10'"), h.sent);
        assertEquals("MISMATCH", h.recorded.get(0)[0]);
        assertEquals("NEXT_HOP_DIFFERS", h.recorded.get(0)[2]);
        h.assertNothingRawStored();

        var missing = new Harness(vs, "check_point", "ssh_exec", cpPhysical()).ssh("unused");
        missing.run("dev-t04");
        assertEquals("REJECTED", missing.finalState);
        assertEquals("CONTEXT_NOT_IN_INVENTORY", missing.finalReason);
        assertEquals(List.of("CONTEXT_NOT_IN_INVENTORY"), missing.abandoned);
        verifyNoInteractions(missing.ssh);
        assertEquals(0, missing.preContacts.get());
    }

    @Test
    void paloAltoSendsTheThreeApprovedOpCommandsAndComparesEach() throws Exception {
        var h = new Harness(PAN, "palo_alto", "pan_xml_api", panVsys1("vr-main", "ZONE-TRANSIT", "ZONE-DMZ")).api(cmd ->
                cmd.startsWith("<test><routing>")
                        ? "<response status=\"success\"><result><nh>ip</nh><ip>0.0.0.0</ip><interface>ethernet1/2</interface></result></response>"
                        : cmd.startsWith("<test><security")
                        ? "<response status=\"success\"><result><rules><entry name=\"allow-https\"/></rules></result></response>"
                        : "<response status=\"success\"><result><rules><entry name=\"other-nat\"/></rules></result></response>");
        h.run("dev-b02");
        assertEquals(List.of(
                "<test><routing><fib-lookup><virtual-router>vr-main</virtual-router><ip>203.0.113.10</ip></fib-lookup></routing></test>",
                "<test><security-policy-match><from>ZONE-TRANSIT</from><to>ZONE-DMZ</to><source>198.51.100.20</source>"
                        + "<destination>203.0.113.10</destination><protocol>6</protocol><destination-port>443</destination-port>"
                        + "</security-policy-match></test>",
                "<test><nat-policy-match><from>ZONE-TRANSIT</from><to>ZONE-DMZ</to><source>198.51.100.20</source>"
                        + "<destination>203.0.113.10</destination><protocol>6</protocol><destination-port>443</destination-port>"
                        + "</nat-policy-match></test>"), h.sent);
        assertEquals(3, h.preContacts.get());
        assertEquals("MISMATCH", h.recorded.get(0)[0]);
        assertEquals("NAT_RULE_DIFFERS", h.recorded.get(0)[2]);
        List<?> checks = JSON.readValue(h.recorded.get(0)[1], List.class);
        assertEquals(List.of("MATCH", "MATCH", "MISMATCH"), checks.stream().map(c -> ((Map<?, ?>) c).get("result")).toList());
        h.assertNothingRawStored();
        verifyNoInteractions(h.ssh);
    }

    @Test
    void anApiErrorOrUnknownShapeIsNotEvaluableNeverAMismatch() throws Exception {
        var h = new Harness(PAN, "palo_alto", "pan_xml_api", panVsys1("vr-main", "ZONE-TRANSIT", "ZONE-DMZ"))
                .api(cmd -> "<response status=\"error\"><msg><line>synthetic</line></msg></response>");
        h.run("dev-b02");
        assertEquals("NOT_EVALUABLE", h.recorded.get(0)[0]);
        assertEquals("ANSWER_NOT_RECOGNISED", h.recorded.get(0)[2]);
        assertEquals("COMPLETED", h.finalState);
    }

    @Test
    void refusalsHappenBeforeAnyContactAndClearThePlan() throws Exception {
        // a zone the device does not have in stored inventory
        var zone = new Harness(PAN, "palo_alto", "pan_xml_api", panVsys1("vr-main", "ZONE-TRANSIT")).api(cmd -> "");
        zone.run("dev-b02");
        assertEquals("ZONE_NOT_IN_INVENTORY", zone.finalReason);
        // a virtual router the device does not have
        var vr = new Harness(PAN, "palo_alto", "pan_xml_api", panVsys1("vr-other", "ZONE-TRANSIT", "ZONE-DMZ")).api(cmd -> "");
        vr.run("dev-b02");
        assertEquals("VIRTUAL_ROUTER_NOT_IN_INVENTORY", vr.finalReason);
        // a plan whose device is not the job's target
        var other = new Harness(CP, "check_point", "ssh_exec", cpPhysical()).ssh("unused");
        other.run("dev-other");
        assertEquals("PLAN_DEVICE_MISMATCH", other.finalReason);
        // a tampered plan (an injected destination) never reaches a command
        var tampered = new Harness(CP, "check_point", "ssh_exec", cpPhysical()).ssh("unused");
        tampered.planJson = tampered.planJson.replace("203.0.113.10", "203.0.113.10;reboot");
        tampered.run("dev-t04");
        assertEquals("DESTINATION_INVALID", tampered.finalReason);
        // the wrong transport for the vendor
        var transport = new Harness(CP, "check_point", "pan_xml_api", cpPhysical()).ssh("unused");
        transport.run("dev-t04");
        assertEquals("TRANSPORT_NOT_ELIGIBLE", transport.finalReason);
        // no pending hop for this job
        var none = new Harness(CP, "check_point", "ssh_exec", cpPhysical()).ssh("unused");
        none.pending = false;
        none.run("dev-t04");
        assertEquals("HOP_NOT_PENDING", none.finalReason);
        for (Harness h : List.of(zone, vr, other, tampered, transport, none)) {
            assertEquals("REJECTED", h.finalState);
            assertEquals(0, h.preContacts.get());
            verify(h.ssh, never()).exec(any(), any(), any());
            verify(h.pan, never()).xmlApiCall(any(), any(), any());
            assertTrue(h.recorded.isEmpty());
        }
    }

    @Test
    void aRecordedAttemptIsNeverReplayed() throws Exception {
        var h = new Harness(CP, "check_point", "ssh_exec", cpPhysical()).ssh("203.0.113.10 via 192.0.2.2 dev eth2");
        when(h.attempts.findByJobAndStep("job-1", 0)).thenReturn(List.of(mock(
                com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt.class)));
        h.run("dev-t04");
        assertEquals("OUTCOME_UNKNOWN", h.finalState);
        assertEquals("PRIOR_ATTEMPT_NOT_REPLAYED", h.finalReason);
        verify(h.ssh, never()).exec(any(), any(), any());
        assertEquals(List.of("JOB_ENDED_WITHOUT_OUTCOME"), h.abandoned, "the plan never outlives the job");
    }
}
