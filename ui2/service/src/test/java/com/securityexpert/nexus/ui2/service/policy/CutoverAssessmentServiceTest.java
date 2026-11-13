package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Target resolution (ACI-3, PO 2026-11-14 Q8), staleness (ACI-8) and what is stored (ACI-5), with the database mocked. */
class CutoverAssessmentServiceTest {
    static final String SEGMENT = "00000000-0000-4000-8000-000000000001";
    static final String DEVICE = "device-synthetic-01";
    final CutoverRepository repository = mock(CutoverRepository.class);
    final PolicyQueryService policies = mock(PolicyQueryService.class);
    final CutoverAssessmentService service = new CutoverAssessmentService(repository, policies, new ObjectMapper());

    static PolicySnapshot policy(String id, String collectedAt, String deviceId) {
        var base = PolicyLookupTest.pan(rule("r1", "any", "any", "deny", true));
        var m = base.metadata();
        return new PolicySnapshot(new Metadata(id, m.sourceId(), m.sourceName(), "PAN", m.containerId(), m.containerName(), m.name(), collectedAt,
            m.artefactRef(), List.of(new Target(deviceId, "FW-TANGO-04", "", "IN_SYNC"))), base.sections(), base.objects());
    }
    void segment(int revision) {
        when(repository.findSegment(SEGMENT)).thenReturn(Optional.of("{\"segmentId\":\"" + SEGMENT + "\",\"name\":\"SEG-ALPHA\",\"revision\":" + revision
            + ",\"blocks\":[\"192.0.2.0/24\"],\"peerBlocks\":[\"198.51.100.0/24\"]}"));
    }
    static final List<Map<String, String>> MAPPINGS = List.of(Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto"));
    static CutoverAssessmentService.Spec spec(String policyId, String segmentZone, String peerZone) {
        return new CutoverAssessmentService.Spec(new CutoverAssessmentService.TargetOverride(DEVICE, policyId, "", segmentZone, peerZone),
            null, null, null, MAPPINGS);
    }
    CutoverAssessmentService.Prepared prepare(CutoverAssessmentService.Spec spec) { return service.prepare(SEGMENT, spec, Optional.empty(), null); }
    /** A stand-in for the routed-path policy (the real one is tested beside the path engine). */
    static CutoverAssessment.FlowPolicy routed(String decision) {
        return new CutoverAssessment.FlowPolicy() {
            @Override public CutoverAssessment.Decision decide(String s, String d, String p, int port, String f, String t) {
                return new CutoverAssessment.Decision(decision, "first matching rule", null, null, List.of(),
                    List.of(Map.of("deviceId", "device-synthetic-02", "vendor", "CP", "hopVerdict", "ALLOWED")));
            }
            @Override public String source() { return CutoverAssessment.SOURCE_PATH; }
            @Override public List<String> coverage() { return List.of("TARGET_FROM_ROUTED_PATH", "FABRIC_REDIRECT_NOT_MODELLED"); }
        };
    }
    static void upload(CutoverAssessmentService.Prepared prepared) throws Exception {
        prepared.run().read(new ByteArrayInputStream("src,dst,port,proto\n192.0.2.8,198.51.100.8,443,tcp\n".getBytes(StandardCharsets.UTF_8)),
            Map.of("source", "src", "destination", "dst", "port", "port", "protocol", "proto"));
    }

    @Test void enrolledAsaAndFortiGateTargetsAreNotEvaluableUntilTheirPolicyIsCollected() throws Exception {
        segment(1);
        for (String hint : List.of("cisco_asa", "fortigate")) {
            when(repository.deviceVendor(DEVICE)).thenReturn(Optional.of(hint));
            var prepared = prepare(spec(null, "", ""));
            upload(prepared);
            var result = prepared.run().finish(Instant.now().plusSeconds(60));
            assertEquals("NOT_EVALUABLE", result.flows().get(0).get("decision"));
            assertEquals("VENDOR_POLICY_NOT_COLLECTED", result.flows().get(0).get("decisionReason"));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> prepare(spec("p1", "", "")))
                .getMessage().startsWith("POLICY_NOT_FOR_TARGET"));
        }
        verifyNoInteractions(policies);
    }

    @Test void evaluableTargetsNeedTheirOwnStoredPolicy() {
        segment(1);
        when(repository.deviceVendor(DEVICE)).thenReturn(Optional.of("palo_alto"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> prepare(spec(null, "Z-SEG", "Z-PEER"))).getMessage().startsWith("POLICY_REQUIRED"));
        when(policies.find("p-other")).thenReturn(Optional.of(policy("p-other", "2026-11-13T00:00:00Z", "another-device")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> prepare(spec("p-other", "Z-SEG", "Z-PEER"))).getMessage().startsWith("POLICY_NOT_FOR_TARGET"));
        when(repository.deviceVendor(DEVICE)).thenReturn(Optional.of("check_point"));
        when(policies.find("p1")).thenReturn(Optional.of(policy("p1", "2026-11-13T00:00:00Z", DEVICE)));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> prepare(spec("p1", "", ""))).getMessage().startsWith("POLICY_NOT_FOR_TARGET"));
        when(repository.deviceVendor("absent")).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> prepare(new CutoverAssessmentService.Spec(
            new CutoverAssessmentService.TargetOverride("absent", null, "", "", ""), null, null, null, MAPPINGS)));
    }

    @Test void savedAssessmentCitesItsPolicyAndStoresOnlyTuplesAndCounts() throws Exception {
        segment(3);
        when(repository.deviceVendor(DEVICE)).thenReturn(Optional.of("palo_alto"));
        when(policies.find("p1")).thenReturn(Optional.of(policy("p1", "2026-11-13T00:00:00Z", DEVICE)));
        var prepared = prepare(spec("p1", "Z-SEG", "Z-PEER"));
        upload(prepared);
        var result = prepared.run().finish(Instant.now().plusSeconds(60));
        assertEquals("BLOCKERS_FOUND", result.readiness());
        service.save(prepared, result, "synthetic-actor");
        var stored = ArgumentCaptor.forClass(CutoverRepository.Assessment.class);
        verify(repository).saveAssessment(stored.capture());
        var a = stored.getValue();
        assertEquals(3, a.segmentRevision()); assertEquals("p1", a.targetPolicyId()); assertEquals("2026-11-13T00:00:00Z", a.targetCollectedAt());
        assertEquals("BLOCKERS_FOUND", a.readiness()); assertEquals(CutoverAssessment.ENGINE_VERSION, a.engineVersion());
        assertEquals(2, a.rows().size());
        assertTrue(a.rows().get(0).contains("\"kind\":\"FLOW\"")); assertTrue(a.rows().get(1).contains("\"kind\":\"MISSING_RULE\""));
        for (String text : List.of(a.summary(), String.join("", a.rows())))
            for (String forbidden : List.of("\"rows\"", "\"mapping\"", "\"headers\"", "\"raw\"", "192.0.2.8,198.51.100.8")) assertFalse(text.contains(forbidden), forbidden);
        assertTrue(a.summary().contains("\"targetVendor\":\"PAN\""));
    }

    @Test void anAssessmentIsStaleWhenItsSegmentOrACitedPolicyChanged() {   // ACI-8
        Optional<String> topology = Optional.empty();
        String stored = "{\"assessmentId\":\"a1\",\"segmentId\":\"" + SEGMENT + "\",\"segmentRevision\":2,\"targetPolicyId\":\"p1\","
            + "\"targetCollectedAt\":\"2026-11-13T00:00:00Z\",\"oldPolicyId\":null,\"oldCollectedAt\":null,\"readiness\":\"BLOCKERS_FOUND\"}";
        when(repository.findAssessment("a1")).thenReturn(Optional.of(stored));
        segment(2);
        when(policies.find("p1")).thenReturn(Optional.of(policy("p1", "2026-11-13T00:00:00Z", DEVICE)));
        assertEquals(false, service.assessment("a1", topology).orElseThrow().get("stale"));
        segment(3);
        assertEquals(List.of("SEGMENT_CHANGED"), service.assessment("a1", topology).orElseThrow().get("staleReasons"));
        segment(2);
        when(policies.find("p1")).thenReturn(Optional.of(policy("p1", "2026-11-14T00:00:00Z", DEVICE)));
        assertEquals(List.of("TARGET_POLICY_CHANGED"), service.assessment("a1", topology).orElseThrow().get("staleReasons"));
        when(policies.find("p1")).thenReturn(Optional.empty());
        assertEquals(true, service.assessment("a1", topology).orElseThrow().get("stale"));
        when(repository.findSegment(SEGMENT)).thenReturn(Optional.empty());
        assertTrue(((List<?>) service.assessment("a1", topology).orElseThrow().get("staleReasons")).contains("SEGMENT_CHANGED"));
    }

    @Test void segmentInputIsValidatedBeforeAnyWrite() {
        for (var bad : List.of(List.of("", "192.0.2.0/24", "198.51.100.0/24"), List.of("SEG\u0007", "192.0.2.0/24", "198.51.100.0/24"),
                List.of("SEG-ALPHA", "192.0.2.1/24", "198.51.100.0/24"), List.of("SEG-ALPHA", "192.0.2.0/24", "192.0.2.0/25")))
            assertThrows(IllegalArgumentException.class, () -> new CutoverAssessmentService.SegmentInput(bad.get(0), List.of(bad.get(1)), List.of(bad.get(2))));
        when(repository.createSegment(anyString(), eq("SEG-ALPHA"), anyString(), anyString(), anyString())).thenReturn(false);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.createSegment(
            new CutoverAssessmentService.SegmentInput("SEG-ALPHA", List.of("192.0.2.0/24"), List.of("198.51.100.0/24")), "synthetic-actor"))
            .getMessage().startsWith("SEGMENT_NAME_TAKEN"));
    }

    @Test void withoutAnOverrideEveryFlowTargetComesFromItsComputedPath() throws Exception {
        segment(1);
        var pathSpec = new CutoverAssessmentService.Spec(null, null, null, null, MAPPINGS);
        assertEquals("TOPOLOGY_NOT_BUILT", assertThrows(IllegalStateException.class,
            () -> service.prepare(SEGMENT, pathSpec, Optional.empty(), null)).getMessage());
        var prepared = service.prepare(SEGMENT, pathSpec, Optional.of(routed("WOULD_ALLOW")), "topology-synthetic-01");
        upload(prepared);
        var result = prepared.run().finish(Instant.now().plusSeconds(60));
        assertEquals("PATH", result.summary().get("targetSource")); assertEquals("PATH", result.flows().get(0).get("targetSource"));
        assertEquals("device-synthetic-02", ((Map<?, ?>) ((List<?>) result.flows().get(0).get("pathFirewalls")).get(0)).get("deviceId"));
        var codes = ((List<?>) result.summary().get("coverage")).stream().map(c -> ((Map<?, ?>) c).get("code")).toList();
        assertTrue(codes.containsAll(List.of("TARGET_FROM_ROUTED_PATH", "FABRIC_REDIRECT_NOT_MODELLED")));
        assertFalse(codes.contains("TARGET_FROM_OPERATOR_OVERRIDE")); assertFalse(codes.contains("ZONE_PAIR_MISSING"));
        verifyNoInteractions(policies); verify(repository, never()).deviceVendor(anyString());
        service.save(prepared, result, "synthetic-actor");
        var stored = ArgumentCaptor.forClass(CutoverRepository.Assessment.class);
        verify(repository).saveAssessment(stored.capture());
        assertEquals("PATH", stored.getValue().targetSource()); assertNull(stored.getValue().targetDeviceId());
        assertEquals("topology-synthetic-01", stored.getValue().topologySnapshotId());
        // An override is labelled as such on every result.
        when(repository.deviceVendor(DEVICE)).thenReturn(Optional.of("cisco_asa"));
        var overridden = prepare(spec(null, "", ""));
        upload(overridden);
        var labelled = overridden.run().finish(Instant.now().plusSeconds(60));
        assertEquals("OPERATOR_OVERRIDE", labelled.flows().get(0).get("targetSource"));
        assertTrue(((List<?>) labelled.summary().get("coverage")).stream().anyMatch(c -> "TARGET_FROM_OPERATOR_OVERRIDE".equals(((Map<?, ?>) c).get("code"))));
    }

    @Test void aRoutedAssessmentIsStaleWhenTheHeldTopologyChanged() {
        when(repository.findAssessment("a2")).thenReturn(Optional.of("{\"assessmentId\":\"a2\",\"segmentId\":\"" + SEGMENT
            + "\",\"segmentRevision\":1,\"targetSource\":\"PATH\",\"topologySnapshotId\":\"topology-synthetic-01\",\"targetPolicyId\":null,\"oldPolicyId\":null}"));
        segment(1);
        assertEquals(false, service.assessment("a2", Optional.of("topology-synthetic-01")).orElseThrow().get("stale"));
        assertEquals(List.of("TOPOLOGY_CHANGED"), service.assessment("a2", Optional.of("topology-synthetic-02")).orElseThrow().get("staleReasons"));
        assertEquals(List.of("TOPOLOGY_CHANGED"), service.assessment("a2", Optional.empty()).orElseThrow().get("staleReasons"));
    }
}
