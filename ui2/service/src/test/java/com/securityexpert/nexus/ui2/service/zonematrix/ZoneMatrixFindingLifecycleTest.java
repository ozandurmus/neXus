package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.CoverageItem;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.EvaluationResult;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.FindingIdentity;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.NotApplicableSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.RuleEvaluation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.VersionCitation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Finding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.KnownFinding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.ResolutionReason;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Result;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Status;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Transition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Finding lifecycle unit tests (docs/design/ZONE_MATRIX_CONTRACT.md section 5.9; ZM-25, ZM-34, ZM-36 second half,
 * ZM-42). Synthetic ids only.
 */
class ZoneMatrixFindingLifecycleTest {
    private static final String POLICY = "POL-ALPHA";
    private static final String UID_A = "00000000-0000-4000-8000-00000000000a";
    private static final String UID_B = "00000000-0000-4000-8000-00000000000b";
    private static final String UID_C = "00000000-0000-4000-8000-00000000000c";
    private static final String UID_D = "00000000-0000-4000-8000-00000000000d";
    private static final String PAIR_1 = "ZONE-1->ZONE-2";
    private static final String PAIR_2 = "ZONE-3->ZONE-4";
    private static final String V1 = "ver-1";
    private static final String V2 = "ver-2";
    private static final String V3 = "ver-3";
    private static final Instant T0 = Instant.parse("2026-11-01T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-10T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-20T08:00:00Z");

    // ---- builders -------------------------------------------------------------------------------------------------

    private static FindingIdentity id(String uid, String... pairs) {
        return new FindingIdentity(POLICY, uid, List.of(), List.of(pairs));
    }

    private static KnownFinding open(FindingIdentity id, Instant first, Instant anyVersion, Instant last, String version) {
        return new KnownFinding(id, Status.OPEN, first, anyVersion, last, "VIOLATION", Set.of(), version);
    }

    private static KnownFinding resolvedEarlier(FindingIdentity id, Instant first, String version) {
        return new KnownFinding(id, Status.RESOLVED, first, first, first, "VIOLATION", Set.of(), version);
    }

    private static RuleEvaluation eval(FindingIdentity id, String outcome, Set<String> flags) {
        return new RuleEvaluation(id, "rule-" + id.ruleUid(), id.ruleUid(), 1, "RULE-ONE", "sec-1", "SECTION-1",
                id.inlineParentPath(), List.of("FW-TANGO-04"), List.of(), List.of(), outcome, "reason-" + outcome,
                "HIGH", flags, null, List.of(), List.of(), List.of(), "ORIGINAL");
    }

    private static RuleEvaluation violation(String uid, String... pairs) {
        return eval(id(uid, pairs), "VIOLATION", Set.of());
    }

    private static RuleEvaluation nonViolating(String uid, String outcome) {
        return eval(id(uid), outcome, Set.of());
    }

    private static EvaluationResult run(String version, String coverage, RuleEvaluation... rules) {
        return run(version, coverage, true, 0, rules);
    }

    private static EvaluationResult run(
            String version, String coverage, boolean evaluated, int failures, RuleEvaluation... rules) {
        List<CoverageItem> items = "COMPLETE".equals(coverage) ? List.of() : List.of(new CoverageItem("ITEM", "item"));
        return new EvaluationResult(
                ZoneMatrixEvaluator.ENGINE_VERSION, new VersionCitation(version, 1, "sha-" + version), POLICY,
                "POLICY-NAME", "src-1", "SOURCE-1", "cont-1", "CONTAINER-1", "CP", T2.toString(), List.of(), evaluated,
                null, coverage, items, failures, Map.of(), new NotApplicableSummary(0, 0, 0, 0, 0, List.of()), null,
                List.of(rules), List.of(), false, 0L, 0L);
    }

    private static Finding only(Result r, FindingIdentity id) {
        List<Finding> hits = r.findings().stream().filter(f -> f.identity().equals(id)).toList();
        assertEquals(1, hits.size(), "expected exactly one finding for the identity");
        return hits.get(0);
    }

    // ---- NEW / REPRODUCED -----------------------------------------------------------------------------------------

    @Test
    void newViolationOpensNewFindingDatedNow() {
        Result r = ZoneMatrixFindingLifecycle.next(List.of(), run(V1, "COMPLETE", violation(UID_A, PAIR_1)), T1);
        Finding f = only(r, id(UID_A, PAIR_1));
        assertEquals(Transition.NEW, f.transition());
        assertEquals(T1, f.firstObservedAt());
        assertEquals(T1, f.firstObservedAnyVersion());
        assertEquals(T1, f.lastObservedAt());
        assertEquals(V1, f.versionId());
        assertEquals(Status.OPEN, f.status());
    }

    @Test
    void reproducedKeepsFirstObservedAndAdvancesLastObserved() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", violation(UID_A, PAIR_1)), T1);
        Finding f = only(r, id(UID_A, PAIR_1));
        assertEquals(Transition.REPRODUCED, f.transition());
        assertEquals(T0, f.firstObservedAt());
        assertEquals(T1, f.lastObservedAt());
        assertEquals(1, r.findings().size());
    }

    @Test
    void zm25_flagToggleKeepsFirstObserved() {
        FindingIdentity i = id(UID_A, PAIR_1);
        KnownFinding k = open(i, T0, T0, T0, V1);
        RuleEvaluation withFlag = eval(i, "VIOLATION", Set.of("COVERED_BY_EARLIER_DENY"));
        Finding on = only(ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", withFlag), T1), i);
        assertEquals(Transition.REPRODUCED, on.transition());
        assertTrue(on.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertEquals(T0, on.firstObservedAt());

        Finding off = only(ZoneMatrixFindingLifecycle.next(List.of(on.toKnown()),
                run(V1, "COMPLETE", violation(UID_A, PAIR_1)), T2), i);
        assertEquals(Transition.REPRODUCED, off.transition());
        assertFalse(off.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertEquals(T0, off.firstObservedAt());
        assertEquals(T0, off.firstObservedAnyVersion());
        assertEquals(T2, off.lastObservedAt());
    }

    @Test
    void reproducedAcrossVersionKeepsFirstObservedAndUpdatesVersion() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Finding f = only(ZoneMatrixFindingLifecycle.next(List.of(k), run(V2, "COMPLETE", violation(UID_A, PAIR_1)), T1),
                id(UID_A, PAIR_1));
        assertEquals(Transition.REPRODUCED, f.transition());
        assertEquals(V2, f.versionId());
        assertEquals(T0, f.firstObservedAt());
    }

    // ---- ZM-34 ----------------------------------------------------------------------------------------------------

    @Test
    void zm34_violationTurningUnknownStaysOpenBecameUndecided() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", nonViolating(UID_A, "UNKNOWN")), T1);
        Finding f = only(r, id(UID_A, PAIR_1));
        assertEquals(Transition.BECAME_UNDECIDED, f.transition());
        assertEquals(Status.OPEN, f.status());
        assertTrue(f.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED));
        assertEquals("UNKNOWN", f.outcome());
        assertEquals("reason-UNKNOWN", f.reason());
        assertEquals(T0, f.firstObservedAt());
        assertEquals(T0, f.lastObservedAt(), "undecided is not a reproduction");
        assertNull(f.resolutionReason());
        assertEquals(1, r.open().size());
        assertTrue(r.resolved().isEmpty());
    }

    @Test
    void zm34_violationTurningNotEvaluableStaysOpenBecameUndecided() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Finding f = only(ZoneMatrixFindingLifecycle.next(List.of(k),
                run(V1, "INCOMPLETE", nonViolating(UID_A, "NOT_EVALUABLE")), T1), id(UID_A, PAIR_1));
        assertEquals(Transition.BECAME_UNDECIDED, f.transition());
        assertEquals("NOT_EVALUABLE", f.outcome());
    }

    @Test
    void zm34_undecidedFindingReturningToViolationIsReproducedAndFlagCleared() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Finding undecided = only(ZoneMatrixFindingLifecycle.next(List.of(k),
                run(V1, "COMPLETE", nonViolating(UID_A, "UNKNOWN")), T1), id(UID_A, PAIR_1));
        Finding back = only(ZoneMatrixFindingLifecycle.next(List.of(undecided.toKnown()),
                run(V1, "COMPLETE", violation(UID_A, PAIR_1)), T2), id(UID_A, PAIR_1));
        assertEquals(Transition.REPRODUCED, back.transition());
        assertEquals("VIOLATION", back.outcome());
        assertFalse(back.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED));
        assertEquals(T0, back.firstObservedAt());
        assertEquals(T2, back.lastObservedAt());
    }

    @Test
    void zm34_ruleMissingFromIncompleteRunIsNotResolved() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "INCOMPLETE"), T1);
        Finding f = only(r, id(UID_A, PAIR_1));
        assertEquals(Transition.STILL_OPEN_NOT_COLLECTED, f.transition());
        assertEquals(Status.OPEN, f.status());
        assertEquals(T0, f.lastObservedAt(), "lastObservedAt must not advance");
        assertEquals(V1, f.versionId());
        assertNull(f.resolutionReason());
        assertFalse(r.resolutionAllowed());
    }

    @Test
    void zm34_compliantRuleInIncompleteRunIsNotResolved() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Finding f = only(ZoneMatrixFindingLifecycle.next(List.of(k),
                run(V1, "INCOMPLETE", nonViolating(UID_A, "COMPLIANT")), T1), id(UID_A, PAIR_1));
        assertEquals(Transition.STILL_OPEN_NOT_COLLECTED, f.transition());
    }

    @Test
    void zm34_runWithCollectionFailuresOrNotEvaluatedOrUnknownCoverageNeverResolves() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        for (EvaluationResult r : List.of(
                run(V1, "COMPLETE", true, 1),
                run(V1, "COMPLETE", false, 0),
                run(V1, "SOMETHING_NEW"))) {
            Finding f = only(ZoneMatrixFindingLifecycle.next(List.of(k), r, T1), id(UID_A, PAIR_1));
            assertEquals(Transition.STILL_OPEN_NOT_COLLECTED, f.transition());
        }
    }

    // ---- resolution -----------------------------------------------------------------------------------------------

    @Test
    void ruleChangedWhenCollectedAndDecidedNotViolatingInSameVersion() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", nonViolating(UID_A, "COMPLIANT")), T1);
        Finding f = only(r, id(UID_A, PAIR_1));
        assertEquals(Transition.RESOLVED, f.transition());
        assertEquals(ResolutionReason.RULE_CHANGED, f.resolutionReason());
        assertEquals(T1, f.resolvedAt());
        assertEquals(Status.RESOLVED, f.status());
        assertEquals(T0, f.lastObservedAt());
        assertTrue(r.resolutionAllowed());
    }

    @Test
    void ruleRemovedOrDisabledOnlyOnCompleteRun() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Finding complete = only(ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE"), T1), id(UID_A, PAIR_1));
        assertEquals(Transition.RESOLVED, complete.transition());
        assertEquals(ResolutionReason.RULE_REMOVED_OR_DISABLED, complete.resolutionReason());

        Finding incomplete = only(ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "INCOMPLETE"), T1), id(UID_A, PAIR_1));
        assertNotEquals(Transition.RESOLVED, incomplete.transition());
        assertNull(incomplete.resolutionReason());
    }

    @Test
    void matrixVersionChangedListedPerVersion() {
        KnownFinding a = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        KnownFinding b = open(id(UID_B, PAIR_2), T0, T0, T0, V1);
        Result r2 = ZoneMatrixFindingLifecycle.next(List.of(a, b),
                run(V2, "COMPLETE", nonViolating(UID_A, "COMPLIANT"), nonViolating(UID_B, "COMPLIANT")), T1);
        Finding fa = only(r2, id(UID_A, PAIR_1));
        assertEquals(ResolutionReason.MATRIX_VERSION_CHANGED, fa.resolutionReason());
        assertEquals(V1, fa.resolvedFromVersionId());
        assertEquals(V2, fa.resolvedInVersionId());
        assertEquals(List.of(V2), List.copyOf(r2.resolvedByVersionChange().keySet()));
        assertEquals(2, r2.resolvedByVersionChange().get(V2).size());

        // c was last proven against V2 and resolved by V3 (version change); d was proven against V3 (rule change).
        KnownFinding c = open(id(UID_C, PAIR_1), T0, T0, T0, V2);
        KnownFinding d = open(id(UID_D, PAIR_1), T0, T0, T0, V3);
        Result r3 = ZoneMatrixFindingLifecycle.next(List.of(c, d),
                run(V3, "COMPLETE", nonViolating(UID_C, "COMPLIANT"), nonViolating(UID_D, "COMPLIANT")), T2);
        assertEquals(ResolutionReason.RULE_CHANGED, only(r3, id(UID_D, PAIR_1)).resolutionReason());

        List<Finding> all = new ArrayList<>(r2.findings());
        all.addAll(r3.findings());
        Map<String, List<Finding>> byVersion = ZoneMatrixFindingLifecycle.resolvedByVersionChange(all);
        assertEquals(List.of(V2, V3), List.copyOf(byVersion.keySet()));
        assertEquals(2, byVersion.get(V2).size());
        assertEquals(1, byVersion.get(V3).size());
        assertEquals(id(UID_C, PAIR_1), byVersion.get(V3).get(0).identity());
    }

    @Test
    void removedRuleIsNotListedAsVersionChange() {
        KnownFinding a = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(a), run(V2, "COMPLETE"), T1);
        assertEquals(ResolutionReason.RULE_REMOVED_OR_DISABLED, only(r, id(UID_A, PAIR_1)).resolutionReason());
        assertTrue(r.resolvedByVersionChange().isEmpty());
    }

    @Test
    void resolvedHistoryIsNotEmittedAgain() {
        Result r = ZoneMatrixFindingLifecycle.next(List.of(resolvedEarlier(id(UID_A, PAIR_1), T0, V1)),
                run(V1, "COMPLETE"), T2);
        assertTrue(r.findings().isEmpty());
    }

    // ---- exact ids ------------------------------------------------------------------------------------------------

    @Test
    void uidsDifferingOnlyByCaseAreTwoFindings() {
        String lower = "0000000a-0000-4000-8000-0000000000ab";
        String upper = lower.toUpperCase();
        KnownFinding k = open(id(lower, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", violation(upper, PAIR_1)), T1);
        assertEquals(2, r.findings().size());
        assertEquals(Transition.RESOLVED, only(r, id(lower, PAIR_1)).transition());
        assertEquals(ResolutionReason.RULE_REMOVED_OR_DISABLED, only(r, id(lower, PAIR_1)).resolutionReason());
        assertEquals(Transition.NEW, only(r, id(upper, PAIR_1)).transition());
        assertEquals(T1, only(r, id(upper, PAIR_1)).firstObservedAnyVersion(), "no date borrowed from the other uid");
    }

    @Test
    void uidsDifferingOnlyByLeadingZeroAreTwoFindings() {
        KnownFinding k = open(id("007", PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", violation("07", PAIR_1)), T1);
        assertEquals(2, r.findings().size());
        assertEquals(Transition.RESOLVED, only(r, id("007", PAIR_1)).transition());
        assertEquals(Transition.NEW, only(r, id("07", PAIR_1)).transition());
        assertEquals(T1, only(r, id("07", PAIR_1)).firstObservedAnyVersion());
    }

    @Test
    void parentPathKeepsSharedRuleUidApart() {
        FindingIdentity underP1 = new FindingIdentity(POLICY, UID_A, List.of("parent-1"), List.of(PAIR_1));
        FindingIdentity underP2 = new FindingIdentity(POLICY, UID_A, List.of("parent-2"), List.of(PAIR_1));
        KnownFinding k = open(underP1, T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", eval(underP2, "VIOLATION", Set.of())), T1);
        assertEquals(Transition.RESOLVED, only(r, underP1).transition());
        assertEquals(Transition.NEW, only(r, underP2).transition());
    }

    @Test
    void pairOrderDoesNotMatterButPairValuesAreExact() {
        KnownFinding k = open(id(UID_A, PAIR_2, PAIR_1), T0, T0, T0, V1);
        Finding same = only(ZoneMatrixFindingLifecycle.next(List.of(k),
                run(V1, "COMPLETE", violation(UID_A, PAIR_1, PAIR_2)), T1), id(UID_A, PAIR_1, PAIR_2));
        assertEquals(Transition.REPRODUCED, same.transition());
        assertEquals(T0, same.firstObservedAt());
    }

    @Test
    void knownFindingOfAnotherPolicySourceIsRejected() {
        FindingIdentity foreign = new FindingIdentity("POL-BRAVO", UID_A, List.of(), List.of(PAIR_1));
        assertThrows(IllegalArgumentException.class, () -> ZoneMatrixFindingLifecycle.next(
                List.of(open(foreign, T0, T0, T0, V1)), run(V1, "COMPLETE"), T1));
    }

    // ---- pair sets and ZM-42 --------------------------------------------------------------------------------------

    @Test
    void zm42_firstObservedAnyVersionSurvivesNewVersion() {
        // PAIR_1 violated since T0 under V1; that finding was resolved when V2 was published; the violation returns
        // under V2 as a NEW finding that still reports the T0 start.
        Result r = ZoneMatrixFindingLifecycle.next(List.of(resolvedEarlier(id(UID_A, PAIR_1), T0, V1)),
                run(V2, "COMPLETE", violation(UID_A, PAIR_1)), T2);
        Finding f = only(r, id(UID_A, PAIR_1));
        assertEquals(Transition.NEW, f.transition());
        assertEquals(T2, f.firstObservedAt());
        assertEquals(T0, f.firstObservedAnyVersion());
        assertEquals(T0, f.pairFirstObserved().get(PAIR_1));
    }

    @Test
    void zm42_reproducedFindingKeepsEarlierAnyVersionDatePerPair() {
        KnownFinding older = resolvedEarlier(id(UID_A, PAIR_1), T0, V1);
        KnownFinding current = open(id(UID_A, PAIR_1, PAIR_2), T1, T1, T1, V2);
        Finding f = only(ZoneMatrixFindingLifecycle.next(List.of(older, current),
                run(V2, "COMPLETE", violation(UID_A, PAIR_1, PAIR_2)), T2), id(UID_A, PAIR_1, PAIR_2));
        assertEquals(Transition.REPRODUCED, f.transition());
        assertEquals(T1, f.firstObservedAt());
        assertEquals(T0, f.pairFirstObserved().get(PAIR_1));
        assertEquals(T1, f.pairFirstObserved().get(PAIR_2));
        assertEquals(T0, f.firstObservedAnyVersion());
    }

    @Test
    void growingPairSetOpensNewFindingAndSupersedesOldOneWithPerPairDates() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", violation(UID_A, PAIR_1, PAIR_2)), T1);
        Finding old = only(r, id(UID_A, PAIR_1));
        assertEquals(Transition.RESOLVED, old.transition());
        assertEquals(ResolutionReason.RULE_CHANGED, old.resolutionReason());
        assertEquals(id(UID_A, PAIR_1, PAIR_2), old.supersededBy());
        Finding grown = only(r, id(UID_A, PAIR_1, PAIR_2));
        assertEquals(Transition.NEW, grown.transition());
        assertEquals(T1, grown.firstObservedAt());
        assertEquals(T0, grown.pairFirstObserved().get(PAIR_1));
        assertEquals(T1, grown.pairFirstObserved().get(PAIR_2));
        assertEquals(T0, grown.firstObservedAnyVersion());
    }

    @Test
    void shrinkingPairSetLosesNoHistory() {
        KnownFinding k = open(id(UID_A, PAIR_1, PAIR_2), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k), run(V1, "COMPLETE", violation(UID_A, PAIR_2)), T1);
        assertEquals(Transition.RESOLVED, only(r, id(UID_A, PAIR_1, PAIR_2)).transition());
        Finding smaller = only(r, id(UID_A, PAIR_2));
        assertEquals(Transition.NEW, smaller.transition());
        assertEquals(T0, smaller.firstObservedAnyVersion());
    }

    @Test
    void changedPairSetInIncompleteRunKeepsOldOpenNextToNew() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k),
                run(V1, "INCOMPLETE", violation(UID_A, PAIR_1, PAIR_2)), T1);
        assertEquals(Transition.STILL_OPEN_NOT_COLLECTED, only(r, id(UID_A, PAIR_1)).transition());
        assertEquals(Transition.NEW, only(r, id(UID_A, PAIR_1, PAIR_2)).transition());
        assertEquals(2, r.open().size());
    }

    @Test
    void storedFindingWithoutPerPairMapSuppliesItsAnyVersionDateForEveryPair() {
        KnownFinding k = open(id(UID_A, PAIR_1, PAIR_2), T1, T0, T1, V1);
        Finding f = only(ZoneMatrixFindingLifecycle.next(List.of(k),
                run(V1, "COMPLETE", violation(UID_A, PAIR_1, PAIR_2)), T2), id(UID_A, PAIR_1, PAIR_2));
        assertEquals(T0, f.pairFirstObserved().get(PAIR_1));
        assertEquals(T0, f.pairFirstObserved().get(PAIR_2));
        assertEquals(T1, f.firstObservedAt());
    }

    @Test
    void anyVersionDateNeverLaterThanFirstObserved() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        Result r = ZoneMatrixFindingLifecycle.next(List.of(k),
                run(V2, "COMPLETE", violation(UID_A, PAIR_1), violation(UID_B, PAIR_2)), T2);
        for (Finding f : r.findings()) {
            assertFalse(f.firstObservedAnyVersion().isAfter(f.firstObservedAt()));
        }
    }

    @Test
    void undecidedRuleWithoutOpenFindingCreatesNothing() {
        Result r = ZoneMatrixFindingLifecycle.next(List.of(), run(V1, "COMPLETE", nonViolating(UID_A, "UNKNOWN")), T1);
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void sameInputGivesSameOutput() {
        KnownFinding k = open(id(UID_A, PAIR_1), T0, T0, T0, V1);
        EvaluationResult run = run(V2, "COMPLETE", violation(UID_B, PAIR_2));
        assertEquals(ZoneMatrixFindingLifecycle.next(List.of(k), run, T1), ZoneMatrixFindingLifecycle.next(List.of(k), run, T1));
    }
}
