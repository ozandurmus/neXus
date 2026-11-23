package com.securityexpert.nexus.ui2.service.zonematrix;

import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.EvaluationResult;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.FindingIdentity;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.RuleEvaluation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finding lifecycle for one policy source (docs/design/ZONE_MATRIX_CONTRACT.md section 5.9; {@code ZM-25},
 * {@code ZM-34}, {@code ZM-36} second half, {@code ZM-42}).
 *
 * <p>Pure and deterministic: no Spring, no database, no clock ({@code now} is a parameter). The caller is the step 4
 * storage layer (durable runs, not yet built); it loads the stored findings of one policy source, passes the new
 * {@link EvaluationResult} of a {@code COMPLETE} run and persists the returned set in the same transaction as the
 * run's {@code COMPLETE} state. Nothing here is wired into {@code ZoneMatrixService} or a controller.</p>
 *
 * <h2>Identity</h2>
 * A finding is {@code (policy source, rule uid, inline parent rule id path, violated zone pair set)}, the existing
 * {@link FindingIdentity}. Every id and pair string is compared with {@code String.equals}: two uids that differ only
 * by case or a leading zero are two findings (identity law, no normalisation). The pair set is compared as a set (the
 * evaluator emits it sorted, so order carries no meaning); the parent path is compared as an ordered list.
 *
 * <h2>Transitions of one run</h2>
 * Matching of an open finding against the run is by its <em>rule key</em> {@code (policy source, rule uid, parent
 * path)}, because a rule that stopped violating is emitted with an empty pair set and would never equal the finding
 * identity. Highest priority first:
 * <ol>
 *   <li>The run holds a {@code VIOLATION} with the exact same identity: {@link Transition#REPRODUCED}. Flags are
 *       replaced by the run's flags (a toggle such as {@code COVERED_BY_EARLIER_DENY} never touches
 *       {@code firstObservedAt}, {@code ZM-25}); {@code BECAME_UNDECIDED} is cleared.</li>
 *   <li>The rule is present but {@code NOT_EVALUABLE}, {@code UNKNOWN} (or any outcome this class does not know):
 *       {@link Transition#BECAME_UNDECIDED}. The finding stays open, gets the flag {@value #FLAG_BECAME_UNDECIDED},
 *       keeps its last proven {@code lastObservedAt} and version (undecided is not a reproduction), and records the
 *       new outcome and reason ({@code ZM-34}). This holds in a complete and in an incomplete run.</li>
 *   <li>The run is not {@code COMPLETE} (coverage {@code INCOMPLETE}, not evaluated, any collection failure, or a
 *       coverage string this class does not know): the finding is carried over unchanged as
 *       {@link Transition#STILL_OPEN_NOT_COLLECTED}; {@code lastObservedAt} is not advanced. A rule missing from an
 *       uncollected scope is never resolved ({@code ZM-34}), and neither is one that merely looks compliant in an
 *       incompletely collected policy.</li>
 *   <li>Complete run, rule present and decided {@code COMPLIANT}, or present with a different violating pair set:
 *       {@link Transition#RESOLVED} with {@link ResolutionReason#RULE_CHANGED}, or
 *       {@link ResolutionReason#MATRIX_VERSION_CHANGED} when the version the finding was last proven against differs
 *       from the run's version (the finding was decided by another matrix; the engine cannot tell whether the rule
 *       also changed, so the version is named, which is what {@code ZM-36} lists).</li>
 *   <li>Complete run, rule absent from the evaluated rules (or reported {@code NOT_APPLICABLE}):
 *       {@link Transition#RESOLVED} with {@link ResolutionReason#RULE_REMOVED_OR_DISABLED}. The evaluator reports
 *       disabled, deny-type, ended-schedule and out-of-rulebase rules only as counts, so those causes cannot be told
 *       apart from removal and share this reason.</li>
 *   <li>A {@code VIOLATION} in the run that no open finding matches exactly: {@link Transition#NEW}.</li>
 * </ol>
 * Only a {@code VIOLATION} creates a finding; an undecided rule with no open finding creates nothing here (the
 * evaluation rows carry it).
 *
 * <h2>Pair sets that grow or shrink</h2>
 * The pair set is part of the identity, so a rule whose violating pair set changes is a different finding: the old
 * finding is {@code RESOLVED} (reason as in step 4 above, {@link Finding#supersededBy()} names the new identity) and
 * the new set opens as {@code NEW} with its own {@code firstObservedAt}. In an incomplete run the old finding stays
 * open ({@code STILL_OPEN_NOT_COLLECTED}) next to the new one and is resolved by the next complete run.
 * "Since when" is carried by the any-version date, which is kept <em>per pair</em>
 * ({@link Finding#pairFirstObserved()}): the earliest observation of {@code (policy source, rule uid, pair)} in any
 * version, any parent path and any status of the history passed in (resolved findings count). A pair never seen
 * before is dated {@code now}. {@link Finding#firstObservedAnyVersion()} is the earliest of the finding's pair dates, so
 * a set that grows by one pair still reports the date the older pair began and a shrinking set loses nothing. A stored
 * finding that has no per-pair map supplies its single {@code firstObservedAnyVersion} for every pair of its set: a
 * lower bound that can only make "since when" earlier, never later, so it never hides history.
 *
 * <h2>Reappearance</h2>
 * A violation that returns after its finding was resolved is a new finding ({@code NEW}); it inherits the
 * any-version date from the resolved history ({@code ZM-42}) but not {@code firstObservedAt}.
 */
public final class ZoneMatrixFindingLifecycle {
    private ZoneMatrixFindingLifecycle() {}

    /** Flag set on a finding whose rule turned {@code NOT_EVALUABLE} or {@code UNKNOWN} (5.9). */
    public static final String FLAG_BECAME_UNDECIDED = "BECAME_UNDECIDED";

    public enum Transition { NEW, REPRODUCED, BECAME_UNDECIDED, RESOLVED, STILL_OPEN_NOT_COLLECTED }

    public enum ResolutionReason { RULE_CHANGED, RULE_REMOVED_OR_DISABLED, MATRIX_VERSION_CHANGED }

    public enum Status { OPEN, RESOLVED }

    /**
     * A finding as stored from earlier runs of one policy source. {@code status RESOLVED} entries only feed the
     * any-version history; they are not emitted again. {@code firstObservedAnyVersion} must not be later than
     * {@code firstObservedAt}.
     */
    public record KnownFinding(
            FindingIdentity identity,
            Status status,
            Instant firstObservedAt,
            Instant firstObservedAnyVersion,
            Map<String, Instant> pairFirstObserved,
            Instant lastObservedAt,
            String outcome,
            Set<String> flags,
            String versionId) {
        public KnownFinding {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            Objects.requireNonNull(firstObservedAnyVersion, "firstObservedAnyVersion");
            Objects.requireNonNull(lastObservedAt, "lastObservedAt");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(versionId, "versionId");
            pairFirstObserved = pairFirstObserved == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(pairFirstObserved));
            flags = flags == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(flags));
        }

        /** Convenience form without a per-pair map (see the class javadoc for the fallback). */
        public KnownFinding(
                FindingIdentity identity,
                Status status,
                Instant firstObservedAt,
                Instant firstObservedAnyVersion,
                Instant lastObservedAt,
                String outcome,
                Set<String> flags,
                String versionId) {
            this(identity, status, firstObservedAt, firstObservedAnyVersion, Map.of(), lastObservedAt, outcome, flags,
                    versionId);
        }
    }

    /** One finding of the next set, with the transition this run applied to it. */
    public record Finding(
            FindingIdentity identity,
            Transition transition,
            String outcome,
            String reason,
            Set<String> flags,
            Instant firstObservedAt,
            Instant firstObservedAnyVersion,
            Map<String, Instant> pairFirstObserved,
            Instant lastObservedAt,
            String versionId,
            ResolutionReason resolutionReason,
            Instant resolvedAt,
            String resolvedFromVersionId,
            String resolvedInVersionId,
            FindingIdentity supersededBy) {
        public Finding {
            flags = Collections.unmodifiableSet(new LinkedHashSet<>(flags));
            pairFirstObserved = Collections.unmodifiableMap(new LinkedHashMap<>(pairFirstObserved));
        }

        public Status status() {
            return transition == Transition.RESOLVED ? Status.RESOLVED : Status.OPEN;
        }

        /** The stored form of this finding, used as input of the next run. */
        public KnownFinding toKnown() {
            return new KnownFinding(identity, status(), firstObservedAt, firstObservedAnyVersion, pairFirstObserved,
                    lastObservedAt, outcome, flags, versionId);
        }
    }

    /** Next set of findings of one policy source after one run. */
    public record Result(String policyId, String versionId, boolean resolutionAllowed, List<Finding> findings) {
        public Result {
            findings = List.copyOf(findings);
        }

        public List<Finding> open() {
            return findings.stream().filter(f -> f.status() == Status.OPEN).toList();
        }

        public List<Finding> resolved() {
            return findings.stream().filter(f -> f.status() == Status.RESOLVED).toList();
        }

        public Map<String, List<Finding>> resolvedByVersionChange() {
            return ZoneMatrixFindingLifecycle.resolvedByVersionChange(findings);
        }
    }

    /**
     * Applies one {@code COMPLETE} run to the known findings of its policy source.
     *
     * @param known findings stored for this policy source, open and resolved
     * @param run   the evaluation of the run; its policy id must equal the policy id of every known finding
     * @param now   the time of the run
     */
    public static Result next(List<KnownFinding> known, EvaluationResult run, Instant now) {
        Objects.requireNonNull(known, "known");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(run.version(), "run.version");
        String policyId = Objects.requireNonNull(run.policyId(), "run.policyId");
        String runVersionId = run.version().versionId();
        for (KnownFinding k : known) {
            if (!policyId.equals(k.identity().policyId())) {
                throw new IllegalArgumentException("known finding belongs to another policy source");
            }
        }
        boolean complete = run.evaluated() && "COMPLETE".equals(run.coverage()) && run.failureCount() == 0;

        Map<RuleKey, List<RuleEvaluation>> byRule = new LinkedHashMap<>();
        for (RuleEvaluation ev : run.rules()) {
            byRule.computeIfAbsent(ruleKey(ev.identity()), k -> new ArrayList<>()).add(ev);
        }
        Map<PairKey, Instant> history = pairHistory(known);

        List<Finding> out = new ArrayList<>();
        Set<FullKey> openKeys = new LinkedHashSet<>();

        for (KnownFinding prior : known) {
            if (prior.status() != Status.OPEN) continue;
            if (!openKeys.add(fullKey(prior.identity()))) {
                throw new IllegalArgumentException("two open findings share one identity");
            }
        }

        for (KnownFinding prior : known) {
            if (prior.status() != Status.OPEN) continue;
            FindingIdentity id = prior.identity();
            List<RuleEvaluation> evals = byRule.getOrDefault(ruleKey(id), List.of());
            RuleEvaluation exact = null;
            RuleEvaluation undecided = null;
            RuleEvaluation otherViolation = null;
            boolean decidedNotViolating = false;
            for (RuleEvaluation ev : evals) {
                String o = ev.outcome();
                if (ZoneMatrixEvaluator.OUTCOME_VIOLATION.equals(o)) {
                    if (fullKey(ev.identity()).equals(fullKey(id))) {
                        if (exact == null) exact = ev;
                    } else if (otherViolation == null) {
                        otherViolation = ev;
                    }
                } else if (ZoneMatrixEvaluator.OUTCOME_COMPLIANT.equals(o)
                        || ZoneMatrixEvaluator.OUTCOME_NOT_APPLICABLE.equals(o)) {
                    decidedNotViolating = true;
                } else if (undecided == null) {
                    undecided = ev;
                }
            }

            if (exact != null) {
                out.add(reproduced(prior, exact, runVersionId, now, history));
            } else if (undecided != null) {
                out.add(becameUndecided(prior, undecided, history));
            } else if (!complete) {
                out.add(carriedOver(prior, history));
            } else if (otherViolation != null || decidedNotViolating) {
                ResolutionReason reason = prior.versionId().equals(runVersionId)
                        ? ResolutionReason.RULE_CHANGED
                        : ResolutionReason.MATRIX_VERSION_CHANGED;
                out.add(resolved(prior, reason, otherViolation == null ? null : otherViolation.identity(),
                        runVersionId, now, history));
            } else {
                out.add(resolved(prior, ResolutionReason.RULE_REMOVED_OR_DISABLED, null, runVersionId, now, history));
            }
        }

        Set<FullKey> created = new LinkedHashSet<>();
        for (RuleEvaluation ev : run.rules()) {
            if (!ZoneMatrixEvaluator.OUTCOME_VIOLATION.equals(ev.outcome())) continue;
            FullKey key = fullKey(ev.identity());
            if (openKeys.contains(key) || !created.add(key)) continue;
            out.add(created(ev, runVersionId, now, history));
        }
        return new Result(policyId, runVersionId, complete, out);
    }

    /**
     * Findings resolved because the cited matrix version changed, grouped by the version of the resolving run
     * ({@code ZM-36} second half). Group order follows first appearance in {@code findings}.
     */
    public static Map<String, List<Finding>> resolvedByVersionChange(Collection<Finding> findings) {
        Map<String, List<Finding>> out = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (f.transition() == Transition.RESOLVED && f.resolutionReason() == ResolutionReason.MATRIX_VERSION_CHANGED) {
                out.computeIfAbsent(f.resolvedInVersionId(), k -> new ArrayList<>()).add(f);
            }
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(out);
    }

    // ---- transitions ---------------------------------------------------------------------------------------------

    private static Finding reproduced(
            KnownFinding prior, RuleEvaluation ev, String runVersionId, Instant now, Map<PairKey, Instant> history) {
        FindingIdentity id = ev.identity();
        Map<String, Instant> pairs = pairDates(id, prior.firstObservedAt(), now, history, prior);
        return new Finding(id, Transition.REPRODUCED, ev.outcome(), null, ev.flags(), prior.firstObservedAt(),
                earliest(pairs, prior.firstObservedAt()), pairs, now, runVersionId, null, null, null, null, null);
    }

    private static Finding becameUndecided(KnownFinding prior, RuleEvaluation ev, Map<PairKey, Instant> history) {
        Set<String> flags = new LinkedHashSet<>(prior.flags());
        flags.add(FLAG_BECAME_UNDECIDED);
        String outcome = ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE.equals(ev.outcome())
                ? ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE
                : ZoneMatrixEvaluator.OUTCOME_UNKNOWN;
        Map<String, Instant> pairs = pairDates(prior.identity(), prior.firstObservedAt(), null, history, prior);
        return new Finding(prior.identity(), Transition.BECAME_UNDECIDED, outcome, ev.reason(), flags,
                prior.firstObservedAt(), earliest(pairs, prior.firstObservedAt()), pairs, prior.lastObservedAt(),
                prior.versionId(), null, null, null, null, null);
    }

    private static Finding carriedOver(KnownFinding prior, Map<PairKey, Instant> history) {
        Map<String, Instant> pairs = pairDates(prior.identity(), prior.firstObservedAt(), null, history, prior);
        return new Finding(prior.identity(), Transition.STILL_OPEN_NOT_COLLECTED, prior.outcome(), null, prior.flags(),
                prior.firstObservedAt(), earliest(pairs, prior.firstObservedAt()), pairs, prior.lastObservedAt(),
                prior.versionId(), null, null, null, null, null);
    }

    private static Finding resolved(
            KnownFinding prior,
            ResolutionReason reason,
            FindingIdentity supersededBy,
            String runVersionId,
            Instant now,
            Map<PairKey, Instant> history) {
        Map<String, Instant> pairs = pairDates(prior.identity(), prior.firstObservedAt(), null, history, prior);
        return new Finding(prior.identity(), Transition.RESOLVED, prior.outcome(), null, prior.flags(),
                prior.firstObservedAt(), earliest(pairs, prior.firstObservedAt()), pairs, prior.lastObservedAt(),
                prior.versionId(), reason, now, prior.versionId(), runVersionId, supersededBy);
    }

    private static Finding created(RuleEvaluation ev, String runVersionId, Instant now, Map<PairKey, Instant> history) {
        FindingIdentity id = ev.identity();
        Map<String, Instant> pairs = pairDates(id, now, now, history, null);
        return new Finding(id, Transition.NEW, ev.outcome(), null, ev.flags(), now, earliest(pairs, now), pairs, now,
                runVersionId, null, null, null, null, null);
    }

    // ---- any-version history -------------------------------------------------------------------------------------

    private record PairKey(String ruleUid, String pair) {}

    /** Earliest observation per (rule uid, pair) over every known finding of the policy source, any status. */
    private static Map<PairKey, Instant> pairHistory(List<KnownFinding> known) {
        Map<PairKey, Instant> history = new HashMap<>();
        for (KnownFinding k : known) {
            for (String pair : k.identity().violatedPairs()) {
                Instant date = min(k.pairFirstObserved().getOrDefault(pair, k.firstObservedAnyVersion()),
                        k.firstObservedAt());
                history.merge(new PairKey(k.identity().ruleUid(), pair), date, ZoneMatrixFindingLifecycle::min);
            }
        }
        return history;
    }

    /**
     * Per-pair any-version dates of one finding: the history date; a pair without history is dated {@code unseenAt}
     * (the run time of a new or reproduced finding), or {@code ownFirst} when {@code unseenAt} is null. A stored
     * {@code prior} keeps its own stored pair date as an upper bound.
     */
    private static Map<String, Instant> pairDates(
            FindingIdentity id, Instant ownFirst, Instant unseenAt, Map<PairKey, Instant> history, KnownFinding prior) {
        Map<String, Instant> pairs = new LinkedHashMap<>();
        for (String pair : id.violatedPairs()) {
            Instant seen = history.get(new PairKey(id.ruleUid(), pair));
            Instant date = seen != null ? seen : (unseenAt != null ? unseenAt : ownFirst);
            if (prior != null) {
                date = min(date, prior.pairFirstObserved().getOrDefault(pair, prior.firstObservedAnyVersion()));
            }
            pairs.put(pair, date);
        }
        return pairs;
    }

    private static Instant earliest(Map<String, Instant> pairs, Instant fallback) {
        Instant best = null;
        for (Instant i : pairs.values()) best = best == null ? i : min(best, i);
        return best == null ? fallback : min(best, fallback);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    // ---- exact keys (identity law: Strings compared with equals, pairs as a set, no normalisation) -----------------

    private record RuleKey(String policyId, String ruleUid, List<String> parentPath) {}

    private record FullKey(String policyId, String ruleUid, List<String> parentPath, Set<String> pairs) {}

    private static RuleKey ruleKey(FindingIdentity id) {
        return new RuleKey(id.policyId(), id.ruleUid(), id.inlineParentPath());
    }

    private static FullKey fullKey(FindingIdentity id) {
        return new FullKey(id.policyId(), id.ruleUid(), id.inlineParentPath(), Set.copyOf(id.violatedPairs()));
    }
}
