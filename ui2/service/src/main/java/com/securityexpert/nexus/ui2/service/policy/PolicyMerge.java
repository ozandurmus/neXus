package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Box;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Dim;
import java.util.*;

/**
 * Insight I5 of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN), phase P2 step c-2a, Check Point only: merge candidates
 * over adjacent enabled rules of one evaluation scope and one section. Pure: reads one stored snapshot, contacts nothing,
 * stores nothing, and generates no command text (decision record Q7).
 *
 * <p>The fixed readings, each from the design or its decision record (fail-closed where the design is silent):
 * <ul>
 * <li><b>Scope and adjacency (Q9, design 4 row I5, 6.3, 7).</b> Walk the shadow engine's {@link EvaluationScope} per
 * section, like {@link PolicyReorder}. Disabled rules match nothing and are skipped. Candidates are pairs {@code (a, b)}
 * that are immediately adjacent among the enabled rules of one proven scope and one section: pairs separated by an enabled
 * rule, across a section header, across a layer, or only adjacent after an I7 promote belong to the proposal engine of the
 * next step and are not offered here. In a chain {@code (a, b, c)} of three mergeable rules, both adjacent pairs
 * {@code (a, b)} and {@code (b, c)} are emitted; groups are not built here.</li>
 * <li><b>Anchors (Q3, design F5).</b> A rule the engine cannot read -- user, application or time constraint, unresolved
 * object, auto-generated flag, missing/unresolved install-on, anything {@link PolicyNormalization#normalize} leaves with a
 * problem, an opaque selector ({@link Box#of} null), an Apply Layer rule, or a negated cell (design F5; Check Point
 * negates the whole cell, so combining two negated cells would take their intersection rather than their union) -- is a
 * fixed anchor: any adjacent pair touching it is refused ({@link #ANCHOR_BLOCKED}, counted).</li>
 * <li><b>Placement and effect signature (G3, Q2).</b> Both rules must have the same install-on placement both ways
 * ({@link PolicyNormalization#compatible} in both directions; else {@link #DIFFERENT_INSTALL_ON}, counted) and the same
 * full effect signature with no UNKNOWN part ({@link PolicyEffectSignature#sameFull}; an UNKNOWN part equals nothing,
 * another UNKNOWN included; else {@link #DIFFERENT_EFFECT}, counted). Check Point access rules carry no collected profile
 * reference, so the evidence text states the same profile caveat as {@link PolicyInsights#I7_SAFE}.</li>
 * <li><b>Boxes and union property (design 4 row I5, 6.3).</b> When {@code a}'s match box already covers {@code b}'s in
 * every dimension (equal boxes, or {@code b} a subset of {@code a} in the one differing cell), the later rule {@code b} is
 * already an I1 ({@code REDUNDANT}, same action) and stays there ({@link #ALREADY_SHADOWED}, counted). Two or more
 * differing dimensions are refused ({@link #DIFFERS_IN_SEVERAL_CELLS}, counted). Exactly one differing dimension gives
 * {@link #MERGE_CANDIDATE}: the surviving rule {@code a} keeps its identity (design 6.3), the merged cell in that
 * dimension is the union of {@code a}'s and {@code b}'s cells, and the engine verifies before offering the pair that the
 * merged rule normalizes to a box equal to {@code box(a) U box(b)}.</li>
 * <li><b>Budget and containment (Q8).</b> The shadow engine's {@link PolicyUnionCover#RULE_WORK} and
 * {@link PolicyUnionCover#TOTAL_WORK}. Exhausting the budget, or any internal-invariant breach on one pair, turns that
 * pair into {@link #NOT_PROVEN} ("analysis budget", shown as an I5 graded {@code NOT_EVALUABLE}), never a candidate and
 * never a failed response.</li>
 * </ul>
 * Other vendors, a snapshot with collection failures, an incomplete snapshot and a section whose order is not proven
 * evaluate nothing and report their enabled security rules in {@code notEvaluable}.
 */
final class PolicyMerge {
    private PolicyMerge() {}
    static final int RULE_WORK = PolicyUnionCover.RULE_WORK, TOTAL_WORK = PolicyUnionCover.TOTAL_WORK;
    static final String MERGE_CANDIDATE = "MERGE_CANDIDATE", NOT_PROVEN = "NOT_PROVEN",
        ALREADY_SHADOWED = "ALREADY_SHADOWED", DIFFERS_IN_SEVERAL_CELLS = "DIFFERS_IN_SEVERAL_CELLS",
        DIFFERENT_EFFECT = "DIFFERENT_EFFECT", DIFFERENT_INSTALL_ON = "DIFFERENT_INSTALL_ON", ANCHOR_BLOCKED = "ANCHOR_BLOCKED";
    static final Set<String> OFFERED = Set.of(MERGE_CANDIDATE);
    static final String SOURCE = "source", DESTINATION = "destination", SERVICE = "service", APPLICATION = "application";
    /** Closed dimension-name vocabulary matching {@link PolicyRegion#DIMENSIONS} in {@link Box#dims()} order. */
    static final List<String> CELLS = List.of(SOURCE, DESTINATION, SERVICE, APPLICATION);
    static final String BUDGET = PolicyUnionCover.BUDGET;
    static final List<String> NOT_EVALUABLE_REASONS = PolicyUnionCover.NOT_EVALUABLE_REASONS;

    /**
     * One adjacent enabled pair {@code (surviving, merged)} in a proven section. {@code differingCell} is non-null only for
     * {@link #MERGE_CANDIDATE}; {@code reason} is {@link #BUDGET} for {@link #NOT_PROVEN} and null otherwise.
     */
    record Pair(String result, String reason, Rule surviving, Rule merged, int differingDim, String differingCell) {}
    /**
     * Per first rule {@code a.id()} of each evaluated adjacent pair its {@link Pair}, and the counts (design Q8):
     * {@code candidates + alreadyShadowed + differsInSeveralCells + differentEffect + differentInstallOn + anchorBlocked + notProven == evaluated},
     * plus {@code notEvaluable} enabled rules by reason.
     */
    record Result(Map<String, Pair> pairs, int evaluated, int candidates, int alreadyShadowed, int differsInSeveralCells,
            int differentEffect, int differentInstallOn, int anchorBlocked, int notProven, Map<String, Integer> notEvaluable) {}

    private record Entry(Rule rule, Section section, int sectionOrdinal, Match match, Box box, boolean anchor) {
        boolean unread() { return !anchor && match == null; }
    }

    static Result analyze(PolicySnapshot snapshot) { return analyze(snapshot, TOTAL_WORK, RULE_WORK); }

    static Result analyze(PolicySnapshot snapshot, int workLimit, int ruleLimit) {
        return analyze(snapshot, workLimit, ruleLimit, (a, b) -> {});
    }

    static Result analyze(PolicySnapshot snapshot, int workLimit, int ruleLimit, java.util.function.BiConsumer<Rule, Rule> pairCheck) {
        var meta = snapshot.metadata();
        Map<String, Pair> pairs = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> notEvaluable = new LinkedHashMap<>();
        String everyRule = !"CP".equals(meta.vendor()) ? PolicyUnionCover.UNSUPPORTED_VENDOR
            : !snapshot.failures().isEmpty() || !snapshot.ruleComplete() ? PolicyUnionCover.INCOMPLETE : null;
        if (everyRule != null) {
            for (Section section : snapshot.sections()) {
                if (PolicySnapshot.outsideSecurityEvaluation(meta, section)) continue;
                for (Rule rule : section.rules()) {
                    if (Boolean.FALSE.equals(rule.enabled())) continue;
                    notEvaluable.merge(everyRule, 1, Integer::sum);
                }
            }
            return new Result(Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, orderedReasons(notEvaluable));
        }
        Budget budget = new Budget(workLimit);
        var resolver = Resolver.shadowing(snapshot, budget);
        var scope = new EvaluationScope<List<Entry>>(meta, ArrayList::new);
        List<Entry[]> adjacent = new ArrayList<>();
        int ordinal = 0;
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(meta, section)) continue;
            ordinal++;
            List<Entry> scopeEntries = scope.advance(section);
            List<Entry> inSection = new ArrayList<>();
            for (Rule rule : section.rules()) {
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                if (scope.unproven) {
                    notEvaluable.merge(PolicyUnionCover.ORDER_UNPROVEN, 1, Integer::sum);
                    continue;
                }
                budget.rule(ruleLimit);
                Entry entry;
                try {
                    Match match = normalize(snapshot, section, rule, resolver);
                    if (match.problem() != null) {
                        notEvaluable.merge(PolicyUnionCover.NOT_RESOLVED, 1, Integer::sum);
                        entry = new Entry(rule, section, ordinal, null, null, true);
                    } else {
                        Box box = Box.of(match.selectors());
                        if (box == null) {
                            notEvaluable.merge(PolicyUnionCover.OPAQUE, 1, Integer::sum);
                            entry = new Entry(rule, section, ordinal, null, null, true);
                        } else if (hasNegatedCell(rule)) {
                            notEvaluable.merge(PolicyUnionCover.NOT_RESOLVED, 1, Integer::sum);
                            entry = new Entry(rule, section, ordinal, null, null, true);
                        } else if (PolicyPartialShadow.isApplyLayer(rule)) {
                            entry = new Entry(rule, section, ordinal, null, null, true);
                        } else {
                            entry = new Entry(rule, section, ordinal, match, box, false);
                        }
                    }
                } catch (Unknown limit) {
                    entry = new Entry(rule, section, ordinal, null, null, false);
                }
                scopeEntries.add(entry);
                inSection.add(entry);
            }
            for (int i = 0; i + 1 < inSection.size(); i++) {
                adjacent.add(new Entry[]{inSection.get(i), inSection.get(i + 1)});
            }
        }
        for (Entry[] ab : adjacent) {
            Entry a = ab[0], b = ab[1];
            Pair pair;
            try {
                pairCheck.accept(a.rule(), b.rule());
                pair = evaluatePair(snapshot, a, b, resolver, budget, ruleLimit);
            } catch (IllegalStateException breach) {
                // An internal-invariant breach must not drop the whole insights response: this one pair is NOT_PROVEN.
                pair = new Pair(NOT_PROVEN, BUDGET, a.rule(), b.rule(), -1, null);
            }
            pairs.put(a.rule().id(), pair);
            counts.merge(pair.result(), 1, Integer::sum);
        }
        return new Result(Collections.unmodifiableMap(pairs), adjacent.size(),
            counts.getOrDefault(MERGE_CANDIDATE, 0),
            counts.getOrDefault(ALREADY_SHADOWED, 0),
            counts.getOrDefault(DIFFERS_IN_SEVERAL_CELLS, 0),
            counts.getOrDefault(DIFFERENT_EFFECT, 0),
            counts.getOrDefault(DIFFERENT_INSTALL_ON, 0),
            counts.getOrDefault(ANCHOR_BLOCKED, 0),
            counts.getOrDefault(NOT_PROVEN, 0),
            orderedReasons(notEvaluable));
    }

    private static Pair evaluatePair(PolicySnapshot snapshot, Entry a, Entry b, Resolver resolver, Budget budget, int ruleLimit) {
        if (a == null || b == null || a.sectionOrdinal() != b.sectionOrdinal() || a.section() != b.section())
            throw new IllegalStateException("I5: pair must belong to one section");
        if (a.anchor() || b.anchor()) return refused(ANCHOR_BLOCKED, a, b);
        if (a.unread() || b.unread()) return refused(NOT_PROVEN, a, b);
        if (!compatible(a.match(), b.match()) || !compatible(b.match(), a.match()))
            return refused(DIFFERENT_INSTALL_ON, a, b);
        String vendor = snapshot.metadata().vendor();
        if (!PolicyEffectSignature.sameFull(vendor, a.rule(), b.rule()))
            return refused(DIFFERENT_EFFECT, a, b);
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
            if (aCoversB) return refused(ALREADY_SHADOWED, a, b);
            if (differingCount >= 2) return refused(DIFFERS_IN_SEVERAL_CELLS, a, b);
            if (differingCount != 1) throw new IllegalStateException("I5: expected one differing dimension");
            Rule merged = mergedRule(vendor, a.rule(), b.rule(), differingDim);
            Match mergedMatch = normalize(snapshot, a.section(), merged, resolver);
            Box mergedBox = mergedMatch.problem() == null ? Box.of(mergedMatch.selectors()) : null;
            if (mergedBox == null) throw new IllegalStateException("I5: merged rule is not readable");
            Dim expectedUnion = unionDim(a.box().dims().get(differingDim), b.box().dims().get(differingDim), budget);
            if (!sameDim(mergedBox.dims().get(differingDim), expectedUnion, budget))
                throw new IllegalStateException("I5: merged cell does not equal the union in the differing dimension");
            List<Box> aLeft = PolicyRegion.minus(a.box(), mergedBox, budget);
            List<Box> bLeft = PolicyRegion.minus(b.box(), mergedBox, budget);
            var extra = PolicyRegion.residual(mergedBox, List.of(a.box(), b.box()), budget);
            if (extra.status() == PolicyRegion.Status.NOT_PROVEN) return refused(NOT_PROVEN, a, b);
            if (aLeft == null || !aLeft.isEmpty() || bLeft == null || !bLeft.isEmpty() || !extra.covered())
                throw new IllegalStateException("I5: merged box does not equal the union of the two boxes");
            return new Pair(MERGE_CANDIDATE, null, a.rule(), b.rule(), differingDim, CELLS.get(differingDim));
        } catch (Unknown limit) {
            return refused(NOT_PROVEN, a, b);
        }
    }

    /**
     * The merged rule for {@code (a, b)} differing only in {@code differingDim}: keeps {@code a}'s identity, position,
     * name, comment, action, log, extras and hit counts (design 6.3), and replaces only the differing cell with the union
     * of the two rules' references.
     */
    static Rule mergedRule(String vendor, Rule a, Rule b, int differingDim) {
        if (differingDim < 0 || differingDim >= PolicyRegion.DIMENSIONS)
            throw new IllegalArgumentException("Invalid differing dimension: " + differingDim);
        Cell source = differingDim == 0 ? unionCell(vendor, a.source(), b.source(), 0) : a.source();
        Cell destination = differingDim == 1 ? unionCell(vendor, a.destination(), b.destination(), 1) : a.destination();
        Cell service = differingDim == 2 ? unionCell(vendor, a.service(), b.service(), 2) : a.service();
        Cell application = differingDim == 3 ? unionCell(vendor, a.application(), b.application(), 3) : a.application();
        return new Rule(a.id(), a.uuid(), a.number(), a.name(), a.enabled(), source, destination, service, application,
            a.action(), a.log(), a.comment(), a.extras(), a.hitCounts());
    }

    private static Cell unionCell(String vendor, Cell a, Cell b, int dim) {
        if (a.negated() || b.negated()) throw new IllegalStateException("I5: negated cell cannot be unioned by reference list");
        if (dim == 3 && EMPTY_APPLICATION_ANY.contains(vendor) && (a.refs().isEmpty() || b.refs().isEmpty()))
            return new Cell(List.of(), false);
        LinkedHashSet<String> refs = new LinkedHashSet<>(a.refs());
        refs.addAll(b.refs());
        return new Cell(List.copyOf(refs), false);
    }

    private static boolean hasNegatedCell(Rule rule) {
        return rule.source().negated() || rule.destination().negated() || rule.service().negated() || rule.application().negated();
    }

    /** Set union of two {@link Dim}s by De Morgan over {@link PolicyRegion#intersect}. */
    static Dim unionDim(Dim a, Dim b, Budget budget) {
        Dim neither = PolicyRegion.intersect(new Dim(!a.complement(), a.ranges()), new Dim(!b.complement(), b.ranges()), budget);
        return new Dim(!neither.complement(), neither.ranges());
    }

    static boolean sameDim(Dim a, Dim b, Budget budget) {
        return PolicyRegion.minus(a, b, budget).empty() && PolicyRegion.minus(b, a, budget).empty();
    }

    private static Pair refused(String result, Entry a, Entry b) {
        return new Pair(result, result.equals(NOT_PROVEN) ? BUDGET : null, a.rule(), b.rule(), -1, null);
    }

    private static Map<String, Integer> orderedReasons(Map<String, Integer> notEvaluable) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String reason : NOT_EVALUABLE_REASONS) if (notEvaluable.containsKey(reason)) ordered.put(reason, notEvaluable.get(reason));
        return Collections.unmodifiableMap(ordered);
    }
}
