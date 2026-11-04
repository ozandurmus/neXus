package com.securityexpert.nexus.ui2.service.pathanalysis.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Check;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathEngine;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathGoldenAccess;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathQuery;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyBuilder;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyFixtures;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Plans over the A4 synthetic chain estate (FW-TANGO-04 Check Point with static destination NAT, FW-BRAVO-02 Palo Alto
 * vsys1 / vr-main; RFC 5737 addresses) and the A4 reference policies: every value of a plan comes from the computed
 * result and stored inventory, and anything the result leaves UNKNOWN is not sent.
 */
class PathValidationPlannerTest {
    static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    static final List<TopologyInput.DeviceFacts> FACTS = TopologyFixtures.devices(PathGoldenAccess.estate("chain.json"));
    static final TopologyGraph GRAPH = TopologyBuilder.build(new TopologyInput("tps-validation-test", NOW, FACTS, List.of(),
            "synthetic-path-validation-salt-01".getBytes(StandardCharsets.UTF_8)));

    static Optional<InventoryRun> inventory(String deviceId) {
        return FACTS.stream().filter(f -> f.deviceId().equals(deviceId)).findFirst().flatMap(TopologyInput.DeviceFacts::latestRun);
    }

    static PathResult result(String protocol, int port) {
        return PathEngine.analyze(GRAPH, PathGoldenAccess.chainReferencePolicies(),
                PathQuery.of("198.51.100.20", "203.0.113.200", protocol, port), NOW);
    }

    static PathValidationPlanner.Plan plan(PathResult result, Function<String, Optional<InventoryRun>> inventory) {
        return PathValidationPlanner.plan(result, Optional.empty(), inventory).orElseThrow();
    }

    @Test
    void theHttpsChainPlansOneCheckPointRouteAndThreePaloAltoMatchersFromTheResultOnly() {
        PathResult result = result("tcp", 443);
        assertEquals("ALLOWED", result.verdict());
        PathValidationPlanner.Plan plan = plan(result, PathValidationPlannerTest::inventory);
        assertEquals("b1", plan.branchId());
        assertEquals(2, plan.hops().size());
        assertEquals(0, plan.hopsNotValidated());

        HopPlan cp = plan.hops().get(0).plan().orElseThrow();
        assertEquals(new HopPlan(1, "check_point", "dev-t04", "physical", null, null, "203.0.113.10", null, null, 6, 443,
                null, null, null, List.of(Check.CP_ROUTE),
                new HopPlan.Expect("IP", "eth2", "192.0.2.2", false, null, null, null, null)), cp,
                "the route get uses the post-NAT destination the hop routed on");

        HopPlan pan = plan.hops().get(1).plan().orElseThrow();
        assertEquals(new HopPlan(1, "palo_alto", "dev-b02", "vsys1", null, "vr-main", "203.0.113.10", "198.51.100.20",
                "203.0.113.10", 6, 443, "ZONE-TRANSIT", "ZONE-DMZ", "ZONE-DMZ",
                List.of(Check.PAN_FIB, Check.PAN_SECURITY, Check.PAN_NAT),
                new HopPlan.Expect("CONNECTED", "ethernet1/2", null, false, "ALLOWED", "RULE-WEB-01", "TRANSLATED",
                        "NAT-SNAT-01")), pan);
        for (var hop : plan.hops()) {
            assertTrue(hop.nodeId().matches("tn-[0-9a-f]{20}"), "opaque node id");
            assertTrue(hop.plan().orElseThrow().invalid().isEmpty());
            assertTrue(hop.reasons().isEmpty(), hop.reasons().toString());
        }
    }

    @Test
    void udpIsNeverPlannedForThePaloAltoPolicyMatchers() {
        PathValidationPlanner.Plan plan = plan(result("udp", 53), PathValidationPlannerTest::inventory);
        for (var hop : plan.hops()) {
            hop.plan().ifPresent(p -> {
                assertFalse(p.checks().contains(Check.PAN_SECURITY));
                assertFalse(p.checks().contains(Check.PAN_NAT));
            });
            if ("palo_alto".equals(hop.vendor())) assertTrue(hop.reasons().contains(PathValidation.PROTOCOL_NOT_APPROVED));
        }
    }

    @Test
    void missingInventoryOrAnUnknownBranchPlansNothing() {
        PathValidationPlanner.Plan plan = plan(result("tcp", 443), id -> Optional.empty());
        assertTrue(plan.hops().stream().allMatch(h -> h.plan().isEmpty() && h.reasons().equals(List.of("INVENTORY_UNAVAILABLE"))));
        assertTrue(PathValidationPlanner.plan(result("tcp", 443), Optional.of("b9"), PathValidationPlannerTest::inventory).isEmpty());
    }

    @Test
    void theServiceAdmitsOneJobPerRunnableHopThroughTheSharedAdmissionAndStoresNoCommand() throws Exception {
        var repository = new InMemoryPathValidationRepository();
        var admission = mock(JobAdmissionService.class);
        List<String> keys = new ArrayList<>();
        when(admission.submit(eq(PathValidation.CAPABILITY), anyString(), anyString(), eq("synthetic-admin"),
                eq(ActionRegistry.PATH_ANALYSIS_VALIDATE))).thenAnswer(call -> {
                    keys.add(call.getArgument(2));
                    return "dev-b02".equals(call.getArgument(1))
                            ? new AdmissionResult.Refused("DEVICE_NOT_ELIGIBLE", "synthetic")
                            : new AdmissionResult.Admitted("job-" + keys.size());
                });
        var inventory = mock(DeviceInventoryRepository.class);
        when(inventory.findLatestRun(anyString())).thenAnswer(call -> inventory(call.getArgument(0)));
        // 06:05 in Europe/Istanbul: inside the 06:00 job window.
        var windows = new JobWindowPolicy(Clock.fixed(Instant.parse("2026-11-04T03:05:00Z"), ZoneOffset.UTC), 30);
        var service = new PathValidationService(repository, admission, inventory, windows);
        var prepared = service.prepare(result("tcp", 443), Optional.empty(), "synthetic-admin").orElseThrow();
        Map<String, Object> body = service.submit(prepared, "synthetic-admin");

        assertEquals(List.of("path-validation:" + prepared.validationId() + ":1", "path-validation:" + prepared.validationId() + ":2"), keys);
        var cpRow = repository.row(prepared.validationId(), 1);
        assertEquals("PENDING", cpRow.result);
        assertEquals("job-1", cpRow.jobId);
        assertFalse(cpRow.plan.contains("bash") || cpRow.plan.contains("<test>") || cpRow.plan.contains("route get"),
                "a plan holds values, never command text");
        var panRow = repository.row(prepared.validationId(), 2);
        assertEquals("NOT_EVALUABLE", panRow.result);
        assertNull(panRow.plan, "a refused hop keeps no plan");
        assertEquals(List.of("DEVICE_NOT_ELIGIBLE"), panRow.reasons);
        assertEquals("PENDING", body.get("result"));
        String text = JSON.writeValueAsString(body);
        for (String raw : List.of("198.51.100.", "203.0.113.", "192.0.2.", "eth", "ZONE-", "vr-main", "RULE-WEB", "NAT-SNAT"))
            assertFalse(text.contains(raw), raw + " in the response body");
    }

    @Test
    void outsideAJobWindowNothingIsWritten() {
        var repository = new InMemoryPathValidationRepository();
        var admission = mock(JobAdmissionService.class);
        var windows = new JobWindowPolicy(Clock.fixed(NOW, ZoneOffset.UTC), 30); // 11:00 Istanbul
        var service = new PathValidationService(repository, admission, mock(DeviceInventoryRepository.class), windows);
        assertThrows(JobWindowPolicy.OutsideWindow.class,
                () -> service.prepare(result("tcp", 443), Optional.empty(), "synthetic-aiview"));
        assertTrue(repository.heads.isEmpty());
        verifyNoInteractions(admission);
    }

    @Test
    void aPendingHopWhoseJobEndedIsSettledOnRead() {
        var repository = new InMemoryPathValidationRepository();
        var admission = mock(JobAdmissionService.class);
        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> new AdmissionResult.Admitted("job-" + call.getArgument(1)));
        var inventory = mock(DeviceInventoryRepository.class);
        when(inventory.findLatestRun(anyString())).thenAnswer(call -> inventory(call.getArgument(0)));
        var windows = new JobWindowPolicy(Clock.fixed(Instant.parse("2026-11-04T03:05:00Z"), ZoneOffset.UTC), 30);
        var service = new PathValidationService(repository, admission, inventory, windows);
        var prepared = service.prepare(result("tcp", 443), Optional.empty(), "synthetic-admin").orElseThrow();
        service.submit(prepared, "synthetic-admin");
        repository.row(prepared.validationId(), 1).jobState = "CANCELLED";
        Map<String, Object> body = service.read(prepared.validationId()).orElseThrow();
        var row = repository.row(prepared.validationId(), 1);
        assertEquals("NOT_EVALUABLE", row.result);
        assertNull(row.plan);
        assertEquals(List.of("JOB_ENDED_WITHOUT_OUTCOME"), row.reasons);
        assertEquals("PENDING", body.get("result"), "the other hop is still running");
        assertTrue(service.read("pv-not-a-uuid").isEmpty());
    }
}
