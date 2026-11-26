package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Box;
import java.util.*;

/**
 * Insight I4 of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN), phase P2 step b, Check Point only: part of a rule's match
 * is also matched by earlier rules of the same layer that all have the rule's own action ("partly shadowed"). It never
 * says the rule is unreachable (that is I1/I2/I3) and never says it can be moved: whether a promote changes any verdict
 * is I7, a later step. Pure: reads the snapshot, the hygiene result and the I3 result, contacts nothing and stores nothing.
 *
 * <p>It rests on the same fixed readings as {@link PolicyUnionCover}, whose earlier-rule bucketing it shares:
 * <ul>
 * <li><b>Same scope, never across a layer (Q9)</b>, and only earlier rules with a {@link PolicyNormalization#compatible}
 * placement take part.</li>
 * <li><b>Anchors (Q3).</b> A rule the engine cannot read is never a subject and never an earlier rule that overlaps: it is
 * a fixed anchor. Whatever the anchors do, the overlap listed here is a proven overlap of two readable matches.</li>
 * <li><b>G1 with a budget (Q8).</b> {@link PolicyRegion#residual} of the subject against the earlier rules that really
 * intersect it. PARTIAL needs at least one real intersection and a proven non-empty rest; running out of work is
 * NOT_PROVEN ("analysis budget"), never a guess.</li>
 * <li><b>Rule-complete snapshot (design 3.1).</b> A snapshot with collection failures evaluates nothing.</li>
 * </ul>
 * Subjects are enabled rules the shadow engine did not find REDUNDANT or CONFLICT and that I3 did not find COVERED. A rule
 * wholly covered (empty rest) is none of those insights' business here: no I4.
 *
 * <p><b>Action relation.</b> The insight exists only when EVERY earlier readable rule that intersects the subject has the
 * subject's action. One intersecting rule with another action means a promote there could change a verdict, which is I7
 * territory: the rule is then counted ({@code differentAction}) and no I4 is made. An Apply Layer rule is never the same
 * action as anything, whichever side it is on.
 */
final class PolicyPartialShadow {
    private PolicyPartialShadow() {}
    static final int RULE_WORK = PolicyUnionCover.RULE_WORK, TOTAL_WORK = PolicyUnionCover.TOTAL_WORK;
    static final String PARTIAL = "PARTIAL", NOT_PROVEN = "NOT_PROVEN";
    static final String BUDGET = PolicyUnionCover.BUDGET;
    /** Why a rule was not evaluated: the same closed set as {@link PolicyUnionCover#NOT_EVALUABLE_REASONS}. */
    static final List<String> NOT_EVALUABLE_REASONS = PolicyUnionCover.NOT_EVALUABLE_REASONS;

    /**
     * PARTIAL (with the earlier rules that really intersect the subject, in rule order, and whether every one has the
     * subject's action), or NOT_PROVEN with {@link #BUDGET}.
     */
    record Partial(String status, String reason, List<Rule> overlapping, boolean sameAction) {}
    /**
     * Per rule a PARTIAL or NOT_PROVEN verdict (a rule with no overlap, or wholly covered, has none). The counts: subject
     * rules evaluated (the overlap was computed), {@code partial} (PARTIAL, every overlapping rule has the same action: the
     * I4 insights), {@code differentAction} (PARTIAL where at least one overlapping rule differs: counted, no insight), not
     * proven, and not evaluable by reason.
     */
    record Result(Map<String, Partial> rules, int evaluated, int partial, int differentAction, int notProven, Map<String, Integer> notEvaluable) {}

    static Result analyze(PolicySnapshot snapshot, PolicyHygiene.Result hygiene, PolicyUnionCover.Result union) {
        return analyze(snapshot, hygiene, union, TOTAL_WORK, RULE_WORK);
    }

    static Result analyze(PolicySnapshot snapshot, PolicyHygiene.Result hygiene, PolicyUnionCover.Result union, int workLimit, int ruleLimit) {
        var meta = snapshot.metadata();
        Map<String, Partial> rules = new LinkedHashMap<>();
        Map<String, Integer> notEvaluable = new LinkedHashMap<>();
        int evaluated = 0, partial = 0, differentAction = 0, notProven = 0;
        String everyRule = !"CP".equals(meta.vendor()) ? PolicyUnionCover.UNSUPPORTED_VENDOR
            : !snapshot.failures().isEmpty() ? PolicyUnionCover.INCOMPLETE : null;
        Budget budget = new Budget(workLimit);
        var resolver = Resolver.shadowing(snapshot, budget);
        var scope = new EvaluationScope<PolicyUnionCover.Earlier>(meta, PolicyUnionCover.Earlier::new);
        // Evaluation states in which an earlier rule ran out of its own work window before it was read: it may be readable
        // and may have another action, so no "every overlapping rule has the same action" claim is made after it there.
        Set<PolicyUnionCover.Earlier> gaps = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(meta, section)) continue;
            PolicyUnionCover.Earlier earlier = everyRule == null ? scope.advance(section) : null;
            for (Rule rule : section.rules()) {
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                var a = hygiene.rules().get(rule.id());
                var cover = union.rules().get(rule.id());
                boolean subject = a != null && !Set.of("REDUNDANT", "CONFLICT").contains(a.shadowStatus())
                    && !(cover != null && PolicyUnionCover.COVERED.equals(cover.status()));
                if (everyRule != null) { if (subject) notEvaluable.merge(everyRule, 1, Integer::sum); continue; }
                budget.rule(ruleLimit);
                Match match;
                try { match = normalize(snapshot, section, rule, resolver); }
                catch (Unknown limit) {
                    // Out of budget before the rule was read: it is no earlier box for anyone, and as a subject not proven.
                    gaps.add(earlier);
                    if (subject) { rules.put(rule.id(), new Partial(NOT_PROVEN, BUDGET, List.of(), false)); notProven++; }
                    continue;
                }
                if (match.problem() != null) { if (subject) notEvaluable.merge(PolicyUnionCover.NOT_RESOLVED, 1, Integer::sum); continue; }
                Box box = Box.of(match.selectors());
                if (box == null) { if (subject) notEvaluable.merge(PolicyUnionCover.OPAQUE, 1, Integer::sum); continue; }
                if (subject && scope.unproven) notEvaluable.merge(PolicyUnionCover.ORDER_UNPROVEN, 1, Integer::sum);
                else if (subject) {
                    evaluated++;
                    var verdict = partial(rule, match, box, earlier, gaps.contains(earlier), budget);
                    if (verdict != null) {
                        rules.put(rule.id(), verdict);
                        if (verdict.status().equals(NOT_PROVEN)) notProven++; else if (verdict.sameAction()) partial++; else differentAction++;
                    }
                }
                earlier.add(match, box);
            }
        }
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String reason : NOT_EVALUABLE_REASONS) if (notEvaluable.containsKey(reason)) ordered.put(reason, notEvaluable.get(reason));
        return new Result(Collections.unmodifiableMap(rules), evaluated, partial, differentAction, notProven, Collections.unmodifiableMap(ordered));
    }

    /**
     * PARTIAL, NOT_PROVEN, or null: no earlier rule intersects the subject, or the earlier rules together cover it all.
     * {@code gap}: an earlier rule of the scope was never read for lack of budget, so a PARTIAL cannot be told from a cover
     * or from a different-action overlap and is NOT_PROVEN.
     */
    private static Partial partial(Rule rule, Match match, Box box, PolicyUnionCover.Earlier earlier, boolean gap, Budget budget) {
        try {
            List<Match> found = new ArrayList<>(); List<Box> foundBoxes = new ArrayList<>();
            var order = earlier.candidates(match, box.spans(), found, foundBoxes, budget);
            // The span test is only necessary: keep the earlier rules whose boxes really share a point with the subject.
            List<Box> boxes = new ArrayList<>(); List<Rule> overlapping = new ArrayList<>();
            for (int i : order) {
                if (!intersects(box, foundBoxes.get(i), budget)) continue;
                boxes.add(foundBoxes.get(i)); overlapping.add(found.get(i).rule());
            }
            if (overlapping.isEmpty()) return null;
            var residual = PolicyRegion.residual(box, boxes, budget);
            if (residual.status() == PolicyRegion.Status.NOT_PROVEN) return new Partial(NOT_PROVEN, BUDGET, List.of(), false);
            if (residual.covered()) return null;
            if (gap) return new Partial(NOT_PROVEN, BUDGET, List.of(), false);
            boolean same = true;
            for (Rule c : overlapping) if (!sameAction(c, rule)) { same = false; break; }
            return new Partial(PARTIAL, null, List.copyOf(overlapping), same);
        } catch (Unknown limit) {
            return new Partial(NOT_PROVEN, BUDGET, List.of(), false);
        }
    }

    /** Whether two rules have the same action; an Apply Layer rule, on either side, is never the same. */
    static boolean sameAction(Rule earlier, Rule subject) {
        return !isApplyLayer(earlier) && !isApplyLayer(subject) && PolicyHygiene.sameAction(earlier, subject);
    }
    static boolean isApplyLayer(Rule rule) {
        return "Apply Layer".equals(rule.action()) || !rule.extras().getOrDefault("inline-layer", List.of()).isEmpty();
    }

    /** The two boxes share at least one point: every dimension's intersection is non-empty. */
    static boolean intersects(Box a, Box b, Budget budget) {
        for (int i = 0; i < PolicyRegion.DIMENSIONS; i++)
            if (PolicyRegion.intersect(a.dims().get(i), b.dims().get(i), budget).empty()) return false;
        return true;
    }
}
