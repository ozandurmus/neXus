package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Insight I4 (partly shadowed by earlier rules of the same action), Check Point fixtures: the overlapping set, the action
 * relation (a different-action overlap is counted, never shown), fixed anchors (Q3), never across a layer (Q9), placement,
 * a rule-complete snapshot and budget exhaustion as NOT_PROVEN (Q8). Documentation addresses (192.0.2.0/24) only.
 */
class PolicyPartialShadowTest {
    /** The halves of the documentation /24 and a /26 inside the upper half. */
    static PolicySnapshot snapshot(Section... sections) { return PolicyUnionCoverTest.snapshot(sections); }
    static PolicyInsights.Report report(PolicySnapshot snapshot) { return PolicyInsightsTest.report(snapshot); }
    static List<PolicyInsights.Insight> i4(PolicySnapshot snapshot) { return PolicyInsightsTest.of(report(snapshot), "I4"); }
    static List<String> ids(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::ruleId).toList(); }
    static PolicyPartialShadow.Result partial(PolicySnapshot snapshot) {
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        return PolicyPartialShadow.analyze(snapshot, hygiene, PolicyUnionCover.analyze(snapshot, hygiene));
    }

    @Test void oneEarlierSameActionRuleOverlappingPartOfTheRuleIsProvenI4() {
        // r2 (the whole /24) is larger than r1 (the lower half): r1 overlaps part of r2 and r2 is not covered.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        assertEquals("NOT_SHADOWED", at(snapshot, "r2").shadowStatus());
        var found = i4(snapshot);
        assertEquals(1, found.size());
        var insight = found.get(0);
        assertEquals("PROVEN", insight.grade());
        assertEquals(List.of("r2", "r1"), ids(insight));
        assertEquals(List.of("subject", "overlapping"), insight.rules().stream().map(PolicyInsights.RuleRef::insightRole).toList());
        assertEquals(List.of(PolicyInsights.I4_PARTIAL), insight.evidence());
        assertEquals(1, insight.counter().get("overlappingRules")); assertEquals(0, insight.counter().get("moreOverlapping"));
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), insight.caveats());
        assertEquals("PROVEN", insight.raiseGrade()); assertEquals(PolicyInsights.NONE, insight.needs());
        var report = report(snapshot);
        assertEquals(1, report.byType().get("I4"));
        assertEquals(1, report.partialShadow().partial()); assertEquals(0, report.partialShadow().differentAction());
        for (String word : List.of("safe", "moved", "behaviour"))
            assertFalse(insight.evidence().get(0).toLowerCase(Locale.ROOT).contains(word), word);
        assertTrue(insight.evidence().get(0).contains("proven"));
    }
    @Test void twoEarlierRulesOverlappingTheRuleAreBothListed() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi-a", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i4(snapshot).stream().filter(i -> ids(i).get(0).equals("r3")).findFirst().orElseThrow();
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
        assertEquals(2, insight.counter().get("overlappingRules"));
        assertTrue(PolicyInsightsTest.of(report(snapshot), "I3").isEmpty(), "192.0.2.192/26 is left: not covered");
    }
    @Test void anOverlapTheOtherEarlierRulesHideIsStillListed() {
        // r2 lies inside r1, so subtracting r1 leaves r2 nothing: it still really intersects r3.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "lo", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i4(snapshot).stream().filter(i -> ids(i).get(0).equals("r3")).findFirst().orElseThrow();
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
    }
    @Test void anOverlapWithAnotherActionIsCountedAndShowsNoInsight() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Drop", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        var report = report(snapshot);
        assertTrue(PolicyInsightsTest.of(report, "I4").isEmpty());
        assertEquals(1, report.partialShadow().differentAction()); assertEquals(0, report.partialShadow().partial());
        // One different-action rule among the intersecting ones is enough, even when it is not the first.
        var mixed = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "hi-a", "any"), cp("r2", 2, "Drop", "lo", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i4(mixed).stream().noneMatch(i -> ids(i).get(0).equals("r3")));
        assertEquals(1, partial(mixed).differentAction());
        // A different-action rule that does not intersect the subject does not matter.
        var apart = snapshot(cpSection("s1", null, cp("r1", 1, "Drop", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "hi-a", "any")));
        assertTrue(i4(apart).isEmpty(), "r3 lies inside r2 (I1); r2 does not meet r1");
    }
    @Test void anApplyLayerRuleIsNeverTheSameAction() {
        var snapshot = snapshot(cpSection("s1", null, applyLayer("r1", 1, "lo", "inline-a"), cp("r2", 2, "Accept", "net", "any")));
        assertTrue(i4(snapshot).isEmpty());
        assertEquals(1, partial(snapshot).differentAction());
        var both = snapshot(cpSection("s1", null, applyLayer("r1", 1, "lo", "inline-a"), applyLayer("r2", 2, "net", "inline-a")));
        assertTrue(i4(both).isEmpty(), "two Apply Layer rules for one inline layer are still not 'the same action'");
    }
    @Test void anUnreadableAnchorIsNeverAnOverlapNorACover() {
        // r1 has a time constraint: it is an anchor, so nothing of r2 is proven to overlap an earlier readable rule.
        var timed = snapshot(cpSection("s1", null, PolicyUnionCoverTest.timed(cp("r1", 1, "Accept", "lo", "any")), cp("r2", 2, "Accept", "net", "any")));
        assertTrue(i4(timed).isEmpty());
        // An unreadable rule in between neither hides nor adds an overlap.
        var between = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("rx", 2, "Drop", "missing", "any"),
            PolicyUnionCoverTest.timed(cp("ry", 3, "Drop", "any", "any")), cp("r2", 4, "Accept", "net", "any")));
        var insight = i4(between).get(0);
        assertEquals(List.of("r2", "r1"), ids(insight));
        // An unreadable subject has no insight and is counted as such.
        var subject = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), PolicyUnionCoverTest.timed(cp("r2", 2, "Accept", "net", "any"))));
        assertTrue(i4(subject).isEmpty());
        assertEquals(Map.of(PolicyUnionCover.NOT_RESOLVED, 1), partial(subject).notEvaluable());
    }
    @Test void aRuleTheEarlierRulesCoverAndAShadowedRuleAreNoI4() {
        var union = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertEquals(1, PolicyInsightsTest.of(report(union), "I3").size());
        assertTrue(i4(union).stream().noneMatch(i -> ids(i).get(0).equals("r3")));
        var single = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "net", "any"), cp("r2", 2, "Accept", "lo", "any")));
        assertEquals(1, PolicyInsightsTest.of(report(single), "I1").size());
        assertTrue(i4(single).isEmpty());
    }
    @Test void earlierRulesOfAnotherLayerAreNeverAnOverlap() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "net", "any"), "LAYER-BRAVO")));
        assertTrue(i4(snapshot).isEmpty());
        // The same rules in one layer, two sections: the layer continues and the overlap holds.
        var continued = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")), cpSection("s2", null, cp("r2", 2, "Accept", "net", "any")));
        assertEquals(List.of("r2", "r1"), ids(i4(continued).get(0)));
    }
    @Test void anotherInstallOnTakesNoPartAndADisabledRuleNeither() {
        var installOn = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "targets"), cp("r2", 2, "Accept", "net", "targets-2")));
        assertTrue(i4(installOn).isEmpty());
        var r1 = cp("r1", 1, "Accept", "lo", "any");
        var disabled = snapshot(cpSection("s1", null,
            new Rule(r1.id(), r1.uuid(), r1.number(), r1.name(), false, r1.source(), r1.destination(), r1.service(), r1.application(), r1.action(), r1.log(), r1.comment(), r1.extras()),
            cp("r2", 2, "Accept", "net", "any")));
        assertTrue(i4(disabled).isEmpty());
        assertEquals(1, partial(disabled).evaluated(), "only the enabled rule is a subject");
    }
    @Test void anIncompleteSnapshotEvaluatesNothing() {
        var complete = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(), List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        assertTrue(i4(incomplete).isEmpty());
        var result = partial(incomplete);
        assertEquals(0, result.evaluated());
        assertEquals(Map.of(PolicyUnionCover.INCOMPLETE, 2), result.notEvaluable());
    }
    @Test void anotherVendorEvaluatesNothing() {
        for (String vendor : List.of("ASA", "FGT")) {
            var unsup = PolicyUnionCoverTest.unsupportedVendorSnapshot(vendor);
            var hygiene = PolicyHygiene.analyze(unsup, 90, NOW, 250000);
            var result = PolicyPartialShadow.analyze(unsup, hygiene, PolicyUnionCover.analyze(unsup, hygiene));
            assertEquals(0, result.evaluated(), vendor);
            assertTrue(result.rules().isEmpty(), vendor);
            assertEquals(Set.of(PolicyUnionCover.UNSUPPORTED_VENDOR), result.notEvaluable().keySet(), "every subject of another vendor is not evaluable for that one reason");
            assertFalse(result.notEvaluable().isEmpty(), vendor);
        }
    }
    @Test void theListOfOverlappingRulesIsCappedAndTheRestIsCounted() {
        List<Rule> rules = new ArrayList<>();
        int earlier = PolicyInsights.MAX_OVERLAPPING + 3;
        for (int i = 1; i <= earlier; i++) rules.add(cp("r" + i, i, "Accept", "lo", "any"));
        // A second /26 keeps the subject from being covered by the lower half alone.
        rules.add(cp("rs", earlier + 1, "Accept", "net", "any"));
        var snapshot = snapshot(cpSection("s1", null, rules.toArray(Rule[]::new)));
        var insight = i4(snapshot).stream().filter(i -> ids(i).get(0).equals("rs")).findFirst().orElseThrow();
        assertEquals(1 + PolicyInsights.MAX_OVERLAPPING, insight.rules().size());
        assertEquals(earlier, insight.counter().get("overlappingRules"));
        assertEquals(3, insight.counter().get("moreOverlapping"));
    }
    @Test void anExhaustedBudgetIsNotEvaluableNeverAWrongPartial() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi-a", "any"), cp("r3", 3, "Accept", "net", "any")));
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        var union = PolicyUnionCover.analyze(snapshot, hygiene);
        boolean sawNotProven = false, sawProven = false;
        for (int work = 0; work < 600; work++) {
            var partial = PolicyPartialShadow.analyze(snapshot, hygiene, union, work, work);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union, partial);
            for (var insight : PolicyInsightsTest.of(report, "I4")) {
                if (insight.grade().equals("PROVEN")) {
                    sawProven = true;
                    assertEquals(List.of("r3", "r1", "r2"), ids(insight), "work " + work);
                } else {
                    sawNotProven = true;
                    assertEquals("NOT_EVALUABLE", insight.grade());
                    assertEquals(List.of(PolicyInsights.I4_BUDGET), insight.evidence());
                    assertEquals(PolicyPartialShadow.BUDGET, insight.counter().get("partialReason"));
                    assertTrue(insight.caveats().isEmpty());
                }
            }
            assertEquals(partial.notProven(), report.partialShadow().notProven());
        }
        assertTrue(sawNotProven); assertTrue(sawProven);
    }
    @Test void theInsightsRouteCarriesI4AndTheAiviewProjectionKeepsItsVocabulary() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        // Layer one: r2 is partly shadowed by r1 (same action). Layer two: b2 meets a Drop rule, so it is counted, not shown.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Drop", "lo", "any"), "LAYER-BRAVO"), inLayer(cp("b2", 2, "Accept", "net", "any"), "LAYER-BRAVO")));
        JsonNode body = mapper.valueToTree(query.insights(snapshot, 0, "I4", "", 90).body());
        assertEquals(1, body.path("total").asInt());
        assertEquals(1, body.path("byType").path("I4").asInt());
        var block = body.path("partialShadow");
        assertEquals(body.path("total").asInt(), block.path("partial").asInt());
        assertEquals(1, block.path("differentAction").asInt(), "b2 meets the Drop rule b1");
        assertEquals(4, block.path("evaluated").asInt(), "four enabled subjects: r1 and b1 meet nothing, r2 and b2 meet a rule");
        assertTrue(block.has("notProven")); assertTrue(block.has("notEvaluable"));
        // The same call twice reads the cached I4 pass of the same snapshot instance.
        assertEquals(body, mapper.valueToTree(query.insights(snapshot, 0, "I4", "", 90).body()));
        JsonNode masked = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(query.insights(snapshot, 0, "I4", "", 90).body(), Map.class), "", names, ips));
        var row = masked.path("insights").get(0);
        assertEquals("I4", row.path("insightType").asText());
        assertEquals(PolicyInsights.I4_PARTIAL, row.path("evidence").get(0).asText());
        assertEquals("overlapping", row.path("rules").get(1).path("insightRole").asText());
        assertTrue(row.path("counter").path("overlappingRules").asInt() >= 1);
        assertTrue(masked.path("partialShadow").has("evaluated"));
        assertFalse(masked.toString().contains("OBJ-RULE-"), "rule names are pseudonymised for aiview");
        var detail = query.insightDetail(snapshot, body.path("insights").get(0).path("insightId").asText(), 90).orElseThrow();
        assertEquals(body.path("insights").get(0).path("rules").size(), ((List<?>) detail.body().get("rules")).size());
    }
    @Test void aiviewKeepsOnlyTheClosedPartialReasonWords() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        assertEquals("analysis budget", PolicyPrivacy.mask("analysis budget", "partialReason", names, ips));
        assertEquals("rule not resolved", PolicyPrivacy.mask("rule not resolved", "partialReason", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-7 source 192.0.2.9", "partialReason", names, ips));
        assertEquals("overlapping", PolicyPrivacy.mask("overlapping", "insightRole", names, ips));
        assertEquals("UNKNOWN", PolicyPrivacy.mask("OBJ-RULE-7", "insightRole", names, ips));
        assertEquals("Evidence withheld in AIView.", PolicyPrivacy.mask("Part of OBJ-RULE-7 overlaps.", "evidence", names, ips));
    }

    @Test void paloAltoSourcePortOverlapsZoneSupersetsUncollectedZoneUniversePhaseChainsAndAnchors() {
        String za = PolicyUnionCoverTest.ZA, zb = PolicyUnionCoverTest.ZB, zc = PolicyUnionCoverTest.ZC;

        // 1. Source-port family overlap (P2b note): tcp|s=1024-65535 overlaps plain tcp on the same destination port.
        var sameActionSp = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1-s-hi", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of())));
        assertEquals(List.of("r2", "r1"), ids(i4(sameActionSp).get(0)));

        // Different action across source-port family overlap MUST suppress I4 and count differentAction.
        var diffActionSp = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("r1", 1, "deny", List.of(za), List.of(zb), "net", "any", "svc-p1-s-hi", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of())));
        assertTrue(i4(diffActionSp).isEmpty(), "deny source-port overlap must never emit I4");
        assertEquals(1, partial(diffActionSp).differentAction());

        // 2. Partial-zone overlap on concrete zones: [ZA] overlaps [ZA, ZB] and leaves [ZB] provably uncovered.
        var partialZone = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of(za, zb), List.of(zc), "net", "any", "svc-p1", Map.of())));
        assertEquals(List.of("r2", "r1"), ids(i4(partialZone).get(0)));

        // 3. ZoneUniverse.UNKNOWN fail-closed: p ([ZA], net) before r ([any], net) cannot prove a non-empty rest (ZA might be the only zone).
        var unknownUniverseCover = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zc), "net", "any", "svc-p1", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of("any"), List.of(zc), "net", "any", "svc-p1", Map.of())));
        assertTrue(i4(unknownUniverseCover).isEmpty(), "cannot claim I4 when ZA might be the only source zone");

        // When p ([ZA], lo) leaves (ZA, hi) uncovered, non-empty rest IS proven in every zone universe -> I4!
        var provenOnNamedZone = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zc), "lo", "any", "svc-p1", Map.of()),
            PolicyUnionCoverTest.panRule("r2", 2, "allow", List.of("any"), List.of(zc), "net", "any", "svc-p1", Map.of())));
        assertEquals(List.of("r2", "r1"), ids(i4(provenOnNamedZone).get(0)));

        // 4. Panorama chain (Shared pre -> DG pre -> Local -> DG post -> Shared post) + anchors ignored as overlaps.
        var chain = PolicyUnionCoverTest.panSnapshot(
            PolicyUnionCoverTest.panSection("Shared", "pre-rulebase",
                PolicyUnionCoverTest.panRule("r1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of()),
                PolicyUnionCoverTest.panRule("ad", 2, "deny", List.of(za), List.of(zb), "any", "any", PolicyUnionCoverTest.panAppDefaultRef(), Map.of())),
            PolicyUnionCoverTest.panSection("DG-TANGO-01", "post-rulebase",
                PolicyUnionCoverTest.panRule("r2", 1, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of())));
        assertEquals(List.of("r2", "r1"), ids(i4(chain).get(0)));
    }

    @Test void paloAltoI4AgreesWithBruteForceEnumerationOnRandomRulebases() {
        Random random = new Random(2026112602L);
        int partials = 0, diffActions = 0, budgetStops = 0;
        for (int trial = 0; trial < 300; trial++) {
            var realized = PolicyUnionCoverTest.panRealize(
                PolicyUnionCoverTest.randomPanRulebase(random, trial % 2 == 0, trial % 3 != 0));
            var items = realized.items();
            var snapshot = realized.snapshot();
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250_000);
            var union = PolicyUnionCover.analyze(snapshot, hygiene);
            var result = PolicyPartialShadow.analyze(snapshot, hygiene, union);

            Map<String, Set<PolicyUnionCoverTest.PanPacket>> ptsExt = new HashMap<>();
            for (var item : items) {
                if (item.readable()) {
                    ptsExt.put(item.rule().id(), PolicyUnionCoverTest.panRulePackets(
                        item.rule(), PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
                }
            }

            Map<String, PolicyPartialShadow.Partial> expected = new LinkedHashMap<>();
            for (int k = 0; k < items.size(); k++) {
                var sub = items.get(k);
                if (!sub.readable()) continue;
                String id = sub.rule().id();
                if (Set.of("REDUNDANT", "CONFLICT").contains(hygiene.rules().get(id).shadowStatus())) continue;
                if (union.rules().containsKey(id) && PolicyUnionCover.COVERED.equals(union.rules().get(id).status())) continue;

                Set<PolicyUnionCoverTest.PanPacket> subExt = ptsExt.get(id);
                List<Rule> overlapping = new ArrayList<>();
                for (int j = 0; j < k; j++) {
                    var prior = items.get(j);
                    if (!prior.readable()) continue;
                    if (!Collections.disjoint(subExt, ptsExt.get(prior.rule().id()))) overlapping.add(prior.rule());
                }
                if (overlapping.isEmpty()) continue;

                // Minimal valid zone universe over named zones of sub and overlapping priors.
                List<String> minFrom = minZoneWorld(sub.rule().extras().get("from"), overlapping, "from");
                List<String> minTo = minZoneWorld(sub.rule().extras().get("to"), overlapping, "to");
                Set<PolicyUnionCoverTest.PanPacket> subMin = PolicyUnionCoverTest.panRulePackets(sub.rule(), minFrom, minTo);
                Set<PolicyUnionCoverTest.PanPacket> covMin = new HashSet<>();
                for (Rule o : overlapping) covMin.addAll(PolicyUnionCoverTest.panRulePackets(o, minFrom, minTo));
                if (covMin.containsAll(subMin)) continue; // could be wholly covered in minimal zone universe

                boolean same = overlapping.stream().allMatch(o -> o.action().equals(sub.rule().action()));
                expected.put(id, new PolicyPartialShadow.Partial(PolicyPartialShadow.PARTIAL, null, List.copyOf(overlapping), same));
                if (same) partials++; else diffActions++;
            }
            assertEquals(expected, result.rules(), "PAN I4 mismatch in trial " + trial);

            var limited = PolicyPartialShadow.analyze(snapshot, hygiene, union, 20 + random.nextInt(120), 10 + random.nextInt(60));
            for (var e : limited.rules().entrySet()) {
                if (PolicyPartialShadow.NOT_PROVEN.equals(e.getValue().status())) {
                    budgetStops++;
                    assertEquals(PolicyPartialShadow.BUDGET, e.getValue().reason());
                    assertFalse(e.getValue().sameAction());
                } else {
                    assertEquals(expected.get(e.getKey()), e.getValue(), "limited budget wrong PAN I4 in trial " + trial);
                }
            }
        }
        assertTrue(partials >= 30, "PAN same-action partials exercised: " + partials);
        assertTrue(diffActions >= 15, "PAN different-action overlaps exercised: " + diffActions);
        assertTrue(budgetStops >= 15, "PAN budget stops exercised: " + budgetStops);
    }

    @Test void paloAltoBudgetAppliesToDisjointBucketsAndInnerPriorZoneScan() {
        String za = PolicyUnionCoverTest.ZA, zb = PolicyUnionCoverTest.ZB, zc = PolicyUnionCoverTest.ZC;
        // Disjoint buckets with workLimit=0 or ruleLimit=1 must fail closed to NOT_PROVEN.
        List<Rule> disjoint = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            disjoint.add(PolicyUnionCoverTest.panRule("d" + i, i + 1, "allow", List.of("ZONE-DISJOINT-" + i), List.of(zc), "lo", "any", "svc-p1", Map.of()));
        }
        disjoint.add(PolicyUnionCoverTest.panRule("sub", 13, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of()));
        var snap = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase", disjoint.toArray(Rule[]::new)));
        var hygiene = PolicyHygiene.analyze(snap, 90, NOW, 250_000);
        var union = PolicyUnionCover.analyze(snap, hygiene);
        var zero = PolicyPartialShadow.analyze(snap, hygiene, union, 0, 0);
        assertEquals(PolicyPartialShadow.NOT_PROVEN, zero.rules().get("sub").status());
        assertEquals(PolicyPartialShadow.BUDGET, zero.rules().get("sub").reason());
        var tight = PolicyPartialShadow.analyze(snap, hygiene, union, 10_000, 1);
        assertEquals(PolicyPartialShadow.NOT_PROVEN, tight.rules().get("sub").status());
        assertEquals(PolicyPartialShadow.BUDGET, tight.rules().get("sub").reason());

        // Inner hitMatches x (testFrom x testTo) scan must spend budget per prior checked.
        List<Rule> manyHits = new ArrayList<>();
        List<String> zones = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String z = "ZONE-HIT-" + i;
            zones.add(z);
            manyHits.add(PolicyUnionCoverTest.panRule("p" + i, i + 1, "allow", List.of(z), List.of(zb), "lo", "any", "svc-p1", Map.of()));
        }
        manyHits.add(PolicyUnionCoverTest.panRule("sub", 9, "allow", zones, List.of(zb), "net", "any", "svc-p1", Map.of()));
        var hitSnap = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase", manyHits.toArray(Rule[]::new)));
        var hitHygiene = PolicyHygiene.analyze(hitSnap, 90, NOW, 250_000);
        var hitUnion = PolicyUnionCover.analyze(hitSnap, hitHygiene);
        boolean sawInnerBudget = false;
        for (int limit = 1; limit < 120; limit++) {
            var res = PolicyPartialShadow.analyze(hitSnap, hitHygiene, hitUnion, 10_000, limit);
            if (res.rules().containsKey("sub") && PolicyPartialShadow.NOT_PROVEN.equals(res.rules().get("sub").status())) {
                sawInnerBudget = true;
            }
        }
        assertTrue(sawInnerBudget, "tight ruleLimit must stop inner PAN partial scan with NOT_PROVEN");
    }

    private static List<String> minZoneWorld(List<String> subZones, List<Rule> overlapping, String key) {
        if (!subZones.equals(List.of("any"))) return subZones;
        Set<String> named = new TreeSet<>();
        for (Rule o : overlapping) {
            List<String> z = o.extras().get(key);
            if (!z.equals(List.of("any"))) named.addAll(z);
        }
        return named.isEmpty() ? List.of(PolicyUnionCoverTest.ZA) : List.copyOf(named);
    }
}
