# Final Comprehensive Security Re-Review — neXus Failover Engine, Phases A–D

**Reviewer:** Enterprise Security Architect / Claude (Fable)
**Scope:** Static review of the 15 supplied artifacts plus the preceding Codex (Astra) re-review. No repository inspection, no execution, no tests, no device or host access, no project-state change. Findings below are derived exclusively from the code as supplied; where the supplied evidence cannot settle a question I say so rather than infer.

**Final Verdict: [REJECTED]**

I concur with Astra's rejection and with the substance of its P0 set. This review confirms those findings from independent reading, escalates two of them, and adds eleven defects Astra did not name — several of which are more severe than the ones already listed, including one that classifies cluster **split-brain as `SUCCEEDED`**.

---

## 1. P0/P1 Remediation Assessment

| ID | Claimed remediation | Assessment | Basis |
|---|---|---|---|
| CF-P0.1 | `actionKind` bound into envelope | **Resolved** | `FailoverScheduleEnvelope.toCanonicalBytes()` emits `actionKind` inside the length-prefixed framing; `keyId`/`algVersion` are bound too. |
| CF-P0.2 | Deterministic baseline digest | **Not resolved (P0)** | Digest is deterministic but never recomputed. See FR-P0.1. |
| CF-P0.3 / P0.10 | Dynamic deadline invariant | **Resolved** | Constructor equality check plus two in-lock re-checks. Credit where Astra was silent: because `mapRow` builds the record through that constructor, a DB-tampered `execution_deadline` column now throws on load rather than executing. |
| CF-P0.5 | Durable managed key | **Partial (P0 remains)** | Key loss → silent regeneration → false `ABORTED_TAMPERED`. Additionally FR-P0.6, FR-P1.3. |
| CF-P0.7 | Durable Flyway persistence | **Partial (P0 remains)** | Tables exist; no transaction spans any multi-statement transition; `CREATE TABLE IF NOT EXISTS` weakens the constraint guarantee (FR-P1.6). |
| CF-P0.8 | Monotonic clock health | **Not resolved** | The stated change ("Removed `Math.abs()`") is **not present in the code**. `Math.abs(elapsedWallMillis - elapsedMonotonicMillis)` is still there. A ~30 s backward step that leaves the clock later than process start passes. |
| CF-P0.11–13 | Single-snapshot T₀, fail-closed drift | **Partial** | Dispatch-side gate is correct but tautological at its only call site; booking side uses two separate evidence passes (FR-P0.4); missing baseline fields still evaluate to `MATCH` (FR-P0.5). |
| CF-P0.14 | Canonical dual-control principals | **Not resolved as claimed** | No Unicode normalization, no confusable detection. `Character.isISOControl` does not cover U+200B/U+200E/U+00AD, so `"alice\u200B"` ≠ `"alice"` defeats the gate. More fundamentally, FR-P0.7. |
| CF-P0.16 | Typed delivery certainty | **Partial (P0 remains)** | Only the failure branch inspects certainty, the test is a deny-list, and a null certainty falls through to the permissive path (FR-P0.3). |
| CF-P0.18 | Affirmative `RETURN_TO_SERVICE` | **Not resolved — escalated to critical** | Only `postA` is evaluated, and the allowlist admits `ACTIVE`, so **both members `ACTIVE` is classified `SUCCEEDED`** (FR-P0.2). |
| CF-P0.20 | Startup crash recovery | **Partial (two P0s)** | Non-atomic reconcile→quarantine (Astra, confirmed); no ownership fencing against a live replica (FR-P0.8); covers only the scheduled path — the manual Phase C path has no crash recovery at all (FR-P0.9). |
| CF-P0.21 | AIView masking | **Partial** | Summaries improved; raw member IDs still enter `FailoverExecutionResult` and durable quarantine JSON; `ex.getMessage()` still reaches a channel named `sanitizedMessages()` (FR-P1.4). |
| CF-P1.2 | Key identity | **Not resolved** | No `key_id` / `alg_version` columns in V31; dispatch hardcodes `DEFAULT_KEY_ID`. The bound `keyId` field buys zero agility. |
| CF-P1.4 | State-machine durability | **Partial** | CAS is on `status`; the `version` column is incremented and never read — the "optimistic lock" is a dead column. `DISPATCHING` is queried on startup but never written. |
| CF-P1.14 | Admission control | **Logic fixed, concurrency broken in-JVM** | Rolling-window and directional cooldown are now correct. But the snapshot is read *outside* the `synchronized` block — the race exists in a **single instance**, not only across replicas (FR-P0.10). |

**Traceability defect.** `FailoverExecutionService` labels the return-to-service allowlist `(Claude CF-P0.21)` twice; CF-P0.21 is the masking finding. When remediation labels in code do not match the finding register, a reviewer cannot use the labels to audit closure. Fix the labels as part of the closure set.

---

## 2. Cryptographic and State Integrity

**Sound:** `actionKind`/`keyId`/`algVersion` HMAC binding; byte-length-prefixed framing (unambiguous, no collision); `MessageDigest.isEqual` on raw bytes; malformed-hex rejection; unknown-`keyId` fail-closed; digest computed over fields excluding `assessmentDigest` (no self-reference).

### FR-P0.1 — Baseline substitution (confirms Astra)
`dispatchScheduledExecution` rebuilds the envelope from `record.baselineSummary().assessmentDigest()` and never calls `computeCanonicalDigest()`. Any actor with `UPDATE` on `failover_schedules` can rewrite `baseline_json` — active/standby identity, version, policy hash, transition counter — while leaving the embedded digest string intact, and the HMAC still verifies. The drift engine then compares live evidence against attacker-chosen "authorized" state.

Worse: the `baseline_digest` **column is write-only**. Nothing reads it. Three copies of the same value exist (column, JSON field, envelope input) with zero cross-checks.

*Required:* after deserialization and before any use, recompute the digest and constant-time compare it against both the JSON-embedded value and the `baseline_digest` column. Also verify `baseline.clusterRef()` equals the live cluster — the drift engine currently never checks it.

### FR-P0.2 — Canonical form is not normalized to storage precision (extends Astra's ledger point into the signature path)
`windowStart`/`windowEnd` are signed as `Instant.toString()` at seal time, then persisted as `TIMESTAMPTZ` (microsecond) and reloaded via `rs.getTimestamp(...).toInstant()`. Any sub-microsecond component present at seal time is lost, so the envelope rebuilt at dispatch differs from the one signed → `verifyEnvelope` fails → **`ABORTED_TAMPERED`**.

This is reachable from external input: a client supplying `...T02:00:00.123456789Z` books a schedule that is guaranteed to abort as tampered. The operational consequence is the one CF-P0.5 was raised to eliminate — a tamper alert that does not mean tampering, training operators to dismiss the signal.

The same defect breaks `verifyChainIntegrity()` in durable mode (Astra's finding; I confirm it and note that the in-memory path masks it, so any test not backed by PostgreSQL will pass).

*Required:* define a canonical temporal precision (microseconds or seconds), `truncatedTo(...)` **before** signing and before inserting, and reject inputs finer than that precision at the API boundary. Also replace `java.sql.Timestamp` binding with `OffsetDateTime`/`setObject` — `Timestamp` round-trips through the JVM default zone and can shift an instant by an hour across a DST boundary, which on a safety-critical temporal fence is material.

### FR-P0.6 — Key loss becomes false tamper (confirms Astra), plus two additions
`resolveFromLocalStorageFile()` treats "file absent" as first deployment. Beyond Astra's point:

- **Permissions.** `Files.writeString(..., CREATE_NEW, WRITE)` applies the default umask. The HMAC master secret authorizing CLASS 2 mutations is written world-readable on the host. Use `PosixFilePermissions.asFileAttribute(EnumSet.of(OWNER_READ, OWNER_WRITE))` on creation, and verify permissions on read.
- **Location.** The default `.nexus_failover_master.key` is a *relative* path resolving to the process CWD. In a container without an explicitly mounted persistent volume this is ephemeral — every pod restart regenerates the key and converts **every** outstanding schedule to `ABORTED_TAMPERED`. The remediation's durability claim holds only under a deployment invariant the code neither states nor checks.
- **Multi-replica.** Each replica gets its own file unless a shared volume is mounted. Replica A signs; replica B verifies against a different key; result is `ABORTED_TAMPERED`. The one deployment topology the DB schema is built for is the one the key manager silently breaks.

*Required:* separate explicit key *initialization* (an operator/bootstrap action) from runtime *resolution*; runtime must return `Optional.empty()` on a missing key whenever any schedule row exists, never generate. Persist `key_id` and `alg_version` per schedule and read them when rebuilding the envelope.

### FR-P1.1 — The ledger is unkeyed and structurally not append-only
Three independent gaps: (a) plain SHA-256 chain — a DB writer can rewrite the whole chain consistently, so it detects corruption, not tampering, and must not be described as tamper-proof; (b) delimiter framing (`prevHash + "|" + index + ...`) while `details` is **operator-supplied free text** (`cancelSchedule`'s `reason`) — the adversary controls a field that can inject the delimiter and create ambiguous attribution; (c) `UNIQUE(entry_index)` prevents duplicate indexes but nothing revokes `UPDATE`/`DELETE`.

*Required:* HMAC the chain under the managed key; length-prefix the framing exactly as the envelope does; add a `BEFORE UPDATE OR DELETE` trigger that raises, and `REVOKE UPDATE, DELETE ON failover_schedule_ledger` from the application role. Note also that `verifyChainIntegrity()` is never invoked anywhere in the supplied code — an integrity mechanism with no runtime verifier and no alert path is decorative.

### FR-P1.2 — Symmetric signing cannot bind the approvers
An HMAC that the executing service can mint provides integrity at rest, not non-repudiation of a four-eyes authorization. For a CLASS 2 mutation grant, the signature should be produced under a key the execution path cannot use to sign — asymmetric signing, or at minimum a separate signing service boundary.

### FR-P1.3 — CF-P0.5 was fixed in one of two key paths
`FailoverAuthorizationService`'s production constructor still does `new SecureRandom().nextBytes(secretKey)` per process for `FourEyesValidationRule`. The lease-token signing key remains ephemeral, and `activeLeaseTokens` / `consumedTokenIds` remain in-memory `ConcurrentHashMap`s. The single-use lease invariant therefore has **no durable enforcement** and no cross-replica enforcement. It happens to fail closed (a token unknown to a replica is rejected), but the engine's headline "single-use" guarantee holds for the scheduled path only, via `UNIQUE(grant_id)`.

---

## 3. Persistence, Storage and Crash Recovery

**Sound:** `UNIQUE(grant_id)` as a real storage constraint with `DuplicateKeyException` translated to the invariant violation; `failover_quarantine` acknowledgment as a genuine CAS (`WHERE cluster_ref = ? AND execution_id = ? AND active = true`); `IDENTITY` over `SERIAL`; `TIMESTAMPTZ`/`JSONB` choices.

### FR-P0.8 — Startup reconciliation has no ownership fencing
`@EventListener(ApplicationReadyEvent.class)` runs on **every** replica start and selects **every** `CLAIMED_VERIFYING` row — including rows a live replica is actively executing. During a rolling deploy this is the normal case, not an edge case. The new replica flips the in-flight schedule to `OUTCOME_UNKNOWN` (the CAS succeeds — the status matches), quarantines the cluster mid-execution, and the working replica's final `updateScheduleCas(CLAIMED_VERIFYING → finished)` then throws `IllegalStateException` **after a real device mutation**, with no handler. The outcome of that mutation is lost.

*Required:* an owner instance ID + heartbeat (or a PostgreSQL advisory lock per schedule), so reconciliation only reclaims rows whose owner is demonstrably dead.

### FR-P0.9 — Crash recovery covers only the scheduled path
Reconciliation scans `failover_schedules`. The manual Phase C path (`executeFailover`) creates no schedule row, and `executionHistory` is an in-memory `ConcurrentHashMap`. A crash between the mutation boundary and `recordResult` therefore leaves **no durable trace and no quarantine** for a command that may have reached the device. Remediation claim #10 is scoped narrower than stated.

Related: after any restart, `getExecution(executionId)` returns empty while `getQuarantine(clusterRef)` still returns a row keyed to that execution ID — the operator performing the CAS acknowledgment cannot retrieve the incident they are required to have reviewed.

### FR-P0.11 — Reconcile→quarantine ordering (confirms Astra)
The fix is cheaper than a transaction: `engageQuarantine` is idempotent (`ON CONFLICT DO UPDATE`), `updateScheduleCas` is the completion marker. **Quarantine first, then flip the status.** A crash between them then simply re-reconciles on the next start.

### FR-P0.12 — The quarantine CAS is bypassable by a sibling method on the same class
`FailoverExecutionService` exposes both `acknowledgeQuarantine(clusterRef, executionId, requesterId, approverId, reason)` and a four-argument overload that looks up whatever quarantine is currently active and acknowledges *that*. The four-argument form reintroduces precisely the race the CAS exists to prevent: incident B overwrites incident A between review and acknowledgment, and the operator clears B believing they cleared A.

This compounds with the schema: `failover_quarantine` is keyed on `cluster_ref` alone and `engageQuarantine` does `ON CONFLICT (cluster_ref) DO UPDATE`, **destroying** the prior incident's execution ID, reason, member set and timestamp — including an incident that was still unacknowledged. Startup reconciliation calls `engageQuarantine` directly and will overwrite a live incident this way. Safety stickiness survives; the forensic record does not.

*Required:* delete the four-argument overload; make quarantine an append-only incident table keyed `(cluster_ref, execution_id)` with a derived "any active incident" predicate, so a cluster is released only when **every** open incident has been individually acknowledged.

### FR-P1.5 — No transaction spans any transition
No `@Transactional` appears anywhere in the supplied service layer; every `JdbcTemplate` call auto-commits. Schedule insert + genesis ledger entry, claim + ledger entry, grant consumption + dispatch state, terminal update + ledger entry are all independently committable. Separately, `recordTransitionDurable` computes the chain tip with read-then-insert under a JVM monitor — two replicas compute the same `entry_index`, one receives an **uncaught** `DuplicateKeyException` which, at the terminal append, propagates after a successful mutation and loses the audit entry.

### FR-P1.6 — `CREATE TABLE IF NOT EXISTS` in a security migration
If a divergent table already exists (an earlier hand-run script, a partially applied environment), V31 silently no-ops and is checksummed as applied — the engine can run against a `failover_grant_consumption` without its uniqueness constraint while every report claims storage-enforced single use. Security-critical DDL must be unconditional. Add `CHECK` constraints too (`window_end > window_start`, `execution_deadline <= window_end`, a status domain) and foreign keys between schedules, grant consumption and ledger entries: the threat model here explicitly includes a DB-write adversary, so the storage layer is exactly where invariants belong.

### FR-P1.7 — Constructor-injection ambiguity on safety-critical beans
`FailoverExecutionService` and `FailoverAuthorizationService` each declare two public constructors with no `@Autowired` marker. `FailoverExecutionService`'s three-argument constructor instantiates `new DurableQuarantineStore()` — the **in-memory, non-durable** fallback. Either Spring fails to start, or quarantine durability is silently lost. `FailoverScheduleLedger` and `DurableQuarantineStore` annotate correctly; the inconsistency is the hazard. Annotate the durable constructor and reduce the test constructor's visibility.

---

## 4. Safety Invariants

### FR-P0.2 — `RETURN_TO_SERVICE` classifies split-brain as `SUCCEEDED` **(critical)**
```java
boolean demotedRestored = AFFIRMATIVE_RECOVERED_ROLES.contains(postA.observedRole().toUpperCase(Locale.ROOT));
```
`postB` is never read, and the allowlist contains `ACTIVE`. **Both members observed `ACTIVE` → `SUCCEEDED`, no quarantine, schedule `COMPLETED`.** Dual-active is the most dangerous post-condition in any HA pair; the engine reports it as clean success and releases the cluster for the next operation.

The asymmetry is stark: the `CONTROLLED_FAILOVER` branch *does* catch dual-active (`standbyPromoted && !activeDemoted` → `OUTCOME_UNKNOWN` + quarantine). The remediation applied the two-sided logic to one branch and the claim to both.

*Required:* assert a vendor- and action-specific **safe role pair** — exactly one active member where the vendor requires it, peer affirmatively ready, critical devices and cluster interfaces healthy on both sides — not a membership test on one member's role string.

### FR-P0.3 — Delivery certainty is a deny-list with a fail-open default
Three problems in one branch:
1. `successful() == true` never consults `certainty()`, so `SUBMITTED_SUCCESS` and `DELIVERY_UNKNOWN` enter identical post-verification.
2. The failure branch tests `certainty() == DELIVERY_UNKNOWN`. A **null** certainty, or any value added to the enum later, evaluates false and takes the permissive "device untouched" path. The default must be the safe one.
3. `DEFINITELY_NOT_SUBMITTED` and `DEFINITELY_REJECTED` share a path despite different meanings — the first means the authorization was burned without the device ever seeing the command.

*Required:* validate the closed product as a state machine and invert the test to an affirmative allowlist: quarantine unless certainty is affirmatively `DEFINITELY_REJECTED` or `DEFINITELY_NOT_SUBMITTED`; reject `successful == true` with anything but `SUBMITTED_SUCCESS`; reject `successful == false` with `SUBMITTED_SUCCESS`.

### FR-P0.13 — Any post-boundary exception fails **open**
Between `boundaryCrossedAt` and the final `recordResult`, there is no catch-all. `postA.observedRole()` returning null (→ `toUpperCase` NPE) or `active.selfState()` null (→ `equalsIgnoreCase` NPE in the rejection path) throws straight out of `executeFailoverInternal`, through the `finally` blocks that **release** the cluster lock and semaphore, with **no quarantine engaged and no execution record written**. A command that may have mutated a production cluster leaves no durable trace, and the next execution is immediately permitted.

*Required:* wrap everything after the mutation boundary in a `try { ... } catch (Throwable t) { engageQuarantine(...); recordResult(OUTCOME_UNKNOWN, ...); }`. This is the single cheapest high-value fix in the set.

### FR-P0.4 — Booking seals a baseline from evidence that was never approved
`scheduleMaintenanceWindow` calls `preflightService.getLatestReport(clusterRef)` for the verdict, then `buildSnapshotForCluster(clusterRef)` for the baseline — two separate evidence passes. The signed baseline can therefore describe a cluster state no pre-flight battery ever approved. The single-snapshot invariant is enforced at dispatch (where, at its only call site, both sides are derived from the same object and the check is tautological) and absent at booking, where it is load-bearing. `getLatestReport` also has no freshness bound at booking; the five-minute evidence-age rule lives only in the dispatch lambda. The same pattern appears in `FailoverAuthorizationService.authorizeFailover`.

### FR-P0.5 — Missing baseline evidence is an implicit waiver
`softwareVersion`, `policyHash` and `haMode` are all treated as `MATCH` when the **baseline** value is null or blank. A null baseline field is exactly what a collection gap produces — so a schedule sealed during a partial collection permanently waives version and policy drift detection for that execution. Absence of evidence is being converted into evidence of no drift. These must be `NOT_EVALUABLE` — ideally rejected at booking, so a baseline is never sealed with an unproven safety dimension.

### FR-P0.14 — `flapCountLast24Hours` is a windowed metric compared as a monotonic counter
```java
if (liveTransitions < baseline.transitionCounter()) { /* "member reboot or counter rollover suspected" */ }
```
A rolling 24-hour counter *legitimately decreases* as transitions age out of the window, with no reboot and no rollover. Booking lead time is up to 7 days, so for any schedule booked more than a day ahead this is the **expected** behaviour. The engine aborts fail-closed (safe) but emits `CLUSTER_TRANSITION_DETECTED` with a diagnosis that is semantically wrong, systematically, on normal clusters. A field name is not its contract: either compare a genuinely monotonic vendor counter, or compare window-aligned counts captured over the same window offset.

### FR-P0.7 — Four-eyes is one actor supplying two strings
`PrincipalCanonicalization.requireDistinctPrincipals(requesterId, approverId, ...)` compares two values that arrive **in the same call, from the same caller**. Nothing in the supplied code binds either identifier to an authenticated session. The same shape governs quarantine release: `acknowledgeQuarantine(clusterRef, executionId, requesterId, approverId, reason)` is one call, two strings, and it releases a cluster locked after an ambiguous CLASS 2 outcome. `engageQuarantine` and `acknowledgeQuarantine` are both `public` on `FailoverExecutionService` with no authorization check of their own.

I mark the controller layer `UNKNOWN` — it was not supplied. But two-person control is the load-bearing authorization control for this entire engine, and the supplied evidence does not demonstrate it. It requires two independently authenticated acts, separated in time, against a stored pending request. If that exists elsewhere, it must be shown; if it does not, every dual-control claim in Phases A–D is unsupported.

Independently, the canonicalization itself is incomplete: no NFKC normalization, no script-confusable check, and `isISOControl` misses zero-width and bidi-format characters. And the **canonical** form is used only for the comparison while the **raw** string is persisted and later replayed into `FailoverAuthorizationRequest` — the comparison universe and the storage universe differ.

### FR-P0.10 — Booking admission race exists within a single JVM
```java
admissionControl.validateBookingAdmission(..., allSchedulesSnapshot());
```
The snapshot is read as an **argument**, outside the `synchronized` method, and `scheduleMaintenanceWindow` is not itself synchronized. Two concurrent bookings both read a pre-insert snapshot, both pass, both insert — overlapping windows admitted under a stated fleet-concurrency limit of 1. This is not only the multi-replica gap Astra named; it reproduces in one instance. Close it with a `SERIALIZABLE` transaction or an exclusion constraint over `[window_start, execution_deadline)` in the database, not with a Java monitor.

The same in-process-only problem applies to the execution side: `Semaphore fleetConcurrencySemaphore` and `activeClusterLocks` are per-JVM, yet the class javadoc asserts "Fleet-wide concurrency capped at 1" as an invariant. A stated invariant that the code enforces only per-process, over a shared database, is a documentation defect as much as a concurrency one.

### FR-P1.8 — Clock health cannot prove trusted time, and will eventually fail permanently
Beyond the unresolved `Math.abs` (CF-P0.8 above): comparing a host clock against the same host's monotonic timer since process start cannot establish *trusted* time — it establishes internal self-consistency. Meanwhile, ordinary crystal drift (10–100 ppm against a slewed wall clock) accumulates 0.86–8.6 s/day, so a long-lived process crosses the 60 s tolerance within roughly 7–70 days and then fails `ERR_CLOCK_DRIFT` **permanently**, blocking all scheduled execution until restart. Fail-closed, but a guaranteed false positive on a schedule. Consult an authoritative clock-health source (chrony/NTP tracking: stratum, offset, leap status) and retain prior wall/monotonic sample pairs to detect individual backward steps rather than net divergence.

### FR-P1.4 — AIView masking and safe-error discipline
- `recordResult(..., active.memberId(), ...)` and `engageQuarantine(..., Set.of(active.memberId(), standby.memberId()))` write **raw member identifiers** into the execution record and into `failover_quarantine.quarantined_member_ids`. Whether `memberId` is an opaque internal reference or a production identity is not determinable from the code — and that ambiguity *is* the finding. There is no type-level distinction between an opaque reference and a presentation identity, so nothing prevents the latter reaching a durable evidence structure. Introduce a typed opaque reference and enforce it in the architecture tests.
- `"Single-use grant check failed: " + ex.getMessage()` places `consumeGrant`'s message — which embeds the raw `grantId` — into a channel named `sanitizedMessages()`, from which it flows to `abort_reason` and the ledger `details`.
- `"Cluster execution lock is already held for: " + clusterRef` reintroduces the raw cluster reference that `FailoverAuthorizationService` deliberately suppressed under F-6.

### FR-P1.9 — Pre/post evidence vocabularies are compared as if identical
`active.selfState().equalsIgnoreCase(rejectObs.memberAObservation().observedRole())` compares a pre-mutation *snapshot* field against a post-mutation *executor observation* field. These are two different evidence sources; no contract proves they share a role vocabulary. Field presence is not field-semantic proof. The result today is fail-closed (spurious quarantines) but the comparison is unsound in both directions.

---

## 5. Final Verdict

### [REJECTED]

The architecture is correct in its bones — sealed authorizations, deterministic typed drift, storage-enforced single use, typed delivery outcomes, sticky quarantine, conservative restart semantics. That is real progress, and several Stage 1/2 remediations (CF-P0.1, CF-P0.3/P0.10, the drift engine's `NOT_EVALUABLE` discipline, the grant uniqueness constraint, the quarantine CAS update) are correctly closed.

The verdict is rejection because the residual defects sit at the exact trust boundaries the engine exists to defend, and because three of the twelve claimed remediations are **materially misstated**: CF-P0.8 claims a code change that is not in the code, CF-P0.18 claims two-sided verification that is one-sided, and CF-P0.20 claims crash recovery that covers one of two execution paths. A remediation register that cannot be trusted is itself a security control failure — every closure claim in this cycle now requires independent verification rather than acceptance.

**Mandatory P0 closure set** (all required before any further real-environment work):

1. **FR-P0.2** — `RETURN_TO_SERVICE`: vendor-safe two-sided pair assertion; dual-active must quarantine, never succeed.
2. **FR-P0.13** — catch-all quarantine + record for any throwable after the mutation boundary.
3. **FR-P0.1** — recompute and constant-time verify the baseline digest before trusting `baseline_json`; verify `clusterRef`.
4. **FR-P0.3** — enforce `successful × DeliveryCertainty` as a closed state machine with a fail-closed default.
5. **FR-P0.6** — separate key initialization from runtime resolution; never regenerate when schedules exist; 0600 permissions; explicit durable path; persist `key_id`/`alg_version` per schedule.
6. **FR-P0.8 / FR-P0.11** — ownership-fenced reconciliation; quarantine before status flip.
7. **FR-P0.9** — durable execution records and crash recovery for the manual Phase C path.
8. **FR-P0.12** — remove the CAS-bypassing acknowledge overload; append-only per-incident quarantine.
9. **FR-P0.7** — demonstrate (or build) two independently authenticated acts for every dual-control gate; complete principal canonicalization with NFKC + confusable rejection.
10. **FR-P0.4 / FR-P0.5 / FR-P0.14** — single evidence pass at booking with a freshness bound; missing baseline dimensions `NOT_EVALUABLE`; correct windowed-counter semantics.
11. **FR-P0.10** — database-level exclusion for booking overlap and execution concurrency, or a demonstrated and enforced singleton deployment gate recorded as a contract.
12. **FR-P0.2 (crypto)** — canonical temporal precision normalized before signing and storing; `OffsetDateTime` binding.

**Architectural note for the Product Owner.** The engine's threat model is currently self-contradictory: it defends against an adversary with database-write capability (HMAC over the envelope, hash-chained ledger) while storing the verification key on the same host filesystem, leaving the ledger unkeyed and structurally mutable, and never recomputing the digest that makes the HMAC meaningful. Either that adversary is in scope — in which case the key belongs in an HSM/KMS, the ledger must be keyed or externally anchored, and FR-P0.1 is load-bearing — or it is not, in which case most of the cryptographic surface should be simplified rather than half-built. Resolving that question is a prerequisite to the closure set, not an outcome of it.

The Failover Engine must remain disabled for unattended and production mutation. After closure, it requires transactional fault-injection testing with crashes injected between every database write, ledger append, quarantine engagement, lease consumption and device dispatch — including a rolling-restart scenario with a second replica starting mid-dispatch — followed by vendor-specific real-environment validation per Check Point and Palo Alto separately.
