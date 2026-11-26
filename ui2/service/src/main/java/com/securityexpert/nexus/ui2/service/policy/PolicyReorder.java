package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Box;
import java.util.*;

/**
 * Insight I7 of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN), phase P2 step c-1, Check Point only: "safe reorder
 * (promote)". For every I4 rule r ({@link PolicyPartialShadow} PARTIAL, every overlapping earlier rule of the same action:
 * design 6.1 step 2, "a specific rule below a broad rule of the same effect") it tests one move: r to just above B, the
 * earliest earlier rule r overlaps. Pure: reads the snapshot and the I4 result, contacts nothing, stores nothing, and
 * writes no change anywhere (decision record Q7).
 *
 * <p>The fixed readings, each from the design or its decision record:
 * <ul>
 * <li><b>Scope (Q9, design 7).</b> B and r share the shadow engine's {@link EvaluationScope} (the I4 pass found B there),
 * so nothing crosses a layer. A move that would leave r's section is not offered in this step ({@link #CROSSES_SECTION},
 * counted): crossing a section is an explicit user choice the design keeps for later. B and r have the same install-on
 * ({@link PolicyNormalization#compatible}, as for I4).</li>
 * <li><b>Anchors (Q3).</b> A rule between B and r that the engine cannot read -- a user, application or time constraint,
 * an unresolved object, an auto-generated rule, an opaque selector, anything {@link PolicyNormalization#normalize} leaves
 * with a problem -- or an Apply Layer rule is a fixed anchor: nothing moves across it ({@link #ANCHOR_BLOCKED}, counted).
 * Disabled rules match nothing and are skipped.</li>
 * <li><b>Crossed rules.</b> Every enabled rule strictly between B and r. One whose install-on is proven disjoint from r's
 * ({@link PolicyNormalization#disjoint}: distinct atomic gateways) never meets r's packets: "disjoint". Any other
 * placement takes part, an equal one exactly and an unequal, not provably disjoint one (a group, "Policy Targets") as if
 * it were equal, which can only add constraints (fail-closed). A taking-part rule whose box does not share a point with
 * r's is "disjoint"; one that does must have r's verdict effect ({@link PolicyEffectSignature#sameVerdict}), else the
 * move is {@link #UNSAFE_DIFFERENT_EFFECT} (counted, no insight). B itself always has r's verdict effect (I4).</li>
 * <li><b>Grades (design 3.1, Q2).</b> {@link #SAFE} when B and every taking-part overlapping crossed rule have r's full
 * effect signature ({@link PolicyEffectSignature#sameFull}): PROVEN, "no behaviour change".
 * {@link #SAFE_INSPECTION_CHANGES} when the verdicts agree but one differs in profile or log setting, or that part was not
 * collected: PROVEN for the verdict, "verdict-safe, inspection changes", never "no behaviour change".</li>
 * <li><b>Symbolic equivalence (design 6.4).</b> Per move, the region whose deciding rule differs: r's box intersected with
 * B and the taking-part crossed rules, each part assigned to the first of them that matches it today
 * ({@link PolicyRegion} intersect and minus, never enumeration). Readable earlier rules of the same placement never meet
 * r before B (B is the first, I4), and rules before B decide the same packets in both orders, so this region holds the
 * true one: {@link #EQUIVALENT} when every part is decided today by a rule of r's full signature, else {@link #CHANGES}
 * with the region counted (boxes and deciding rules; no range is stored or returned). {@link #SAFE} implies
 * {@link #EQUIVALENT}; it is asserted.</li>
 * <li><b>Budget (Q8).</b> The shadow engine's ({@link PolicyUnionCover#RULE_WORK} per rule and per move,
 * {@link PolicyUnionCover#TOTAL_WORK} per snapshot). Running out, or a crossed rule left unread for it, is
 * {@link #NOT_PROVEN} ("analysis budget"), never a safe move.</li>
 * <li><b>One move at a time</b> against the stored order (design 6.1): each candidate is evaluated on its own; moves are
 * not stacked in this step.</li>
 * </ul>
 * A snapshot with collection failures, a section whose order is not proven and an opaque rule evaluate nothing: the I4
 * pass gives them no candidate, and its not-evaluable counts are this pass's.
 */
final class PolicyReorder {
    private PolicyReorder() {}
    static final int RULE_WORK = PolicyUnionCover.RULE_WORK, TOTAL_WORK = PolicyUnionCover.TOTAL_WORK;
    /** Move results: the two offered (an I7 insight), NOT_PROVEN (an I7 graded NOT_EVALUABLE) and three only counted. */
    static final String SAFE = "SAFE", SAFE_INSPECTION_CHANGES = "SAFE_INSPECTION_CHANGES", NOT_PROVEN = "NOT_PROVEN",
        UNSAFE_DIFFERENT_EFFECT = "UNSAFE_DIFFERENT_EFFECT", ANCHOR_BLOCKED = "ANCHOR_BLOCKED", CROSSES_SECTION = "CROSSES_SECTION";
    /** The results an insight can carry ({@code counter.reorderResult}). */
    static final Set<String> OFFERED = Set.of(SAFE, SAFE_INSPECTION_CHANGES);
    /** Symbolic equivalence per move (design 6.4). */
    static final String EQUIVALENT = "EQUIVALENT", CHANGES = "CHANGES";
    static final Set<String> EQUIVALENCE = Set.of(EQUIVALENT, CHANGES, NOT_PROVEN);
    static final String BUDGET = PolicyUnionCover.BUDGET;
    /** Why a crossed rule is harmless. */
    static final String DISJOINT = "disjoint", SAME_VERDICT = "same verdict effect";
    static final Set<String> CROSSED_REASONS = Set.of(DISJOINT, SAME_VERDICT);
    static final List<String> NOT_EVALUABLE_REASONS = PolicyUnionCover.NOT_EVALUABLE_REASONS;

    /** A rule the move crosses (or B), why it is harmless, and whether its profile or log setting differs from r's. */
    record Crossed(Rule rule, String reason, boolean inspectionChanges) {}
    /**
     * One promote of {@code promoted} to just above {@code above}. {@code crossed}: the enabled rules strictly between, in
     * rule order (empty unless offered). {@code deciding}: B and the crossed rules that decide a non-empty part of the
     * promoted rule's match today, which the move hands to it; {@code changedRules}/{@code changedBoxes}: those of them
     * with another full signature, and the disjoint boxes of their parts.
     */
    record Move(String result, String reason, Rule promoted, Crossed above, List<Crossed> crossed, String equivalence,
            List<Rule> deciding, int changedRules, int changedBoxes) {}
    /**
     * Per I4 rule its move, and the counts (design Q8): moves evaluated (one per I4 rule), safe, safe with inspection
     * changes, unsafe (a different verdict effect in between), anchor blocked, crossing a section, not proven for the
     * budget (these six sum to {@code evaluated}), and not evaluable by reason (the I4 pass's: no candidate there).
     */
    record Result(Map<String, Move> moves, int evaluated, int safe, int safeInspectionChanges, int unsafeDifferentEffect,
            int anchorBlocked, int crossesSection, int notProven, Map<String, Integer> notEvaluable) {}

    /** One enabled rule of a scope: readable (match and box), an anchor, or unread for the budget. */
    private record Entry(Rule rule, int section, Match match, Box box, boolean anchor) {
        boolean unread() { return !anchor && match == null; }
    }
    private record Position(List<Entry> scope, int index) {}

    static Result analyze(PolicySnapshot snapshot, PolicyPartialShadow.Result partial) { return analyze(snapshot, partial, TOTAL_WORK, RULE_WORK); }

    static Result analyze(PolicySnapshot snapshot, PolicyPartialShadow.Result partial, int workLimit, int ruleLimit) {
        return analyze(snapshot, partial, new Budget(workLimit), ruleLimit);
    }

    /**
     * The same pass over a budget the caller owns: the reorder-only proposal ({@link PolicyProposal}) shares one pool over
     * every step it analyses and reads what is left ({@code budget.left[0] < 0}: used up). The only addition of the
     * proposal step to this class.
     */
    static Result analyze(PolicySnapshot snapshot, PolicyPartialShadow.Result partial, Budget budget, int ruleLimit) {
        var meta = snapshot.metadata();
        Map<String, Move> moves = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, PolicyPartialShadow.Partial> candidates = new HashMap<>();
        partial.rules().forEach((id, p) -> { if (PolicyPartialShadow.PARTIAL.equals(p.status()) && p.sameAction() && !p.overlapping().isEmpty()) candidates.put(id, p); });
        if (!"CP".equals(meta.vendor()) || !snapshot.failures().isEmpty() || candidates.isEmpty())
            return new Result(Map.of(), 0, 0, 0, 0, 0, 0, 0, partial.notEvaluable());
        var resolver = Resolver.shadowing(snapshot, budget);
        var scope = new EvaluationScope<List<Entry>>(meta, ArrayList::new);
        Map<String, Position> where = new HashMap<>();
        List<Rule> order = new ArrayList<>();
        int ordinal = 0;
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(meta, section)) continue;
            ordinal++;
            List<Entry> entries = scope.advance(section);
            for (Rule rule : section.rules()) {
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                budget.rule(ruleLimit);
                Entry entry;
                try {
                    Match match = normalize(snapshot, section, rule, resolver);
                    Box box = match.problem() == null ? Box.of(match.selectors()) : null;
                    entry = box == null || PolicyPartialShadow.isApplyLayer(rule) ? new Entry(rule, ordinal, null, null, true) : new Entry(rule, ordinal, match, box, false);
                } catch (Unknown limit) {
                    entry = new Entry(rule, ordinal, null, null, false);
                }
                entries.add(entry);
                where.put(rule.id(), new Position(entries, entries.size() - 1));
                if (candidates.containsKey(rule.id())) order.add(rule);
            }
        }
        for (Rule rule : order) {
            Move move;
            try {
                move = move(snapshot, where.get(rule.id()), where.get(candidates.get(rule.id()).overlapping().get(0).id()), budget, ruleLimit);
            } catch (IllegalStateException breach) {
                // An internal-invariant breach must not drop the whole insights response: this one move is NOT_PROVEN.
                move = new Move(NOT_PROVEN, BUDGET, rule, new Crossed(candidates.get(rule.id()).overlapping().get(0), SAME_VERDICT, false), List.of(), NOT_PROVEN, List.of(), 0, 0);
            }
            moves.put(rule.id(), move);
            counts.merge(move.result(), 1, Integer::sum);
        }
        return new Result(Collections.unmodifiableMap(moves), order.size(), counts.getOrDefault(SAFE, 0), counts.getOrDefault(SAFE_INSPECTION_CHANGES, 0),
            counts.getOrDefault(UNSAFE_DIFFERENT_EFFECT, 0), counts.getOrDefault(ANCHOR_BLOCKED, 0), counts.getOrDefault(CROSSES_SECTION, 0),
            counts.getOrDefault(NOT_PROVEN, 0), partial.notEvaluable());
    }

    private static Move move(PolicySnapshot snapshot, Position subject, Position broad, Budget budget, int ruleLimit) {
        // The I4 pass read B from the same evaluation scope, earlier in it: anything else is a defect, never a guess.
        if (broad == null || subject == null || broad.scope() != subject.scope() || broad.index() >= subject.index())
            throw new IllegalStateException("I7: the broad rule is not an earlier rule of the subject's scope");
        var scope = subject.scope();
        Entry r = scope.get(subject.index()), b = scope.get(broad.index());
        var between = scope.subList(broad.index() + 1, subject.index());
        if (between.stream().anyMatch(Entry::anchor)) return refused(ANCHOR_BLOCKED, r, b);
        if (b.section() != r.section()) return refused(CROSSES_SECTION, r, b);
        if (r.unread() || b.unread() || between.stream().anyMatch(Entry::unread)) return refused(NOT_PROVEN, r, b);
        String vendor = snapshot.metadata().vendor();
        budget.rule(ruleLimit);
        try {
            List<Crossed> crossed = new ArrayList<>();
            // Rules that take part, B first, in rule order; whether each subtracts its box from later parts (equal placement).
            List<Entry> parts = new ArrayList<>(List.of(b));
            boolean inspection = !PolicyEffectSignature.sameFull(vendor, b.rule(), r.rule());
            for (Entry c : between) {
                boolean takesPart = compatible(c.match(), r.match()) || !disjoint(c.match(), r.match(), snapshot.objects());
                if (!takesPart || !PolicyPartialShadow.intersects(r.box(), c.box(), budget)) { crossed.add(new Crossed(c.rule(), DISJOINT, false)); continue; }
                if (!PolicyEffectSignature.sameVerdict(c.rule(), r.rule())) return refused(UNSAFE_DIFFERENT_EFFECT, r, b);
                boolean differs = !PolicyEffectSignature.sameFull(vendor, c.rule(), r.rule());
                inspection |= differs;
                crossed.add(new Crossed(c.rule(), SAME_VERDICT, differs));
                parts.add(c);
            }
            // Design 6.4: the region whose deciding rule changes, split by the rule deciding each part today.
            List<Rule> deciding = new ArrayList<>();
            List<Box> earlier = new ArrayList<>();
            int changedRules = 0, changedBoxes = 0;
            for (Entry p : parts) {
                Box shared = intersection(r.box(), p.box(), budget);
                if (shared != null) {
                    var piece = PolicyRegion.residual(shared, earlier, budget);
                    if (piece.status() == PolicyRegion.Status.NOT_PROVEN) return refused(NOT_PROVEN, r, b);
                    if (!piece.boxes().isEmpty()) {
                        deciding.add(p.rule());
                        if (!PolicyEffectSignature.sameFull(vendor, p.rule(), r.rule())) { changedRules++; changedBoxes += piece.boxes().size(); }
                    }
                }
                // Only a rule of r's own placement is sure to take its part on every gateway r is installed on.
                if (compatible(p.match(), r.match()) && compatible(r.match(), p.match())) earlier.add(p.box());
            }
            String result = inspection ? SAFE_INSPECTION_CHANGES : SAFE;
            String equivalence = changedRules == 0 ? EQUIVALENT : CHANGES;
            if (result.equals(SAFE) && !equivalence.equals(EQUIVALENT)) throw new IllegalStateException("I7: a SAFE move must be EQUIVALENT");
            return new Move(result, null, r.rule(), new Crossed(b.rule(), SAME_VERDICT, !PolicyEffectSignature.sameFull(vendor, b.rule(), r.rule())),
                List.copyOf(crossed), equivalence, List.copyOf(deciding), changedRules, changedBoxes);
        } catch (Unknown limit) {
            return refused(NOT_PROVEN, r, b);
        }
    }

    private static Move refused(String result, Entry r, Entry b) {
        return new Move(result, result.equals(NOT_PROVEN) ? BUDGET : null, r.rule(), new Crossed(b.rule(), SAME_VERDICT, false), List.of(),
            result.equals(NOT_PROVEN) ? NOT_PROVEN : null, List.of(), 0, 0);
    }

    /** The common part of two boxes, or null when they share no point. */
    private static Box intersection(Box a, Box b, Budget budget) {
        List<PolicyRegion.Dim> dims = new ArrayList<>(PolicyRegion.DIMENSIONS);
        for (int i = 0; i < PolicyRegion.DIMENSIONS; i++) {
            var d = PolicyRegion.intersect(a.dims().get(i), b.dims().get(i), budget);
            if (d.empty()) return null;
            dims.add(d);
        }
        return new Box(dims);
    }
}
