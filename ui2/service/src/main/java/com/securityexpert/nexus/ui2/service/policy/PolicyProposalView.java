package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;

/**
 * Read-only view and per-snapshot single-flight cache over {@link PolicyProposal}
 * (POLICY_INSIGHTS_DESIGN_2026_11_24.md, FROZEN, sections 6.1, 6.4, 7 Check Point, 8 item 4, 9 phase P2;
 * PO_DECISION_RECORD_2026_11_24_POLICY_INSIGHTS.md Q2, Q3, Q7, Q8, Q9; phase P2 step c-2c).
 *
 * <p>Check Point packages only: another vendor answers {@code supported=false} like {@code /insights}.
 * Calls {@link PolicyProposal#allOfferedMoves(PolicySnapshot)} up to {@link #MAX_SUBJECTS} candidates in rule order
 * (and {@link PolicyProposal#propose(PolicySnapshot, List, int, int)} on the first {@link #MAX_SUBJECTS} when a package
 * has more, reporting {@code candidatesTotal}, {@code tried}, {@code notTried}, {@code truncated} and
 * {@code notTriedNote} so a truncated run is never presented as the whole package). Runs I5 merges on that proposal
 * in the same cached computation. Stores nothing, contacts nothing and generates no command text (Q7).
 */
public final class PolicyProposalView {

    /** Maximum candidate subjects tried in one proposal request, in stored rule order. */
    public static final int MAX_SUBJECTS = 25;
    /** Maximum rule identifiers returned per changed section in {@code order}; remaining rules are counted as "+N more". */
    public static final int MAX_ORDER_RULES = 200;
    /** Maximum merge attempts shown in {@code merges}; counts include all attempts and {@code mergesNote} marks the cap. */
    public static final int MAX_MERGE_STEPS = 25;
    static final int MAX_CACHED = 4;
    private static final int DAYS = 90;

    /** Fixed caveat string required on every Check Point proposal response (design 3.1, 6.1, 6.4; Q2, Q7). */
    public static final String CAVEAT =
            "Moves are stacked in the order shown: each accepted move is applied before proving the next. "
            + "The label \"no behaviour change\" is only ever given to an EQUIVALENT package of SAFE steps. "
            + "Install state is not verified (INSTALL_STATE_NOT_VERIFIED): results hold for stored management intent only, "
            + "and no device command is generated.";

    static final Set<String> LABELS = Set.of(PolicyProposal.LABEL_NO_CHANGE, PolicyProposal.LABEL_INSPECTION_CHANGES);
    static final Set<String> EMPTY_REASONS = Set.of(
            PolicyProposal.UNSUPPORTED_VENDOR, PolicyProposal.INCOMPLETE, PolicyProposal.NO_SUBJECT);
    static final Set<String> STEP_RESULTS;
    static {
        Set<String> results = new LinkedHashSet<>(PolicyReorder.OFFERED);
        results.addAll(PolicyProposal.REFUSALS);
        STEP_RESULTS = Collections.unmodifiableSet(results);
    }

    record Computed(Map<String, Object> fields) {}
    private record Cached(PolicySnapshot snapshot, CompletableFuture<Computed> result) {}

    @FunctionalInterface
    interface Computer {
        Computed compute(PolicySnapshot snapshot);
    }

    private final Map<String, Cached> cache = new LinkedHashMap<>(8, 0.75f, true);
    private final Semaphore slots;
    private volatile Computer computer = PolicyProposalView::compute;

    public PolicyProposalView() {
        this(new Semaphore(2, true));
    }

    PolicyProposalView(Semaphore slots) {
        this.slots = Objects.requireNonNull(slots, "slots");
    }

    void setComputer(Computer computer) {
        this.computer = Objects.requireNonNull(computer, "computer");
    }

    int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /**
     * Read-only proposal response for {@code snapshot}. Non-Check-Point packages answer {@code supported=false} without
     * running or caching an analysis.
     */
    public PolicyResponse response(PolicySnapshot snapshot, int days) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendor", snapshot.metadata().vendor());
        if (!"CP".equals(snapshot.metadata().vendor())) {
            body.put("supported", false);
            return new PolicyResponse(body);
        }
        Computed computed = cached(snapshot);
        body.put("supported", true);
        body.put("days", days);
        body.put("collectedAt", snapshot.metadata().collectedAt());
        body.put("complete", snapshot.failures().isEmpty());
        body.putAll(computed.fields());
        return new PolicyResponse(body);
    }

    private Computed cached(PolicySnapshot snapshot) {
        String id = snapshot.metadata().id();
        Cached entry;
        boolean run = false;
        synchronized (cache) {
            entry = cache.get(id);
            if (entry == null || entry.snapshot() != snapshot) {
                entry = new Cached(snapshot, new CompletableFuture<>());
                run = true;
                cache.put(id, entry);
                if (cache.size() > MAX_CACHED) {
                    cache.remove(cache.keySet().iterator().next());
                }
            }
        }
        if (run) {
            slots.acquireUninterruptibly();
            try {
                entry.result().complete(computer.compute(snapshot));
            } catch (RuntimeException | Error failed) {
                entry.result().completeExceptionally(failed);
                synchronized (cache) {
                    cache.remove(id, entry);
                }
            } finally {
                slots.release();
            }
        }
        try {
            return entry.result().join();
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            if (failed.getCause() instanceof Error cause) throw cause;
            throw failed;
        }
    }

    static Computed compute(PolicySnapshot stored) {
        return compute(stored, MAX_SUBJECTS, MAX_ORDER_RULES, PolicyProposal.TOTAL_WORK, PolicyProposal.RULE_WORK);
    }

    static Computed compute(PolicySnapshot stored, int maxSubjects, int maxOrderRules, int workLimit, int ruleLimit) {
        return compute(stored, maxSubjects, maxOrderRules, workLimit, ruleLimit,
                PolicyProposalMerges.TOTAL_WORK, PolicyProposalMerges.RULE_WORK);
    }

    static Computed compute(PolicySnapshot stored, int maxSubjects, int maxOrderRules, int workLimit, int ruleLimit,
            int mergeWorkLimit, int mergeRuleLimit) {
        int cap = Math.max(1, maxSubjects);
        int orderCap = Math.max(1, maxOrderRules);
        List<String> candidates = !"CP".equals(stored.metadata().vendor()) || !stored.failures().isEmpty()
                ? List.of()
                : candidateIds(stored);
        int candidatesTotal = candidates.size();
        PolicyProposal.Result result;
        if (candidatesTotal <= cap) {
            result = workLimit == PolicyProposal.TOTAL_WORK && ruleLimit == PolicyProposal.RULE_WORK
                    ? PolicyProposal.allOfferedMoves(stored)
                    : PolicyProposal.allOfferedMoves(stored, workLimit, ruleLimit);
        } else {
            result = PolicyProposal.propose(stored, candidates.subList(0, cap), workLimit, ruleLimit);
        }
        var merges = PolicyProposalMerges.allOfferedMerges(result, mergeWorkLimit, mergeRuleLimit);
        var combined = PolicyProposalMerges.combine(result, merges);
        int tried = result.steps().size();
        int notTried = Math.max(0, candidatesTotal - tried);
        boolean truncated = notTried > 0;
        Map<String, Rule> byId = new HashMap<>();
        for (Section section : stored.sections()) {
            for (Rule rule : section.rules()) {
                byId.put(rule.id(), rule);
            }
        }
        List<Map<String, Object>> steps = result.steps().stream().map(step -> stepView(step, byId)).toList();
        var mergeSteps = merges.steps().stream().limit(MAX_MERGE_STEPS).map(step -> mergeView(step, byId)).toList();
        int moreMerges = merges.steps().size() - mergeSteps.size();
        List<Map<String, Object>> order = sectionOrders(stored, combined.snapshot(), orderCap);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("packageVerdict", combined.packageVerdict());
        fields.put("label", combined.label());
        fields.put("accepted", result.accepted());
        fields.put("refused", result.refused());
        fields.put("inspectionChanges", combined.inspectionChanges());
        fields.put("budgetStopped", combined.budgetStopped());
        fields.put("emptyReason", result.emptyReason());
        fields.put("maxSubjects", cap);
        fields.put("candidatesTotal", candidatesTotal);
        fields.put("tried", tried);
        fields.put("notTried", notTried);
        fields.put("truncated", truncated);
        fields.put("notTriedNote", truncated ? "+" + notTried + " more not tried" : null);
        fields.put("steps", steps);
        fields.put("merges", mergeSteps);
        fields.put("mergesNote", moreMerges > 0 ? "+" + moreMerges + " more" : null);
        fields.put("mergesAccepted", merges.accepted());
        fields.put("mergesRefused", merges.refused());
        fields.put("mergesBudgetStopped", merges.budgetStopped());
        fields.put("mergesEmptyReason", merges.emptyReason());
        fields.put("order", order);
        fields.put("caveat", CAVEAT);
        fields.put("insightCaveats", List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED));
        return new Computed(Collections.unmodifiableMap(fields));
    }

    private static List<String> candidateIds(PolicySnapshot stored) {
        var hygiene = PolicyHygiene.analyze(stored, DAYS, Instant.EPOCH, PolicyHygiene.TOTAL_WORK);
        var partial = PolicyPartialShadow.analyze(stored, hygiene, PolicyUnionCover.analyze(stored, hygiene));
        List<String> ids = new ArrayList<>();
        partial.rules().forEach((id, p) -> {
            if (PolicyPartialShadow.PARTIAL.equals(p.status()) && p.sameAction() && !p.overlapping().isEmpty()) {
                ids.add(id);
            }
        });
        return ids;
    }

    private static Map<String, Object> stepView(PolicyProposal.Step step, Map<String, Rule> byId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subject", ruleRef(step.subject(), byId));
        row.put("above", ruleRef(step.above(), byId));
        row.put("result", step.result());
        row.put("reason", step.reason());
        row.put("accepted", step.accepted());
        row.put("equivalence", step.equivalence());
        row.put("crossedRules", step.crossedRules());
        row.put("inspectionChanges", step.inspectionChanges());
        return Collections.unmodifiableMap(row);
    }

    private static Map<String, Object> mergeView(PolicyProposalMerges.Step step, Map<String, Rule> byId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("surviving", ruleRef(step.surviving(), byId));
        row.put("merged", ruleRef(step.merged(), byId));
        row.put("differingCell", step.differingCell());
        row.put("result", step.result());
        row.put("reason", step.reason());
        row.put("accepted", step.accepted());
        row.put("equivalence", step.equivalence());
        return Collections.unmodifiableMap(row);
    }

    private static Map<String, Object> ruleRef(String ruleId, Map<String, Rule> byId) {
        if (ruleId == null) return null;
        Rule rule = byId.get(ruleId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", ruleId);
        out.put("ruleName", rule != null ? rule.name() : "UNKNOWN");
        out.put("ruleNumber", rule != null ? rule.number() : 0);
        return Collections.unmodifiableMap(out);
    }

    private static List<Map<String, Object>> sectionOrders(PolicySnapshot stored, PolicySnapshot proposed, int orderCap) {
        if (stored == proposed) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Section> beforeSections = stored.sections();
        List<Section> afterSections = proposed.sections();
        int count = Math.min(beforeSections.size(), afterSections.size());
        for (int s = 0; s < count; s++) {
            Section beforeSec = beforeSections.get(s);
            Section afterSec = afterSections.get(s);
            if (beforeSec == afterSec) continue;
            List<String> beforeAll = beforeSec.rules().stream().map(Rule::id).toList();
            List<String> afterAll = afterSec.rules().stream().map(Rule::id).toList();
            if (beforeAll.equals(afterAll)) continue;
            int total = beforeAll.size();
            int firstDiff = 0;
            int sharedSize = Math.min(total, afterAll.size());
            while (firstDiff < sharedSize && beforeAll.get(firstDiff).equals(afterAll.get(firstDiff))) {
                firstDiff++;
            }
            int offset = total <= orderCap || firstDiff < orderCap ? 0 : firstDiff;
            int end = Math.min(total, offset + orderCap);
            int moreRules = total - (end - offset);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sectionId", beforeSec.id());
            row.put("offset", offset);
            row.put("totalRules", total);
            row.put("before", List.copyOf(beforeAll.subList(offset, end)));
            row.put("after", List.copyOf(afterAll.subList(offset, Math.min(end, afterAll.size()))));
            row.put("moreRules", moreRules);
            row.put("more", moreRules > 0 ? "+" + moreRules + " more" : null);
            out.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(out);
    }

    /** Closed-vocabulary AIView projection for {@code refused}: only {@link PolicyProposal#REFUSALS} keys pass. */
    static Map<String, Object> maskRefused(Map<?, ?> refused) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String word : PolicyProposal.REFUSALS) {
            out.put(word, refused.get(word) instanceof Number n ? n : 0);
        }
        return out;
    }

    /** Closed-structure AIView projection for {@code order}: section and rule ids, counts and "+N more" only. */
    static List<Map<String, Object>> maskOrder(List<?> sections) {
        List<Map<String, Object>> out = new ArrayList<>(sections.size());
        for (Object item : sections) {
            if (!(item instanceof Map<?, ?> s)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            if (s.get("sectionId") instanceof String id) row.put("sectionId", id);
            row.put("offset", s.get("offset") instanceof Number n ? n : 0);
            row.put("totalRules", s.get("totalRules") instanceof Number n ? n : 0);
            row.put("before", s.get("before") instanceof List<?> b
                    ? b.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of());
            row.put("after", s.get("after") instanceof List<?> a
                    ? a.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of());
            row.put("moreRules", s.get("moreRules") instanceof Number n ? n : 0);
            row.put("more", s.get("more") instanceof String m && m.matches("\\+[0-9]+ more") ? m : null);
            out.add(row);
        }
        return List.copyOf(out);
    }
}
