package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.Budget;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.EvaluationScope;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.Resolver;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.Selector;
import java.time.Instant;
import java.util.*;

/**
 * The reorder-only proposal of POLICY_INSIGHTS_DESIGN_2026_11_24.md (FROZEN) sections 6.1 and 6.4, phase P2 step c-2b,
 * Check Point only, engine only: an ordered list of "promote r to just above B" moves ({@link PolicyReorder}), each proven
 * against the order the earlier moves produced ("Moves are applied one at a time against the current order, so the proof
 * of a later move reads the result of the earlier ones"). Pure: reads the stored snapshot, builds in-memory snapshots,
 * contacts nothing, stores nothing, mutates nothing and generates no command (decision record Q7).
 *
 * <p>The fixed readings, each from the design or its decision record:
 * <ul>
 * <li><b>G6 ({@link #reordered}).</b> One move builds a new {@link PolicySnapshot} in which only the one section's rule
 * list changes (r removed and re-inserted immediately above B, same section, so same layer and scope); metadata, objects,
 * failures, the other sections and every rule outside the moved stretch are the stored instances. The stored snapshot is
 * never touched. {@code Rule.number} is read as order by the Check Point layer check ({@link PolicyNormalization.CpLayers}:
 * "strictly increasing rule numbers" else the layer is "not analysed"), so the numbers of the moved stretch are reassigned
 * by position: the slot keeps its number, the rule in it changes. The section's set of numbers, its first and last number
 * and so the continuation into the next section are unchanged. The proposal object therefore carries slot numbers; the
 * stored number of a rule is read from the stored snapshot by rule id.</li>
 * <li><b>Per step, the current order.</b> For each subject, in the caller's order, the I3/I4 pass
 * ({@link PolicyUnionCover}, {@link PolicyPartialShadow}, at their own full budget as in the stored pass) and
 * {@link PolicyReorder#analyze} run on the CURRENT in-memory snapshot (the stored one plus the accepted moves so far); the
 * subject's own move is read from that and accepted only if its result is {@link PolicyReorder#OFFERED} (SAFE or
 * SAFE_INSPECTION_CHANGES). A refused step names the engine's own reason ({@link PolicyReorder#UNSAFE_DIFFERENT_EFFECT},
 * {@link PolicyReorder#ANCHOR_BLOCKED}, {@link PolicyReorder#CROSSES_SECTION}) or {@link #NO_LONGER_A_CANDIDATE} (the
 * earlier moves took away the subject's I4 overlap: it is wholly covered, redundant, or overlaps another action), leaves
 * the snapshot as it was and does not stop the next subject. The analysis of an unchanged snapshot is reused, so a refusal
 * costs no new pass; an accepted move invalidates it.</li>
 * <li><b>Scope-local passes.</b> After an accepted move only the evaluation scope (layer) whose section changed is
 * analysed again; the other scopes' I3/I4 verdicts and I7 moves are the previous step's, and the shared pool is charged
 * what the whole-snapshot pass would charge. The result is identical to {@link #proposeReference}, the whole-snapshot
 * passes at every step, for every input and every work limit; see {@link ScopeLocal} for what is reused, what is proven
 * and where a step gives way to the whole-snapshot passes.</li>
 * <li><b>Budget (Q8): one pool.</b> One {@link Budget} of {@code workLimit} units is passed to every {@link PolicyReorder}
 * pass of the proposal, with {@code ruleLimit} per rule (the parameters of {@link PolicyReorder#analyze}; the I3/I4 passes
 * keep the stored pass's own full budget, so the candidates are the stored pass's). A step that is
 * {@link PolicyReorder#NOT_PROVEN} (the pool or a rule's window used up, a crossed rule left unread, an internal check) is
 * recorded as such with {@link PolicyReorder#BUDGET}, and every remaining subject is then recorded NOT_PROVEN the same way
 * and the run stops: a later step is never judged on a snapshot that differs from the one a bigger budget would have
 * produced, so a limited run is a prefix of the full run and never accepts a move the full run refuses. Never a silent
 * skip, never an accepted move that was not proven.</li>
 * <li><b>Package verdict (design 6.4).</b> {@link PolicyReorder#EQUIVALENT} only when at least one move was accepted and
 * every accepted step is; {@link PolicyReorder#CHANGES} when an accepted step changes the deciding rule of some part
 * (only a SAFE_INSPECTION_CHANGES step can); {@link PolicyReorder#NOT_PROVEN} when the budget stopped the run; {@link #NO_MOVES}
 * when nothing was accepted. Equivalence composes: a step that keeps every packet's full effect against the order before
 * it leaves it unchanged against the stored order. Any accepted SAFE_INSPECTION_CHANGES step sets {@code inspectionChanges}
 * and the label {@link #LABEL_INSPECTION_CHANGES}; the label {@link #LABEL_NO_CHANGE} is only ever given to an
 * {@link PolicyReorder#EQUIVALENT} package of SAFE steps (decision record Q2).</li>
 * <li><b>Scope.</b> Only {@code r to just above B} as {@link PolicyReorder} defines it: never across a layer, never across
 * a section ({@link PolicyReorder#CROSSES_SECTION} is refused), never across an anchor (Q3), never a rule of another scope.
 * A snapshot of another vendor or with collection failures, or one with no subject, gives an empty proposal with
 * {@link Result#emptyReason()}, never an exception.</li>
 * </ul>
 * Counts and closed words only: no range, address, name or comment is read into the result.
 */
final class PolicyProposal {
    private PolicyProposal() {}
    static final int RULE_WORK = PolicyReorder.RULE_WORK, TOTAL_WORK = PolicyReorder.TOTAL_WORK;
    /** A refused step whose subject is no I4 rule of the current order, and the package verdict of a proposal with no move. */
    static final String NO_LONGER_A_CANDIDATE = "NO_LONGER_A_CANDIDATE", NO_MOVES = "NO_MOVES";
    /** Why a proposal has no step at all (closed words; the first two are the shadow engine's own). */
    static final String UNSUPPORTED_VENDOR = PolicyUnionCover.UNSUPPORTED_VENDOR, INCOMPLETE = PolicyUnionCover.INCOMPLETE,
        NO_SUBJECT = "no offered move";
    /** The package label (decision record Q2); null when nothing was accepted or the run was stopped. */
    static final String LABEL_NO_CHANGE = "no behaviour change", LABEL_INSPECTION_CHANGES = "verdict-safe, inspection changes";
    static final String BUDGET = PolicyReorder.BUDGET;
    static final Set<String> VERDICTS = Set.of(PolicyReorder.EQUIVALENT, PolicyReorder.CHANGES, PolicyReorder.NOT_PROVEN, NO_MOVES);
    /** The refusal words, in display order. */
    static final List<String> REFUSALS = List.of(PolicyReorder.UNSAFE_DIFFERENT_EFFECT, PolicyReorder.ANCHOR_BLOCKED,
        PolicyReorder.CROSSES_SECTION, PolicyReorder.NOT_PROVEN, NO_LONGER_A_CANDIDATE);
    /** The shadow verdicts do not depend on the threshold or the clock; these only fill the hygiene signature. */
    private static final int DAYS = 90;

    /**
     * One subject in the caller's order. {@code above}: the rule B it was to be promoted above in the order current at its
     * step (null when none was read: {@link #NO_LONGER_A_CANDIDATE}, or a run stopped before it). {@code result}: an offered
     * word when {@code accepted}, else a refusal word. {@code reason}: {@link #BUDGET} for NOT_PROVEN, else null.
     * {@code equivalence}: the step's, null for a refusal the engine does not prove. {@code crossedRules}: the enabled rules
     * the accepted move crossed (0 for a refusal). {@code inspectionChanges}: an accepted SAFE_INSPECTION_CHANGES step.
     */
    record Step(String subject, String above, String result, String reason, boolean accepted, String equivalence,
            int crossedRules, boolean inspectionChanges) {}
    /**
     * The proposal. {@code packageVerdict}/{@code label}: see the class comment. {@code accepted}, {@code refused} (per
     * {@link #REFUSALS} word, every word present) and {@code inspectionChanges} are counts over {@code steps}.
     * {@code budgetStopped}: the run stopped on a NOT_PROVEN step. {@code emptyReason}: why there is no step at all, else
     * null. {@code snapshot}: the final in-memory snapshot (G6), the stored instance when nothing was accepted.
     */
    record Result(String packageVerdict, String label, List<Step> steps, int accepted, Map<String, Integer> refused,
            boolean inspectionChanges, boolean budgetStopped, String emptyReason, PolicySnapshot snapshot) {}

    /** The I7 candidates of the stored order (one per I4 rule, in rule order), offered alone or not, proposed in that order. */
    static Result allOfferedMoves(PolicySnapshot stored) { return allOfferedMoves(stored, TOTAL_WORK, RULE_WORK); }
    static Result allOfferedMoves(PolicySnapshot stored, int workLimit, int ruleLimit) { return propose(stored, null, workLimit, ruleLimit); }
    static Result propose(PolicySnapshot stored, List<String> subjects) { return propose(stored, subjects, TOTAL_WORK, RULE_WORK); }

    /**
     * {@code subjects}: rule ids in the order to try them (null: every I7 candidate of the stored order in rule order). An
     * id that is no rule, or no I4 rule of the order current at its step, is a refused step, never an error.
     */
    static Result propose(PolicySnapshot stored, List<String> subjects, int workLimit, int ruleLimit) {
        return propose(stored, subjects, workLimit, ruleLimit, true, new Stats());
    }
    /** The whole-snapshot passes of every step, as before the scope-local passes: the oracle of the differential tests. */
    static Result proposeReference(PolicySnapshot stored, List<String> subjects, int workLimit, int ruleLimit) {
        return propose(stored, subjects, workLimit, ruleLimit, false, new Stats());
    }

    /**
     * {@code scopeLocal}: after an accepted move only the evaluation scope (layer) whose section changed is analysed again
     * (see {@link ScopeLocal}); the result is the same as with {@code false}, which runs the three whole-snapshot passes
     * after every accepted move. {@code stats} (never null) records what ran.
     */
    static Result propose(PolicySnapshot stored, List<String> subjects, int workLimit, int ruleLimit, boolean scopeLocal, Stats stats) {
        var meta = stored.metadata();
        if (!PolicyUnionCover.SUPPORTED_VENDORS.contains(meta.vendor())) return empty(stored, UNSUPPORTED_VENDOR);
        if (!stored.failures().isEmpty()) return empty(stored, INCOMPLETE);
        Budget budget = new Budget(workLimit);
        Analysis analysis = scopeLocal ? ScopeLocal.of(stored, stats) : null;
        if (analysis == null) analysis = new WholeSnapshot(stats);
        PolicySnapshot current = stored;
        PolicyPartialShadow.Result partial = analysis.candidates(current);
        List<String> order = subjects != null ? List.copyOf(subjects) : candidateIds(partial);
        if (order.isEmpty()) return empty(stored, NO_SUBJECT);
        PolicyReorder.Result reorder = null;
        List<Step> steps = new ArrayList<>();
        boolean stopped = false;
        for (String subject : order) {
            if (stopped) { steps.add(unproven(subject, null)); continue; }
            // A pool already used up proves nothing further: do not even run the (costly) analysis passes.
            if (reorder == null && budget.left[0] < 0) { stopped = true; steps.add(unproven(subject, null)); continue; }
            if (partial == null) partial = analysis.candidates(current);
            if (reorder == null) reorder = analysis.reorder(current, partial, budget, ruleLimit);
            var move = reorder.moves().get(subject);
            if (move == null) {
                var p = partial.rules().get(subject);
                if (p != null && PolicyPartialShadow.NOT_PROVEN.equals(p.status())) { stopped = true; steps.add(unproven(subject, null)); }
                else steps.add(new Step(subject, null, NO_LONGER_A_CANDIDATE, null, false, null, 0, false));
                continue;
            }
            String above = move.above().rule().id();
            if (PolicyReorder.NOT_PROVEN.equals(move.result())) { stopped = true; steps.add(unproven(subject, above)); continue; }
            if (!PolicyReorder.OFFERED.contains(move.result())) {
                steps.add(new Step(subject, above, move.result(), null, false, null, 0, false));
                continue;
            }
            PolicySnapshot next;
            try { next = reordered(current, subject, above); }
            catch (IllegalStateException breach) {
                // An internal-invariant breach must not drop the response: this step is not proven and the run stops.
                stopped = true; steps.add(unproven(subject, above)); continue;
            }
            current = next; partial = null; reorder = null;
            analysis.accepted(subject);
            steps.add(new Step(subject, above, move.result(), null, true, move.equivalence(), move.crossed().size(),
                PolicyReorder.SAFE_INSPECTION_CHANGES.equals(move.result())));
        }
        return result(steps, stopped, current);
    }

    /**
     * G6: the snapshot after promoting {@code subjectId} to just above {@code aboveId}. Both must be rules of one section
     * with the subject later than the other, else {@link IllegalStateException} (a defect of the caller, never data). The
     * stored snapshot is not mutated; see the class comment for the instances shared and for {@code Rule.number}.
     */
    static PolicySnapshot reordered(PolicySnapshot snapshot, String subjectId, String aboveId) {
        List<Section> sections = new ArrayList<>(snapshot.sections());
        for (int s = 0; s < sections.size(); s++) {
            Section section = sections.get(s);
            int at = indexOf(section.rules(), subjectId);
            if (at < 0) continue;
            int to = indexOf(section.rules(), aboveId);
            if (to < 0 || to >= at) throw new IllegalStateException("G6: the rule to promote above is not an earlier rule of the subject's section");
            List<Rule> before = section.rules(), after = new ArrayList<>(before);
            Rule moved = after.remove(at);
            after.add(to, moved);
            // Positional numbering: the slot keeps its number, so the layer check still sees strictly increasing numbers.
            for (int i = to; i <= at; i++) after.set(i, withNumber(after.get(i), before.get(i).number()));
            sections.set(s, new Section(section.id(), section.name(), section.source(), section.parentRuleId(), after));
            return new PolicySnapshot(snapshot.metadata(), sections, snapshot.objects(), snapshot.failures());
        }
        throw new IllegalStateException("G6: the rule to promote is not in the snapshot");
    }

    private static int indexOf(List<Rule> rules, String id) {
        for (int i = 0; i < rules.size(); i++) if (rules.get(i).id().equals(id)) return i;
        return -1;
    }
    private static Rule withNumber(Rule r, int number) {
        return r.number() == number ? r : new Rule(r.id(), r.uuid(), number, r.name(), r.enabled(), r.source(), r.destination(), r.service(),
            r.application(), r.action(), r.log(), r.comment(), r.extras(), r.hitCounts());
    }

    /** The I3/I4 pass of one snapshot at the stored pass's own budgets (the hygiene result they read is not kept). */
    private static PolicyPartialShadow.Result candidates(PolicySnapshot snapshot) {
        var hygiene = PolicyHygiene.analyze(snapshot, DAYS, Instant.EPOCH, PolicyHygiene.TOTAL_WORK);
        return PolicyPartialShadow.analyze(snapshot, hygiene, PolicyUnionCover.analyze(snapshot, hygiene));
    }
    /** The I4 rules that have a move, in rule order (the same set as {@link PolicyReorder.Result#moves()}). */
    private static List<String> candidateIds(PolicyPartialShadow.Result partial) {
        List<String> ids = new ArrayList<>();
        partial.rules().forEach((id, p) -> { if (PolicyPartialShadow.PARTIAL.equals(p.status()) && p.sameAction() && !p.overlapping().isEmpty()) ids.add(id); });
        return ids;
    }

    /** What one proposal ran, for the tests: per scope, how often its I3/I4 and its I7 pass ran, and the whole-snapshot passes. */
    static final class Stats {
        /** 0: the scope-local passes did not apply (or were not asked for) and every pass was a whole-snapshot pass. */
        int scopes;
        int[] candidateRuns = new int[0], reorderRuns = new int[0];
        /** Whole-snapshot I3/I4 and I7 passes (every pass of the reference path; the fallbacks of the scope-local one). */
        int wholeCandidatePasses, wholeReorderPasses;
        /** Times the scope-local path gave way to a whole-snapshot pass for lack of a proof that it charges the same. */
        int fallbacks;
    }

    /** The two analyses a step reads: the I3/I4 candidates and the I7 moves of the current order. */
    private interface Analysis {
        PolicyPartialShadow.Result candidates(PolicySnapshot current);
        PolicyReorder.Result reorder(PolicySnapshot current, PolicyPartialShadow.Result partial, Budget budget, int ruleLimit);
        /** The move of {@code subjectId} was accepted: the next analysis reads the new order. */
        void accepted(String subjectId);
    }

    /** The reference: the three whole-snapshot I3/I4 passes and the whole-snapshot I7 pass at every step. */
    private static final class WholeSnapshot implements Analysis {
        private final Stats stats;
        WholeSnapshot(Stats stats) { this.stats = stats; }
        @Override public PolicyPartialShadow.Result candidates(PolicySnapshot current) { stats.wholeCandidatePasses++; return PolicyProposal.candidates(current); }
        @Override public PolicyReorder.Result reorder(PolicySnapshot current, PolicyPartialShadow.Result partial, Budget budget, int ruleLimit) {
            stats.wholeReorderPasses++;
            return PolicyReorder.analyze(current, partial, budget, ruleLimit);
        }
        @Override public void accepted(String subjectId) {}
    }

    /**
     * Scope-local passes. A move changes the rule list of one section, so only the rules of its evaluation scope (a layer:
     * the sections that share one {@link EvaluationScope} state; nothing crosses a layer, Q9) see a different earlier-rule
     * list. The I3/I4 verdicts and the I7 moves of every other scope are what the previous step computed, so they are not
     * computed again. Each scope is analysed on a snapshot of its own sections (same metadata, objects and failures), which
     * the engines read exactly as they read those sections in the whole snapshot. What is not scope-local, and how it is
     * kept exact (a verdict is never approximated: where a proof is missing the step runs the whole-snapshot passes):
     * <ul>
     * <li><b>The order of the sections.</b> Whether a section's place in the order is proven is decided over all sections.
     * The scopes are used only when every section is proven, each scope is one contiguous run of sections and a scope
     * alone is proven as one state; else (an inline layer, a layer seen again, a number anomaly) every pass is whole.</li>
     * <li><b>The I3/I4 budgets (Q8, fresh per pass).</b> The three passes share nothing between scopes but their pool
     * ({@link PolicyUnionCover#TOTAL_WORK}) and the object memo, which only ever lowers a charge. Each scope runs with
     * {@code TOTAL_WORK / scopes} units from a cold memo, and is used only if no unit was refused anywhere: the whole
     * snapshot's charge is then at most the sum, which is at most the pool, so the whole-snapshot pass refuses nothing
     * either and its verdicts are budget-independent, hence the scopes' verdicts. Else the step is a whole pass. The
     * object memo can change a verdict only through the nesting depth limit of a group ({@link Resolver#object}), so
     * groups nested deeper than {@link #SAFE_NESTING} switch the scopes off.</li>
     * <li><b>The shared I7 pool (Q8).</b> Every step charges the one pool for the whole snapshot's I7 pass. A scope's pass
     * is run with the object memo of the scopes before it (as the whole pass has it: the set of objects resolved after a
     * scope does not depend on the order of its rules) and its charge is recorded; a step charges the sum of the scopes'
     * recorded charges to the pool, exactly what the whole pass charges. If the pool would run out inside the step, or any
     * unit was refused, the step is the whole pass on the real pool, so the stop point of every work limit is the whole
     * pass's. The object set a scope leaves is checked against the one it left before; a difference recomputes the scopes
     * after it.</li>
     * </ul>
     */
    private static final class ScopeLocal implements Analysis {
        /** Group nesting up to here cannot reach the resolver's depth limit (32), so the object memo cannot change a verdict. */
        static final int SAFE_NESTING = 20;
        private final PolicySnapshot stored;
        private final Stats stats;
        /** Per scope: the indices of its sections in the snapshot, in order. */
        private final int[][] sections;
        private final Map<String, Integer> scopeOf = new HashMap<>();
        private final int pool;
        private final PolicyPartialShadow.Result[] partials;
        private final PolicyReorder.Result[] moves;
        private final int[] charge;
        /** Per scope, the object memo after its I7 pass, which seeds the next scope's pass. */
        private final List<Map<String, Selector>> memoAfter = new ArrayList<>();
        private final boolean[] reorderValid;
        private boolean usable = true;

        private ScopeLocal(PolicySnapshot stored, int[][] sections, Stats stats) {
            this.stored = stored; this.sections = sections; this.stats = stats;
            int n = sections.length;
            pool = PolicyUnionCover.TOTAL_WORK / n;
            partials = new PolicyPartialShadow.Result[n]; moves = new PolicyReorder.Result[n]; charge = new int[n]; reorderValid = new boolean[n];
            for (int g = 0; g < n; g++) {
                memoAfter.add(null);
                for (int idx : sections[g]) for (Rule rule : stored.sections().get(idx).rules()) scopeOf.put(rule.id(), g);
            }
            stats.scopes = n; stats.candidateRuns = new int[n]; stats.reorderRuns = new int[n];
        }

        /** The scope-local passes of {@code stored}, or null where they do not apply (every pass is then whole). */
        static ScopeLocal of(PolicySnapshot stored, Stats stats) {
            if (!shallow(stored.objects())) return null;
            var meta = stored.metadata();
            var states = new EvaluationScope<Object>(meta, Object::new);
            List<List<Integer>> runs = new ArrayList<>();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Object last = null;
            for (int i = 0; i < stored.sections().size(); i++) {
                Section section = stored.sections().get(i);
                if (PolicySnapshot.outsideSecurityEvaluation(meta, section) || section.rules().isEmpty()) continue;
                Object state = states.advance(section);
                if (states.unproven) return null;
                if (state != last) {
                    if (!seen.add(state)) return null; // a scope seen again after another: not one contiguous run
                    runs.add(new ArrayList<>());
                    last = state;
                }
                runs.get(runs.size() - 1).add(i);
            }
            if (runs.isEmpty()) return null;
            int[][] sections = new int[runs.size()][];
            for (int g = 0; g < sections.length; g++) {
                sections[g] = runs.get(g).stream().mapToInt(Integer::intValue).toArray();
                // The scope alone must read as one proven state, as it does inside the whole snapshot.
                var alone = new EvaluationScope<Object>(meta, Object::new);
                Object one = null;
                for (int idx : sections[g]) {
                    Object state = alone.advance(stored.sections().get(idx));
                    if (alone.unproven || one != null && state != one) return null;
                    one = state;
                }
            }
            return new ScopeLocal(stored, sections, stats);
        }

        /** The deepest chain of group members is shallow enough that no memo hit can stand in for the resolver's depth limit. */
        private static boolean shallow(Map<String, PolicyObject> objects) {
            Map<String, Integer> depth = new HashMap<>();
            for (String id : objects.keySet()) if (depth(id, objects, depth, new HashSet<>()) > SAFE_NESTING) return false;
            return true;
        }
        private static int depth(String id, Map<String, PolicyObject> objects, Map<String, Integer> known, Set<String> path) {
            Integer cached = known.get(id);
            if (cached != null) return cached;
            PolicyObject o = objects.get(id);
            if (o == null || o.members().isEmpty() || !path.add(id)) return 1;
            int deepest = 0;
            for (String member : o.members()) {
                deepest = Math.max(deepest, depth(member, objects, known, path));
                if (deepest > SAFE_NESTING) break;
            }
            path.remove(id);
            int result = deepest + 1;
            known.put(id, result);
            return result;
        }

        private PolicySnapshot scope(PolicySnapshot current, int g) {
            List<Section> own = new ArrayList<>(sections[g].length);
            for (int idx : sections[g]) own.add(current.sections().get(idx));
            return new PolicySnapshot(current.metadata(), own, current.objects(), current.failures());
        }

        @Override public PolicyPartialShadow.Result candidates(PolicySnapshot current) {
            if (!usable) { stats.wholeCandidatePasses++; return PolicyProposal.candidates(current); }
            for (int g = 0; g < sections.length; g++) {
                if (partials[g] != null) continue;
                var own = scope(current, g);
                var probe = new ArrayList<Budget>();
                Budget.PROBE.set(probe);
                try {
                    var hygiene = PolicyHygiene.analyze(own, DAYS, Instant.EPOCH, pool);
                    var union = PolicyUnionCover.analyze(own, hygiene, pool, PolicyUnionCover.RULE_WORK);
                    var partial = PolicyPartialShadow.analyze(own, hygiene, union, pool, PolicyPartialShadow.RULE_WORK);
                    if (probe.stream().anyMatch(Budget::refused)) { usable = false; stats.fallbacks++; stats.wholeCandidatePasses++; return PolicyProposal.candidates(current); }
                    partials[g] = partial; stats.candidateRuns[g]++;
                } finally { Budget.PROBE.remove(); }
            }
            Map<String, PolicyPartialShadow.Partial> rules = new LinkedHashMap<>();
            int evaluated = 0, partial = 0, differentAction = 0, notProven = 0;
            for (var each : partials) {
                rules.putAll(each.rules());
                evaluated += each.evaluated(); partial += each.partial(); differentAction += each.differentAction(); notProven += each.notProven();
            }
            Map<String, Integer> notEvaluable = new LinkedHashMap<>();
            for (String reason : PolicyPartialShadow.NOT_EVALUABLE_REASONS) {
                int sum = 0;
                for (var each : partials) sum += each.notEvaluable().getOrDefault(reason, 0);
                if (sum > 0) notEvaluable.put(reason, sum);
            }
            return new PolicyPartialShadow.Result(Collections.unmodifiableMap(rules), evaluated, partial, differentAction, notProven, Collections.unmodifiableMap(notEvaluable));
        }

        @Override public PolicyReorder.Result reorder(PolicySnapshot current, PolicyPartialShadow.Result partial, Budget budget, int ruleLimit) {
            boolean anyCandidate = partial.rules().values().stream().anyMatch(p -> PolicyPartialShadow.PARTIAL.equals(p.status()) && p.sameAction() && !p.overlapping().isEmpty());
            // Nothing to move: the whole pass reads and charges nothing either.
            if (!usable || !anyCandidate) return whole(current, partial, budget, ruleLimit);
            long total = 0;
            for (int g = 0; g < sections.length; g++) {
                if (!reorderValid[g] && !runReorder(current, g, ruleLimit)) { usable = false; stats.fallbacks++; return whole(current, partial, budget, ruleLimit); }
                total += charge[g];
            }
            // The pool would run out inside this pass: which unit is refused is the whole pass's to say.
            if (total > budget.left[0]) { stats.fallbacks++; return whole(current, partial, budget, ruleLimit); }
            budget.left[0] -= (int) total;
            Map<String, PolicyReorder.Move> all = new LinkedHashMap<>();
            int evaluated = 0, safe = 0, inspection = 0, unsafe = 0, anchor = 0, crosses = 0, notProven = 0;
            for (var each : moves) {
                all.putAll(each.moves());
                evaluated += each.evaluated(); safe += each.safe(); inspection += each.safeInspectionChanges(); unsafe += each.unsafeDifferentEffect();
                anchor += each.anchorBlocked(); crosses += each.crossesSection(); notProven += each.notProven();
            }
            return new PolicyReorder.Result(Collections.unmodifiableMap(all), evaluated, safe, inspection, unsafe, anchor, crosses, notProven, partial.notEvaluable());
        }

        private PolicyReorder.Result whole(PolicySnapshot current, PolicyPartialShadow.Result partial, Budget budget, int ruleLimit) {
            stats.wholeReorderPasses++;
            return PolicyReorder.analyze(current, partial, budget, ruleLimit);
        }

        /** The I7 pass of scope {@code g} on its own pool, charged as the whole pass charges it; false when a unit was refused. */
        private boolean runReorder(PolicySnapshot current, int g, int ruleLimit) {
            var own = scope(current, g);
            var alone = new Budget(Integer.MAX_VALUE);
            var resolver = Resolver.shadowing(own, alone);
            if (g > 0) resolver.memo.putAll(memoAfter.get(g - 1));
            var result = PolicyReorder.analyze(own, partials[g], alone, ruleLimit, resolver, true);
            if (alone.refused() || result.notProven() > 0) return false;
            var before = memoAfter.get(g);
            moves[g] = result; charge[g] = Integer.MAX_VALUE - alone.left[0]; reorderValid[g] = true; stats.reorderRuns[g]++;
            memoAfter.set(g, resolver.memo);
            // The objects a scope resolves do not depend on the order of its rules; should they, the later scopes are stale.
            if (before != null && !before.keySet().equals(resolver.memo.keySet())) for (int h = g + 1; h < sections.length; h++) reorderValid[h] = false;
            return true;
        }

        @Override public void accepted(String subjectId) {
            int g = scopeOf.get(subjectId);
            partials[g] = null; reorderValid[g] = false;
        }
    }

    private static Step unproven(String subject, String above) {
        return new Step(subject, above, PolicyReorder.NOT_PROVEN, BUDGET, false, PolicyReorder.NOT_PROVEN, 0, false);
    }
    private static Result empty(PolicySnapshot stored, String reason) {
        return new Result(NO_MOVES, null, List.of(), 0, refusals(List.of()), false, false, reason, stored);
    }
    private static Map<String, Integer> refusals(List<Step> steps) {
        Map<String, Integer> refused = new LinkedHashMap<>();
        for (String word : REFUSALS) refused.put(word, 0);
        for (Step step : steps) if (!step.accepted()) refused.merge(step.result(), 1, Integer::sum);
        return Collections.unmodifiableMap(refused);
    }
    private static Result result(List<Step> steps, boolean stopped, PolicySnapshot snapshot) {
        int accepted = 0;
        boolean inspection = false, changes = false;
        for (Step step : steps) {
            if (!step.accepted()) continue;
            accepted++;
            inspection |= step.inspectionChanges();
            changes |= !PolicyReorder.EQUIVALENT.equals(step.equivalence());
        }
        String verdict = stopped ? PolicyReorder.NOT_PROVEN : accepted == 0 ? NO_MOVES : changes ? PolicyReorder.CHANGES : PolicyReorder.EQUIVALENT;
        String label = stopped || accepted == 0 ? null : inspection ? LABEL_INSPECTION_CHANGES : PolicyReorder.EQUIVALENT.equals(verdict) ? LABEL_NO_CHANGE : null;
        return new Result(verdict, label, List.copyOf(steps), accepted, refusals(steps), inspection, stopped, null, snapshot);
    }
}
