package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;

class OfflineAnalysisControllerTest {
    final PolicyQueryService policies = mock(PolicyQueryService.class);
    final OfflineAnalysisService analyses = mock(OfflineAnalysisService.class);
    final RbacEvaluator rbac = mock(RbacEvaluator.class);
    final OfflineAnalysisController controller = new OfflineAnalysisController(policies, analyses, rbac);
    static final String DATA = "src,dst,port,proto,ignored\r\n192.0.2.8,198.51.100.8,443,tcp,synthetic-raw-marker\r\n";
    static final String MAPPING = "{\"source\":\"src\",\"destination\":\"dst\",\"port\":\"port\",\"protocol\":\"proto\"}";
    MockHttpServletRequest request(boolean masked, String mapping, String data, boolean extra) {
        var request = new MockHttpServletRequest("POST", "/api/v2/policy/policies/p1/offline-analyses");
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor");
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, masked);
        request.setContentType("multipart/form-data; boundary=SyntheticBoundary");
        String content = "--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"mapping\"\r\n\r\n" + mapping
            + "\r\n--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.csv\"\r\nContent-Type: text/csv\r\n\r\n" + data
            + (extra ? "\r\n--SyntheticBoundary\r\nContent-Disposition: form-data; name=\"extra\"\r\n\r\nx" : "") + "\r\n--SyntheticBoundary--\r\n";
        request.setContent(content.getBytes(StandardCharsets.UTF_8)); return request;
    }
    void allow() {
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenReturn(new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty()));
        var any = new Cell(List.of("any"), false);
        var rule = new Rule("r1", "uuid-r1", 1, "RULE-SYNTHETIC", true, any, any, any, any, "allow", "Log", "", Map.of("from", List.of("any"), "to", List.of("any")));
        var meta = new Metadata("p1", "s1", "MGR-ALPHA", "PAN", "c1", "DOM-ALPHA", "POL-ALPHA", "2026-10-09T12:00:00Z", "a1", List.of());
        when(policies.find("p1")).thenReturn(Optional.of(new PolicySnapshot(meta,
            List.of(new Section(ref("p1", "Shared", "pre-rulebase"), "Pre rules", "Shared", null, List.of(rule))),
            Map.of("any", new PolicyObject("any", "Any", "any", List.of(), List.of(), "RESOLVED")))));
        when(analyses.save(anyString(), anyString(), anyString(), anyMap())).thenReturn("analysis-1");
    }
    @Test void streamingUploadPersistsOnlyFinishedAggregatesAndInvalidInputsPersistNothing() {
        allow(); var request = request(false, MAPPING, DATA, false);
        assertFalse(new OfflineMultipartConfiguration().multipartResolver().isMultipart(request));
        assertEquals(201, controller.upload("p1", request).getStatusCode().value());
        verify(analyses).save(eq("p1"), eq("synthetic-actor"), eq("synthetic.csv"), argThat(body -> body.get("rowCount").equals(1L)
            && !body.toString().contains("synthetic-raw-marker") && !body.containsKey("rows") && !body.containsKey("mapping")));
        clearInvocations(analyses);
        for (var invalid : List.of(request(false, "{}", DATA, false), request(false, MAPPING, DATA, true),
                request(false, MAPPING, "src,dst,port,proto\n\"unterminated", false)))
            assertEquals(400, controller.upload("p1", invalid).getStatusCode().value());
        verifyNoInteractions(analyses);
        request.setRequestURI("/unrelated-upload"); assertTrue(new OfflineMultipartConfiguration().multipartResolver().isMultipart(request));
    }
    @Test void aiviewCannotUploadOrDeleteEvenWithAnAdminBinding() {
        allow(); var request = request(true, MAPPING, DATA, false);
        assertEquals(403, controller.upload("p1", request).getStatusCode().value());
        assertEquals(403, controller.delete("analysis-1", request).getStatusCode().value()); verifyNoInteractions(analyses);
        when(analyses.list("p1", 0)).thenReturn(List.of());
        var list = (PolicyResponse) controller.list("p1", 0, request).getBody(); assertEquals(false, list.body().get("canManage"));
    }
    @Test void malformedMultipartIsSanitizedAndReleasesUploadSlot() {
        allow(); var invalid = request(false, MAPPING, DATA, false);
        invalid.setContentType("multipart/form-data");
        var response = controller.upload("p1", invalid);
        assertEquals(400, response.getStatusCode().value());
        assertEquals(Map.of("error", "CSV_UPLOAD_INVALID: UTF-8 CSV only, maximum 100 MB, valid multipart required"), response.getBody());
        verifyNoInteractions(analyses);
        assertEquals(201, controller.upload("p1", request(false, MAPPING, DATA, false)).getStatusCode().value());
    }
    @Test void adminDeleteUsesAuthenticatedActorAndMissingAnalysisStaysMissing() {
        allow(); when(analyses.delete("analysis-1", "synthetic-actor")).thenReturn(true);
        assertEquals(200, controller.delete("analysis-1", request(false, MAPPING, DATA, false)).getStatusCode().value());
        verify(analyses).delete("analysis-1", "synthetic-actor"); assertEquals(404, controller.read("missing").getStatusCode().value());
    }
    @Test void projectionMasksEveryObservedAddressNameFilenameAndUploader() {
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        var sample = new LinkedHashMap<String, Object>(); sample.put("fileName", "synthetic-private.csv"); sample.put("uploader", "synthetic-actor");
        sample.put("observedSource", List.of("192.0.2.8/31")); sample.put("observedDestination", "198.51.100.8"); sample.put("observedService", "tcp/443-444");
        sample.put("name", "RULE-SYNTHETIC"); sample.put("zeroTraffic", "No traffic in this sample (period UNKNOWN–UNKNOWN, 1 rows).");
        sample.put("memberId", "OBJECT-SYNTHETIC"); sample.put("coveragePercent", 100); sample.put("statReason", "invalid IP address");
        var masked = (Map<?, ?>) PolicyPrivacy.mask(sample, "", names, ips); String text = masked.toString();
        for (String raw : List.of("192.0.2.8", "198.51.100.8", "RULE-SYNTHETIC", "synthetic-private.csv", "synthetic-actor", "OBJECT-SYNTHETIC")) assertFalse(text.contains(raw));
        assertEquals("tcp/443-444", masked.get("observedService")); assertEquals(100, masked.get("coveragePercent"));
        assertEquals(sample.get("zeroTraffic"), masked.get("zeroTraffic")); assertEquals("invalid IP address", masked.get("statReason"));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("synthetic-secret-marker", "statReason", names, ips));
    }
}
