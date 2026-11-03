package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.time.*;
import java.util.*;

/** Pure stored-evidence analysis over proven, ordered evaluation scopes. */
public final class PolicyHygiene {
    private PolicyHygiene() {}
    public static final Set<String> CLASSES = Set.of("shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown");
    public static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    /** Every shadow reason this class (and PolicyNormalization) can emit; PolicyPrivacy projects these unchanged, so a new reason must be added here. */
    public static final Set<String> REASONS = Set.of(
        "analysis budget", "unsupported rulebase", "negated selector", "missing selector", "enabled state is unknown",
        "non-terminal or unknown action", "time or content constraint", "user or category constraint", "zone rule type constraint",
        "unresolved install-on selector", "unsupported rule constraint", "time evidence not collected", "unresolved object",
        "group cycle or depth limit", "empty or dynamic group", "dynamic or unsupported object type", "unsupported address representation",
        "missing address values", "unsupported object members", "ambiguous object values", "unsupported service constraint",
        "unsupported port expression", "invalid port range", "missing service values", "earlier rule has unsupported constraints",
        "zone or install-on coverage is unproven", "zone evidence not collected", "install-on evidence not collected", "snapshot collection incomplete",
        "earlier sections not analysed", "multiple ordered layers not evaluated");
    /**
     * Evidence for a rule fully covered by an earlier Check Point "Apply Layer" rule: the rule is never reached, but the
     * inline layer's own verdict is not analysed, so the actions are not comparable and this is never a CONFLICT.
     */
    public static final String INLINE_LAYER_COVER = "An earlier Apply Layer rule sends all of this match into its inline layer; this rule is never reached.";
    /** Every fixed finding evidence string; "Shadowing: <reason>." is derived from {@link #REASONS}. */
    public static final Set<String> EVIDENCE = Set.of(
        "Rule is disabled.", "Enabled state is unknown.", "Stored schedule has expired.", "Stored schedule expires within 14 days.",
        "Schedule evidence is incomplete.", "Permissiveness cannot be established from resolved selectors.",
        "Accept rule matches any source, destination, service and application.", "Broad selectors are present; see permissiveness reasons.",
        "Earlier enabled rule fully covers this match with a different action.", "Earlier enabled rule fully covers this match with the same action.",
        PolicyHygiene.INLINE_LAYER_COVER,
        "Hit counts were not collected or are invalid.", "Hit counts are missing for a target.", "Last-hit timestamp is invalid.",
        "Zero-hit counter window is shorter than the threshold.", "Zero hits contradict a recorded last hit.",
        "Zero collected hits; counter reset/window is unknown.", "Last-hit time was not collected.",
        "Last hit predates the threshold at counter collection time.");
    public record Finding(String findingClass, String severity, String evidence) {}
    public record Assessment(String shadowStatus, String shadowReason, String coveringRuleId,
            List<Finding> findings, Map<String, Object> permissiveness, String timeStatus, boolean expiring,
            String counterWindow, String hitSource, String hitsCollectedAt) {}
    public record Result(Map<String, Assessment> rules, boolean budgetReached) {}
    /**
     * Shadowing work limits in {@link Budget} units (one object resolution, range step or candidate comparison each).
     * A rule gets {@link #RULE_WORK} units of its own: a rule that exhausts them is UNKNOWN "analysis budget" alone and
     * later rules keep going. The snapshot stops at {@link #TOTAL_WORK}. Measured on a laptop JVM (2026-11-03) a unit
     * costs about 7 ns when candidate comparisons dominate and 20-25 ns when range walks or object resolution do, so the
     * cap bounds shadowing to roughly 1.5-5 CPU seconds whatever the input, and RULE_WORK one rule to 7-25 ms. A fully
     * resolvable policy reaches the cap only beyond about 20,000 rules sharing one placement key (every rule then
     * compared with every earlier rule of that key); PolicyHygieneScaleTest pins the 10,000-rule runtime.
     */
    public static final int RULE_WORK = 1_000_000, TOTAL_WORK = 200_000_000;
    public static Result analyze(PolicySnapshot snapshot, int days, Instant now, int workLimit) {
        return analyze(snapshot, days, now, workLimit, snapshot.objects());
    }
    /**
     * {@code breadthObjects} is the object map the permissiveness verdict reads: the snapshot objects, optionally with
     * Check Point group members the rulebase dictionary did not carry joined from the same domain's object inventory.
     * Shadowing keeps reading the snapshot objects only (the inventory is collected separately and may be newer).
     * Permissiveness and schedule status are per-rule properties with their own per-rule budget, so the snapshot-wide
     * shadowing budget can no longer turn them into "Analysis budget reached".
     */
    public static Result analyze(PolicySnapshot snapshot, int days, Instant now, int workLimit, Map<String, PolicyObject> breadthObjects) {
        return analyze(snapshot, days, now, workLimit, RULE_WORK, breadthObjects);
    }
    /** {@code workLimit} caps the whole snapshot's shadowing work, {@code ruleLimit} each rule's own. */
    static Result analyze(PolicySnapshot snapshot, int days, Instant now, int workLimit, int ruleLimit, Map<String, PolicyObject> breadthObjects) {
        if (days < 1 || days > 36500 || workLimit < 0 || ruleLimit < 0) throw new IllegalArgumentException("Invalid hygiene limits");
        Budget budget = new Budget(workLimit);
        var resolver = new Resolver(snapshot.objects(), budget);
        Map<String, Assessment> results = new LinkedHashMap<>();
        // One Earlier per evaluation scope; a Check Point parent layer's Earlier resumes after its inline layers.
        var scope = new EvaluationScope<Earlier>(snapshot.metadata(), () -> new Earlier(snapshot.objects()));
        boolean budgetReached = false;
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.panNonSecurity(snapshot.metadata(), section)) {
                // Palo Alto NAT and default-rule sections are stored, not analysed: no shadowing, permissiveness or
                // hit-based finding is invented for them, and they do not disturb the security rule ordering.
                for (Rule rule : section.rules()) {
                    List<Finding> findings = Boolean.FALSE.equals(rule.enabled()) ? List.of(finding("disabled", "LOW", "Rule is disabled.")) : List.of();
                    results.put(rule.id(), new Assessment("UNKNOWN", "unsupported rulebase", null, findings,
                        Map.of("level", "Unknown", "reasons", List.of(), "score", 0), "unknown", false,
                        "Counter window unknown.", "UNKNOWN", null));
                }
                continue;
            }
            Earlier earlier = scope.advance(section);
            for (Rule rule : section.rules()) {
                List<Finding> findings = new ArrayList<>();
                String status = "UNKNOWN", reason = "analysis budget", covering = null;
                if (Boolean.FALSE.equals(rule.enabled())) findings.add(finding("disabled", "LOW", "Rule is disabled."));
                else if (rule.enabled() == null) findings.add(unknown("Enabled state is unknown."));
                Map<String, Object> broad = PolicyRuleMetrics.permissiveness(rule, breadthObjects, new int[]{PolicyRuleMetrics.RULE_BUDGET}, snapshot.metadata().vendor());
                var joins = breadthObjects == snapshot.objects() ? Set.<String>of() : joined(rule, snapshot.objects(), breadthObjects);
                if (!joins.isEmpty()) {
                    var reasons = new ArrayList<Object>((List<?>) broad.get("reasons")); reasons.addAll(joins);
                    broad = Map.of("level", broad.get("level"), "reasons", List.copyOf(reasons), "score", broad.get("score"));
                }
                var time = PolicyRuleMetrics.time(rule, snapshot.objects(), now, snapshot.metadata().vendor(), new int[]{PolicyRuleMetrics.RULE_BUDGET});
                String timeStatus = time.status(); boolean expiring = time.expiring();
                if (timeStatus.equals("expired")) findings.add(finding("expired", "LOW", "Stored schedule has expired."));
                else if (expiring) findings.add(finding("expired", "LOW", "Stored schedule expires within 14 days."));
                else if (timeStatus.equals("unknown")) findings.add(unknown("Schedule evidence is incomplete."));
                if (broad.get("level").equals("Unknown")) findings.add(unknown("Permissiveness cannot be established from resolved selectors."));
                else if (!broad.get("level").equals("Low")) {
                    var reasons = (List<?>) broad.get("reasons");
                    boolean anyAccept = accepting(rule.action()) && reasons.containsAll(List.of("Any source", "Any destination", "Any service"))
                        && (reasons.contains("Any application") || snapshot.metadata().vendor().equals("CP") && rule.application().refs().isEmpty());
                    findings.add(finding("broad", anyAccept ? "HIGH" : "MEDIUM", anyAccept
                        ? "Accept rule matches any source, destination, service and application."
                        : "Broad selectors are present; see permissiveness reasons."));
                }
                Match match = null;
                try {
                    budget.rule(ruleLimit);
                    budget.spend();
                    match = normalize(snapshot, section, rule, resolver);
                    budget.spend();
                    reason = match.problem();
                    if (scope.unproven) reason = "earlier sections not analysed";
                    if (reason == null) {
                        status = "NOT_SHADOWED";
                        if (earlier.unknown) reason = "earlier rule has unsupported constraints";
                        Match prior = earlier.cover(match, budget);
                        if (prior != null) {
                            covering = prior.rule().id();
                            boolean same = sameAction(prior.rule(), rule);
                            // An earlier Apply Layer rule makes this rule unreachable, but its inline layer's verdict is not
                            // analysed: never reached (REDUNDANT), never an action conflict.
                            boolean inlineCover = !same && applyLayer(prior.rule());
                            status = same || inlineCover ? "REDUNDANT" : "CONFLICT";
                            reason = null;
                            findings.add(finding(status.equals("CONFLICT") ? "conflict" : "shadowed", status.equals("CONFLICT") ? "HIGH" : "MEDIUM",
                                status.equals("CONFLICT") ? "Earlier enabled rule fully covers this match with a different action."
                                    : inlineCover ? INLINE_LAYER_COVER : "Earlier enabled rule fully covers this match with the same action."));
                        } else if (earlier.unproven(match, budget)) reason = "zone or install-on coverage is unproven";
                        if (covering == null && reason != null) status = "UNKNOWN";
                    }
                } catch (Unknown limit) { status = "UNKNOWN"; reason = limit.getMessage(); budgetReached = true; }
                // A rule that ran out of budget still constrains later rules: a resolved match stays a coverage
                // candidate and an unresolved one counts as an earlier unknown -- never silently dropped (fail-closed).
                if (!Boolean.FALSE.equals(rule.enabled())) {
                    if (match != null && match.problem() == null) earlier.add(match);
                    else earlier.unknown = true;
                }
                if (status.equals("NOT_SHADOWED") && !snapshot.failures().isEmpty()) {
                    status = "UNKNOWN"; reason = "snapshot collection incomplete";
                }
                if (status.equals("UNKNOWN")) findings.add(unknown("Shadowing: " + reason + "."));
                String window = unused(snapshot, rule, days, findings);
                var hits = rule.hitCounts();
                results.put(rule.id(), new Assessment(status, reason, covering, List.copyOf(findings), broad, timeStatus, expiring,
                    window, hits == null ? "UNKNOWN" : hits.source(), hits == null ? null : hits.collectedAt()));
            }
        }
        return new Result(Collections.unmodifiableMap(results), budgetReached);
    }
    /**
     * The enabled, fully resolved earlier rules of one evaluation scope, indexed so a rule does real work only against
     * candidates that can change its result. It answers exactly what the former scan over every earlier rule answered
     * (that scan is kept in the test sources as LegacyPolicyHygiene and compared by PolicyHygieneDifferentialTest):
     * <ul>
     * <li>Only {@code compatible} priors can cover, and compatibility is exact equality of the placement key except an
     * earlier zone of exactly {@code any}, so the up to four keys (zone or any) x (zone or any) x the same install-on
     * are looked up directly. A compatible prior is never {@code disjoint}, so that test is not repeated.</li>
     * <li>The covering rule is the earliest compatible prior containing all four selectors. Within one key a prior
     * whose selectors equal an earlier one's, or any prior after one whose four selectors are all any, can never be
     * that earliest prior and is not kept as a candidate.</li>
     * <li>"Zone or install-on coverage is unproven" needs one overlapping, incompatible prior. Both predicates read the
     * placements only, so each (rule key, earlier key) pair is evaluated once and the answer remembered.</li>
     * <li>A candidate whose selector bounds cannot contain the rule's is rejected before the range walk.</li>
     * </ul>
     */
    private static final class Earlier {
        private record Key(Set<String> from, Set<String> to, Set<String> installOn) {}
        /** Candidates in rule order; their bounds are packed {@link #WIDTH} longs each so the scan stays in one array. */
        private static final class Bucket {
            final Match first; final List<Match> matches = new ArrayList<>(); final Set<List<Selector>> seen = new HashSet<>(); boolean closed;
            int[] orders = new int[8]; long[] bounds = new long[8 * WIDTH];
            Bucket(Match first) { this.first = first; }
            void add(int order, Match match) {
                int n = matches.size();
                if (n == orders.length) { orders = Arrays.copyOf(orders, n * 2); bounds = Arrays.copyOf(bounds, n * 2 * WIDTH); }
                orders[n] = order; System.arraycopy(bounds(match.selectors()), 0, bounds, n * WIDTH, WIDTH); matches.add(match);
            }
        }
        private static final List<String> FAMILIES = List.of("ipv4", "tcp", "udp");
        private static final int WIDTH = 4 * 3 * 2;
        private static final Set<String> ANY = Set.of("any");
        private final Map<String, PolicyObject> objects;
        private final Map<Key, Bucket> buckets = new HashMap<>();
        private final List<Bucket> keys = new ArrayList<>();
        private final Map<Key, int[]> overlap = new HashMap<>();
        private int count;
        /** An enabled earlier rule of this scope had no resolved match, so no later rule can be proven clean. */
        boolean unknown;
        Earlier(Map<String, PolicyObject> objects) { this.objects = objects; }
        private static Key key(Match m) { return new Key(m.placements().get("from"), m.placements().get("to"), m.placements().get("install-on")); }
        void add(Match match) {
            var bucket = buckets.computeIfAbsent(key(match), k -> { var b = new Bucket(match); keys.add(b); return b; });
            int order = count++;
            if (bucket.closed || !bucket.seen.add(match.selectors())) return;
            bucket.add(order, match);
            bucket.closed = match.selectors().stream().allMatch(Selector::any);
        }
        Match cover(Match match, Budget budget) {
            var key = key(match); Match best = null; int bestOrder = Integer.MAX_VALUE;
            long[] inner = bounds(match.selectors());
            var lookups = new LinkedHashSet<Key>();
            for (var from : List.of(key.from(), ANY)) for (var to : List.of(key.to(), ANY)) lookups.add(new Key(from, to, key.installOn()));
            for (Key lookup : lookups) {
                var bucket = buckets.get(lookup);
                if (bucket == null) continue;
                int[] orders = bucket.orders; long[] outer = bucket.bounds;
                for (int j = 0, n = bucket.matches.size(); j < n && orders[j] < bestOrder; j++) {
                    budget.spend();
                    if (!bounded(outer, j * WIDTH, inner)) continue;
                    var prior = bucket.matches.get(j); boolean covers = true;
                    for (int i = 0; i < 4 && covers; i++) covers = contains(prior.selectors().get(i), match.selectors().get(i), budget);
                    if (covers) { best = prior; bestOrder = orders[j]; break; }
                }
            }
            return best;
        }
        /** True once any earlier key overlaps this rule's key without being compatible with it; the answer only grows. */
        boolean unproven(Match match, Budget budget) {
            var state = overlap.computeIfAbsent(key(match), k -> new int[2]);
            while (state[1] == 0 && state[0] < keys.size()) {
                budget.spend();
                var prior = keys.get(state[0]).first;
                if (!disjoint(prior, match, objects) && !compatible(prior, match)) state[1] = 1;
                state[0]++;
            }
            return state[1] == 1;
        }
        /**
         * Per selector and range family the lowest start and highest end of the merged (sorted) ranges; [MIN, MAX] for
         * any and [MAX, MIN] for an absent family. Outer [lo, hi] holding inner [lo, hi] for every slot is then a
         * necessary condition for {@code contains}: any holds everything, nothing but any holds any, an absent outer
         * family holds no present inner one, and an absent inner family is held by everything. Other families are
         * not summarised, which only lets more candidates through to the exact range walk.
         */
        private static long[] bounds(List<Selector> selectors) {
            long[] out = new long[WIDTH]; int at = 0;
            for (Selector s : selectors) for (String family : FAMILIES) {
                var ranges = s.ranges().get(family); boolean absent = ranges == null || ranges.isEmpty();
                out[at++] = s.any() ? Long.MIN_VALUE : absent ? Long.MAX_VALUE : ranges.get(0).start();
                out[at++] = s.any() ? Long.MAX_VALUE : absent ? Long.MIN_VALUE : ranges.get(ranges.size() - 1).end();
            }
            return out;
        }
        private static boolean bounded(long[] outer, int at, long[] inner) {
            for (int k = 0; k < WIDTH; k += 2) if (outer[at + k] > inner[k] || outer[at + k + 1] < inner[k + 1]) return false;
            return true;
        }
    }
    /**
     * The inventory-join reasons for objects this rule's selectors reach that the join supplied (group members the
     * dictionary did not carry) or filled (dictionary objects without values); empty when none.
     */
    private static Set<String> joined(Rule rule, Map<String, PolicyObject> snapshotObjects, Map<String, PolicyObject> breadthObjects) {
        var out = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>();
        for (var cell : List.of(rule.source(), rule.destination(), rule.service(), rule.application())) pending.addAll(cell.refs());
        var seen = new HashSet<String>();
        while (!pending.isEmpty() && seen.size() < PolicyRuleMetrics.RULE_BUDGET) {
            String id = pending.removeFirst();
            if (!seen.add(id)) continue;
            var object = breadthObjects.get(id);
            if (object == null) continue;
            var stored = snapshotObjects.get(id);
            if (!object.equals(stored))
                out.add(stored == null || "UNRESOLVED".equals(stored.status()) ? PolicyRuleMetrics.INVENTORY : PolicyRuleMetrics.VALUES_INVENTORY);
            pending.addAll(object.members());
        }
        return out;
    }
    private static Finding finding(String cls, String severity, String evidence) { return new Finding(cls, severity, evidence); }
    private static Finding unknown(String evidence) { return finding("unknown", "LOW", evidence); }
    private static boolean accepting(String action) { return Set.of("Accept", "allow").contains(action); }
    /**
     * Same action for REDUNDANT versus CONFLICT. Two Check Point "Apply Layer" rules act alike only when they apply the
     * same inline layer (same opaque reference); other rules carry no inline-layer reference, so this is action equality.
     */
    /** A resolved coverage candidate with action "Apply Layer" is a Check Point rule naming exactly one inline layer. */
    private static boolean applyLayer(Rule rule) {
        return rule.action().equals("Apply Layer") && rule.extras().getOrDefault("inline-layer", List.of()).size() == 1;
    }
    private static boolean sameAction(Rule covering, Rule covered) {
        return covering.action().equals(covered.action())
            && covering.extras().getOrDefault("inline-layer", List.of()).equals(covered.extras().getOrDefault("inline-layer", List.of()));
    }
    private static String unused(PolicySnapshot snapshot, Rule rule, int days, List<Finding> findings) {
        var h = rule.hitCounts(); String window = "Counter window unknown; first hit is not a counter start.";
        if (h == null || h.hits() == null || h.hits() < 0 || parse(h.collectedAt()) == null) {
            findings.add(unknown("Hit counts were not collected or are invalid.")); return window;
        }
        if (h.firewalls().stream().anyMatch(f -> f.hits() == null) || ("device".equals(h.source()) && snapshot.metadata().targets().stream()
                .anyMatch(t -> h.firewalls().stream().noneMatch(f -> f.deviceId().equals(t.deviceId()) && Objects.equals(f.context(), t.context()))))) {
            findings.add(unknown("Hit counts are missing for a target.")); return window;
        }
        Instant at = parse(h.collectedAt()), last = parse(h.lastHit());
        if (h.lastHit() != null && (last == null || last.isAfter(at))) {
            findings.add(unknown("Last-hit timestamp is invalid.")); return window;
        }
        Instant newestCreation = h.firewalls().stream().map(f -> parse(f.createdAt())).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (newestCreation != null) {
            long age = Duration.between(newestCreation, at).toDays();
            // Creation is only an upper bound: a later counter reset may shorten it further.
            window = "Counter window unknown; bounded above by rule age: " + Math.max(0, age) + " days.";
            if (h.hits() == 0 && age < days) { findings.add(unknown("Zero-hit counter window is shorter than the threshold.")); return window; }
        }
        if (h.hits() == 0 && last != null) findings.add(unknown("Zero hits contradict a recorded last hit."));
        else if (h.hits() == 0) findings.add(finding("unused", "LOW", "Zero collected hits; counter reset/window is unknown."));
        else if (last == null) findings.add(unknown("Last-hit time was not collected."));
        else if (!last.isAfter(at.minus(Duration.ofDays(days)))) findings.add(finding("unused", "LOW", "Last hit predates the threshold at counter collection time."));
        return window;
    }
    private static Instant parse(String value) { try { return Instant.parse(value); } catch (RuntimeException invalid) { return null; } }


}
