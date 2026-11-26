package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.Budget;
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
        var meta = stored.metadata();
        if (!"CP".equals(meta.vendor())) return empty(stored, UNSUPPORTED_VENDOR);
        if (!stored.failures().isEmpty()) return empty(stored, INCOMPLETE);
        Budget budget = new Budget(workLimit);
        PolicySnapshot current = stored;
        PolicyPartialShadow.Result partial = candidates(current);
        List<String> order = subjects != null ? List.copyOf(subjects) : candidateIds(partial);
        if (order.isEmpty()) return empty(stored, NO_SUBJECT);
        PolicyReorder.Result reorder = null;
        List<Step> steps = new ArrayList<>();
        boolean stopped = false;
        for (String subject : order) {
            if (stopped) { steps.add(unproven(subject, null)); continue; }
            // A pool already used up proves nothing further: do not even run the (costly) analysis passes.
            if (reorder == null && budget.left[0] < 0) { stopped = true; steps.add(unproven(subject, null)); continue; }
            if (partial == null) partial = candidates(current);
            if (reorder == null) reorder = PolicyReorder.analyze(current, partial, budget, ruleLimit);
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
