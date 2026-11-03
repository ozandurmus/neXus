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
    @Test void defaultViewListsFindingsOnlyAndCoverageSplitsShadowFromHitCountersPolicyWide() throws Exception {
        // r1 resolves cleanly (NOT_SHADOWED, not broad) and has no hit counters: its only result is "unknown".
        // r2 is any/any allow: a broad finding plus the same missing counters. Neither has a shadow.
        var base = PolicyHygieneTest.rule("r1", "host", "port", "allow", true);
        var clean = new Rule(base.id(), base.uuid(), 1, base.name(), true, base.source(), PolicyHygieneTest.cell("host"), base.service(), base.application(), base.action(), base.log(), "", base.extras());
        var broad = PolicyHygieneTest.rule("r2", "any", "any", "allow", true);
        var snapshot = PolicyHygieneTest.snapshot(List.of(clean, broad), PolicyHygieneTest.objects());
        var page = mapper.valueToTree(query.hygiene(snapshot, 0, "", "", 90).body());
        assertEquals(1, page.path("total").asInt(), "a rule whose only result is unknown is not a finding in the default view");
        assertEquals("r2", page.path("rows").get(0).path("ruleId").asText());
        assertEquals(2, page.path("counts").path("unknown").asInt(), "the unknown chip still counts every such rule");
        assertEquals(2, mapper.valueToTree(query.hygiene(snapshot, 0, "unknown", "", 90).body()).path("total").asInt());
        assertEquals(2, mapper.valueToTree(query.hygiene(snapshot, 0, "", "LOW", 90).body()).path("counts").path("unknown").asInt());
        assertEquals(1, mapper.valueToTree(query.hygiene(snapshot, 0, "", "HIGH", 90).body()).path("total").asInt(), "r2's any/any accept is a HIGH broad finding");
        assertEquals(0, mapper.valueToTree(query.hygiene(snapshot, 0, "", "LOW", 90).body()).path("total").asInt(), "a severity filter alone never admits unknown-only rows");
        var coverage = page.path("coverage");
        assertEquals(2, coverage.path("rules").asInt());
        assertEquals(2, coverage.path("shadowProven").asInt(), "missing hit counters never make a decided shadow result unproven");
        assertEquals(0, coverage.path("shadowUnknown").asInt());
        assertEquals(0, coverage.path("hitsCollected").asInt()); assertEquals(2, coverage.path("hitsMissing").asInt());
        var reasons = page.path("unknownReasons");
        assertEquals(1, reasons.size());
        assertEquals("Hit counts were not collected or are invalid.", reasons.get(0).path("evidence").asText());
        assertEquals(2, reasons.get(0).path("rules").asInt());
        // With counters stored the hit coverage flips and the reason list empties, the shadow coverage unchanged.
        var counted = PolicyHygieneTest.snapshot(List.of(clean.withHitCounts(new HitCounts(3L, null, PolicyHygieneTest.NOW.minusSeconds(3600).toString(), "mds", PolicyHygieneTest.NOW.toString(), "low", List.of())),
            broad.withHitCounts(new HitCounts(0L, null, null, "mds", PolicyHygieneTest.NOW.toString(), "zero", List.of()))), PolicyHygieneTest.objects());
        var countedPage = mapper.valueToTree(query.hygiene(counted, 0, "", "", 90).body());
        assertEquals(2, countedPage.path("coverage").path("hitsCollected").asInt()); assertEquals(0, countedPage.path("coverage").path("hitsMissing").asInt());
        assertEquals(2, countedPage.path("coverage").path("shadowProven").asInt());
        assertEquals(0, countedPage.path("unknownReasons").size());
        // The AIView projection keeps the numbers and the fixed evidence text; a foreign reason string is withheld.
        var masked = mapper.valueToTree(PolicyPrivacy.mask(query.hygiene(snapshot, 0, "", "", 90).body(), "", names, ips));
        assertEquals(2, masked.path("coverage").path("shadowProven").asInt()); assertEquals(2, masked.path("coverage").path("hitsMissing").asInt());
        assertEquals("Hit counts were not collected or are invalid.", masked.path("unknownReasons").get(0).path("evidence").asText());
        assertEquals(2, masked.path("unknownReasons").get(0).path("rules").asInt());
        var foreign = mapper.valueToTree(PolicyPrivacy.mask(Map.of("unknownReasons", List.of(Map.of("evidence", "Synthetic private 192.0.2.8", "rules", 1))), "", names, ips));
        assertEquals("Evidence withheld in AIView.", foreign.path("unknownReasons").get(0).path("evidence").asText());
    }
    @Test void cachedSnapshotReplacementAndThresholdChangesInvalidateResults() {
        var r = PolicyHygieneTest.rule("r1", "host", "port", "allow", true);
        var first = PolicyHygieneTest.snapshot(List.of(r), PolicyHygieneTest.objects());
        var a = query.analysis(first, 90); assertSame(a, query.analysis(first, 90));
        assertNotSame(a, query.analysis(first, 10));
        var replaced = PolicyHygieneTest.snapshot(List.of(r, PolicyHygieneTest.rule("r2", "host", "port", "allow", true)), first.objects());
        assertEquals(2, query.analysis(replaced, 90).rules().size());
    }
    @Test void concurrentRequestsShareOneAnalysisAndOtherSnapshotsAreNotBlocked() throws Exception {
        var large = SyntheticPolicies.large("CP", 12_000, 7);
        var small = PolicyHygieneTest.snapshot(List.of(PolicyHygieneTest.rule("r1", "host", "port", "allow", true)), PolicyHygieneTest.objects());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<PolicyHygiene.Result>> same = new ArrayList<>();
            for (int i = 0; i < 3; i++) same.add(pool.submit(() -> { start.await(); return query.analysis(large, 90); }));
            start.countDown();
            Thread.sleep(150);
            // The large snapshot is still being analysed; the small one is answered without waiting for it.
            long begun = System.nanoTime();
            var other = query.analysis(small, 90);
            long waited = (System.nanoTime() - begun) / 1_000_000;
            assertTrue(same.stream().noneMatch(java.util.concurrent.Future::isDone), "large analysis finished before the probe");
            assertTrue(waited < 500, "small snapshot waited " + waited + " ms");
            assertEquals("NOT_SHADOWED", other.rules().get("r1").shadowStatus());
            // Single flight: every concurrent caller of the large snapshot receives the one computed result.
            var first = same.get(0).get(60, java.util.concurrent.TimeUnit.SECONDS);
            for (var future : same) assertSame(first, future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            assertSame(first, query.analysis(large, 90));
            assertEquals(12_000, first.rules().size());
        } finally { pool.shutdownNow(); }
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
