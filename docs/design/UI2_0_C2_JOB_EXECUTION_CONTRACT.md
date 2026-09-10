# UI 2.0 — B0/C2 job execution contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09** (platform contract freeze (C1–C6 + baseline directory), per `UI2_0_BASELINE_CONTRACT.md` §2 `FREEZE-SLICING`). Open items listed in this document's own open-items section are deferred to the movements they name; they do not reopen this freeze. Previous status: DRAFT — FOR PRODUCT OWNER FREEZE. Written under
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09), decisions `JOB-UNCERTAIN-OUTCOME` (D-2a), `UI-OPERATIONAL-RUN-NOW`
(D-2b) and `APPROVAL-MODEL` (D-2c). Nothing in this document is implementation
authority until its own status line changes to `FROZEN`.

---

## 1. Scope and authority chain

### 1.1 What this contract owns

How a Java worker **claims, executes, records and completes a job**: the job
record and its state machine; leasing and heartbeat; timeouts; worker loss;
duplicate detection; `OUTCOME_UNKNOWN` semantics; the separation of owner,
approver and execution identity for scheduled and Run Now jobs; schedule
optimistic concurrency; and the step log's required fields and write
ordering.

### 1.2 What this contract explicitly does not own

| Not owned here | Owner | This document's relationship to it |
|---|---|---|
| Table identity, audit-table schema, secrets storage, key custody | `C1` (platform & schema contract) | Names lifecycle columns explicitly (§2, §4, §5, §7) so `C1` can adopt them; never defines the audit table itself — every mutation this contract requires "is audited" points at `C1`'s audit table by reference, not by re-specification |
| What a "step" is: its kind vocabulary, its expectation-gate semantics, the capability registry, the gate-record resolution rule | `C4` (capability registry & command-gate resolution) | This contract assumes only that a step has a kind, a gate reference, a timeout and an expect/validation result (`UI2_0_BASELINE_CONTRACT.md` sequencing note); the closed step-kind vocabulary and its per-kind expectation rules are `design §6.3`, ported by `C4` |
| Session authenticity, RBAC evaluation (`E1`–`E6`), role tokens, directory binding | `C3` (identity, sessions, RBAC) | This contract begins at `E7` (§6) — the request already carries a resolved actor and a `D7` outcome by the time a job row exists |
| The backup-profile object model, step content, profile lifecycle/approval, artefact store, restore execution | `C7` (backup, artefact and restore engine) | This contract treats a profile version as an opaque, immutable reference (`profile_id`, `version`) resolved once at claim time; it never inspects step content |
| Device contact itself: transport, credentials, network access | Out of scope for every B0 contract | Nothing here authorizes execution of any capability on a device (`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-1) |

### 1.3 Authority chain (highest first, per `AGENTS.md` "Authority hierarchy")

1. `AGENTS.md` — durable constitution (evidence laws, UNKNOWN/fail-closed law, raw-evidence law).
2. `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — binding direction and Phase 0 decisions; this document implements `JOB-UNCERTAIN-OUTCOME`, `UI-OPERATIONAL-RUN-NOW`, `APPROVAL-MODEL` and acceptance sentence A-2.
3. This document, once its own status line reads `FROZEN`.
4. `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.2 (step executor, closed step kinds, evidence writer) and §3.4 (Line-1/Java device-contact coordination, R-08) — the plan this contract concretizes.
5. `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §5.4 (storage/audit pattern), §6.3–§6.7 (profile model, step kinds, lifecycle, taxonomy reconciliation, schedule-edit intent) — design under amendment; `APPROVAL-MODEL` supersedes its per-run four-eyes framing exactly where `UI2_0_BASELINE_CONTRACT.md` §5 item 3 says so.
6. `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` §6.3 (the job-execution-contract concern this document answers) and §6.7 (the identity concern §7 of this document resolves).
7. `utils/action_taxonomy.py` — the action classes, ported to Java verbatim (`RUNTIME-DIRECTION`); every retry rule and every console-submittability statement in this document keys on it.

### 1.4 Reference-only, not ported

- **`utils/operate/` (`states.py`, `record.py`, `coordinator.py`) — the `OP.2.0` execution state machine.** This is a **single-process, class-2 (operational-state-change) reference** for a system that does not yet have a multi-worker server. Its vocabulary (`CREATED`/`PREFLIGHTING`/`AWAITING_CONFIRMATION`/`EXECUTING`, a two-valued `mutation_boundary_crossed` field written before submission, a legal-transitions graph with no edges out of a terminal state, `OUTCOME_UNKNOWN` as a terminal state with no automatic retry) is read for what it got right — the mutation-boundary-before-contact discipline and the closed-transitions-graph pattern are reused as *patterns*, restated fully in §3–§5 below for a multi-worker, class-0/class-1 job queue. No class from `utils/operate` is imported, subclassed or ported; `OP.2` (class 2) itself stays unbuilt and out of scope.
- **`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — the RB.3b operational-write ledger.** Read for its fail-closed philosophy (§5 of that document: *unreadable* blocks the run, *absent* proceeds — never conflated) and for the ordering rule (ledger read and write happen inside the same admitted section as the device operation, §7 of that document). This contract's pre-execution checks (§6) reference the ledger as an existing Line-1/RB.x authority to be re-checked at claim time; it does not redefine the ledger's own schema or backend.

---

## 2. Job record

### 2.1 Fields (all mandatory unless marked optional)

| Field | Meaning | Fixed when |
|---|---|---|
| `job_id` | opaque identifier (identity law: never cast to integer, never parsed) | at creation |
| `idempotency_key` | server-generated on a `POST` that supplies no client key, or a client-supplied token de-duplicated at creation (§2.3) | at creation, unique |
| `job_type` / `capability_id` | reference into the `C4` capability registry | at creation |
| `action_class` | one of `utils.action_taxonomy`'s five ids, read from the capability/profile the job references — never client-supplied | at creation |
| `target_refs` | the opaque `device_id`/`entity_id` set the job runs against | at creation (request-time fixation, `UI-OPERATIONAL-RUN-NOW`) |
| `capability_ref` / `profile_ref` | `{capability_id}` for a read job, `{profile_id, version}` for an operational-write job — the **immutable** version is copied by value from the currently `APPROVED` row at creation; a later profile edit produces a new version and never mutates a job already created against the old one | at creation |
| `reason` | operator-supplied for Run Now (`D7` mandatory-reason pattern, ≥ 8 characters, redaction-filtered); scheduler-supplied fixed text for a scheduled run, naming the schedule | at creation |
| `owner` | who created the request or, for a scheduled run, who owns the schedule that created it (§7.2) — `actor_fingerprint`, never a raw identity | at creation |
| `approvers` | who approved the profile version and/or the schedule authorization the job relies on (§7.2) — zero or more `actor_fingerprint` values, sourced from the profile/schedule rows, never re-derived from the requester | at creation, from the referenced profile/schedule's own approval record |
| `execution_credential_ref` | which credential reference the worker actually used | resolved at claim time (§4), not at creation — a job created but never claimed has no execution identity yet |
| `origin` | `{kind: "run_now", session_id}` or `{kind: "schedule", schedule_id, schedule_version}` — the causer of the request (§7.4) | at creation |
| `state` | current state (§3) | mutated only per §3's legal-transitions graph |
| `precheck_results` | ordered list of `{check_id, outcome, detail, recorded_at}` (§6) | appended at claim time, before device contact |
| `lease_worker_id`, `lease_epoch`, `lease_expires_at`, `last_heartbeat_at` | claiming state (§4) | mutated only by the fencing-token-guarded update in §4 |
| `outcome`, `terminal_reason`, `finished_at` | terminal result (§3, §8) | set once, on the single legal transition into a terminal state |
| `reconciliation_ref` | link to a `job_reconciliation` row (§3.5) | only for a job that reached `OUTCOME_UNKNOWN` and was later reconciled |
| `audit_ref` | link to `C1`'s audit table entry recording this job's creation | at creation; every subsequent state transition writes its own `C1` audit row referencing `job_id`, not a second copy of the job fields |

### 2.2 Request-time fixation (`UI-OPERATIONAL-RUN-NOW`)

`target_refs`, `capability_ref`/`profile_ref` and `reason` are copied by
value into the job row at creation and never re-read from the mutable
`BackupAssignment`/`BackupSchedule`/profile rows afterward. This is what
"the UI creates an authorised job request; the worker executes" means
concretely: the worker's execution plan is fully determined by the job row
alone, never by a live join against tables that might have changed between
request and claim. Approval **state**, by contrast, is deliberately **not**
fixed at request time — it is re-checked fresh at claim time (§6, AC-7),
because approval can be revoked after a job is queued and before a worker
picks it up.

### 2.3 Idempotency key vs device-command idempotency

An API-level `idempotency_key` prevents the **request** from being
duplicated (a retried `POST`, a double-click, a browser resubmit creates at
most one job row for the same key within a bounded window). It says nothing
about whether the **device-side command** the job eventually sends is safe
to repeat — that is a property of the command itself, established by its
own network-device command-gate record, and this contract never treats
request-level idempotency as license to auto-retry an operational-write step
(§5.3, echoing brief §6.3's AWS idempotent-API citation: the two guarantees
are different and this contract does not conflate them).

---

## 3. State machine

### 3.1 States

| State | Terminal? | Meaning |
|---|---|---|
| `REQUESTED` | no | job row exists; admission-time checks (`E1`–`E6`, taxonomy admissibility) already passed at creation; no lease held |
| `CLAIMED` | no | a worker holds a live lease; pre-execution checks (§6) are running or have just completed; **no device contact has occurred** |
| `EXECUTING` | no | pre-execution checks passed; the worker is running steps; one or more step-attempt records may exist (§5) |
| `COMPLETED` | yes | every step (including `finally`) reached a definite, non-ambiguous successful outcome |
| `FAILED` | yes | a definite, non-ambiguous failure — a pre-execution check failed with certainty is `REJECTED` instead (below); `FAILED` covers a step that failed with a received, parsed response (connect refused, expectation unmet, device replied with an explicit error) where the worker can prove no operational-write step's outcome is ambiguous |
| `REJECTED` | yes | a pre-execution check (§6) refused the job before any device contact; the specific check and reason are recorded |
| `CANCELLED` | yes | an authorized human cancelled the job before device contact (§3.4) |
| `OUTCOME_UNKNOWN` | yes (until reconciled) | a step-attempt record proves a command may have reached the device, but its result could not be confirmed (§3.5, §8) |
| `RECONCILED` | yes | the only transition out of `OUTCOME_UNKNOWN`; a reconciliation record (§3.5) closed the ambiguity without a second device contact |

### 3.2 Legal transitions (diagram-as-text)

```
REQUESTED ──claim (lease acquired, fencing token issued)──► CLAIMED
REQUESTED ──cancel (operator, before claim)────────────────► CANCELLED
CLAIMED   ──lease expires, no step-attempt written──────────► REQUESTED   (requeue; new fencing epoch on next claim)
CLAIMED   ──any pre-execution check (§6) fails───────────────► REJECTED
CLAIMED   ──cancel (operator, before device contact)─────────► CANCELLED
CLAIMED   ──all pre-execution checks pass─────────────────────► EXECUTING
EXECUTING ──every step + finally succeeds──────────────────────► COMPLETED
EXECUTING ──a step fails with a definite (non-ambiguous) result─► FAILED
EXECUTING ──lease expires or crash AFTER a step-attempt was
             sent but its result could not be confirmed────────► OUTCOME_UNKNOWN
EXECUTING ──read-class step, transient transport failure,
             bounded retry budget remaining (§5.3)──────────────► EXECUTING (re-attempt, same job_id, new step-attempt row)
OUTCOME_UNKNOWN ──reconciliation record recorded (§3.5)─────────► RECONCILED
```

No other edge exists. `COMPLETED`, `FAILED`, `REJECTED`, `CANCELLED` and
`RECONCILED` have no outgoing edges (AC-1: the graph is closed; every state
has an explicit set of allowed transitions and causers; nothing leaves
`OUTCOME_UNKNOWN` except the one edge to `RECONCILED`). A job's `job_id`
never gets a "second attempt" once it has reached any terminal state,
including `RECONCILED` — resolving a conflicting operation after
`OUTCOME_UNKNOWN` means creating a **new** job row with its own `job_id`,
its own `idempotency_key`, its own owner/approver/reason, and — where the
new job targets the same entity while the old one is still `OUTCOME_UNKNOWN`
— an explicit `supersedes_job_id` field naming the ambiguous job it follows.
That new job is never created automatically; it requires either recorded
reconciliation evidence establishing the true device state, or a separately
authorised intervention decision (`UI2_0_BASELINE_CONTRACT.md` D-2a,
acceptance sentence A-2) — never a bare retry.

### 3.3 Who may cause each transition

| Transition | Causer |
|---|---|
| (creation) → `REQUESTED` | the API (Run Now request) or the scheduler (due `BackupSchedule`), both already past `E1`–`E6` |
| `REQUESTED` → `CLAIMED` | a worker's claim query (§4) — a **system** actor, not a human |
| `REQUESTED` → `CANCELLED` | `role:backup_admin` (or the equivalent read-job role) via an explicit cancel intent, audited; refused once the job is `CLAIMED` or later — cancellation after that point risks racing a worker that has already begun (§3.4) |
| `CLAIMED` → `REQUESTED` | the **reconciler** (a scheduled process or the next claim attempt's own expiry check), never a human decision — this is pure lease-expiry bookkeeping with no device-visible effect |
| `CLAIMED` → `REJECTED` | the claiming worker, from a failed pre-execution check (§6) — a system actor, not a human veto |
| `CLAIMED` → `CANCELLED` | `role:backup_admin`, same as above, still legal because no device contact has occurred |
| `CLAIMED` → `EXECUTING` | the claiming worker, once every pre-execution check passes |
| `EXECUTING` → `COMPLETED` / `FAILED` | the claiming worker, from a step outcome |
| `EXECUTING` → `OUTCOME_UNKNOWN` | the reconciler, from an expired lease with an unresolved step-attempt (§3.5), **or** the claiming worker itself, if it observes an ambiguous response before losing its lease |
| `OUTCOME_UNKNOWN` → `RECONCILED` | a human with the authority the reconciliation record requires (§3.5) — never a worker, never automatic |

### 3.4 Cancellation boundary

Cancellation is legal only in `REQUESTED` and `CLAIMED` — i.e. strictly
before device contact. Once a job is `EXECUTING`, "cancel" would mean racing
an in-flight device operation with no way to know whether the cancel or the
command arrives first; this contract does not offer it. An `EXECUTING` job
runs to one of its own terminal states; an operator who wants to stop future
harm disables the schedule or revokes the profile's approval (both are
policy writes that block the **next** claim's pre-execution check, §6) —
neither reaches back into a job already executing.

### 3.5 `OUTCOME_UNKNOWN` and reconciliation (`JOB-UNCERTAIN-OUTCOME`, A-2)

A job reaches `OUTCOME_UNKNOWN` when a step-attempt record proves a command
was sent (§5.1) but the reconciler cannot establish, from the durable step
log, whether the device executed it. This is a **terminal-until-reconciled**
state (`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-2):

- **No automatic second write.** Nothing in this contract, at any layer,
  re-sends the operational-write step. The reconciler's only job for an
  `OUTCOME_UNKNOWN` row is to leave it exactly as it is.
- **Reconciliation record.** A `job_reconciliation` row: `job_id`, `evidence`
  (what was inspected — e.g. a subsequent read-class job's observation of
  the device's backup listing, or an operator's own device session outside
  this product), `recorded_by` (`actor_fingerprint`, `role:backup_admin` or
  stronger), `recorded_at`, `reconciled_outcome` ∈
  `{RECONCILED_SUCCESS, RECONCILED_FAILURE, RECONCILED_STILL_UNKNOWN}`. Any
  of the three values is a legal reconciliation — `RECONCILED_STILL_UNKNOWN`
  exists because "we looked and still can't tell" is a legitimate, auditable
  outcome, not a reason to withhold the record. Writing this row is the sole
  trigger for `OUTCOME_UNKNOWN` → `RECONCILED`.
- **Reopening a conflicting operation** — i.e. actually running the
  operational-write again against the same target — requires either (a) the
  reconciliation record above establishing the write did not happen, or (b)
  a separately authorised intervention decision (a human decision outside
  this job's own record, e.g. a `RELAY_DECISION` or an equivalent recorded
  authorization) — and even then, per §3.2, it happens as a **new** job, not
  a mutation of the `OUTCOME_UNKNOWN`/`RECONCILED` row.

---

## 4. Claiming: lease, heartbeat, fencing token, worker loss, multi-worker safety

### 4.1 Lease acquisition — one atomic statement

A worker claims the next eligible job with a single atomic
`UPDATE ... WHERE ... RETURNING`, not a read-then-write:

```sql
UPDATE job
SET state = 'CLAIMED',
    lease_worker_id = :worker_id,
    lease_epoch = lease_epoch + 1,
    lease_expires_at = now() + interval '60 seconds',
    last_heartbeat_at = now()
WHERE job_id = (
    SELECT job_id FROM job
    WHERE state = 'REQUESTED'
      AND job_type = ANY(:worker_capable_job_types)
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING job_id, lease_epoch;
```

`FOR UPDATE SKIP LOCKED` is the multi-worker safety primitive: two workers
racing the same poll never block each other and never claim the same row —
one gets the row, the other's `SELECT` simply skips it and returns the next
eligible one (or none). This is directly Testcontainers-testable (AC-3): a
test starts a real PostgreSQL container, seeds N `REQUESTED` jobs, starts M
concurrent worker connections issuing the claim statement in a tight loop,
and asserts (a) every job is claimed by exactly one `lease_worker_id`, (b)
`lease_epoch` is strictly increasing per job across any re-claims, (c) the
sum of claimed jobs across workers equals N with zero duplicates.

### 4.2 Fencing token

`lease_epoch`, returned to the claiming worker, is the fencing token. Every
subsequent write this worker makes to the job or its step-attempts — the
`EXECUTING` transition, every step-attempt insert, every heartbeat, the
terminal-state write — carries `WHERE lease_epoch = :claimed_epoch` in its
own `UPDATE`. A worker that believes it still owns a job but whose lease has
actually expired and been re-claimed (clock skew, a long GC pause, a network
partition that heals late) has its writes silently affect zero rows once a
second worker's claim has bumped the epoch — the zombie worker's own next
write fails its row-count check, and its execution loop treats that as "I no
longer own this job" and stops, without ever needing to detect the expiry
directly. This is what makes "two workers execute the same step attempt"
structurally impossible (AC-3) rather than merely improbable: the fencing
check is unconditional on every state-affecting write, not only on the
initial claim.

### 4.3 Heartbeat

While `EXECUTING`, the worker extends its own lease every 20 seconds (one
third of the 60-second lease duration) with the same fencing-token-guarded
update:

```sql
UPDATE job
SET lease_expires_at = now() + interval '60 seconds',
    last_heartbeat_at = now()
WHERE job_id = :job_id AND lease_epoch = :claimed_epoch AND state IN ('CLAIMED', 'EXECUTING');
```

A missed heartbeat does not itself change job state — only lease expiry
(below) does. The heartbeat interval is deliberately a fraction of the
lease duration so one missed heartbeat (a transient scheduling delay) is not
mistaken for worker loss; three consecutive missed heartbeats exhaust the
lease.

### 4.4 Lease expiry and worker-loss detection

A reconciler (a lightweight periodic scan, or the claim query's own
`REQUESTED`-eligibility check treating an expired `CLAIMED`/`EXECUTING` job
as needing reconciliation first) evaluates any job whose
`lease_expires_at < now()`:

| Condition at expiry | Resulting transition |
|---|---|
| No step-attempt row exists for this job at this `lease_epoch` (worker died before or during pre-execution checks, before any device contact) | `CLAIMED` → `REQUESTED` (or stays `REQUESTED` if it never left it) — a new worker may claim it; no ambiguity exists because nothing was sent |
| A step-attempt row exists with `mutation_boundary_crossed = NO` for every step so far (the worker died between steps, but the furthest step it reached never crossed the write boundary — e.g. it died during a `connect`/`exec` read step) | `EXECUTING` → `REQUESTED`; the run restarts from the beginning at the next claim (steps are not resumed mid-sequence — a fresh `EXECUTING` run re-executes `connect` onward) |
| A step-attempt row exists with `mutation_boundary_crossed = YES` and no confirmed `outcome` was recorded for that step | `EXECUTING` → `OUTCOME_UNKNOWN` (§3.5, §8) — **no second claim is issued**; this row is never picked up by the claim query again |

The reconciler's own transition writes are themselves fencing-token-guarded
(`WHERE lease_epoch = <the epoch recorded on the step-attempt row>`), so a
worker that wakes up late from the same GC pause and tries to keep working
after its lease actually expired cannot un-do the reconciler's decision.

---

## 5. Step execution

### 5.1 Step-attempt record before contact

For every step, the worker writes a `job_step_attempt` row **and commits
it** before sending anything to the device:

| Field | Written | Meaning |
|---|---|---|
| `job_id`, `lease_epoch`, `step_index`, `step_kind` | before contact | identifies the attempt and binds it to the fencing token that authorized it |
| `action_class` | before contact | the step's own class per `utils.action_taxonomy` (a profile mixes class-0 preconditions with class-1 write steps, design §6.3; retry rules key on this field, not only the job's overall class) |
| `mutation_boundary_crossed` | `NO` before contact, flipped to `YES` in the **same transaction** that records "the command has been sent" | the two-valued, never-`UNKNOWN` field the crash matrix (§8) depends on — mirroring `utils/operate/record.py`'s `mutation_boundary_crossed` pattern (§1.4), restated here for a multi-worker table rather than a single in-memory record |
| `sent_at` | at the moment of send | — |
| `outcome`, `matched_expectation`, `output_bytes`, `output_lines`, `fingerprint_sha256`, captured variables | after the device responds and the expectation gate evaluates (design §6.3/§6.4) | never the raw transcript — discarded per `output_policy: discard_raw` and the raw-evidence law |
| `error_class` | on failure | the named failure class (`STEP_EXPECTATION_UNMET`, `STEP_POLL_TIMEOUT`, `STEP_AMBIGUOUS_RESPONSE`, `STEP_OUTPUT_OVERFLOW`, transport-level classes) |

Writing the pre-contact row **and committing it** before the SSH/API call is
what makes the crash-point-3 and crash-point-4 rows of §8 well-defined: if
the worker dies after sending the command but before the outcome is
recorded, the durable evidence that a command *may* have reached the device
already exists, independent of whether the worker itself survives to record
the result.

### 5.2 Per-step timeout

Every step declares `timeout_s` (design §6.3's `expect`/`until` fields); a
timeout is never a success (design §6.3 point 4) and never leaves
`mutation_boundary_crossed` ambiguous — it is a boolean the worker sets
deterministically at send time, not inferred afterward. A step that times
out after crossing the boundary is exactly the crash-matrix case above,
whether the timeout comes from a genuine crash or from a slow/hung device.

### 5.3 Retry rules per action class (`utils.action_taxonomy`, AC-4)

| Action class | Retry rule |
|---|---|
| `CLASS_0_READ` (`read`) | Bounded automatic retry — proposed: up to 2 retries, exponential backoff starting at 2 s — **only** for a transport-level failure before any response was received (connection refused, connect timeout, DNS/handshake failure). A retry creates a new `job_step_attempt` row at the same `step_index` with an incremented `attempt_number`; it never retries after a response was received and parsed (that is a semantic failure, not a transient one, and is `FAILED`, not retried). Retry exhaustion is `FAILED` (or, if the boundary was somehow already crossed by an earlier attempt at the same step — which cannot happen for a class-0 step by construction, since a read step's precondition is that it declares no mutation — `OUTCOME_UNKNOWN` never applies to a pure class-0 job). |
| `CLASS_1_RECOVERY_WRITE` (`recovery-write`) | **Never auto-retries, at any stage, for any reason** — this is the direct implementation of `UI2_0_BASELINE_CONTRACT.md` D-2a and the risk this contract's own dispatch explicitly names ("allowing a 'safe' automatic retry ... because the command is idempotent"). A class-1 step's failure or ambiguity ends the job (`FAILED` or `OUTCOME_UNKNOWN`, §3, §8); any further attempt is a brand-new job with its own owner, reason and (where `OUTCOME_UNKNOWN` is involved) reconciliation evidence, per §3.5. This holds even when the underlying device command is documented as idempotent — idempotency of the **device command** is not the same guarantee as safety of an **automatic** retry with no human decision point (brief §6.3), and this contract does not let one substitute for the other. |
| `CLASS_2`/`CLASS_3`/`CLASS_4` | Not applicable — no member of these classes is executable through this job contract (`utils.action_taxonomy`: class 2 has no member yet, classes 3–4 are prohibited); a job whose resolved `action_class` is one of these is refused at admission (`E3`/`E6`), never reaches `CLAIMED`. |

A profile that mixes classes (design §6.3's example: class-0 `connect`/
`exec` steps, a class-1 `add backup local` step, a class-1 `finally` cleanup
step) applies the rule **per step**: the class-0 precondition steps may
retry transiently; the moment a class-1 step is reached, retry stops being
available for the rest of that job's `EXECUTING` run.

### 5.4 Step log data class

The step log (`job_step_attempt` rows) is derived operational metadata —
outcomes, byte/line counts, fingerprints, declared captures, error classes —
never raw device output (§5.1, raw-evidence law) and never a credential or
secret. Its data-class assignment and retention policy are `C1`'s to make
(per `PRIVACY_AND_DATA_HANDLING.md`'s CLASS 0–3 scheme); this contract only
fixes the field set and the write-ordering invariant above.

---

## 6. Pre-execution checks

Pre-execution checks run **at claim time**, inside the `CLAIMED` state,
strictly before the first device contact (`EXECUTING`). They are the `E7`
step of the gate chain (design §5.3: "`E7` (execution phase only) admission
+ immediately-before-execution checks") plus the additional battery this
contract's own scope requires. Every check is recorded on the job's
`precheck_results` list, in this fixed order; the first failure aborts the
claim and transitions `CLAIMED` → `REJECTED` with that check named — no
later check runs, and no device is contacted:

| # | Check | Outcome field | What it re-reads |
|---|---|---|---|
| 1 | **Approval state, re-checked fresh** — the profile version is still `APPROVED` (not superseded/retired); the target's `BackupAssignment` (or capability assignment for a read job) is still enabled; for a Run Now, the `D7` role binding and mandatory reason are still valid for the owning actor | `PASSED` / `FAILED(reason)` | current rows, never the request-time snapshot in §2.2 — this is what closes AC-7: approval is checked at claim time, not only at request time |
| 2 | **Connectivity precondition** — for a class-1 profile, a prior successful `backup_profile_connect_check` exists for this `{profile_id, version, device_id}` (design §6.5's `SCHEDULE_ENABLE_REQUIRES_CONNECT_CHECK` invariant, re-verified at every claim, not only at schedule-enable time). For `CLASS_1B_CONTROLLED_RESTORE_WRITE`, re-evaluate `RestoreConnectivityFreshnessPolicy` at every claim per `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.3; the compile-time result is not authoritative. | `PASSED` / `FAILED(reason)` / `NOT_APPLICABLE` (class-0 job) | the connect-check record and, for restore, the current freshness policy |
| 3 | **Line-1/Java device-contact coordination window** (workflow §3.4, R-08) — per device and job type, only one line may contact the device in a given window; the claim refuses if the coordination record shows Line-1 currently owns this device+job-type window | `PASSED` / `BLOCKED(reason)` | the coordination record (a shared window/ownership record, not a shared runtime) |
| 4 | **Class-scoped write ledger** — `CLASS_1_RECOVERY_WRITE` retains the `RB.x` ledger, read *inside* the claimed/admitted section per `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §5/§7: unreadable ⇒ `BLOCKED`; inside the minimum interval ⇒ `BLOCKED` with due time; absent/outside ⇒ `PASSED`. For `CLASS_1B_CONTROLLED_RESTORE_WRITE`, independently name and record `RestoreWriteLedger.has_unreconciled_prior` against the physical `device_id`, re-read fresh inside this same admission section: unreconciled prior outcome or unreadable ledger ⇒ `BLOCKED`; no unreconciled prior ⇒ `PASSED`. Never trust C7's earlier check-7 pass. | `PASSED` / `BLOCKED(reason)` / `NOT_APPLICABLE` (every other class) | the class-specific ledger; `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.0/§5 for restore |
| 5 | **Device registry / allowlist re-check** — every `target_ref` still resolves to a live, non-disabled registry entry; for class-1, the target is still within the profile's fail-closed allowlist. For controlled restore-write, re-evaluate `RESTORE_TARGET_TOPOLOGY_ELIGIBILITY` from authoritative registry plus current topology evidence: only `STANDALONE_PHYSICAL_DEVICE` proceeds; refuse with `TARGET_CLUSTERXL_MEMBER_UNSUPPORTED`, `TARGET_TOPOLOGY_EVIDENCE_MISSING_OR_STALE`, or `TARGET_TOPOLOGY_EVIDENCE_CONFLICTING` as appropriate. VSX-context targets remain unsupported; physical `device_id`/`endpoint_id` only. | `PASSED` / `FAILED(reason)` | the device registry and current topology evidence for restore |
| 6 | **Credential resolution** — the worker resolves `execution_credential_ref` from the server-side credential store for the profile's/capability's declared `credential_profile_ref`; a resolution failure blocks before contact | `PASSED` / `FAILED(reason)` | the credential store (server-side only; nothing reaches the operator's browser or the job row as a value) |

For restore check 2, the active probe uses the actual restore-write transport.
Cached evidence requires explicit configuration, TTL, identity binding and a
per-vendor/platform council-reviewed semantic-sufficiency contract; failure
of any condition falls back to the active probe. Probe failure/timeout blocks
admission. Numeric timeout/TTL values remain `UNKNOWN`; no default is invented.
Record evidence source, age, fallback and policy snapshot per ledger §3.3.1.
These amendments add no standalone C2 check 7: the six-check order is retained.
Static C4 sign-off remains necessary but never sufficient for runtime admission.

Checks 2 and 4 are `NOT_APPLICABLE` for a class-0 job — recorded as such,
never silently omitted, so the ordered list is complete for every job
(AC-6: enumerated in order, each with a recorded outcome field). A
`REJECTED` job's `precheck_results` list is therefore always a complete,
truncated-at-first-failure trace an operator can read to see exactly why no
device was contacted.

---

## 7. Schedules: versioning, optimistic concurrency, actor decoupling, Run Now

### 7.1 Optimistic concurrency (AC-8)

`BackupSchedule` (design §6.2) gains an explicit `version` integer, starting
at 1 and incremented by exactly one on every accepted edit. The schedule-edit
intent (design §6.7) gains a required `expected_version` field. On write:

```sql
UPDATE backup_schedule
SET cadence = :cadence, window = :window, enabled = :enabled, version = version + 1,
    ...
WHERE schedule_id = :schedule_id AND version = :expected_version;
```

Zero rows affected ⇒ **refuse**, never clamp, never silently merge (design
§6.7 semantics preserved verbatim). The refusal shape:

```json
{
  "error": "SCHEDULE_VERSION_CONFLICT",
  "schedule_id": "...",
  "expected_version": 4,
  "current_version": 6,
  "current_schedule": { "...": "the full current row, so the UI can show what changed" }
}
```

The client never receives only "conflict" — it receives the current row so
the operator can decide whether to re-apply their edit against the new
version or abandon it; the server never picks for them. This is the same
`schedule_changes` audit-before-mutation ordering design §6.7 already
requires (an immutable `schedule_changes` row durable before the
`BackupSchedule` row changes) — the version check happens as part of that
same conditional update, not as a separate pre-check that could itself race.

### 7.2 Owner, approver, execution identity (resolves brief SR-D6)

Three distinct fields, three distinct sources, enforced as separate columns
that no code path collapses into one another:

| Field | Source | Never equals |
|---|---|---|
| **Owner** | For a Run Now: the actor who submitted the request (`session`'s resolved `actor_fingerprint`, §2.1 `origin.session_id`). For a scheduled run: the schedule's `created_by` — the identity that authored the schedule, durable regardless of who later edits it. | the execution credential (never inferred from "who has the credential") |
| **Approver(s)** | The profile version's own `approved_by` (design §6.5, author ≠ approver, four-eyes) and, where the schedule itself required its own `D7` authorization (`APPROVAL-MODEL`: four-eyes on schedule authorisation), the schedule's `authorized_by` — read from those rows at job-creation time, never re-derived from the requester or from "whoever last edited the schedule" | the owner (a self-approval is structurally impossible: `authorized_by ≠ created_by` is enforced the same way profile approval already is, design §6.5) |
| **Execution identity** | `execution_credential_ref`, resolved by the worker at claim time (§6 check 6) from the profile's/capability's declared `credential_profile_ref` | **the last editor of anything** — this is the explicit invariant this contract states to close SR-D6/Astra §6.7 point 3: a schedule is never bound to "whoever saved it last" as its authority for a scheduled class-1 run |

**Resolving SR-D6 concretely.** The council's brief proposed defining the
scheduler's actor as "the last schedule editor." Astra's second-opinion
round explicitly rejected binding a schedule permanently to its last editor
and asked for owner/approver/execution-identity separation plus a visible
notification on authorization loss, not silent unprotected continuation.
This contract adopts Astra's framing: a scheduled class-1 job's `E4`
re-evaluation at claim time (§6 check 1) checks the **schedule's recorded
`authorized_by` actor's `D7` binding**, not a live session (a scheduled run
has none) and not "the last person who touched the row." If that binding
has lapsed (group membership lost, binding revoked) between authorization
and the next due run, the pre-execution check fails with
`FAILED(schedule_authorization_lapsed)`, the job is `REJECTED`, and — this
is the notification half of Astra's point — the schedule itself transitions
to a disabled/needs-reauthorization state (owned by `C7`'s schedule-lifecycle
rows, referenced here) so the lapse is visible on the backup overview
screen, not merely absent from the run history. A lapsed schedule does not
silently stop backing up a device with no operator the wiser; it stops
**and says so**.

### 7.3 Run Now as a job request (`UI-OPERATIONAL-RUN-NOW`, AC-7)

A Run Now is created exactly like a scheduled job — the same `job` row
shape, the same `REQUESTED` → `CLAIMED` → `EXECUTING` path, the same claim
query, the same pre-execution checks (§6). The only difference is `origin`
(§2.1): `{kind: "run_now", session_id}` instead of
`{kind: "schedule", schedule_id, schedule_version}`. There is no
browser-executes-directly path and no console-submission path for a class-1
Run Now (`utils.action_taxonomy.CLASS_1_RECOVERY_WRITE.console_submittable
= false`, unchanged, design §6.6): the browser's `POST` creates a
`REQUESTED` row and nothing else; a worker claims and executes it under the
identical rules as a scheduled run, including the approval re-check at claim
time (§6 check 1) that closes the race brief §6.4/§6.6 raised — an approval
revoked between the Run Now request and the worker's claim is caught there,
not assumed still valid from request time.

---

## 8. Crash matrix

Five crash points, each naming the resulting state, whether a second device
contact is possible, and what the operator sees (AC-2).

| # | Crash point | Resulting state | Second device contact possible? | What the operator sees |
|---|---|---|---|---|
| 1 | **Before claim** — the job row exists (`REQUESTED`), no worker has claimed it yet, and the process that would have claimed it dies (or never starts) | stays `REQUESTED` | Yes — no contact has ever occurred; any worker may claim it once available | "Queued" — no step activity, no lease held |
| 2 | **After claim, before contact** — a worker holds the lease, is running pre-execution checks (§6) or has just entered `EXECUTING`, and dies before any step-attempt row is committed | lease expires (§4.4) → `CLAIMED`/`EXECUTING` → `REQUESTED` | Yes — nothing was sent; this is the first attempt, not a second one | Briefly "claimed by \<worker\>", then "queued" again once the lease expires (≤ 60 s) — no error surfaced, this is routine worker-loss recovery |
| 3 | **After contact (command sent), before result recorded** — the worker sent the command, the device may have executed it, and the worker dies before writing an outcome | `EXECUTING` → `OUTCOME_UNKNOWN` (via lease-expiry reconciliation, §4.4, §3.5) | **No** — the job is removed from the claim pool permanently; no worker ever claims this `job_id` again | "Outcome unknown — a command may have reached the device; no automatic retry" with the step index and the last confirmed pre-contact state; a "start reconciliation" action is offered, never a "retry" action |
| 4 | **After result computed, before commit** — the worker received and parsed the device's response (it "knows" the outcome in memory) but crashes before the database transaction that would persist it commits | database atomicity means nothing durable changed — indistinguishable from crash point 3 from any later reader's perspective | **No**, same reasoning as point 3 | Identical to point 3: `OUTCOME_UNKNOWN` once the lease expires, because the durable record shows a sent-but-unconfirmed step regardless of what the now-dead worker's memory briefly held |
| 5 | **After commit, before notify** — the terminal state (`COMPLETED`/`FAILED`/`OUTCOME_UNKNOWN`) is already durably committed; the worker (or a separate notifier) dies before a push notification (websocket, email) fires | job stays exactly in its already-correct terminal state — no transition, because the terminal write already succeeded | N/A — the job is already terminal; nothing re-executes | The correct final state, on the operator's **next read** of the job (page load, poll, refresh) — the notification channel is best-effort only; the job row is always the source of truth, so a lost notification never leaves the operator seeing a stale state, only a delayed one |

The invariant AC-2 requires — "no second device contact for any point after
contact" — holds for points 3, 4 and 5 by construction: `OUTCOME_UNKNOWN`
and every other terminal state have no outgoing edge in the transitions
graph (§3.2) except the single `OUTCOME_UNKNOWN` → `RECONCILED` edge, which
itself never re-contacts a device (§3.5).

---

## 9. Acceptance criteria for B1-4 and acceptance scenarios B1-10 / B1-11

At least ten, each independently testable (AC-9):

1. **State-machine closure.** A property-based or exhaustive test asserts that for every `(from_state, to_state)` pair not listed in §3.2's legal-transitions graph, the persistence layer refuses the transition (the fencing-guarded `UPDATE`'s `WHERE state = <expected from_state>` clause affects zero rows) — proves AC-1 in code, not only in prose.
2. **Multi-worker claim safety (Testcontainers).** N `REQUESTED` jobs, M concurrent worker connections against a real PostgreSQL container issuing the §4.1 claim statement concurrently in a loop until all N are claimed: every job is claimed by exactly one worker, `lease_epoch` is 1 on first claim, and no two `job_step_attempt` rows for the same `(job_id, step_index)` carry the same `lease_epoch` unless one of them was itself a legal same-epoch retry (§5.3) from the *same* worker.
3. **Fencing token rejects a zombie writer.** A test simulates a worker whose lease has expired and been re-claimed by a second worker (bump `lease_epoch` directly), then has the *first* worker attempt a step-attempt write using its stale epoch: the write affects zero rows and the first worker's execution loop observes the zero-row result and stops without contacting the device again.
4. **Lease-expiry branching by mutation boundary.** Three Testcontainers scenarios differing only in whether a `job_step_attempt` row with `mutation_boundary_crossed = YES` exists at expiry: (a) none → `REQUESTED`; (b) exists but every row is `NO` → `REQUESTED`; (c) at least one `YES` with no confirmed outcome → `OUTCOME_UNKNOWN`. Proves the crash-matrix branch (§8, §4.4) deterministically from durable state alone.
5. **Step-attempt-before-contact ordering.** A test double for the transport records the wall-clock order of "the `job_step_attempt` row's `mutation_boundary_crossed = YES` write commits" vs. "the simulated device write is invoked": the commit is always first, with zero exceptions across a fuzzed set of simulated failure injection points.
6. **Read-class retry, bounded.** A class-0 step whose transport fails twice then succeeds on the third attempt completes the job `COMPLETED`, with three `job_step_attempt` rows at the same `step_index` and increasing `attempt_number`; a fourth consecutive failure (exceeding the retry budget) yields `FAILED`, never `OUTCOME_UNKNOWN` (a class-0 step never crosses the mutation boundary).
7. **Operational-write never auto-retries.** A class-1 step failure (any `error_class`) or an injected worker crash after send produces exactly one `job_step_attempt` row at that `step_index` for the job's entire lifetime — the job reaches `FAILED` or `OUTCOME_UNKNOWN` and the claim query never selects this `job_id` again, verified by asserting the claim query returns nothing for it across repeated polls.
8. **`OUTCOME_UNKNOWN` closes only via reconciliation.** An attempt to transition an `OUTCOME_UNKNOWN` job to any state other than `RECONCILED` (including a second claim attempt) is refused at the persistence layer; writing a `job_reconciliation` row is the only operation observed to change its state, and the state that results is always `RECONCILED` regardless of the recorded `reconciled_outcome` value.
9. **Schedule version conflict, refusal shape.** Two concurrent schedule-edit intents against the same `schedule_id` and the same `expected_version`: the first succeeds and increments `version`; the second is refused with `SCHEDULE_VERSION_CONFLICT` carrying the now-current `version` and row, and the underlying `cadence`/`window`/`enabled` fields show only the first edit's values — never a merge of both.
10. **Run Now approval re-check at claim time, not only at request time.** A Run Now job is created while its profile version is `APPROVED`; before any worker claims it, the profile version is retired (or the `D7` binding is revoked); the claim's pre-execution check 1 fails, the job transitions to `REJECTED(schedule_authorization_lapsed | profile_not_approved)`, and no `job_step_attempt` row is ever created.
11. **Owner/approver/execution-identity distinctness.** For a scheduled class-1 job, a test asserts `job.owner == schedule.created_by`, `job.approvers` contains the profile's `approved_by` and (where applicable) the schedule's `authorized_by`, `job.execution_credential_ref` is populated only after `CLAIMED`, and no code path sets `execution_credential_ref` from any of the identity fields (a static/architectural test forbidding that assignment shape) — directly proving the SR-D6 "last editor is never execution identity" invariant (§7.2).
12. **Concurrent Run Now de-duplication (bridges B1-10's concurrency scenario into C2's scope).** Two admin sessions submit a Run Now for the same `{device_id, profile_id, version}` within the idempotency window with no client-supplied key: the second request is refused or resolves to the same `job_id` as the first (server-generated idempotency key collision per §2.3) rather than creating a second job that could independently claim and contact the device — proving that C2's job admission, not session takeover alone, is what prevents two admins from causing duplicate device contact.

Scenario B1-11 (worker killed mid-step; job lands in `OUTCOME_UNKNOWN`; no
duplicate device contact; recovery path visible) is directly covered by
criteria 3–8 and the crash matrix (§8) together — no additional criterion is
needed beyond those. Scenario B1-10 (three admins on one device
concurrently, single-session takeover/refuse, correct state throughout) is
primarily `C3`'s contract; criterion 12 above states the one piece of it
that is C2's own responsibility — that concurrent requests for the same
operation do not produce concurrent device contact regardless of how many
sessions or admins are involved.

---

## 10. Contradictions and open items for the Product Owner

**Contradictions with frozen authority: none found.** Documents checked:
`AGENTS.md`, `docs/design/UI2_0_BASELINE_CONTRACT.md`,
`docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md`,
`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (as amended by the workflow),
`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`,
`utils/action_taxonomy.py`, `docs/AI_DEVELOPMENT_PROTOCOL.md`. This document
reopens no PO-reserved decision: `JOB-UNCERTAIN-OUTCOME`, `UI-OPERATIONAL-
RUN-NOW` and `APPROVAL-MODEL` are implemented as ruled, not re-litigated —
in particular, whether a routine-scope Run Now needs a second human approver
stays settled (`D7` role + reason, no second approver, per D-2c) and this
document does not touch it.

**Open items, not contradictions:**

1. **Lease duration and heartbeat interval (60 s / 20 s) are proposed
   defaults**, not vendor-derived or corporate-mandated numbers — chosen for
   testability and a conservative worker-loss detection window. The Product
   Owner may tune them; nothing else in this contract depends on their exact
   values, only on the lease-duration-vs-heartbeat-interval ratio staying
   generous enough that one missed heartbeat is not mistaken for loss (§4.3).
2. **Read-class retry budget (2 retries, 2 s backoff) is a proposed
   default**, per §5.3 — the *rule* (retry only pre-response, never
   post-response) is the load-bearing part; the specific numbers are not.
3. **SR-D6's resolution direction differs from the brief's original
   proposal** ("define the actor as the last schedule editor") and instead
   adopts Astra's owner/approver/execution-identity separation with a visible
   lapse notification (§7.2). This is presented as this contract's answer to
   an explicitly still-open council item, not as overriding a Product Owner
   ruling — no PO decision named SR-D6's resolution before this document.
4. **The lapsed-schedule "needs-reauthorization" state and its surfacing on
   the backup overview screen** are named here as a requirement (§7.2) but
   the state's own schema and the screen's own layout belong to `C7` and the
   later UI-integration movement respectively; this document does not define
   either, only the trigger condition and the requirement that it be visible.

---

## 11. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED, 2026-09-09) — binding direction; `JOB-UNCERTAIN-OUTCOME`, `UI-OPERATIONAL-RUN-NOW`, `APPROVAL-MODEL`, acceptance sentences A-1/A-2.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §3.2 (step executor, crash-safe job record, duplicate detection), §3.4 (device-contact coordination, R-08), §5 B0-2 (this movement's own scope line).
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §5.4 (storage/audit pattern this contract's tables follow), §6.2–§6.7 (profile/step model, lifecycle, taxonomy reconciliation, schedule-edit intent — the object this contract's `version`/optimistic-concurrency addition extends).
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` §6.3 (the job-execution-contract concern answered by §3–§5, §8 above), §6.7 (the identity concern answered by §7.2), §4.3/§182 (SR-D6, resolved §7.2/§10).
- `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — Line-1 fail-closed reference for §6 check 4.
- `utils/action_taxonomy.py` — the five action classes; §5.3, §7.3, §9 all key on it directly.
- `utils/operate/states.py`, `utils/operate/record.py` — reference-only pattern for the mutation-boundary-before-contact discipline and closed-transitions-graph shape (§1.4); not ported.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — network-device command gate (unaffected; this contract contacts no device itself) and approval boundaries.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (concurrent movement, `C1`) — owns the audit table, secrets, and adopts the lifecycle columns this document names explicitly in §2, §4, §5, §7.
