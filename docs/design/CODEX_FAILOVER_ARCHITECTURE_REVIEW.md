Role: Lead Software Architect / Codex Reviewer (Astra)

# [REJECTED]

The product direction is reasonable, but this implementation plan is not safe or contract-complete enough to authorize. It introduces a CLASS 2 mutation path while leaving critical authorization, concurrency, uncertainty, vendor semantics, and recovery behavior underspecified. It also risks conflicting with the existing failover architectural invariants and canonical verdict logic.

## Blocking findings

1. **Implementation authority is not established.**  
   A failover executor, vendor mutation commands, scheduled execution, and new persistence schema require a verified FROZEN contract and completed network-command gates. Referring to `OP.2.0 §10.2` is insufficient unless its status, scope, commands, identities, failure semantics, and scheduling authority are confirmed. The existing `utils/failover/` boundary remains read-only and must not be treated as implicit approval for Java execution.

2. **The proposed state machine misrepresents post-mutation failure.**  
   Once a command may have reached a device, the operation cannot become `ABORTED`. A timeout or worker crash can leave the outcome unknown. Blind retry could perform a second mutation.

3. **“4-eyes token” is not a four-eyes control.**  
   It must prove two distinct authenticated principals, separation of duties, approval of the exact immutable plan, approval expiry, and server-side enforcement. A modal and an eight-character reason are only UI validation.

4. **The SPI gives plugins excessive authority.**  
   Supplying a generic `DeviceTransport` to every check permits arbitrary commands and bypasses the approved-command registry. Pre-flight checks should evaluate typed, sanitized evidence; they should not directly control transport.

5. **Scheduling is placed in the wrong layer.**  
   `ui2-service` should persist authorized intent and enqueue durable work. It should not own a background executor that independently reaches devices. Execution, locking, JIT evidence collection, and recovery belong in the worker/job lifecycle.

## 1. Interface and SPI design

The proposed interface is too weak for safety-critical extensibility:

```java
CheckResult evaluate(FailoverContext context, DeviceTransport transport);
```

Required changes:

- Replace `String vendor` with a closed vendor/capability type.
- Do not put credentials or connection targets in `FailoverContext`.
- Do not expose generic transport to checks.
- Separate collection from evaluation:
  - Vendor evidence providers execute only pre-approved read operations.
  - Checks are pure evaluators over typed evidence snapshots.
- Register production checks at application startup from an explicit allowlist. Arbitrary runtime plugin registration is inappropriate for a mutation gate.
- Give every check a stable ID and version.
- Declare required evidence, supported topology/mode, freshness requirements, and applicability.
- Keep outcome separate from policy:
  - Outcome: `PASS`, `FAIL`, `UNKNOWN`, `COLLECTION_FAILED`, `NOT_APPLICABLE`.
  - Enforcement: `BLOCKING` or `ADVISORY`.
- Do not let plugin authors unilaterally decide whether a check is blocking. That mapping is governed policy.
- Add `observedAt`, evidence grade, source type, expiry, reason code, and sanitized evidence references.
- Avoid free-form `details` that could leak identities or raw vendor output.

The seven legacy stop conditions should have an explicit one-to-one compatibility table against the existing canonical assessment logic. Do not recreate their meaning from labels, community guidance, or vendor field names.

A safe ownership split is:

- `job-engine`: state machine, typed plans, aggregation policy, invariants.
- `worker`: approved evidence collectors, vendor adapters, mutation execution.
- `service`: authentication, authorization, persistence, queue submission.
- `frontend`: sanitized projections and typed intent only.

## 2. Failover lifecycle state machine

The proposed states omit approval, scheduling, cancellation, expiry, uncertain execution, and reconciliation.

Recommended lifecycle:

```text
PLANNED
  -> AWAITING_APPROVAL
  -> APPROVED
  -> SCHEDULED | READY_FOR_JIT
  -> CLAIMED
  -> PREFLIGHT_RUNNING
  -> READY_TO_EXECUTE
  -> MUTATION_SUBMITTED
  -> POST_VERIFYING
  -> COMPLETED
```

Valid terminal outcomes before mutation:

```text
CANCELLED
EXPIRED
AUTHORIZATION_REVOKED
PREFLIGHT_BLOCKED
DRIFT_ABORTED
```

Required outcomes after mutation may have been submitted:

```text
COMPLETED
EXECUTED_NOT_CONVERGED
OUTCOME_UNKNOWN
RECONCILIATION_REQUIRED
```

`ROLLED_BACK` should not be a transition in the original job. Reversal is a separately authorized typed action with its own plan, pre-flight, approvals, job ID, execution record, and verification result. It can reference the original execution as its cause.

Concurrency requirements:

- One distributed lock per operational HA unit—not display name or inferred member identity.
- Use a fencing token, not merely a lease.
- Manual, scheduled, and reversal jobs must contend for the same lock.
- Persist a unique execution/idempotency key.
- Never promise exactly-once device mutation.
- If the worker loses its lease before mutation, stop.
- If the lease expires or the process crashes after submission may have occurred, perform read-only reconciliation; never automatically resend.
- Write a durable “mutation about to be attempted” event before sending.
- Make audit persistence failure fail closed before mutation.
- Use an outbox or equivalent durable delivery mechanism for post-command audit notifications.

`READY` must be short-lived and bound to:

- Operational-unit identity
- Target identity
- Topology generation
- Evidence snapshot/check-set versions
- Plan version and digest
- Approval identities and expiry
- Scheduled window
- Command-contract version

## 3. Scheduling and JIT pre-flight

Manual and scheduled executions should use the same worker pipeline.

At execution time, the worker should:

1. Claim the durable job using a lease and fencing token.
2. Acquire the operational-unit lock.
3. Reload authorization, cancellation state, maintenance window, topology, and target identity.
4. Confirm both approvals remain valid and apply to the current plan digest.
5. Collect a new evidence snapshot through approved vendor reads.
6. Run the same canonical pre-flight evaluator.
7. Compare defined safety-relevant facts with the approved baseline.
8. Abort before mutation on blocking failure, unknown evidence, material drift, or expired authorization.
9. Submit the mutation once.
10. Verify the post-condition using fresh direct evidence.
11. Persist the derived result and sanitized audit events.

“Any network state drift” is too broad. Volatile counters will always drift. The contract must enumerate material drift fields, such as:

- Member identity or role
- Peer availability
- Sync state
- Critical interface/path state
- Policy/software compatibility
- Routing safety status
- Approval target
- Topology generation

For the open TTL question: **always run JIT pre-flight immediately before mutation**. A five-minute report may be displayed as a preview, but it must never authorize execution.

Scheduling must also define UTC storage, displayed timezone, daylight-saving behavior, missed windows, maximum lateness, cancellation cutoff, approval expiry, duplicate delivery, and service restart recovery.

## 4. Multi-vendor adequacy

The common model is viable only at the orchestration level. Evidence and semantics must remain vendor-specific.

For both vendors:

- Support only explicitly contracted HA modes.
- Fail closed for active/active, unsupported multi-member arrangements, ambiguous identity, or incomplete peer observation.
- Verify the physical/evidence member separately from the operational cluster identity.
- Require independent direct observations where the conclusion depends on both peers.
- Do not treat manager-plane information as direct runtime truth.
- Do not infer health from field presence or successful command return.

Check Point needs explicit contracts for ClusterXL mode, VSX context, shell/context, pnotes interpretation, sync semantics, monitored interfaces, target-member selection, and post-demotion verification.

Palo Alto needs explicit contracts for active/passive mode, suspended/non-functional states, HA1/HA2 and data-link semantics, path/link monitoring, session synchronization, preemption behavior, and direct-device versus Panorama evidence.

Several current checks are too vague:

- “Policy parity” must define exactly which evidence proves compatibility.
- Resource headroom requires vendor-specific thresholds and evidence semantics; otherwise it is advisory.
- Flap history needs a time window and defined source.
- Session synchronization must not be reduced to one status string.
- Routing stability, interface/VIP safety, BGP, and synthetic probes are listed as goals but not actually represented in the proposed built-ins.
- `NOT_APPLICABLE` must be distinct from successful evaluation.

All mutation and verification commands require their own frozen command contracts. Command strings found in source or vendor examples do not constitute approval.

## 5. API, authorization, and UI corrections

The browser must submit typed intent only. It must never receive or submit primitive or reversal commands.

Prefer APIs centered on durable resources:

```text
POST /preflight-runs
POST /failover-plans
POST /failover-plans/{id}/approvals
POST /failover-plans/{id}/executions
POST /failover-plans/{id}/schedules
GET  /failover-executions/{id}
POST /failover-executions/{id}/reversal-plans
```

Execution should reference an immutable plan ID and digest. Server-side validation must enforce:

- Two distinct principals
- Initiator cannot self-approve
- Required roles and scope
- Approval expiry/revocation
- Exact cluster, target, action, and window binding
- CSRF protection
- Idempotency
- Mandatory bounded reason and audit-safe sanitization

The UI may display a human-readable reversal procedure, but not executable command text. “Initiate Failover” should depend on current authorization and JIT execution eligibility—not merely a cached `READINESS_CONFIRMED` verdict.

## Verification additions required

The proposed test plan also needs:

- Concurrent manual-versus-scheduled execution
- Duplicate queue delivery
- Lease expiry at every execution boundary
- Crash immediately before and after command submission
- Transport timeout with unknown device outcome
- Approval expiry and revocation
- Topology or target drift
- Unsupported HA mode
- One-sided peer evidence
- Audit-store failure
- Schedule cancellation and missed-window behavior
- Reversal as an independent job
- Browser-to-device architecture enforcement
- UI render harness and repository privacy gate
- Real-environment validation for each vendor before any `DONE` status

## Conditions for resubmission

Approval would require:

1. Verified FROZEN CLASS 2 execution and scheduling contract.
2. Completed command gates for every read, mutation, and reversal operation.
3. Pure check SPI over typed evidence rather than generic transport.
4. Durable fenced execution with explicit uncertain-outcome handling.
5. Independent, plan-bound four-eyes authorization.
6. Separate reversal jobs rather than `ROLLED_BACK`.
7. Vendor-specific evidence contracts and unsupported-mode behavior.
8. JIT pre-flight under the same lock immediately before mutation.

No tools were run and no repository, Git, deployment, device, or production state was changed.