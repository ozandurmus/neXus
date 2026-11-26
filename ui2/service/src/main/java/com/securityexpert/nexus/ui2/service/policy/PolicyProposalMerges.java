package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Box;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Dim;
import java.util.*;

/**
 * Phase P2 step c-2d of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN) sections 4 (row I5), 6.1 (point 4), 6.3 and 6.4,
 * and PO_DECISION_RECORD_2026_11_24_POLICY_INSIGHTS.md (Q2, Q3, Q7, Q9), Check Point only, engine only: I5 merges
 * inside the reorder-only proposal. Pure: reads a {@link PolicyProposal.Result} (or a stored {@link PolicySnapshot}
 * when no move was accepted), builds in-memory snapshots, mutates nothing, stores nothing, contacts nothing and
 * generates no command text (Q7).
 *
 * <p>The fixed readings, each from the frozen design or its decision record:
 * <ul>
 * <li><b>Post-move adjacency (design 4 row I5, 6.3, Q9).</b> Runs {@link PolicyMerge#analyze} on the proposal's final
 * in-memory snapshot ({@link PolicyProposal.Result#snapshot()}), so two rules become a merge candidate when they are
 * adjacent among the enabled rules of one proven evaluation scope and one section after the proven reorder moves. Never
 * merges across a layer or section (Q9), never merges an anchor (Q3, negated cells included), and never accepts a pair
 * whose result is not {@link PolicyMerge#MERGE_CANDIDATE}.</li>
 * <li><b>One step at a time, G6 ({@link #merged}).</b> Each accepted merge replaces the surviving rule {@code a} with
 * {@link PolicyMerge#mergedRule} (keeping {@code a}'s id, uuid, number, name and comment; replacing only the differing
 * cell with the order-preserving, de-duplicated union of {@code a}'s and {@code b}'s references, {@code negated=false})
 * and removes {@code b} from that section's rule list. Metadata, objects, failures, other sections and every unmerged
 * rule of the section stay the same instances. Neither names nor comments are concatenated into the snapshot; both rule
 * ids are recorded on the {@link Step}. Because a merge changes adjacency, {@link PolicyMerge#analyze} re-runs after
 * each accepted merge so a chain {@code (a, b, c)} merges as {@code (a+b)} then {@code (ab+c)} only when the second
 * pair is still {@link PolicyMerge#MERGE_CANDIDATE} in the updated snapshot.</li>
 * <li><b>Equivalence and shared budget (design 6.4, Q8).</b> Equivalence is proven against the order before the step by
 * the union property {@link PolicyMerge} already verifies ({@code box(merged) == box(a) U box(b)}, same full effect
 * signature, same install-on placement both ways); no new equivalence rule is added. One shared {@link Budget} of
 * {@code workLimit} units ({@link PolicyMerge#TOTAL_WORK} default, {@link PolicyMerge#RULE_WORK} per rule) spans every
 * analysis pass of the run. Any step that cannot be proven is {@link PolicyMerge#NOT_PROVEN} with reason
 * {@link #BUDGET}, never accepted, and stops the run so every remaining step is recorded {@link PolicyMerge#NOT_PROVEN}
 * the same way; a limited run is therefore a prefix of the full run and never accepts a merge the full run refuses.</li>
 * <li><b>Combined package verdict ({@link #combine}).</b> {@link PolicyReorder#EQUIVALENT} only when at least one move
 * or merge was accepted, the reorder part is {@link PolicyReorder#EQUIVALENT} or {@link PolicyProposal#NO_MOVES}, and
 * every accepted merge is proven; {@link PolicyReorder#NOT_PROVEN} when either part stopped on budget; the label stays
 * the reorder part's label when moves were accepted (or {@link PolicyProposal#LABEL_NO_CHANGE} for an equivalent
 * merge-only package, null when stopped or nothing accepted), because a merge never changes a profile or log setting.</li>
 * </ul>
 * Counts and closed words only: no range, address, name or comment is read into the result.
 */
final class PolicyProposalMerges {
    private PolicyProposalMerges() {}
    static final int RULE_WORK = PolicyMerge.RULE_WORK, TOTAL_WORK = PolicyMerge.TOTAL_WORK;
    static final String NO_LONGER_A_CANDIDATE = PolicyProposal.NO_LONGER_A_CANDIDATE;
    static final String UNSUPPORTED_VENDOR = PolicyProposal.UNSUPPORTED_VENDOR,
        INCOMPLETE = PolicyProposal.INCOMPLETE,
        NO_CANDIDATE = "no merge candidate";
    static final String BUDGET = PolicyMerge.BUDGET;
    static final String EQUIVALENT = PolicyReorder.EQUIVALENT;
    /** Every refusal reason a step can carry, in display order; every key is present in {@link Result#refused()}. */
    static final List<String> REFUSALS = List.of(
        PolicyMerge.ALREADY_SHADOWED,
        PolicyMerge.DIFFERS_IN_SEVERAL_CELLS,
        PolicyMerge.DIFFERENT_EFFECT,
        PolicyMerge.DIFFERENT_INSTALL_ON,
        PolicyMerge.ANCHOR_BLOCKED,
        PolicyMerge.NOT_PROVEN,
        NO_LONGER_A_CANDIDATE);

    /** A requested pair {@code (surviving, merged)} to try in order; if {@code surviving} was merged into an earlier rule, its survivor stands in. */
    record Candidate(String surviving, String merged) {}

    /**
     * One merge attempt. {@code differingCell}: closed word from {@link PolicyMerge#CELLS} when {@code accepted}, else
     * null. {@code result}: {@link PolicyMerge#MERGE_CANDIDATE} when {@code accepted}, else the refusal word from
     * {@link PolicyMerge} or {@link #NO_LONGER_A_CANDIDATE}. {@code reason}: {@link #BUDGET} for
     * {@link PolicyMerge#NOT_PROVEN}, else null. {@code equivalence}: {@link #EQUIVALENT} when {@code accepted},
     * {@link PolicyMerge#NOT_PROVEN} when stopped by budget, else null.
     */
    record Step(String surviving, String merged, String differingCell, String result, String reason,
            boolean accepted, String equivalence) {}

    /**
     * The merge pass over a proposal snapshot. {@code accepted} and {@code refused} (every {@link #REFUSALS} key
     * present) count {@code steps}. {@code budgetStopped}: a {@link PolicyMerge#NOT_PROVEN} step stopped the run.
     * {@code emptyReason}: why {@code steps} is empty, else null. {@code snapshot}: the merged-in-memory snapshot, the
     * input instance when nothing was accepted.
     */
    record Result(List<Step> steps, int accepted, Map<String, Integer> refused, boolean budgetStopped,
            String emptyReason, PolicySnapshot snapshot) {}

    /**
     * Combined verdict and label over a reorder proposal and its merge pass (design 6.4, Q2): counts and closed words
     * only, plus the final in-memory snapshot.
     */
    record Combined(String packageVerdict, String label, int acceptedMoves, int acceptedMerges,
            boolean inspectionChanges, boolean budgetStopped, PolicySnapshot snapshot) {}

    private record ChargedEntry(Rule rule, Section section, int sectionOrdinal, Match match, Box box, boolean anchor) {
        boolean unread() { return !anchor && match == null; }
    }

    static Result allOfferedMerges(PolicyProposal.Result proposal) {
        return allOfferedMerges(proposal, TOTAL_WORK, RULE_WORK);
    }

    static Result allOfferedMerges(PolicyProposal.Result proposal, int workLimit, int ruleLimit) {
        return propose(proposal, null, workLimit, ruleLimit);
    }

    static Result allOfferedMerges(PolicySnapshot snapshot) {
        return allOfferedMerges(snapshot, TOTAL_WORK, RULE_WORK);
    }

    static Result allOfferedMerges(PolicySnapshot snapshot, int workLimit, int ruleLimit) {
        return propose(snapshot, null, workLimit, ruleLimit);
    }

    static Result propose(PolicyProposal.Result proposal, List<Candidate> candidates) {
        return propose(proposal, candidates, TOTAL_WORK, RULE_WORK);
    }

    static Result propose(PolicyProposal.Result proposal, List<Candidate> candidates, int workLimit, int ruleLimit) {
        Objects.requireNonNull(proposal, "proposal");
        return propose(proposal.snapshot(), candidates, workLimit, ruleLimit);
    }

    static Result propose(PolicySnapshot snapshot, List<Candidate> candidates) {
        return propose(snapshot, candidates, TOTAL_WORK, RULE_WORK);
    }

    /**
     * Evaluates and applies I5 merges one at a time on {@code snapshot}. When {@code candidates} is null, tries the
     * merge candidates of {@code snapshot} in rule order; an empty candidate set returns {@link #NO_CANDIDATE} with no
     * step and {@code snapshot} unchanged.
     */
    static Result propose(PolicySnapshot snapshot, List<Candidate> candidates, int workLimit, int ruleLimit) {
        Objects.requireNonNull(snapshot, "snapshot");
        var meta = snapshot.metadata();
        if (!PolicyUnionCover.SUPPORTED_VENDORS.contains(meta.vendor())) return empty(snapshot, UNSUPPORTED_VENDOR);
        if (!snapshot.failures().isEmpty() || !snapshot.ruleComplete()) return empty(snapshot, INCOMPLETE);
        PolicyMerge.Result initialFull = null;
        List<Candidate> order;
        if (candidates != null) {
            order = List.copyOf(candidates);
        } else {
            initialFull = PolicyMerge.analyze(snapshot);
            order = candidatePairs(initialFull);
        }
        if (order.isEmpty()) return empty(snapshot, NO_CANDIDATE);

        Budget budget = new Budget(workLimit);
        PolicySnapshot current = snapshot;
        PolicyMerge.Result merge = initialFull != null && workLimit == TOTAL_WORK && ruleLimit == RULE_WORK ? initialFull : null;
        Map<String, String> mergedInto = new HashMap<>();
        List<Step> steps = new ArrayList<>(order.size());
        boolean stopped = false;

        for (int idx = 0; idx < order.size(); idx++) {
            Candidate target = order.get(idx);
            String surviving = target == null ? null : survivorOf(mergedInto, target.surviving());
            String toMerge = target == null ? null : target.merged();
            if (stopped) {
                steps.add(unproven(surviving, toMerge));
                continue;
            }
            if (merge == null && budget.left[0] < 0) {
                stopped = true;
                steps.add(unproven(surviving, toMerge));
                continue;
            }
            if (merge == null) {
                merge = PolicyMerge.analyze(current, budget.left[0], ruleLimit);
            }
            if (surviving == null || toMerge == null || surviving.equals(toMerge) || mergedInto.containsKey(toMerge)) {
                steps.add(new Step(surviving, toMerge, null, NO_LONGER_A_CANDIDATE, null, false, null));
                continue;
            }
            PolicyMerge.Pair pair = merge.pairs().get(surviving);
            if (pair == null || !pair.merged().id().equals(toMerge)) {
                steps.add(new Step(surviving, toMerge, null, NO_LONGER_A_CANDIDATE, null, false, null));
                continue;
            }
            if (PolicyMerge.NOT_PROVEN.equals(pair.result())) {
                stopped = true;
                steps.add(unproven(surviving, toMerge));
                continue;
            }
            if (!PolicyMerge.OFFERED.contains(pair.result())) {
                steps.add(new Step(surviving, toMerge, null, pair.result(), null, false, null));
                continue;
            }
            PolicySnapshot next;
            try {
                next = merged(current, surviving, toMerge, pair.differingDim());
            } catch (IllegalStateException | IllegalArgumentException breach) {
                stopped = true;
                steps.add(unproven(surviving, toMerge));
                continue;
            }
            if (idx + 1 < order.size()) {
                charge(current, budget, ruleLimit);
            }
            current = next;
            merge = null;
            mergedInto.put(toMerge, surviving);
            steps.add(new Step(surviving, toMerge, pair.differingCell(), PolicyMerge.MERGE_CANDIDATE, null, true, EQUIVALENT));
        }
        return result(steps, stopped, current);
    }

    /** The merge-candidate (and any budget-stopped) pairs of {@code snapshot} in rule order. */
    static List<Candidate> candidatesOf(PolicySnapshot snapshot) {
        var meta = snapshot.metadata();
        if (!PolicyUnionCover.SUPPORTED_VENDORS.contains(meta.vendor()) || !snapshot.failures().isEmpty() || !snapshot.ruleComplete()) return List.of();
        return candidatePairs(PolicyMerge.analyze(snapshot));
    }

    private static List<Candidate> candidatePairs(PolicyMerge.Result merge) {
        List<Candidate> out = new ArrayList<>();
        merge.pairs().values().forEach(pair -> {
            if (PolicyMerge.OFFERED.contains(pair.result()) || PolicyMerge.NOT_PROVEN.equals(pair.result())) {
                out.add(new Candidate(pair.surviving().id(), pair.merged().id()));
            }
        });
        return List.copyOf(out);
    }

    /**
     * Combines a reorder proposal and a merge pass into one package verdict and label (design 6.4, Q2).
     * {@code EQUIVALENT} only when at least one move or merge was accepted, the reorder verdict is {@code EQUIVALENT}
     * or {@code NO_MOVES}, and every accepted merge is proven; {@code NOT_PROVEN} when either part stopped on budget.
     * The label stays the reorder part's label (or {@link PolicyProposal#LABEL_NO_CHANGE} when merges alone were
     * accepted, null when stopped or nothing was accepted).
     */
    static Combined combine(PolicyProposal.Result proposal, Result merges) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(merges, "merges");
        boolean everyMergeProven = merges.steps().stream()
            .filter(Step::accepted)
            .allMatch(s -> PolicyMerge.MERGE_CANDIDATE.equals(s.result()) && EQUIVALENT.equals(s.equivalence())
                && s.differingCell() != null && PolicyMerge.CELLS.contains(s.differingCell()));
        boolean stopped = proposal.budgetStopped()
            || merges.budgetStopped()
            || PolicyReorder.NOT_PROVEN.equals(proposal.packageVerdict())
            || !everyMergeProven;
        int totalAccepted = proposal.accepted() + merges.accepted();
        String verdict;
        if (stopped) {
            verdict = PolicyReorder.NOT_PROVEN;
        } else if (totalAccepted == 0) {
            verdict = PolicyProposal.NO_MOVES;
        } else if (EQUIVALENT.equals(proposal.packageVerdict()) || PolicyProposal.NO_MOVES.equals(proposal.packageVerdict())) {
            verdict = EQUIVALENT;
        } else {
            verdict = proposal.packageVerdict();
        }
        boolean inspection = proposal.inspectionChanges();
        String label = stopped || totalAccepted == 0 ? null
            : proposal.label() != null ? proposal.label()
            : EQUIVALENT.equals(verdict) ? PolicyProposal.LABEL_NO_CHANGE : null;
        return new Combined(verdict, label, proposal.accepted(), merges.accepted(), inspection, stopped, merges.snapshot());
    }

    /**
     * G6: the snapshot after merging {@code mergedId} into {@code survivingId} in {@code differingDim}. Both must be
     * enabled rules of the same section with {@code mergedId} later than {@code survivingId} and only disabled rules (if
     * any) between them; else {@link IllegalStateException}. The surviving rule keeps its id, uuid, number, name and
     * comment; the merged rule is removed; every other rule, section, object map, metadata and failure list is shared by
     * identity, and {@code snapshot} is never mutated.
     */
    static PolicySnapshot merged(PolicySnapshot snapshot, String survivingId, String mergedId, int differingDim) {
        List<Section> sections = new ArrayList<>(snapshot.sections());
        for (int s = 0; s < sections.size(); s++) {
            Section section = sections.get(s);
            int atSurviving = indexOf(section.rules(), survivingId);
            if (atSurviving < 0) continue;
            int atMerged = indexOf(section.rules(), mergedId);
            if (atMerged < 0 || atMerged <= atSurviving) {
                throw new IllegalStateException("G6: merged rule is not a later rule of the surviving rule's section");
            }
            List<Rule> before = section.rules();
            Rule a = before.get(atSurviving), b = before.get(atMerged);
            if (Boolean.FALSE.equals(a.enabled()) || Boolean.FALSE.equals(b.enabled())) {
                throw new IllegalStateException("G6: disabled rules cannot be merged");
            }
            for (int k = atSurviving + 1; k < atMerged; k++) {
                if (!Boolean.FALSE.equals(before.get(k).enabled())) {
                    throw new IllegalStateException("G6: cannot merge across an enabled rule");
                }
            }
            Rule replacement = PolicyMerge.mergedRule(snapshot.metadata().vendor(), a, b, differingDim);
            List<Rule> after = new ArrayList<>(before.size() - 1);
            for (int i = 0; i < before.size(); i++) {
                if (i == atSurviving) after.add(replacement);
                else if (i != atMerged) after.add(before.get(i));
            }
            sections.set(s, new Section(section.id(), section.name(), section.source(), section.parentRuleId(), after));
            return new PolicySnapshot(snapshot.metadata(), sections, snapshot.objects(), snapshot.failures());
        }
        throw new IllegalStateException("G6: surviving rule is not in the snapshot");
    }

    /** Deducts from {@code budget} the exact work units one {@link PolicyMerge#analyze} pass spent on {@code snapshot}. */
    static void charge(PolicySnapshot snapshot, Budget budget, int ruleLimit) {
        var meta = snapshot.metadata();
        var resolver = Resolver.shadowing(snapshot, budget);
        var scope = new EvaluationScope<List<ChargedEntry>>(meta, ArrayList::new);
        List<ChargedEntry[]> adjacent = new ArrayList<>();
        int ordinal = 0;
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(meta, section)) continue;
            ordinal++;
            List<ChargedEntry> scopeEntries = scope.advance(section);
            List<ChargedEntry> inSection = new ArrayList<>();
            for (Rule rule : section.rules()) {
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                if (scope.unproven) continue;
                budget.rule(ruleLimit);
                ChargedEntry entry;
                try {
                    Match match = normalize(snapshot, section, rule, resolver);
                    if (match.problem() != null) {
                        entry = new ChargedEntry(rule, section, ordinal, null, null, true);
                    } else {
                        Box box = PolicyUnionCover.mixedPanZones(meta, match) ? null : Box.of(match.selectors());
                        if (box == null || hasNegatedCell(rule) || PolicyPartialShadow.isApplyLayer(rule)) {
                            entry = new ChargedEntry(rule, section, ordinal, null, null, true);
                        } else {
                            entry = new ChargedEntry(rule, section, ordinal, match, box, false);
                        }
                    }
                } catch (Unknown limit) {
                    entry = new ChargedEntry(rule, section, ordinal, null, null, false);
                }
                scopeEntries.add(entry);
                inSection.add(entry);
            }
            for (int i = 0; i + 1 < inSection.size(); i++) {
                adjacent.add(new ChargedEntry[]{inSection.get(i), inSection.get(i + 1)});
            }
        }
        String vendor = meta.vendor();
        for (ChargedEntry[] ab : adjacent) {
            ChargedEntry a = ab[0], b = ab[1];
            if (a.anchor() || b.anchor() || a.unread() || b.unread()) continue;
            if (!compatible(a.match(), b.match()) || !compatible(b.match(), a.match())) continue;
            if (!PolicyEffectSignature.sameFull(vendor, a.rule(), b.rule())) continue;
            budget.rule(ruleLimit);
            try {
                int differingDim = -1, differingCount = 0;
                boolean aCoversB = true;
                for (int i = 0; i < PolicyRegion.DIMENSIONS; i++) {
                    Dim da = a.box().dims().get(i), db = b.box().dims().get(i);
                    boolean bInA = PolicyRegion.minus(db, da, budget).empty();
                    boolean aInB = PolicyRegion.minus(da, db, budget).empty();
                    aCoversB &= bInA;
                    if (!bInA || !aInB) {
                        differingDim = i;
                        differingCount++;
                    }
                }
                if (aCoversB || differingCount != 1) continue;
                Rule merged = PolicyMerge.mergedRule(vendor, a.rule(), b.rule(), differingDim);
                Match mergedMatch = normalize(snapshot, a.section(), merged, resolver);
                Box mergedBox = mergedMatch.problem() == null ? Box.of(mergedMatch.selectors()) : null;
                if (mergedBox == null) continue;
                Dim expectedUnion = PolicyMerge.unionDim(a.box().dims().get(differingDim), b.box().dims().get(differingDim), budget);
                if (!PolicyMerge.sameDim(mergedBox.dims().get(differingDim), expectedUnion, budget)) continue;
                PolicyRegion.minus(a.box(), mergedBox, budget);
                PolicyRegion.minus(b.box(), mergedBox, budget);
                PolicyRegion.residual(mergedBox, List.of(a.box(), b.box()), budget);
            } catch (Unknown ignored) {
            }
        }
    }

    private static boolean hasNegatedCell(Rule rule) {
        return rule.source().negated() || rule.destination().negated()
            || rule.service().negated() || rule.application().negated();
    }

    private static String survivorOf(Map<String, String> mergedInto, String id) {
        if (id == null) return null;
        String current = id;
        for (int hops = 0; hops <= mergedInto.size() && mergedInto.containsKey(current); hops++) {
            current = mergedInto.get(current);
        }
        return current;
    }

    private static int indexOf(List<Rule> rules, String id) {
        for (int i = 0; i < rules.size(); i++) if (rules.get(i).id().equals(id)) return i;
        return -1;
    }

    private static Step unproven(String surviving, String merged) {
        return new Step(surviving, merged, null, PolicyMerge.NOT_PROVEN, BUDGET, false, PolicyMerge.NOT_PROVEN);
    }

    private static Result empty(PolicySnapshot snapshot, String reason) {
        return new Result(List.of(), 0, refusals(List.of()), false, reason, snapshot);
    }

    private static Map<String, Integer> refusals(List<Step> steps) {
        Map<String, Integer> refused = new LinkedHashMap<>();
        for (String word : REFUSALS) refused.put(word, 0);
        for (Step step : steps) if (!step.accepted()) refused.merge(step.result(), 1, Integer::sum);
        return Collections.unmodifiableMap(refused);
    }

    private static Result result(List<Step> steps, boolean stopped, PolicySnapshot snapshot) {
        int accepted = 0;
        for (Step step : steps) if (step.accepted()) accepted++;
        return new Result(List.copyOf(steps), accepted, refusals(steps), stopped, null, snapshot);
    }
}
