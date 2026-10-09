package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;

class PolicyHygieneQueryTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    final PolicyQueryService query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
    @Test void paginationFiltersAndPerRuleLookupUseWholeSnapshot() {
        var rules = IntStream.range(0, 205).mapToObj(i -> PolicyHygieneTest.rule("r" + i, "any", "any", "allow", true)).toList();
        var snapshot = PolicyHygieneTest.snapshot(rules, PolicyHygieneTest.objects());
        var page = mapper.valueToTree(query.hygiene(snapshot, 1, "shadowed", "MEDIUM", 90).body());
        assertEquals(204, page.path("total").asInt()); assertEquals(4, page.path("rows").size());
        assertEquals(205, page.path("counts").path("broad").asInt());
        assertEquals("r0", mapper.valueToTree(query.rule(snapshot, "r204", 90).orElseThrow().body()).path("rule").path("hygiene").path("coveringRuleId").asText());
        assertTrue(query.rule(snapshot, "missing", 90).isEmpty());
        assertEquals(0, query.hygiene(snapshot, 0, "shadowed", "HIGH", 90).body().get("total"));
        assertEquals(0, ((List<?>) query.hygiene(snapshot, Integer.MAX_VALUE, "", "", 90).body().get("rows")).size());
    }
    @Test void cachedSnapshotReplacementAndThresholdChangesInvalidateResults() {
        var r = PolicyHygieneTest.rule("r1", "host", "port", "allow", true);
        var first = PolicyHygieneTest.snapshot(List.of(r), PolicyHygieneTest.objects());
        var a = query.analysis(first, 90); assertSame(a, query.analysis(first, 90));
        assertNotSame(a, query.analysis(first, 10));
        var replaced = PolicyHygieneTest.snapshot(List.of(r, PolicyHygieneTest.rule("r2", "host", "port", "allow", true)), first.objects());
        assertEquals(2, query.analysis(replaced, 90).rules().size());
    }
    @Test void aiviewMasksListRuleAndCsvAndRetainsSafeEvidence() throws Exception {
        var original = PolicyHygieneTest.rule("r1", "host", "port", "allow", true);
        var r = new Rule(original.id(), original.uuid(), 1, "Synthetic confidential rule 192.0.2.8", true, original.source(), original.destination(), original.service(), original.application(),
            original.action(), original.log(), "Synthetic private description", Map.of(), new HitCounts(0L, null, null, "mds", PolicyHygieneTest.NOW.toString(), "zero", List.of()));
        var s = PolicyHygieneTest.snapshot(List.of(r), PolicyHygieneTest.objects());
        for (var response : List.of(query.hygiene(s, 0, "", "", 90), query.rule(s, "r1", 90).orElseThrow(), query.page(s, 0, "", true))) {
            String json = mapper.writeValueAsString(PolicyPrivacy.mask(response.body(), "", names, ips));
            assertFalse(json.contains(r.name())); assertFalse(json.contains("192.0.2.8")); assertFalse(json.contains(r.comment()));
            assertTrue(json.contains("Zero collected hits; counter reset/window is unknown."));
        }
        String csv = new String(query.hygieneCsv(s, "", "", 90, true), StandardCharsets.UTF_8);
        assertFalse(csv.contains(r.name())); assertFalse(csv.contains("192.0.2.8"));
        assertTrue(csv.contains(names.maskPolicyName("rule", r.name())));
        assertTrue(csv.contains("mds")); assertTrue(csv.contains("Counter window unknown"));
        var malicious = Map.of("shadowReason", "Synthetic private description 192.0.2.8", "evidence", r.comment(), "hitSource", r.name());
        String masked = mapper.writeValueAsString(PolicyPrivacy.mask(malicious, "", names, ips));
        assertFalse(masked.contains("Synthetic")); assertFalse(masked.contains("192.0.2.8"));
    }
    @Test void csvUsesFiltersAndEscapesSpreadsheetFormulas() {
        var r = PolicyHygieneTest.rule("r1", "any", "any", "allow", false);
        r = new Rule(r.id(), r.uuid(), 1, " =1+1", false, r.source(), r.destination(), r.service(), r.application(), r.action(), r.log(), "", r.extras());
        String csv = new String(query.hygieneCsv(PolicyHygieneTest.snapshot(List.of(r), PolicyHygieneTest.objects()), "disabled", "LOW", 90, false), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"' =1+1\"")); assertTrue(csv.contains("Rule is disabled.")); assertFalse(csv.contains("counter reset"));
    }
}
