# Zone Matrix scale: the ten largest stored policies against a 500-zone version (counts only)

Backlog `zone_matrix_delivery`. `docs/design/ZONE_MATRIX_CONTRACT.md` (FROZEN rev 2) section 9 "Performance budget" says:
measured before release on the 10 largest stored policies (counts only, in the pod; the largest has about 16,500 rules),
the largest policy against a 500-zone version at the block cap takes at most 30 seconds and 512 MB additional heap, and all
10 together stay within 3 minutes (acceptance ZM-12; the heap part of ZM-39; section 5.1 names the hard time limit and the
measured heap ceiling). The synthetic version of that measurement is `ZoneMatrixScaleMeasurementTest`
(`docs/design/ZONE_MATRIX_STEP3_SCALE_MEASUREMENT.md`); the stored-policy measurement is what
`docs/history/backlog/zone_matrix_delivery.md` still lists as open for ZM-12. This harness takes it inside the pod.

**This lane wrote and self-tested the harness; it has not been run against any environment.** The in-pod run belongs to the
PO session, needs the PO session's go, and runs outside ship/e2e windows because `/app/tmp` is lost on a rollout. The result
becomes a MEASUREMENT RECORD written by the PO session; no counts are recorded in the repository by this lane.

## What it reads and builds

Inside `ui2-service`, one read-only database connection per pass (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`,
`SET statement_timeout = '120s'`), the pod's own env files, no device contact, no new command, no write:

- Pass 1: every `policy_snapshot` row, one at a time in policy-id order (the id is held in memory only to re-read the row,
  never printed). A snapshot with collection failures (a checkpoint) is not analysed; it only counts. A snapshot of a
  vendor the evaluator does not evaluate (anything but Check Point and Palo Alto) only counts. The analysable ones are
  ranked by enabled plus disabled rules of security sections (NAT and default sections are outside security evaluation);
  ties in policy-id order. The ten largest are labelled `p01`..`p10`.
- The synthetic section 9 version, built in memory exactly like
  `ZoneMatrixScaleMeasurementTest.buildFiveHundredZoneTwoHundredThousandBlockMatrix`: 500 synthetic zones, 200,000 disjoint
  `/30` blocks (400 per zone, inside `100.64.0.0/10`), two service sets, 1,200 explicit cells (ALLOW_LISTED, ALLOW_ANY,
  DENY_ALL), default `DENY_ALL`; compiled with the public `ZoneMatrixEvaluator.compile`. **A synthetic version never touches
  stored matrix data: no matrix version is read from or written to the database, no zone name or block of the product
  appears anywhere.**
- Pass 2: each of the ten is re-read by its key, evaluated once with the public `ZoneMatrixEvaluator.evaluate`, and dropped.
  The engine is reached through its public API only; no reflection and no production change were needed.

The stored `policy_snapshot` row is one whole JSON value, so no chunk reader is needed (the sibling harness's gzip chunk
reader serves the Check Point inventory, which this harness does not read). The production path also merges Check Point
domain objects into the snapshot before evaluation (`ZoneMatrixPolicyBridge.withDomainObjects`); this harness does not, so
a snapshot that depends on domain objects can show more NOT_EVALUABLE ("unresolved object") than the screen does.

## What it prints

`measure.out` in the staged directory: tab-separated, four fields per line `kind id key value`, every field `[a-z0-9_.]+`.
Ordinals (`p01`..`p10`), vendor tokens (`cp`, `pan`), fixed counter and reason slugs, integers and booleans. No policy id,
hash, rule uid, name, address, zone name or exception text can reach it; a failure prints only
`fatal<TAB><lowercase exception class>` (an older pod without the evaluator ends in `fatal noclassdeffounderror`).
`show_safe.sh` drops (and counts as `filtered_lines`) any line with an unsafe field. The run ends with a `done` line.

| line | meaning |
|---|---|
| `scope fleet <counter> n` | `snapshots_total`, `snapshots_parse_failed`, `snapshots_with_failures_skipped` (checkpoints), `snapshots_vendor_not_evaluated`, `snapshots_analysable`, `snapshots_ranked` (at most 10), `ranked_reread_failed`, `evaluation_failed` |
| `version synthetic <key> n` | `zones`, `blocks`, `cells` (500, 200000, 1200), `compile_ms`, `retained_heap_mb` (heap the compiled index retains after a best-effort GC) |
| `package pNN vendor <cp\|pan>`, `package pNN security_rules n`, `package pNN rules_total n` | the ten largest, in rank order; `rules_total` is the snapshot's own rule count (NAT included) |
| `eval pNN duration_ms n` | wall-clock of one `evaluate` call |
| `eval pNN hygiene_ms n` | the evaluator's own hygiene part of it |
| `eval pNN heap_before_mb n`, `heap_peak_mb n`, `heap_additional_mb n` | used heap before the call (after a best-effort `System.gc()`), the highest used heap a 20 ms sampler saw during it, and peak minus before; whole MB, rounded up |
| `eval pNN coverage_complete <bool>`, `hygiene_budget_reached <bool>` | the evaluator's coverage verdict and whether its hygiene analysis budget was hit |
| `outcome pNN <outcome> n` | `violation`, `compliant`, `not_evaluable`, `unknown`, `not_applicable` rule counts |
| `reason pNN not_evaluable.<slug> n`, `reason pNN unknown.<slug> n` | the evaluator's closed reasons (`NOT_EVALUABLE_REASONS`, `UNKNOWN_REASONS`, lower-cased, non-alphanumerics to `_`), every one printed, zero included; `*.other` for anything outside the sets |
| `sum fleet evaluated_policies n`, `security_rules n`, `duration_ms n` | over the policies that were evaluated |
| `budget fleet largest_within_30s <v>` | `true` when `p01` took at most 30,000 ms; section 9 / ZM-12 |
| `budget fleet largest_within_512mb <v>` | `true` when `p01`'s additional heap is at most 512 MB; section 9 / ZM-12, heap part of ZM-39 |
| `budget fleet all_ten_within_180s <v>` | `true` when the ten durations sum to at most 180,000 ms |

`<v>` is `true`, `false`, or `not_measured`. `largest_*` is `not_measured` when `p01` could not be evaluated;
`all_ten_within_180s` is `not_measured` unless exactly ten policies were ranked, re-read and evaluated (fewer than ten
analysable snapshots, a re-read failure or an evaluation failure each make the run incomplete; the matching `scope` counter
says which).

## What the number means, and what it does not

- One single-threaded evaluation per policy, **warm nothing**: a cold-ish JVM (the class loading and JIT warm-up of the first
  call are inside `p01`'s time, which is the conservative side for the largest policy). Not a steady-state benchmark.
- It runs in a separate JVM (`-Xmx2g`) started inside the pod that is also serving users: the wall-clock is shared with
  whatever else the pod and the node are doing at that moment. Repeat the run if a figure is near a limit.
- Heap by sampling is an **estimate, not a profiler**. A 20 ms poll can miss a short peak (under-reports) and counts garbage
  not yet collected (over-reports). The figure is the harness JVM's own used heap, not the serving JVM's.
- The heap part of ZM-39 is only this additional-heap figure. **Not measured here:** the ZM-39 interactive-p95 part and the
  pool-separation part (evaluation must not starve interactive requests); those need a loaded pod and are not in scope.
- It does not prove the hard time limit or the measured heap ceiling of section 5.1 are enforced in the service; it shows how
  the evaluator behaves against the budget on stored data.
- Counts only. No decision, finding, zone name or rule is derived from or kept of the stored policies. The synthetic version
  does not model the product's real zones, so the verdict counts say how the evaluator reacts to the synthetic matrix, not
  how compliant a stored policy is.

## Self-test (no pod, no database, no device)

Build the module classes once, then run the self-test:

    cd ui2 && ./gradlew --no-daemon :service:classes :persistence:classes :platform-core:classes :worker:classes
    bash scripts/measurement/zone_matrix_scale/selftest.sh

`selftest.sh` first feeds `show_safe.sh` one unsafe and one safe line (prints `show_safe<TAB>ok` when the unsafe one is
dropped and counted), then compiles the class against the local module outputs and the Gradle-cached Jackson 2 and
PostgreSQL jars and runs `java -cp "<tmp>:<classpath>" nexusmeasure.ZoneMatrixScaleMeasure --selftest`, which must print
`selftest<TAB>ok`. The same measurement code (`rank`, `topTen`, `measureRanked`, `measure`, `emitReport`) runs over inline
synthetic snapshots (written to JSON and parsed back, like the database read) and a small synthetic version (8 zones):

- twelve analysable snapshots (Check Point and Palo Alto) of different sizes, plus a checkpoint and a vendor the evaluator
  does not evaluate, both larger than all the rest, so a ranking that did not skip them would put them first; the scope
  counters, the exact ten-largest order and the policy-id tie order are asserted;
- each of the ten measured: sizes, outcome counts (violations, compliant, not-applicable, the IPv6 reason slug), non-negative
  timing and heap;
- the whole report: every line matches `^[a-z0-9_.]+(\t[a-z0-9_.]+)*$`, no synthetic id, name or address text appears, and
  the boolean tokens come out `true` on the fixture, `false` just over a limit, `true` exactly at a limit, and
  `not_measured` for an incomplete run or no measured policy;
- the unsafe-field guard drops a line carrying a name; a ranked policy that cannot be re-read only counts.

## Run (PO session only)

    MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/zone_matrix_scale/measure_run.sh <tag>

`measure_run.sh` reads the host from the operator's own config file, refuses to start while a deploy e2e runs, copies the
pod's own service jar (size-checked), compiles only the harness against it, stages `zmscale-<tag>` into `/app/tmp`, runs
`run_in_pod.sh` (`java -Xmx2g` through `PropertiesLauncher`, 30 minute timeout), waits for `done`/`fatal` (up to 30 minutes),
prints the `show_safe.sh`-filtered output into `zmscale-<tag>.tsv`, and removes the stage directory. It needs the PO
session's go and a window with no ship or e2e (a rollout would lose `/app/tmp` and the run with it); no agent lane runs it.
