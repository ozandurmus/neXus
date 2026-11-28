package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.cp;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PolicyProposalView} (phase P2 step c-2c): plain accepted move and stacked stored rule numbers, unsupported
 * vendor, incomplete snapshot, subject cap ({@link PolicyProposalView#MAX_SUBJECTS}) with truncation count, section-order
 * cap, budget stop, AIView closed-vocabulary projection (no planted name survives), fixed caveat, single-flight
 * computation per snapshot instance, bounded cache and cache eviction on failure. Documentation addresses
 * (192.0.2.0/24, 198.51.100.0/24) and synthetic masked-style labels only.
 */
class PolicyProposalViewTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);

    private JsonNode json(Object body) {
        return mapper.valueToTree(body);
    }

    private JsonNode masked(Object body) {
        return json(PolicyPrivacy.mask(mapper.convertValue(body, Map.class), "", names, ips));
    }

    @Test
    void plainAcceptedMoveCarriesStoredNumbersBeforeAfterOrderAndRefusalMap() {
        var view = new PolicyProposalView();
        var stored = PolicyProposalTest.snapshot(cpSection("s1", null,
                cp("r1", 10, "Accept", "lo", "any"),
                cp("r2", 20, "Accept", "net", "any")),
                cpSection("s2", null, cp("r3", 30, "Drop", "other", "any")));
        var body = json(view.response(stored, 90).body());

        assertTrue(body.path("supported").asBoolean());
        assertEquals("CP", body.path("vendor").asText());
        assertEquals(90, body.path("days").asInt());
        assertTrue(body.path("complete").asBoolean());
        assertEquals("EQUIVALENT", body.path("packageVerdict").asText());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, body.path("label").asText());
        assertEquals(1, body.path("accepted").asInt());
        assertFalse(body.path("inspectionChanges").asBoolean());
        assertFalse(body.path("budgetStopped").asBoolean());
        assertTrue(body.path("emptyReason").isNull());
        assertEquals(PolicyProposalView.MAX_SUBJECTS, body.path("maxSubjects").asInt());
        assertEquals(1, body.path("candidatesTotal").asInt());
        assertEquals(1, body.path("tried").asInt());
        assertEquals(0, body.path("notTried").asInt());
        assertFalse(body.path("truncated").asBoolean());
        assertTrue(body.path("notTriedNote").isNull());

        // Every refusal word is present with 0.
        Set<String> refusalKeys = new LinkedHashSet<>();
        body.path("refused").fieldNames().forEachRemaining(refusalKeys::add);
        assertEquals(new LinkedHashSet<>(PolicyProposal.REFUSALS), refusalKeys);
        for (String word : PolicyProposal.REFUSALS) {
            assertEquals(0, body.path("refused").path(word).asInt(), word);
        }

        assertEquals(1, body.path("steps").size());
        var step = body.path("steps").get(0);
        assertEquals("r2", step.path("subject").path("ruleId").asText());
        assertEquals(20, step.path("subject").path("ruleNumber").asInt());
        assertEquals("r1", step.path("above").path("ruleId").asText());
        assertEquals(10, step.path("above").path("ruleNumber").asInt());
        assertEquals("SAFE", step.path("result").asText());
        assertTrue(step.path("reason").isNull());
        assertTrue(step.path("accepted").asBoolean());
        assertEquals("EQUIVALENT", step.path("equivalence").asText());
        assertEquals(0, step.path("crossedRules").asInt());
        assertFalse(step.path("inspectionChanges").asBoolean());

        // Only the moved section s1 appears in order; untouched s2 is omitted.
        assertEquals(1, body.path("order").size());
        var sectionOrder = body.path("order").get(0);
        assertEquals("s1", sectionOrder.path("sectionId").asText());
        assertEquals(0, sectionOrder.path("offset").asInt());
        assertEquals(2, sectionOrder.path("totalRules").asInt());
        assertEquals(0, sectionOrder.path("moreRules").asInt());
        assertTrue(sectionOrder.path("more").isNull());
        assertEquals(List.of("r1", "r2"), mapper.convertValue(sectionOrder.path("before"), List.class));
        assertEquals(List.of("r2", "r1"), mapper.convertValue(sectionOrder.path("after"), List.class));

        // Stacked moves keep stored rule numbers even when earlier moves reassigned positional slot numbers.
        var stacked = PolicyProposalTest.snapshot(cpSection("s1", null,
                cp("r1", 10, "Accept", "lo-a", "any"),
                cp("r2", 20, "Accept", "lo", "any"),
                cp("r3", 30, "Accept", "net", "any")));
        var stackedBody = json(view.response(stacked, 60).body());
        assertEquals(2, stackedBody.path("accepted").asInt());
        assertEquals(10, stackedBody.path("steps").get(0).path("above").path("ruleNumber").asInt());
        assertEquals(20, stackedBody.path("steps").get(1).path("above").path("ruleNumber").asInt(),
                "r2 moved into slot 10, but steps report its stored number 20");
        assertEquals(30, stackedBody.path("steps").get(1).path("subject").path("ruleNumber").asInt());
        assertEquals(List.of("r3", "r2", "r1"),
                mapper.convertValue(stackedBody.path("order").get(0).path("after"), List.class));
    }

    private static PolicySnapshot mergeOnlySnapshot() {
        return PolicyProposalTest.snapshot(cpSection("s1", null,
                cp("r1", 10, "Accept", "lo", "any"),
                cp("r2", 20, "Accept", "hi", "any")));
    }

    @Test
    void mergeOnlyPackageIsEquivalentWithNoBehaviourChangeLabelAndDropsTheMergedRuleFromOrder() {
        var stored = mergeOnlySnapshot();
        var body = json(new PolicyProposalView().response(stored, 90).body());

        assertEquals(PolicyReorder.EQUIVALENT, body.path("packageVerdict").asText());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, body.path("label").asText());
        assertFalse(body.path("inspectionChanges").asBoolean());
        assertEquals(0, body.path("accepted").asInt());
        assertEquals(PolicyProposal.NO_SUBJECT, body.path("emptyReason").asText());
        assertEquals(1, body.path("mergesAccepted").asInt());
        assertFalse(body.path("mergesBudgetStopped").asBoolean());
        assertTrue(body.path("mergesEmptyReason").isNull());
        assertTrue(body.path("mergesNote").isNull());
        assertEquals(1, body.path("merges").size());
        var merge = body.path("merges").get(0);
        assertEquals(json(Map.of("ruleId", "r1", "ruleName", stored.sections().getFirst().rules().getFirst().name(),
                "ruleNumber", 10)), merge.path("surviving"));
        assertEquals(json(Map.of("ruleId", "r2", "ruleName", stored.sections().getFirst().rules().getLast().name(),
                "ruleNumber", 20)), merge.path("merged"));
        assertEquals("source", merge.path("differingCell").asText());
        assertEquals(PolicyMerge.MERGE_CANDIDATE, merge.path("result").asText());
        assertTrue(merge.path("reason").isNull());
        assertTrue(merge.path("accepted").asBoolean());
        assertEquals(PolicyReorder.EQUIVALENT, merge.path("equivalence").asText());

        var order = body.path("order").get(0);
        assertEquals(2, order.path("totalRules").asInt());
        assertEquals(json(List.of("r1", "r2")), order.path("before"));
        assertEquals(json(List.of("r1")), order.path("after"));

        var capped = json(PolicyProposalView.compute(stored, PolicyProposalView.MAX_SUBJECTS, 1,
                PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK).fields()).path("order").get(0);
        assertEquals(1, capped.path("offset").asInt());
        assertEquals(json(List.of("r2")), capped.path("before"));
        assertEquals(json(List.of()), capped.path("after"));
        assertEquals("+1 more", capped.path("more").asText());
    }

    @Test
    void movesAndMergesCombineIntoOneVerdictAndTheMergeSeesTheMovedOrder() {
        // Promoting spec above broad makes m1 and m2 adjacent for the first time.
        var stored = PolicyProposalMergesTest.snapshot(cpSection("s1", null,
                PolicyMergeTest.ruleWithCells("broad", 1, "Accept", "lo", "lo", "svc-https", "any"),
                PolicyMergeTest.ruleWithCells("m1", 2, "Accept", "hi-a", "other", "svc-http", "any"),
                PolicyMergeTest.ruleWithCells("spec", 3, "Accept", "lo-a", "net", "svc-https", "any"),
                PolicyMergeTest.ruleWithCells("m2", 4, "Accept", "hi-b", "other", "svc-http", "any")));
        assertEquals(0, PolicyMerge.analyze(stored).candidates());

        var body = json(new PolicyProposalView().response(stored, 90).body());
        assertEquals(1, body.path("accepted").asInt());
        assertEquals("spec", body.path("steps").get(0).path("subject").path("ruleId").asText());
        assertEquals(PolicyReorder.SAFE, body.path("steps").get(0).path("result").asText());
        assertEquals(1, body.path("mergesAccepted").asInt());
        var merge = body.path("merges").get(0);
        assertEquals("m1", merge.path("surviving").path("ruleId").asText());
        assertEquals(2, merge.path("surviving").path("ruleNumber").asInt(), "stored number, not post-move position");
        assertEquals("m2", merge.path("merged").path("ruleId").asText());
        assertEquals(PolicyReorder.EQUIVALENT, body.path("packageVerdict").asText());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, body.path("label").asText());
        var order = body.path("order").get(0);
        assertEquals(json(List.of("broad", "m1", "spec", "m2")), order.path("before"));
        assertEquals(json(List.of("spec", "broad", "m1")), order.path("after"));
    }

    @Test
    void aMergeBudgetStopMakesThePackageNotProvenWithNoLabel() {
        var body = json(PolicyProposalView.compute(mergeOnlySnapshot(), PolicyProposalView.MAX_SUBJECTS,
                PolicyProposalView.MAX_ORDER_RULES, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK, 0, 0).fields());

        assertTrue(body.path("mergesBudgetStopped").asBoolean());
        assertTrue(body.path("budgetStopped").asBoolean());
        assertEquals(PolicyReorder.NOT_PROVEN, body.path("packageVerdict").asText());
        assertTrue(body.path("label").isNull());
        assertEquals(0, body.path("mergesAccepted").asInt());
        assertEquals(1, body.path("mergesRefused").path(PolicyMerge.NOT_PROVEN).asInt());
        var merge = body.path("merges").get(0);
        assertEquals(PolicyMerge.NOT_PROVEN, merge.path("result").asText());
        assertEquals(PolicyMerge.BUDGET, merge.path("reason").asText());
        assertEquals(PolicyMerge.NOT_PROVEN, merge.path("equivalence").asText());
        assertFalse(merge.path("accepted").asBoolean());
        assertTrue(body.path("order").isEmpty());
    }

    @Test
    void mergesRefusedAlwaysCarriesEveryRefusalKey() {
        var stored = PolicyProposalTest.snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")));
        var body = json(new PolicyProposalView().response(stored, 90).body());
        assertEquals(PolicyProposalMerges.NO_CANDIDATE, body.path("mergesEmptyReason").asText());
        var refused = body.path("mergesRefused");
        List<String> keys = new ArrayList<>();
        refused.fieldNames().forEachRemaining(keys::add);
        assertEquals(PolicyProposalMerges.REFUSALS, keys);
        for (String word : keys) assertEquals(0, refused.path(word).intValue(), word);
    }

    @Test
    void nonCheckPointStillAnswersSupportedFalseWithoutRunningMerges() {
        var view = new PolicyProposalView();
        view.setComputer(snapshot -> { throw new AssertionError("unsupported vendor started a proposal"); });
        var pan = PolicyHygieneTest.snapshot(
                List.of(PolicyHygieneTest.rule("r1", "any", "any", "allow", true)), PolicyHygieneTest.objects());
        var body = json(view.response(pan, 90).body());
        assertFalse(body.path("supported").asBoolean());
        assertFalse(body.has("merges"));
        assertFalse(body.has("mergesAccepted"));
        assertEquals(0, view.cacheSize());
    }

    @Test
    void mergeCapCountsAllAttemptsAndSignalsOmittedRows() {
        int total = PolicyProposalView.MAX_MERGE_STEPS + 2;
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            sections.add(cpSection("s-" + i, null,
                    cp("a-" + i, i * 2 + 1, "Accept", "lo", "any"),
                    cp("b-" + i, i * 2 + 2, "Accept", "hi", "any")));
        }
        var stored = PolicyProposalTest.snapshot(sections.toArray(Section[]::new));
        var body = json(new PolicyProposalView().response(stored, 90).body());
        assertEquals(total, body.path("mergesAccepted").asInt());
        assertEquals(PolicyProposalView.MAX_MERGE_STEPS, body.path("merges").size());
        assertEquals("+2 more", body.path("mergesNote").asText());
        assertEquals(total, body.path("order").size(), "display cap does not limit applied merges");
    }

    @Test
    void unsupportedVendorAnswersSupportedFalseLikeInsights() {
        var view = new PolicyProposalView();
        var pan = PolicyHygieneTest.snapshot(
                List.of(PolicyHygieneTest.rule("r1", "any", "any", "allow", true)),
                PolicyHygieneTest.objects());
        var body = json(view.response(pan, 90).body());
        assertEquals("PAN", body.path("vendor").asText());
        assertFalse(body.path("supported").asBoolean());
        assertFalse(body.has("steps"));
        assertFalse(body.has("packageVerdict"));
        assertFalse(body.has("order"));
        assertEquals(0, view.cacheSize(), "unsupported vendor does not populate the proposal cache");
    }

    @Test
    void incompleteSnapshotAndNoCandidatePackageReportEmptyReasons() {
        var view = new PolicyProposalView();
        var complete = PolicyProposalTest.stackedRelease();
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(),
                List.of(new CollectionFailure("layer-1", "COLLECTION_PENDING")));
        var body = json(view.response(incomplete, 90).body());
        assertTrue(body.path("supported").asBoolean());
        assertFalse(body.path("complete").asBoolean());
        assertEquals(PolicyProposal.NO_MOVES, body.path("packageVerdict").asText());
        assertTrue(body.path("label").isNull());
        assertEquals(PolicyProposal.INCOMPLETE, body.path("emptyReason").asText());
        assertEquals(PolicyProposalMerges.INCOMPLETE, body.path("mergesEmptyReason").asText());
        assertEquals(0, body.path("accepted").asInt());
        assertEquals(0, body.path("candidatesTotal").asInt());
        assertEquals(0, body.path("tried").asInt());
        assertEquals(0, body.path("notTried").asInt());
        assertFalse(body.path("truncated").asBoolean());
        assertEquals(0, body.path("steps").size());
        assertEquals(0, body.path("order").size());

        var noMoves = PolicyProposalTest.snapshot(cpSection("s1", null,
                cp("r1", 1, "Accept", "lo", "any"),
                cp("r2", 2, "Drop", "other", "any")));
        var emptyBody = json(new PolicyProposalView().response(noMoves, 90).body());
        assertEquals(PolicyProposal.NO_SUBJECT, emptyBody.path("emptyReason").asText());
        assertEquals(PolicyProposal.NO_MOVES, emptyBody.path("packageVerdict").asText());
        assertEquals(0, emptyBody.path("steps").size());
    }

    @Test
    void subjectCapLimitsTriedMovesReportsTruncationCountsAndCapsOrderList() {
        assertEquals(25, PolicyProposalView.MAX_SUBJECTS);
        int extra = 4;
        int totalCandidates = PolicyProposalView.MAX_SUBJECTS + extra;
        Map<String, PolicyObject> objects = new LinkedHashMap<>(PolicyReorderTest.snapshot().objects());
        objects.put("shared-host", object("shared-host", "address", "ip-netmask: 192.0.2.1/32"));
        List<Rule> rules = new ArrayList<>();
        rules.add(cp("b0", 1, "Accept", "shared-host", "any"));
        for (int i = 1; i <= totalCandidates; i++) {
            String unique = "u-" + i;
            objects.put(unique, object(unique, "address", "ip-netmask: 198.51.100." + i + "/32"));
            // All subjects overlap b0, but each has a different destination, so they cannot merge with each other.
            rules.add(PolicyProposalMergesTest.ruleWithCells("s" + i, i + 1, "Accept",
                    List.of("shared-host", unique), List.of(unique), List.of("any"), "any"));
        }
        rules.add(cp("merge-a", totalCandidates + 2, "Drop", "lo", "any"));
        rules.add(cp("merge-b", totalCandidates + 3, "Drop", "hi", "any"));
        var base = PolicyReorderTest.snapshot(cpSection("s1", null, rules.toArray(Rule[]::new)));
        var stored = new PolicySnapshot(base.metadata(), base.sections(), objects, List.of());

        var body = json(new PolicyProposalView().response(stored, 90).body());
        assertEquals(totalCandidates, body.path("candidatesTotal").asInt());
        assertEquals(PolicyProposalView.MAX_SUBJECTS, body.path("tried").asInt());
        assertEquals(extra, body.path("notTried").asInt());
        assertTrue(body.path("truncated").asBoolean());
        assertEquals("+" + extra + " more not tried", body.path("notTriedNote").asText());
        assertEquals(PolicyProposalView.MAX_SUBJECTS, body.path("steps").size());
        assertEquals(PolicyProposalView.MAX_SUBJECTS, body.path("accepted").asInt());
        // Subjects are tried in stored rule order: s1..s25, never s26..s29.
        assertEquals("s1", body.path("steps").get(0).path("subject").path("ruleId").asText());
        assertEquals("s25", body.path("steps").get(24).path("subject").path("ruleId").asText());
        assertEquals(1, body.path("mergesAccepted").asInt(), "truncating moves does not skip the merge pass");
        assertEquals("merge-b", body.path("merges").get(0).path("merged").path("ruleId").asText());

        // Capped order list reports "+N more" and ids only.
        var orderCapped = json(PolicyProposalView.compute(stored, 3, 5,
                PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK).fields());
        assertEquals(totalCandidates, orderCapped.path("candidatesTotal").asInt());
        assertEquals(3, orderCapped.path("tried").asInt());
        assertEquals(totalCandidates - 3, orderCapped.path("notTried").asInt());
        var secOrder = orderCapped.path("order").get(0);
        assertEquals(5, secOrder.path("before").size());
        assertEquals(5, secOrder.path("after").size());
        assertEquals(rules.size() - 5, secOrder.path("moreRules").asInt());
        assertEquals("+" + (rules.size() - 5) + " more", secOrder.path("more").asText());
    }

    @Test
    void budgetStopReportsNotProvenWithAnalysisBudgetReason() {
        var stored = PolicyProposalTest.stackedRelease();
        var stopped = json(PolicyProposalView.compute(stored, PolicyProposalView.MAX_SUBJECTS,
                PolicyProposalView.MAX_ORDER_RULES, 0, 0).fields());
        assertTrue(stopped.path("budgetStopped").asBoolean());
        assertEquals(PolicyReorder.NOT_PROVEN, stopped.path("packageVerdict").asText());
        assertTrue(stopped.path("label").isNull());
        assertEquals(2, stopped.path("candidatesTotal").asInt());
        assertEquals(2, stopped.path("tried").asInt());
        assertEquals(0, stopped.path("notTried").asInt());
        assertEquals(2, stopped.path("refused").path(PolicyReorder.NOT_PROVEN).asInt());
        for (JsonNode step : stopped.path("steps")) {
            assertFalse(step.path("accepted").asBoolean());
            assertEquals(PolicyReorder.NOT_PROVEN, step.path("result").asText());
            assertEquals(PolicyProposal.BUDGET, step.path("reason").asText());
            assertEquals(PolicyReorder.NOT_PROVEN, step.path("equivalence").asText());
        }
        assertEquals(0, stopped.path("order").size());
    }

    @Test
    void aiviewProjectionMasksRuleNamesAndEnforcesClosedVocabularies() {
        String plantedSubject = "allow-x9f4a2-cardholder";
        String plantedAbove = "broad-x9f4a2-branches";
        String plantedSection = "sec-x9f4a2-inbound";
        String plantedPolicy = "pkg-x9f4a2-prod";
        String plantedDomain = "dom-x9f4a2-core";
        String plantedObjectLo = "obj-x9f4a2-subnet";
        String plantedObjectNet = "obj-x9f4a2-supernet";
        String plantedComment = "approved by adm.ops on FW-TANGO-04 for 192.0.2.55";

        Map<String, PolicyObject> objects = Map.of(
                "any", new PolicyObject("any", "Any", "any", List.of(), List.of(), "RESOLVED"),
                "o-lo", new PolicyObject("o-lo", plantedObjectLo, "address", List.of(), List.of("ip-netmask: 192.0.2.0/25"), "RESOLVED"),
                "o-net", new PolicyObject("o-net", plantedObjectNet, "address", List.of(), List.of("ip-netmask: 192.0.2.0/24"), "RESOLVED"));
        Map<String, List<String>> extras = Map.of("time", List.of("any"), "install-on", List.of("any"));
        var r1 = new Rule("rule-1", "u-1", 1, plantedAbove, true, new Cell(List.of("o-lo"), false),
                new Cell(List.of("any"), false), new Cell(List.of("any"), false), new Cell(List.of("any"), false),
                "Accept", "None", plantedComment, extras);
        var r2 = new Rule("rule-2", "u-2", 2, plantedSubject, true, new Cell(List.of("o-net"), false),
                new Cell(List.of("any"), false), new Cell(List.of("any"), false), new Cell(List.of("any"), false),
                "Accept", "Log", plantedComment, extras);
        var meta = new Metadata("pol-1", "src-1", "core-smc-01", "CP", "dom-1", plantedDomain, plantedPolicy,
                "2026-11-26T09:00:00Z", null, List.of());
        var stored = new PolicySnapshot(meta, List.of(new Section("sec-1", plantedSection, "CP access layer", null, List.of(r1, r2))), objects);

        var rawBody = new PolicyProposalView().response(stored, 90).body();
        var projected = masked(rawBody);
        String text = projected.toString();

        for (String planted : List.of(plantedSubject, plantedAbove, plantedSection, plantedPolicy, plantedDomain,
                plantedObjectLo, plantedObjectNet, "adm.ops", "192.0.2.55", "192.0.2.0", "core-smc-01")) {
            assertFalse(text.contains(planted), "planted value leaked: " + planted);
        }

        // Rule names use the exact rule pseudonym namespace as /insights and /insight.
        var step = projected.path("steps").get(0);
        assertEquals(names.maskPolicyName("rule", plantedSubject), step.path("subject").path("ruleName").asText());
        assertEquals(names.maskPolicyName("rule", plantedAbove), step.path("above").path("ruleName").asText());
        assertEquals(2, step.path("subject").path("ruleNumber").asInt());
        assertEquals(1, step.path("above").path("ruleNumber").asInt());
        assertEquals("SAFE_INSPECTION_CHANGES", step.path("result").asText());
        assertEquals("CHANGES", step.path("equivalence").asText());
        assertEquals("CHANGES", projected.path("packageVerdict").asText());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, projected.path("label").asText());
        assertEquals(PolicyProposalView.CAVEAT, projected.path("caveat").asText());
        assertEquals("INSTALL_STATE_NOT_VERIFIED", projected.path("insightCaveats").get(0).asText());
        assertEquals(List.of("rule-2", "rule-1"), mapper.convertValue(projected.path("order").get(0).path("after"), List.class));

        // Budget reason survives closed vocabulary; rogue strings in every closed field are scrubbed.
        assertEquals(PolicyProposal.BUDGET, PolicyPrivacy.mask(PolicyProposal.BUDGET, "reason", names, ips));
        Map<String, Object> tampered = new LinkedHashMap<>();
        tampered.put("packageVerdict", plantedSubject);
        tampered.put("label", plantedSubject);
        tampered.put("emptyReason", plantedSubject);
        tampered.put("notTriedNote", plantedSubject);
        tampered.put("caveat", plantedComment);
        tampered.put("refused", Map.of(plantedSubject, 3, PolicyReorder.ANCHOR_BLOCKED, 2));
        tampered.put("steps", List.of(Map.of(
                "subject", Map.of("ruleId", "rule-2", "ruleName", plantedSubject, "ruleNumber", 2),
                "result", plantedSubject,
                "reason", plantedComment,
                "accepted", false,
                "equivalence", plantedSubject,
                "crossedRules", 0,
                "inspectionChanges", false)));
        tampered.put("order", List.of(Map.of("sectionId", "sec-1", "more", plantedSection,
                "before", List.of("rule-1"), "after", List.of("rule-1"))));
        String safeTampered = masked(tampered).toString();
        assertFalse(safeTampered.contains(plantedSubject));
        assertFalse(safeTampered.contains(plantedSection));
        assertFalse(safeTampered.contains("adm.ops"));
        assertEquals(PolicyReorder.NOT_PROVEN, masked(tampered).path("packageVerdict").asText());
        assertEquals("UNKNOWN", masked(tampered).path("label").asText());
        assertEquals("UNKNOWN", masked(tampered).path("emptyReason").asText());
        assertEquals("Withheld in AIView", masked(tampered).path("caveat").asText());
        assertFalse(masked(tampered).path("refused").has(plantedSubject));
        assertEquals(2, masked(tampered).path("refused").path(PolicyReorder.ANCHOR_BLOCKED).asInt());
        assertEquals("UNKNOWN", masked(tampered).path("steps").get(0).path("result").asText());
        assertEquals("UNKNOWN", masked(tampered).path("steps").get(0).path("reason").asText());
        assertEquals(PolicyReorder.NOT_PROVEN, masked(tampered).path("steps").get(0).path("equivalence").asText());
        assertTrue(masked(tampered).path("order").get(0).path("more").isNull());
    }

    @Test
    void fixedCaveatStatesStackedOrderNoBehaviourChangeConditionAndInstallStateNotVerified() {
        var stored = PolicyProposalTest.snapshot(cpSection("s1", null,
                cp("r1", 1, "Accept", "lo", "any"),
                cp("r2", 2, "Accept", "net", "any")));
        var body = json(new PolicyProposalView().response(stored, 90).body());
        String caveat = body.path("caveat").asText();
        assertEquals(PolicyProposalView.CAVEAT, caveat);
        assertTrue(caveat.contains("stacked in the order shown"));
        assertTrue(caveat.contains("\"no behaviour change\""));
        assertTrue(caveat.contains("EQUIVALENT"));
        assertTrue(caveat.contains("SAFE"));
        assertTrue(caveat.contains("INSTALL_STATE_NOT_VERIFIED"));
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED),
                mapper.convertValue(body.path("insightCaveats"), List.class));
    }

    @Test
    void singleFlightComputesOnceForConcurrentCallsAndBoundsCacheBySnapshotInstance() throws Exception {
        var view = new PolicyProposalView();
        var stored = PolicyProposalTest.snapshot(cpSection("s1", null,
                cp("r1", 1, "Accept", "lo", "any"),
                cp("r2", 2, "Accept", "net", "any")));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        view.setComputer(s -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return PolicyProposalView.compute(s);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PolicyResponse> first = pool.submit(() -> view.response(stored, 90));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<PolicyResponse> second = pool.submit(() -> view.response(stored, 30));
            Thread.sleep(100);
            release.countDown();
            var r1 = json(first.get(10, TimeUnit.SECONDS).body());
            var r2 = json(second.get(10, TimeUnit.SECONDS).body());
            assertEquals(1, calls.get(), "concurrent calls on one snapshot instance compute once");
            assertEquals(90, r1.path("days").asInt());
            assertEquals(30, r2.path("days").asInt());
            assertEquals(r1.path("steps"), r2.path("steps"));
            // Subsequent call on the same snapshot instance hits the cache.
            view.response(stored, 90);
            assertEquals(1, calls.get());
            // A new snapshot instance with the same policy id recomputes.
            var replaced = new PolicySnapshot(stored.metadata(), stored.sections(), stored.objects(), stored.failures());
            view.response(replaced, 90);
            assertEquals(2, calls.get());
            // Bounded cache keeps at most MAX_CACHED entries.
            for (int i = 0; i < PolicyProposalView.MAX_CACHED + 2; i++) {
                var m = stored.metadata();
                var distinct = new PolicySnapshot(new Metadata("p-" + i, m.sourceId(), m.sourceName(), m.vendor(),
                        m.containerId(), m.containerName(), m.name(), m.collectedAt(), m.artefactRef(), m.targets()),
                        stored.sections(), stored.objects(), stored.failures());
                view.response(distinct, 90);
            }
            assertEquals(PolicyProposalView.MAX_CACHED, view.cacheSize());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theMergePassSharesTheSingleFlightCache() throws Exception {
        var view = new PolicyProposalView();
        var stored = mergeOnlySnapshot();
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        view.setComputer(snapshot -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return PolicyProposalView.compute(snapshot);
        });

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> view.response(stored, 90));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return view.response(stored, 30);
            });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            release.countDown();
            var one = first.get(10, TimeUnit.SECONDS).body();
            var two = second.get(10, TimeUnit.SECONDS).body();
            assertEquals(1, one.get("mergesAccepted"));
            assertSame(one.get("merges"), two.get("merges"), "both responses use the same computed merge rows");
            assertEquals(1, calls.get());
            assertEquals(1, view.cacheSize());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedComputationEvictsCacheEntryAndNextRequestRecomputes() {
        var view = new PolicyProposalView();
        var stored = PolicyProposalTest.snapshot(cpSection("s1", null,
                cp("r1", 1, "Accept", "lo", "any"),
                cp("r2", 2, "Accept", "net", "any")));
        AtomicInteger attempts = new AtomicInteger();
        view.setComputer(s -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("synthetic proposal failure");
            }
            return PolicyProposalView.compute(s);
        });

        var thrown = assertThrows(IllegalStateException.class, () -> view.response(stored, 90));
        assertEquals("synthetic proposal failure", thrown.getMessage());
        assertEquals(0, view.cacheSize(), "failed computation evicts its entry");

        var recovered = json(view.response(stored, 90).body());
        assertEquals(2, attempts.get());
        assertEquals(1, recovered.path("accepted").asInt());
        assertEquals(1, view.cacheSize());
    }
}
