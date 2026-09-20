SESSION START

- Role: Lead Software Architect / Codex Reviewer (Astra)
- Movement: ARCHITECTURE
- Objective: Review Phase D schedule integrity, temporal safety, T₀ verification, drift abort, and Phase C handoff
- Scope: Design and supplied Phase B/C code only
- Repository/device changes: None
- Recommended reasoning level: High; unattended CLASS 2 execution crosses security, persistence, timing, and distributed-state boundaries

# Verdict: [REJECTED]

The safety intent is sound, but the proposed implementation does not yet prove safe unattended execution. The principal blocker is a time-of-check/time-of-use gap: Phase D performs its decisive window and drift checks before acquiring Phase C’s execution lock. Waiting for that lock, process restarts, concurrent schedulers, cancellation, or state changes can invalidate the checks before mutation.

## P0 — Must resolve before implementation

### P0.1 — Final authorization check is outside the mutation boundary

The proposed sequence is:

1. Verify schedule.
2. Check window and revocation.
3. Collect T₀ evidence.
4. Evaluate drift.
5. Acquire concurrency lock.
6. Mint lease.
7. Call `executeFailover()`.

This is unsafe. The scheduler may wait after step 4. Phase C then performs another health read, but it does not revalidate:

- schedule signature;
- schedule status or revocation;
- execution-window expiry;
- authorized active/standby identities;
- policy hash;
- software version;
- the complete Phase D drift policy.

The final Phase D gate must execute inside the same exclusive execution critical section as Phase C and immediately before durable lease consumption and command submission. The window, revocation, quarantine, pilot fence, schedule claim, fresh evidence, and drift result must all still be valid there.

Recommended shape:

```text
durably claim schedule
    → acquire fleet/entity execution ownership
    → recheck signature/status/revocation/window
    → collect one fresh two-sided snapshot
    → evaluate full preflight + frozen drift policy
    → atomically consume scheduled authorization
    → submit exactly one command
```

Do not implement this as a second public execution path. Extend the existing Phase C boundary with a server-owned scheduled-execution context or final pre-mutation gate.

### P0.2 — Current concurrency and at-most-once controls are process-local

The supplied Phase C service uses in-memory:

- `Semaphore`;
- `activeClusterLocks`;
- lease tokens and consumed-token IDs;
- quarantine;
- execution history.

These controls do not survive restart and do not coordinate multiple service replicas. A restart or overlapping poller can cause duplicate ownership, lost quarantine, or reuse of an authorization record.

Phase D needs a durable transactional claim such as:

```text
SCHEDULED → CLAIMED/VERIFYING
```

with a unique execution-attempt identifier and compare-and-set semantics. Only one claimant may advance a schedule. The mutation boundary must durably record authorization consumption before command submission.

A crashed process after submission can never safely infer that no command was sent. Recovery must produce sticky `OUTCOME_UNKNOWN`, not retry.

### P0.3 — The HMAC does not cover all execution-authoritative fields

HMAC-SHA256 with unambiguous length-prefixed encoding is cryptographically sufficient for integrity and authenticity relative to possession of the key. The proposed field set is insufficient.

At minimum, the authenticated envelope must bind:

- schedule ID and schema/canonicalization version;
- cluster/evidence identity;
- action kind;
- window start and end;
- maximum start delay;
- requester and approver immutable principal IDs;
- approval/grant identifier and approval reason digest where authoritative;
- complete baseline digest;
- pilot/program scope where it affects authorization;
- creation/authorization time;
- key ID and algorithm/version domain;
- authorization nonce or unique grant ID.

If `maxStartDelayMinutes` is stored outside the MAC, a row edit can enlarge the permitted execution interval.

The baseline itself must be serialized canonically and re-hashed at use time. Signing a `baselineDigest` does not protect a separately stored baseline object unless the service recomputes that digest from the exact baseline fields before comparison.

Use explicit domain separation, for example a constant equivalent to:

```text
NEXUS_FAILOVER_SCHEDULE_V1
```

Length-prefix bytes, UTF-8 encoding, timestamp format, null handling, enum spelling, and field order must be fixed by the contract.

Use constant-time MAC comparison.

### P0.4 — Schedule HMAC is not an execution authorization by itself

A valid MAC proves that a holder of the schedule-signing key created the envelope. It does not prove:

- that requester and approver were authenticated;
- that they were distinct durable principals;
- that approval remained valid;
- that the row has not been replayed;
- that the schedule has not already executed.

The sealed schedule must reference a durable, single-use, four-eyes authorization grant. Execution needs an atomic transition from unused to consumed. Do not mint a disconnected 15-minute lease after T₀ unless that lease is bound to the schedule ID, attempt ID, action, cluster, window, baseline, and approval grant.

### P0.5 — Revocation/cancellation has a race

Checking cancellation before T₀ is not enough. Cancellation may occur after the check but before command submission.

Cancellation and execution claim/consumption require transactional state-machine rules:

- cancellation succeeds only from cancellable pre-boundary states;
- execution claims only an uncancelled, unconsumed schedule;
- after the mutation boundary, cancellation cannot claim that execution was prevented;
- the final revocation check occurs within the exclusive pre-mutation transaction/gate.

## P1 — Required architectural corrections

### P1.1 — Define a closed, transition-enforced state machine

The enum alone does not define safe transitions. Recommended minimum lifecycle:

```text
SCHEDULED
  → CANCELLED
  → CLAIMED
      → ABORTED_TAMPERED
      → ABORTED_REVOKED
      → ABORTED_WINDOW_EXPIRED
      → ABORTED_PRE_MUTATION
      → ABORTED_DRIFT_DETECTED
      → DISPATCHING
          → COMPLETED
          → FAILED_NO_CHANGE
          → VENDOR_REJECTED
          → OUTCOME_UNKNOWN
```

The exact names may reuse Phase C results, but enforce:

- terminal states cannot return to runnable states;
- only one transition crosses into `DISPATCHING`;
- no pre-mutation abort state is reachable after the boundary;
- any uncertainty about whether submission occurred becomes `OUTCOME_UNKNOWN`;
- generic `FAILED` must not hide whether the mutation boundary was crossed.

`IN_PROGRESS` is too ambiguous unless it distinguishes verification from command dispatch.

### P1.2 — Define the mutation boundary durably

The comment in Phase C says lease consumption occurs “upon crossing mutation boundary,” but the token is consumed before `executor.executeAction()`. That is a reasonable safety fence, but the durable record must distinguish:

- authorization consumed, command definitely not invoked;
- invocation begun, delivery/result unknown;
- vendor explicitly rejected without mutation;
- mutation succeeded and independently verified.

If the process dies between token consumption and invocation, retry must still be prohibited unless the design can prove no submission occurred. Conservative recovery is `OUTCOME_UNKNOWN` or a separately named non-retryable indeterminate dispatch state.

### P1.3 — Harden timing semantics

UTC `Instant` avoids timezone ambiguity but does not solve clock correctness.

Required controls:

- reject `windowEnd <= windowStart`;
- validate bounded positive `maxStartDelay`;
- compute the effective deadline once:

```text
executionDeadline = min(windowEnd, windowStart + maxStartDelay)
```

- require `windowStart <= now <= executionDeadline`;
- recheck the deadline immediately before authorization consumption;
- define equality precisely—normally `now == deadline` is permitted, but command dispatch must still begin before it;
- define permitted clock-health uncertainty;
- abort if trusted time is unavailable or observed wall-clock movement exceeds the allowed tolerance;
- never extend a persisted deadline after restart;
- claim due schedules transactionally after restart rather than relying on an in-memory timer.

A monotonic clock is useful for measuring elapsed time within one process, but persisted safety decisions must use a trusted wall clock. An NTP backward jump must not reopen an expired schedule. Persisting a terminal expiry decision and using non-regressing observed time per attempt prevents reopening.

### P1.4 — Treat every unknown or incomplete T₀ observation as an abort

`hasDrift` is too weak because it risks collapsing `UNKNOWN` into “no drift.” Use a closed result such as:

```text
PASS
DRIFT
BLOCKED
INSUFFICIENT_EVIDENCE
COLLECTION_FAILED
```

Only `PASS` may dispatch. Missing fields, unsupported semantics, partial two-sided collection, stale evidence, or comparison failure must abort pre-mutation.

The T₀ report must prove the required-check manifest is complete. A zero blocking count is insufficient if a required check was omitted or not evaluated.

### P1.5 — Freeze the drift policy, not only selected field equality

Required abort inputs include:

- full Phase A required-check battery;
- both members independently and directly observed;
- membership/pair identity unchanged;
- active and standby identities and roles unchanged;
- no transitional, initializing, split-brain, ambiguous, or role-flapping state;
- peer communication and synchronization affirmatively healthy;
- critical interfaces and required dataplane health affirmative;
- software version/build/take parity according to the frozen vendor contract;
- effective installed policy identity unchanged;
- no policy installation or relevant configuration operation in progress;
- no active quarantine or conflicting operation;
- freshness bounds for every T₀ observation.

Routing, adjacency, session, interface, or policy-installation checks should be mandatory only where existing approved commands and frozen vendor semantics can establish them. If a load-bearing check cannot be established without a new command or unproven field meaning, Phase D must report `UNKNOWN` and remain non-executable until that contract is frozen.

Do not compare raw presentation labels. Use opaque, contract-proven evidence identities.

### P1.6 — Avoid two independent JIT snapshots

Phase D collects a T₀ snapshot and Phase C collects another. This creates inconsistent evidence and an unnecessary race.

Prefer one of:

- Phase C accepts a short-lived, immutable verified execution context and validates freshness inside its lock; or
- Phase C itself invokes the Phase D final gate and uses that same snapshot for its precondition observation.

A snapshot must be tied to the schedule, attempt, collection pass, and capture timestamp. It must expire before mutation if the bounded validation-to-dispatch interval is exceeded.

### P1.7 — Key management must be persistent and independent

The supplied authorization service generates a new random key in its production constructor. That invalidates tokens after restart and is unsuitable for durable schedules.

Schedule-signing keys must come from persistent secret management, not be generated at service construction. Include:

- separate key purpose from lease-token signing;
- key ID in the envelope;
- active signing key plus retained verification keys;
- explicit rotation procedure;
- retirement only after all schedules signed by the old key are terminal or expired;
- audit of key use and rotation;
- fail-closed behavior when a key is missing;
- no raw key material in database rows, logs, UI, or exceptions.

Rotation must never silently re-sign a stored record, because that would conceal tampering. A controlled migration must verify the old MAC before creating a new envelope and preserve the audit chain.

## P2 — Recommended improvements

### P2.1 — Reduce sensitive UI exposure

The schedule table should not display raw approver identities or cluster identifiers. Use authorized masked projections and role-safe audit summaries. Drift reports should expose typed reason codes and masked relationships, not raw snapshots, policy hashes, serials, or topology values.

### P2.2 — Remove the public trigger or make it semantically inert

`POST .../trigger` increases attack and race surface. It must never bypass due-time selection, approval, revocation, durable claiming, or final checks. The simpler design is to omit it. If operationally required, it should merely request evaluation of an already due schedule through the identical scheduler path.

### P2.3 — Use typed drift reasons

Replace `List<String>` with stable reason codes plus sanitized descriptions, for example:

```text
ACTIVE_IDENTITY_CHANGED
STANDBY_NOT_READY
POLICY_IDENTITY_CHANGED
REQUIRED_CHECK_MISSING
EVIDENCE_COLLECTION_FAILED
WINDOW_EXPIRED
```

This prevents UI logic and audit analysis from depending on message text.

### P2.4 — Add adversarial lifecycle tests

Beyond the listed tests, cover:

- two scheduler instances claim the same schedule;
- restart before claim, during verification, after authorization consumption, and during command submission;
- cancellation racing with claim and dispatch;
- window expires while waiting for the fleet lock;
- signature-valid row with altered unsigned `maxStartDelay`;
- baseline object altered while stored digest remains unchanged;
- old-key verification during rotation;
- missing verification key;
- NTP forward and backward jumps;
- incomplete required-check manifest;
- T₀ evidence changes between verification and dispatch;
- quarantine becoming active during scheduling;
- command timeout or connection loss after possible delivery;
- terminal schedule cannot be replayed via poller or manual trigger.

## Direct answers

1. HMAC-SHA256 and length-prefix framing are sufficient primitives, but the proposed authenticated field set and key lifecycle are not sufficient. Durable key management, domain/version binding, full authoritative-field coverage, baseline recomputation, replay prevention, and atomic single-use consumption are required.

2. `Instant` and a seven-day cap are necessary but insufficient. The effective deadline must be enforced again at the final mutation boundary, and restart, clock-health, backward-jump, and distributed-claim semantics must be defined.

3. The proposed baseline comparison is incomplete. It must preserve the full required-check manifest, distinguish unknown from no drift, and include all contract-supported peer, synchronization, transitional-operation, identity, software, and effective-policy safety conditions.

4. The current plan does not yet prove automated-abort-only behavior because revocation, expiry, drift, and execution ownership are not atomic with dispatch. A closed state machine and final in-lock gate are required.

5. The Phase C handoff preserves several invariants only within one running JVM. It does not preserve them across replicas or restarts, and Phase D’s safety checks may become stale while waiting for Phase C concurrency. Integrate the scheduled gate into Phase C’s final pre-mutation critical section and persist execution ownership/quarantine.

6. Final verdict remains **[REJECTED]** until the P0 items are incorporated into a FROZEN Phase D contract. With those corrections, the overall approach is viable and should not require a parallel execution engine.

SESSION CLOSE

- Result: Architecture reviewed; implementation not approved
- Blocking findings: Non-atomic final gate, process-local at-most-once controls, incomplete authenticated envelope, revocation race, and missing durable execution state machine
- Changes made: None
- Validation performed: Design and supplied-code analysis only; no repository, runtime, device, deployment, Git, or production state changed
- Recommended next movement: ARCHITECTURE at high reasoning to freeze the schedule state machine, authenticated envelope, durable claim protocol, and integrated Phase C pre-mutation gate
