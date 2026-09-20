SESSION START

- Role: Enterprise Security Architect / Claude Reviewer (Fable)
- Movement: ARCHITECTURE (security review, single-turn consultation)
- Objective: Review Phase D cryptographic bindings, temporal bounds, drift semantics, unattended blast radius, and the Phase B/C handoff; adjudicate the preceding Codex/Astra review
- Scope: Phase D implementation plan, Phase D scope statement, supplied Phase C `FailoverExecutionService` source, and the Astra review. No repository read, no device access, no state change.
- Repository/device changes: None
- Recommended reasoning level: High — unattended CLASS 2 destructive execution crossing cryptography, durable state, distributed concurrency, and trusted-time boundaries

# Verdict: [REJECTED]

I concur with Astra's rejection and its principal blocker (non-atomic final gate). My review does not overturn any Astra finding. It adds eight findings Astra did not raise, re-classifies three, reframes one as a defect in already-validated phases rather than a Phase D requirement, and disputes one recommended remediation on the grounds that it would invert a Phase C safety invariant.

The overriding reason this cannot ship as `APPROVED WITH RECOMMENDATIONS`: Phase D is the first design in this product that submits a CLASS 2 destructive command **with no human present at the moment of submission**. Every control that was "good enough because an operator was watching a 500 error" becomes load-bearing. The supplied Phase C code contains at least four escape paths that produce no durable result at all — acceptable when a human sees a failed HTTP call, unacceptable at 02:00 with nobody looking.

---

## P0 — Blocking; must be resolved in a FROZEN contract before implementation

### F-P0.0 — Phase D as designed is constitutionally non-compliant: an unledgered automatic CLASS 2 write [NEW]

`AGENTS.md`, Network action taxonomy: *"No automatic (unscheduled-trigger, non-ledgered) network-device write/change operation is permitted at the current maturity."*

The parenthetical carves out scheduled triggers **only in conjunction with ledgering**. Phase D is scheduled-trigger, which satisfies half the condition. It is not ledgered: the supplied Phase C service holds lease tokens, consumed-token IDs, quarantine records, and execution history in `ConcurrentHashMap`, and the Phase D plan adds no durable authorization ledger — Step 3 says "persists schedule" with no transactional, append-only authorization record.

This is not a robustness preference. Until schedule authorization, claim, consumption, and outcome are recorded in a durable append-only ledger, Phase D is a prohibited operation class, not a risky one. Every other P0 below is downstream of this.

**Required:** a durable, append-only authorization-and-execution ledger is a precondition of the Phase D contract, named as such in the freeze document, with the constitutional clause cited.

### F-P0.1 — Final authorization gate outside the mutation boundary [CONCUR-ASTRA P0.1]

Full concurrence. The proposed ordering (verify → window → T₀ → drift → *then* acquire lock → mint lease → execute) is a textbook TOCTOU on the decisive checks. Astra's recommended shape is correct. See F-P0.7 for my dissent on *how* the gate should obtain its evidence.

### F-P0.2 — At-most-once controls are process-local — but this is a Phase B/C contract defect, not a Phase D requirement [CONCUR-ASTRA P0.2, REFRAME]

Astra is right that `Semaphore`, `activeClusterLocks`, `quarantinedClusters`, `executionHistory`, and consumed-token IDs do not survive restart or span replicas. I would go further on classification, because it changes what must be frozen:

Phase C's invariants — at-most-once submission, sticky quarantine, fleet concurrency = 1 — were validated under an **implicit single-JVM, attended** assumption that no contract states. Phase D removes the human and the plan contemplates a polling service. Phase B likewise generates its signing key in its constructor (Astra P1.7): tolerable for a 15-minute attended lease, fatal for a 7-day schedule.

**Required:** Phase C's status must be re-scoped to `REAL_ENV_VALIDATED (single-instance, attended)` in `project/*.json` and `CURRENT_STATE.md`, and Phase B's key lifecycle re-opened, before Phase D can inherit either. Phase D cannot silently promote an attended-scope validation into an unattended one — that is exactly the "collection success ≠ semantic correctness" failure mode the constitution names.

Worst concrete case: the conditions producing `OUTCOME_UNKNOWN` (device unreachable, hung SSH, command timeout) are strongly correlated with the conditions producing a service restart (network partition, host trouble, OOM from hung connections). A crash-loop immediately after an ambiguous failover **loses the quarantine** and lets the next due schedule execute against a cluster in unknown state. Quarantine must be durable and must fail closed on read failure: cannot read the quarantine store → treat as quarantined.

### F-P0.3 — The MAC cannot protect the fields an attacker actually wants to change [NEW — extends ASTRA P0.3/P0.4]

Astra's field-coverage list is correct and I adopt it. But there is a structural point underneath it that no amount of field coverage fixes, and it is the most important cryptographic finding in this review:

**A static envelope can only seal immutable fields. The schedule's lifecycle state is mutable by construction.** `status`, `cancelledAt`, `executedAt`, `attemptCount` cannot be inside the MAC, because they change. Therefore the MAC provides **zero integrity for precisely the fields that gate execution.**

Attack, requiring only database write access (an insider, a compromised service account, a SQL injection, a restored-from-backup row):

```
UPDATE failover_schedule SET status='SCHEDULED', executed_at=NULL
  WHERE schedule_id='...';        -- signature still verifies perfectly
```

`CANCELLED → SCHEDULED` resurrects a revoked destructive action. `COMPLETED → SCHEDULED` replays one that already ran. The scheduler verifies the HMAC, finds it valid, and proceeds. Astra's P0.4 gestures at this ("the row has not been replayed", "has not already executed") but frames it as missing envelope fields; it is not — it is an unfixable property of sealing an immutable envelope over a mutable row.

**Required mitigations, all three:**

1. **Single-use consumption enforced by the storage engine, not by a status comparison.** A `UNIQUE` constraint on `(grant_id)` in an `authorization_consumption` table, inserted in the same transaction that crosses the mutation boundary. Re-execution fails at the constraint, not at an `if`.
2. **Hash-chained state transitions.** Each transition record carries `MAC(prev_transition_digest || new_state || attempt_id || timestamp || key_id)`. State regression becomes cryptographically detectable even by an attacker with full row-write access, because they cannot forge a chain link. Verify the chain at the pre-mutation gate.
3. **Monotonic attempt counter inside the claim transaction**, compare-and-set, so a rolled-back row is detectable as a counter regression.

### F-P0.4 — Vendor and command family are not bound; the sealed schedule can be re-routed to a different destructive command [NEW]

The canonical payload binds `clusterRef` and `actionKind`. It does not bind **vendor**. In Phase C, vendor is resolved at execution time from mutable inventory:

```java
String vendor = snapshot.vendor() != null ? snapshot.vendor().toUpperCase(Locale.ROOT) : "";
FailoverDeviceExecutor executor = resolveExecutor(vendor);
```

`actionKind = CONTROLLED_FAILOVER` maps to `clusterXL_admin down` under a Check Point executor and `request high-availability state suspend` under a PAN-OS executor. An attacker (or a discovery-pass misclassification, which is the likelier cause) who alters the vendor attribute in inventory changes **which CLASS 2 command is submitted** under a schedule whose signature still verifies. The network-device command gate approved specific commands for specific vendors; this design lets the command selection float free of the authorization.

**Required:** bind `vendor` and a stable `commandFamilyId` (the gate-approved command identity, not its literal text) into the authenticated envelope. At the pre-mutation gate, a vendor or command-family mismatch against live inventory is `ABORTED_TAMPERED`, never a silent re-route.

### F-P0.5 — The mutation target is not bound to the authorization [NEW]

Phase C derives the target from the live snapshot: `active.memberId()`. The schedule authorizes "fail over this cluster," and the destructive command lands on whichever member the snapshot currently labels active.

Presentation identity is never an authorization key (constitutional identity law). If role labels in the snapshot are inverted — by a genuine flap, by a parser fault, by a one-sided peer claim, or by manipulation — the destructive command is submitted to the member actually carrying production traffic, with no standby ready to receive it. The drift check (`baselineActiveId == t0ActiveId`) is the only thing standing between the design and that outcome, and per F-P0.1 it currently runs outside the lock.

**Required:** `baselineActiveMemberId` enters the authenticated envelope as the **sole permissible mutation target**. The pre-mutation gate passes that opaque identifier to the executor as a constraint; the executor must not re-derive its target. Any T₀ disagreement between the signed target and the observed active member is a terminal abort, never a target update.

### F-P0.6 — Four-eyes compares display strings, not authenticated principals, and does not survive the 7-day lead time [NEW — extends ASTRA P0.4]

Two distinct defects:

**(a) String comparison is not distinctness of humans.** Supplied Phase C:

```java
if (requesterId.equalsIgnoreCase(approverId)) {
    throw new IllegalArgumentException("... requires 4-eyes dual control (requester != approver)");
}
```

This is defeated by one person holding two accounts (`alice`, `a.smith`, a break-glass ID, a service principal). Four-eyes must compare **immutable directory principal identifiers** (`sub` / objectGUID / equivalent), and must additionally require two independently authenticated sessions with distinct authentication events — not two strings submitted through one form by one browser. Phase D makes this worse than Phase B: in Phase B the approval was consumed within 15 minutes of being given; in Phase D it is consumed up to 7 days later, so there is no live session to corroborate anything.

Secondary note: `equalsIgnoreCase` on a principal identifier is case normalization on an identity value, which the identity law prohibits absent proven semantics. Here it happens to fail *safe* (it catches more collisions), so it is not itself exploitable — but it signals the comparison is operating on display names.

**(b) Entitlement is not re-verified at consumption.** If either principal is disabled, de-entitled, or offboarded on day 3 of a 7-day lead, the schedule still executes on day 7 carrying their authorization. **Required:** re-verify both principals' current entitlement to authorize a CLASS 2 failover inside the pre-mutation gate; either principal failing → `ABORTED_AUTHORIZATION_LAPSED`.

Also unspecified in the plan: **who is the acting operator for an unattended execution?** Phase C requires a non-null `operatorId`. If Phase D passes the requester's ID, the audit record falsely asserts that a human acted at 02:00. **Required:** a distinct non-human principal (`SYSTEM_SCHEDULER`) as actor, with `onBehalfOfGrantId` referencing the four-eyes grant. An audit trail that cannot distinguish a human action from an unattended one is not an audit trail.

### F-P0.7 — Revocation race [CONCUR-ASTRA P0.5, EXPAND]

Concurrence. Two additions:

**Two authorities for one fact.** The plan introduces a separate "revocation ledger" checked at T₀ while the schedule row carries its own `CANCELLED` status. Two mutable sources for "is this cancelled" is a race by construction. **Required:** cancellation is a state transition **on the schedule row itself**, under the same row lock as the execution claim; the ledger is a derived audit projection with no authority.

**Cancel-after-boundary must not lie to the operator.** If cancellation arrives after the boundary is crossed, the API must return a distinct outcome (`CANCELLATION_REJECTED_POST_BOUNDARY`) and the console must render it as such. An operator who clicks Cancel at 02:04, sees "Cancelled," and goes back to bed while a failover is in flight is an operational hazard of the first order. Astra states the invariant; I am raising it from an invariant to a required API return type and a required UI state, because the failure here is human belief, not machine state.

### F-P0.8 — The verification-to-dispatch interval can escape the maintenance window [NEW — sharpens ASTRA P1.3]

Astra correctly requires a deadline recheck before consumption. The concrete mechanism deserves naming, because it is the most likely real-world window violation:

The deadline currently gates when verification **starts**. T₀ verification is a live two-sided collection against two firewalls over SSH/HTTPS. Under a hung TCP connection it is bounded only by the executor's timeout — plausibly 30–120 seconds, and longer if retries exist anywhere in the collector. A schedule can pass the deadline check at `deadline − 2s`, spend 90s in collection, acquire the fleet lock, and submit `clusterXL_admin down` **after `windowEnd`** — a CLASS 2 destructive command executed outside the change-approved window. That is a change-control violation regardless of whether the failover succeeds.

**Required:**

```
executionDeadline = min(windowEnd, windowStart + maxStartDelay)     // computed once, persisted
admission (before collection):  now + VERIFY_BUDGET + DISPATCH_BUDGET  <  executionDeadline
final gate (inside lock, pre-consumption):  now  <  executionDeadline
window interval is half-open: [windowStart, executionDeadline)
```

- Hard, non-retrying timeouts on every T₀ collection call, summing to at most `VERIFY_BUDGET`.
- Strict `<`, not `<=`. The equality case buys nothing on a destructive path and produces an off-by-one argument in every test review. (Minor dissent from Astra P1.3, which permits `now == deadline`.)
- The freeze document must state explicitly that `windowEnd` bounds **command submission**, not convergence or post-verification, and the console must say so. Operators will otherwise read the window as bounding the disruption.

### F-P0.9 — Clock manipulation fails **open**, and the 7-day cap does not help [NEW — extends ASTRA P1.3]

Astra covers NTP jumps. The asymmetry is worth stating plainly because it determines the mitigation:

- A **backward** clock step makes a due schedule look not-yet-due → it waits → eventually aborts on window expiry. **Fails closed.** Acceptable.
- A **forward** clock step makes a future schedule look due → it executes. **Fails open.** A +7-day step executes a fully legitimate, correctly signed schedule a week early, in production hours, with a 7-day-stale baseline. `MAX_LEAD_TIME_DAYS = 7` provides no protection; it defines the maximum size of the error.

The realistic cause is not an attacker. It is VM suspend/resume, container live-migration, hypervisor time drift, or a first NTP sync after a host with a dead RTC boots.

**Required:**

1. **Clock health becomes required pre-flight check #13**, with its own `UNKNOWN`, and a dedicated terminal state `ABORTED_CLOCK_UNTRUSTED`. Untrusted time never dispatches.
2. **Monotonic cross-check.** Persist `(bootId, monotonicAtStart, wallAtStart)`. Within a process lifetime, if `|Δwall − Δmonotonic| > tolerance` (60s is generous), mark time untrusted, refuse all dispatch, alert. Across restarts, `bootId` change forces re-attestation before any dispatch.
3. **Trusted-time attestation** from the host sync daemon (sync state, stratum, last-sync age) as a gate input, not a log line.
4. Terminal expiry decisions are **persisted** and never reopened by a later clock movement.

---

## P1 — Required architectural corrections

### F-P1.1 — Closed, transition-enforced state machine [CONCUR-ASTRA P1.1]
Concurrence in full. Add `ABORTED_CLOCK_UNTRUSTED`, `ABORTED_AUTHORIZATION_LAPSED`, and `ABORTED_COOLDOWN_ACTIVE` (F-P1.6). `IN_PROGRESS` must be split into `VERIFYING` and `DISPATCHING`; the pre/post-boundary distinction is the single most important thing the state machine encodes.

### F-P1.2 — Durable mutation boundary [CONCUR-ASTRA P1.2, EXPAND with code evidence]

Concurrence, and the supplied code already demonstrates the gap concretely:

```java
consumedToken = authzService.consumeLeaseForExecution(clusterRef, tokenId, clientNonce);
Instant boundaryCrossedAt = Instant.now();
FailoverDeviceExecutor executor = resolveExecutor(vendor);   // <-- can throw, AFTER lease burn
```

`resolveExecutor` throws `IllegalArgumentException` for an unregistered vendor, and `vendor` is `""` whenever `snapshot.vendor()` is null — a guaranteed throw. The throw is **not** inside the try/catch that guards `executeAction`. Result: lease consumed, no `FailoverExecutionResult` written to `executionHistory`, no quarantine engaged, raw exception propagates. In Phase C an operator sees a 500. In Phase D, step 3.7 ("Updates schedule status with execution result ID") never runs and the schedule is stranded in `IN_PROGRESS` with a burned authorization — indefinitely, with the fleet semaphore released and the next due schedule free to proceed.

**Required:** hoist executor resolution (and every other fallible non-device operation) **before** lease consumption, and make `executeFailover` a **total function** — every invocation, including every exceptional path, yields a durable terminal typed result. Nothing may escape as an exception to an unattended caller.

### F-P1.3 — `VENDOR_REJECTED` is an unsafe inference from a transport failure [NEW]

```java
if (!cmdResult.successful()) {
    TwoSidedObservation postObs = executor.observePostcondition(...);   // unguarded — can throw
    return recordResult(..., FailoverExecutionState.VENDOR_REJECTED, ...,
        "Vendor device rejected command without mutating state: " + cmdResult.errorReason());
}
```

Two defects:

1. The summary asserts **"without mutating state."** That claim is only provable from a parsed, vendor-attributable rejection response. A timeout, connection reset, or EOF after the bytes were written also yields `successful() == false` — and in those cases the command may well have been delivered and executed. Recording `VENDOR_REJECTED` there is fabricated certainty about a CLASS 2 mutation, which the UNKNOWN / fail-closed law directly prohibits. **Required:** `VENDOR_REJECTED` requires an explicit parsed vendor error; every transport-layer failure is `OUTCOME_UNKNOWN` with quarantine engaged.
2. `observePostcondition` on this branch is **unguarded**, unlike its counterpart on the success branch. A throw here escapes with no record and no quarantine.

### F-P1.4 — `RETURN_TO_SERVICE` reports `SUCCEEDED` without verifying anything [NEW]

```java
} else { // RETURN_TO_SERVICE
    return recordResult(..., FailoverExecutionState.SUCCEEDED, ...,
        "Return to service completed successfully");
}
```

The branch checks only that both post-observations were *obtained*, then declares success. A return-to-service that changed nothing is recorded as `SUCCEEDED`. Under attended Phase C an operator notices; under scheduled Phase D this writes a false success into the audit ledger and, worse, into the baseline for whatever is scheduled next. **Required:** role-transition classification for `RETURN_TO_SERVICE` symmetric with `CONTROLLED_FAILOVER`, including a `FAILED_NO_CHANGE` outcome.

### F-P1.5 — Quarantine engage/acknowledge are inconsistently serialized [NEW]

`acknowledgeQuarantine` is `synchronized`; `engageQuarantine` is a bare `ConcurrentHashMap.put`. An operator acknowledging a *prior* quarantine can clear a record engaged microseconds earlier by an in-flight execution — reachable in Phase D precisely because execution and human acknowledgment are no longer the same workflow. **Required:** acknowledgment is a compare-and-set against the specific `executionId` the approvers reviewed, transactional against the durable quarantine store. Acknowledging "the cluster" is not acknowledging "this ambiguous outcome."

### F-P1.6 — No inter-failover cooldown and no booking-time admission control [NEW]

Nothing prevents booking two schedules for the same cluster in overlapping or adjacent windows. Fleet concurrency = 1 serializes them — which means the second executes *immediately after* the first, failing over a cluster that just failed over and may still be converging, re-synchronizing, or holding a partially installed policy. Identity-based drift detection will not catch this: after a failover completes, active and standby identities have swapped in a way the second schedule's baseline may happily accept.

Compounding it: `fleetConcurrencySemaphore.tryAcquire()` is non-blocking and throws on contention. Three schedules due at 02:00 produce two immediate exceptions, and Phase D's handling (terminal abort vs. retry-next-poll) is unspecified. Retry-next-poll means repeated full T₀ collections — device load plus repeated chances to breach F-P0.8's deadline.

**Required:**
- **Booking-time admission control:** reject any new schedule whose `[windowStart, executionDeadline)` overlaps an existing non-terminal schedule **anywhere in the fleet**, since fleet concurrency is 1. This converts a runtime race into a rejection the operator can see and fix.
- **`MIN_INTER_FAILOVER_INTERVAL` per cluster** as a required pre-flight check → `ABORTED_COOLDOWN_ACTIVE`.
- **Explicit lock-contention semantics** in the state machine, with the wait bounded by `LOCK_WAIT_BUDGET` from F-P0.8.
- Enforce `maxStartDelay >= pollInterval + VERIFY_BUDGET + LOCK_WAIT_BUDGET` at booking. Offering a 5-minute option against a 30-second poller and an unbounded collection is how you train operators to widen windows.

### F-P1.7 — `PASS`/`DRIFT`/`BLOCKED`/`INSUFFICIENT_EVIDENCE`/`COLLECTION_FAILED` [CONCUR-ASTRA P1.4]

Concurrence. `boolean hasDrift` collapses `UNKNOWN` into "no drift" and must not survive the freeze. Pointing at the literal plan code: `baselineActiveId.equals(t0ActiveId)` throws NPE on a null baseline, and the natural "fix" to `Objects.equals` silently returns `true` for `(null, null)` — two absent identities reported as a match. **Required:** a three-valued comparator returning `MATCH` / `MISMATCH` / `NOT_EVALUABLE` for every dimension; `NOT_EVALUABLE` aborts. Only an all-`MATCH` result with a complete required-check manifest may dispatch.

### F-P1.8 — Freeze the drift policy [CONCUR-ASTRA P1.5, EXPAND]

Concurrence with Astra's full list. Two additions:

**Flap-and-return is invisible to identity comparison.** A cluster that failed over and failed back between booking and T₀ presents matching active/standby identities while potentially carrying stale sync state or an incomplete policy install. **Required:** where a vendor exposes a monotonic role-transition counter or last-transition timestamp under an already-approved command, bind it into the baseline and abort if it advanced. Where no such evidence exists under an approved command, report the flap dimension `UNKNOWN` and — per the constitution's rule that an unprovable load-bearing semantic constrains the design rather than the design assuming it — cap baseline age for that cluster far below 7 days rather than freezing a check the product cannot evaluate.

**Classify drift dimensions, or operations will campaign to disable them.** A 7-day-old baseline compared against `policyHash` in an estate with weekly policy pushes will abort constantly. The pressure that creates is the real risk. **Required classification in the freeze:**
- *Safety-critical* (member identity, pair membership, role assignment, sync health, transitional-operation-in-progress) → abort, no exception, ever.
- *Authorization-critical* (policy identity, software version — what the approver actually signed off on) → abort, with **re-book plus fresh four-eyes** as the only remedy. Never an in-flight "accept drift" control.
- *Informational* → recorded in the drift report, non-gating.

### F-P1.9 — Do not pass Phase D's snapshot into Phase C [DISPUTE-ASTRA P1.6]

Astra is right that two independent JIT snapshots are wrong, and offers two remedies. I dispute the first.

Option one — "Phase C accepts a short-lived, immutable verified execution context" — **inverts a Phase C safety invariant.** Phase C's JIT re-check exists specifically so that *no caller* can supply evidence to the mutation boundary. Accepting a caller-provided snapshot, however short-lived and however freshness-validated, creates a trusted-input path into a CLASS 2 command submission. Freshness validation checks *when* evidence was collected, not *who* collected it or whether it was truthful. Once that parameter exists, it is one refactor away from being reachable from the REST layer, and the invariant is gone.

**Required shape — Astra's option two, exclusively:** Phase C remains the sole owner of the authoritative pre-mutation snapshot. Phase D registers a **server-owned policy callback** that Phase C invokes inside its own lock, with its own snapshot, immediately before lease consumption:

```
Phase C lock
  └─ collect the one authoritative two-sided snapshot
  └─ Phase C preconditions
  └─ invoke scheduledGate.evaluate(scheduleId, attemptId, snapshot)   // Phase D policy, Phase C evidence
        ├─ verify MAC + envelope field set + key id
        ├─ verify transition-chain integrity, claim, non-revocation, non-consumption
        ├─ verify clock health, now < executionDeadline
        ├─ verify entitlement of both principals
        ├─ verify vendor/commandFamily/signed target
        └─ evaluate frozen drift policy against the signed baseline
  └─ atomically consume the grant (UNIQUE constraint) + append ledger entry
  └─ submit exactly one command against the SIGNED target
```

One snapshot. One lock. No trusted input crosses the boundary. Any Phase D snapshot collected earlier exists only for console display and must be labelled non-authoritative in both the API and the UI.

### F-P1.10 — Key management [CONCUR-ASTRA P1.7, EXPAND]

Concurrence. Three additions:

- **Do not sign with "the server master key."** The plan says so literally, and Phase B also uses HMAC-SHA256. Derive purpose-separated subkeys: `HKDF(master, info="NEXUS_FAILOVER_SCHEDULE_V1")` and `HKDF(master, info="NEXUS_FAILOVER_LEASE_V1")`. Reusing one key across two message spaces is an unnecessary cross-protocol risk even where the encodings happen not to collide today.
- `keyId` and `algVersion` go **inside** the authenticated payload, not beside it.
- Constant-time comparison (`MessageDigest.isEqual`), never `String.equals` on a hex MAC.

### F-P1.11 — Masked-identity and typed-reason requirements are constitutional, not advisory [RECLASSIFY-ASTRA P2.1/P2.3 → P1]

Astra places these at P2. Under this repository's **Mandatory AIView inspection & post-parsing masking law**, they are mandatory:

- The Scheduled Windows table specifies columns for raw `clusterRef` and "Approvers." Console rendering must use `TopologyNamePseudonymizer` output (`CLS-ROMEO-01`, `FW-TANGO-04`) and role-safe approver projections. This is a compliance requirement, not a hardening suggestion.
- `List<String> driftReasons` is free text constructed from evidence fields — the exact mechanism by which serials, hostnames, and management addresses leak into logs, UI, exports, and the repository privacy gate. **Required:** stable typed reason codes (`ACTIVE_IDENTITY_CHANGED`, `POLICY_IDENTITY_CHANGED`, `REQUIRED_CHECK_MISSING`, `EVIDENCE_COLLECTION_FAILED`, `SYNC_DEGRADED`, `WINDOW_EXPIRED`, `CLOCK_UNTRUSTED`, `COOLDOWN_ACTIVE`) plus a sanitized description built only from an allowlisted vocabulary. The same applies to `FailoverExecutionResult.summary`, which currently interpolates `ex.getMessage()` and `active.memberId()` directly.

### F-P1.12 — No alerting on unattended terminal states [NEW]

The plan has no notification path whatsoever. An unattended `OUTCOME_UNKNOWN` at 02:00 that nobody sees until 09:00 is seven hours during which a cluster is in an unknown state and operations believes a maintenance action succeeded. **Required:** out-of-band alerting on every terminal state, with `OUTCOME_UNKNOWN`, `ABORTED_CLOCK_UNTRUSTED`, `ABORTED_TAMPERED`, and `ABORTED_DRIFT_DETECTED` at the highest severity, and a heartbeat so a *silent* scheduler is itself detectable. Alert payloads carry masked identities and typed codes only (F-P1.11). Unattended execution without alerting is not unattended execution; it is unobserved execution.

### F-P1.13 — No global kill switch, and the pilot fence may be stale [NEW]

`FailoverPilotAllowlist` being server-owned is correct. Two gaps under unattended operation:

- If the allowlist is loaded from configuration at startup, **de-scoping a cluster does not take effect until restart**. For an unattended destructive operation, removing a cluster from scope must take effect at the next gate evaluation.
- There is no single control that stops **all** scheduled execution. Cancelling schedules one at a time races the poller. **Required:** a durable `SCHEDULED_FAILOVER_ENABLED` kill switch read inside the pre-mutation gate, defaulting closed if unreadable, plus per-cluster and fleet-wide caps (`MAX_SCHEDULED_EXECUTIONS_PER_24H`) enforced at booking and re-checked at the gate. Concurrency = 1 bounds *simultaneity*; nothing currently bounds *volume*.

### F-P1.14 — Scheduling is presented as a lesser privilege than executing [NEW]

This is a human-factors finding and I rate it P1 because it degrades every cryptographic control above.

"Schedule Maintenance Window" reads, to an approver, as less consequential than "Execute Failover Now." It is not — it is strictly more consequential, because the approval is consumed up to 7 days later with nobody watching. If the authorization ceremony is lighter, or merely *feels* lighter, approvers will approve more readily and the four-eyes control degrades in practice while remaining perfect on paper.

**Required:** identical entitlement requirements, identical disclosure content, and explicit console language at the approval step stating that this action **will execute unattended without further human confirmation**, naming the window and the abort conditions.

### F-P1.15 — What the approver saw must be what the envelope sealed [NEW]

The plan shows a "baseline preview card" in the schedule dialog and separately computes `baselineDigest` for the MAC. If those are produced by different code paths, the approver approved a rendered view while the system sealed a different object — a four-eyes bypass that requires no attacker at all, just a divergence between two serializers.

**Required:** the approval binds the digest of the **exact disclosure payload rendered to the approver**. Phase B already produces a deterministic dry-run plan disclosure; reuse it. `schedule.baselineDigest MUST == phaseB.dryRunDisclosureDigest`, and the approval record stores both. Per Astra P0.3, the gate recomputes that digest from the stored baseline fields at T₀ rather than trusting the stored digest.

---

## P2 — Recommended

- **F-P2.1 — Keep the trigger endpoint, make it inert [PARTIAL DISPUTE-ASTRA P2.2].** Astra prefers removal. Removing it entirely leaves no way to exercise the scheduled path in a lab without waiting for a real window — which pushes teams toward manipulating the system clock in test environments, a strictly worse outcome given F-P0.9. Keep `POST .../trigger` as a pure "evaluate now" hint that routes through the identical claim path, cannot widen the window, cannot bypass due-time selection, and is authenticated, rate-limited, and audited. Astra's constraints, opposite conclusion on deletion.
- **F-P2.2 — Advisory pre-window drift probe.** A read-only, explicitly non-authoritative drift evaluation at `T₀ − 1h` that notifies the requester of likely abort conditions. Non-mutating, cheap, and it lets operators re-book before the window instead of discovering the abort afterwards. Must never write schedule state and must never satisfy any gate.
- **F-P2.3 — Remove `activeClusterLocks`.** With a fleet-wide semaphore of 1, it can never be contended in a single JVM. It reads as defense-in-depth in review while providing nothing, and it will be mistaken for the cross-replica lock that F-P0.2 actually requires. Replace it with the durable per-entity claim or delete it.
- **F-P2.4 — `reason.trim().length() < 8` is theatre.** Either drop it or require structured justification (change ticket reference validated against a known format).
- **F-P2.5 — Canonical framing.** For the record, length-prefixed framing is a sound primitive here and I do not find a practical collision in the proposed layout. But the contract must pin: length counted in **UTF-8 bytes** (not `String.length()`, which returns UTF-16 code units), encoding UTF-8, timestamp format `Instant` ISO-8601 with fixed precision, explicit null sentinel distinct from empty string, enum spelling frozen, field order frozen, and a domain-separation constant as the first authenticated field. The framing is not the weakness; the unspecified encoding contract around it would become one.

---

## Evaluation of the Codex (Astra) review

| Astra finding | Position |
|---|---|
| P0.1 final gate outside mutation boundary | **Concur.** Correctly identified as the principal blocker. |
| P0.2 process-local at-most-once controls | **Concur, reframed** (F-P0.2): this is a Phase B/C contract defect that Phase D exposes. Phase C must be re-scoped to attended/single-instance in project state before Phase D inherits it. |
| P0.3 incomplete authenticated envelope | **Concur, expanded** (F-P0.3/4/5): add vendor + command family, signed mutation target, and the structural point that mutable lifecycle state cannot be sealed by a static envelope at all. |
| P0.4 MAC ≠ execution authorization | **Concur, strongly.** A MAC authenticates the *record*, not the *act*. Add entitlement re-verification at consumption and principal-ID-based four-eyes (F-P0.6). |
| P0.5 revocation race | **Concur, expanded** (F-P0.7): single authority for cancellation; typed post-boundary rejection surfaced in the UI. |
| P1.1 closed state machine | **Concur.** Three states added. |
| P1.2 durable mutation boundary | **Concur, with code evidence** (F-P1.2): `resolveExecutor` throwing after lease burn is a live instance of exactly this gap. |
| P1.3 timing semantics | **Concur, sharpened** (F-P0.8/F-P0.9): verification-budget admission control, and the forward-clock-step fail-open asymmetry. Minor dissent on permitting `now == deadline`. |
| P1.4 closed drift result type | **Concur** (F-P1.7). |
| P1.5 freeze the drift policy | **Concur, expanded** (F-P1.8): flap-and-return detection, and drift-dimension classification to prevent operational pressure to disable checks. |
| P1.6 avoid two JIT snapshots | **Concur on the problem; dispute option one** (F-P1.9). Passing a verified context into Phase C creates a trusted-input path into the mutation boundary. Option two only. |
| P1.7 key management | **Concur, expanded** (F-P1.10): HKDF purpose separation from the Phase B lease key. |
| P2.1 sensitive UI exposure | **Reclassify → P1** (F-P1.11). Mandatory under the repository's AIView masking law. |
| P2.2 remove the trigger endpoint | **Partial dispute** (F-P2.1). Keep it inert rather than delete it; deletion pushes lab testing toward clock manipulation. |
| P2.3 typed drift reasons | **Reclassify → P1** (F-P1.11). Free-text reasons are a privacy-gate leak vector, not a code-quality preference. |
| P2.4 adversarial lifecycle tests | **Concur.** Additions below. |

Astra did not raise: the constitutional ledger requirement (F-P0.0), the mutable-state replay that field coverage cannot fix (F-P0.3), vendor/command-family binding (F-P0.4), signed mutation target (F-P0.5), principal-ID four-eyes and entitlement lapse (F-P0.6), verification-budget window escape and the forward-clock asymmetry (F-P0.8/9), `VENDOR_REJECTED` inferred from transport failure (F-P1.3), `RETURN_TO_SERVICE` unverified success (F-P1.4), quarantine engage/ack race (F-P1.5), inter-failover cooldown and booking-time admission control (F-P1.6), alerting (F-P1.12), kill switch and volume caps (F-P1.13), and the two human-factors findings (F-P1.14/15).

---

## Direct answers to the review deliverables

**1. Cryptographic binding and revocation.** HMAC-SHA256 over length-prefixed canonical framing is a sound primitive and I find no practical collision in the proposed layout — provided the encoding contract in F-P2.5 is pinned. It does not deliver what the design needs. It cannot prevent replay, because the fields that gate execution (`status`, `executedAt`) are mutable and therefore outside the envelope (F-P0.3). It cannot prevent privilege escalation, because it does not bind vendor, command family, or mutation target (F-P0.4/5) and does not prove the four-eyes principals were distinct authenticated humans who remain entitled (F-P0.6). The revocation check is **not** race-free: it reads a second mutable authority outside the execution lock (F-P0.7). Verdict: primitive sufficient, protocol insufficient.

**2. Temporal enforcement.** Not watertight. The deadline gates the start of verification rather than the submission of the command, permitting a window escape under ordinary collection latency (F-P0.8). Clock handling fails **open** on forward steps, and `MAX_LEAD_TIME_DAYS = 7` bounds the size of that error rather than preventing it (F-P0.9). The system does fail closed on a late wake-up in the narrow case the plan contemplates — but the `min(windowEnd, windowStart + maxStartDelay)` deadline must be computed once, persisted, and re-evaluated inside the lock, and `maxStartDelay` must be inside the MAC (Astra P0.3) or a row edit enlarges the permitted interval.

**3. Drift coverage and abort-only semantics.** Coverage is incomplete. Missing: complete required-check-manifest verification, `UNKNOWN`/`NOT_EVALUABLE` as distinct from "no drift" (F-P1.7), flap-and-return (F-P1.8), transitional-operation-in-progress, inter-failover cooldown (F-P1.6), and clock health (F-P0.9). Abort-only semantics are correctly intended and I endorse the intent — but they are not yet *enforced*, because the aborts are evaluated outside the lock that guards the mutation. An abort decision that can be invalidated before the command is submitted is not an abort.

**4. Unattended blast radius.** Materially larger than Phase C's and not yet bounded. Concurrency = 1 bounds simultaneity, not volume (F-P1.13). Sticky quarantine is in-memory and is lost by exactly the restart that ambiguous outcomes tend to cause (F-P0.2). The pilot fence may be stale against a de-scoping edit, and there is no kill switch. `executeFailover` has at least four paths that return no durable result to an unattended caller (F-P1.2/F-P1.3). There is no alerting at all (F-P1.12). With those closed — durable ledger and quarantine, kill switch, volume caps, cooldown, total execution contract, alerting — the pilot-scoped blast radius becomes defensible.

**5. Astra's findings.** Table above. Summary: concur with all five P0s and all seven P1s; reframe P0.2 as a Phase B/C defect; dispute Astra P1.6's first remediation option as inverting a Phase C invariant; raise P2.1 and P2.3 to P1 on constitutional grounds; partially dispute P2.2.

**6. Verdict.** **[REJECTED].** Unattended CLASS 2 destructive execution is the highest-consequence capability in this product. Not `APPROVED WITH RECOMMENDATIONS` — F-P0.0 makes the current design a prohibited operation class rather than a risky one, and F-P0.3/4/5 are protocol defects that no amount of implementation care compensates for. The architectural approach is nonetheless sound and does **not** require a parallel execution engine; with the P0 set incorporated into a frozen contract, Phase D is viable as a policy gate inside the existing Phase C boundary.

---

## Required contents of the Phase D freeze document

1. **Durable schedule and authorization ledger schema** — append-only, hash-chained state transitions, `UNIQUE(grant_id)` consumption, monotonic attempt counter. (F-P0.0, F-P0.3)
2. **Closed state machine** with enforced transitions and an unambiguous pre/post-boundary split. (F-P1.1)
3. **Authenticated envelope specification** — full field list including vendor, command family, signed mutation target, `maxStartDelay`, `keyId`, `algVersion`, grant ID, nonce, domain-separation constant; byte-level encoding contract; constant-time comparison. (F-P0.4/5, F-P1.10, F-P2.5)
4. **Key lifecycle** — HKDF purpose separation, persistent secret management, rotation with verification-key retention, fail-closed on missing key, controlled re-signing that verifies the old MAC first. (F-P1.10)
5. **Integrated pre-mutation gate protocol** — the single-snapshot, in-lock sequence in F-P1.9, explicitly stated as an extension of the Phase C boundary and not a second execution path.
6. **Temporal contract** — persisted `executionDeadline`, half-open interval, verification/dispatch/lock-wait budgets, hard collection timeouts, clock-health as required check #13, monotonic cross-check, `ABORTED_CLOCK_UNTRUSTED`. (F-P0.8/9)
7. **Frozen drift policy** — the complete required-check manifest, the three-tier dimension classification, three-valued comparison semantics, cooldown, and an explicit `UNKNOWN` register for any dimension not establishable under an already-approved command. (F-P1.7/8, F-P1.6)
8. **Four-eyes contract** — immutable principal IDs, two independent authentication events, entitlement re-verification at consumption, disclosure-digest binding, `SYSTEM_SCHEDULER` actor with `onBehalfOfGrantId`. (F-P0.6, F-P1.14/15)
9. **Containment controls** — durable quarantine with fail-closed reads and CAS acknowledgment, durable pilot fence, global kill switch, volume caps, booking-time overlap admission control. (F-P0.2, F-P1.5/6/13)
10. **Observability contract** — alerting on every terminal state, scheduler heartbeat, masked identities and typed reason codes throughout API, UI, logs, and alerts. (F-P1.11/12)
11. **Phase B/C re-scoping record** — Phase C status corrected to attended/single-instance; Phase B key lifecycle re-opened; both recorded in `project/*.json` via `scripts/project_queue.py` and reflected in `CURRENT_STATE.md`. (F-P0.2)

**Additional adversarial tests beyond Astra's list:** valid MAC with `CANCELLED → SCHEDULED` row flip; valid MAC with `COMPLETED → SCHEDULED` replay; transition-chain link deleted or reordered; vendor attribute flipped in inventory between booking and T₀; active/standby role labels inverted in the snapshot; forward clock step of 1h / 24h / 7d; VM suspend/resume across the window; `snapshot.vendor()` null (executor resolution after lease burn); `observePostcondition` throwing on the `VENDOR_REJECTED` branch; command timeout classified as vendor rejection; `RETURN_TO_SERVICE` producing no role change; quarantine acknowledgment racing engagement; approver disabled in the directory on day 3 of a 7-day lead; two schedules for the same cluster 10 minutes apart; three schedules due simultaneously against a semaphore of 1; kill switch engaged mid-verification; alert channel unavailable at terminal state; approval-disclosure digest diverging from the sealed baseline digest.

SESSION CLOSE

- Result: Phase D security architecture reviewed; **[REJECTED]**. Implementation not authorized.
- Blocking findings: Unledgered automatic CLASS 2 write (constitutional); non-atomic final gate; process-local at-most-once and quarantine controls inherited from an attended-scope validation; static envelope cannot protect mutable lifecycle state; vendor/command-family and mutation target unbound; four-eyes over display strings with no entitlement re-check; revocation race; verification-to-dispatch window escape; forward-clock fail-open.
- Concurrence: All five Astra P0 items and all seven P1 items upheld. One remediation option disputed (Astra P1.6 option one), one finding reframed (P0.2), two reclassified upward (P2.1, P2.3), one partially disputed (P2.2). Fifteen findings added.
- Changes made: None. No file written, no repository, runtime, device, deployment, Git, or production state touched.
- Recommended next movement: ARCHITECTURE at high reasoning to author the Phase D freeze document against the eleven-item checklist above, together with the Phase B/C re-scoping record. No IMPLEMENTATION movement until that contract reaches `FROZEN`.
