# Final Comprehensive Re-Review — Failover Engine Phases A–D

## Final Verdict: [REJECTED]

The implementation is materially stronger, and many Stage 1/2 remediations are directionally correct. However, several remaining P0 defects invalidate the cryptographic baseline, durable crash-recovery, transport certainty, and return-to-service safety claims.

This verdict is based exclusively on the supplied code. No repository inspection, execution, tests, deployment, devices, production data, or project state were changed.

## 1. P0/P1 Remediation Assessment

| Remediation | Assessment | Finding |
|---|---|---|
| CF-P0.1 `actionKind` binding | Resolved | `actionKind` is included in the canonical envelope and therefore covered by HMAC. |
| CF-P0.2 deterministic baseline digest | Partial / P0 gap | The digest is deterministic, but it is never recomputed when loading or dispatching. An attacker with database-write capability can alter `baseline_json`, retain its old `assessmentDigest`, and still pass envelope verification. |
| CF-P0.3/P0.10 dynamic deadline | Resolved | The derived deadline is constructor-validated and checked inside the execution lock before lease consumption and again before dispatch. |
| CF-P0.5 durable keys | Partial / P0 gap | Persistence exists, but deletion of the key file causes automatic generation of a new key. Existing schedules then appear tampered instead of producing `ABORTED_KEY_UNAVAILABLE`. |
| CF-P0.7 durable persistence | Partial | Core records are persisted, but related state transitions are not transactional. |
| CF-P0.8 monotonic clock | Partial | A wall clock earlier than process start is caught, but an actual backward step that leaves the clock later than process start can pass when within the 60-second tolerance. The implementation still uses `Math.abs`. |
| CF-P0.11–13 fail-closed drift | Substantially resolved | Snapshot identity and missing live evidence fail closed. See baseline digest and booking snapshot qualifications below. |
| CF-P0.14 dual control | Partial | Case and whitespace variants are handled, but the stated confusable rejection is not implemented. Unicode normalization/script-confusable checks are absent. |
| CF-P0.16 typed delivery certainty | Partial / P0 gap | The type exists, but inconsistent combinations are not rejected. A `successful()` result with `DELIVERY_UNKNOWN` can enter normal post-verification, and an unsuccessful `SUBMITTED_SUCCESS` is treated like an affirmatively rejected command. |
| CF-P0.18 return-to-service verification | Not resolved / P0 | Only `postA` is tested against the role allowlist. Both members are not affirmatively evaluated as claimed, nor is a vendor/action-specific safe pair asserted. |
| CF-P0.20 startup crash recovery | Partial / P0 gap | Orphans are detected, but schedule reconciliation, ledger append, and quarantine engagement are separate transactions. A crash after setting `OUTCOME_UNKNOWN` but before quarantine leaves the cluster permanently unquarantined because the next startup no longer selects that row. |
| CF-P0.21 AIView masking | Partial | Several messages use masked names and exception classes, but raw member identifiers remain in durable quarantine JSON and execution results. Whether that storage is permissible requires an explicit opaque-identity/storage contract. |
| CF-P1.2 key identity | Partial | Unknown keys fail closed, but `key_id` and algorithm version are not stored with schedules. Every record is reconstructed as `k1`, preventing safe key rotation or historical verification. |
| CF-P1.4 state-machine durability | Partial | Status CAS is useful, but it checks status rather than the supplied `version`; state update and ledger append are not atomic. |
| CF-P1.14 admission control | Logic improved, concurrency unresolved | Rolling-window and directional cooldown logic are corrected. Read-then-admit remains unsafe across replicas, and no demonstrated database exclusion/locking or enforced singleton guard closes it. |

## 2. Persistence and Durability

The schema correctly supplies durable tables, a storage-level unique grant identity, schedule status fields, and a quarantine CAS key. PostgreSQL `TIMESTAMPTZ`, JSONB storage, and identity keys are reasonable choices.

The durability boundary is nevertheless incomplete:

1. No transaction atomically groups:

   - Schedule insertion and initial ledger entry.
   - Schedule claim and ledger entry.
   - Grant consumption, lease consumption, and dispatch-state persistence.
   - Terminal schedule update and ledger entry.
   - Startup reconciliation, ledger append, and quarantine engagement.

2. `DISPATCHING` is recovered but is never visibly persisted by the supplied execution flow. `CLAIMED_VERIFYING` therefore represents both definitely-pre-mutation and possibly-post-mutation states.

3. The ledger tip calculation uses read-next-insert without database locking. Java `synchronized` protects only one service instance. Multiple replicas can calculate the same `entry_index`; one will fail after the associated schedule transition may already have committed.

4. The database does not enforce ledger append-only behavior. `UNIQUE(entry_index)` prevents duplicate indexes but does not prevent update or deletion.

5. The quarantine table overwrites the previous incident for a cluster. It provides current sticky state, not durable quarantine history.

6. Referential constraints are missing between schedules, grant consumption, and ledger entries. This permits orphan records unless application code remains perfect.

The `UNIQUE(grant_id)` consumption constraint itself is sound for replay prevention. It does not make the larger execution transition atomic.

## 3. Cryptographic and State Integrity

### Correct

- `actionKind` is HMAC-bound.
- Canonical envelope fields use UTF-8 byte lengths.
- Signature comparison uses `MessageDigest.isEqual`.
- The baseline digest is deterministic.
- Unknown key IDs fail closed.
- The schedule record rejects a stored deadline that differs from the derived deadline.

### Blocking defects

#### P0 — Baseline substitution remains possible

At dispatch, the envelope is rebuilt using:

```java
record.baselineSummary().assessmentDigest()
```

The service never verifies:

```java
record.baselineSummary().computeCanonicalDigest()
    .equals(record.baselineSummary().assessmentDigest())
```

Consequently, modifying active/standby identity, version, policy, topology, transition counter, or timestamp in `baseline_json` does not invalidate the HMAC as long as the embedded digest string is left unchanged.

Required correction: recompute and constant-time compare the baseline digest after deserialization and immediately before envelope verification/drift evaluation. Prefer also verifying `baseline_digest`, the JSON-embedded digest, and the recomputed digest are identical.

#### P0 — Lost key becomes false tamper

A missing default key file is treated as first deployment and regenerated. The service cannot distinguish:

- Initial secure bootstrap.
- Accidental key loss.
- Deployment mounted at the wrong path.
- Partial persistent-volume failure.

After regeneration, old schedules verify with the wrong key and become `ABORTED_TAMPERED`, recreating the original false-tamper condition.

Required correction: separate explicit key initialization from runtime key resolution. Runtime must never silently generate a replacement when durable schedules may already exist.

#### Ledger integrity limitations

The ledger hash framing is delimiter-based rather than length-prefixed. Actor IDs and details containing `|` can create ambiguous logical attribution.

There is also a likely timestamp precision defect: the hash is calculated using the original nanosecond `Instant`, while PostgreSQL commonly persists timestamps at microsecond precision. Reloading the row can therefore produce a different `Instant.toString()` and fail `verifyChainIntegrity()`.

Required correction: canonical length-prefixed framing and normalization to the exact database timestamp precision before hashing and inserting.

A plain unkeyed hash chain detects accidental corruption but cannot resist a database writer rewriting the entire chain. It should not be described as tamper-proof without an external anchor, signature, or separately protected checkpoint.

## 4. Safety Invariants

### Dynamic deadline

The two in-lock checks are correctly positioned. The residual interval between the final check and executor invocation is unavoidable but bounded by local call overhead.

### Drift and single-snapshot gate

T₀ report evaluation and drift comparison use the same supplied snapshot ID, which is correct.

Booking is weaker: it accepts `getLatestReport(clusterRef)` and then independently obtains another snapshot. Thus the schedule baseline may not correspond to the readiness report that authorized booking. Booking should evaluate the exact snapshot used to build the baseline.

The drift engine also treats absent baseline version, policy, or HA mode as `MATCH`. If these fields are required safety dimensions, missing baseline evidence must be `NOT_EVALUABLE`, not an implicit waiver.

### Delivery certainty

The enum must be validated as a closed state machine:

- `successful == true` must require `SUBMITTED_SUCCESS`.
- `successful == false && DELIVERY_UNKNOWN` must quarantine.
- `successful == false && DEFINITELY_REJECTED` may use guarded no-change verification.
- `DEFINITELY_NOT_SUBMITTED` must not be described as vendor rejection.
- `successful == false && SUBMITTED_SUCCESS` is inconsistent and must quarantine or fail closed.

Currently, only `DELIVERY_UNKNOWN` is distinguished on the failure branch.

### Return to service

The current implementation proves only that `postA` has one of four generic role strings. It does not prove:

- Both members are independently in a compatible safe state.
- Exactly one active member exists where required.
- The peer is ready or healthy.
- The observed role pair is valid for the vendor and action.
- Critical interfaces and devices remain healthy after restoration.

This does not satisfy affirmative two-sided return-to-service verification.

### Clock health

Comparing every reading only with process-start anchors detects large net divergence, not every backward step. A small backward adjustment can remain within tolerance and pass. Detecting backward steps requires retained prior wall/monotonic samples or an authoritative clock-health source.

### AIView

User-facing summaries are improved, but raw identities are still passed into execution records and persisted in quarantine. That may be acceptable only if they are opaque internal identifiers and access is restricted. They must never be projected into UI, logs, exception text, or shareable evidence without pseudonymization.

## Required P0 Closure Set

Approval requires, at minimum:

1. Recompute and verify the baseline digest before trusting `baseline_json`.
2. Make startup reconciliation plus quarantine atomic or safely retryable/idempotent.
3. Enforce valid `successful × DeliveryCertainty` combinations.
4. Replace return-to-service’s single-member role check with vendor-safe, two-sided affirmative verification.
5. Prevent silent key regeneration after key loss; persist `key_id` and algorithm version per schedule.
6. Make schedule state, grant use, ledger attribution, and execution-boundary transitions transactionally coherent.
7. Fix ledger canonicalization, timestamp precision, and multi-instance chain serialization.
8. Close multi-instance booking/dispatch concurrency through database locking/constraints or a demonstrated enforced-singleton deployment gate.

## Architectural Assessment

The design now has the right major components: sealed schedules, deterministic drift, durable replay prevention, typed delivery outcomes, sticky quarantine, and conservative restart behavior. The remaining problems are not cosmetic recommendations; they occur precisely at the trust boundaries the engine is intended to secure.

The Failover Engine should remain disabled for unattended or production mutation. After the P0 closure set is implemented, it should undergo transactional fault-injection testing—especially crashes between every database update, ledger append, quarantine action, lease consumption, and device dispatch—followed by vendor-specific real-environment validation.
