package com.securityexpert.nexus.ui2.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.CutoverPathTargets;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The HTTP edge: writes are refused to aiview, the multipart order is fixed, and aiview reads are self-masked (ACI-6). */
class CutoverAssessmentControllerTest {
    static final String SEGMENT = "00000000-0000-4000-8000-000000000001";
    final CutoverRepository repository = mock(CutoverRepository.class);
    final PolicyQueryService policies = mock(PolicyQueryService.class);
    final RbacEvaluator rbac = mock(RbacEvaluator.class);
    final CutoverPathTargets paths = mock(CutoverPathTargets.class);
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer("k".repeat(32).getBytes(StandardCharsets.UTF_8));
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    final CutoverAssessmentController controller = new CutoverAssessmentController(
        new CutoverAssessmentService(repository, policies, new ObjectMapper()), paths, rbac, names, ips, new PolicyBulkhead(),
        new CutoverFlowRetention(repository, true));
    static final String SPEC = "{\"targetOverride\":{\"deviceId\":\"device-synthetic-01\",\"policyId\":\"p1\",\"segmentZone\":\"Z-SEG\",\"peerZone\":\"Z-PEER\"},"
        + "\"mappings\":[{\"source\":\"src\",\"destination\":\"dst\",\"port\":\"port\",\"protocol\":\"proto\"}]}";
    static final String DATA = "src,dst,port,proto,ignored\r\n192.0.2.8,198.51.100.8,443,tcp,synthetic-raw-marker\r\n";

    MockHttpServletRequest request(String method, String path, boolean masked) {
        var request = new MockHttpServletRequest(method, path);
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor");
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, masked);
        return request;
    }
    MockHttpServletRequest upload(boolean masked, String first, String spec, String data) {
        var request = request("POST", "/api/v2/cutover/segments/" + SEGMENT + "/assessments", masked);
        request.setContentType("multipart/form-data; boundary=SyntheticBoundary");
        String content = "--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"" + first + "\"\r\n\r\n" + spec
            + "\r\n--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.csv\"\r\nContent-Type: text/csv\r\n\r\n" + data
            + "\r\n--SyntheticBoundary--\r\n";
        request.setContent(content.getBytes(StandardCharsets.UTF_8)); return request;
    }
    void allow() {
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenReturn(new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty()));
        when(repository.findSegment(SEGMENT)).thenReturn(Optional.of("{\"segmentId\":\"" + SEGMENT + "\",\"name\":\"SEG-ALPHA\",\"revision\":1,"
            + "\"blocks\":[\"192.0.2.0/24\"],\"peerBlocks\":[\"198.51.100.0/24\"]}"));
        when(repository.deviceVendor("device-synthetic-01")).thenReturn(Optional.of("palo_alto"));
        var any = new Cell(List.of("any"), false);
        var rule = new Rule("r1", "uuid-r1", 1, "RULE-SYNTHETIC", true, any, any, any, any, "deny", "Log", "", Map.of("from", List.of("any"), "to", List.of("any")));
        var meta = new Metadata("p1", "s1", "MGR-ALPHA", "PAN", "c1", "DOM-ALPHA", "POL-ALPHA", "2026-11-13T12:00:00Z", "a1",
            List.of(new Target("device-synthetic-01", "FW-TANGO-04", "", "IN_SYNC")));
        when(policies.find("p1")).thenReturn(Optional.of(new PolicySnapshot(meta,
            List.of(new Section(ref("p1", "Shared", "pre-rulebase"), "Pre rules", "Shared", null, List.of(rule))),
            Map.of("any", new PolicyObject("any", "Any", "any", List.of(), List.of(), "RESOLVED")))));
    }

    @Test void anAssessmentRunsFromStoredPolicyAndUploadAndStoresNoRawRow() throws Exception {
        allow();
        var response = controller.assess(SEGMENT, upload(false, "spec", SPEC, DATA));
        assertEquals(201, response.getStatusCode().value());
        var body = ((CutoverResponse) response.getBody()).body();
        assertEquals("BLOCKERS_FOUND", body.get("readiness")); assertEquals("COMPLETE", body.get("status"));
        var stored = ArgumentCaptor.forClass(CutoverRepository.Assessment.class);
        verify(repository).saveAssessment(stored.capture());
        assertFalse(stored.getValue().summary().contains("synthetic-raw-marker")); assertFalse(String.join("", stored.getValue().rows()).contains("synthetic-raw-marker"));
    }

    @Test void aiviewNeverWritesAndMultipartOrderIsFixed() throws Exception {
        allow();
        assertEquals(403, controller.assess(SEGMENT, upload(true, "spec", SPEC, DATA)).getStatusCode().value());
        assertEquals(403, controller.create(Map.of("name", "SEG-ALPHA", "blocks", List.of("192.0.2.0/24"), "peerBlocks", List.of("198.51.100.0/24")),
            request("POST", "/api/v2/cutover/segments", true)).getStatusCode().value());
        assertEquals(403, controller.deleteSegment(SEGMENT, request("DELETE", "/api/v2/cutover/segments/" + SEGMENT, true)).getStatusCode().value());
        var wrongOrder = controller.assess(SEGMENT, upload(false, "mapping", SPEC, DATA));
        assertEquals(400, wrongOrder.getStatusCode().value()); assertTrue(wrongOrder.getBody().toString().contains("MULTIPART_SPEC_FIRST"));
        var unknownField = controller.assess(SEGMENT, upload(false, "spec", SPEC.replace("\"mappings\"", "\"surprise\":1,\"mappings\""), DATA));
        assertEquals(400, unknownField.getStatusCode().value());
        assertEquals(400, controller.assess("not-a-uuid", upload(false, "spec", SPEC, DATA)).getStatusCode().value());
        verify(repository, never()).saveAssessment(any());
    }

    @Test void aiviewReadsAreMaskedByTheControllerItself() {
        allow();
        when(repository.listAssessments(SEGMENT)).thenReturn(List.of());
        var response = controller.segment(SEGMENT, request("GET", "/api/v2/cutover/segments/" + SEGMENT, true));
        var masked = (CutoverResponse) response.getBody();
        assertTrue(masked.masked());
        assertNotEquals(List.of("192.0.2.0/24"), masked.body().get("blocks")); assertNotEquals("SEG-ALPHA", masked.body().get("name"));
        assertEquals(false, masked.body().get("canManage"));
        var raw = (CutoverResponse) controller.segment(SEGMENT, request("GET", "/api/v2/cutover/segments/" + SEGMENT, false)).getBody();
        assertFalse(raw.masked()); assertEquals(List.of("192.0.2.0/24"), raw.body().get("blocks"));
    }

    @Test void withoutAnOverrideAndWithoutAHeldTopologyTheAssessmentIsRefused() throws Exception {
        allow();
        when(paths.current()).thenReturn(Optional.empty());
        String pathSpec = "{\"mappings\":[{\"source\":\"src\",\"destination\":\"dst\",\"port\":\"port\",\"protocol\":\"proto\"}]}";
        var response = controller.assess(SEGMENT, upload(false, "spec", pathSpec, DATA));
        assertEquals(409, response.getStatusCode().value()); assertTrue(response.getBody().toString().contains("TOPOLOGY_NOT_BUILT"));
        verify(repository, never()).saveAssessment(any()); verify(repository, never()).deviceVendor(anyString());
    }

    @Test void anEmptyFlowPageOfAnExpiredAssessmentSaysTheRowsWereRemovedByRetention() {
        String id = "00000000-0000-4000-8000-0000000000b1";
        when(repository.findAssessment(id)).thenReturn(Optional.of("{\"assessmentId\":\"" + id + "\"}"));
        when(repository.flowCount(id, "FLOW", "WOULD_DROP")).thenReturn(0L);
        when(repository.flows(id, "FLOW", "WOULD_DROP", 0)).thenReturn(List.of());
        when(repository.flowsExpired(id, CutoverFlowRetention.RETENTION_DAYS)).thenReturn(true);
        for (boolean masked : new boolean[] {false, true}) {
            var body = ((CutoverResponse) controller.flows(id, "FLOW", "WOULD_DROP", 0, request("GET", "/api/v2/cutover/assessments/" + id + "/flows", masked)).getBody()).body();
            assertEquals(true, body.get("flowsExpired")); assertEquals(90, body.get("retentionDays")); assertEquals(0L, body.get("total"));
        }
        // A young assessment with no row in this decision keeps the plain empty answer.
        when(repository.flowsExpired(id, CutoverFlowRetention.RETENTION_DAYS)).thenReturn(false);
        var young = ((CutoverResponse) controller.flows(id, "FLOW", "WOULD_DROP", 0, request("GET", "/api/v2/cutover/assessments/" + id + "/flows", false)).getBody()).body();
        assertFalse(young.containsKey("flowsExpired")); assertFalse(young.containsKey("retentionDays"));
        // A page that has rows never asks the retention read.
        clearInvocations(repository);
        when(repository.flowCount(id, "FLOW", null)).thenReturn(1L);
        when(repository.flows(id, "FLOW", null, 0)).thenReturn(List.of("{\"flowSource\":\"192.0.2.9\"}"));
        controller.flows(id, "FLOW", null, 0, request("GET", "/api/v2/cutover/assessments/" + id + "/flows", false));
        verify(repository, never()).flowsExpired(anyString(), anyInt());
    }
}
