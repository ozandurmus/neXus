# [REJECTED]

The safety intent is strong, but Phase C is not safe to implement from this plan. The primary blockers are non-durable authorization, an unproven mutation transaction boundary, a generic command-bearing adapter, and insufficiently independent evidence collection.

“Exactly once” cannot be guaranteed across a database and an external firewall. The achievable contract is:

> At-most-once command submission, with every uncertain post-boundary condition becoming sticky `OUTCOME_UNKNOWN`.

## Blocking findings

| Priority | Finding | Required correction |
|---|---|---|
| P0 | Phase B does not establish real four-eyes approval. `approverId` is requester-supplied text. | The approver must authenticate independently and approve the exact plan/action through a separate session. |
| P0 | Dry-run consumes the single-use token, so the proposed authorize → dry-run → execute flow cannot work. | Compile the plan first, bind approval to its digest, and consume the execution lease only at the durable mutation boundary. |
| P0 | Tokens, signing key, replay state, and consumption state are process-local. Restarts and multiple replicas break validity and single-use guarantees. | Persist leases and consumption transactionally. Keep signing material in stable protected key storage. |
| P0 | `mutation_boundary_crossed` has no specified atomic transaction. | Atomically consume the lease, create the execution, bind the command descriptor, acquire the cluster operation lock, and record `boundaryCrossedAt`. |
| P0 | `executeOnce(String targetMember, String command)` creates a generic command channel. | Accept only a closed action enum and opaque entity reference. The vendor adapter must own the command literal. |
| P0 | Independent two-peer verification is not represented by the SPI. | Require two direct, identity-verified member observations with separate provenance, timestamps, and collection outcomes. |
| P0 | The current dry-run asserts unproven impact and continuity: fixed millisecond estimates, “flows preserved,” and preemption behavior. | Remove these claims or derive them from authoritative configuration and evidence. Unknown must remain unknown. |
| P0 | Vendor command semantics have not been demonstrated through the command gate, frozen contract, official documentation, and lab evidence. | Complete those gates before implementation or release. |

## 1. Lifecycle and mutation boundary

The transition shape is reasonable, but the state names need exact semantics. `MUTATION_COMMITTED` must not mean that the device changed; it means that neXus irreversibly committed to one possible submission.

The safe transition contract is:

```text
PLANNED
  -> PRECONDITION_VERIFYING
      -> ABORTED_PRE_MUTATION
      -> MUTATION_COMMITTED
          -> EXECUTING
              -> POST_OBSERVING
                  -> SUCCEEDED
                  -> FAILED_NO_CHANGE
                  -> OUTCOME_UNKNOWN
```

Required rules:

- `PRECONDITION_VERIFYING → MUTATION_COMMITTED` must be one database transaction that:

  - validates and consumes the execution lease;
  - compares the approved plan and current assessment digests;
  - acquires a unique active-operation lock for the cluster;
  - freezes action, vendor, target opaque identity, VSID/context where relevant, and command descriptor;
  - records `boundaryCrossedAt`;
  - creates an append-only audit event.

- `MUTATION_COMMITTED → EXECUTING` must use a database compare-and-set. Only the process winning that transition may touch the transport.
- A process may send only after the `EXECUTING` transition is durably confirmed.
- A worker finding an existing `EXECUTING` record after restart must never resubmit. It may perform read-only reconciliation, but the execution remains uncertain until evidence proves an allowed terminal outcome.
- Any crash, timeout, partial write, lost response, malformed response, or audit-write failure after `EXECUTING` must preserve the cluster lock and result in `OUTCOME_UNKNOWN` unless direct post-observation proves a stricter outcome.
- `ABORTED_PRE_MUTATION` is illegal once `boundaryCrossedAt` exists.
- Terminal history must never be rewritten. Manual acknowledgment may resolve the entity quarantine but must not change the historical `OUTCOME_UNKNOWN` result.

A boolean `commandSubmitted` is inadequate. Persist separate facts such as:

- `attemptStartedAt`
- `transportDisposition`: `NOT_ATTEMPTED`, `DEFINITELY_NOT_SENT`, `MAY_HAVE_BEEN_SENT`
- `vendorAcceptance`: `ACKNOWLEDGED`, `REJECTED`, `UNKNOWN`
- `boundaryCrossedAt`

Duplicate HTTP requests also require a durable idempotency key. They must return the existing execution rather than create a second one.

## 2. Precondition and post-verification

“Same-workflow” verification reduces risk but cannot guarantee prevention of split-brain: another operator or management plane can change state between observation and command submission.

Immediately before the boundary, neXus must independently establish:

- exactly one active member;
- the chosen target is that active member;
- both endpoint identities match the approved operational unit;
- both observations are direct-device evidence, not one peer describing the other;
- required synchronization, health, pnote, interface, and capability checks are current;
- the approved assessment digest matches the server-produced assessment;
- the evidence age is within a frozen maximum;
- no execution or unresolved quarantine already exists;
- the maintenance window and pilot enrollment are currently valid.

`checkPrecondition(ClusterEvidenceSnapshot snapshot)` is too weak unless the snapshot carries immutable provenance and was collected inside the execution workflow. A cached snapshot passed into the adapter is not sufficient.

Post-verification must represent two distinct reads:

```text
Member A: endpoint identity + direct observation + timestamp + collection result
Member B: endpoint identity + direct observation + timestamp + collection result
```

A peer-reported peer state cannot satisfy the second observation.

Terminal classification should be conservative:

- `SUCCEEDED`: both direct observations agree on the intended role transition and every required continuity criterion is proven.
- `FAILED_NO_CHANGE`: both direct observations prove the original topology remained stable after the defined observation horizon, with no contradictory evidence.
- `OUTCOME_UNKNOWN`: either member is unreachable, identities mismatch, results conflict, parsing is incomplete, convergence exceeds the deadline, or continuity cannot be established.

Read-only observation retries are appropriate within a bounded vendor-specific deadline. Mutation retries are not.

“HA synchronization healthy” does not itself prove zero connection drops. If session continuity is mandatory, it needs an explicitly approved measurement source. Otherwise report it as `NOT_EVALUABLE`; do not render “0 connection drops.”

## 3. Reversal architecture

The prohibition on automatic rollback is correct and must remain absolute.

The `/reversal` endpoint is acceptable only if reversal is modeled as a completely new operation with:

- a new plan and digest;
- fresh preflight evidence;
- independent four-eyes approval;
- a new single-use execution lease;
- a new state-machine instance;
- current allowlist and maintenance-window checks;
- its own post-verification and audit history.

Manual acknowledgment of `OUTCOME_UNKNOWN` must not itself authorize reversal. The actual topology must first be independently established.

The terminology also needs correction:

- `clusterXL_admin up` re-enables participation; it does not inherently prove or command failback.
- PAN `functional` returns the peer to HA participation; its resulting role depends on current state and validated preemption behavior.

Unless a frozen vendor contract proves otherwise, label these actions “return member to service,” not “failback.”

## 4. Vendor mechanics

The proposed commands are plausible command candidates, but the supplied material is insufficient to approve their operational semantics.

### Check Point ClusterXL

The adapter must bind:

- physical endpoint identity;
- cluster identity;
- VSID/context where applicable;
- validated shell/context;
- exact command form and privilege boundary.

Success cannot be inferred from SSH exit status or command text alone. Post-observation must run directly against both members and validate only semantics authorized by the frozen command contract. Any `admin_down` pnote interpretation, CCP status, or state name must be parser-backed and documented.

### Palo Alto Networks HA

The adapter must distinguish:

- HTTP/TLS transport success;
- XML API envelope status;
- vendor error code/message;
- command acceptance;
- observed HA state transition.

HTTP `200` is not command success. A timeout after request transmission is ambiguous even if no response body arrives. TLS identity verification and direct observation of both peers remain mandatory.

### Convergence timing

Fixed sleeps and fixed `250 ms`/`350 ms` impact claims are not defensible.

Use bounded observation polling with:

- vendor-approved interval and maximum horizon;
- monotonic timing;
- recorded safe state classifications;
- optional consecutive stable observations when the contract requires them;
- `OUTCOME_UNKNOWN` when the horizon expires.

The adapter must never accept a caller-provided command. A minimal safe shape is a closed action such as `CONTROLLED_FAILOVER` or `RETURN_TO_SERVICE`; the adapter selects the command internally.

## 5. Safety fence, permissions, and audit

A UI checkbox or request flag asserting “lab” is not a safety fence.

The pilot fence must be server-owned and checked twice: once during authorization and again adjacent to mutation. It should bind:

- exact opaque cluster identity;
- environment classification;
- vendor and HA mode;
- permitted action;
- optional target/member or VSID scope;
- validity interval;
- approving authority.

Wildcards, display names, inferred member ordinals, and masked names must not be security identifiers. Unknown environment classification must deny execution.

`OPERATE` authorization should be enforced at the controller, service, and worker boundary. Both requester and approver need authenticated authorization, and approval must be an independent act. A requester typing another person’s identifier is not dual control.

The existing Phase B code additionally needs correction because:

- the caller-supplied assessment digest is never compared with the latest server-generated report digest;
- maintenance-window validation checks only for a nonempty string;
- token consumption is race-prone;
- token state and HMAC key disappear on restart;
- the response unnecessarily discloses the token signature;
- deterministic UUID derivation is not a substitute for a stored opaque cluster identifier.

Audit immutability requires append-only transition events, not merely an immutable Java result object. Failure to persist the pre-boundary audit must block execution. Failure after the boundary must leave the durable operation lock engaged and be treated as uncertain.

Store sanitized classifications and evidence references, not raw vendor output or operational identities.

## Required validation before approval

At minimum, test:

- concurrent consumption of one lease across multiple service replicas;
- duplicate `/execute` requests;
- two executions targeting the same cluster;
- crashes before and after every durable transition;
- crash immediately before and after socket/API transmission;
- timeout after complete and partial transport writes;
- SSH nonzero exits and ambiguous output;
- PAN HTTP success with vendor error envelope;
- one peer unreachable or identity-mismatched;
- contradictory peer observations;
- delayed convergence beyond the observation horizon;
- quarantine persistence across restart;
- reversal requiring an entirely new authorization;
- production and unknown-environment fence denial;
- masking/privacy behavior throughout audit, API, and UI.

Mock “no unsolicited packets” testing is useful but cannot validate Phase C. Release requires authorized execution against an allowlisted lab pair with direct two-peer evidence. Automated tests alone cannot advance this network-mutating behavior to `DONE`.

No tools were called, and no source, Git state, deployment, device, or production data was changed.