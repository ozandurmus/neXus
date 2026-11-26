package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * The reorder-only proposal engine ({@link PolicyProposal}): no candidate, a SAFE move, the inspection label (Q2), a
 * refusal that leaves the snapshot alone and does not stop the next subject, stacking that matters in both directions (the
 * proof of a later move reads the order the earlier moves produced, design 6.1), NO_LONGER_A_CANDIDATE, the one shared
 * budget (Q8), another vendor, an incomplete snapshot, a stored snapshot that is never touched, and G6 (what is shared by
 * identity and how {@code Rule.number} is kept). Documentation addresses (192.0.2.0/24, 198.51.100.0/24) and masked names
 * only. The gateways "fw-tango-04" and "targets" (Policy Targets) are the install-on values: a rule on "targets" is not
 * provably disjoint from one on a gateway, which is the one way an I4 rule meets a crossed rule of another verdict.
 */
class PolicyProposalTest {
    /** PolicyReorderTest's objects plus the four quarters of 192.0.2.0/24 that the stacking fixtures need. */
    static PolicySnapshot snapshot(Section... sections) {
        var base = PolicyReorderTest.snapshot(sections);
        var objects = new HashMap<>(base.objects());
        objects.put("lo-a", object("lo-a", "address", "ip-netmask: 192.0.2.0/26"));
        objects.put("lo-b", object("lo-b", "address", "ip-netmask: 192.0.2.64/26"));
        objects.put("hi-b", object("hi-b", "address", "ip-netmask: 192.0.2.192/26"));
        return new PolicySnapshot(base.metadata(), base.sections(), objects, base.failures());
    }
    static Rule multi(Rule r, String... refs) { return edit(r, new Cell(List.of(refs), false), Map.of()); }
    static List<String> order(PolicySnapshot snapshot) { return snapshot.sections().stream().flatMap(s -> s.rules().stream()).map(Rule::id).toList(); }
    static List<Integer> numbers(PolicySnapshot snapshot) { return snapshot.sections().stream().flatMap(s -> s.rules().stream()).map(Rule::number).toList(); }
    static List<String> results(PolicyProposal.Result result) { return result.steps().stream().map(PolicyProposal.Step::result).toList(); }
    static PolicyReorder.Result alone(PolicySnapshot snapshot) { return PolicyReorderTest.reorder(snapshot); }

    // The two stacking fixtures, built by construction (see the tests for how they work).
    /**
     * Stacking 1. sB alone is SAFE: its B is bB and the only rule it crosses is sA (same verdict). sA moves first, to above
     * bA, so that it lands above X ("Drop" on Policy Targets, which overlaps sB and is not provably disjoint from it); sA is
     * now the earliest overlapping rule of sB and X lies between them.
     */
    static PolicySnapshot stackedRefusal() {
        return snapshot(cpSection("s1", null, cp("bA", 1, "Accept", "lo-a", "fw-tango-04"), cp("x", 2, "Drop", "hi-b", "targets"),
            cp("bB", 3, "Accept", "hi-b", "fw-tango-04"), cp("sA", 4, "Accept", "lo", "fw-tango-04"),
            multi(cp("sB", 5, "Accept", "lo-b", "fw-tango-04"), "lo-b", "hi")));
    }
    /**
     * Stacking 2. sB alone is refused: it would cross x (Drop, Policy Targets, overlapping it). x is itself an I4 rule (it
     * overlaps bX, same placement, same action) and its promote crosses only bb, which is disjoint from x's box. After x
     * moves above bX, nothing lies between bb and sB any more.
     */
    static PolicySnapshot stackedRelease() {
        return snapshot(cpSection("s1", null, cp("bX", 1, "Drop", "hi-b", "targets"), cp("bb", 2, "Accept", "lo", "fw-tango-04"),
            cp("x", 3, "Drop", "hi", "targets"), cp("sB", 4, "Accept", "net", "fw-tango-04")));
    }

    @Test void noCandidateGivesAnEmptyProposalWithItsReason() {
        var stored = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "other", "any")));
        var result = PolicyProposal.allOfferedMoves(stored);
        assertEquals(PolicyProposal.NO_MOVES, result.packageVerdict());
        assertEquals(PolicyProposal.NO_SUBJECT, result.emptyReason());
        assertTrue(result.steps().isEmpty());
        assertEquals(0, result.accepted());
        assertNull(result.label());
        assertFalse(result.budgetStopped()); assertFalse(result.inspectionChanges());
        assertSame(stored, result.snapshot(), "nothing accepted: the stored instance");
        assertEquals(Set.copyOf(PolicyProposal.REFUSALS), result.refused().keySet());
        assertTrue(result.refused().values().stream().allMatch(n -> n == 0));
        assertEquals(PolicyProposal.NO_SUBJECT, PolicyProposal.propose(stored, List.of()).emptyReason());
        // A subject that is no I4 rule (or no rule at all) is a refused step, not an error.
        var named = PolicyProposal.propose(stored, List.of("r2", "no-such-rule"));
        assertEquals(List.of(PolicyProposal.NO_LONGER_A_CANDIDATE, PolicyProposal.NO_LONGER_A_CANDIDATE), results(named));
        assertEquals(2, named.refused().get(PolicyProposal.NO_LONGER_A_CANDIDATE));
        assertNull(named.emptyReason()); assertEquals(PolicyProposal.NO_MOVES, named.packageVerdict());
        assertSame(stored, named.snapshot());
    }
    @Test void oneSafeMoveIsAcceptedAndBuildsTheFinalSnapshot() {
        var stored = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        var result = PolicyProposal.allOfferedMoves(stored);
        assertEquals(1, result.steps().size());
        var step = result.steps().get(0);
        assertEquals(new PolicyProposal.Step("r2", "r1", PolicyReorder.SAFE, null, true, PolicyReorder.EQUIVALENT, 0, false), step);
        assertEquals(PolicyReorder.EQUIVALENT, result.packageVerdict());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, result.label());
        assertEquals(1, result.accepted()); assertFalse(result.inspectionChanges()); assertFalse(result.budgetStopped());
        assertNull(result.emptyReason());
        assertEquals(List.of("r2", "r1"), order(result.snapshot()));
        assertEquals(List.of("r1", "r2"), order(stored), "the stored order is untouched");
        // The slot keeps its number: the layer check still sees strictly increasing numbers.
        assertEquals(List.of(1, 2), numbers(result.snapshot()));
        // The promoted rule really is first now: the same engines read the in-memory snapshot (G6).
        var hygiene = PolicyHygiene.analyze(result.snapshot(), 90, NOW, 250000);
        assertEquals("REDUNDANT", hygiene.rules().get("r1").shadowStatus(), "r1 is now behind the whole /24");
    }
    @Test void aProfileOrLogDifferenceLabelsThePackageAsInspectionChanging() {
        for (var changed : List.of(PolicyReorderTest.profiled(cp("r2", 2, "Accept", "net", "any"), "PROF-ALPHA"), PolicyReorderTest.withLog(cp("r2", 2, "Accept", "net", "any"), "None"))) {
            var stored = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), changed));
            var result = PolicyProposal.allOfferedMoves(stored);
            assertEquals(List.of(PolicyReorder.SAFE_INSPECTION_CHANGES), results(result));
            assertTrue(result.steps().get(0).accepted()); assertTrue(result.steps().get(0).inspectionChanges());
            assertTrue(result.inspectionChanges());
            assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, result.label(), "never \"no behaviour change\" (Q2)");
            assertEquals(List.of("r2", "r1"), order(result.snapshot()));
        }
        // One SAFE step and one inspection step: the package is labelled by the second.
        var mixed = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "fw-tango-04"), cp("r2", 2, "Accept", "net", "fw-tango-04"),
            cp("r3", 3, "Accept", "lo", "fw-juliet-06"), PolicyReorderTest.withLog(cp("r4", 4, "Accept", "net", "fw-juliet-06"), "None")));
        var result = PolicyProposal.allOfferedMoves(mixed);
        assertEquals(List.of(PolicyReorder.SAFE, PolicyReorder.SAFE_INSPECTION_CHANGES), results(result));
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, result.label());
        assertEquals(2, result.accepted());
    }
    @Test void aRefusedMoveLeavesTheSnapshotAndDoesNotStopTheNextSubject() {
        // r2's promote would cross x (Drop on Policy Targets, overlapping r2): refused. r4's promote is independent: accepted.
        var stored = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "fw-tango-04"), cp("x", 2, "Drop", "hi-a", "targets"),
            cp("r2", 3, "Accept", "net", "fw-tango-04"), cp("r3", 4, "Accept", "lo", "fw-juliet-06"), cp("r4", 5, "Accept", "net", "fw-juliet-06")));
        var alone = alone(stored);
        assertEquals(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, alone.moves().get("r2").result());
        assertEquals(PolicyReorder.SAFE, alone.moves().get("r4").result());
        var result = PolicyProposal.propose(stored, List.of("r2", "r4"));
        assertEquals(List.of(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, PolicyReorder.SAFE), results(result));
        assertFalse(result.steps().get(0).accepted()); assertEquals("r1", result.steps().get(0).above());
        assertNull(result.steps().get(0).reason()); assertEquals(0, result.steps().get(0).crossedRules());
        assertTrue(result.steps().get(1).accepted());
        assertEquals(List.of("r1", "x", "r2", "r4", "r3"), order(result.snapshot()), "only r4 moved");
        assertEquals(1, result.refused().get(PolicyReorder.UNSAFE_DIFFERENT_EFFECT));
        assertEquals(PolicyReorder.EQUIVALENT, result.packageVerdict());
        // The other order of the same list: the refusal does not depend on what came before it.
        var reversed = PolicyProposal.propose(stored, List.of("r4", "r2"));
        assertEquals(List.of(PolicyReorder.SAFE, PolicyReorder.UNSAFE_DIFFERENT_EFFECT), results(reversed));
        assertEquals(order(result.snapshot()), order(reversed.snapshot()));
        // Only refusals: the stored instance comes back.
        var refusedOnly = PolicyProposal.propose(stored, List.of("r2"));
        assertEquals(PolicyProposal.NO_MOVES, refusedOnly.packageVerdict());
        assertSame(stored, refusedOnly.snapshot());
    }
    @Test void anAnchorAndASectionBoundaryAreRefusedWithTheEnginesReason() {
        var anchored = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), PolicyUnionCoverTest.timed(cp("x", 2, "Accept", "other", "any")), cp("r2", 3, "Accept", "net", "any")));
        var blocked = PolicyProposal.allOfferedMoves(anchored);
        assertEquals(List.of(PolicyReorder.ANCHOR_BLOCKED), results(blocked));
        assertEquals(1, blocked.refused().get(PolicyReorder.ANCHOR_BLOCKED));
        assertSame(anchored, blocked.snapshot());
        var split = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")), cpSection("s2", null, cp("r2", 2, "Accept", "net", "any")));
        assertEquals(1, PolicyInsightsTest.of(PolicyInsightsTest.report(split), "I4").size(), "an I4 rule across the section boundary");
        var crossing = PolicyProposal.allOfferedMoves(split);
        assertEquals(List.of(PolicyReorder.CROSSES_SECTION), results(crossing));
        assertEquals(1, crossing.refused().get(PolicyReorder.CROSSES_SECTION));
        assertSame(split, crossing.snapshot());
    }
    @Test void stackingThatMattersASecondMoveSafeAloneIsRefusedAfterTheFirst() {
        var stored = stackedRefusal();
        var alone = alone(stored);
        assertEquals(PolicyReorder.SAFE, alone.moves().get("sA").result());
        assertEquals(PolicyReorder.SAFE, alone.moves().get("sB").result(), "against the stored order sB is SAFE");
        assertEquals("bB", alone.moves().get("sB").above().rule().id());
        var result = PolicyProposal.allOfferedMoves(stored);
        assertEquals(List.of("sA", "sB"), result.steps().stream().map(PolicyProposal.Step::subject).toList());
        assertEquals(List.of(PolicyReorder.SAFE, PolicyReorder.UNSAFE_DIFFERENT_EFFECT), results(result), "after sA moved, sB's proof reads the new order");
        assertEquals("bA", result.steps().get(0).above());
        assertEquals("sA", result.steps().get(1).above(), "the earliest overlapping rule of sB is now the promoted sA");
        assertEquals(List.of("sA", "bA", "x", "bB", "sB"), order(result.snapshot()));
        assertEquals(1, result.accepted());
        // Without the first move the second is accepted: the refusal is the stacking's.
        var second = PolicyProposal.propose(stored, List.of("sB"));
        assertEquals(List.of(PolicyReorder.SAFE), results(second));
        assertEquals(List.of("bA", "x", "sB", "bB", "sA"), order(second.snapshot()));
    }
    @Test void stackingThatMattersARefusedMoveBecomesSafeAfterAnEarlierOne() {
        var stored = stackedRelease();
        var alone = alone(stored);
        assertEquals(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, alone.moves().get("sB").result(), "against the stored order sB crosses x");
        assertEquals(PolicyReorder.SAFE, alone.moves().get("x").result());
        var result = PolicyProposal.allOfferedMoves(stored);
        assertEquals(List.of("x", "sB"), result.steps().stream().map(PolicyProposal.Step::subject).toList());
        assertEquals(List.of(PolicyReorder.SAFE, PolicyReorder.SAFE), results(result), "after x moved above bX, sB crosses nothing");
        assertEquals(2, result.accepted());
        assertEquals(List.of("x", "bX", "sB", "bb"), order(result.snapshot()));
        assertEquals(PolicyReorder.EQUIVALENT, result.packageVerdict());
        assertEquals(0, result.steps().get(1).crossedRules());
        // In the other order the refusal stands and x still moves.
        var reversed = PolicyProposal.propose(stored, List.of("sB", "x"));
        assertEquals(List.of(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, PolicyReorder.SAFE), results(reversed));
        assertEquals(List.of("x", "bX", "bb", "sB"), order(reversed.snapshot()));
    }
    @Test void aMoveCanLoseItsOverlapToAnEarlierOneAndIsNoLongerACandidate() {
        // sA (the whole /24) is promoted above bA and now covers s2 completely: s2 is redundant, no I4 rule any more.
        var stored = snapshot(cpSection("s1", null, cp("bA", 1, "Accept", "lo-a", "fw-tango-04"), cp("s2", 2, "Accept", "lo", "fw-tango-04"),
            cp("sA", 3, "Accept", "net", "fw-tango-04")));
        var alone = alone(stored);
        assertEquals(PolicyReorder.SAFE, alone.moves().get("s2").result());
        assertEquals(PolicyReorder.SAFE, alone.moves().get("sA").result());
        var result = PolicyProposal.propose(stored, List.of("sA", "s2"));
        assertEquals(List.of(PolicyReorder.SAFE, PolicyProposal.NO_LONGER_A_CANDIDATE), results(result));
        assertNull(result.steps().get(1).above()); assertNull(result.steps().get(1).reason()); assertNull(result.steps().get(1).equivalence());
        assertEquals(1, result.refused().get(PolicyProposal.NO_LONGER_A_CANDIDATE));
        assertEquals(List.of("sA", "bA", "s2"), order(result.snapshot()));
        assertEquals(PolicyReorder.EQUIVALENT, result.packageVerdict(), "a refused step is not part of the package");
        // A subject listed twice: after its own promote it overlaps nothing earlier.
        var twice = PolicyProposal.propose(stored, List.of("sA", "sA"));
        assertEquals(List.of(PolicyReorder.SAFE, PolicyProposal.NO_LONGER_A_CANDIDATE), results(twice));
    }
    @Test void theOneSharedBudgetStopsTheProposalWithNotProvenAndNeverAnUnprovenMove() {
        var stored = stackedRelease();
        var full = PolicyProposal.allOfferedMoves(stored);
        assertEquals(2, full.accepted());
        boolean midProposal = false, stoppedAtOnce = false;
        for (int work = 0; work < 1200; work++) {
            var result = PolicyProposal.allOfferedMoves(stored, work, work);
            assertEquals(full.steps().size(), result.steps().size(), "no silent skip, work " + work);
            for (int i = 0; i < result.steps().size(); i++) {
                var step = result.steps().get(i);
                if (step.accepted()) assertTrue(full.steps().get(i).accepted() && step.subject().equals(full.steps().get(i).subject()), "work " + work);
                if (!step.result().equals(PolicyReorder.NOT_PROVEN)) continue;
                assertEquals(PolicyReorder.BUDGET, step.reason()); assertEquals(PolicyReorder.NOT_PROVEN, step.equivalence());
                assertFalse(step.accepted());
                // The run stops there: every later step is NOT_PROVEN too.
                for (var later : result.steps().subList(i, result.steps().size())) assertEquals(PolicyReorder.NOT_PROVEN, later.result(), "work " + work);
            }
            if (result.budgetStopped()) {
                assertEquals(PolicyReorder.NOT_PROVEN, result.packageVerdict(), "work " + work);
                assertNull(result.label());
                assertTrue(result.refused().get(PolicyReorder.NOT_PROVEN) >= 1);
                if (result.accepted() == 1) { midProposal = true; assertEquals(List.of("x", "bX", "bb", "sB"), order(result.snapshot())); }
                if (result.accepted() == 0) { stoppedAtOnce = true; assertSame(stored, result.snapshot()); }
            } else {
                assertEquals(full.accepted(), result.accepted(), "a run that was not stopped is the full run, work " + work);
                assertEquals(order(full.snapshot()), order(result.snapshot()));
            }
        }
        assertTrue(midProposal, "a budget that proves the first move and runs out on the second");
        assertTrue(stoppedAtOnce, "a budget too small for the first move");
        // A per-rule window of nothing proves nothing either, whatever the pool.
        var windowed = PolicyProposal.allOfferedMoves(stored, PolicyProposal.TOTAL_WORK, 0);
        assertTrue(windowed.budgetStopped()); assertEquals(0, windowed.accepted());
    }
    @Test void anotherVendorAndAnIncompleteSnapshotGiveAnEmptyProposalNeverAnException() {
        for (String vendor : List.of("ASA", "FGT")) {
            var unsup = PolicyUnionCoverTest.unsupportedVendorSnapshot(vendor);
            var other = PolicyProposal.propose(unsup, List.of("r2"));
            assertEquals(PolicyProposal.UNSUPPORTED_VENDOR, other.emptyReason(), vendor);
            assertTrue(other.steps().isEmpty()); assertSame(unsup, other.snapshot()); assertEquals(PolicyProposal.NO_MOVES, other.packageVerdict());
            assertEquals(PolicyProposal.UNSUPPORTED_VENDOR, PolicyProposal.allOfferedMoves(unsup).emptyReason(), vendor);
        }
        var complete = stackedRelease();
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(), List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        var result = PolicyProposal.propose(incomplete, List.of("x", "sB"));
        assertEquals(PolicyProposal.INCOMPLETE, result.emptyReason());
        assertTrue(result.steps().isEmpty()); assertSame(incomplete, result.snapshot());
        assertEquals(PolicyProposal.INCOMPLETE, PolicyProposal.allOfferedMoves(incomplete).emptyReason());
    }
    @Test void theStoredSnapshotIsNeverMutated() {
        var stored = stackedRelease();
        var twin = stackedRelease();
        assertEquals(twin, stored, "the fixture is deterministic");
        var sections = stored.sections(); var rules = sections.get(0).rules();
        var result = PolicyProposal.allOfferedMoves(stored);
        assertEquals(2, result.accepted());
        assertNotSame(stored, result.snapshot());
        assertSame(sections, stored.sections()); assertSame(rules, stored.sections().get(0).rules());
        assertEquals(twin, stored, "content unchanged: order, numbers, every field");
        assertEquals(List.of("bX", "bb", "x", "sB"), order(stored));
        assertEquals(List.of(1, 2, 3, 4), numbers(stored));
        // Running it again on the same instance gives the same proposal.
        assertEquals(result.steps(), PolicyProposal.allOfferedMoves(stored).steps());
    }
    @Test void g6KeepsObjectsMetadataAndOtherSectionsByIdentityAndChangesOnlyOneSection() {
        var stored = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")),
            cpSection("s2", null, cp("r3", 3, "Accept", "other", "any"), cp("r4", 4, "Drop", "other", "any")));
        var moved = PolicyProposal.reordered(stored, "r2", "r1");
        assertSame(stored.metadata(), moved.metadata());
        assertSame(stored.objects(), moved.objects());
        assertSame(stored.failures(), moved.failures());
        assertEquals(2, moved.sections().size());
        assertSame(stored.sections().get(1), moved.sections().get(1), "the other section is the same instance");
        var section = moved.sections().get(0);
        assertEquals(stored.sections().get(0).id(), section.id()); assertEquals(stored.sections().get(0).name(), section.name());
        assertEquals(stored.sections().get(0).source(), section.source()); assertEquals(stored.sections().get(0).parentRuleId(), section.parentRuleId());
        assertEquals(List.of("r2", "r1"), section.rules().stream().map(Rule::id).toList());
        assertEquals(List.of("r1", "r2"), stored.sections().get(0).rules().stream().map(Rule::id).toList());
        // Every field but the number of a moved rule is the stored rule's.
        var r2 = stored.sections().get(0).rules().get(1);
        var promoted = section.rules().get(0);
        assertEquals(r2.id(), promoted.id()); assertSame(r2.source(), promoted.source()); assertEquals(r2.extras(), promoted.extras());
        assertEquals(r2.action(), promoted.action()); assertEquals(r2.log(), promoted.log()); assertEquals(r2.name(), promoted.name());
        assertEquals(List.of(1, 2), section.rules().stream().map(Rule::number).toList());
        // A rule outside the moved stretch is the stored instance.
        var wide = snapshot(cpSection("s1", null, cp("a", 1, "Accept", "other", "any"), cp("b", 2, "Accept", "lo", "any"),
            cp("c", 3, "Accept", "other", "any"), cp("d", 4, "Accept", "net", "any"), cp("e", 5, "Accept", "other", "any")));
        var inner = PolicyProposal.reordered(wide, "d", "b");
        var before = wide.sections().get(0).rules(); var after = inner.sections().get(0).rules();
        assertEquals(List.of("a", "d", "b", "c", "e"), after.stream().map(Rule::id).toList());
        assertSame(before.get(0), after.get(0)); assertSame(before.get(4), after.get(4));
        assertEquals(List.of(1, 2, 3, 4, 5), after.stream().map(Rule::number).toList(), "slot numbers: strictly increasing, same first and last");
        // A move that is no move of one section is a defect of the caller, never data.
        assertThrows(IllegalStateException.class, () -> PolicyProposal.reordered(stored, "r1", "r2"));
        assertThrows(IllegalStateException.class, () -> PolicyProposal.reordered(stored, "r3", "r1"));
        assertThrows(IllegalStateException.class, () -> PolicyProposal.reordered(stored, "r2", "missing"));
        assertThrows(IllegalStateException.class, () -> PolicyProposal.reordered(stored, "missing", "r1"));
    }
    @Test void aMovedRulesNumberHasToBeReassignedBecauseTheLayerCheckReadsNumbersAsOrder() {
        var stored = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any")));
        // Keeping each rule's own number would put the layer's numbers out of order: the section is then "not analysed".
        var kept = stored.sections().get(0).rules();
        var naive = new Section("s1", "OBJ-SECTION-01", "CP access layer", null, List.of(kept.get(1), kept.get(0)));
        var naiveScope = new PolicyNormalization.EvaluationScope<List<Object>>(stored.metadata(), ArrayList::new);
        naiveScope.advance(naive);
        assertTrue(naiveScope.unproven, "unchanged numbers, rules reordered: the evaluation order is unproven");
        var proper = PolicyProposal.reordered(stored, "r2", "r1");
        var properScope = new PolicyNormalization.EvaluationScope<List<Object>>(proper.metadata(), ArrayList::new);
        properScope.advance(proper.sections().get(0));
        assertFalse(properScope.unproven, "positional numbers keep it proven");
        // Several moves in a row keep every layer strictly increasing.
        var result = PolicyProposal.allOfferedMoves(stackedRelease());
        var numbers = numbers(result.snapshot());
        for (int i = 1; i < numbers.size(); i++) assertTrue(numbers.get(i) > numbers.get(i - 1), numbers.toString());
    }

    @Test void paloAltoProposalPromotesWithinScopesRefusesPhaseAndDeviceGroupCrossingsAndMatchesReference() {
        String za = PolicyUnionCoverTest.ZA, zb = PolicyUnionCoverTest.ZB, zc = PolicyUnionCoverTest.ZC;
        // Shared pre: s1 (lo)
        // DG pre: dCross (net, overlaps Shared pre s1 -> CROSSES_SECTION!), dBroad (lo, ZC, PROF-ALPHA), dSpec (net, ZC, PROF-BRAVO -> SAFE_INSPECTION_CHANGES)
        // DG post: pBroad (lo, ZC, svc-p2), pAnchor (app-default), pBlocked (net, ZC, svc-p2 -> ANCHOR_BLOCKED), pSafeBroad (lo, ZB, svc-p2), pSafeSpec (net, ZB, svc-p2 -> SAFE)
        var stored = PolicyUnionCoverTest.panSnapshot(
            PolicyUnionCoverTest.panSection("Shared", "pre-rulebase",
                PolicyUnionCoverTest.panRule("s1", 1, "allow", List.of(za), List.of(zb), "lo", "any", "svc-p1", Map.of())),
            PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
                PolicyUnionCoverTest.panRule("dCross", 1, "allow", List.of(za), List.of(zb), "net", "any", "svc-p1", Map.of()),
                PolicyUnionCoverTest.panRule("dBroad", 2, "allow", List.of(zc), List.of(zb), "lo", "any", "svc-p1", Map.of("profile-setting/group", List.of("PROF-ALPHA"))),
                PolicyUnionCoverTest.panRule("dSpec", 3, "allow", List.of(zc), List.of(zb), "net", "any", "svc-p1", Map.of("profile-setting/group", List.of("PROF-BRAVO")))),
            PolicyUnionCoverTest.panSection("DG-TANGO-01", "post-rulebase",
                PolicyUnionCoverTest.panRule("pBroad", 1, "allow", List.of(zc), List.of(zb), "lo", "other", "svc-p2", Map.of()),
                PolicyUnionCoverTest.panRule("pAnchor", 2, "allow", List.of(zc), List.of(zb), "h0", "other", PolicyUnionCoverTest.panAppDefaultRef(), Map.of()),
                PolicyUnionCoverTest.panRule("pBlocked", 3, "allow", List.of(zc), List.of(zb), "net", "other", "svc-p2", Map.of()),
                PolicyUnionCoverTest.panRule("pSafeBroad", 4, "allow", List.of(zb), List.of(zc), "lo", "other", "svc-p2", Map.of("profile-setting/group", List.of("PROF-ALPHA"))),
                PolicyUnionCoverTest.panRule("pSafeSpec", 5, "allow", List.of(zb), List.of(zc), "net", "other", "svc-p2", Map.of("profile-setting/group", List.of("PROF-ALPHA")))));

        var stats = new PolicyProposal.Stats();
        var result = PolicyProposal.propose(stored, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK, true, stats);
        var reference = PolicyProposal.proposeReference(stored, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK);
        assertEquals(reference.steps(), result.steps());
        assertEquals(reference.packageVerdict(), result.packageVerdict());
        assertEquals(reference.label(), result.label());
        assertEquals(order(reference.snapshot()), order(result.snapshot()));
        assertEquals(List.of(
            PolicyReorder.CROSSES_SECTION,
            PolicyReorder.SAFE_INSPECTION_CHANGES,
            PolicyReorder.ANCHOR_BLOCKED,
            PolicyReorder.SAFE), results(result));
        assertEquals(2, result.accepted());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, result.label());
        assertEquals(PolicyReorder.CHANGES, result.packageVerdict());
        // Verify Shared pre stayed ["s1"], DG pre reordered dSpec above dBroad, DG post reordered pSafeSpec above pSafeBroad.
        assertEquals(List.of("s1"), result.snapshot().sections().get(0).rules().stream().map(Rule::id).toList());
        assertEquals(List.of("dCross", "dSpec", "dBroad"), result.snapshot().sections().get(1).rules().stream().map(Rule::id).toList());
        assertEquals(List.of("pBroad", "pAnchor", "pBlocked", "pSafeSpec", "pSafeBroad"), result.snapshot().sections().get(2).rules().stream().map(Rule::id).toList());
    }

    @Test void paloAltoProposalDifferentialOracleAndScopeLocalParity() {
        Random random = new Random(2026112605L);
        Set<PolicyUnionCoverTest.PanPacket> allUniverse = new HashSet<>(PolicyUnionCoverTest.panRulePackets(
            PolicyUnionCoverTest.panRule("u", 1, "allow", List.of("any"), List.of("any"), "any", "any", "any", Map.of()),
            PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
        allUniverse.addAll(PolicyUnionCoverTest.panRulePackets(
            PolicyUnionCoverTest.panRule("u2", 1, "allow", List.of("any"), List.of("any"), "other", "other", "any", Map.of()),
            PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));

        int acceptedMoves = 0, safePackages = 0, budgetStops = 0;
        for (int trial = 0; trial < 220; trial++) {
            var realized = PolicyUnionCoverTest.panRealize(
                PolicyUnionCoverTest.randomPanRulebase(random, trial % 2 == 0, trial % 3 != 0));
            var items = realized.items();
            var stored = realized.snapshot();
            var res = PolicyProposal.allOfferedMoves(stored);
            var ref = PolicyProposal.proposeReference(stored, null, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK);
            assertEquals(ref.steps(), res.steps(), "ScopeLocal vs Reference steps in trial " + trial);
            assertEquals(ref.packageVerdict(), res.packageVerdict());
            assertEquals(ref.label(), res.label());
            assertEquals(order(ref.snapshot()), order(res.snapshot()));

            if (res.accepted() > 0) {
                acceptedMoves += res.accepted();
                // Verify section membership never changed (no cross-phase or cross-DG move).
                assertEquals(stored.sections().size(), res.snapshot().sections().size());
                for (int s = 0; s < stored.sections().size(); s++) {
                    Set<String> beforeIds = new HashSet<>(stored.sections().get(s).rules().stream().map(Rule::id).toList());
                    Set<String> afterIds = new HashSet<>(res.snapshot().sections().get(s).rules().stream().map(Rule::id).toList());
                    assertEquals(beforeIds, afterIds, "rule crossed PAN section/phase/DG boundary in trial " + trial);
                }
                Map<String, PolicyUnionCoverTest.PanSynthetic> byId = new HashMap<>();
                Map<String, Set<PolicyUnionCoverTest.PanPacket>> pts = new HashMap<>();
                for (var item : items) {
                    byId.put(item.rule().id(), item);
                    pts.put(item.rule().id(), PolicyUnionCoverTest.panRulePackets(
                        item.rule(), PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
                }
                List<PolicyUnionCoverTest.PanSynthetic> afterItems = res.snapshot().sections().stream()
                    .flatMap(s -> s.rules().stream())
                    .map(r -> new PolicyUnionCoverTest.PanSynthetic(r, byId.get(r.id()).kind(), byId.get(r.id()).sectionIndex()))
                    .toList();
                boolean eqNoInspection = PolicyReorder.EQUIVALENT.equals(res.packageVerdict()) && !res.inspectionChanges();
                if (eqNoInspection) safePackages++;
                for (boolean anchorsMatch : new boolean[]{false, true}) {
                    for (var pkt : allUniverse) {
                        Rule beforeDec = PolicyMergeTest.panFirstMatch(items, pkt, anchorsMatch, pts);
                        Rule afterDec = PolicyMergeTest.panFirstMatch(afterItems, pkt, anchorsMatch, pts);
                        if (beforeDec == null && afterDec == null) continue;
                        assertNotNull(beforeDec); assertNotNull(afterDec);
                        if (beforeDec.id().equals(afterDec.id())) continue;
                        assertTrue(PolicyEffectSignature.sameVerdict(beforeDec, afterDec), "PAN proposal changed verdict in trial " + trial);
                        if (eqNoInspection) {
                            assertTrue(PolicyEffectSignature.sameFull("PAN", beforeDec, afterDec), "SAFE PAN proposal changed effect in trial " + trial);
                        }
                    }
                }
            }

            int lim = 10 + random.nextInt(150);
            var limRes = PolicyProposal.allOfferedMoves(stored, lim, Math.max(1, lim / 2));
            var limRef = PolicyProposal.proposeReference(stored, null, lim, Math.max(1, lim / 2));
            assertEquals(limRef.steps(), limRes.steps(), "limited ScopeLocal vs Reference in trial " + trial);
            if (limRes.budgetStopped()) budgetStops++;
        }
        assertTrue(acceptedMoves >= 25, "PAN accepted proposal moves exercised: " + acceptedMoves);
        assertTrue(safePackages >= 5, "PAN SAFE/EQUIVALENT packages exercised: " + safePackages);
        assertTrue(budgetStops >= 10, "PAN proposal budget stops exercised: " + budgetStops);
    }
}
