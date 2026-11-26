# Policy Insights budget and cost: the G1 engines on the ten largest stored Check Point policies (counts only)

Backlog `policy_insights_p2`. PO decision Q8 (`docs/design/PO_DECISION_RECORD_2026_11_24_POLICY_INSIGHTS.md`): the G1 engines
use interval-box arithmetic with a budget, budget exhaustion is `NOT_PROVEN`, and the decision is to be revisited after P2
measures how often the budget is hit. The P2 gate (`docs/design/POLICY_INSIGHTS_DESIGN_2026_11_24.md` section 9) asks for
differential tests and a scale test at 10,000 rules; the synthetic scale tests exist, the stored-policy answer does not. This
harness takes it inside the pod: how often does each engine end a rule evaluation `NOT_PROVEN` on the work budget, and what does
one run cost in thread CPU time and heap, on the ten largest stored Check Point policies. It follows the pattern of
`scripts/measurement/policy_collection_gaps/` and `scripts/measurement/zone_matrix_scale/` (PO answer 2026-11-23: counts-only
in-pod measurements, no device command; `docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md`).

**This lane wrote and self-tested the harness; it has NOT been run against any environment, and no counts exist.** The in-pod
run belongs to the PO session, needs the PO session's go, and runs outside ship/e2e windows because `/app/tmp` is lost on a
rollout. The result becomes a MEASUREMENT RECORD written by the PO session; no counts are recorded in the repository by this
lane.

## What it reads and runs

Inside `ui2-service`, one read-only database connection per pass (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`,
`SET statement_timeout = '120s'`), the pod's own env files, no device contact, no write, no new command:

- Pass 1: every `policy_snapshot` row, one at a time in policy-id order (the id is held in memory only to re-read the row,
  never printed). A snapshot with collection failures (a checkpoint) is not analysed; it only counts. A snapshot of any vendor
  but Check Point (the G1 engines evaluate Check Point only) only counts. The analysable ones are ranked by **enabled** rules of
  security sections (NAT and default sections are outside security evaluation); ties in policy-id order. The ten largest are
  labelled `p01`..`p10`. The cheap counters (snapshots, enabled and disabled security rules) are also summed over all analysable
  snapshots on `package fleet` lines.
- Pass 2: each of the ten is re-read by its key, run through the engines once each, and dropped:
  1. `PolicyHygiene.analyze(snapshot, 90, EPOCH, TOTAL_WORK)`, the shadow engine the others read (what `PolicyProposal` itself
     runs; shown as `cost pNN.hygiene`, not as an engine);
  2. `PolicyUnionCover.analyze` (I3), 3. `PolicyPartialShadow.analyze` (I4), 4. `PolicyMerge.analyze` (I5),
  5. `PolicyReorder.analyze` (I7), 6. `PolicyProposal.allOfferedMoves` (the reorder-only proposal),

  every one with the **production budgets** (`TOTAL_WORK` 200,000,000 per snapshot, `RULE_WORK` 1,000,000 per rule). The engine
  classes are package-private, so they are reached by reflection (the harness is compiled against the pod's own service jar);
  the production code is not changed. The limits are an argument of the measuring method; only the self-test passes a tiny one,
  never an environment variable or a production switch.

Not applied: the Check Point domain-inventory merge of `PolicyQueryService.breadthObjects`; it feeds only the permissiveness
verdict, never the shadow or box engines.

## What it prints

`measure.out` in the staged directory: tab-separated, four fields per line `kind id key value`, every field `[a-z0-9_.]+`.
Ordinals (`p01`..`p10`), engine slugs (`union_cover`, `partial_shadow`, `merge`, `reorder`, `proposal`, `hygiene`), fixed counter
and reason slugs, integers, booleans. `id` is `<ordinal>.<engine>` or `fleet.<engine>`. No policy id, hash, rule uid, name,
address or exception text can reach it; a failure prints only `fatal<TAB><lowercase exception class>` (a pod older than the
engines ends in `fatal exceptionininitializererror`). `show_safe.sh` drops (and counts as `filtered_lines`) any line with an
unsafe field. The run ends with a `done` line.

`fleet` in the `engine`, `reason`, `outcome`, `cost` and `budget` lines is the sum over the **ranked ten that were measured**
(the engines run only on them); heap is the maximum, not the sum. `package fleet` lines are over all analysable snapshots.

| line | meaning |
|---|---|
| `scope fleet <counter> n` | `snapshots_total`, `snapshots_parse_failed`, `snapshots_with_failures_skipped`, `snapshots_vendor_not_evaluated`, `snapshots_analysable`, `snapshots_ranked` (at most 10), `ranked_reread_failed`, `analysis_failed`, `cpu_time_unsupported` (1 when the JVM gives no thread CPU time; every `cpu_ms` is then 0) |
| `package fleet snapshots\|rules_security\|rules_security_disabled n` | over all analysable snapshots: count, enabled and disabled security rules |
| `package fleet rules_security_ranked n`, `snapshots_measured n` | over the ranked ten that were measured |
| `package pNN rules_security n` | enabled security rules of the ranked snapshot, in rank order |
| `engine <id> evaluated\|decided\|not_proven n` | per engine, below |
| `reason <id> <slug> n` | rules not evaluated, by the engine's closed reason (`unsupported_rulebase`, `snapshot_collection_incomplete`, `earlier_sections_not_analysed`, `rule_not_resolved`, `opaque_selector`, `other`), zero included; the four engines only |
| `outcome <id> <slug> n` | what the engine decided: `covered`; `partial`, `different_action`; `candidates`, `already_shadowed`, `differs_in_several_cells`, `different_effect`, `different_install_on`, `anchor_blocked`; `safe`, `safe_inspection_changes`, `unsafe_different_effect`, `anchor_blocked`, `crosses_section`; for the proposal `accepted`, `steps`, `candidates_total`, `budget_stopped` (1 when the run stopped on a NOT_PROVEN step) |
| `refused <pNN.proposal> <word> n` | the proposal's refusals by closed word (`unsafe_different_effect`, `anchor_blocked`, `crosses_section`, `not_proven`, `no_longer_a_candidate`, `other`), zero included |
| `cost <id> cpu_ms n` | thread CPU time of the single call (`ThreadMXBean`), whole ms rounded up; not wall clock |
| `cost <id> heap_additional_mb n` | highest used heap a 20 ms sampler saw during the call minus the used heap before it (after a best-effort GC), whole MB rounded up; an estimate, not a profiler. Also `cost pNN.hygiene` |
| `budget <id> hit_rate_ppm n` | the Q8 figure, below |
| `budget <id> any_hit <bool>` | `true` when at least one rule evaluation of that engine ended `NOT_PROVEN` on the budget (for the proposal also when it stopped) |

### The counters

`evaluated` is **rule evaluations attempted** by that engine, `not_proven` those that ended `NOT_PROVEN` on the budget,
`decided` the rest (`evaluated` = `decided` + `not_proven`). The unit differs per engine: a subject rule for `union_cover` and
`partial_shadow` (an enabled rule the shadow engine did not find REDUNDANT or CONFLICT, and for the partial pass not COVERED),
an adjacent enabled pair for `merge`, an I4 rule's promote for `reorder`, a step for `proposal`.

- `union_cover` and `partial_shadow`: the engines count a subject whose own work window ran out **before it was read** as
  `NOT_PROVEN` but not as evaluated. The harness adds those to `evaluated` (subjects counted from the shadow result, minus
  `evaluated`, minus the not-evaluable ones) so that a budget hit never leaves the denominator.
- `merge` and `reorder`: `evaluated` already includes the `NOT_PROVEN` ones (the engines' own count identity).
- `proposal`: `evaluated` = `steps`, `not_proven` = `refused.not_proven`. The proposal stops at its first `NOT_PROVEN` step and
  marks every later step `NOT_PROVEN` too, so its rate overstates the number of independent hits; read `budget_stopped` first.

`hit_rate_ppm` = `not_proven` x 1,000,000 / `evaluated`, integer (floor), 0 when nothing was evaluated; no floating point.
`any_hit` is exact whatever the rate rounds to. `reorder` and `proposal` run only on the I4 candidates the partial pass found
under the same limits, so a hit upstream can hide downstream evaluations; the self-test shows it (a tiny limit gives `reorder`
`any_hit false` because no candidate survived to it).

## How to read the result

| Outcome in `pibudget-<tag>.tsv` | Meaning | Follow-up it opens |
|---|---|---|
| `done`, `filtered_lines` 0, `scope fleet analysis_failed` 0, `snapshots_ranked` 10, and every `budget fleet.<engine> any_hit` is `false` | On the ten largest stored policies no engine hit its production budget; Q8's `NOT_PROVEN` branch is not exercised by real data | PO session writes the MEASUREMENT RECORD and may close the Q8 revisit as "budget never hit at these sizes"; the cost lines give the real CPU and heap for the P2 gate |
| some `any_hit` `true` with a small `hit_rate_ppm` (the PO session names the threshold) | The budget bites on few rules | Record per engine and per ordinal which policy (`pNN`, by size) hits; decide whether to raise `RULE_WORK`/`TOTAL_WORK` or keep `NOT_PROVEN`; no engine change from this lane |
| `any_hit` `true` with a large `hit_rate_ppm`, or `proposal budget_stopped` 1 on a ranked policy | The interval-box arithmetic does not finish on a real policy of that size; the proposal stops on it | Open a G1 engine or budget lane (revisit Q8); `NOT_PROVEN` stays the safe answer meanwhile |
| `cost` `cpu_ms` of one engine near the pod's request timeout, or `heap_additional_mb` large | The engine is too costly to run in a request for that policy | Plan an asynchronous run or a pool of its own before P2 is shipped to users; repeat the run first if a figure is near a limit |
| `p01 rules_security` well below 10,000 | The 10,000-rule scale test of the P2 gate is synthetic only on this fleet | Keep the synthetic scale test as the gate for that size; the stored figures say nothing about it |
| `snapshots_ranked` below 10, `ranked_reread_failed` or `analysis_failed` above 0, `cpu_time_unsupported` 1, `fatal <class>`, `filtered_lines` above 0, or no `done` | The run is incomplete (a policy was not measured, CPU times are 0, the pod predates the engines, or an unsafe field was dropped) | Nothing is evidenced for the missing part; do not record a budget claim; the matching `scope` counter says why |

What the figures do not say: one single-threaded call per engine per policy, **warm nothing** (class loading and JIT warm-up of
the first call are inside `p01`); the harness JVM (`-Xmx2g`) shares the pod and the node with whatever else runs, which affects
wall-clock but not thread CPU time much, and heap is the harness JVM's own, not the serving JVM's. Counts only: no finding,
rule, move or package is derived from or kept of the stored policies, and the proposal's snapshot is dropped.

## Never record

Policy ids or hashes, device ids, rule uids, rule or object names, addresses, section or layer names, usernames, exception
messages, bank identifiers, any mapping from a `pNN` ordinal to a policy, and any raw row. Only the counted tokens above, taken
from the `show_safe.sh`-filtered file, are recorded.

## Self-test (no pod, no database, no device)

Build the module classes once, then run the self-test:

    cd ui2 && ./gradlew --no-daemon :service:classes :persistence:classes :platform-core:classes :worker:classes
    bash scripts/measurement/policy_insights_budget/selftest.sh

`selftest.sh` prints `show_safe<TAB>ok` when `show_safe.sh` drops and counts an unsafe line (a name) and keeps a safe one; compiles
the class against the local module outputs and the Gradle-cached Jackson 2 and PostgreSQL jars and runs
`nexusmeasure.PolicyInsightsBudgetMeasure --selftest`, which must print `selftest<TAB>ok`; then runs `--selftest-report` and checks
its stdout (`report<TAB>ok`). The same measurement code (`rank`, `topTen`, `measureRanked`, `measure`, `emitReport`) runs over
inline synthetic Check Point snapshots (`FW-TANGO-04`-style names, `192.0.2.x` and `198.51.100.x` addresses, planted in rule,
object, comment and target names):

- exact counters at the production limits for a cover snapshot (the lower and upper half before the whole /24: I3 covered, two
  merge candidates) and an overlap snapshot (the whole /24 after its lower half: one partial, one safe move, the proposal accepts
  it, a NAT-section rule not counted);
- a tiny work limit (passed as an argument, 0) on the cover snapshot: union, partial and merge end entirely `NOT_PROVEN`
  (including the rules stopped before they were read), `any_hit true`, hit rate 1,000,000 ppm, while the same snapshot at the
  production limits has no hit;
- a sweep of every limit 0..400 over the overlap snapshot: for every engine `decided` + `not_proven` = `evaluated`, the rate
  stays within 0..1,000,000, and some limit stops the proposal on the budget with `not_proven` at least 1;
- ranking: fourteen snapshots (a checkpoint and a Palo Alto policy, both larger than the rest, only count), the ten largest in
  order with a tie in key order, fleet rule counts (one disabled rule), a ranked snapshot that cannot be re-read only counts;
- the whole report: every line matches `^[a-z0-9_.]+(\t[a-z0-9_.]+)*$` with four fields, no planted name or address reaches the
  output, the closed reason and refusal vocabularies are printed whole, the unsafe-field guard drops a line carrying a name, and
  the shell script greps the report on stdout for the planted text.

## Run (PO session only)

    MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/policy_insights_budget/measure_run.sh <tag>

`measure_run.sh` reads the host from the operator's own config file, refuses to start while a deploy e2e runs, copies the pod's
own service jar (size-checked), compiles only the harness against it, stages `pibudget-<tag>`, runs `run_in_pod.sh` (`java -Xmx2g`
through `PropertiesLauncher`, 30 minute timeout), waits for `done`/`fatal` (up to 30 minutes), prints the `show_safe.sh`-filtered
output into `pibudget-<tag>.tsv`, and removes the stage directory. It needs the PO session's go; no agent lane runs it. The
proposal re-runs the shadow, union, partial and reorder passes once per accepted step, so on a large policy it is the longest
call; a run that does not finish in 30 minutes ends without `done`.
