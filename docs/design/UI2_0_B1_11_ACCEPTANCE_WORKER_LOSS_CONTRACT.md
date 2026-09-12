# UI 2.0 — B1-11: acceptance scenario B — worker loss contract

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 B1 contracts.

This is the acceptance contract for `ui2_b1_11_acceptance_scenario_worker_
loss`, `UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B1-11: "acceptance scenario B:
worker killed mid-step → `OUTCOME_UNKNOWN`, no duplicate device contact."
It fixes one end-to-end run, its passing evidence, and what that run does
not prove. It writes no `ui2/` source and issues no Flyway SQL. It
restates nothing `UI2_0_B1_04_COLLECTION_ENGINE_CORE_CONTRACT.md` (`B1-4`)
already owns; every mechanism cited here — lease/fencing, the state
machine, `job_step_attempt`'s `mutation_boundary_crossed` column, `job_
reconciliation` — is `B1-4`'s (in turn `C2`'s), not re-derived.

**Standing collection gate restated, not reopened.** No vendor command, no
new collection method, and no capability content is introduced or implied
by this document. This scenario contacts no real device and no device
command of any kind — it exercises `B1-4`'s transport **port**
(`DeviceTransport`, `B1-4` §5) through a test double, never `ssh_exec`
against a live endpoint. Anything about real-device non-duplication is out
of scope here and named as a separate gate in §5.

## 1. Why an acceptance scenario, not another unit test

`B1-4` §8 already proves the individual mechanisms this scenario composes:
test 4 (`LeaseExpiryBranchesByMutationBoundaryTest`) proves the three-way
branch by mutation-boundary state in isolation; test 5
(`WorkerKilledMidStepLandsInOutcomeUnknownNoSecondContactTest`) proves a
single worker's kill-mid-step lands in `OUTCOME_UNKNOWN` with the double
invoked exactly once; test 6 proves a racing lease is claimed by exactly
one worker. None of these compose the full **operational** path an actual
build would exercise end-to-end: (1) an admission-created `REQUESTED` job,
(2) claimed by a real worker process (not a unit-test harness call),
(3) the worker actually crashing mid-`exec` (not merely a test asserting
the boundary column), (4) the reconciler's `lease_expires_at` sweep
independently discovering and correctly classifying the orphaned job, (5)
a second worker process starting up and never claiming that job because
its state has left `REQUESTED` forever, and (6) a human recording `job_
reconciliation`, the sole legal exit. A defect this scenario catches that
no `B1-4` unit test would: the unit tests each construct their fixture
state directly (seed a `job_step_attempt` row with `mutation_boundary_
crossed = true`, then assert the reconciler's classification) rather than
letting a live claim → lease-extend → crash → reconciler-sweep pipeline
produce that state itself. A build could pass every `B1-4` unit test while
its actual worker process never writes the pre-contact row before
invoking the transport (violating `C2` §5.1's ordering, `B1-4` test 7's own
target) under real process boundaries — e.g. a JVM shutdown hook, thread
interruption, or heartbeat-extension race that a hand-constructed fixture
never exercises. This scenario is the first specification requiring the
full pipeline to run as one operational unit before the row lands in
`OUTCOME_UNKNOWN`.

## 2. Scenario

**Actors.** One admission path producing a `REQUESTED` job of an
execution-eligible `CLASS_0_READ` capability whose only step is one
`ssh_exec` `exec` call; one worker process (`WorkerA`) executing against a
**test transport** — a `DeviceTransport` test implementation that records
every call it receives (§5) and can be made to hang indefinitely on
command, standing in for a container-hosted SSH endpoint or an in-process
double per `B1-4` §8 test 5's own carrier note; the reconciler (run either
as `WorkerA`'s own background sweep or a second lightweight process,
matching whatever `B1-4`'s implementation ships — `B1-4` does not fix which
process runs the sweep, only its predicate, §4 open item 2); a second
worker process (`WorkerB`) started after `WorkerA` is killed, to prove no
new contact occurs.

**Starting state.** One `jobs` row, `state = 'REQUESTED'`, `lease_epoch =
0`, no `job_step_attempt` rows, no `job_reconciliation` row.

**Ordered steps:**

1. `WorkerA` claims the job via `B1-4` §4's single atomic `UPDATE ...
   RETURNING` — `state` → `CLAIMED`, `lease_epoch` → 1, lease fields set.
2. `WorkerA` passes `C2` §6's precheck battery (out of this scenario's
   scope to construct in detail; the scenario supplies a fixture where
   every check trivially passes, since this scenario's question is about
   post-`EXECUTING` behaviour, not admission) — `state` → `EXECUTING`.
3. `WorkerA` inserts the `job_step_attempt` row for step 0 with
   `mutation_boundary_crossed = false`, commits, then in a **separate**
   transaction (matching `C2` §5.1's "flipped to true in the same
   transaction as send") flips `mutation_boundary_crossed = true` and
   commits — this commit **must** happen before the test transport's `exec`
   call returns, and the test transport is configured to block on that
   call until the scenario signals it (simulating a device that has
   received the command but not yet answered).
4. **`WorkerA` is killed** — the scenario's OS process is sent `SIGKILL`
   (or the JVM equivalent that bypasses shutdown hooks) while blocked
   inside step 3's `exec` call, after the boundary-`true` commit is
   confirmed on the database but before any response is ever recorded.
5. The reconciler's sweep runs against `lease_expires_at` once the lease
   naturally expires (no lease renewal occurs because `WorkerA` is dead) —
   ordered after step 4, waited for rather than forced, so the sweep
   exercises its actual polling/predicate path, not a direct method call.
6. `WorkerB` starts and polls the claim query repeatedly (matching `B1-4`
   test 10's "claimed across 1000 polls" style bound) — ordered
   concurrently with, and continuing past, step 5.
7. A human-equivalent step records `job_reconciliation` for the job with
   `reconciled_outcome = 'RECONCILED_FAILURE'` (chosen arbitrarily among
   the three legal values; the scenario's assertions do not depend on
   which) — ordered after step 5's classification is observed.

**The single question this run answers:** when a real worker process is
killed after committing the mutation-boundary-crossed fact but before any
device response is recorded, does the full operational pipeline — lease
expiry detection, reconciler classification, and every subsequent worker's
claim eligibility — converge on exactly one `OUTCOME_UNKNOWN` row with
exactly one recorded transport attempt, indefinitely, until a human closes
it?

## 3. Passing evidence — named, queryable assertions

- **PA-1 (the pre-kill boundary commit is durable).** After step 4,
  `SELECT mutation_boundary_crossed, sent_at FROM job_step_attempt WHERE
  job_id = :job_id AND step_index = 0` returns exactly one row with
  `mutation_boundary_crossed = true` — proving the fact survived the kill
  because it was committed, not held in the dead process's memory.
- **PA-2 (`OUTCOME_UNKNOWN` is a recorded state, not an absent row).**
  After step 5, `SELECT state FROM jobs WHERE job_id = :job_id` returns
  exactly `'OUTCOME_UNKNOWN'` — a member of `chk_jobs_state`'s closed
  vocabulary (`V4__collection_engine_core.sql`), written by the
  reconciler's own `UPDATE`, not inferred by the test from an absent
  heartbeat. This is what distinguishes "unknown" from "not yet attempted"
  (`REQUESTED`, zero `job_step_attempt` rows) and from "not yet claimed"
  (`REQUESTED`, `lease_epoch = 0`): all three are different, queryable
  `jobs.state`/`job_step_attempt` combinations, never conflated.
- **PA-3 (never reclaimed).** `WorkerB`'s claim query (step 6), polled at
  least 1000 times across the wait window, never returns this `job_id` —
  `SELECT count(*) FROM jobs WHERE job_id = :job_id AND lease_worker_id =
  :worker_b_id` is zero throughout and after the scenario.
- **PA-4 (exactly one transport attempt, ever).** The test transport
  double's call log (§5) shows exactly one `exec` invocation for this
  `job_id`'s step 0 across the entire scenario, before, during, and after
  the kill/sweep/reconciliation sequence — the load-bearing "no duplicate
  device contact" assertion, proven at the transport boundary this build
  actually has (§5).
- **PA-5 (reconciliation is the sole legal exit, and only after it).**
  Before step 7, `SELECT count(*) FROM job_reconciliation WHERE job_id =
  :job_id` is zero and `jobs.state` remains `'OUTCOME_UNKNOWN'`. After step
  7, `jobs.state = 'RECONCILED'` and `jobs.reconciliation_ref = :job_id`,
  and `WorkerB`'s claim query still never selects it (the claim predicate
  excludes both `OUTCOME_UNKNOWN` and `RECONCILED`, per `B1-4` §4's state
  table).
- **PA-6 (no second boundary flip).** `SELECT count(*) FROM job_step_
  attempt WHERE job_id = :job_id` is exactly 1 throughout — no retry
  attempt row was ever created for this step, distinguishing this scenario
  from `B1-4` test 8's bounded-retry path (which applies only to
  class-0 pre-response failures with a definite outcome, not an unresolved
  boundary-crossed case).

## 4. What this scenario does not prove

- It does not prove **real-device** non-duplication. No real device exists
  in this build (`B1-4` §1, baseline A-1); PA-4 proves non-duplication at
  the test-transport call-log boundary this codebase actually has. Whether
  a real SSH endpoint, having received the command once, would ever see a
  second connection attempt after a real-world worker crash is a **real-
  environment gate**, separate from this scenario and not satisfied by it
  — consistent with `AGENTS.md`'s "automated validation != real-environment
  validation," restated, not reopened, here.
- It does not prove the reconciler's sweep interval, its exact polling
  cadence, or that it scales to many simultaneously-orphaned jobs — one
  job, one crash, is the fixture; throughput is out of scope.
- It does not prove `C2` §6's precheck battery's own correctness — this
  scenario's precheck fixture trivially passes every check so the scenario
  can reach `EXECUTING` deterministically; precheck semantics remain
  `B1-3`/`C2`'s own scope.
- It does not exercise the parser framework, evidence writer, or any
  captured-fact semantics — the step never receives a response to parse
  (the kill happens before any response), so `matched`/`expectationUnmet`/
  `ambiguous` classification is untouched by this scenario (`B1-4` §6, §8
  test 9's own scope).
- It does not authorize, imply, or specify any vendor command, capability
  content, or collection method — the standing Product Owner gate on
  collection is unaffected by this document (§0 restated).

## 5. The transport boundary and the real-device gate

Per `B1-4` §5, `DeviceTransport` is a port with exactly one shipped
implementation (`ssh_exec`) and this movement contacts no real device
anywhere (`B1-4` §1, `B1-4` §8 test 5's own carrier note: "a test double
commits the boundary"). This scenario specifies its assertions against a
**test transport double** — an in-process or container-hosted stand-in
implementing `DeviceTransport` that (a) can be told to block a call
indefinitely until signalled, simulating a device that received a command
and has not yet answered, and (b) records every call it receives, keyed by
`job_id`/`step_index`/`attempt_number`, for PA-4's assertion. This is the
same class of double `B1-4` §8 test 5 already uses; this scenario composes
it into the fuller pipeline of §2 rather than introducing a new kind of
double. **Real-device non-duplication is out of scope for this scenario
and for this entire build phase** — it is the first point in the workflow
(§5 row 6, `CAP-VALIDATED`, per `B1-4` §8's own "need a real device: none"
line) that would exercise a live endpoint at all, and even there under the
standing collection gate, never a new command or method introduced by this
document.

## 6. Killed process versus expired lease — the CI-runnability decision

**Decision: an expired lease (no renewal, natural timeout) is treated as
equivalent to a killed OS process, for every assertion in §3, and this
scenario's default execution path uses lease expiry, not an actual
`SIGKILL`, to be runnable in CI.**

**Reasoning.** `B1-4` §4's state machine and §8's crash matrix are
specified entirely in terms of database predicates: "expiry, some attempt
`YES` unconfirmed" is the trigger the reconciler acts on (`C2` §4.4-3, §8
pts 3–4), and nothing in `C2`/`B1-4` distinguishes *why* a lease stopped
being renewed — a killed process, a network partition between worker and
database, a paused container, and a hung thread that never reaches its
heartbeat call are all, from the database's point of view, the identical
fact: no lease-extending `UPDATE ... WHERE lease_epoch = :claimed_epoch`
arrived before `lease_expires_at`. `B1-4` §4's own table entry reads
"expiry/crash, some attempt `YES` unconfirmed" — treating expiry and crash
as one row, not two. The reconciler's sweep is specified purely against
`jobs.lease_expires_at` and `job_step_attempt.mutation_boundary_crossed`
(`B1-4` §4's comment: "purely from this table plus jobs.lease_expires_at —
no additional column is needed"); it has no mechanism to observe, and no
contract requires it to observe, whether the non-renewing worker is dead,
partitioned, or merely slow. Therefore an actual `SIGKILL` against a real
OS process and an artificially-forced lease expiry (advance the clock, or
simply do not renew and wait out the real interval) produce byte-identical
database state for every assertion in §3 — PA-1 through PA-6 are all
expressed as queries against `jobs`/`job_step_attempt`/`job_reconciliation`
columns, none of which can distinguish the two causes.

This decides CI-runnability directly: a scenario requiring an actual killed
OS process needs a process-management harness (spawn a real worker JVM,
locate its PID, send a real signal, confirm it is gone) that is
substantially heavier than the rest of this build's test carrier (`B1-1a`
§5's real-PostgreSQL-only requirement) and adds a second kind of external
dependency (process supervision) the carrier rule does not otherwise
require. Since the two are provably equivalent at every layer this
contract's assertions touch, this scenario is specified to run with a
forced/expired lease as its **primary, always-run** path. An actual
killed-process variant **may** additionally be authored as a heavier,
separately-gated test (not required by this contract, not blocking PA-1
through PA-6) if a future movement wants extra confidence that a real JVM
process kill produces the same lease-non-renewal behaviour as an
artificially-expired one — but nothing in `B1-4` or this contract makes
that additional variant load-bearing, since the reconciler's contract
surface cannot observe the difference.

## 7. Carrier rule

Per `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5: this scenario needs a
real PostgreSQL 16 server, named by `UI2_TEST_JDBC_URL` or provisioned via
Testcontainers; if neither carrier exists the test **fails**, never skips.
No container-hosted SSH endpoint is required (§5's test transport double is
in-process, matching `B1-4` §8's own note that test 5 may use "an in-
process test double," not only a container-hosted endpoint) — this
scenario is therefore runnable wherever the PostgreSQL-only carrier is
available, with no additional container-runtime dependency beyond what
`B1-4`'s own integration tests already require for the lease/fencing
tests.

## 8. Test specification

1. **`WorkerLossFullPipelineOutcomeUnknownScenarioTest`** [needs PostgreSQL
   16 carrier, per §7] — executes §2's ordered step sequence with the
   lease-expiry-as-crash-equivalent path of §6; fails on any of PA-1
   through PA-6.

No other new test is authored by this contract; the scenario composes
`B1-4`'s own claim/lease/reconciler machinery through one live pipeline run
rather than the isolated fixture constructions `B1-4` §8 already covers.

## 9. Acceptance criteria

- **AC-1.** PA-1 through PA-6 all hold in one continuous run of §2's
  sequence, executed via lease expiry per §6.
- **AC-2.** The scenario runs against the shared carrier fixture per §7,
  requires no container-hosted SSH endpoint, and issues no real or
  simulated new device command beyond the single recorded `exec` call.
- **AC-3.** No assertion in this document introduces a vendor command,
  collection method, or capability content; the standing Product Owner
  collection gate is cited, not reopened.
- **AC-4.** This document states plainly (§4, §5) that real-device non-
  duplication is a separate, later, real-environment gate, and this
  scenario's PA-4 is scoped to the test-transport call log only.

## 10. Worker route

**Sonnet 5, normal.** This composes already-frozen `B1-4`/`C2` mechanics
into one pipeline-level test; no new architecture, schema, transport, or
security-boundary decision is introduced. The killed-process-versus-
expired-lease question (§6) is a documentation/scoping decision, not a new
design, resolved directly from `B1-4`'s existing text.

## 11. Open items for the Product Owner

1. **Which process runs the reconciler sweep** (a `WorkerA`-local
   background thread, a dedicated reconciler process, or a scheduled job)
   is `B1-4` §11 item 3's own open item, restated: this scenario's step 5
   is written generically ("the reconciler's sweep runs") and does not
   presume an answer; whichever shape `B1-4`'s implementation ships, this
   scenario's assertions (all against `jobs`/`job_step_attempt` state, not
   against which process performed the write) are unaffected.
2. **Whether an additional, heavier killed-OS-process variant should ever
   be authored** (§6's closing paragraph) is left to the Product Owner;
   this contract does not require it and states why the lease-expiry path
   is sufficient evidence for every assertion this document makes.

No contradiction between this contract, `B1-4`, or `C2` was found; §6's
equivalence claim is derived directly from `B1-4` §4's own "expiry/crash"
table row and its own explanatory note that the reconciler's classification
depends only on `job_step_attempt` and `jobs.lease_expires_at` — no source
document treats a killed process as evidentially distinguishable from an
expired lease at the layer this scenario asserts against.
