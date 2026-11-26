package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cell;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static com.securityexpert.nexus.ui2.service.policy.PolicyProposalMerges.Candidate;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Engine tests for {@link PolicyProposalMerges} (phase P2c-2d): no candidate, one accepted merge (identity kept, merged
 * removed, order-preserving de-duplicated union in the differing cell, comments not concatenated), chains of three
 * merging pair by pair (and refusing the second pair when the first merge invalidates it), a pair that becomes adjacent
 * only after an accepted reorder move, every refusal reason leaving the snapshot untouched, negated cells never merged,
 * another vendor, incomplete snapshot, shared budget swept over every work limit (prefix property), stored snapshot
 * untouched, G6 identities, and combined verdict and label cases. Synthetic documentation addresses (192.0.2.0/24,
 * 198.51.100.0/24) and masked names only.
 */
class PolicyProposalMergesTest {
    static PolicySnapshot snapshot(Section... sections) {
        var base = PolicyProposalTest.snapshot(sections);
        var objects = new HashMap<>(base.objects());
        objects.put("svc-http", object("svc-http", "service-tcp", "protocol: tcp", "port: 80"));
        objects.put("svc-https", object("svc-https", "service-tcp", "protocol: tcp", "port: 443"));
        objects.put("svc-dns", object("svc-dns", "service-udp", "protocol: udp", "port: 53"));
        return new PolicySnapshot(base.metadata(), base.sections(), objects, base.failures());
    }

    static Rule ruleWithCells(String id, int number, String action, List<String> sources, List<String> destinations,
            List<String> services, String... installOn) {
        Rule base = cp(id, number, action, sources.get(0), installOn);
        return new Rule(base.id(), base.uuid(), base.number(), base.name(), base.enabled(),
            new Cell(sources, false), new Cell(destinations, false), new Cell(services, false), base.application(),
            base.action(), base.log(), "COMMENT-" + id, base.extras(), base.hitCounts());
    }

    static List<String> order(PolicySnapshot snapshot) { return PolicyProposalTest.order(snapshot); }
    static List<Integer> numbers(PolicySnapshot snapshot) { return PolicyProposalTest.numbers(snapshot); }
    static List<String> results(PolicyProposalMerges.Result result) {
        return result.steps().stream().map(PolicyProposalMerges.Step::result).toList();
    }

    @Test void noCandidateGivesNoStepAndLeavesSnapshotUntouched() {
        var stored = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("r1", 1, "Accept", "lo", "lo", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("r2", 2, "Accept", "hi", "hi", "svc-http", "any")));
        var proposal = PolicyProposal.allOfferedMoves(stored);
        assertSame(stored, proposal.snapshot());

        for (var result : List.of(
                PolicyProposalMerges.allOfferedMerges(proposal),
                PolicyProposalMerges.allOfferedMerges(stored),
                PolicyProposalMerges.propose(proposal, List.of()))) {
            assertTrue(result.steps().isEmpty());
            assertEquals(0, result.accepted());
            assertFalse(result.budgetStopped());
            assertEquals(PolicyProposalMerges.NO_CANDIDATE, result.emptyReason());
            assertSame(stored, result.snapshot());
            assertEquals(Set.copyOf(PolicyProposalMerges.REFUSALS), result.refused().keySet());
            assertTrue(result.refused().values().stream().allMatch(n -> n == 0));
        }

        // Naming non-existent or non-adjacent rules produces NO_LONGER_A_CANDIDATE, never an exception.
        var nonCandidate = PolicyProposalMerges.propose(stored, List.of(new Candidate("r1", "no-such"), new Candidate("no-such", "r2")));
        assertEquals(List.of(PolicyProposalMerges.NO_LONGER_A_CANDIDATE, PolicyProposalMerges.NO_LONGER_A_CANDIDATE), results(nonCandidate));
        assertEquals(2, nonCandidate.refused().get(PolicyProposalMerges.NO_LONGER_A_CANDIDATE));
        assertEquals(0, nonCandidate.accepted());
        assertNull(nonCandidate.emptyReason());
        assertSame(stored, nonCandidate.snapshot());
    }

    @Test void oneAcceptedMergeKeepsSurvivingIdentityRemovesMergedAndUnionsDifferingCellWithoutConcatenatingComments() {
        // Test order-preserving, de-duplicated union on source ("lo-a", "lo-b" U "lo-b", "hi-a" -> "lo-a", "lo-b", "hi-a").
        var r1 = ruleWithCells("r1", 10, "Accept", List.of("lo-a", "lo-b"), List.of("other"), List.of("svc-http"), "any");
        var r2 = ruleWithCells("r2", 20, "Accept", List.of("lo-b", "hi-a"), List.of("other"), List.of("svc-http"), "any");
        var r3 = ruleWithCells("r3", 30, "Drop", List.of("other"), List.of("other"), List.of("svc-dns"), "any");
        var stored = snapshot(cpSection("s1", null, r1, r2, r3));

        var merges = PolicyProposalMerges.allOfferedMerges(stored);
        assertNull(merges.emptyReason());
        assertFalse(merges.budgetStopped());
        assertEquals(1, merges.accepted());
        assertEquals(1, merges.steps().size());
        assertTrue(merges.refused().values().stream().allMatch(n -> n == 0));

        var step = merges.steps().get(0);
        assertEquals(new PolicyProposalMerges.Step(
            "r1", "r2", "source", PolicyMerge.MERGE_CANDIDATE, null, true, PolicyReorder.EQUIVALENT), step);
        String nonSnapshotSummary = merges.steps() + " " + merges.refused() + " " + merges.emptyReason();
        assertFalse(nonSnapshotSummary.contains("COMMENT-") || nonSnapshotSummary.contains("OBJ-RULE-")
            || nonSnapshotSummary.contains("192.0.2") || nonSnapshotSummary.contains("198.51.100"));

        var afterRules = merges.snapshot().sections().get(0).rules();
        assertEquals(List.of("r1", "r3"), order(merges.snapshot()));
        assertEquals(List.of(10, 30), numbers(merges.snapshot()), "surviving rules keep strictly increasing numbers");
        Rule surviving = afterRules.get(0);
        assertEquals(r1.id(), surviving.id());
        assertEquals(r1.uuid(), surviving.uuid());
        assertEquals(r1.number(), surviving.number());
        assertEquals(r1.name(), surviving.name(), "surviving rule keeps its name");
        assertEquals("COMMENT-r1", surviving.comment(), "comment text is never concatenated into the snapshot");
        assertEquals(List.of("lo-a", "lo-b", "hi-a"), surviving.source().refs(), "order-preserving, de-duplicated union");
        assertFalse(surviving.source().negated());
        assertSame(r1.destination(), surviving.destination());
        assertSame(r1.service(), surviving.service());
        assertSame(r3, afterRules.get(1), "untouched rule is shared by identity (G6)");

        // Disjoint single-cell merge via PolicyProposal.Result (NO_MOVES -> merge) keeps r1 first as well.
        var disjointSrc = snapshot(cpSection("s1", null,
            ruleWithCells("s1r", 1, "Accept", List.of("lo-a"), List.of("other"), List.of("svc-http"), "any"),
            ruleWithCells("s2r", 2, "Accept", List.of("hi-a"), List.of("other"), List.of("svc-http"), "any")));
        var fromProposal = PolicyProposalMerges.allOfferedMerges(PolicyProposal.allOfferedMoves(disjointSrc));
        assertEquals(1, fromProposal.accepted());
        assertEquals("s1r", fromProposal.steps().get(0).surviving());
        assertEquals("s2r", fromProposal.steps().get(0).merged());

        // Destination and service single-cell merges also work identically.
        var dstSnap = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("d1", 1, "Accept", "lo", "lo-a", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("d2", 2, "Accept", "lo", "hi-a", "svc-http", "any")));
        var dstMerges = PolicyProposalMerges.allOfferedMerges(dstSnap);
        assertEquals(1, dstMerges.accepted());
        assertEquals("destination", dstMerges.steps().get(0).differingCell());
        assertEquals(List.of("lo-a", "hi-a"), dstMerges.snapshot().sections().get(0).rules().get(0).destination().refs());

        var svcSnap = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("v1", 1, "Accept", "lo", "other", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("v2", 2, "Accept", "lo", "other", "svc-https", "any")));
        var svcMerges = PolicyProposalMerges.allOfferedMerges(svcSnap);
        assertEquals(1, svcMerges.accepted());
        assertEquals("service", svcMerges.steps().get(0).differingCell());
        assertEquals(List.of("svc-http", "svc-https"), svcMerges.snapshot().sections().get(0).rules().get(0).service().refs());
    }

    @Test void chainOfThreeMergesPairByPairOnlyWhenSecondPairStaysACandidateInNewSnapshot() {
        // 1. Chain (a, b, c) all differing in source: (a+b) then (ab+c) both accepted into surviving rule a.
        var chain = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("a", 1, "Accept", "lo-a", "other", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("b", 2, "Accept", "lo-b", "other", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("c", 3, "Accept", "hi-a", "other", "svc-http", "any")));
        var mergedChain = PolicyProposalMerges.allOfferedMerges(chain);
        assertEquals(2, mergedChain.accepted());
        assertEquals(List.of(
            new PolicyProposalMerges.Step("a", "b", "source", PolicyMerge.MERGE_CANDIDATE, null, true, PolicyReorder.EQUIVALENT),
            new PolicyProposalMerges.Step("a", "c", "source", PolicyMerge.MERGE_CANDIDATE, null, true, PolicyReorder.EQUIVALENT)),
            mergedChain.steps());
        assertEquals(List.of("a"), order(mergedChain.snapshot()));
        Rule survivor = mergedChain.snapshot().sections().get(0).rules().get(0);
        assertEquals("a", survivor.id());
        assertEquals(1, survivor.number());
        assertEquals("COMMENT-a", survivor.comment());
        assertEquals(List.of("lo-a", "lo-b", "hi-a"), survivor.source().refs());

        // Listing the already-merged pair a second time is refused as NO_LONGER_A_CANDIDATE and does not merge c.
        var duplicate = PolicyProposalMerges.propose(chain, List.of(new Candidate("a", "b"), new Candidate("a", "b")));
        assertEquals(List.of(PolicyMerge.MERGE_CANDIDATE, PolicyProposalMerges.NO_LONGER_A_CANDIDATE), results(duplicate));
        assertEquals(List.of("a", "c"), order(duplicate.snapshot()));

        // 2. Chain (a, b, c) where (a, b) differs in source and (b, c) differs in destination:
        // both are MERGE_CANDIDATE in the stored snapshot, but after (a+b) the pair (ab, c) differs in two cells!
        var crossDimChain = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("a", 1, "Accept", "lo-a", "lo", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("b", 2, "Accept", "lo-b", "lo", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("c", 3, "Accept", "lo-b", "hi", "svc-http", "any")));
        assertEquals(2, PolicyMerge.analyze(crossDimChain).candidates(), "both adjacent pairs are candidates before step 1");
        var crossResult = PolicyProposalMerges.allOfferedMerges(crossDimChain);
        assertEquals(1, crossResult.accepted());
        assertEquals(List.of(
            new PolicyProposalMerges.Step("a", "b", "source", PolicyMerge.MERGE_CANDIDATE, null, true, PolicyReorder.EQUIVALENT),
            new PolicyProposalMerges.Step("a", "c", null, PolicyMerge.DIFFERS_IN_SEVERAL_CELLS, null, false, null)),
            crossResult.steps());
        assertEquals(1, crossResult.refused().get(PolicyMerge.DIFFERS_IN_SEVERAL_CELLS));
        assertEquals(List.of("a", "c"), order(crossResult.snapshot()));

        // 3. Chain (a, b, c) where a=lo-a, b=lo-b, c=lo (lo-a U lo-b): after (a+b)=lo, c is ALREADY_SHADOWED!
        var shadowedAfterFirst = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("a", 1, "Accept", "lo-a", "other", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("b", 2, "Accept", "lo-b", "other", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("c", 3, "Accept", "lo", "other", "svc-http", "any")));
        assertEquals(2, PolicyMerge.analyze(shadowedAfterFirst).candidates());
        var shadowedResult = PolicyProposalMerges.allOfferedMerges(shadowedAfterFirst);
        assertEquals(1, shadowedResult.accepted());
        assertEquals(List.of(PolicyMerge.MERGE_CANDIDATE, PolicyMerge.ALREADY_SHADOWED), results(shadowedResult));
        assertEquals(1, shadowedResult.refused().get(PolicyMerge.ALREADY_SHADOWED));
        assertEquals(List.of("a", "c"), order(shadowedResult.snapshot()));
    }

    @Test void pairOnlyAdjacentAfterAnAcceptedReorderMoveBecomesMergeable() {
        // Stored order: [broad(lo, lo, svc-https), m1(hi-a, other, svc-http), spec(lo-a, net, svc-https), m2(hi-b, other, svc-http)].
        // Before reorder, every adjacent pair differs in >= 2 dimensions, so there is NO merge candidate.
        // Reorder promotes spec (I4 overlap on lo-a x lo x svc-https) above broad -> [spec, broad, m1, m2], making m1 and m2 adjacent!
        var stored = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("broad", 1, "Accept", "lo", "lo", "svc-https", "fw-tango-04"),
            PolicyMergeTest.ruleWithCells("m1", 2, "Accept", "hi-a", "other", "svc-http", "fw-tango-04"),
            PolicyMergeTest.ruleWithCells("spec", 3, "Accept", "lo-a", "net", "svc-https", "fw-tango-04"),
            PolicyMergeTest.ruleWithCells("m2", 4, "Accept", "hi-b", "other", "svc-http", "fw-tango-04")));

        assertEquals(0, PolicyMerge.analyze(stored).candidates(), "m1 and m2 are separated by spec in the stored order");
        assertEquals(PolicyProposalMerges.NO_CANDIDATE, PolicyProposalMerges.allOfferedMerges(stored).emptyReason());

        var proposal = PolicyProposal.allOfferedMoves(stored);
        assertEquals(1, proposal.accepted());
        assertEquals(List.of("spec", "broad", "m1", "m2"), order(proposal.snapshot()));

        var merges = PolicyProposalMerges.allOfferedMerges(proposal);
        assertEquals(1, merges.accepted());
        assertEquals(List.of(
            new PolicyProposalMerges.Step("m1", "m2", "source", PolicyMerge.MERGE_CANDIDATE, null, true, PolicyReorder.EQUIVALENT)),
            merges.steps());
        assertEquals(List.of("spec", "broad", "m1"), order(merges.snapshot()));
        assertEquals(List.of(1, 2, 3), numbers(merges.snapshot()));
        assertEquals(List.of("hi-a", "hi-b"), merges.snapshot().sections().get(0).rules().get(2).source().refs());
        assertEquals(List.of("broad", "m1", "spec", "m2"), order(stored), "stored snapshot untouched");
    }

    @Test void everyRefusalReasonLeavesTheSnapshotAsItWasAndDoesNotStopLaterCandidates() {
        Map<String, PolicySnapshot> byRefusal = new LinkedHashMap<>();
        byRefusal.put(PolicyMerge.ANCHOR_BLOCKED, snapshot(cpSection("s1", null,
            PolicyUnionCoverTest.timed(cp("r1", 1, "Accept", "lo", "any")),
            cp("r2", 2, "Accept", "hi", "any"))));
        byRefusal.put(PolicyMerge.DIFFERENT_EFFECT, snapshot(cpSection("s1", null,
            cp("r1", 1, "Accept", "lo", "any"),
            cp("r2", 2, "Drop", "hi", "any"))));
        byRefusal.put(PolicyMerge.DIFFERENT_INSTALL_ON, snapshot(cpSection("s1", null,
            cp("r1", 1, "Accept", "lo", "fw-tango-04"),
            cp("r2", 2, "Accept", "hi", "fw-juliet-06"))));
        byRefusal.put(PolicyMerge.DIFFERS_IN_SEVERAL_CELLS, snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("r1", 1, "Accept", "lo", "lo", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("r2", 2, "Accept", "hi", "hi", "svc-http", "any"))));
        byRefusal.put(PolicyMerge.ALREADY_SHADOWED, snapshot(cpSection("s1", null,
            cp("r1", 1, "Accept", "net", "any"),
            cp("r2", 2, "Accept", "lo", "any"))));

        for (var entry : byRefusal.entrySet()) {
            String expectedReason = entry.getKey();
            PolicySnapshot stored = entry.getValue();
            var res = PolicyProposalMerges.propose(stored, List.of(new Candidate("r1", "r2")));
            assertEquals(0, res.accepted(), expectedReason);
            assertFalse(res.budgetStopped(), expectedReason);
            assertNull(res.emptyReason(), expectedReason);
            assertSame(stored, res.snapshot(), expectedReason);
            assertEquals(List.of(new PolicyProposalMerges.Step("r1", "r2", null, expectedReason, null, false, null)), res.steps(), expectedReason);
            assertEquals(1, res.refused().get(expectedReason), expectedReason);
        }

        // Different log setting or UNKNOWN log setting is also DIFFERENT_EFFECT; section/layer boundaries are never merged.
        var differentLog = snapshot(cpSection("s1", null,
            cp("r1", 1, "Accept", "lo", "any"),
            PolicyReorderTest.withLog(cp("r2", 2, "Accept", "hi", "any"), "None")));
        assertEquals(List.of(PolicyMerge.DIFFERENT_EFFECT),
            results(PolicyProposalMerges.propose(differentLog, List.of(new Candidate("r1", "r2")))));

        var splitSections = snapshot(
            cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")),
            cpSection("s2", null, cp("r2", 2, "Accept", "hi", "any")));
        var acrossSection = PolicyProposalMerges.propose(splitSections, List.of(new Candidate("r1", "r2")));
        assertEquals(List.of(PolicyProposalMerges.NO_LONGER_A_CANDIDATE), results(acrossSection));
        assertSame(splitSections, acrossSection.snapshot());

        var splitLayers = snapshot(
            cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "hi", "any"), "LAYER-BRAVO")));
        var acrossLayer = PolicyProposalMerges.propose(splitLayers, List.of(new Candidate("r1", "b1")));
        assertEquals(List.of(PolicyProposalMerges.NO_LONGER_A_CANDIDATE), results(acrossLayer));
        assertSame(splitLayers, acrossLayer.snapshot());

        // A refused step does not stop a subsequent valid merge.
        var mixed = snapshot(cpSection("s1", null,
            cp("r1", 1, "Accept", "lo", "any"),
            cp("r2", 2, "Drop", "other", "any"),
            cp("r3", 3, "Accept", "lo-a", "any"),
            cp("r4", 4, "Accept", "hi-a", "any")));
        var mixedRes = PolicyProposalMerges.propose(mixed, List.of(new Candidate("r1", "r2"), new Candidate("r3", "r4")));
        assertEquals(List.of(PolicyMerge.DIFFERENT_EFFECT, PolicyMerge.MERGE_CANDIDATE), results(mixedRes));
        assertEquals(1, mixedRes.accepted());
        assertEquals(List.of("r1", "r2", "r3"), order(mixedRes.snapshot()));
    }

    @Test void negatedCellIsNeverMergedEvenWhenBothRulesCarryNegation() {
        Map<String, List<String>> extras = Map.of("install-on", List.of("any"), "time", List.of("any"), "layer-name", List.of("LAYER-ALPHA"));
        Rule negA = new Rule("r1", "uuid-r1", 1, "OBJ-RULE-1", true, new Cell(List.of("lo"), true), cell("any"), cell("any"),
            new Cell(List.of(), false), "Accept", "Log", "", extras);
        Rule negB = new Rule("r2", "uuid-r2", 2, "OBJ-RULE-2", true, new Cell(List.of("hi"), true), cell("any"), cell("any"),
            new Cell(List.of(), false), "Accept", "Log", "", extras);
        Rule plainB = cp("r2", 2, "Accept", "hi", "any");

        for (var stored : List.of(
                snapshot(cpSection("s1", null, negA, plainB)),
                snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), negB)),
                snapshot(cpSection("s1", null, negA, negB)))) {
            var offered = PolicyProposalMerges.allOfferedMerges(stored);
            assertEquals(PolicyProposalMerges.NO_CANDIDATE, offered.emptyReason());
            assertSame(stored, offered.snapshot());

            var explicit = PolicyProposalMerges.propose(stored, List.of(new Candidate("r1", "r2")));
            assertEquals(List.of(PolicyMerge.ANCHOR_BLOCKED), results(explicit));
            assertEquals(1, explicit.refused().get(PolicyMerge.ANCHOR_BLOCKED));
            assertSame(stored, explicit.snapshot());
        }
    }

    @Test void anotherVendorAndIncompleteSnapshotGiveNoStepAndUnchangedSnapshot() {
        for (String vendor : List.of("ASA", "FGT")) {
            var unsup = PolicyUnionCoverTest.unsupportedVendorSnapshot(vendor);
            for (var result : List.of(
                    PolicyProposalMerges.allOfferedMerges(unsup),
                    PolicyProposalMerges.propose(unsup, List.of(new Candidate("r1", "r2"))),
                    PolicyProposalMerges.allOfferedMerges(PolicyProposal.allOfferedMoves(unsup)))) {
                assertEquals(PolicyProposalMerges.UNSUPPORTED_VENDOR, result.emptyReason(), vendor);
                assertTrue(result.steps().isEmpty());
                assertEquals(0, result.accepted());
                assertFalse(result.budgetStopped());
                assertSame(unsup, result.snapshot());
            }
        }

        var complete = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")));
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(),
            List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        for (var result : List.of(
                PolicyProposalMerges.allOfferedMerges(incomplete),
                PolicyProposalMerges.propose(incomplete, List.of(new Candidate("r1", "r2"))),
                PolicyProposalMerges.allOfferedMerges(PolicyProposal.allOfferedMoves(incomplete)))) {
            assertEquals(PolicyProposalMerges.INCOMPLETE, result.emptyReason());
            assertTrue(result.steps().isEmpty());
            assertEquals(0, result.accepted());
            assertFalse(result.budgetStopped());
            assertSame(incomplete, result.snapshot());
        }
    }

    @Test void budgetSweptOverEveryWorkLimitIsAPrefixOfTheFullRunAndNeverAcceptsAnUnprovenMerge() {
        var stored = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("a", 1, "Accept", "lo-a", "other", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("b", 2, "Accept", "lo-b", "other", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("c", 3, "Accept", "hi-a", "other", "svc-http", "any")));
        var proposal = PolicyProposal.allOfferedMoves(stored);
        var full = PolicyProposalMerges.allOfferedMerges(proposal);
        assertEquals(2, full.accepted());

        boolean stoppedAtOnce = false, midRun = false, completed = false;
        for (int work = 0; work < 600; work++) {
            var limited = PolicyProposalMerges.allOfferedMerges(proposal, work, work);
            assertEquals(full.steps().size(), limited.steps().size(), "no silent skip at work " + work);
            int firstUnproven = limited.steps().size();
            for (int i = 0; i < limited.steps().size(); i++) {
                if (PolicyMerge.NOT_PROVEN.equals(limited.steps().get(i).result())) {
                    firstUnproven = i;
                    break;
                }
            }
            for (int i = 0; i < firstUnproven; i++) {
                assertEquals(full.steps().get(i), limited.steps().get(i), "prefix mismatch at work " + work);
            }
            for (int i = firstUnproven; i < limited.steps().size(); i++) {
                var step = limited.steps().get(i);
                assertFalse(step.accepted(), "work " + work);
                assertEquals(PolicyMerge.NOT_PROVEN, step.result(), "work " + work);
                assertEquals(PolicyProposalMerges.BUDGET, step.reason(), "work " + work);
                assertEquals(PolicyMerge.NOT_PROVEN, step.equivalence(), "work " + work);
                assertNull(step.differingCell(), "work " + work);
            }
            if (limited.budgetStopped()) {
                assertTrue(firstUnproven < limited.steps().size());
                assertTrue(limited.refused().get(PolicyMerge.NOT_PROVEN) >= 1);
                var combined = PolicyProposalMerges.combine(proposal, limited);
                assertEquals(PolicyReorder.NOT_PROVEN, combined.packageVerdict());
                assertNull(combined.label());
                assertTrue(combined.budgetStopped());
                if (limited.accepted() == 0) {
                    stoppedAtOnce = true;
                    assertSame(stored, limited.snapshot());
                } else if (limited.accepted() == 1) {
                    midRun = true;
                    assertEquals(List.of("a", "c"), order(limited.snapshot()));
                }
            } else {
                completed = true;
                assertEquals(full.steps(), limited.steps());
                assertEquals(order(full.snapshot()), order(limited.snapshot()));
            }
        }
        assertTrue(stoppedAtOnce, "budget too small for the first merge");
        assertTrue(midRun, "budget proves the first merge and runs out on the second");
        assertTrue(completed, "budget proves the whole chain");

        var zeroWindow = PolicyProposalMerges.allOfferedMerges(proposal, PolicyProposalMerges.TOTAL_WORK, 0);
        assertTrue(zeroWindow.budgetStopped());
        assertEquals(0, zeroWindow.accepted());
    }

    @Test void storedSnapshotProposalSnapshotAndG6IdentitiesStayUntouched() {
        var stored = snapshot(
            cpSection("s1", null,
                PolicyMergeTest.ruleWithCells("broad", 1, "Accept", "lo", "lo", "svc-https", "fw-tango-04"),
                PolicyMergeTest.ruleWithCells("m1", 2, "Accept", "hi-a", "other", "svc-http", "fw-tango-04"),
                PolicyMergeTest.ruleWithCells("spec", 3, "Accept", "lo-a", "net", "svc-https", "fw-tango-04"),
                PolicyMergeTest.ruleWithCells("m2", 4, "Accept", "hi-b", "other", "svc-http", "fw-tango-04")),
            cpSection("s2", null,
                cp("keep1", 5, "Accept", "other", "fw-tango-04"),
                cp("keep2", 6, "Drop", "other", "fw-tango-04")));
        var storedTwin = snapshot(
            cpSection("s1", null,
                PolicyMergeTest.ruleWithCells("broad", 1, "Accept", "lo", "lo", "svc-https", "fw-tango-04"),
                PolicyMergeTest.ruleWithCells("m1", 2, "Accept", "hi-a", "other", "svc-http", "fw-tango-04"),
                PolicyMergeTest.ruleWithCells("spec", 3, "Accept", "lo-a", "net", "svc-https", "fw-tango-04"),
                PolicyMergeTest.ruleWithCells("m2", 4, "Accept", "hi-b", "other", "svc-http", "fw-tango-04")),
            cpSection("s2", null,
                cp("keep1", 5, "Accept", "other", "fw-tango-04"),
                cp("keep2", 6, "Drop", "other", "fw-tango-04")));
        assertEquals(storedTwin, stored);

        var proposal = PolicyProposal.allOfferedMoves(stored);
        var postMoveRules = List.copyOf(proposal.snapshot().sections().get(0).rules());
        var merges = PolicyProposalMerges.allOfferedMerges(proposal);
        assertEquals(1, proposal.accepted());
        assertEquals(1, merges.accepted());

        // Stored snapshot and intermediate post-move snapshot are never mutated.
        assertEquals(storedTwin, stored);
        assertEquals(postMoveRules, proposal.snapshot().sections().get(0).rules());
        assertSame(stored.metadata(), merges.snapshot().metadata());
        assertSame(stored.objects(), merges.snapshot().objects());
        assertSame(stored.failures(), merges.snapshot().failures());
        assertSame(stored.sections().get(1), merges.snapshot().sections().get(1), "untouched section kept by identity");
        assertSame(postMoveRules.get(0), merges.snapshot().sections().get(0).rules().get(0));
        assertSame(postMoveRules.get(1), merges.snapshot().sections().get(0).rules().get(1));
        assertEquals(merges.steps(), PolicyProposalMerges.allOfferedMerges(proposal).steps(), "deterministic on repeat call");

        // Disabled rule between two mergeable rules stays in place by identity; enabled rule between throws on G6.
        Rule off = cp("off", 2, "Drop", "any", "any");
        off = new Rule(off.id(), off.uuid(), off.number(), off.name(), false, off.source(), off.destination(),
            off.service(), off.application(), off.action(), off.log(), off.comment(), off.extras());
        var withDisabled = snapshot(cpSection("s1", null,
            cp("a", 1, "Accept", "lo", "any"), off, cp("b", 3, "Accept", "hi", "any")));
        var mergedAcrossDisabled = PolicyProposalMerges.allOfferedMerges(withDisabled);
        assertEquals(1, mergedAcrossDisabled.accepted());
        assertEquals(List.of("a", "off"), order(mergedAcrossDisabled.snapshot()));
        assertSame(off, mergedAcrossDisabled.snapshot().sections().get(0).rules().get(1));
        assertEquals(List.of(1, 2), numbers(mergedAcrossDisabled.snapshot()));

        assertThrows(IllegalStateException.class, () -> PolicyProposalMerges.merged(stored, "broad", "spec", 0));
        assertThrows(IllegalStateException.class, () -> PolicyProposalMerges.merged(stored, "m2", "m1", 0));
        assertThrows(IllegalStateException.class, () -> PolicyProposalMerges.merged(stored, "broad", "keep1", 0));
        assertThrows(IllegalStateException.class, () -> PolicyProposalMerges.merged(stored, "missing", "m1", 0));
    }

    @Test void combinedVerdictAndLabelCoverEveryReorderAndMergeCombination() {
        // 1. NO_MOVES + no merge -> NO_MOVES, null label.
        var noneSnap = snapshot(cpSection("s1", null,
            PolicyMergeTest.ruleWithCells("r1", 1, "Accept", "lo", "lo", "svc-http", "any"),
            PolicyMergeTest.ruleWithCells("r2", 2, "Accept", "hi", "hi", "svc-http", "any")));
        var noneProposal = PolicyProposal.allOfferedMoves(noneSnap);
        var noneMerges = PolicyProposalMerges.allOfferedMerges(noneProposal);
        var cNone = PolicyProposalMerges.combine(noneProposal, noneMerges);
        assertEquals(PolicyProposal.NO_MOVES, cNone.packageVerdict());
        assertNull(cNone.label());
        assertFalse(cNone.inspectionChanges());
        assertFalse(cNone.budgetStopped());
        assertSame(noneSnap, cNone.snapshot());

        // 2. NO_MOVES + accepted merge -> EQUIVALENT, "no behaviour change".
        var mergeOnlySnap = snapshot(cpSection("s1", null,
            cp("m1", 1, "Accept", "lo", "any"),
            cp("m2", 2, "Accept", "hi", "any")));
        var mergeOnlyProposal = PolicyProposal.allOfferedMoves(mergeOnlySnap);
        assertEquals(PolicyProposal.NO_MOVES, mergeOnlyProposal.packageVerdict());
        var mergeOnlyMerges = PolicyProposalMerges.allOfferedMerges(mergeOnlyProposal);
        assertEquals(1, mergeOnlyMerges.accepted());
        var cMergeOnly = PolicyProposalMerges.combine(mergeOnlyProposal, mergeOnlyMerges);
        assertEquals(PolicyReorder.EQUIVALENT, cMergeOnly.packageVerdict());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, cMergeOnly.label());
        assertEquals(0, cMergeOnly.acceptedMoves());
        assertEquals(1, cMergeOnly.acceptedMerges());
        assertFalse(cMergeOnly.inspectionChanges());
        assertSame(mergeOnlyMerges.snapshot(), cMergeOnly.snapshot());

        // 3. SAFE reorder (EQUIVALENT, "no behaviour change") + accepted merge -> EQUIVALENT, "no behaviour change".
        var safeAndMergeSnap = snapshot(cpSection("s1", null,
            cp("b", 1, "Accept", "lo", "fw-tango-04"),
            cp("r", 2, "Accept", "net", "fw-tango-04"),
            cp("m1", 3, "Accept", "lo", "fw-juliet-06"),
            cp("m2", 4, "Accept", "hi", "fw-juliet-06")));
        var safeProposal = PolicyProposal.allOfferedMoves(safeAndMergeSnap);
        var safeMerges = PolicyProposalMerges.allOfferedMerges(safeProposal);
        var cSafe = PolicyProposalMerges.combine(safeProposal, safeMerges);
        assertEquals(PolicyReorder.EQUIVALENT, cSafe.packageVerdict());
        assertEquals(PolicyProposal.LABEL_NO_CHANGE, cSafe.label());
        assertEquals(1, cSafe.acceptedMoves());
        assertEquals(1, cSafe.acceptedMerges());
        assertFalse(cSafe.inspectionChanges());

        // 4. SAFE_INSPECTION_CHANGES that is EQUIVALENT (crossed differing-log rule already covered by B) + merge:
        // verdict is EQUIVALENT, label stays "verdict-safe, inspection changes".
        var eqInspectionSnap = snapshot(cpSection("s1", null,
            cp("b", 1, "Accept", "lo", "fw-tango-04"),
            PolicyReorderTest.withLog(cp("c", 2, "Accept", "lo-a", "fw-tango-04"), "None"),
            cp("r", 3, "Accept", "net", "fw-tango-04"),
            cp("m1", 4, "Accept", "lo", "fw-juliet-06"),
            cp("m2", 5, "Accept", "hi", "fw-juliet-06")));
        var eqInspectionProposal = PolicyProposal.allOfferedMoves(eqInspectionSnap);
        assertEquals(PolicyReorder.EQUIVALENT, eqInspectionProposal.packageVerdict());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, eqInspectionProposal.label());
        var eqInspectionMerges = PolicyProposalMerges.allOfferedMerges(eqInspectionProposal);
        assertEquals(1, eqInspectionMerges.accepted());
        var cEqInspection = PolicyProposalMerges.combine(eqInspectionProposal, eqInspectionMerges);
        assertEquals(PolicyReorder.EQUIVALENT, cEqInspection.packageVerdict());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, cEqInspection.label());
        assertTrue(cEqInspection.inspectionChanges());

        // 5. CHANGES reorder (promoted rule takes packets from a differing-log broad rule) + accepted merge:
        // verdict stays CHANGES (never EQUIVALENT!), label stays "verdict-safe, inspection changes".
        var changesSnap = snapshot(cpSection("s1", null,
            PolicyReorderTest.withLog(cp("b", 1, "Accept", "lo", "fw-tango-04"), "None"),
            cp("r", 2, "Accept", "net", "fw-tango-04"),
            cp("m1", 3, "Accept", "lo", "fw-juliet-06"),
            cp("m2", 4, "Accept", "hi", "fw-juliet-06")));
        var changesProposal = PolicyProposal.allOfferedMoves(changesSnap);
        assertEquals(PolicyReorder.CHANGES, changesProposal.packageVerdict());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, changesProposal.label());
        var changesMerges = PolicyProposalMerges.allOfferedMerges(changesProposal);
        assertEquals(1, changesMerges.accepted());
        var cChanges = PolicyProposalMerges.combine(changesProposal, changesMerges);
        assertEquals(PolicyReorder.CHANGES, cChanges.packageVerdict());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, cChanges.label());
        assertTrue(cChanges.inspectionChanges());

        // 6. Reorder stopped on budget, merges complete -> NOT_PROVEN, null label.
        var reorderStopped = PolicyProposal.allOfferedMoves(safeAndMergeSnap, 0, 0);
        assertTrue(reorderStopped.budgetStopped());
        var mergesAfterStopped = PolicyProposalMerges.allOfferedMerges(reorderStopped);
        assertFalse(mergesAfterStopped.budgetStopped());
        var cReorderStopped = PolicyProposalMerges.combine(reorderStopped, mergesAfterStopped);
        assertEquals(PolicyReorder.NOT_PROVEN, cReorderStopped.packageVerdict());
        assertNull(cReorderStopped.label());
        assertTrue(cReorderStopped.budgetStopped());

        // 7. Reorder complete, merges stopped on budget -> NOT_PROVEN, null label.
        var mergesStopped = PolicyProposalMerges.allOfferedMerges(safeProposal, 0, 0);
        assertTrue(mergesStopped.budgetStopped());
        var cMergesStopped = PolicyProposalMerges.combine(safeProposal, mergesStopped);
        assertEquals(PolicyReorder.NOT_PROVEN, cMergesStopped.packageVerdict());
        assertNull(cMergesStopped.label());
        assertTrue(cMergesStopped.budgetStopped());
    }

    @Test void paloAltoPostMoveAdjacencyMergesSourcePortChainsCombinedLabelsAndPhaseBoundaries() {
        String za = PolicyUnionCoverTest.ZA, zb = PolicyUnionCoverTest.ZB, zc = PolicyUnionCoverTest.ZC;
        Map<String, List<String>> profAlpha = Map.of("profile-setting/group", List.of("PROF-ALPHA"), "log-setting", List.of("LOG-FWD-ALPHA"));
        Map<String, List<String>> profBravo = Map.of("profile-setting/group", List.of("PROF-BRAVO"), "log-setting", List.of("LOG-FWD-ALPHA"));

        // DG-TANGO-01 Pre: [broad(lo, lo, svc-p2, PROF-ALPHA), m1(other, h0, svc-p1-s-lo, PROF-ALPHA),
        //                   spec(h0, net, svc-p2, PROF-BRAVO), m2(other, h0, svc-p1-s-hi, PROF-ALPHA)].
        // Shared Post: [postOnly(other, h0, svc-p2, PROF-ALPHA)] -- never merged across DG/phase!
        // Reorder promotes spec above broad (SAFE_INSPECTION_CHANGES, CHANGES), making m1 and m2 adjacent;
        // m1 + m2 then merge in service (svc-p1-s-lo U svc-p1-s-hi == svc-p1)!
        var stored = PolicyUnionCoverTest.panSnapshot(
            PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
                PolicyUnionCoverTest.panRule("broad", 1, "allow", List.of(za), List.of(zb), "lo", "lo", "svc-p2", profAlpha),
                PolicyUnionCoverTest.panRule("m1", 2, "allow", List.of(zc), List.of(zb), "other", "h0", "svc-p1-s-lo", profAlpha),
                PolicyUnionCoverTest.panRule("spec", 3, "allow", List.of(za), List.of(zb), "h0", "net", "svc-p2", profBravo),
                PolicyUnionCoverTest.panRule("m2", 4, "allow", List.of(zc), List.of(zb), "other", "h0", "svc-p1-s-hi", profAlpha)),
            PolicyUnionCoverTest.panSection("Shared", "post-rulebase",
                PolicyUnionCoverTest.panRule("postOnly", 1, "allow", List.of(zc), List.of(zb), "other", "h0", "svc-p2", profAlpha)));

        assertEquals(0, PolicyMerge.analyze(stored).candidates());
        var proposal = PolicyProposal.allOfferedMoves(stored);
        assertEquals(1, proposal.accepted());
        assertEquals(PolicyReorder.CHANGES, proposal.packageVerdict());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, proposal.label());

        var merges = PolicyProposalMerges.allOfferedMerges(proposal);
        assertEquals(1, merges.accepted());
        assertEquals(List.of(new PolicyProposalMerges.Step("m1", "m2", "service", PolicyMerge.MERGE_CANDIDATE, null, true, PolicyReorder.EQUIVALENT)), merges.steps());
        assertEquals(List.of("spec", "broad", "m1"), merges.snapshot().sections().get(0).rules().stream().map(Rule::id).toList());
        assertEquals(List.of("postOnly"), merges.snapshot().sections().get(1).rules().stream().map(Rule::id).toList());
        assertEquals(List.of("svc-p1-s-lo", "svc-p1-s-hi"), merges.snapshot().sections().get(0).rules().get(2).service().refs());

        var combined = PolicyProposalMerges.combine(proposal, merges);
        assertEquals(PolicyReorder.CHANGES, combined.packageVerdict());
        assertEquals(PolicyProposal.LABEL_INSPECTION_CHANGES, combined.label());
        assertTrue(combined.inspectionChanges());
        assertEquals(1, combined.acceptedMoves());
        assertEquals(1, combined.acceptedMerges());

        // Budget sweep over PAN chain of three rules (h0, h1, hi -> net) verifies strict prefix property and charge() parity.
        var panChain = PolicyUnionCoverTest.panSnapshot(PolicyUnionCoverTest.panSection("DG-TANGO-01", "pre-rulebase",
            PolicyUnionCoverTest.panRule("a", 1, "allow", List.of(za), List.of(zb), "h0", "other", "svc-p1", profAlpha),
            PolicyUnionCoverTest.panRule("b", 2, "allow", List.of(za), List.of(zb), "h1", "other", "svc-p1", profAlpha),
            PolicyUnionCoverTest.panRule("c", 3, "allow", List.of(za), List.of(zb), "hi", "other", "svc-p1", profAlpha)));
        var chainProp = PolicyProposal.allOfferedMoves(panChain);
        var fullChain = PolicyProposalMerges.allOfferedMerges(chainProp);
        assertEquals(2, fullChain.accepted());
        boolean sawMid = false;
        for (int work = 0; work < 500; work++) {
            var lim = PolicyProposalMerges.allOfferedMerges(chainProp, work, work);
            assertEquals(2, lim.steps().size());
            if (lim.budgetStopped() && lim.accepted() == 1) sawMid = true;
            if (!lim.budgetStopped()) assertEquals(fullChain.steps(), lim.steps());
        }
        assertTrue(sawMid);
    }

    @Test void paloAltoProposalMergesDifferentialOracle() {
        Random random = new Random(2026112606L);
        Set<PolicyUnionCoverTest.PanPacket> allUniverse = new HashSet<>(PolicyUnionCoverTest.panRulePackets(
            PolicyUnionCoverTest.panRule("u", 1, "allow", List.of("any"), List.of("any"), "any", "any", "any", Map.of()),
            PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
        allUniverse.addAll(PolicyUnionCoverTest.panRulePackets(
            PolicyUnionCoverTest.panRule("u2", 1, "allow", List.of("any"), List.of("any"), "other", "other", "any", Map.of()),
            PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));

        int acceptedMerges = 0, budgetStops = 0;
        for (int trial = 0; trial < 220; trial++) {
            var realized = PolicyUnionCoverTest.panRealize(
                PolicyUnionCoverTest.randomPanRulebase(random, trial % 2 == 0, trial % 3 == 0));
            var items = realized.items();
            var stored = realized.snapshot();
            var proposal = PolicyProposal.allOfferedMoves(stored);
            var merges = PolicyProposalMerges.allOfferedMerges(proposal);
            var combined = PolicyProposalMerges.combine(proposal, merges);
            acceptedMerges += merges.accepted();

            Map<String, PolicyUnionCoverTest.PanSynthetic> byId = new HashMap<>();
            Map<String, Set<PolicyUnionCoverTest.PanPacket>> origPts = new HashMap<>();
            for (var item : items) {
                byId.put(item.rule().id(), item);
                origPts.put(item.rule().id(), PolicyUnionCoverTest.panRulePackets(
                    item.rule(), PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
            }
            List<PolicyUnionCoverTest.PanSynthetic> finalItems = new ArrayList<>();
            Map<String, Set<PolicyUnionCoverTest.PanPacket>> finalPts = new HashMap<>();
            for (Section s : combined.snapshot().sections()) {
                for (Rule r : s.rules()) {
                    var orig = byId.get(r.id());
                    finalItems.add(new PolicyUnionCoverTest.PanSynthetic(r, orig.kind(), orig.sectionIndex()));
                    finalPts.put(r.id(), PolicyUnionCoverTest.panRulePackets(
                        r, PolicyUnionCoverTest.PAN_WORLD_EXT, PolicyUnionCoverTest.PAN_WORLD_EXT));
                }
            }
            boolean fullEq = PolicyReorder.EQUIVALENT.equals(combined.packageVerdict()) && !combined.inspectionChanges();
            for (boolean anchorsMatch : new boolean[]{false, true}) {
                for (var pkt : allUniverse) {
                    Rule beforeDec = PolicyMergeTest.panFirstMatch(items, pkt, anchorsMatch, origPts);
                    Rule afterDec = PolicyMergeTest.panFirstMatch(finalItems, pkt, anchorsMatch, finalPts);
                    if (beforeDec == null && afterDec == null) continue;
                    assertNotNull(beforeDec); assertNotNull(afterDec);
                    if (beforeDec.id().equals(afterDec.id())) {
                        assertEquals(beforeDec.action(), afterDec.action());
                        assertEquals(beforeDec.log(), afterDec.log());
                        assertEquals(beforeDec.extras(), afterDec.extras());
                        continue;
                    }
                    assertTrue(PolicyEffectSignature.sameVerdict(beforeDec, afterDec), "combined PAN verdict changed in trial " + trial);
                    if (fullEq) {
                        assertTrue(PolicyEffectSignature.sameFull("PAN", beforeDec, afterDec), "combined PAN full effect changed in trial " + trial);
                    }
                }
            }

            int lim = 10 + random.nextInt(120);
            var limMerges = PolicyProposalMerges.allOfferedMerges(proposal, lim, Math.max(1, lim / 2));
            if (limMerges.budgetStopped()) budgetStops++;
            else assertEquals(merges.steps(), limMerges.steps());
        }
        assertTrue(acceptedMerges >= 15, "PAN proposal merges exercised: " + acceptedMerges);
        assertTrue(budgetStops >= 10, "PAN proposal merge budget stops exercised: " + budgetStops);
    }
}
