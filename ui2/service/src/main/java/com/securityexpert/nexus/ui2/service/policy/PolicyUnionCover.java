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
    static final Set<String> SUPPORTED_VENDORS = Set.of("CP", "PAN");
    private static final Set<String> ANY_ZONE = Set.of("any");

    /** COVERED with its covering rules and their action relation, or NOT_PROVEN with {@link #BUDGET}. */
    record Cover(String status, String reason, List<Rule> covering, String coverAction) {}
    /**
     * Per rule a COVERED or NOT_PROVEN verdict; the counts: subject rules evaluated (a residual was computed), covered,
     * not proven, and not evaluable by reason. A subject is an enabled rule the shadow engine did not find REDUNDANT or
     * CONFLICT.
     */
    record Result(Map<String, Cover> rules, int evaluated, int covered, int notProven, Map<String, Integer> notEvaluable) {}

    /**
     * A Palo Alto placement that is empty or mixes {@code any} with named zones has no set interpretation
     * ({@link PolicyNormalization#zoneHolds}) and is treated fail-closed as opaque.
     */
    static boolean mixedPanZones(Metadata meta, Match match) {
        if (!"PAN".equals(meta.vendor())) return false;
        for (String key : List.of("from", "to")) {
            var zones = match.placements().getOrDefault(key, Set.of());
            if (zones.isEmpty() || zones.contains("any") && zones.size() > 1) return true;
        }
        return false;
    }

    /** Whether {@code prior}'s placement covers every target/zone pair of {@code later} (exact install-on for CP; zone superset for PAN). */
    static boolean placementCovers(Metadata meta, Match prior, Match later) {
        if ("PAN".equals(meta.vendor())) {
            return prior.placements().get("install-on").equals(later.placements().get("install-on"))
                && zonesCover(prior.placements().get("from"), prior.placements().get("to"),
                    later.placements().get("from"), later.placements().get("to"), false);
        }
        return compatible(prior, later) && compatible(later, prior);
    }

    /**
     * The readable earlier rules of one evaluation scope, bucketed by exact placement key (from, to, install-on) with each
     * bucket's box spans packed in one array, so Check Point reads only the up to four {@link PolicyNormalization#compatible}
     * keys and Palo Alto reads zone-covering or non-disjoint buckets while rejecting priors on packed spans before box work.
     */
    static final class Earlier {
        private record Key(Set<String> from, Set<String> to, Set<String> installOn) {}
        private static final class Bucket {
            final Key key;
            final Map<String, Set<String>> placements;
            final List<Match> matches = new ArrayList<>(); final List<Box> boxes = new ArrayList<>(); final List<Integer> orders = new ArrayList<>();
            long[] spans = new long[8 * 2 * PolicyRegion.DIMENSIONS];
            Bucket(Key key) {
                this.key = key;
                this.placements = Map.of("from", key.from(), "to", key.to(), "install-on", key.installOn());
            }
        }
        /** Inverted zone index for PAN buckets (every added PAN bucket has either {@code any} or named zones only). */
        private static final class ZoneIndex {
            final List<Bucket> any = new ArrayList<>();
            final Map<String, List<Bucket>> byZone = new HashMap<>();
            void add(Set<String> zones, Bucket bucket) {
                if (zones.equals(ANY_ZONE)) any.add(bucket);
                else for (String z : zones) byZone.computeIfAbsent(z, k -> new ArrayList<>()).add(bucket);
            }
            List<List<Bucket>> holders(Set<String> slice, Budget budget) {
                budget.spend();
                if (slice.equals(ANY_ZONE)) return any.isEmpty() ? List.of() : List.of(any);
                String z = slice.iterator().next();
                var named = byZone.get(z);
                if (any.isEmpty()) return named == null ? List.of() : List.of(named);
                return named == null ? List.of(any) : List.of(any, named);
            }
            List<List<Bucket>> overlappers(Set<String> zones, List<Bucket> all, Budget budget) {
                if (zones.equals(ANY_ZONE)) {
                    budget.spend();
                    return all.isEmpty() ? List.of() : List.of(all);
                }
                List<List<Bucket>> out = new ArrayList<>(zones.size() + 1);
                if (!any.isEmpty()) out.add(any);
                for (String z : zones) {
                    budget.spend();
                    var named = byZone.get(z);
                    if (named != null) out.add(named);
                }
                return out;
            }
        }
        private static final class Targets { final ZoneIndex from = new ZoneIndex(), to = new ZoneIndex(); }
        private final Map<Key, Bucket> buckets = new LinkedHashMap<>();
        private final List<Bucket> allBuckets = new ArrayList<>();
        private final Map<Set<String>, Targets> byInstallOn = new HashMap<>();
        private final ZoneIndex allFrom = new ZoneIndex(), allTo = new ZoneIndex();
        private int count;
        private static Key key(Match m) { return new Key(m.placements().get("from"), m.placements().get("to"), m.placements().get("install-on")); }
        private static int totalSize(List<List<Bucket>> lists) { int n = 0; for (var l : lists) n += l.size(); return n; }
        void add(Match match, Box box) {
            var bucket = buckets.computeIfAbsent(key(match), k -> {
                var b = new Bucket(k);
                allBuckets.add(b);
                var targets = byInstallOn.computeIfAbsent(k.installOn(), t -> new Targets());
                targets.from.add(k.from(), b); targets.to.add(k.to(), b);
                allFrom.add(k.from(), b); allTo.add(k.to(), b);
                return b;
            });
            int n = bucket.matches.size(), width = 2 * PolicyRegion.DIMENSIONS;
            if ((n + 1) * width > bucket.spans.length) bucket.spans = Arrays.copyOf(bucket.spans, bucket.spans.length * 2);
            System.arraycopy(box.spans(), 0, bucket.spans, n * width, width);
            bucket.matches.add(match); bucket.boxes.add(box); bucket.orders.add(count++);
        }
        /** The compatible earlier rules whose spans meet the subject's, in rule order; one budget unit per prior read. */
        List<Integer> candidates(Match match, long[] spans, List<Match> matches, List<Box> boxes, Budget budget) {
            var key = key(match);
            var lookups = new LinkedHashSet<Key>();
            for (var from : List.of(key.from(), ANY_ZONE)) for (var to : List.of(key.to(), ANY_ZONE)) lookups.add(new Key(from, to, key.installOn()));
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
        /** Palo Alto: earlier rules whose zones cover {@code (sliceFrom, sliceTo)} with equal install-on and overlapping spans, in rule order. */
        List<Integer> panSliceCandidates(Set<String> sliceFrom, Set<String> sliceTo, Set<String> installOn, long[] spans,
                List<Match> matches, List<Box> boxes, List<Integer> globalOrders, Budget budget) {
            var targets = byInstallOn.get(installOn);
            if (targets == null) { budget.spend(); return List.of(); }
            var fromLists = targets.from.holders(sliceFrom, budget);
            var toLists = targets.to.holders(sliceTo, budget);
            var chosen = totalSize(fromLists) <= totalSize(toLists) ? fromLists : toLists;
            TreeMap<Integer, Integer> found = new TreeMap<>();
            int width = 2 * PolicyRegion.DIMENSIONS;
            for (var list : chosen) {
                for (Bucket bucket : list) {
                    budget.spend();
                    if (!zonesCover(bucket.key.from(), bucket.key.to(), sliceFrom, sliceTo, false)) continue;
                    for (int j = 0, n = bucket.matches.size(); j < n; j++) {
                        budget.spend();
                        if (!overlaps(bucket.spans, j * width, spans)) continue;
                        found.put(bucket.orders.get(j), matches.size());
                        matches.add(bucket.matches.get(j));
                        boxes.add(bucket.boxes.get(j));
                        globalOrders.add(bucket.orders.get(j));
                    }
                }
            }
            return List.copyOf(found.values());
        }
        /** Palo Alto: earlier rules whose placements are not disjoint from {@code match}'s and whose spans overlap, in rule order. */
        List<Integer> panOverlapCandidates(Match match, long[] spans, Map<String, PolicyObject> objects,
                List<Match> matches, List<Box> boxes, Budget budget) {
            var fromLists = allFrom.overlappers(match.placements().get("from"), allBuckets, budget);
            var toLists = allTo.overlappers(match.placements().get("to"), allBuckets, budget);
            var chosen = totalSize(fromLists) <= totalSize(toLists) ? fromLists : toLists;
            Set<Bucket> seen = chosen.size() > 1 ? Collections.newSetFromMap(new IdentityHashMap<>()) : null;
            TreeMap<Integer, Integer> found = new TreeMap<>();
            int width = 2 * PolicyRegion.DIMENSIONS;
            for (var list : chosen) {
                for (Bucket bucket : list) {
                    budget.spend();
                    if (seen != null && !seen.add(bucket)) continue;
                    if (disjoint(bucket.placements, match.placements(), objects)) continue;
                    for (int j = 0, n = bucket.matches.size(); j < n; j++) {
                        budget.spend();
                        if (!overlaps(bucket.spans, j * width, spans)) continue;
                        found.put(bucket.orders.get(j), matches.size());
                        matches.add(bucket.matches.get(j));
                        boxes.add(bucket.boxes.get(j));
                    }
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
        String everyRule = !SUPPORTED_VENDORS.contains(meta.vendor()) ? UNSUPPORTED_VENDOR : !snapshot.failures().isEmpty() ? INCOMPLETE : null;
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
                Box box = mixedPanZones(meta, match) ? null : Box.of(match.selectors());
                if (box == null) { if (subject) notEvaluable.merge(OPAQUE, 1, Integer::sum); continue; }
                if (subject && scope.unproven) notEvaluable.merge(ORDER_UNPROVEN, 1, Integer::sum);
                else if (subject) {
                    evaluated++;
                    var cover = cover(meta, rule, match, box, earlier, budget);
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
    private static Cover cover(Metadata meta, Rule rule, Match match, Box box, Earlier earlier, Budget budget) {
        if ("PAN".equals(meta.vendor())) return panCover(meta, rule, match, box, earlier, budget);
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
            return coveredVerdict(rule, residual.contributors().stream().map(candidates::get).toList());
        } catch (Unknown limit) {
            return new Cover(NOT_PROVEN, BUDGET, List.of(), null);
        }
    }

    private record Prior(Match match, Box box) {}

    private static Cover panCover(Metadata meta, Rule rule, Match match, Box box, Earlier earlier, Budget budget) {
        try {
            var subFrom = match.placements().get("from");
            var subTo = match.placements().get("to");
            var subInstall = match.placements().get("install-on");
            List<Set<String>> fromSlices = zoneSlices(subFrom), toSlices = zoneSlices(subTo);
            boolean multiSlice = fromSlices.size() > 1 || toSlices.size() > 1;
            long[] spans = box.spans();
            TreeMap<Integer, Prior> contributors = new TreeMap<>();
            for (Set<String> sf : fromSlices) {
                for (Set<String> st : toSlices) {
                    budget.spend();
                    List<Match> found = new ArrayList<>(); List<Box> foundBoxes = new ArrayList<>(); List<Integer> foundOrders = new ArrayList<>();
                    var order = earlier.panSliceCandidates(sf, st, subInstall, spans, found, foundBoxes, foundOrders, budget);
                    if (order.size() < (multiSlice ? 1 : 2)) return null;
                    List<Box> boxes = new ArrayList<>(order.size());
                    List<Match> sliceMatches = new ArrayList<>(order.size());
                    List<Integer> sliceOrders = new ArrayList<>(order.size());
                    for (int i : order) {
                        boxes.add(foundBoxes.get(i));
                        sliceMatches.add(found.get(i));
                        sliceOrders.add(foundOrders.get(i));
                    }
                    var residual = PolicyRegion.residual(box, boxes, budget);
                    if (residual.status() == PolicyRegion.Status.NOT_PROVEN) return new Cover(NOT_PROVEN, BUDGET, List.of(), null);
                    if (!residual.covered()) return null;
                    for (int k : residual.contributors()) {
                        contributors.putIfAbsent(sliceOrders.get(k), new Prior(sliceMatches.get(k), boxes.get(k)));
                    }
                }
            }
            if (contributors.size() < 2) return null;
            for (Prior prior : contributors.values()) {
                if (placementCovers(meta, prior.match(), match) && PolicyRegion.minus(box, prior.box(), budget).isEmpty()) return null;
            }
            return coveredVerdict(rule, contributors.values().stream().map(p -> p.match().rule()).toList());
        } catch (Unknown limit) {
            return new Cover(NOT_PROVEN, BUDGET, List.of(), null);
        }
    }

    private static List<Set<String>> zoneSlices(Set<String> zones) {
        if (zones.equals(ANY_ZONE)) return List.of(ANY_ZONE);
        List<Set<String>> out = new ArrayList<>(zones.size());
        for (String z : new TreeSet<>(zones)) out.add(Set.of(z));
        return out;
    }

    private static Cover coveredVerdict(Rule subject, List<Rule> covering) {
        boolean different = false, inline = false;
        for (Rule c : covering) {
            if (PolicyHygiene.sameAction(c, subject)) continue;
            if (PolicyHygiene.applyLayer(c)) inline = true; else different = true;
        }
        return new Cover(COVERED, null, covering, different ? DIFFERENT : inline ? INLINE_LAYER : SAME);
    }

    /** Every dimension's [lo, hi] span meets: a necessary condition for two boxes to share a point. */
    private static boolean overlaps(long[] packed, int at, long[] b) {
        for (int i = 0; i < b.length; i += 2) if (packed[at + i + 1] < b[i] || b[i + 1] < packed[at + i]) return false;
        return true;
    }
}
