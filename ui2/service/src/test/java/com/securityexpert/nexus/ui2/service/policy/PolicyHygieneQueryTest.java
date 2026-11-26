package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
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
        var large = SyntheticPolicies.large("CP", 200, 7);
        var small = PolicyHygieneTest.snapshot(List.of(PolicyHygieneTest.rule("r1", "host", "port", "allow", true)), PolicyHygieneTest.objects());
        var service = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        var largeRuns = new java.util.concurrent.atomic.AtomicInteger();
        var largeEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseLarge = new java.util.concurrent.CountDownLatch(1);
        service.setHygieneComputer((snapshot, days, now) -> {
            if (snapshot == large) {
                largeRuns.incrementAndGet();
                largeEntered.countDown();
                try {
                    assertTrue(releaseLarge.await(10, java.util.concurrent.TimeUnit.SECONDS), "timed out waiting to release large analysis");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while holding large analysis", e);
                }
            }
            return service.compute(snapshot, days, now);
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var submitted = new java.util.concurrent.CountDownLatch(3);
            List<java.util.concurrent.Future<PolicyHygiene.Result>> same = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                same.add(pool.submit(() -> {
                    submitted.countDown();
                    submitted.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return service.analysis(large, 90);
                }));
            }
            assertTrue(submitted.await(5, java.util.concurrent.TimeUnit.SECONDS), "worker tasks did not start in time");
            assertTrue(largeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "large analysis did not enter compute");
            // While large is held in flight, a different snapshot completes without blocking and no large future is done.
            var other = service.analysis(small, 90);
            assertEquals("NOT_SHADOWED", other.rules().get("r1").shadowStatus());
            assertTrue(same.stream().noneMatch(java.util.concurrent.Future::isDone), "large analysis finished before release");
            // Single flight: releasing the held computation lets all concurrent callers receive the same instance.
            releaseLarge.countDown();
            var first = same.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS);
            for (var future : same) assertSame(first, future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertSame(first, service.analysis(large, 90));
            assertEquals(1, largeRuns.get());
            assertEquals(200, first.rules().size());
        } finally {
            releaseLarge.countDown();
            pool.shutdownNow();
        }
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
    @Test void hygieneCsvFailsClosedOnNonAllowlistedFindingFieldsInAiviewAndPreservesRawForAdmin() {
        var service = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        var base = PolicyHygieneTest.rule("r1", "host", "port", "allow", true);
        var r = new Rule(base.id(), base.uuid(), 7, "Synthetic rule 192.0.2.44", true, base.source(), base.destination(), base.service(), base.application(),
            base.action(), base.log(), "", Map.of());
        var snapshot = PolicyHygieneTest.snapshot(List.of(r), PolicyHygieneTest.objects());
        service.setHygieneComputer((snap, days, now) -> new PolicyHygiene.Result(Map.of("r1",
            new PolicyHygiene.Assessment("UNKNOWN", "Synthetic shadow reason 192.0.2.44", "r-cover-1",
                List.of(
                    new PolicyHygiene.Finding("custom_class", "CRITICAL", "Synthetic raw leak 192.0.2.44"),
                    new PolicyHygiene.Finding("unused", "LOW", PolicyHygiene.ASA_ACL_NOT_REFERENCED)),
                PolicyRuleMetrics.permissiveness("Unknown", List.of(), 0), "unknown", false,
                "Synthetic window 192.0.2.44", "Synthetic source", "not-a-timestamp")), false));

        String maskedCsv = new String(service.hygieneCsv(snapshot, "", "", 90, true), StandardCharsets.UTF_8);
        assertFalse(maskedCsv.contains("Synthetic"));
        assertFalse(maskedCsv.contains("192.0.2.44"));
        assertFalse(maskedCsv.contains("custom_class"));
        assertFalse(maskedCsv.contains("CRITICAL"));
        assertTrue(maskedCsv.contains("\"" + names.maskPolicyName("rule", r.name())
            + "\",\"7\",\"unknown\",\"LOW\",\"Evidence withheld in AIView.\",\"r-cover-1\",\"Counter window unknown.\",\"UNKNOWN\",\"\"\r\n"));
        assertTrue(maskedCsv.contains("\"" + names.maskPolicyName("rule", r.name())
            + "\",\"7\",\"unused\",\"LOW\",\"" + PolicyHygiene.ASA_ACL_NOT_REFERENCED + "\",\"r-cover-1\",\"Counter window unknown.\",\"UNKNOWN\",\"\"\r\n"));

        String filteredMaskedCsv = new String(service.hygieneCsv(snapshot, "unused", "LOW", 90, true), StandardCharsets.UTF_8);
        assertFalse(filteredMaskedCsv.contains("Evidence withheld in AIView."));
        assertTrue(filteredMaskedCsv.contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED));

        String adminCsv = new String(service.hygieneCsv(snapshot, "", "", 90, false), StandardCharsets.UTF_8);
        assertTrue(adminCsv.contains("\"Synthetic rule 192.0.2.44\",\"7\",\"custom_class\",\"CRITICAL\",\"Synthetic raw leak 192.0.2.44\","
            + "\"r-cover-1\",\"Synthetic window 192.0.2.44\",\"Synthetic source\",\"not-a-timestamp\"\r\n"));
    }
    @Test void hygieneCsvPreservesAllowlistedEvidenceAndShadowReasonsAndMasksAsaUnboundAclNames() {
        String asaConfig = """
                ASA Version 9.18(4)22
                interface GigabitEthernet0/0
                 nameif nif-cardholder-out
                 security-level 0
                access-list ACL-BOUND-192-0-2-55 extended permit tcp any host 198.51.100.20 eq 443 time-range TR-CORP
                access-list ACL-ORPHAN-192-0-2-66 extended permit ip any any
                access-group ACL-BOUND-192-0-2-55 in interface nif-cardholder-out
                """;
        var asaSnapshot = AsaPolicyMapper.map("dev-asa-1", "asa-raw-gw01", "2026-11-26T00:00:00Z", "artefact",
            AsaConfigPolicyParser.parse(asaConfig));
        String maskedAsaCsv = new String(query.hygieneCsv(asaSnapshot, "", "", 90, true), StandardCharsets.UTF_8);
        assertFalse(maskedAsaCsv.contains("ACL-ORPHAN-192-0-2-66"));
        assertFalse(maskedAsaCsv.contains("ACL-BOUND-192-0-2-55"));
        assertFalse(maskedAsaCsv.contains("192-0-2-55"));
        assertFalse(maskedAsaCsv.contains("192-0-2-66"));
        assertFalse(maskedAsaCsv.contains("asa-raw-gw01"));
        assertTrue(maskedAsaCsv.contains(names.maskPolicyName("rule", "ACL-ORPHAN-192-0-2-66")));
        assertTrue(maskedAsaCsv.contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED));
        assertTrue(maskedAsaCsv.contains("Shadowing: time or content constraint."));

        var service = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        List<PolicyHygiene.Finding> allowlisted = new ArrayList<>();
        for (String evidence : PolicyHygiene.EVIDENCE) allowlisted.add(new PolicyHygiene.Finding("unused", "LOW", evidence));
        for (String reason : PolicyHygiene.REASONS) allowlisted.add(new PolicyHygiene.Finding("unknown", "LOW", "Shadowing: " + reason + "."));
        var rule = PolicyHygieneTest.rule("r1", "host", "port", "allow", true);
        var snapshot = PolicyHygieneTest.snapshot(List.of(rule), PolicyHygieneTest.objects());
        service.setHygieneComputer((snap, days, now) -> new PolicyHygiene.Result(Map.of("r1",
            new PolicyHygiene.Assessment("UNKNOWN", "analysis budget", null, allowlisted,
                PolicyRuleMetrics.permissiveness("Unknown", List.of(), 0), "unknown", false,
                "Counter window unknown.", "device", "2026-11-26T00:00:00Z")), false));
        String allowlistedCsv = new String(service.hygieneCsv(snapshot, "", "", 90, true), StandardCharsets.UTF_8);
        assertFalse(allowlistedCsv.contains("Evidence withheld in AIView."));
        for (String evidence : PolicyHygiene.EVIDENCE) assertTrue(allowlistedCsv.contains(evidence), evidence);
        for (String reason : PolicyHygiene.REASONS) assertTrue(allowlistedCsv.contains("Shadowing: " + reason + "."), reason);
    }
    @Test void fgtAndAsaLocalSnapshotsMaskAllRawValuesAcrossPageRuleHygieneAndCsv() throws Exception {
        String asaConfig = """
                ASA Version 9.18(4)22
                hostname asa-edge-raw01
                interface GigabitEthernet0/0
                 nameif nif-raw-outside
                 security-level 0
                 ip address 203.0.113.20 255.255.255.0
                interface GigabitEthernet0/1
                 nameif nif-raw-inside
                 security-level 100
                object network asah-raw-app01
                 host 203.0.113.21
                access-list ACL-RAW-OUT remark approved by admraw.ops for asa-edge-raw01
                access-list ACL-RAW-OUT extended permit tcp any object asah-raw-app01 eq 443 log
                access-list ACL-RAW-OUT extended permit tcp any object asah-raw-app01 eq 443 log
                access-list ACL-RAW-ORPHAN extended permit ip any any
                access-group ACL-RAW-OUT in interface nif-raw-outside
                """;
        PolicySnapshot asa = AsaPolicyMapper.map("dev-asa-raw", "asa-edge-raw01", "2026-11-26T00:00:00Z", null,
            AsaConfigPolicyParser.parse(asaConfig));
        String asaBoundRuleId = asa.sections().get(0).rules().get(0).id();
        String asaUnboundRuleId = asa.sections().stream().filter(s -> "ASA not evaluated".equals(s.name())).findFirst().orElseThrow()
            .rules().get(0).id();
        List<String> asaRaw = List.of("asa-edge-raw01", "nif-raw-outside", "nif-raw-inside", "203.0.113.20", "203.0.113.21",
            "asah-raw-app01", "ACL-RAW-OUT", "ACL-RAW-ORPHAN", "admraw.ops");
        for (var resp : List.of(query.page(asa, 0, "", true), query.rule(asa, asaUnboundRuleId, 90).orElseThrow(), query.hygiene(asa, 0, "", "", 90))) {
            String json = mapper.writeValueAsString(PolicyPrivacy.mask(resp.body(), "", names, ips));
            for (String secret : asaRaw) assertFalse(json.contains(secret), secret);
        }
        String asaHygieneJson = mapper.writeValueAsString(PolicyPrivacy.mask(query.hygiene(asa, 0, "", "", 90).body(), "", names, ips));
        assertTrue(asaHygieneJson.contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED));
        assertTrue(asaHygieneJson.contains("\"shadowStatus\":\"REDUNDANT\""));
        assertTrue(asaHygieneJson.contains(asaBoundRuleId));
        String asaCsv = new String(query.hygieneCsv(asa, "", "", 90, true), StandardCharsets.UTF_8);
        assertTrue(asaCsv.contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED));
        assertTrue(asaCsv.contains(asaBoundRuleId));
        for (String secret : asaRaw) assertFalse(asaCsv.contains(secret), secret);

        String fgtConfig = """
                #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin
                config system global
                    set hostname "fgt-edge-raw01"
                end
                config system interface
                    edit "if-raw-wan"
                        set vdom "root"
                        set ip 203.0.113.30 255.255.255.0
                    next
                    edit "if-raw-lan"
                        set vdom "root"
                    next
                end
                config firewall address
                    edit "fgth-raw-src"
                        set subnet 203.0.113.31 255.255.255.255
                    next
                    edit "fgth-raw-dst"
                        set subnet 203.0.113.32 255.255.255.255
                    next
                end
                config firewall service custom
                    edit "HTTPS"
                        set tcp-portrange 443
                    next
                end
                config firewall policy
                    edit 1
                        set name "fgtpol-raw-allow"
                        set srcintf "if-raw-wan"
                        set dstintf "if-raw-lan"
                        set srcaddr "fgth-raw-src"
                        set dstaddr "fgth-raw-dst"
                        set action accept
                        set schedule "always"
                        set service "HTTPS"
                        set logtraffic all
                        set comments "approved by fgtadm.sec on fgt-edge-raw01"
                    next
                    edit 2
                        set name "fgtpol-raw-shadow"
                        set srcintf "if-raw-wan"
                        set dstintf "if-raw-lan"
                        set srcaddr "fgth-raw-src"
                        set dstaddr "fgth-raw-dst"
                        set action accept
                        set schedule "always"
                        set service "HTTPS"
                        set logtraffic all
                        set comments "shadowed policy by fgtadm.sec"
                    next
                end
                """;
        PolicySnapshot mappedFgt = FortiGatePolicyMapper.map("dev-fgt-raw", "fgt-edge-raw01", "2026-11-26T00:00:00Z", null,
            FortiGateConfigPolicyParser.parse(fgtConfig)).get(0);
        List<Section> fgtSections = mappedFgt.sections().stream().map(s -> "FGT firewall policy".equals(s.source())
            ? new Section(s.id(), s.name(), s.source(), s.parentRuleId(), s.rules().stream().map(r -> {
                Map<String, List<String>> extras = new LinkedHashMap<>(r.extras());
                extras.put("last-modifier", List.of("fgtadm.sec"));
                return new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), r.source(), r.destination(),
                    r.service(), r.application(), r.action(), r.log(), r.comment(), extras);
            }).toList()) : s).toList();
        PolicySnapshot fgt = new PolicySnapshot(mappedFgt.metadata(), fgtSections, mappedFgt.objects(), mappedFgt.failures());
        String fgtRuleId = fgt.sections().get(0).rules().get(0).id();
        String fgtShadowRuleId = fgt.sections().get(0).rules().get(1).id();
        List<String> fgtRaw = List.of("fgt-edge-raw01", "if-raw-wan", "if-raw-lan", "203.0.113.30", "203.0.113.31", "203.0.113.32",
            "fgth-raw-src", "fgth-raw-dst", "fgtpol-raw-allow", "fgtpol-raw-shadow", "fgtadm.sec");
        for (var resp : List.of(query.page(fgt, 0, "", true), query.rule(fgt, fgtShadowRuleId, 90).orElseThrow(), query.hygiene(fgt, 0, "", "", 90))) {
            String json = mapper.writeValueAsString(PolicyPrivacy.mask(resp.body(), "", names, ips));
            for (String secret : fgtRaw) assertFalse(json.contains(secret), secret);
        }
        String fgtHygieneJson = mapper.writeValueAsString(PolicyPrivacy.mask(query.hygiene(fgt, 0, "", "", 90).body(), "", names, ips));
        assertTrue(fgtHygieneJson.contains("\"shadowStatus\":\"REDUNDANT\""));
        assertTrue(fgtHygieneJson.contains(fgtRuleId));
        String fgtCsv = new String(query.hygieneCsv(fgt, "", "", 90, true), StandardCharsets.UTF_8);
        assertTrue(fgtCsv.contains("\"shadowed\""));
        assertTrue(fgtCsv.contains("Earlier enabled rule fully covers this match with the same action."));
        assertTrue(fgtCsv.contains(fgtRuleId));
        for (String secret : fgtRaw) assertFalse(fgtCsv.contains(secret), secret);
    }
}
