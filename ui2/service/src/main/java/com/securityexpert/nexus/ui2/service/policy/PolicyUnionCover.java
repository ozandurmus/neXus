package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyRegion.Box;
import java.util.*;

/**
 * Insight I3 of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN), phase P2 step a, Check Point only: a rule whose whole
 * match is covered by the union of several earlier rules of the same layer, so it is never reached. One earlier rule
 * covering it alone is I1/I2 ({@link PolicyHygiene} REDUNDANT/CONFLICT) and is left to that engine. Pure: reads the
 * snapshot and the hygiene result, contacts nothing and stores nothing.
 *
 * <p>The rules it keeps, each one a fixed reading of the design and its decision record:
 * <ul>
 * <li><b>Same scope, never across a layer (Q9).</b> Earlier rules come from the shadow engine's own
 * {@link EvaluationScope}: one state per Check Point ordered layer and per inline layer; sections of a layer continue it.
 * A section whose place in the order is not proven ("earlier sections not analysed") evaluates no subject.</li>
 * <li><b>Anchors (Q3).</b> A rule the engine cannot read -- a user, application or time constraint, an unresolved
 * object, anything {@link PolicyNormalization#normalize} leaves with a problem -- is never part of a cover and is never a
 * subject. It cannot weaken a proven cover: a cover says every packet of the subject is decided by some earlier resolved
 * rule, whatever the unreadable rules between them do.</li>
 * <li><b>Placement.</b> Only an earlier rule whose placement is {@link PolicyNormalization#compatible} with the subject's
 * (Check Point: the same install-on references) may take part, exactly as for a single-rule cover.</li>
 * <li><b>G1 with a budget (Q8).</b> The subject's box minus the earlier boxes ({@link PolicyRegion#residual}). An empty
 * residual is COVERED (insight grade PROVEN); running out of work is NOT_PROVEN (insight grade NOT_EVALUABLE, reason
 * "analysis budget"), never a cover.</li>
 * <li><b>Rule-complete snapshot (design 3.1).</b> A snapshot with collection failures evaluates nothing.</li>
 * </ul>
 * The covering set is the earlier rules that took a non-empty part of the subject, in rule order. Their actions against
 * the subject's ({@link PolicyHygiene#sameAction}) give {@link #SAME}, {@link #DIFFERENT} (intent is a human question, as
 * for I2) or {@link #INLINE_LAYER} (an Apply Layer rule takes part: the rule is never reached, the inline verdict is not
 * analysed).
 */
final class PolicyUnionCover {
    private PolicyUnionCover() {}
    /** The shadow engine's limits, in the same {@link Budget} units: per subject rule, and for the whole snapshot. */
    static final int RULE_WORK = PolicyHygiene.RULE_WORK, TOTAL_WORK = PolicyHygiene.TOTAL_WORK;
    static final String COVERED = "COVERED", NOT_PROVEN = "NOT_PROVEN";
    static final String SAME = "SAME", DIFFERENT = "DIFFERENT", INLINE_LAYER = "INLINE_LAYER";
    static final Set<String> COVER_ACTIONS = Set.of(SAME, DIFFERENT, INLINE_LAYER);
    static final String BUDGET = "analysis budget";
    /** Why a rule was not evaluated (NOT_EVALUABLE), a closed set in display order. */
    static final String UNSUPPORTED_VENDOR = "unsupported rulebase", INCOMPLETE = "snapshot collection incomplete",
        ORDER_UNPROVEN = "earlier sections not analysed", NOT_RESOLVED = "rule not resolved", OPAQUE = "opaque selector";
    static final List<String> NOT_EVALUABLE_REASONS = List.of(UNSUPPORTED_VENDOR, INCOMPLETE, ORDER_UNPROVEN, NOT_RESOLVED, OPAQUE);

    /** COVERED with its covering rules and their action relation, or NOT_PROVEN with {@link #BUDGET}. */
    record Cover(String status, String reason, List<Rule> covering, String coverAction) {}
    /**
     * Per rule a COVERED or NOT_PROVEN verdict; the counts: subject rules evaluated (a residual was computed), covered,
     * not proven, and not evaluable by reason. A subject is an enabled rule the shadow engine did not find REDUNDANT or
     * CONFLICT.
     */
    record Result(Map<String, Cover> rules, int evaluated, int covered, int notProven, Map<String, Integer> notEvaluable) {}

    /**
     * The readable earlier rules of one evaluation scope, bucketed by exact placement key (from, to, install-on) with each
     * bucket's box spans packed in one array, so a subject reads only the up to four keys {@link PolicyNormalization#compatible}
     * can accept (its own from/to or any, the same install-on) and rejects a prior on its spans before any box work.
     */
    private static final class Earlier {
        private record Key(Set<String> from, Set<String> to, Set<String> installOn) {}
        private static final class Bucket {
            final List<Match> matches = new ArrayList<>(); final List<Box> boxes = new ArrayList<>(); final List<Integer> orders = new ArrayList<>();
            long[] spans = new long[8 * 2 * PolicyRegion.DIMENSIONS];
        }
        private final Map<Key, Bucket> buckets = new HashMap<>();
        private int count;
        private static Key key(Match m) { return new Key(m.placements().get("from"), m.placements().get("to"), m.placements().get("install-on")); }
        void add(Match match, Box box) {
            var bucket = buckets.computeIfAbsent(key(match), k -> new Bucket());
            int n = bucket.matches.size(), width = 2 * PolicyRegion.DIMENSIONS;
            if ((n + 1) * width > bucket.spans.length) bucket.spans = Arrays.copyOf(bucket.spans, bucket.spans.length * 2);
            System.arraycopy(box.spans(), 0, bucket.spans, n * width, width);
            bucket.matches.add(match); bucket.boxes.add(box); bucket.orders.add(count++);
        }
        /** The compatible earlier rules whose spans meet the subject's, in rule order; one budget unit per prior read. */
        List<Integer> candidates(Match match, long[] spans, List<Match> matches, List<Box> boxes, Budget budget) {
            var key = key(match);
            var lookups = new LinkedHashSet<Key>();
            for (var from : List.of(key.from(), Set.of("any"))) for (var to : List.of(key.to(), Set.of("any"))) lookups.add(new Key(from, to, key.installOn()));
            TreeMap<Integer, Integer> found = new TreeMap<>();
            int width = 2 * PolicyRegion.DIMENSIONS;
            for (Key lookup : lookups) {
                var bucket = buckets.get(lookup);
                if (bucket == null) continue;
                for (int j = 0, n = bucket.matches.size(); j < n; j++) {
                    budget.spend();
                    if (!overlaps(bucket.spans, j * width, spans) || !compatible(bucket.matches.get(j), match)) continue;
                    found.put(bucket.orders.get(j), matches.size());
                    matches.add(bucket.matches.get(j)); boxes.add(bucket.boxes.get(j));
                }
            }
            return List.copyOf(found.values());
        }
    }

    static Result analyze(PolicySnapshot snapshot, PolicyHygiene.Result hygiene) { return analyze(snapshot, hygiene, TOTAL_WORK, RULE_WORK); }

    static Result analyze(PolicySnapshot snapshot, PolicyHygiene.Result hygiene, int workLimit, int ruleLimit) {
        var meta = snapshot.metadata();
        Map<String, Cover> rules = new LinkedHashMap<>();
        Map<String, Integer> notEvaluable = new LinkedHashMap<>();
        int evaluated = 0, covered = 0, notProven = 0;
        String everyRule = !"CP".equals(meta.vendor()) ? UNSUPPORTED_VENDOR : !snapshot.failures().isEmpty() ? INCOMPLETE : null;
        Budget budget = new Budget(workLimit);
        var resolver = Resolver.shadowing(snapshot, budget);
        var scope = new EvaluationScope<Earlier>(meta, Earlier::new);
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(meta, section)) continue;
            Earlier earlier = everyRule == null ? scope.advance(section) : null;
            for (Rule rule : section.rules()) {
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                var a = hygiene.rules().get(rule.id());
                boolean subject = a != null && !Set.of("REDUNDANT", "CONFLICT").contains(a.shadowStatus());
                if (everyRule != null) { if (subject) notEvaluable.merge(everyRule, 1, Integer::sum); continue; }
                budget.rule(ruleLimit);
                Match match;
                try { match = normalize(snapshot, section, rule, resolver); }
                catch (Unknown limit) {
                    // Out of budget before the rule was read: it is no earlier box for anyone, and as a subject not proven.
                    if (subject) { rules.put(rule.id(), new Cover(NOT_PROVEN, BUDGET, List.of(), null)); notProven++; }
                    continue;
                }
                if (match.problem() != null) { if (subject) notEvaluable.merge(NOT_RESOLVED, 1, Integer::sum); continue; }
                Box box = Box.of(match.selectors());
                if (box == null) { if (subject) notEvaluable.merge(OPAQUE, 1, Integer::sum); continue; }
                if (subject && scope.unproven) notEvaluable.merge(ORDER_UNPROVEN, 1, Integer::sum);
                else if (subject) {
                    evaluated++;
                    var cover = cover(rule, match, box, earlier, budget);
                    if (cover != null) {
                        rules.put(rule.id(), cover);
                        if (cover.status().equals(COVERED)) covered++; else notProven++;
                    }
                }
                earlier.add(match, box);
            }
        }
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String reason : NOT_EVALUABLE_REASONS) if (notEvaluable.containsKey(reason)) ordered.put(reason, notEvaluable.get(reason));
        return new Result(Collections.unmodifiableMap(rules), evaluated, covered, notProven, Collections.unmodifiableMap(ordered));
    }

    /** COVERED by two or more earlier rules, NOT_PROVEN, or null: not covered, or covered by one earlier rule alone (I1/I2). */
    private static Cover cover(Rule rule, Match match, Box box, Earlier earlier, Budget budget) {
        try {
            List<Match> found = new ArrayList<>(); List<Box> foundBoxes = new ArrayList<>();
            var order = earlier.candidates(match, box.spans(), found, foundBoxes, budget);
            if (order.size() < 2) return null;
            List<Box> boxes = new ArrayList<>(); List<Rule> candidates = new ArrayList<>();
            for (int i : order) { boxes.add(foundBoxes.get(i)); candidates.add(found.get(i).rule()); }
            var residual = PolicyRegion.residual(box, boxes, budget);
            if (residual.status() == PolicyRegion.Status.NOT_PROVEN) return new Cover(NOT_PROVEN, BUDGET, List.of(), null);
            if (!residual.covered() || residual.contributors().size() < 2) return null;
            for (int k : residual.contributors()) if (PolicyRegion.minus(box, boxes.get(k), budget).isEmpty()) return null;
            List<Rule> covering = residual.contributors().stream().map(candidates::get).toList();
            boolean different = false, inline = false;
            for (Rule c : covering) {
                if (PolicyHygiene.sameAction(c, rule)) continue;
                if (PolicyHygiene.applyLayer(c)) inline = true; else different = true;
            }
            return new Cover(COVERED, null, covering, different ? DIFFERENT : inline ? INLINE_LAYER : SAME);
        } catch (Unknown limit) {
            return new Cover(NOT_PROVEN, BUDGET, List.of(), null);
        }
    }

    /** Every dimension's [lo, hi] span meets: a necessary condition for two boxes to share a point. */
    private static boolean overlaps(long[] packed, int at, long[] b) {
        for (int i = 0; i < b.length; i += 2) if (packed[at + i + 1] < b[i] || b[i + 1] < packed[at + i]) return false;
        return true;
    }
}
