package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ACI-6: the aiview projection of cutover answers masks addresses, blocks, names and rules, and withholds unknown fields. */
class CutoverPrivacyTest {
    static final TopologyNamePseudonymizer NAMES = new TopologyNamePseudonymizer("k".repeat(32).getBytes(StandardCharsets.UTF_8));
    static final SubnetPreservingIpMasker IPS = new SubnetPreservingIpMasker("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    @Test void addressesNamesAndRulesAreMaskedAndUnknownFieldsWithheld() {
        var rule = Map.<String, Object>of("ruleId", "r1", "number", 1, "name", "OBJ-RULE-r1", "sectionId", "s1", "sectionName", "OBJ-SECTION-01", "action", "allow");
        var flow = new LinkedHashMap<String, Object>();
        flow.put("flowSource", "192.0.2.8"); flow.put("flowDestination", "198.51.100.8"); flow.put("flowSources", List.of("192.0.2.9"));
        flow.put("sourceRange", "192.0.2.1-192.0.2.200"); flow.put("decision", "WOULD_DROP"); flow.put("decisionReason", "first matching rule");
        flow.put("direction", "SEGMENT_TO_PEER"); flow.put("flags", List.of("CONDITIONAL_RULE", "INVENTED_FLAG")); flow.put("decidingRule", rule);
        flow.put("protocol", "tcp"); flow.put("port", 443); flow.put("firstSeen", "2026-11-01T10:00:00Z"); flow.put("droppedConstraints", List.of("source-user"));
        var body = new LinkedHashMap<String, Object>();
        body.put("segmentId", "00000000-0000-4000-8000-000000000001"); body.put("name", "SEGMENT REAL NAME");
        body.put("blocks", List.of("192.0.2.0/24")); body.put("peerBlocks", List.of("198.51.100.0/24"));
        body.put("segmentZone", "REAL-ZONE-NAME"); body.put("actor", "actor-fingerprint");
        body.put("readiness", "BLOCKERS_FOUND"); body.put("headline", "1 of 2 evaluated flows would be dropped by the target policy.");
        body.put("coverage", List.of(Map.of("code", "LOG_PERIOD", "periodStart", "2026-11-01T10:00:00Z"), Map.of("code", "SOMETHING_ELSE")));
        body.put("items", List.of(flow)); body.put("unexpected", "free text with 192.0.2.8"); body.put("stale", false);

        var masked = CutoverPrivacy.mask(body, NAMES, IPS);
        var item = (Map<?, ?>) ((List<?>) masked.get("items")).get(0);
        assertNotEquals("192.0.2.8", item.get("flowSource")); assertNotEquals("198.51.100.8", item.get("flowDestination"));
        assertNotEquals(List.of("192.0.2.9"), item.get("flowSources")); assertFalse(item.get("sourceRange").toString().contains("192.0.2.200"));
        assertNotEquals(List.of("192.0.2.0/24"), masked.get("blocks")); assertNotEquals("SEGMENT REAL NAME", masked.get("name"));
        assertNotEquals("REAL-ZONE-NAME", masked.get("segmentZone")); assertNotEquals("actor-fingerprint", masked.get("actor"));
        assertNotEquals("OBJ-RULE-r1", ((Map<?, ?>) item.get("decidingRule")).get("name"));
        assertEquals(List.of("CONDITIONAL_RULE", "UNKNOWN"), item.get("flags")); assertEquals("WOULD_DROP", item.get("decision"));
        assertEquals(List.of("source-user"), item.get("droppedConstraints")); assertEquals(443, item.get("port"));
        assertEquals("BLOCKERS_FOUND", masked.get("readiness")); assertEquals(body.get("headline"), masked.get("headline"));
        assertEquals("UNKNOWN", ((Map<?, ?>) ((List<?>) masked.get("coverage")).get(1)).get("code"));
        assertEquals(CutoverPrivacy.WITHHELD, masked.get("unexpected")); assertEquals(false, masked.get("stale"));
        assertEquals("00000000-0000-4000-8000-000000000001", masked.get("segmentId"));
        // The same address masks to the same value wherever it appears (one masker, one projection).
        assertEquals(CutoverPrivacy.mask(Map.of("flowSource", "192.0.2.8"), NAMES, IPS).get("flowSource"), item.get("flowSource"));
    }

    @Test void headlinesOutsideTheFixedTemplatesAreWithheld() {
        assertEquals(CutoverPrivacy.WITHHELD, CutoverPrivacy.mask(Map.of("headline", "Cutover is safe for 192.0.2.8"), NAMES, IPS).get("headline"));
        for (String headline : List.of(String.format(Locale.ROOT, CutoverAssessment.HEADLINE_NO_BLOCKERS, 7),
                String.format(Locale.ROOT, CutoverAssessment.HEADLINE_NOT_DECIDED, 3, 9), CutoverAssessment.HEADLINE_NO_EVIDENCE))
            assertEquals(headline, CutoverPrivacy.mask(Map.of("headline", headline), NAMES, IPS).get("headline"));
    }

    @Test void theResponseAdviceRefusesAnUnmaskedCutoverBodyForAiview() {
        var advice = new PrivacyMaskingResponseBodyAdvice(IPS, NAMES);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.securityexpert.nexus.ui2.service.security.GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        var server = new org.springframework.http.server.ServletServerHttpRequest(request);
        var response = new org.springframework.http.server.ServletServerHttpResponse(new org.springframework.mock.web.MockHttpServletResponse());
        assertThrows(IllegalStateException.class, () -> advice.beforeBodyWrite(new CutoverResponse(false, Map.of("flowSource", "192.0.2.8")),
            null, null, null, server, response));
        var body = Map.<String, Object>of("flowSource", "MASKED");
        assertSame(body, advice.beforeBodyWrite(new CutoverResponse(true, body), null, null, null, server, response));
    }
}
