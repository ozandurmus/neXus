package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.time.*;
import java.util.*;

/** Pure stored-evidence analysis over proven, ordered evaluation scopes. */
public final class PolicyHygiene {
    private PolicyHygiene() {}
    /** The reason of a rule that an earlier, not fully resolved rule keeps from being proven; {@link Assessment#blockingRuleId} names that rule. */
    static final String EARLIER_UNSUPPORTED = "earlier rule has unsupported constraints";
    public static final Set<String> CLASSES = Set.of("shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown");
    public static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    /** Every shadow reason this class (and PolicyNormalization) can emit; PolicyPrivacy projects these unchanged, so a new reason must be added here. */
    public static final Set<String> REASONS = Set.of(
        "analysis budget", "unsupported rulebase", "negated selector", "missing selector", "enabled state is unknown",
        "non-terminal or unknown action", "time or content constraint", "user or category constraint", "zone rule type constraint",
        "unresolved install-on selector", "unsupported rule constraint", "time evidence not collected", "unresolved object",
        "group cycle or depth limit", "empty or dynamic group", "dynamic or unsupported object type", "unsupported address representation",
        "missing address values", "unsupported object members", "ambiguous object values", "unsupported service constraint",
        "unsupported port expression", "invalid port range", "missing service values", EARLIER_UNSUPPORTED,
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
            String counterWindow, String hitSource, String hitsCollectedAt, String blockingRuleId, Integer blockingRuleNumber) {
        /** An assessment no earlier rule blocks (the form every verdict-only caller and test oracle uses). */
        public Assessment(String shadowStatus, String shadowReason, String coveringRuleId, List<Finding> findings,
                Map<String, Object> permissiveness, String timeStatus, boolean expiring, String counterWindow, String hitSource, String hitsCollectedAt) {
            this(shadowStatus, shadowReason, coveringRuleId, findings, permissiveness, timeStatus, expiring, counterWindow, hitSource, hitsCollectedAt, null, null);
        }
    }
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
        Budget budget = new Budget(workLimit), side = budget.side();
        // Covering Palo Alto intrazone/interzone rules is work of its own pool (same total, same per-rule window): it only
        // ever turns a "zone rule type constraint" UNKNOWN into REDUNDANT/CONFLICT, so running out of it leaves that
        // reason and never takes budget from another rule's verdict or from a partial prior.
        Budget typedBudget = new Budget(workLimit);
        // Passing over placement-undecided priors whose selectors cannot contain the rule's (Earlier#undecidedMayCover)
        // is work of its own pool too (same total, same per-rule window): it only ever turns "zone or install-on
        // coverage is unproven" into NOT_SHADOWED, and running out of it leaves that reason.
        Budget passBudget = new Budget(workLimit);
        var resolver = new Resolver(snapshot.objects(), budget);
        var sideResolver = new Resolver(snapshot.objects(), side);
        var typedResolver = new Resolver(snapshot.objects(), typedBudget);
        boolean pan = snapshot.metadata().vendor().equals("PAN");
        Map<String, Assessment> results = new LinkedHashMap<>();
        // One Earlier per evaluation scope; a Check Point parent layer's Earlier resumes after its inline layers.
        var scope = new EvaluationScope<Earlier>(snapshot.metadata(), () -> new Earlier(snapshot.objects(), pan));
        boolean budgetReached = false;
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) {
                // Check Point NAT rulebase and Palo Alto NAT/default-rule sections are stored, not analysed: no shadowing,
                // permissiveness or hit-based finding is invented for them, and they do not disturb the security rule
                // ordering (lookup and offline analysis skip them the same way). Before 2026-11-03 a CP NAT section broke
                // the access-layer scope, so 5,494 NAT and 7,156 later access rules read "earlier sections not analysed".
                for (Rule rule : section.rules()) {
                    List<Finding> findings = Boolean.FALSE.equals(rule.enabled()) ? List.of(finding("disabled", "LOW", "Rule is disabled.")) : List.of();
                    results.put(rule.id(), new Assessment("UNKNOWN", "unsupported rulebase", null, findings,
                        Map.of("level", "Unknown", "reasons", List.of(), "score", 0), "unknown", false,
                        "Counter window unknown.", "UNKNOWN", null, null, null));
                }
                continue;
            }
            Earlier earlier = scope.advance(section);
            for (Rule rule : section.rules()) {
                List<Finding> findings = new ArrayList<>();
                String status = "UNKNOWN", reason = "analysis budget", covering = null;
                Rule blocker = null;
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
                    budget.rule(ruleLimit); passBudget.rule(ruleLimit);
                    budget.spend();
                    match = normalize(snapshot, section, rule, resolver);
                    budget.spend();
                    reason = match.problem();
                    if (scope.unproven) reason = "earlier sections not analysed";
                    if (reason == null) {
                        status = "NOT_SHADOWED";
                        if (earlier.unknown) { reason = EARLIER_UNSUPPORTED; blocker = earlier.unknownRule; }
                        Match prior = earlier.cover(match, budget);
                        if (prior != null) {
                            covering = prior.rule().id(); status = covered(prior.rule(), rule, findings); reason = null;
                        } else if (earlier.unproven(match, budget) && earlier.undecidedMayCover(match, passBudget)) reason = "zone or install-on coverage is unproven";
                        // Only where the former analysis said "earlier rule has unsupported constraints" for want of a full
                        // match: every partial prior must be proven disjoint, or that reason stands.
                        else if (reason == null && earlier.partialOverlap(match, side)) { reason = EARLIER_UNSUPPORTED; blocker = earlier.partialBlocker(match); }
                        if (covering == null && reason != null) status = "UNKNOWN";
                    } else if (reason.equals(ZONE_RULE_TYPE) && earlier.zoneSupersets) {
                        // A Palo Alto intrazone or interzone rule stays UNKNOWN for its rule type unless an earlier universal
                        // rule provably covers every zone pair it matches (PolicyNormalization.zonesCover). It is never an
                        // earlier rule itself, so it remains a partial prior below exactly as before.
                        Match prior = typedCover(snapshot, section, rule, earlier, typedBudget, typedResolver, ruleLimit);
                        if (prior != null) { covering = prior.rule().id(); status = covered(prior.rule(), rule, findings); reason = null; }
                    }
                } catch (Unknown limit) { status = "UNKNOWN"; reason = limit.getMessage(); budgetReached = true; }
                // A rule that ran out of budget still constrains later rules: a resolved match stays a coverage
                // candidate, an unresolved one a partial prior or, when nothing of it may be relied on, an earlier
                // unknown -- never silently dropped (fail-closed).
                if (!Boolean.FALSE.equals(rule.enabled())) {
                    if (match != null && match.problem() == null) earlier.add(match);
                    else {
                        Partial partial = null;
                        if (match != null) try { partial = partial(snapshot, section, rule, sideResolver); } catch (Unknown limit) { partial = null; }
                        if (partial != null) earlier.add(partial);
                        else { earlier.unknown = true; if (earlier.unknownRule == null) earlier.unknownRule = rule; }
                    }
                }
                if (status.equals("NOT_SHADOWED") && !snapshot.failures().isEmpty()) {
                    status = "UNKNOWN"; reason = "snapshot collection incomplete";
                }
                if (status.equals("UNKNOWN")) findings.add(unknown("Shadowing: " + reason + "."));
                String window = unused(snapshot, rule, days, findings);
                var hits = rule.hitCounts();
                // Named only where the final verdict is that very reason (a coverage, "unproven" or budget verdict supersedes it).
                boolean blocked = status.equals("UNKNOWN") && EARLIER_UNSUPPORTED.equals(reason) && blocker != null;
                results.put(rule.id(), new Assessment(status, reason, covering, List.copyOf(findings), broad, timeStatus, expiring,
                    window, hits == null ? "UNKNOWN" : hits.source(), hits == null ? null : hits.collectedAt(),
                    blocked ? blocker.id() : null, blocked ? blocker.number() : null));
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
     * The deliberate differences from that scan:
     * <ul>
     * <li>An earlier rule without a full match no longer makes every later rule UNKNOWN "earlier rule has unsupported
     * constraints" when it is a {@link Partial} prior proven disjoint from the later rule. Partial priors never cover (no
     * REDUNDANT or CONFLICT from them) and are consulted only where the scan would have given that very reason.</li>
     * <li>Palo Alto zone supersets ({@link #zoneSupersets}): an earlier universal rule with the same install-on whose
     * from- and to-zones hold the later rule's ({@link PolicyNormalization#zonesCover}) is a coverage candidate too, and
     * no longer makes the rule "zone or install-on coverage is unproven". An overlapping prior with the same install-on
     * whose zones provably do not hold the rule's ({@link PolicyNormalization#zonesCannotCover}) cannot cover it and is
     * passed over, as a partially overlapping address or service is; "unproven" remains only where the relation cannot
     * be decided (any mixed with names, a lone any against names, unequal install-on). The compatible (former)
     * candidates are searched first and win when one covers, so
     * every rule the scan covered keeps its covering rule; superset candidates are consulted only where the scan said
     * "zone or install-on coverage is unproven". The same candidates may cover a later intrazone or interzone rule,
     * which otherwise stays "zone rule type constraint". Install-on stays exact equality.</li>
     * <li>Selector pass-over ({@link #undecidedMayCover}): a prior whose placement relation to the rule is undecided
     * (the reason for "zone or install-on coverage is unproven") but whose selectors do not contain the rule's on at
     * least one of the four dimensions can never be the single earlier rule that covers it, whatever the placement
     * turns out to be, so it is passed over. "Unproven" stays only where some undecided prior also contains every
     * selector; otherwise the rule is decided exactly as if that prior had not been there (NOT_SHADOWED, or the
     * partial-prior and earlier-unsupported reasons). Both vendors; placements, cover search and every other verdict
     * are unchanged.</li>
     * </ul>
     * Every other verdict stays the scan's.
     */
    private static final class Earlier {
        private record Key(Set<String> from, Set<String> to, Set<String> installOn) {}
        /** Candidates in rule order; their bounds are packed {@link #WIDTH} longs each so the scan stays in one array. */
        private static final class Bucket {
            final Key key; final Match first; final List<Match> matches = new ArrayList<>(); final Set<List<Selector>> seen = new HashSet<>(); boolean closed;
            int[] orders = new int[8]; long[] bounds = new long[8 * WIDTH];
            Bucket(Key key, Match first) { this.key = key; this.first = first; }
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
        /** Palo Alto only: zone supersets cover. Check Point placements stay exact (install-on is never a superset). */
        final boolean zoneSupersets;
        /**
         * Per install-on key, an inverted zone index of the buckets, each side (from, to) on its own: a lone any, a set
         * mixing any with names (or empty) by the exact set, otherwise under every zone it names. Every bucket whose
         * zones on one side can hold a set lies in at most two of these lists ({@link Side#holders}).
         */
        private static final class Side {
            final List<Bucket> any = new ArrayList<>();
            final Map<String, List<Bucket>> byZone = new HashMap<>();
            final Map<Set<String>, List<Bucket>> bySet = new HashMap<>();
            void add(Set<String> zones, Bucket bucket) {
                if (zones.equals(ANY)) any.add(bucket);
                else if (zones.isEmpty() || zones.contains("any")) bySet.computeIfAbsent(zones, k -> new ArrayList<>()).add(bucket);
                else for (String zone : zones) byZone.computeIfAbsent(zone, k -> new ArrayList<>()).add(bucket);
            }
            /** Lists holding every bucket whose zones on this side may hold {@code inner} (PolicyNormalization.zoneSuperset), and others. */
            List<List<Bucket>> holders(Set<String> inner) {
                if (inner.equals(ANY)) return List.of(any);
                if (inner.isEmpty() || inner.contains("any")) return List.of(any, bySet.computeIfAbsent(inner, k -> new ArrayList<>()));
                List<Bucket> fewest = null;
                for (String zone : inner) {
                    var list = byZone.computeIfAbsent(zone, k -> new ArrayList<>());
                    if (fewest == null || list.size() < fewest.size()) fewest = list;
                }
                return List.of(any, fewest);
            }
        }
        private static final class Targets { final Side from = new Side(), to = new Side(); }
        private final Map<Set<String>, Targets> byInstallOn = new HashMap<>();
        private record Lookup(Key key, boolean intrazone) {}
        /** One rule key's superset search: the index lists it reads (chosen once, the side with fewer entries), how far each was read, and the hits. */
        private static final class Supersets {
            final List<List<Bucket>> lists; final int[] next; final List<Bucket> buckets = new ArrayList<>();
            Supersets(List<List<Bucket>> lists) { this.lists = lists; this.next = new int[lists.size()]; }
        }
        private final Map<Lookup, Supersets> supersets = new HashMap<>();
        /**
         * For {@link #unproven} of a rule whose zones are names only: per install-on key, every bucket, and the buckets
         * with a set mixing any with names (or empty) on from or to -- with the same install-on, only those can be
         * undecided against such a rule. Per rule key: how far each list was read, and whether an undecided prior was met.
         */
        private final Map<Set<String>, List<Bucket>> byTarget = new LinkedHashMap<>(), mixedByTarget = new HashMap<>();
        private static final class Named { final Map<Set<String>, int[]> read = new HashMap<>(); boolean found; }
        private final Map<Key, Named> named = new HashMap<>();
        private int count;
        /** An enabled earlier rule of this scope had no resolved match and no partial prior, so no later rule can be proven clean. */
        boolean unknown;
        /** The first such rule in rule order: with {@link #unknown} set it keeps every later rule of the scope from being proven. */
        Rule unknownRule;
        /** Distinct partial priors in rule order (equal resolved dimensions answer every disjointness test alike). */
        private final List<Partial> partials = new ArrayList<>();
        private final Set<List<Object>> partialSeen = new HashSet<>();
        /** Each partial prior's placement-key number; placements are disjoint or not per (prior key, rule key), once. */
        private final Map<Map<String, Set<String>>, Integer> partialKeys = new HashMap<>();
        private int[] partialKey = new int[8];
        private final Map<Map<String, Set<String>>, byte[][]> placementMemo = new HashMap<>();
        /** Per later-rule dimensions: partial priors proven disjoint so far, and whether one was not; the answer only grows. */
        private final Map<List<Object>, int[]> partialState = new HashMap<>();
        Earlier(Map<String, PolicyObject> objects, boolean zoneSupersets) { this.objects = objects; this.zoneSupersets = zoneSupersets; }
        private static Key key(Match m) { return new Key(m.placements().get("from"), m.placements().get("to"), m.placements().get("install-on")); }
        void add(Match match) {
            var bucket = buckets.computeIfAbsent(key(match), k -> {
                var b = new Bucket(k, match); keys.add(b);
                if (zoneSupersets) {
                    var targets = byInstallOn.computeIfAbsent(k.installOn(), t -> new Targets()); targets.from.add(k.from(), b); targets.to.add(k.to(), b);
                    byTarget.computeIfAbsent(k.installOn(), t -> new ArrayList<>()).add(b);
                    if (mixed(k.from()) || mixed(k.to())) mixedByTarget.computeIfAbsent(k.installOn(), t -> new ArrayList<>()).add(b);
                }
                return b;
            });
            int order = count++;
            if (bucket.closed || !bucket.seen.add(match.selectors())) return;
            bucket.add(order, match);
            bucket.closed = match.selectors().stream().allMatch(Selector::any);
        }
        /**
         * The covering earlier rule, or null. A universal rule is looked up under its compatible keys first (the former
         * index); only when none of those covers, and only for Palo Alto, the buckets whose zones cover the rule's are
         * searched as well. An intrazone or interzone rule ({@link PolicyNormalization#typed}) is searched in the zone
         * superset buckets only. Either search takes the earliest covering prior it finds.
         */
        Match cover(Match match, Budget budget) {
            var key = key(match); long[] inner = bounds(match.selectors());
            String type = PolicyNormalization.ruleType(match.rule());
            Match[] best = {null}; int[] bestOrder = {Integer.MAX_VALUE};
            var lookups = new LinkedHashSet<Key>();
            if (type.equals("universal")) {
                for (var from : List.of(key.from(), ANY)) for (var to : List.of(key.to(), ANY)) lookups.add(new Key(from, to, key.installOn()));
                for (Key lookup : lookups) {
                    var bucket = buckets.get(lookup);
                    if (bucket != null) earliest(bucket, match, inner, best, bestOrder, budget);
                }
            } else if (!Set.of("intrazone", "interzone").contains(type)) return null;
            if (best[0] != null || !zoneSupersets) return best[0];
            for (Bucket bucket : supersets(key, type.equals("intrazone"), budget))
                if (!lookups.contains(bucket.key)) earliest(bucket, match, inner, best, bestOrder, budget);
            return best[0];
        }
        /** The bucket's first prior before {@code bestOrder} that contains all four selectors becomes the best. */
        private static void earliest(Bucket bucket, Match match, long[] inner, Match[] best, int[] bestOrder, Budget budget) {
            int[] orders = bucket.orders; long[] outer = bucket.bounds;
            for (int j = 0, n = bucket.matches.size(); j < n && orders[j] < bestOrder[0]; j++) {
                budget.spend();
                if (!bounded(outer, j * WIDTH, inner)) continue;
                var prior = bucket.matches.get(j); boolean covers = true;
                for (int i = 0; i < 4 && covers; i++) covers = contains(prior.selectors().get(i), match.selectors().get(i), budget);
                if (covers) { best[0] = prior; bestOrder[0] = orders[j]; return; }
            }
        }
        /**
         * The buckets of this install-on key whose zones cover the rule key's. Candidates come from the zone index of one
         * side only -- whichever lists were shorter when the key was first looked up -- and each is tested once, one
         * budget unit each, and remembered; the hits only grow as buckets are added.
         */
        private List<Bucket> supersets(Key key, boolean intrazone, Budget budget) {
            var targets = byInstallOn.get(key.installOn());
            if (targets == null) return List.of();
            var state = supersets.computeIfAbsent(new Lookup(key, intrazone), k -> {
                var from = targets.from.holders(key.from()); var to = targets.to.holders(intrazone ? key.from() : key.to());
                return new Supersets(size(from) <= size(to) ? from : to);
            });
            for (int i = 0; i < state.lists.size(); i++) {
                var list = state.lists.get(i);
                while (state.next[i] < list.size()) {
                    budget.spend();
                    var bucket = list.get(state.next[i]);
                    if (PolicyNormalization.zonesCover(bucket.key.from(), bucket.key.to(), key.from(), key.to(), intrazone)) state.buckets.add(bucket);
                    state.next[i]++;
                }
            }
            return state.buckets;
        }
        private static int size(List<List<Bucket>> lists) { int n = 0; for (var list : lists) n += list.size(); return n; }
        /**
         * The earlier key's bearing on this rule's key is decided: it can cover (compatible, or Palo Alto with the same
         * install-on and covering zones; the cover search then compares the selectors), or it provably cannot (Palo Alto,
         * same install-on, a zone of the rule the prior does not name). Otherwise it is undecided.
         */
        private boolean placementDecided(Match prior, Match match) {
            if (compatible(prior, match)) return true;
            if (!zoneSupersets || !prior.placements().get("install-on").equals(match.placements().get("install-on"))) return false;
            var from = prior.placements().get("from"); var to = prior.placements().get("to");
            var laterFrom = match.placements().get("from"); var laterTo = match.placements().get("to");
            return PolicyNormalization.zonesCover(from, to, laterFrom, laterTo, false) || PolicyNormalization.zonesCannotCover(from, to, laterFrom, laterTo);
        }
        void add(Partial partial) {
            if (!partialSeen.add(Arrays.asList(partial.selectors(), partial.placements()))) return;
            int n = partials.size();
            if (n == partialKey.length) partialKey = Arrays.copyOf(partialKey, n * 2);
            partialKey[n] = partialKeys.computeIfAbsent(partial.placements(), k -> partialKeys.size());
            partials.add(partial);
        }
        /**
         * True unless every partial prior of this scope is proven {@code disjoint} from the match. The work is spent from
         * {@code side} (the rule's own window, a separate pool); running out of it is "not proven", never a budget verdict.
         */
        boolean partialOverlap(Match match, Budget side) {
            if (partials.isEmpty()) return false;
            var state = partialState.computeIfAbsent(List.of(match.selectors(), match.placements()), k -> new int[2]);
            // 0 = not yet evaluated, 1 = placements disjoint, 2 = not disjoint; one entry per partial placement key.
            var memo = placementMemo.computeIfAbsent(match.placements(), k -> new byte[1][0]);
            if (memo[0].length < partialKeys.size()) memo[0] = Arrays.copyOf(memo[0], Math.max(partialKeys.size(), 2 * memo[0].length));
            byte[] placed = memo[0];
            try {
                while (state[1] == 0 && state[0] < partials.size()) {
                    side.spend();
                    var prior = partials.get(state[0]); int key = partialKey[state[0]];
                    if (placed[key] == 0) placed[key] = (byte) (PolicyNormalization.disjoint(prior.placements(), match.placements(), objects) ? 1 : 2);
                    if (placed[key] == 2 && !disjoint(prior, match, side)) state[1] = 1;
                    else state[0]++;
                }
            } catch (Unknown limit) { return true; }
            return state[1] == 1;
        }
        /**
         * The earliest partial prior (rule order) that {@link #partialOverlap} could not prove disjoint from the match:
         * the scan stops at the first one, or at the one it ran out of work on. Call only after it returned true.
         */
        Rule partialBlocker(Match match) {
            var state = partialState.get(List.of(match.selectors(), match.placements()));
            return state == null || state[0] >= partials.size() ? null : partials.get(state[0]).rule();
        }
        /**
         * True once any earlier key overlaps this rule's key while whether it can cover the rule is undecided
         * ({@link #placementDecided}); the answer only grows. Such a prior (unequal install-on, or zones that cannot be
         * compared) might cover the rule, so the rule stays UNKNOWN.
         */
        boolean unproven(Match match, Budget budget) {
            var key = key(match);
            if (zoneSupersets && names(key.from()) && names(key.to())) return unprovenNamed(match, key, budget);
            var state = overlap.computeIfAbsent(key, k -> new int[2]);
            while (state[1] == 0 && state[0] < keys.size()) {
                budget.spend();
                var prior = keys.get(state[0]).first;
                if (!disjoint(prior, match, objects) && !placementDecided(prior, match)) state[1] = 1;
                state[0]++;
            }
            return state[1] == 1;
        }
        /**
         * {@link #unproven} for a rule whose from- and to-zones are names only. A prior with the same install-on whose zones
         * are names or a lone any on both sides is always decided against it ({@link PolicyNormalization#zoneHolds}), so
         * only the mixed buckets of its own install-on and every bucket of another install-on are read: the same answer as
         * the full scan, without walking every earlier key.
         */
        private boolean unprovenNamed(Match match, Key key, Budget budget) {
            var state = named.computeIfAbsent(key, k -> new Named());
            for (var entry : byTarget.entrySet()) {
                if (state.found) break;
                var list = entry.getKey().equals(key.installOn()) ? mixedByTarget.getOrDefault(entry.getKey(), List.of()) : entry.getValue();
                int[] read = state.read.computeIfAbsent(entry.getKey(), t -> new int[1]);
                while (!state.found && read[0] < list.size()) {
                    budget.spend();
                    var prior = list.get(read[0]).first;
                    if (!disjoint(prior, match, objects) && !placementDecided(prior, match)) state.found = true;
                    read[0]++;
                }
            }
            return state.found;
        }
        /**
         * For a rule {@link #unproven} answered true for: whether some earlier prior whose placement relation to the
         * rule is undecided ({@link #placementDecided} false, placements not disjoint -- the same test) also contains
         * all four of the rule's selectors. Both rules are full matches, so every selector is resolved and {@code
         * contains} is exact: a prior failing it on one dimension leaves a packet of the rule outside the prior (a
         * match is the product of its dimensions, resolved selectors are never empty), so it cannot cover the rule
         * in any placement. Bucket deduplication keeps this exact: a dropped prior repeats a kept prior's selectors or
         * follows a kept all-any prior of its bucket. Work comes from {@code pass}, a pool of its own; running out of
         * it answers true, the former verdict.
         */
        boolean undecidedMayCover(Match match, Budget pass) {
            try {
                var state = undecided.computeIfAbsent(key(match), k -> new Undecided());
                while (state.read < keys.size()) {
                    pass.spend();
                    var bucket = keys.get(state.read);
                    if (!disjoint(bucket.first, match, objects) && !placementDecided(bucket.first, match)) state.buckets.add(bucket);
                    state.read++;
                }
                long[] inner = bounds(match.selectors());
                for (Bucket bucket : state.buckets) {
                    for (int j = 0, n = bucket.matches.size(); j < n; j++) {
                        pass.spend();
                        if (!bounded(bucket.bounds, j * WIDTH, inner)) continue;
                        var prior = bucket.matches.get(j); boolean contains = true;
                        for (int i = 0; i < 4 && contains; i++) contains = PolicyNormalization.contains(prior.selectors().get(i), match.selectors().get(i), pass);
                        if (contains) return true;
                    }
                }
                return false;
            } catch (Unknown limit) { return true; }
        }
        /** Per rule key: how far the earlier keys were read for {@link #undecidedMayCover}, and the undecided buckets found. */
        private static final class Undecided { int read; final List<Bucket> buckets = new ArrayList<>(); }
        private final Map<Key, Undecided> undecided = new HashMap<>();
        private static boolean names(Set<String> zones) { return !zones.isEmpty() && !zones.contains("any"); }
        private static boolean mixed(Set<String> zones) { return zones.isEmpty() || zones.contains("any") && zones.size() > 1; }
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
    static final String ZONE_RULE_TYPE = "zone rule type constraint";
    /** The verdict for a rule fully covered by {@code prior}, adding its finding. */
    private static String covered(Rule prior, Rule rule, List<Finding> findings) {
        boolean same = sameAction(prior, rule);
        // An earlier Apply Layer rule makes this rule unreachable, but its inline layer's verdict is not
        // analysed: never reached (REDUNDANT), never an action conflict.
        boolean inlineCover = !same && applyLayer(prior);
        String status = same || inlineCover ? "REDUNDANT" : "CONFLICT";
        findings.add(finding(status.equals("CONFLICT") ? "conflict" : "shadowed", status.equals("CONFLICT") ? "HIGH" : "MEDIUM",
            status.equals("CONFLICT") ? "Earlier enabled rule fully covers this match with a different action."
                : inlineCover ? INLINE_LAYER_COVER : "Earlier enabled rule fully covers this match with the same action."));
        return status;
    }
    /** The earlier universal rule covering an intrazone/interzone rule, or null (not resolvable, not covered, or out of its budget). */
    private static Match typedCover(PolicySnapshot snapshot, Section section, Rule rule, Earlier earlier, Budget typedBudget, Resolver typedResolver, int ruleLimit) {
        try {
            typedBudget.rule(ruleLimit);
            var typed = PolicyNormalization.typed(snapshot, section, rule, typedResolver);
            return typed == null ? null : earlier.cover(typed, typedBudget);
        } catch (Unknown limit) { return null; }
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
