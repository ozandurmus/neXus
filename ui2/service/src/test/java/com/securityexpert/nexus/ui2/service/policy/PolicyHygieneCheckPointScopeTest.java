package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Check Point shadowing scope: install-on as an opaque placement token, and access rules scoped by layer identity
 * (inline layers as independent sub-policies, "Apply Layer" rules terminal in their parent layer). Synthetic fixtures:
 * documentation addresses (192.0.2.0/24) and masked names only.
 */
class PolicyHygieneCheckPointScopeTest {
    /** The default install-on value as CheckPointPolicyMapper stores it: present in the dictionary, native type Global. */
    static PolicyObject policyTargets(String id) {
        return new PolicyObject(id, "Policy Targets", "unresolved", List.of(), List.of("native-type: Global"), "UNSUPPORTED");
    }
    static Map<String, PolicyObject> cpObjects() {
        var objects = objects();
        objects.put("targets", policyTargets("targets"));
        objects.put("targets-2", policyTargets("targets-2"));
        objects.put("absent-from-dictionary", new PolicyObject("absent-from-dictionary", "uid-not-in-dictionary", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        objects.put("fw-tango-04", object("fw-tango-04", "gateway"));
        return objects;
    }
    static Rule cp(String id, int number, String action, String source, String... installOn) {
        return edit(cpLayerRule(id, number, "LAYER-ALPHA", action), cell(source), Map.of("install-on", List.of(installOn)));
    }
    static Rule inLayer(Rule rule, String layer) { return edit(rule, rule.source(), Map.of("layer-name", List.of(layer))); }
    static Rule applyLayer(String id, int number, String source, String... inlineLayers) {
        return edit(cp(id, number, "Apply Layer", source, "any"), cell(source), Map.of("inline-layer", List.of(inlineLayers)));
    }
    static PolicySnapshot cpSnapshot(Section... sections) {
        var base = PolicyHygieneTest.sections("CP", sections);
        return new PolicySnapshot(base.metadata(), base.sections(), cpObjects());
    }
    static PolicyHygiene.Assessment at(PolicySnapshot snapshot, String id) { return assessment(snapshot, id); }
    static void assertCovered(PolicySnapshot snapshot, String id, String status, String covering) {
        var result = at(snapshot, id);
        assertEquals(status, result.shadowStatus(), id + " " + result);
        assertEquals(covering, result.coveringRuleId(), id);
        assertNull(result.shadowReason(), id);
    }
    static void assertUnknown(PolicySnapshot snapshot, String id, String reason) {
        var result = at(snapshot, id);
        assertEquals("UNKNOWN", result.shadowStatus(), id + " " + result);
        assertEquals(reason, result.shadowReason(), id);
        assertNull(result.coveringRuleId(), id);
    }

    // ---- install-on as an opaque placement token ----

    @Test void rulesInstalledOnTheSameGlobalTargetObjectCanCoverEachOther() {
        for (String target : List.of("targets", "absent-from-dictionary")) {
            var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "net", target), cp("r2", 2, "Accept", "host", target),
                cp("r3", 3, "Drop", "host", target), cp("r4", 4, "Drop", "any", target)));
            assertEquals("NOT_SHADOWED", at(snapshot, "r1").shadowStatus(), target);
            assertNull(at(snapshot, "r1").shadowReason());
            assertCovered(snapshot, "r2", "REDUNDANT", "r1");
            assertCovered(snapshot, "r3", "CONFLICT", "r1");
            assertEquals("NOT_SHADOWED", at(snapshot, "r4").shadowStatus());
            // The analysed rule has no other uncertainty, so nothing was added for the opaque target.
            assertTrue(at(snapshot, "r2").findings().stream().noneMatch(f -> f.evidence().startsWith("Shadowing:")));
        }
    }
    @Test void differentInstallOnSetsStayUnprovenAndAreNeverDisjoint() {
        for (var targets : List.of(List.of("fw-tango-04"), List.of("targets-2"), List.of("targets", "fw-tango-04"), List.of("any"))) {
            var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "targets"),
                cp("r2", 2, "Accept", "host", targets.toArray(String[]::new))));
            assertUnknown(snapshot, "r2", "zone or install-on coverage is unproven");
        }
        // Two resolved, distinct gateways stay disjoint as before.
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "fw-tango-04"), cp("r2", 2, "Accept", "host", "gw-other")));
        snapshot = new PolicySnapshot(snapshot.metadata(), snapshot.sections(), with(snapshot.objects(), object("gw-other", "gateway")));
        assertEquals("NOT_SHADOWED", at(snapshot, "r2").shadowStatus());
        assertNull(at(snapshot, "r2").shadowReason());
    }
    @Test void anInstallOnReferenceMissingFromTheSnapshotAndUnresolvedSelectorsStillStopTheRule() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "targets"), cp("r2", 2, "Accept", "host", "no-such-object"),
            cp("r3", 3, "Accept", "missing", "targets"), cp("r4", 4, "Accept", "host", "targets")));
        assertUnknown(snapshot, "r2", "unresolved install-on selector");
        assertUnknown(snapshot, "r3", "unresolved object");
        // r4 is still covered by r1: an earlier unknown never hides a proven cover.
        assertCovered(snapshot, "r4", "REDUNDANT", "r1");
        var after = cpSnapshot(cpSection("s1", null, cp("r3", 1, "Accept", "missing", "targets"), cp("r5", 2, "Accept", "host", "targets")));
        assertUnknown(after, "r5", "earlier rule has unsupported constraints");
    }
    @Test void theLookupStillRequiresAResolvedInstallOnObject() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "targets")));
        var section = snapshot.sections().get(0); var rule = section.rules().get(0);
        var resolver = new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(10_000));
        assertNull(PolicyNormalization.normalize(snapshot, section, rule, resolver).problem());
        assertEquals("unresolved install-on selector", PolicyNormalization.normalize(snapshot, section, rule, resolver, true).problem());
    }
    @Test void thePlacementIndexUsesTheSameOpaqueKey() {
        // Many rules on one opaque key and a few on another: covers are found only within the same key, at any depth.
        List<Rule> rules = new ArrayList<>();
        rules.add(cp("r0", 1, "Drop", "net", "targets-2"));
        for (int i = 1; i <= 200; i++) rules.add(cp("r" + i, i + 1, "Accept", i % 2 == 0 ? "host" : "net", "targets"));
        rules.add(cp("r201", 202, "Accept", "host", "targets-2"));
        var snapshot = cpSnapshot(new Section("s1", "OBJ-SECTION-01", "CP access layer", null, rules));
        assertCovered(snapshot, "r2", "REDUNDANT", "r1");
        assertCovered(snapshot, "r200", "REDUNDANT", "r1");
        assertCovered(snapshot, "r201", "CONFLICT", "r0");
        // r1 overlaps r0 on an unequal opaque key: never disjoint, so unproven rather than clean.
        assertUnknown(snapshot, "r1", "zone or install-on coverage is unproven");
    }

    // ---- inline layers scoped by layer identity ----

    @Test void anInlineLayerIsAnalysedIndependentlyAndTheParentResumesWithTheApplyLayerRuleAsCover() {
        var snapshot = cpSnapshot(
            cpSection("s1", null, cp("r1", 1, "Accept", "host", "any"), applyLayer("r2", 2, "net", "il-1")),
            cpSection("inline", "r2", inLayer(cp("c1", 1, "Accept", "any", "any"), "LAYER-INLINE"), inLayer(cp("c2", 2, "Drop", "host", "any"), "LAYER-INLINE")),
            cpSection("s3", null, cp("r3", 3, "Accept", "host", "any"), cp("r4", 4, "Drop", "net", "any"), cp("r5", 5, "Drop", "any", "any")));
        // The Apply Layer rule itself is analysed in its layer; r1 does not cover it.
        assertEquals("NOT_SHADOWED", at(snapshot, "r2").shadowStatus());
        // Child rules meet neither r1 nor r2, only each other.
        assertEquals("NOT_SHADOWED", at(snapshot, "c1").shadowStatus());
        assertNull(at(snapshot, "c1").shadowReason());
        assertCovered(snapshot, "c2", "CONFLICT", "c1");
        // The parent resumes with its own earlier rules: r1 still covers r3; r2 covers r4; the child's "any" never leaks.
        assertCovered(snapshot, "r3", "REDUNDANT", "r1");
        assertCovered(snapshot, "r4", "CONFLICT", "r2");
        assertEquals("NOT_SHADOWED", at(snapshot, "r5").shadowStatus());
        assertNull(at(snapshot, "r5").shadowReason());
    }
    @Test void earlierParentRulesAreNotComparedWithChildRulesButDoCoverTheApplyLayerRule() {
        var snapshot = cpSnapshot(
            cpSection("s1", null, cp("r1", 1, "Accept", "any", "any"), applyLayer("r2", 2, "net", "il-1")),
            cpSection("inline", "r2", inLayer(cp("c1", 1, "Drop", "host", "any"), "LAYER-INLINE")));
        assertCovered(snapshot, "r2", "CONFLICT", "r1");
        assertEquals("NOT_SHADOWED", at(snapshot, "c1").shadowStatus());
        assertNull(at(snapshot, "c1").coveringRuleId());
    }
    @Test void nestedInlineLayersResumeTheRightLayer() {
        var snapshot = cpSnapshot(
            cpSection("s1", null, applyLayer("r1", 1, "net", "il-1")),
            cpSection("child-a", "r1", inLayer(cp("c1", 1, "Accept", "host", "any"), "LAYER-INLINE"), inLayer(applyLayer("c2", 2, "any", "il-2"), "LAYER-INLINE")),
            cpSection("grandchild", "c2", inLayer(cp("g1", 1, "Accept", "net", "any"), "LAYER-NESTED"), inLayer(cp("g2", 2, "Accept", "host", "any"), "LAYER-NESTED")),
            cpSection("child-b", "r1", inLayer(cp("c3", 3, "Accept", "host", "any"), "LAYER-INLINE")),
            cpSection("s2", null, cp("r2", 2, "Drop", "host", "any")));
        assertCovered(snapshot, "g2", "REDUNDANT", "g1");
        // c3 continues the child layer after the grandchild ended: c1 covers it (the grandchild's g1 never leaks).
        assertCovered(snapshot, "c3", "REDUNDANT", "c1");
        assertCovered(snapshot, "r2", "CONFLICT", "r1");
        // Child layer ending right after a grandchild: the parent resumes past both.
        var direct = cpSnapshot(
            cpSection("s1", null, applyLayer("r1", 1, "net", "il-1")),
            cpSection("child", "r1", inLayer(applyLayer("c1", 1, "any", "il-2"), "LAYER-INLINE")),
            cpSection("grandchild", "c1", inLayer(cp("g1", 1, "Accept", "any", "any"), "LAYER-NESTED")),
            cpSection("s2", null, cp("r2", 2, "Drop", "host", "any"), cp("r3", 3, "Drop", "any", "any")));
        assertCovered(direct, "r2", "CONFLICT", "r1");
        assertEquals("NOT_SHADOWED", at(direct, "r3").shadowStatus());
    }
    @Test void applyLayerRulesAreRedundantOnlyWhenTheyApplyTheSameInlineLayer() {
        for (String second : List.of("il-1", "il-2")) {
            var snapshot = cpSnapshot(
                cpSection("s1", null, applyLayer("r1", 1, "net", "il-1")),
                cpSection("inline-1", "r1", inLayer(cp("c1", 1, "Accept", "any", "any"), "LAYER-INLINE")),
                cpSection("s2", null, applyLayer("r2", 2, "host", second)),
                cpSection("inline-2", "r2", inLayer(cp("c2", 1, "Accept", "any", "any"), "LAYER-INLINE-2")));
            assertCovered(snapshot, "r2", second.equals("il-1") ? "REDUNDANT" : "CONFLICT", "r1");
        }
    }
    @Test void aDisabledOrUnresolvedApplyLayerRuleDoesNotCoverLaterParentRules() {
        var disabled = applyLayer("r1", 1, "any", "il-1");
        disabled = new Rule(disabled.id(), disabled.uuid(), disabled.number(), disabled.name(), false, disabled.source(), disabled.destination(),
            disabled.service(), disabled.application(), disabled.action(), disabled.log(), disabled.comment(), disabled.extras());
        var snapshot = cpSnapshot(cpSection("s1", null, disabled), cpSection("inline", "r1", inLayer(cp("c1", 1, "Accept", "any", "any"), "LAYER-INLINE")),
            cpSection("s2", null, cp("r2", 2, "Accept", "host", "any")));
        assertEquals("NOT_SHADOWED", at(snapshot, "r2").shadowStatus());
        assertNull(at(snapshot, "r2").coveringRuleId());
        var unresolved = cpSnapshot(cpSection("s1", null, applyLayer("r1", 1, "missing", "il-1")),
            cpSection("inline", "r1", inLayer(cp("c1", 1, "Accept", "any", "any"), "LAYER-INLINE")),
            cpSection("s2", null, cp("r2", 2, "Accept", "host", "any")));
        assertUnknown(unresolved, "r1", "unresolved object");
        assertEquals("NOT_SHADOWED", at(unresolved, "c1").shadowStatus());
        assertUnknown(unresolved, "r2", "earlier rule has unsupported constraints");
        // An Apply Layer rule naming no single inline layer is not a terminal rule.
        var ambiguous = cpSnapshot(cpSection("s1", null, applyLayer("r1", 1, "any", "il-1", "il-2"), cp("r2", 2, "Accept", "host", "any")));
        assertUnknown(ambiguous, "r1", "non-terminal or unknown action");
        assertUnknown(ambiguous, "r2", "earlier rule has unsupported constraints");
    }
    @Test void unprovenLayerIdentityStaysUnknown() {
        var child = inLayer(cp("c1", 1, "Accept", "any", "any"), "LAYER-INLINE");
        // The child names a parent that is not the rule just before it.
        var notAdjacent = cpSnapshot(cpSection("s1", null, applyLayer("r1", 1, "net", "il-1"), cp("r2", 2, "Accept", "host", "any")),
            cpSection("inline", "r1", child), cpSection("s3", null, cp("r3", 3, "Accept", "host", "any")));
        assertUnknown(notAdjacent, "c1", "earlier sections not analysed");
        assertUnknown(notAdjacent, "r3", "earlier sections not analysed");
        // The child layer comes back after the parent resumed.
        var reentered = cpSnapshot(cpSection("s1", null, applyLayer("r1", 1, "net", "il-1")), cpSection("inline", "r1", child),
            cpSection("s2", null, cp("r2", 2, "Accept", "host", "any")),
            cpSection("inline-again", "r1", inLayer(cp("c2", 2, "Accept", "host", "any"), "LAYER-INLINE")));
        assertCovered(reentered, "r2", "CONFLICT", "r1");
        assertUnknown(reentered, "c2", "earlier sections not analysed");
        // Rule numbers of the resumed parent do not increase: stored order is not proven enforcement order.
        var renumbered = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "host", "any"), applyLayer("r2", 2, "net", "il-1")),
            cpSection("inline", "r2", child), cpSection("s3", null, cp("r3", 2, "Accept", "host", "any")));
        assertEquals("NOT_SHADOWED", at(renumbered, "c1").shadowStatus());
        assertUnknown(renumbered, "r3", "earlier sections not analysed");
        // A label anomaly inside the child layer.
        var labels = cpSnapshot(cpSection("s1", null, applyLayer("r1", 1, "net", "il-1")),
            cpSection("inline", "r1", child, inLayer(cp("c2", 2, "Accept", "host", "any"), "LAYER-OTHER")),
            cpSection("s3", null, cp("r3", 3, "Accept", "host", "any")));
        assertUnknown(labels, "c2", "earlier sections not analysed");
        assertUnknown(labels, "r3", "earlier sections not analysed");
        // A blank parent link.
        var blank = cpSnapshot(cpSection("s1", null, applyLayer("r1", 1, "net", "il-1")), cpSection("inline", " ", child));
        assertUnknown(blank, "c1", "earlier sections not analysed");
    }
    @Test void everyReasonEmittedHereIsAKnownReason() {
        var snapshot = cpSnapshot(cpSection("s1", null, applyLayer("r1", 1, "net", "il-1")),
            cpSection("inline", "r1", inLayer(cp("c1", 1, "Accept", "any", "targets"), "LAYER-INLINE")),
            cpSection("s2", null, cp("r2", 2, "Accept", "host", "targets-2"), cp("r3", 3, "Accept", "host", "no-such-object")));
        for (var result : PolicyHygiene.analyze(snapshot, 90, NOW, 250000).rules().values())
            if (result.shadowReason() != null) assertTrue(PolicyHygiene.REASONS.contains(result.shadowReason()), result.shadowReason());
    }
    private static Map<String, PolicyObject> with(Map<String, PolicyObject> objects, PolicyObject extra) {
        var out = new HashMap<>(objects); out.put(extra.id(), extra); return out;
    }
}
