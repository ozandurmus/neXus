package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;

/**
 * Policy Insights phase P1 (POLICY_INSIGHTS_DESIGN_2026_11_24.md, FROZEN; PO decision record of the same date):
 * the Check Point insight catalogue I1, I2, I8, I9, I10, I11 and I12, read from the existing engines only. This class
 * runs no analysis of its own: it reads the {@link PolicyHygiene} verdicts and findings of a snapshot and the object
 * where-used / duplicate rows of its {@link PolicyDomainIndex}, assigns the section 3.1 evidence grade by a fixed table
 * of conditions (never a weighted score), and counts the package the way section 3.2 defines. Phase P2 step a adds I3
 * (covered by several earlier rules together) from the {@link PolicyUnionCover} result and step b adds I4 (partly shadowed
 * by earlier rules of the same action) from the {@link PolicyPartialShadow} result; the package counts stay the shadow
 * engine's.
 *
 * <p>Everything an insight carries is a rule or object reference, a number, or a word from a closed vocabulary
 * ({@link #TYPES}, {@link #GRADES}, {@link #NEEDS}, {@link PolicyHygiene#EVIDENCE}, {@link PolicyHygiene#REASONS}), so the
 * AIView projection ({@link PolicyPrivacy}) can pass it without ever seeing a name or address. Read-only: no device is
 * contacted and nothing is stored.
 *
 * <p>Every PROVEN insight carries {@link #INSTALL_STATE_NOT_VERIFIED}: it is proven for stored management intent, not
 * for what is installed on a gateway.
 */
public final class PolicyInsights {
    private PolicyInsights() {}

    static final String PROVEN = "PROVEN", SUPPORTED = "SUPPORTED", INDICATED = "INDICATED", NOT_ESTABLISHED = "NOT_ESTABLISHED", NOT_EVALUABLE = "NOT_EVALUABLE";
    /** The closed grade vocabulary of design section 3.1, strongest first. */
    public static final List<String> GRADES = List.of(PROVEN, SUPPORTED, INDICATED, NOT_ESTABLISHED, NOT_EVALUABLE);
    /** The insight types of phases P1, P2a and P2b (design section 4), in display order. */
    public static final List<String> TYPES = List.of("I1", "I2", "I3", "I4", "I8", "I9", "I10", "I11", "I12");
    /** The fixed I3 evidence, one per {@link PolicyUnionCover} cover action and one for an exhausted budget. */
    static final String I3_SAME = "Earlier enabled rules together cover this match; each has the same action.",
        I3_DIFFERENT = "Earlier enabled rules together cover this match; at least one has a different action.",
        I3_INLINE = "Earlier enabled rules together cover this match; at least one is an Apply Layer rule whose inline layer is not analysed.",
        I3_BUDGET = "Union cover analysis ran out of its work budget; whether earlier rules together cover this match is not proven.";
    /**
     * The fixed I4 evidence: the overlap is proven for stored management intent; it says nothing about moving the rule
     * (that is I7), and the other-action overlaps never reach this string (they are counted, not shown).
     */
    static final String I4_PARTIAL = "Part of this match is proven to overlap earlier enabled rules of the same layer; every overlapping earlier rule has the same action.",
        I4_BUDGET = "Partial overlap analysis ran out of its work budget; whether earlier rules overlap part of this match is not proven.";
    /** Every evidence string an insight carries besides {@link PolicyHygiene#EVIDENCE}; PolicyPrivacy passes these unchanged. */
    public static final Set<String> EVIDENCE = Set.of(I3_SAME, I3_DIFFERENT, I3_INLINE, I3_BUDGET, I4_PARTIAL, I4_BUDGET);
    /** An I4 insight lists at most this many overlapping earlier rules; the rest is the {@code moreOverlapping} counter. */
    static final int MAX_OVERLAPPING = 10;
    static final String INSTALL_STATE_NOT_VERIFIED = "INSTALL_STATE_NOT_VERIFIED";
    /**
     * What raises a grade (the "raise to" column): nothing, traffic logs (phase P4), counter samples over time (phase P3),
     * a new collection, the management's own unused-object report, or nothing in the design.
     */
    static final String NONE = "NONE", TRAFFIC_LOGS = "TRAFFIC_LOGS", COUNTER_SAMPLES = "COUNTER_SAMPLES", NEW_COLLECTION = "NEW_COLLECTION",
        VENDOR_REPORT = "VENDOR_REPORT", NOT_RESOLVABLE_NEEDS = "NOT_RESOLVABLE";
    static final Set<String> NEEDS = Set.of(NONE, TRAFFIC_LOGS, COUNTER_SAMPLES, NEW_COLLECTION, VENDOR_REPORT, NOT_RESOLVABLE_NEEDS);

    /** The three blocking classes of design section 3.2. */
    static final String LOGS = "LOGS", COLLECTION = "COLLECTION", NOT_RESOLVABLE = "NOT_RESOLVABLE";
    static final Set<String> REASON_CLASSES = Set.of(LOGS, COLLECTION, NOT_RESOLVABLE);
    private static final Set<String> LOG_RESOLVABLE = Set.of("time or content constraint", "user or category constraint",
        PolicyHygiene.APPLICATION_OVERLAP_UNKNOWN, PolicyHygiene.DEFAULT_PORTS_UNKNOWN);
    private static final Set<String> COLLECTION_RESOLVABLE = Set.of("snapshot collection incomplete", "time evidence not collected",
        "zone evidence not collected", "install-on evidence not collected");
    /**
     * The class of one blocking reason, fixed here once. A reason not listed is NOT_RESOLVABLE: the ceiling "up to N with
     * logs" is never raised by a reason nobody classified.
     */
    static String reasonClass(String reason) {
        return LOG_RESOLVABLE.contains(reason) ? LOGS : COLLECTION_RESOLVABLE.contains(reason) ? COLLECTION : NOT_RESOLVABLE;
    }

    record RuleRef(String ruleId, int number, String name, String insightRole) {}
    /** {@code raiseGrade}/{@code needs}: the grade reachable and what it needs ({@link #NONE} when nothing raises it). */
    record Insight(String id, String type, String grade, String scope, List<RuleRef> rules, List<String> objectIds,
            String raiseGrade, String needs, List<String> caveats, List<String> evidence, Map<String, Object> counter) {}
    /** One row of the not-decidable list: the engine's reason, its class and the number of rules behind it. */
    record Blocked(String shadowReason, String reasonClass, int rules) {}
    /**
     * The package numbers of design section 3.2 over enabled security rules: {@code decided} from the policy alone,
     * {@code upToWithLogs} the ceiling with logs (decided plus the rules whose only blocking reason is log-resolvable;
     * a ceiling, known before any log exists), and the rest by reason. {@code decided} and the three classes sum to
     * {@code rules}. {@code disabledRules} and {@code outsideSecurityRules} (NAT rulebase) are the rules left out, so the
     * Hygiene tab's all-rule coverage is {@code rules + disabledRules + outsideSecurityRules}.
     */
    record Counts(int rules, int decided, int upToWithLogs, int logResolvable, int collectionResolvable, int notResolvable,
            List<Blocked> notDecidable, int hitsCollected, int hitsMissing, int disabledRules, int outsideSecurityRules) {}
    /**
     * The I3 pass in numbers (design Q8: how often the budget is hit is measured before the engine is revisited): subject
     * rules evaluated, covered, not proven for the budget, and not evaluable by reason ({@link PolicyUnionCover#NOT_EVALUABLE_REASONS}).
     */
    record UnionCover(int evaluated, int covered, int notProven, List<Map<String, Object>> notEvaluable) {}
    /**
     * The I4 pass in numbers (design Q8): subject rules evaluated, {@code partial} (the I4 insights: every overlapping earlier
     * rule has the rule's action), {@code differentAction} (an overlapping earlier rule has another action: counted, no
     * insight), not proven for the budget, and not evaluable by reason.
     */
    record PartialShadow(int evaluated, int partial, int differentAction, int notProven, List<Map<String, Object>> notEvaluable) {}
    record Report(Counts counts, List<Insight> insights, Map<String, Integer> byType, Map<String, Integer> byGrade, boolean complete, String collectedAt,
            UnionCover unionCover, PartialShadow partialShadow) {}

    /**
     * @param index        the package's domain index (objects, where-used, duplicates), or null when not available:
     *                     I11 and I12 are then absent, never guessed
     * @param windowDays   a counter window in days that something other than the stored counters established, by rule id.
     *                     Phase P1 passes none: the stored hit counts carry no window start (design F9), so a zero is
     *                     INDICATED. The parameter exists so the SUPPORTED rule of section 3.1 is one tested condition,
     *                     which phase P3 (counter samples) feeds.
     * @param thresholdDays the unused threshold the hygiene result was computed with
     */
    static Report analyze(PolicySnapshot snapshot, PolicyHygiene.Result hygiene, PolicyDomainIndex index, Map<String, Integer> windowDays, int thresholdDays) {
        return analyze(snapshot, hygiene, index, windowDays, thresholdDays, PolicyUnionCover.analyze(snapshot, hygiene));
    }
    static Report analyze(PolicySnapshot snapshot, PolicyHygiene.Result hygiene, PolicyDomainIndex index, Map<String, Integer> windowDays, int thresholdDays,
            PolicyUnionCover.Result union) {
        return analyze(snapshot, hygiene, index, windowDays, thresholdDays, union, PolicyPartialShadow.analyze(snapshot, hygiene, union));
    }
    /**
     * @param union   the {@link PolicyUnionCover} result of the same snapshot and hygiene result (the service caches it)
     * @param partial the {@link PolicyPartialShadow} result of the same inputs (the service caches it too)
     */
    static Report analyze(PolicySnapshot snapshot, PolicyHygiene.Result hygiene, PolicyDomainIndex index, Map<String, Integer> windowDays, int thresholdDays,
            PolicyUnionCover.Result union, PolicyPartialShadow.Result partial) {
        var meta = snapshot.metadata();
        List<Insight> insights = new ArrayList<>();
        int rules = 0, decided = 0, logs = 0, collection = 0, notResolvable = 0, hitsCollected = 0, hitsMissing = 0, disabled = 0, outside = 0;
        Map<String, int[]> blocked = new LinkedHashMap<>();
        for (var section : snapshot.sections()) {
            if (PolicySnapshot.outsideSecurityEvaluation(meta, section)) { outside += section.rules().size(); continue; }
            for (var rule : section.rules()) {
                var a = hygiene.rules().get(rule.id());
                if (a == null || Boolean.FALSE.equals(rule.enabled())) {
                    if (a != null) insights.add(insight(meta, "I8", PROVEN, rule, List.of(), NONE, PROVEN, List.of("Rule is disabled."), Map.of()));
                    disabled++;
                    continue;
                }
                rules++;
                if (a.findings().stream().anyMatch(f -> f.findingClass().equals("unknown") && PolicyQueryService.HITS_MISSING_EVIDENCE.contains(f.evidence()))) hitsMissing++;
                else hitsCollected++;
                if (!"UNKNOWN".equals(a.shadowStatus())) decided++;
                else {
                    String reason = effectiveReason(a, hygiene);
                    String cls = reasonClass(reason);
                    blocked.computeIfAbsent(reason + "\u0000" + cls, k -> new int[1])[0]++;
                    switch (cls) { case LOGS -> logs++; case COLLECTION -> collection++; default -> notResolvable++; }
                }
                shadow(meta, rule, a, hygiene, section, insights);
                unionCover(meta, rule, union.rules().get(rule.id()), insights);
                partialShadow(meta, rule, partial.rules().get(rule.id()), insights);
                for (var f : a.findings()) {
                    if (f.findingClass().equals("expired")) insights.add(insight(meta, "I9", PROVEN, rule, List.of(), NONE, PROVEN, List.of(f.evidence()), Map.of()));
                    else if (f.findingClass().equals("unknown") && f.evidence().equals("Schedule evidence is incomplete."))
                        insights.add(insight(meta, "I9", NOT_EVALUABLE, rule, List.of(), NEW_COLLECTION, PROVEN, List.of(f.evidence()), Map.of()));
                }
                unused(meta, rule, a, windowDays, thresholdDays, insights);
            }
        }
        if (index != null) objects(meta, index, insights);
        List<Blocked> notDecidable = blocked.entrySet().stream().map(e -> {
            String[] key = e.getKey().split("\u0000", -1);
            return new Blocked(key[0], key[1], e.getValue()[0]);
        }).sorted(Comparator.comparingInt(Blocked::rules).reversed().thenComparing(Blocked::shadowReason)).toList();
        var counts = new Counts(rules, decided, decided + logs, logs, collection, notResolvable, notDecidable, hitsCollected, hitsMissing, disabled, outside);
        insights.sort(Comparator.comparingInt((Insight i) -> TYPES.indexOf(i.type())).thenComparingInt(i -> GRADES.indexOf(i.grade()))
            .thenComparingInt(i -> i.rules().isEmpty() ? Integer.MAX_VALUE : i.rules().get(0).number()).thenComparing(Insight::id));
        Map<String, Integer> byType = new LinkedHashMap<>(), byGrade = new LinkedHashMap<>();
        TYPES.forEach(t -> byType.put(t, 0)); GRADES.forEach(g -> byGrade.put(g, 0));
        for (var i : insights) { byType.merge(i.type(), 1, Integer::sum); byGrade.merge(i.grade(), 1, Integer::sum); }
        var unionCover = new UnionCover(union.evaluated(), union.covered(), union.notProven(), union.notEvaluable().entrySet().stream()
            .map(e -> Map.<String, Object>of("unionReason", e.getKey(), "rules", e.getValue())).toList());
        var partialShadow = new PartialShadow(partial.evaluated(), partial.partial(), partial.differentAction(), partial.notProven(), partial.notEvaluable().entrySet().stream()
            .map(e -> Map.<String, Object>of("partialReason", e.getKey(), "rules", e.getValue())).toList());
        return new Report(counts, List.copyOf(insights), byType, byGrade, snapshot.failures().isEmpty(), meta.collectedAt(), unionCover, partialShadow);
    }

    /** The reason that keeps a rule undecided; behind an earlier unreadable rule it is that rule's own reason. */
    private static String effectiveReason(PolicyHygiene.Assessment a, PolicyHygiene.Result hygiene) {
        String reason = a.shadowReason() == null || a.shadowReason().isBlank() ? PolicyQueryService.NO_REASON : a.shadowReason();
        if (PolicyHygiene.EARLIER_UNSUPPORTED.equals(reason) && a.blockingRuleId() != null) {
            var blocker = hygiene.rules().get(a.blockingRuleId());
            if (blocker != null && blocker.shadowReason() != null && !blocker.shadowReason().isBlank()
                    && !PolicyHygiene.EARLIER_UNSUPPORTED.equals(blocker.shadowReason())) return blocker.shadowReason();
        }
        return reason;
    }

    /** I1 (same action) and I2 (different action): the engine's own REDUNDANT / CONFLICT verdict, one earlier rule covering all four selectors. */
    private static void shadow(Metadata meta, Rule rule, PolicyHygiene.Assessment a, PolicyHygiene.Result hygiene, Section section, List<Insight> out) {
        if (a.coveringRuleId() == null || !Set.of("REDUNDANT", "CONFLICT").contains(a.shadowStatus())) return;
        String type = a.shadowStatus().equals("CONFLICT") ? "I2" : "I1";
        var evidence = a.findings().stream().filter(f -> Set.of("shadowed", "conflict").contains(f.findingClass())).map(PolicyHygiene.Finding::evidence).toList();
        var covering = section.rules().stream().filter(r -> r.id().equals(a.coveringRuleId())).findFirst();
        // The covering rule can sit in an earlier section of the same layer; its number is then unknown here and the id still links it.
        var refs = new ArrayList<RuleRef>();
        refs.add(new RuleRef(rule.id(), rule.number(), rule.name(), "subject"));
        refs.add(covering.map(c -> new RuleRef(c.id(), c.number(), c.name(), "covering")).orElseGet(() -> new RuleRef(a.coveringRuleId(), 0, "UNKNOWN", "covering")));
        out.add(new Insight(PolicySnapshot.ref("insight", meta.id(), type, rule.id()), type, PROVEN, "package", List.copyOf(refs), List.of(),
            PROVEN, NONE, List.of(INSTALL_STATE_NOT_VERIFIED), evidence, Map.of()));
    }

    /**
     * I3: the subject never matches because earlier rules of its layer together cover it (design section 4; G1 in
     * {@link PolicyRegion}). PROVEN with the covering set listed, whatever their actions: the action relation is evidence
     * and the {@code coverAction} counter, the same split I1/I2 make, so a DIFFERENT set reads as a question of intent.
     * An exhausted budget is NOT_EVALUABLE (design: "PROVEN or NOT_EVALUABLE (budget)"); nothing in this design raises it.
     */
    private static void unionCover(Metadata meta, Rule rule, PolicyUnionCover.Cover cover, List<Insight> out) {
        if (cover == null) return;
        String id = PolicySnapshot.ref("insight", meta.id(), "I3", rule.id());
        var subject = new RuleRef(rule.id(), rule.number(), rule.name(), "subject");
        if (!cover.status().equals(PolicyUnionCover.COVERED)) {
            out.add(new Insight(id, "I3", NOT_EVALUABLE, "package", List.of(subject), List.of(), PROVEN, NOT_RESOLVABLE_NEEDS, List.of(),
                List.of(I3_BUDGET), Map.of("unionReason", PolicyUnionCover.BUDGET)));
            return;
        }
        var refs = new ArrayList<RuleRef>();
        refs.add(subject);
        for (Rule c : cover.covering()) refs.add(new RuleRef(c.id(), c.number(), c.name(), "covering"));
        String evidence = switch (cover.coverAction()) {
            case PolicyUnionCover.DIFFERENT -> I3_DIFFERENT;
            case PolicyUnionCover.INLINE_LAYER -> I3_INLINE;
            default -> I3_SAME;
        };
        out.add(new Insight(id, "I3", PROVEN, "package", List.copyOf(refs), List.of(), PROVEN, NONE, List.of(INSTALL_STATE_NOT_VERIFIED),
            List.of(evidence), Map.of("coveringRules", cover.covering().size(), "coverAction", cover.coverAction())));
    }

    /**
     * I4: part of the subject's match is also matched by earlier rules of its layer, every one with the subject's action
     * (design section 4; G1 in {@link PolicyRegion}). PROVEN: the overlap is a proven intersection of two readable matches
     * for stored management intent. It explains a low counter; it does not say the rule can be moved (I7). A subject with
     * an overlapping rule of another action has no insight ({@link PolicyPartialShadow.Result#differentAction()} counts it).
     * The overlapping rules are listed up to {@link #MAX_OVERLAPPING}, the rest is the {@code moreOverlapping} counter. An
     * exhausted budget is NOT_EVALUABLE, as for I3.
     */
    private static void partialShadow(Metadata meta, Rule rule, PolicyPartialShadow.Partial partial, List<Insight> out) {
        if (partial == null) return;
        String id = PolicySnapshot.ref("insight", meta.id(), "I4", rule.id());
        var subject = new RuleRef(rule.id(), rule.number(), rule.name(), "subject");
        if (!partial.status().equals(PolicyPartialShadow.PARTIAL)) {
            out.add(new Insight(id, "I4", NOT_EVALUABLE, "package", List.of(subject), List.of(), PROVEN, NOT_RESOLVABLE_NEEDS, List.of(),
                List.of(I4_BUDGET), Map.of("partialReason", PolicyPartialShadow.BUDGET)));
            return;
        }
        if (!partial.sameAction()) return;
        var refs = new ArrayList<RuleRef>();
        refs.add(subject);
        for (Rule c : partial.overlapping().subList(0, Math.min(MAX_OVERLAPPING, partial.overlapping().size())))
            refs.add(new RuleRef(c.id(), c.number(), c.name(), "overlapping"));
        out.add(new Insight(id, "I4", PROVEN, "package", List.copyOf(refs), List.of(), PROVEN, NONE, List.of(INSTALL_STATE_NOT_VERIFIED),
            List.of(I4_PARTIAL), Map.of("overlappingRules", partial.overlapping().size(),
                "moreOverlapping", Math.max(0, partial.overlapping().size() - MAX_OVERLAPPING))));
    }

    /**
     * I10. The stored counters carry no window start (design F9) and Check Point's aggregation of cluster members is not
     * established (design section 3.3), so a zero or an old last hit is INDICATED. It is SUPPORTED only when a measured
     * window of at least the threshold is supplied and no gap (missing target counters, invalid timestamp) was found:
     * those gaps are UNKNOWN findings in the hygiene result, which produce no I10 at all.
     */
    private static void unused(Metadata meta, Rule rule, PolicyHygiene.Assessment a, Map<String, Integer> windowDays, int thresholdDays, List<Insight> out) {
        var finding = a.findings().stream().filter(f -> f.findingClass().equals("unused")).findFirst();
        if (finding.isEmpty()) return;
        Integer window = windowDays.get(rule.id());
        boolean known = window != null && window >= thresholdDays;
        var h = rule.hitCounts();
        Map<String, Object> counter = new LinkedHashMap<>();
        counter.put("hits", h == null ? null : h.hits()); counter.put("firstHit", h == null ? null : h.firstHit());
        counter.put("lastHit", h == null ? null : h.lastHit()); counter.put("hitSource", a.hitSource());
        counter.put("hitsCollectedAt", a.hitsCollectedAt()); counter.put("level", h == null ? null : h.level());
        counter.put("counterWindow", a.counterWindow()); counter.put("windowDays", window); counter.put("windowKnown", known);
        out.add(new Insight(PolicySnapshot.ref("insight", meta.id(), "I10", rule.id()), "I10", known ? SUPPORTED : INDICATED, "package",
            List.of(new RuleRef(rule.id(), rule.number(), rule.name(), "subject")), List.of(),
            SUPPORTED, known ? NONE : COUNTER_SAMPLES, List.of(), List.of(finding.get().evidence()), counter));
    }

    /**
     * I11 and I12 over the package's domain. An unused object is PROVEN only when the management's own unused-object report
     * says so and no stored reference contradicts it ({@link PolicyDomainIndex}: {@code unused} is TRUE only then); an object
     * with no stored reference and no report is an INDICATED lower bound. Duplicates are PROVEN: equal values and type.
     */
    private static void objects(Metadata meta, PolicyDomainIndex index, List<Insight> out) {
        for (var row : index.objects) {
            String uid = String.valueOf(row.get("uid")), type = String.valueOf(row.get("type"));
            boolean reported = Boolean.TRUE.equals(row.get("unused"));
            boolean noReference = number(row.get("ruleCount")) + number(row.get("groupCount")) == 0;
            if (!reported && !(row.get("unused") == null && noReference && "RESOLVED".equals(row.get("status"))
                    && !Set.of("any", "unresolved", "tag", "dynamic-object").contains(type) && !Boolean.TRUE.equals(row.get("managementInUse")))) continue;
            @SuppressWarnings("unchecked") var reasons = (List<String>) row.get("usageReasons");
            out.add(new Insight(PolicySnapshot.ref("insight", meta.id(), "I11", uid), "I11", reported ? PROVEN : INDICATED, "domain", List.of(), List.of(uid),
                PROVEN, reported ? NONE : VENDOR_REPORT, reported ? List.of(INSTALL_STATE_NOT_VERIFIED) : List.of(), List.of(),
                Map.of("usageReasons", reported ? List.of() : reasons == null ? List.of() : reasons, "ruleCount", number(row.get("ruleCount")), "groupCount", number(row.get("groupCount")))));
        }
        for (var group : index.duplicates) {
            @SuppressWarnings("unchecked") var members = (List<Map<String, Object>>) group.get("objects");
            var uids = members.stream().map(m -> String.valueOf(m.get("uid"))).sorted().toList();
            out.add(new Insight(PolicySnapshot.ref("insight", meta.id(), "I12", String.valueOf(group.get("id"))), "I12", PROVEN, "domain", List.of(), uids,
                PROVEN, NONE, List.of(INSTALL_STATE_NOT_VERIFIED), List.of(), Map.of("objects", uids.size())));
        }
    }

    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    private static Insight insight(Metadata meta, String type, String grade, Rule rule, List<String> objectIds, String needs, String raise,
            List<String> evidence, Map<String, Object> counter) {
        return new Insight(PolicySnapshot.ref("insight", meta.id(), type, rule.id()), type, grade, "package",
            List.of(new RuleRef(rule.id(), rule.number(), rule.name(), "subject")), objectIds, raise, needs,
            grade.equals(PROVEN) ? List.of(INSTALL_STATE_NOT_VERIFIED) : List.of(), evidence, counter);
    }
}
