# OP.2.0 — Controlled HA operation / CLASS 2 execution architecture contract

## Status

**DRAFT — INDEPENDENT CHALLENGE REVIEW COMPLETE 2026-09-04; READY FOR
PRODUCT-OWNER FREEZE. Not frozen.**

Per `AGENTS.md` "Contract-status law" this document may guide investigation
and sequencing; it **authorizes no implementation**, approves no command,
and does not make `CLASS_2_OPERATIONAL_STATE_CHANGE` reachable. It becomes
implementation authority only if a product owner freezes it, and even then
only for the slices whose own prerequisites (§"Explicit blockers") are met.
The product-owner decisions of 2026-09-04 and the independent challenge
review that incorporated them are in §"Independent challenge review —
2026-09-04". The review does not freeze this contract; only the product
owner does.

`utils/action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE` keeps **no member**
while this document is `DRAFT`. Nothing here is a reason to add one.

- **Movement:** `ARCHITECTURE` (extended reasoning — new cross-subsystem
  architecture and a security boundary).
- **Design parent:** `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §2, §5–§8,
  §10, §10.1 (the `OP.2` prerequisite list and safety contract).
- **Contract parents:**
  `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
  (FROZEN WITH REAL-ENV VALIDATION GATES — its §"CLASS 2 handoff
  requirements" is the list this document answers),
  `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` (APPROVED, class 0
  reads only — the gate-package shape `OP.2.1` must mirror),
  `docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md` (the one readiness
  authority), `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` §4/§7 (the
  intent boundary and console security model),
  `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` (the repository's only
  precedent for a durable, fail-closed write ledger).
- **Gate:** this contract adds **no command**. Every vendor mutation
  primitive named anywhere in the design parent stays un-approved; the
  `OP.2.1` command-gate package (§"Implementation plan") is the only place
  one may be proposed, ten points per row, per
  `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate".
- **Project-state:** deliberately **not** recorded as a build. This is not a
  frozen build and must not become `project/roadmap.json`
  `now_next.now.build` — the same posture `OP.0b.0` took while drafting. Two
  product-owner decisions and one previously-untracked P0 gap raised by this
  session are recorded in `project/roadmap.json` `open_decisions` and
  `project/backlog.json` respectively, because those files are their
  canonical homes regardless of this document's status. Both decisions
  were resolved by the product owner on 2026-09-04 (`status: decided`).

---

## Objective

Freeze — once a product owner accepts it — the **vendor-independent**
architecture of a controlled HA operation: how an operator's typed intent
becomes exactly one authorized, confirmed, locked, verified and durably
audited state change to one operational HA entity, and what the product does
when any step is interrupted.

The deliberate split this document makes:

| Half | Content | Can it be frozen now? |
| --- | --- | --- |
| **Vendor-independent** | authorization, eligibility, freshness, confirmation binding, lock grain, lifecycle, mutation boundary, exactly-once, verification shape, unknown-outcome semantics, reversal, audit, crash recovery, UI boundary, adapter surface | **yes** — every input it needs already exists in the repository |
| **Vendor-specific** | which command/API operation, its syntax, its options, its settle behaviour, its observable postcondition, its unsupported cases | **no** — blocked on `OP.2.1`, `D-V7b`, `D-V3a`/`B2`, `D-F3`, S8-B/S8-C |

Nothing in the second column is guessed here. Where a vendor fact is
required and absent, this document says `UNKNOWN` and names the gate that
closes it.

## Why this contract exists now

Three concrete findings from the authority audit, not a general desire to
plan ahead:

1. **The per-HA-entity lock is tracked nowhere.** `OP.0b.0` §26 row `X-1`
   records it as "P0 before CLASS 2 (record now)"; it has no
   `project/backlog.json` item, no roadmap row and no design section. The
   existing coordinator lock is per **physical endpoint**
   (`utils/coordinator_backend.derive_lock_key(secret, "endpoint",
   canonical_id)`) — two members of one cluster are two endpoints, which is
   precisely the wrong grain for an action whose whole subject is the
   cluster.
2. **The design parent contradicts itself on rollback.**
   `FAILOVER_ENGINE_ARCHITECTURE.md` §5/§7/§8 require "auto-rollback on a
   failed/partial transition"; §10.1 items 5–7 require explicit human
   confirmation per action, exactly one action per authorised run, and
   `UNKNOWN` as a first-class outcome that is never a reason to re-issue.
   An automatic rollback issued after an unverifiable outcome **is a second
   CLASS 2 mutation, unconfirmed, on an unknown state.** Per `AGENTS.md`
   "Authority hierarchy" this contradiction is reported, not silently
   reconciled — see §"Reversal / failback semantics" and the new
   `op_reversal_model` decision. *Resolved by the product owner
   2026-09-04: no automatic rollback; the design parent's §10.2 records
   the reconciliation.*
3. **The existing durable job store would resolve a crash the wrong way.**
   `console/jobs.py::sweep_orphaned_running` marks a job left `running` by a
   dead process as `failed` / `console_restarted`. For a class 0 collection
   that is correct. For a class 2 action it is the single most dangerous
   possible answer: it asserts *the mutation did not happen* from evidence
   that only proves *the process died*. A CLASS 2 action registry cannot
   reuse that sweep. *Resolved by the product owner 2026-09-04: the class 0
   sweep stays valid for class 0 only; class 2 resolves to
   `OUTCOME_UNKNOWN` (`op_outcome_unknown_recovery`).*

Each is cheap to decide now and expensive to discover during implementation.

## Scope — in

- The vendor-independent execution architecture for
  `CLASS_2_OPERATIONAL_STATE_CHANGE` against **one** operational HA entity
  per authorized action.
- Authorization, eligibility and confirmation as three independent gates.
- Action lifecycle, mutation boundary, exactly-once semantics, lock model,
  crash/restart reconciliation.
- Post-action verification shape and unknown-outcome semantics.
- The typed vendor-capability adapter **boundary** (its shape, not its
  contents).
- The durable action/audit record and its privacy invariants.
- Operator Console product behaviour for a class 2 action.
- Module placement, implementation slices, acceptance criteria, blockers.
- The CP/PAN CLASS 2 readiness and blocker matrix.

## Scope — out

- **Any implementation.** No source, no test that contacts a device, no
  schema change, no registry entry, no taxonomy member.
- **Any vendor command or API operation.** `clusterXL_admin down`,
  `request high-availability state suspend` and their reversals are named in
  the design parent and remain **un-approved**; this document does not
  propose, describe the syntax of, or authorize any of them.
- Vendor mutation semantics, settle behaviour and observable postconditions
  (`OP.2.1` + real-env).
- Full enterprise RBAC design. Only the boundary is defined (§"Authorization
  boundary"); the identity/role implementation is `DEPLOY.1A`'s.
- PAN Active/Active, Check Point VSLS / per-VS execution, Load Sharing
  member evacuation, VRRP, HA4 clustering — `UNSUPPORTED`, unchanged.
- The emergency "evacuate a failing member" path. Product-owner decision
  2026-09-04: the initial CLASS 2 architecture has **no emergency bypass**
  — no path bypasses authorization, fresh preflight, confirmation or the
  operational lock. A future emergency capability requires its own
  contract and approval (`op_emergency_evac`, `OP.3` scoping).
- Numeric thresholds of any kind: no TTL, no timeout value, no continuity
  percentage, no flap threshold, no skew bound is invented here.
- Container/pod migration, SSH known-hosts enrollment/rotation, parser
  frameworks, storage redesign, pacing tuning, UI polish — none of these are
  load-bearing for this architecture.

---

## Architectural principles / decisions

Each principle is a fail-closed condition. "Should" does not appear.

### P1 — This is `OP.2`, not `OP.1`

The roadmap already assigns `OP.1` = *Failover plan compiler + dry-run*,
class 0, write-free (`project/roadmap.json` `now_next.upcoming`,
`project/backlog.json` `failover_plan_compiler`,
`FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1). The CLASS 2 execution stage is
`OP.2`. This document is therefore **`OP.2.0`** — the architecture contract
for that stage — mirroring the existing `OP.0b.0` (evidence contract) →
`OP.0b.1` (command gate) pair. It does not rename, re-scope or absorb
`OP.1`, and it does not require `OP.1` to ship first: a plan compiler that
executes nothing is a valuable but independent surface, and this contract's
`ActionPlan` (§"Vendor-adapter contract") is the same object `OP.1` would
compile in dry-run mode.

### P2 — Authorization is a separate, prior, fail-closed gate

`authorize(actor, action_type, operational_entity_id) → PERMIT | DENY` is
evaluated **before** entity resolution and **before** any device contact.
Default is `DENY`. Until `DEPLOY.1A`'s OIDC boundary and `OPERATE` role
exist, the implementation of this boundary returns `DENY` **unconditionally**
for every class 2 action type.

This is the load-bearing productization consequence of the whole contract:
`OP.2.A` and `OP.2.B` (§"Implementation plan") can be built, tested and
merged **before** `DEPLOY.1A`, because with an unconditional-`DENY`
authorization gate and no vendor adapter in existence, CLASS 2 remains
structurally unreachable exactly as it is today. Only `OP.2.C` — the first
adapter, the first real mutation — needs the full `FAILOVER_ENGINE_
ARCHITECTURE.md` §10 prerequisite set.

Authorization before device contact is itself a security property worth
freezing: an unauthorized actor must not be able to cause a production
firewall to be read.

### P3 — Readiness ≠ eligibility ≠ authorization

Three independent questions, three independent gates, evaluated in this
order, all of which must pass:

| Gate | Question | Authority | Failure |
| --- | --- | --- | --- |
| **Authorization** | may *this actor* request *this action type* on *this entity*? | `DEPLOY.1A` OIDC/RBAC (`OPERATE` role) | `DENY` — no device contact occurs |
| **Readiness** | does current evidence support the operation? | the one canonical backend readiness authority (`utils.failover.compute_ha_readiness` → `_verdict_for`) | a non-positive verdict |
| **Eligibility** | is *this specific proposed action* admissible against *this specific fresh readiness result*? | this contract (§"Correctness contract") | `NOT_ELIGIBLE` with a reason code |

A positive readiness verdict **never** authorizes execution
(`AGENTS.md` evidence laws: "Readiness != authorization"). Eligibility is
narrower than readiness: readiness answers a question about the entity,
eligibility answers a question about a proposed action on that entity in
this workflow at this moment.

The readiness authority is consumed **as a projection** — this contract adds
no second verdict engine, no second check set, and no reinterpretation of
any check. It binds to "the current canonical check set", not to a fixed
arity: `OP.0b.0` §"Seven-check model review" already mandates a future
4-prerequisite/8-check `securityexpert-ha-readiness-v2` shape, and this
contract must survive that transition without amendment.

### P4 — Same-workflow preflight generation; no TTL

Execution eligibility may consume **only** a preflight generated inside the
same action workflow: a `PreflightSnapshot` whose `preflight_run_id` was
produced by this `action_id`'s own preflight stage and whose
`operational_entity_id` equals the action's target.

Explicitly not eligible as an authorization input: a rendered report, stored
telemetry, a persisted historical readiness record, a cached
`PreflightSnapshot`, another action's preflight, or a preflight for a
different entity. This preserves the established readiness model
(invocation-scoped fresh preflight, no persisted `PreflightSnapshot`, no
invented TTL) rather than reopening it.

**`D-F1` is avoided, not solved.** No numeric expiry is introduced. Freshness
is guaranteed *structurally*: the preflight, the eligibility evaluation, the
proposal, the confirmation and the lock acquisition all belong to one
action workflow, and any departure from that workflow discards the
preflight generation. Concretely — if the process restarts at any point
before `EXECUTING`, or the action leaves its workflow for any reason before
the boundary (cancellation, refusal, restart), the preflight generation is
discarded and the action is terminated pre-mutation. There is no
re-preflight *inside* an action: a new action runs a new preflight.

Category-C configuration-intent facts keep their existing status: recorded
with their own provenance, never a check input, never an eligibility input
(`OP.0b.0` AC-4, disclosed as `configuration_intent_freshness:
not_evaluable:D-F1`). This contract does not change that and does not need
`D-F1` closed.

### P5 — Confirmation binds to one specific proposal

Confirmation is not "the operator pressed yes"; it is "the operator accepted
*this* proposal". A `proposal_digest` is computed over exactly:

```
action_id
action_type
operational_entity_id            (opaque unit id, not a hostname/address)
intended_postcondition           (typed, vendor-neutral)
subject_member_token             (opaque token of the member the adapter
                                  selected as the mutation subject)
preflight_generation_id
eligibility_result               (verdict + reason codes + check statuses)
material_action_parameters       (typed; adapter-declared, bounded set)
```

The digest is shown to the operator with the proposal and returned with the
confirmation; the server recomputes it and refuses a mismatch. Bound facts
are immutable once the proposal exists — they are computed from one frozen
preflight generation — so a mismatch can only mean a stale or altered
client: the confirmation is refused and the record stays in
`AWAITING_CONFIRMATION`. There is no re-preflight transition; an operator
who wants fresh evidence cancels and creates a new action.

This is a content digest for binding, **not** a signature: the repository
has an established precedent for exactly this (`group_id` =
`sha256(CMA + sorted VIP set)[:16]`; `derive_lock_key`'s domain-separated
HMAC) and no requirement anywhere for non-repudiation of an operator action
beyond the audit record. Cryptographic signing is **not** introduced.

The confirmation gate consults one **approval policy boundary** —
`approval_policy(action) → required approvals` — and nothing else decides
how many confirmations, from whom, under what conditions. What that policy
requires in production (one approver, a second approver, role
combinations, a maintenance-window requirement for `planned` intent, a
change ticket reference) is a **deployment/release policy decision**, not
architecture: product-owner decision 2026-09-04, `op_four_eyes` is not an
architecture freeze blocker. The architecture supports the boundary and
builds no generic quorum framework; the initial implementation of the
boundary is "one confirmation by the requesting operator" with no
configuration surface. The maintenance-window and change-reference
requirements that `FAILOVER_ENGINE_ARCHITECTURE.md` §8 and the `OP.0b.0`
CLASS 2 handoff list name live here, as policy inputs of this one gate —
they are not eligibility inputs and are not silently dropped.

### P6 — The mutation boundary is a durable state commit, not exception handling

There is exactly one point in the architecture where "definitely has not
happened" becomes "may have happened", and it is a **durable write**, not a
`try` block:

```
   ... LOCKED ...
        │
        │  (1) durably commit: state = EXECUTING,
        │      mutation_boundary_crossed = YES
        │  ────────────────── MUTATION BOUNDARY ──────────────────
        │  (2) adapter.execute_once(...)  — exactly one submission attempt
        ▼
   ... EXECUTING ...
```

`mutation_boundary_crossed` is a two-valued durable field (`NO` | `YES`)
written *before* the submission is attempted. It is deliberately never
`UNKNOWN`: a process that dies between the commit and the submission is
recorded as `YES` — the conservative direction — because the alternative
(recording `NO` and later discovering otherwise) is unrecoverable.
Product-owner decision 2026-09-04: that false-positive uncertainty is
preferable to blind replay; no automatic mutation retry exists after this
boundary.

The commit is a **guarded transition**: one durable compare-and-set from
`AWAITING_CONFIRMATION` to `EXECUTING` that succeeds for exactly one
caller. A confirmation that finds the record in any other state — a
cancellation that landed first, a second confirmation, a record already
reconciled after a restart — returns the existing record and submits
nothing. Only the caller whose guarded transition succeeded calls
`execute_once`, exactly once. This single conditional write is the
exactly-once mechanism; nothing else in the design needs to be clever.

Immediately before the commit, inside the same coordinator admission the
submission will use (P8), the adapter re-observes the plan's **subject
precondition** — that the member it selected still holds the role the plan
assumed — with the already-approved class 0 battery. A precondition that
no longer holds, or cannot be read, ends the action
`ABORTED_PRE_MUTATION:precondition_changed` without crossing the boundary.
The human wait in `AWAITING_CONFIRMATION` is unbounded by design; this
check is what stops a mutation being sent to a member whose role changed
while the operator deliberated. The residual window between the check and
the commit is milliseconds and is accepted: no vendor offers an atomic
check-and-act.

No generic exception handler, retry decorator, transport wrapper or
framework-level error mapping may sit across this boundary. An adapter may
raise only two distinguishable outcome families:
`SUBMISSION_NOT_SENT` (the adapter proved the operation never left the
product — e.g. the session was not established) and everything else, which
is `SUBMISSION_OUTCOME_UNKNOWN` until verification says otherwise. A
transport timeout after submission is **not** evidence that the mutation did
not execute.

### P7 — Exactly one submission per `action_id`; no blind retry

One authorized, confirmed action submits **at most one** mutation. The
durable `action_id` is the idempotency subject:

- A duplicate request carrying an existing `action_id` (browser retry,
  double-submit, reverse-proxy replay) returns the existing action record.
  It never creates a second action and never causes a second submission.
- A request with no `action_id` creates a new action; a new action against
  an entity that already has a non-terminal action is refused by the lock
  (§P8), not silently queued.
- **Pre-mutation** failures (`mutation_boundary_crossed = NO`) are safe: the
  action terminates `ABORTED_PRE_MUTATION`, and the operator may create a
  *new* action, which runs a *new* preflight. This is a new workflow, not a
  retry of the old one.
- **Post-submission** outcomes are never replayed, under any circumstance,
  by any component, automatically or on operator request. Reconciliation is
  by observation (§P9), never by re-issuing.

`FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 item 6 is restated, not weakened:
retry safety would need its own per-vendor proof and none exists.

### P8 — The lock is on the operational HA entity

The lock subject is the **operational HA entity** — never a member, a
management address, an SSH endpoint, an API session, a browser session or an
`entity_id` row. Two levels, one owner each, no distributed consensus:

| Level | What it is | Purpose | Held |
| --- | --- | --- | --- |
| **outer — the HA-entity lock** | **the action record itself**: the durable store admits at most one record per `operational_entity_id` that is non-terminal *or* an unacknowledged `OUTCOME_UNKNOWN`, enforced by a create-time uniqueness check with the same create-race handling `ConsoleJobStore.submit` already uses | at most one class 2 action per HA entity; quarantine after an unknown outcome | from `CREATED` until the record is terminal — and, for `OUTCOME_UNKNOWN`, until it is acknowledged |
| **inner — member admission** | the existing coordinator admission (`CollectionCoordinator.admit_request` over the entity's member `canonical_id`s: the per-endpoint lease plus the vendor budget), taken and released **per device-contact stage** exactly like any other coordinator job | no concurrent collection on any member while the action is reading or mutating | stage 1: the preflight; stage 2: precondition re-observation + submission + verification. **Never across the human wait.** |

No new lock mechanism, key domain or table is introduced: the outer lock is
a uniqueness rule over records that must exist anyway, and the inner one is
the coordinator as it stands. The independent review (§"Independent
challenge review — 2026-09-04") replaced the earlier two-lock model for two
load-bearing reasons. First, the existing lease is a *connection-lifetime*
advisory lock (`DEV.3.2`: released by the server itself when the holder's
connection dies, no TTL, no heartbeat; the in-memory backend dies with the
process) — "the lock is retained on crash" is a property no lease can
provide, so quarantine cannot live in a lock. Second, coordinator admission
takes the per-vendor budget (capacity 1) for the job's lifetime: an
admission held across an unbounded human confirmation wait would block
every collection of that vendor in the deployment, not just the two
members, for as long as the operator deliberates — and no TTL may be
invented to bound it.

Current entity examples: the CP ClusterXL cluster (`group_id`), and the CP
VSX **physical** cluster parent. VSIDs are subordinate contexts and are
never lock subjects. PAN pairs are not lock subjects while their operational
identity is unresolved (§"Identity invariants").

Concurrency behaviour:

- **Same entity, second action:** refused at creation —
  `entity_action_in_flight` (or `entity_quarantined`); no record is
  created. Not queued — a queued class 2 action would execute against a
  preflight it did not generate, violating P4.
- **Same entity, collection during a device-contact stage:** the inner
  admission refuses it, with the existing coalescing behaviour.
- **Same entity, collection between stages** (while the action awaits
  confirmation): permitted. A class 0 read cannot change the entity; the
  only thing that can — another class 2 action — is excluded by the outer
  lock. The preflight generation stays authoritative for this action
  because nothing that ran could have mutated what it observed.
- **Action needs stage 2 while a collection holds a member:** refused
  pre-boundary, `ABORTED_PRE_MUTATION:member_busy`. The action never waits
  for admission.
- **Different entities:** independent; no global action lock exists.

**Crash:** the outer lock is the record, so reconciliation decides it.
A pre-boundary record is terminated and thereby released; a post-boundary
record is terminated `OUTCOME_UNKNOWN` and thereby *keeps* the entity
quarantined (P10). The inner admission is gone with the process either
way, which is correct: no device contact survives a crash.

### P9 — Command success is not action success

Post-action verification is an **independent fresh observation** of the
intended postcondition, produced by the existing read-only preflight
evidence layer under a **new** `preflight_run_id` — never the pre-action
one, never the adapter's own return value, never the transport's exit code,
never an HTTP status.

An adapter that reports its submission accepted has said nothing about the
entity's state. Only an observation says that.

### P10 — `OUTCOME_UNKNOWN` is first-class, terminal, and quarantining

`OUTCOME_UNKNOWN` is a real outcome, not a degraded `FAILED`. It means: the
mutation boundary was crossed and the product cannot independently establish
the entity's postcondition.

On `OUTCOME_UNKNOWN` the operational HA entity is **quarantined**.
Quarantine has exactly one owner: it is a **derived predicate over the
action record store** — *an entity is quarantined iff a record for it is
`OUTCOME_UNKNOWN` with `acknowledged_at` unset*. It is not a lock state,
not an entity table, not a member of the lifecycle enum, and nothing
duplicates it. Consequences:

- No new class 2 action on that entity can be created (the outer-lock
  uniqueness rule, P8, refuses it with `entity_quarantined`).
- Class 0 reads on that entity stay permitted — they are how a human finds
  out what happened.
- A restarted process cannot lift it: reconciliation never rewrites a
  terminal state and never sets `acknowledged_at`; the predicate is
  re-derived from durable records every time it is consulted.
- The quarantine is lifted only by an explicit, audited operator
  acknowledgement of the specific action record, which sets
  `acknowledged_at` / `acknowledged_by` on that record. Acknowledgement is
  itself an authorized operation through the one `authorize()` boundary
  (action type `acknowledge_unknown_outcome`); while authorization is
  unconditional `DENY` it is unreachable, which is consistent because
  `OUTCOME_UNKNOWN` is unreachable too. It is never lifted by a timer, a
  restart, a successful read, or a subsequent green readiness verdict.

A subsequent operator-initiated class 0 reconciliation read appends a
`post_hoc_observation` to the action's audit record. It **never** rewrites
the terminal lifecycle state: terminal is terminal, and an action that could
not be verified stays recorded as one.

### P11 — No generic cross-vendor mutation primitive

Execution is reached only through a typed, per-vendor capability adapter
(§"Vendor-adapter contract"). There is no generic `execute(command)`,
`execute_shell(...)`, `api_call(...)` or vendor-operation passthrough
anywhere above the adapter, and no adapter is generalized before one vendor
is proven in a real environment (§10.1 item 9, unchanged).

Unsupported capability is **explicit and fail-closed**: an adapter that
cannot serve `(entity_kind, action_type, evidence)` returns `UNSUPPORTED`
with a reason. Absence of a capability is never treated as permission to try
something adjacent.

### P12 — Reversal is a new typed action; there is no automatic rollback

Failback/reversal is a **new** class 2 action with its own `action_id`,
authorization, fresh preflight, eligibility, proposal, confirmation, lock,
single submission, verification and audit record. It is linked to its
predecessor by `reverses_action_id` and by nothing else.

Automatic rollback — including the design parent's §5/§7/§8 "auto-rollback
on a failed/partial transition" — is **removed from this model**, and
`FailoverOutcome.FAILED_ROLLED_BACK` (§6) is correspondingly not a state
here. The reason is P6/P10: the situation in which an auto-rollback would
fire is precisely the situation in which the entity's state is unknown, and
issuing an unconfirmed second mutation against an unknown state is the worst
available action. This was a **reported contradiction with the design
parent**, raised for product-owner resolution as `op_reversal_model`;
**resolved 2026-09-04 in favour of this principle**, and
`FAILOVER_ENGINE_ARCHITECTURE.md` §10.2 now records the supersession of
its §1–§8 auto-rollback text.

If a vendor is later proven to offer a true transactional rollback (one
operation whose failure atomically restores the prior state), that is a
different capability and gets its own contract and its own gate. None is
known today.

### P13 — Audit is durable, append-only and identity-lean

One durable record per action, written before it can act and appended to at
every transition (§"Audit / evidence model"). No credentials, no raw device
output, no command text, no management address, no unsanitized identity.

### P14 — The console projects; it never decides

The Operator Console submits **typed intent** against a closed server-side
registry and projects server-computed state. It builds no command, computes
no readiness, resolves no execution target of its own, and never infers
success from an HTTP status (§"UI / application boundary"). This is the
existing `CON.x` intent boundary, unchanged and unweakened — a class 2
action is a stricter user of it, never an exception to it.

### P15 — Module placement preserves the tested absence

`utils/failover/` stays exactly what it is: read-only assessment and
evidence, with `tests/test_architecture_convergence.py::
test_the_failover_package_still_contains_no_executor` asserting its exact
module set. **This contract does not widen that allowlist.**

The class 2 execution plane is a new package, `utils/operate/`, carrying its
own convergence assertion from its first commit: it contains no vendor
adapter, imports no transport/collector module, and holds no command text,
until `OP.2.C`'s own gate is cleared. Rationale is the repository's own
`remove_dormant_remote_cleanup` precedent — dormant write-capable code is a
standing liability — applied to the boundary rather than around it.

### P16 — One vendor first, and it is Check Point ClusterXL

`FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 item 9 and `op_aa_vsls_scope`
already require one vendor proven before any abstraction is generalized.
The evidence (§"CP / PAN blocker matrix") makes the choice, not preference:
CP ClusterXL is the only operational entity in the estate whose **identity**
and **fresh preflight** are both real-environment validated. PAN must not be
a class 2 target while `B2` is `NOT ESTABLISHED`.

### P17 — Identity law preserved verbatim

Physical/evidence identity ≠ operational HA identity. Transport endpoint ≠
operational HA entity. Logical context (CP VSID, PAN VSYS) ≠ physical
failover entity. Presentation identity is never an action target. All
identifiers stay opaque — no numeric coercion, no leading-zero
normalization, no formatting-similarity equality (`AGENTS.md` "Identity law
— identifiers are opaque"). Unchanged, not reopened.

### P18 — Privacy invariants are inherited, not restated per-surface

`PRIVACY_AND_DATA_HANDLING.md` and the repository privacy gate govern every
artifact this contract creates. The one addition specific to class 2 is
§"Privacy invariants" below: **command text never exists above the adapter
boundary**, which makes a command string structurally unable to reach an
audit record, a payload, a report or a support bundle.

---

## Core product flow

```
operator typed intent  (action_type + operational_entity_id + reason)
        │
        ▼
  AUTHORIZATION            actor may request this action type on this entity
        │                  fail-closed DENY; no device contact before PERMIT
        ▼
  ENTITY RESOLUTION        typed id → one operational HA entity
        │                  (never a member, address, session or label)
        ▼
  ACTION RECORD            durable create = the HA-entity lock; refused if
  (= HA-ENTITY LOCK)       the entity has a non-terminal or unacknowledged
        │                  action; held until terminal
        ▼
  MEMBER ADMISSION 1       existing coordinator admission over both
        │                  members, for the preflight only
        ▼
  FRESH SAME-WORKFLOW      the action's own preflight run; class 0 only
  PREFLIGHT                new preflight_run_id, both members, coherent
        │
        ▼
  READINESS                the one canonical authority, consumed as a
        │                  projection; no second verdict engine
        ▼
  ELIGIBILITY              this proposed action, against this fresh result
        │                  NOT_ELIGIBLE is terminal and reasoned
        ▼
  PROPOSED TYPED           ActionPlan: typed intent + intended postcondition
  ACTION PLAN              + impact disclosure + reversal note; no command
        │
        ▼
  EXPLICIT HUMAN           confirmation bound to proposal_digest;
  CONFIRMATION             approval policy boundary decides who/how many
        │                  (member admission NOT held during this wait)
        ▼
  MEMBER ADMISSION 2       coordinator admission over both members again;
        │                  refused → ABORTED_PRE_MUTATION:member_busy
        ▼
  PRECONDITION             adapter re-observes the subject member's role
  RE-OBSERVATION           (approved class 0 battery); changed → abort
        │
        ▼
  ══ guarded durable commit: AWAITING_CONFIRMATION → EXECUTING, ══
  ══ mutation_boundary_crossed = YES — exactly one winner          ══
        │
        ▼
  EXECUTION COORDINATOR    exactly one submission attempt, no retry
        │
        ▼
  VENDOR CAPABILITY        the only holder of vendor command/API text
  ADAPTER                  execute_once(...)
        │
        ▼
  POST-ACTION              independent fresh class 0 observation,
  VERIFICATION             new preflight_run_id
        │
        ▼
  DURABLE AUDIT RESULT     append-only, identity-lean
        │
        ▼
  FINAL ACTION OUTCOME     SUCCEEDED | FAILED_NO_CHANGE | OUTCOME_UNKNOWN
```

**Lock placement note.** The HA-entity lock (the record) exists *before*
the preflight, not after eligibility: a preflight is the evidence the whole
decision rests on, and evidence collected while another action could be
mutating the same entity is not evidence. Member admission, by contrast, is
held only while the action is actually touching devices — the preflight,
and the precondition-submission-verification stage — never across the
human wait (P8). This is one deviation from the flow sketch in the
session brief, and it is deliberate.

Never permitted anywhere in this flow: arbitrary shell, arbitrary API
operation, raw command text originating in or passing through the browser,
UI-side execution logic, UI-side target selection, or a device contact
before `PERMIT`.

---

## Action / command surface boundary

| Surface | May hold | May never hold |
| --- | --- | --- |
| Browser / console UI | `action_type` (from a closed registry), `operational_entity_id` (must already exist server-side), `proposal_digest`, bounded reason text | any command, flag, argv fragment, path, address, credential, vendor operation, or free-form argument that reaches a device |
| Console HTTP layer | the same, validated | argv construction, target inference, readiness computation |
| Action coordinator (`utils/operate/`) | typed intent, typed plan, lifecycle, locks, audit | vendor command text, transport, vendor API knowledge |
| Vendor capability adapter | **the only place** vendor command/API text exists | anything unrelated to the one capability it declares |

A class 2 `action_type` may appear in `console/registry.py` only after every
`FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite is met.
`tests/test_architecture_convergence.py::
test_no_console_job_type_is_class_2_or_above` is the gate and stays green
until then.

---

## Correctness contract

An action is **eligible** only if all of the following hold simultaneously,
evaluated in one pass over one coherent preflight generation:

1. Authorization returned `PERMIT` for `(actor, action_type, entity)`.
2. The entity resolved to exactly one supported operational HA entity, whose
   mode is determined and is one the contract supports.
3. At creation, no other record for the entity was non-terminal or an
   unacknowledged `OUTCOME_UNKNOWN` — the outer lock / quarantine predicate
   (P8, P10) — and this record is that entity's one live action.
4. The stage's coordinator admission over every member of the entity is
   held by this action for the duration of the device contact.
5. The preflight generation is this action's own (P4), covers **both**
   members, and is coherent by the existing `evaluate_coherence` rule (one
   `preflight_run_id` across categories D–K).
6. The canonical readiness authority returned a positive verdict for this
   entity from that generation, with the entity's `pair_identity_state` at
   the level the vendor's identity contract requires (CP: `group_id` mutual
   + both members identity-gated in-run; PAN: `B₂`).
7. The requested `action_type` is one the vendor adapter declares as a
   supported capability for this entity kind and this observed mode.
8. The adapter produced an `ActionPlan` with a determinate intended
   postcondition that the verification layer can independently observe.

Any failure is `NOT_ELIGIBLE` with a machine-readable reason code. A missing
or unreadable input is `NOT_ELIGIBLE`, never a default-permit
(`AGENTS.md` UNKNOWN / fail-closed law).

**Anti-requirement:** eligibility never re-derives, re-weights or overrides a
readiness check. If readiness and eligibility ever disagree about a fact,
that is a defect in this layer, not a tie to break.

---

## Safety contract

Restating `FAILOVER_ENGINE_ARCHITECTURE.md` §8/§10.1 only where this
contract makes it operative, and adding what it lacked:

1. Default **off**. No class 2 capability without the taxonomy member, the
   authorization `PERMIT`, and an adapter that exists.
2. A non-positive readiness verdict is not operator-overridable.
3. Exactly one action per authorized run; no multi-entity scripting; no
   batching; no fleet selection. (`C-D4`'s recommendation — one target per
   request — applies a fortiori to class 2.)
4. Only the vendor-designated safe subject is acted on; the adapter, not the
   caller, decides which member that is, from the fresh preflight.
5. Snapshot-before is the preflight; observe-after is the verification;
   there is no automatic third action between them (P12).
6. Per-HA-entity lock (the action record) held across preflight →
   verification; a collection and an action's device contact never run on
   one entity at once.
7. Every run fully audited; the shareable summary is value-free.
8. A management-plane configuration push (Panorama/MDS) is forbidden by the
   plan for the duration of an action — stated as a plan invariant, not
   enforced by this product, which has no such write capability.
9. **New:** crossing the mutation boundary is a durable commit (P6); a crash
   after it is `OUTCOME_UNKNOWN` and quarantines the entity (P10).
10. **New:** no component may replay, resume, or "complete" an action whose
    `mutation_boundary_crossed` is `YES`.
11. **New:** the boundary commit is a guarded transition with exactly one
    winner, preceded by a same-admission precondition re-observation (P6);
    the winner alone submits.
12. **New:** no emergency bypass exists. No path skips authorization,
    fresh preflight, confirmation or the operational lock (PO 2026-09-04).

---

## Privacy invariants

- The action record carries: opaque `operational_entity_id`, typed
  `action_type`, typed postcondition, lifecycle states, timestamps,
  reason codes, `preflight_run_id`s, actor reference, `proposal_digest`.
- It carries **no** credential, token, management address, HA/control-link
  address, host-key material, serial in raw form, hostname beyond the
  already-opaque unit id, raw device output, or **command text of any kind**.
- Command text cannot reach the record structurally: it does not exist above
  the adapter boundary (P18), so there is nothing to redact.
- Operator reason text is mandatory, bounded, filtered through the existing
  redaction registry before persistence, and excluded from the support
  bundle — the `C-D6` recommendation, applied here because a class 2 audit
  trail without an operator's stated reason is a list of timestamps.
- Serials and comparable identities persist only as one-way tokens
  (`OpaqueToken` / the established `Tokenizer` pattern) or not at all.
- Verification observations are parsed to safe scalars in-module; raw vendor
  responses are discarded (`AGENTS.md` Raw-evidence law). No raw response is
  retained to "make the audit stronger".
- Repository privacy gate stays PASS / 0.

---

## Identity invariants

- **Physical/evidence identity ≠ operational HA identity.** Facts are
  collected from members and asserted about the entity, never the reverse.
- **Transport endpoint ≠ operational HA entity.** One SSH session, one API
  context, one management IP: none of these is an action subject.
- **CP ClusterXL cluster** = the operational unit and the action target,
  keyed by `cluster_topology.group_id` (mutual by construction; role
  independent). The legacy hostname-suffix `cluster` fallback
  (`utils/merge.py:95-101`, `OP.0b.0` §26 CP-11) must be **removed from the
  failover key path** before any class 2 action can resolve an entity.
- **CP VSX physical cluster parent** = the operational failover unit and the
  action target. **VSIDs are subordinate contexts** — readiness/impact only,
  never an execution target, never a lock subject (non-VSLS estate;
  `OP.0b.0` domain invariant 9, unchanged). A VS role differing from its
  physical member's is `RELATIONSHIP_INCONSISTENT` to surface, never a
  reason to plan a per-VS action.
- **PAN VSYS** is never an operational unit.
- **PAN pair identity is not established.** The serial-keyed candidate key
  `sorted(I2_A, I2_B)` is explicitly **NOT FROZEN** (`OP.0b.0`
  §"Identity contract") pending `D-V3a` (official `peer-info/serial-num`
  semantics) and `D-V3b`/`B2` (real-pair correspondence, currently
  `MISMATCH` on one member with root cause `UNKNOWN`). The hostname-keyed
  fallback stands and hostname is mutable. **`B₂` is the minimum identity
  input to a class 2 action on a PAN pair, and `B₂` alone is still not
  authorization.**
- Identifiers stay opaque. Leading-zero normalization of the mismatching PAN
  serial is **not authorized** and must not be introduced to make `B₂` pass.

---

## Provenance requirements

Every fact that reaches an eligibility decision or a verification outcome
carries the existing `OP.0b.0` provenance envelope unchanged
(`collected_at`, `preflight_run_id`, `source_vendor`, `source_plane`,
`transport`, `source_command` in its identity-free wire form,
`shell_profile`, `physical_device_identity`, `operational_entity_id`,
`context`, `outcome`, `member_skew_ms`).

Additions specific to class 2, all of which are *references*, not copies:

| Field | Meaning |
| --- | --- |
| `action_id` | the durable, opaque action this evidence belongs to |
| `evidence_role` | `pre_action_preflight` \| `post_action_verification` \| `post_hoc_observation` |
| `preflight_generation_id` | the generation the eligibility decision consumed |

Pre-action and post-action evidence are two distinct generations and are
never rendered as one snapshot — the same rule the provenance contract
already applies to old config + fresh runtime.

---

## Authorization boundary

Defined as a boundary only; no RBAC implementation is designed here
(no repository authority requires one at this layer — `DEPLOY.1A` owns it).

```
authorize(actor_ref, action_type, operational_entity_id) -> PERMIT | DENY(reason)
```

- **One call site.** Every class 2 entry point routes through it; there is no
  second path, no "internal" bypass, no CLI exemption.
- **Fail-closed.** Any error, absence of an identity provider, unknown
  actor, unknown role mapping, or unreadable policy is `DENY`.
- **Unconditional `DENY` until `DEPLOY.1A`.** The `OPERATE` role does not
  exist yet; until it does, the boundary denies every class 2 action type.
  This is what allows `OP.2.A`/`OP.2.B` to be built safely (P2).
- **`actor_ref` is a reference, not an identity payload.** No credential, no
  token, no email/principal string beyond what the audit record's privacy
  rules permit.
- **No CLI / argv entry point exists.** `main.py` gains no class 2 mode,
  `workflow_argv()` produces nothing for it, and `console/runner.py` never
  dispatches it: the sole entry is the coordinator's single
  `create_action`, which calls `authorize()` before anything else. (This is
  stricter than `RB.x` class 1, whose gates are deliberately CLI-side —
  that asymmetry is acceptable for a ledgered backup and is not for a
  failover.) The console HTTP layer calls that one coordinator method; it
  cannot reach a stage below it.
- **No test mode, no runtime selector.** The authorizer is a constructor
  argument of the coordinator; no environment variable, flag, setting or
  code path chooses it at runtime. A `PERMIT`-returning implementation may
  exist only under `tests/`, and a source-level convergence assertion says
  so from `OP.2.A`'s first commit. "Admin" (the console's per-launch
  bearer token) means nothing to this boundary: it denies regardless.
- A `DENY` creates no action record and contacts no device; it is logged
  through the existing redacting logger.

---

## Confirmation model

| Property | Rule |
| --- | --- |
| Binds to | `proposal_digest` (P5) |
| Displayed before | the full `ActionPlan`, the eligibility line items, the intended postcondition, the impact disclosure, the reversal note |
| Invalidated by | leaving `AWAITING_CONFIRMATION` (cancel, abort, restart). A digest mismatch is *refused*, not an invalidation: the record stays where it is |
| Survives restart | **no** — a confirmation binds to a preflight generation that a restart discards (P4) |
| Batching | prohibited; one confirmation confirms one action on one entity |
| Implicit confirmation | prohibited; no default, no timeout-to-accept, no "remember this choice" |
| Approval policy | one `approval_policy(action)` boundary (P5); second approver, role combinations, maintenance window and change reference are its production inputs — deployment/release policy, not architecture (`op_four_eyes`, PO 2026-09-04) |
| Initial policy | one confirmation by the requesting operator; no configuration surface until a release policy exists |
| Cancellation | legal from `CREATED`, `PREFLIGHTING`, `AWAITING_CONFIRMATION`; **impossible** from `EXECUTING` — the guarded transition (P6) makes a confirm/cancel race resolve to exactly one of them |
| Reason text | mandatory, bounded, redaction-filtered, never in the support bundle |

---

## Locking / concurrency model

See P8 for the grain and ordering. Remaining rules:

| Situation | Behaviour |
| --- | --- |
| Second class 2 action, same entity | refused at creation (`entity_action_in_flight` / `entity_quarantined`); no record created; never queued |
| Collection request, same entity, action in a device-contact stage | refused by the inner admission (existing coalescing behaviour) |
| Collection request, same entity, action awaiting confirmation | admitted — a read cannot change the entity, and the outer lock excludes the only thing that can |
| Action needs a device-contact stage, collection in flight on a member | admission refused → `ABORTED_PRE_MUTATION:member_busy`, always pre-boundary. The action never waits for admission. |
| Failover and failback overlapping | structurally impossible — failback is a new action and the entity's outer lock (its predecessor's record) is not released until that record is terminal and, if `OUTCOME_UNKNOWN`, acknowledged |
| Different entities | fully independent |
| Lock ownership | the record's own `action_id`; there is no separate lock row to fall out of sync with it |
| Crash, `mutation_boundary_crossed = NO` | reconciliation terminates the record `ABORTED_PRE_MUTATION:process_restart`; the entity is free |
| Crash, `mutation_boundary_crossed = YES` | reconciliation terminates the record `OUTCOME_UNKNOWN`; the entity stays quarantined by that record (P10) until acknowledged |
| Durability | the record store: the existing `utils.evidence_backend` pattern (filesystem default, opt-in Postgres), the same shape `ConsoleJobBackend` uses. Nothing about the lock is held only in memory because nothing about the lock is separate from the record. |
| Coordinator topology | **exactly one class 2 coordinator process per deployment** (the console process). Reconciliation runs at its start over records it owns. A multi-worker class 2 coordinator is out of scope and would need its own contract (`per_vendor_worker_split` is P2 and unrelated). |

No distributed consensus, leader election or quorum is introduced. The
current topology (one worker process per deployment; opt-in shared Postgres
for cross-container admission, `DEV.3.2`) does not require it, and adding it
would be the largest unforced complexity in this design.

---

## Execution state machine

Deterministic, durable, and boring by intent. Four non-terminal states and
six terminal ones. A state exists here only if restart reconciliation,
cancellation legality or lock behaviour differs across its boundary;
nothing exists for observability alone (`transitions[]` in the audit
record carries that).

### States

| State | Durable across restart | Terminal | Meaning |
| --- | --- | --- | --- |
| `CREATED` | yes | no | authorized typed intent, entity resolved, record durable — **this write is the HA-entity lock**; no device has been contacted |
| `PREFLIGHTING` | yes | no | member admission held; this action's own class 0 preflight is running, then the readiness projection and eligibility are evaluated from it (a pure function, no wait, no I/O) |
| `AWAITING_CONFIRMATION` | yes | no | a bound proposal exists; member admission released; the only state with an external (human) wait |
| `EXECUTING` | yes | no | **mutation boundary crossed**; member admission held; exactly one submission attempted, then the independent fresh observation. `submission_outcome_family` records whether the adapter returned |
| `NOT_ELIGIBLE` | yes | **yes** | the preflight ran and the eligibility contract refused (non-positive readiness included — a preflight whose reads fail is `INSUFFICIENT_EVIDENCE`, therefore `NOT_ELIGIBLE`, never `ABORTED`); nothing was sent |
| `ABORTED_PRE_MUTATION` | yes | **yes** | the workflow itself could not complete before the boundary (admission refused, restart, precondition changed, adapter proved nothing was sent); nothing was sent |
| `CANCELLED` | yes | **yes** | operator-cancelled before the boundary; nothing was sent |
| `SUCCEEDED` | yes | **yes** | intended postcondition independently observed |
| `FAILED_NO_CHANGE` | yes | **yes** | submitted; fresh independent verification **positively** established that the mutation did not take effect and the entity is coherently in its original state — see §"Post-action verification" for the conditions, which are strict |
| `OUTCOME_UNKNOWN` | yes | **yes** | boundary crossed; postcondition not independently determinable |

**Removed by the independent review (2026-09-04):** `LOCKING` and
`LOCKED` (lock acquisition is a non-waiting try-acquire with no in-progress
meaning; the earlier reconciliation table already treated them identically
to `CREATED`), `EVALUATING` (a pure function over a frozen generation),
`VERIFYING` (same restart resolution, cancellation legality and lock
behaviour as `EXECUTING`), and `SUCCEEDED_WITH_WARNINGS` (no approved
policy can make it reachable while `op_continuity_tolerance` is open;
reintroducing it requires that decision and a contract amendment —
continuity observations are still *recorded*).

Every state is durable across process restart — that is what makes the
reconciliation table (§"Crash / restart recovery") decidable.

### Legal transitions

```
CREATED ──► PREFLIGHTING ──► AWAITING_CONFIRMATION
   │             │                    │
   │             ├──► NOT_ELIGIBLE     │  (precondition re-observed, then)
   │             │                    ▼
   │             └──► ABORTED_    ══ MUTATION BOUNDARY ══  guarded transition,
   │                  PRE_MUTATION         │               exactly one winner
   ├──► ABORTED_PRE_MUTATION               ▼
   │    (admission refused,           EXECUTING
   │     restart)                          │
   │                          ┌────────────┼────────────┐
   └──► CANCELLED             ▼            ▼            ▼
        (also legal from   SUCCEEDED  FAILED_NO_    OUTCOME_UNKNOWN
         PREFLIGHTING,                  CHANGE
         AWAITING_CONFIRMATION)
```

`AWAITING_CONFIRMATION` also exits to `ABORTED_PRE_MUTATION` (restart,
member admission refused for stage 2, precondition changed) and to
`CANCELLED`.

Additional rules:

- `EXECUTING → OUTCOME_UNKNOWN` covers both "the submission's outcome
  family was unknown and verification could not start" and "verification
  ran and could not establish the postcondition".
- No transition **out of** a terminal state exists. A `post_hoc_observation`
  appends to the record; it does not transition it.
- No transition re-enters `EXECUTING`. There is exactly one entry, it is
  the guarded transition, and it happens at most once per `action_id`.
- No transition re-enters `PREFLIGHTING`. An action has exactly one
  preflight generation; wanting another means a new action.
- Every transition is a conditional (from-state guarded) durable write, so
  two writers cannot both succeed on one record.

### Cancellation

Legal in every pre-mutation state. **Impossible** from `EXECUTING` —
not "discouraged", not "best-effort": the API has no route, the console has
no control, and the coordinator has no code path. A cancel that races a
confirmation is resolved by the guarded transition: whichever conditional
write lands first wins, and the other sees the record. A request to stop an
executing action is answered with the truth: the submission has been sent and
the product will report what it observes.

---

## Mutation boundary

| | Before | After |
| --- | --- | --- |
| Definition | `mutation_boundary_crossed = NO` durably | `mutation_boundary_crossed = YES` durably |
| Truth | the mutation **definitely** has not executed | the mutation **may** have executed |
| Retry | a *new* action with a *new* preflight is safe | never, by any component, for any reason |
| Cancellation | legal | impossible |
| Crash resolution | `ABORTED_PRE_MUTATION` | `OUTCOME_UNKNOWN` |
| Record on crash | terminated; the entity is free | terminated `OUTCOME_UNKNOWN`; the entity is quarantined by that record until acknowledged (P10) |
| `FAILED_NO_CHANGE` | unreachable (nothing was sent) | only by positive fresh verification under §"Post-action verification"'s strict conditions — never from a command error, a transport timeout, a crash or a missing response |
| Audit | records the attempt was not made | records the attempt was made |

The boundary is crossed by the durable state commit, not by the submission
(P6), and the commit is the guarded `AWAITING_CONFIRMATION → EXECUTING`
transition with exactly one winner. Generic exception handling must not
blur it: the coordinator maps only `SUBMISSION_NOT_SENT` back to the
pre-mutation family, and only when the adapter positively proves it (e.g.
no session was ever established). Every other failure mode stays on the
post-boundary side. Product-owner decision 2026-09-04: a crash between the
commit and the actual transmission conservatively produces
`OUTCOME_UNKNOWN`; that false positive is accepted in preference to any
replay.

---

## Exactly-once / retry semantics

| Scenario | Behaviour |
| --- | --- |
| Browser double-submit / retry with the same `action_id` | returns the existing record; no second action, no second submission |
| Browser retry with no `action_id` | new action; refused at creation if the entity already has one in flight or is quarantined |
| Reverse-proxy or client-library replay | same as above — `action_id` is the idempotency subject, not the HTTP request |
| Transport error before submission, adapter proves nothing was sent | `ABORTED_PRE_MUTATION:submission_not_sent`; a new action may be created |
| Transport timeout after submission | `OUTCOME_UNKNOWN` unless verification determines otherwise; **never** re-sent |
| Backend restart, `mutation_boundary_crossed = NO` | `ABORTED_PRE_MUTATION:process_restart` |
| Backend restart, `mutation_boundary_crossed = YES` | `OUTCOME_UNKNOWN:process_restart_after_mutation_boundary` + quarantine |
| Operator asks to "try again" after `OUTCOME_UNKNOWN` | refused while quarantined; after acknowledgement, a **new** action with a **new** preflight — which will observe the real current state and may well be a different action entirely |
| Two confirmations for one `action_id` (double-click, two tabs, two operators) | the guarded transition admits exactly one; the other returns the existing record — no second submission |
| Confirmation races a cancellation | whichever conditional write lands first wins; a confirmation that loses submits nothing |
| Confirmation arrives after a restart reconciled the record | the record is already `ABORTED_PRE_MUTATION`; the guarded transition fails; nothing is sent |
| Precondition changed during the human wait | `ABORTED_PRE_MUTATION:precondition_changed`, pre-boundary; a new action may be created |
| Transport-library retry (HTTP adapter retries, SSH reconnect-and-resend) | structurally disabled on the path `execute_once` uses and test-asserted (AC-17); the adapter never reconnects to resend |

State is never held only in memory: `action_id`, lifecycle state,
`mutation_boundary_crossed`, lock ownership and the audit record are durable
before they are relied upon.

---

## Post-action verification

Independent, fresh, class 0, new `preflight_run_id` (P9).

| Observation | Outcome |
| --- | --- |
| Intended postcondition observed, coherent, both members, no new blocking condition | `SUCCEEDED` — an observed transition is evidence regardless of settle knowledge; secondary off-nominal observations are recorded on the record, not a separate outcome |
| Original state observed, coherent, both members, **and** the capability's `settle_observation` is KNOWN (adapter-declared from real-environment evidence) and has elapsed | `FAILED_NO_CHANGE` |
| Original state observed while `settle_observation` is `UNKNOWN`, or before it has elapsed | `OUTCOME_UNKNOWN` — one read that shows no transition is not evidence that the transition will not occur; closing the action here would release the entity into an unattended failover |
| Partially changed / members disagree / `RELATIONSHIP_INCONSISTENT` | `OUTCOME_UNKNOWN` |
| One-sided (only one member observable) | `OUTCOME_UNKNOWN` — a member's report about its peer is not an observation of the peer |
| Read failed / device unreachable / session lost | `OUTCOME_UNKNOWN` — collection failure is not a known-bad state |
| Mode changed to one the contract does not support | `OUTCOME_UNKNOWN` |

**No numeric timers are invented.** How long to wait before the postcondition
is stably observable is a *per-vendor, per-capability* fact
(`settle_observation`) that the adapter declares and that is `UNKNOWN` until
real-environment evidence establishes it. The first pilot's job is to
**measure and record** it, not to assume it. While it is `UNKNOWN`, the
verification stage performs a single bounded observation using the existing
preflight command timeouts, and only two outcomes are reachable from it:
`SUCCEEDED` when the intended postcondition is positively observed, and
`OUTCOME_UNKNOWN` otherwise. **`FAILED_NO_CHANGE` is unreachable while
`settle_observation` is `UNKNOWN`** — an early "nothing changed" read is
exactly the observation a slow transition would produce.

`FAILED_NO_CHANGE` is therefore used **only** when fresh independent
verification positively establishes that the intended mutation did not take
effect and the operational state is safely understood. It never means
"the command returned an error", "the transport timed out", "the process
crashed" or "the response was missing" — each of those is
`OUTCOME_UNKNOWN` unless verification establishes otherwise under the rules
above (PO 2026-09-04).

**Continuity observations are recorded, never verdict-bearing** while
`op_continuity_tolerance` is open (PO 2026-09-04: recorded, not
independently verdict-bearing; no numeric tolerance is invented; not an
architecture freeze blocker). Session/connection continuity is a
numeric, tolerance-bearing observation; the intended postcondition is a
deterministic role/state fact. Only the latter decides `SUCCEEDED` vs
`FAILED_NO_CHANGE`. This mirrors the pattern the repository already uses for
`member_skew_ms` and for check 6 — record the fact, withhold the judgment
until the threshold is a decision rather than a guess.

---

## Unknown-outcome semantics

Fully specified in P10. Summarized:

- `OUTCOME_UNKNOWN` ≠ `FAILED`. It is the honest answer, and it is terminal.
- It quarantines the operational HA entity against further class 2 actions;
  the quarantine *is* the unacknowledged record — one owner, nothing to
  fall out of sync, nothing a restart can drop.
- Class 0 reads remain permitted — they are the recovery path.
- The quarantine lifts only by explicit, audited, authorized operator
  acknowledgement of that record.
- Process death, worker death, transport timeout and lost response after
  the boundary are never converted into `FAILED` (PO 2026-09-04); class 0's
  `sweep_orphaned_running → failed` stays valid for class 0 and is not
  inherited.
- A post-hoc observation appends to the record and never rewrites its state.
- It maps to the design parent's `FAILED_MANUAL_INTERVENTION_REQUIRED`
  (§6) but is named for what is actually known rather than for what someone
  must do about it.

---

## Reversal / failback semantics

Fully specified in P12. Summarized:

- Failback is a new typed class 2 action carrying the full gate chain: new
  authorization, fresh same-workflow preflight, new confirmation, the
  HA-entity lock, one new mutation attempt, independent verification, an
  independent audit record (PO 2026-09-04, `op_reversal_model` decided).
- No automatic rollback exists in this model; `FAILED_ROLLED_BACK` is not a
  state. `FAILOVER_ENGINE_ARCHITECTURE.md` §10.2 records the supersession.
- The `ActionPlan` must disclose, before confirmation, whether the vendor's
  configured preemption behaviour means the reversal will itself cause a
  second impact — this is disclosure, not a decision the product makes.
  (CP preemption is a management-plane read that no collector performs
  today, `OP.0b.0` §26 CP-3 / `D-V7b`; PAN preemption is a runtime field.
  Where it is `UNKNOWN`, the plan says `UNKNOWN` and the operator decides.)
- A true vendor transactional rollback, if one is ever proven, is a separate
  capability with its own contract and gate.

---

## Audit / evidence model

One durable, append-only `ha_action_record` per `action_id`, created before
the action can do anything and closed at its terminal state. It must be able
to answer, from itself alone:

| Question | Field |
| --- | --- |
| who requested | `actor_ref` (reference, privacy-filtered) |
| what typed intent | `action_type`, `intended_postcondition` |
| which operational entity | `operational_entity_id` (opaque), `entity_kind`, `vendor` |
| which preflight generation | `pre_action_preflight_run_id`, `preflight_generation_id` |
| eligibility outcome | `readiness_verdict`, `check_statuses`, `eligibility_result`, `reason_codes` |
| what was confirmed | `proposal_digest`, `confirmed_at`, `confirmations[]`, `superseded_proposals[]` |
| why | `operator_reason` (bounded, redaction-filtered, bundle-excluded) |
| member admission | `admissions[]` — one entry per device-contact stage: `stage`, `admitted_at`, `released_at`, `member_count`. The outer lock needs no field: it is this record's own existence |
| precondition re-observation | `precondition_result` (`HOLDS` \| `CHANGED` \| `UNKNOWN`), `precondition_observed_at` |
| **whether the boundary was crossed** | `mutation_boundary_crossed` (`NO` \| `YES`) + `boundary_committed_at` |
| selected vendor capability | `capability_id`, `adapter_version` — **never the command** |
| submission outcome | `submission_outcome_family` (`NOT_SENT` \| `UNKNOWN`) |
| post-verification | `post_action_preflight_run_id`, `observed_postcondition`, `continuity_observations[]` (recorded, non-verdict) |
| final lifecycle state | `state`, `terminal_reason`, `finished_at` |
| lineage / quarantine | `reverses_action_id`, `acknowledged_at`, `acknowledged_by`, `post_hoc_observations[]` |
| every transition | `transitions[]` — `(from, to, at, reason_code)` |

Forbidden by construction (the record type has no such field, so nothing can
pass one through): credentials, tokens, management addresses, HA/control-link
addresses, host-key material, raw serials, raw device output, file paths
outside the runtime root, stack traces, and **command text**.

Storage reuses the existing `utils.evidence_backend` abstraction (filesystem
default, opt-in Postgres) — the same durable-state pattern
`console/jobs.py` and `utils/recovery_operational_ledger.py` already use. No
new persistence technology is chosen.

**It does not reuse `ConsoleJobStore`.** That store's crash sweep resolves
an orphaned `running` record to `failed` (§"Why this contract exists now",
finding 3), which is the one resolution a class 2 action may never receive.
A class 2 record's crash resolution is `OUTCOME_UNKNOWN` and depends on
`mutation_boundary_crossed`, a field `JobRecord` does not have. What *may*
be reused conceptually is the `ConsoleJobBackend` storage shape (one JSON
document per record under the runtime root, an opt-in Postgres table, a
create-race-safe uniqueness lookup) and its atomic-write discipline — minus
the sweep. No new persistence technology is chosen here.

---

## Vendor-adapter contract

Conceptual surface. Names are a design decision; the boundary is not.

```
capability(entity_kind, action_type, evidence)   -> Capability | UNSUPPORTED(reason)
build_plan(entity, action_type, evidence)        -> ActionPlan
check_precondition(plan)                         -> HOLDS | CHANGED | UNKNOWN   (class 0 only;
                                                    called by the coordinator before the boundary)
execute_once(plan, action_id)                    -> SUBMISSION_NOT_SENT | SUBMISSION_OUTCOME_UNKNOWN
observe_postcondition(entity, plan)              -> Observation          (class 0 only)
```

Four typed operations plus one precondition check, each per vendor
capability. The surface is deliberately narrow so that Check Point ClusterXL
(first, `OP.2.C`) and a later PAN adapter (`OP.3`) share a *shape*, never a
forced symmetry: `capability` may say `UNSUPPORTED` for any combination,
`ActionPlan`'s material parameters are adapter-declared, and nothing above
the adapter knows what the vendor operation is.

| Rule | |
| --- | --- |
| Sole holder of vendor command/API text | the adapter module, nothing above it |
| `ActionPlan` contents | typed intent, intended postcondition, the subject member the adapter selected and why, impact disclosure, reversal/preemption note, `settle_observation` (may be `UNKNOWN`), declared material parameters. **No command string, no argv, no XML, no API path.** |
| Unsupported | explicit, reasoned, fail-closed. Never "try the closest thing." |
| Precondition | `check_precondition` uses only the already-approved class 0 battery; `CHANGED` and `UNKNOWN` both mean the coordinator does not cross the boundary |
| Submission | exactly one attempt; the adapter performs no retry, no fallback, no alternate primitive, no reconnect-and-resend; the transport path it uses has automatic retry structurally disabled (AC-17) |
| Generic primitives | none: no `execute_shell`, no arbitrary API operation, no operation name from a caller |
| Cross-vendor abstraction | none until one vendor is proven in a real environment (P16) |
| Transport | reuses the existing validated transport, identity gates, redaction and `RunContext` — no new credential path, no new network pattern (`AGENTS.md` diagnostic-path law) |
| Commands | every command/API operation an adapter issues requires an approved `OP.2.1` gate row. **This contract approves none and invents none.** |

---

## Crash / restart recovery

A restart runs one reconciliation pass over non-terminal records **before**
any coordinator or console worker starts. It never replays anything.

| Crash point | `mutation_boundary_crossed` | Resolution |
| --- | --- | --- |
| before preflight (`CREATED`) | `NO` | `ABORTED_PRE_MUTATION:process_restart`; the terminated record releases the entity |
| during preflight or evaluation (`PREFLIGHTING`) | `NO` | `ABORTED_PRE_MUTATION:process_restart`; preflight generation discarded; entity released |
| awaiting or after confirmation, before the boundary (`AWAITING_CONFIRMATION`) | `NO` | `ABORTED_PRE_MUTATION:confirmation_context_lost`; the confirmation does **not** survive, because the preflight generation it bound to cannot be revalidated post hoc; entity released |
| during submission (`EXECUTING`) | `YES` | `OUTCOME_UNKNOWN:process_restart_after_mutation_boundary`; the record keeps the entity quarantined |
| after submission, before response (`EXECUTING`) | `YES` | identical to the row above — the product cannot distinguish these two, and must not pretend it can |
| during verification (`EXECUTING`, adapter returned) | `YES` | `OUTCOME_UNKNOWN:verification_interrupted`; quarantined. Verification is not re-run on restart: a later operator-initiated class 0 read appends a `post_hoc_observation` |

Invariants:

- A restart **never** automatically replays a possibly-executed mutation.
- A restart **never** automatically resumes an action past the boundary.
- A restart **never** resolves a post-boundary record to `FAILED`.
- A restart **never** creates a new action, acquires anything, or lifts a
  quarantine: the predicate is derived from the records it just reconciled.
- Reconciliation is idempotent, runs in the single coordinator process
  before it accepts any request, and produces an audit transition of its
  own.

Persistence technology is not chosen here beyond "the existing evidence
backend abstraction" (§"Audit / evidence model").

---

## UI / application boundary

The Operator Console **must**:

- show current eligibility for the entity, including each check's status and
  reason, as a projection of the backend record;
- show the typed proposed action, its intended postcondition, its impact
  disclosure and its reversal/preemption note;
- make the confirmation boundary unmistakable — a class 2 action must never
  look like refreshing a table;
- show the in-progress lifecycle state, live from the durable record;
- show the verification result and the final outcome;
- surface `OUTCOME_UNKNOWN` and the derived entity quarantine prominently
  and honestly, with the acknowledgement path;
- show *why* an action is unavailable, in words, rather than a disabled
  control (the existing `CON.x` honest-affordance contract).

The Operator Console **must not**:

- build, hold, display or transmit a vendor command;
- compute readiness, eligibility or any check;
- choose or infer an execution target independently of the backend;
- infer success from an HTTP status, a job state, or a completed request —
  only the backend's verified outcome is an outcome;
- offer cancellation once `EXECUTING` is reached (no such control exists);
- batch, multi-select or fleet-select a class 2 action.

This is the existing intent boundary (`OPERATOR_CONSOLE_ARCHITECTURE.md` §4)
under stricter use, not a new one.

---

## Implementation plan

Few, coherent movements. Every one of them is gated by
§"Explicit blockers"; none is authorized by this document.

| Movement | Scope | Class introduced | Prerequisites | Tier |
| --- | --- | --- | --- | --- |
| **`OP.2.1`** | Mutation command-gate package: ten points per candidate vendor primitive and its reversal, mirroring `OP.0b.1`'s structure. **Docs only.** Requires official vendor documentation for each primitive's semantics, options and observable postcondition. | none | `D-V7b` (CP) and `D-V3a` (PAN) research paths; official-source access | extended (security boundary) |
| **`OP.2.A`** | Typed action model, the four-plus-six-state lifecycle, durable action/audit record, `authorize()` boundary returning unconditional `DENY`, the `approval_policy` boundary in its initial one-confirmation form. Pure + storage; **zero device I/O**; no adapter, no transport, no command. New package `utils/operate/` carrying its convergence assertions from the first commit: no vendor adapter, no transport/collector import, no command text, no `PERMIT`-returning authorizer outside `tests/`, no argv/CLI entry point. | none — CLASS 2 stays memberless | this contract frozen | normal |
| **`OP.2.B`** | Action coordinator: the record-as-HA-entity-lock uniqueness rule, per-stage member admission through the existing coordinator, idempotency on `action_id`, the guarded boundary transition, crash reconciliation, the derived quarantine predicate and the acknowledgement path. Still structurally `DENY`; no adapter, no mutation capability, no device contact. | none | `OP.2.A`; closes `ha_entity_operational_lock` | extended (concurrency + safety boundary) |
| **`OP.2.C`** | First vendor capability adapter (**CP ClusterXL**): `capability`/`build_plan`/`execute_once`/`observe_postcondition`; post-action verification wiring. **This is where CLASS 2 gains its first member.** | **CLASS 2** | every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite; `OP.2.1` approved; `DEPLOY.1A` OIDC + `OPERATE`; `D-V7b`, `D-F3`; `cp_production_ssh_host_key_trust_hardening`; signed change-management review | extended |
| **`OP.2.D`** | Operator Console class 2 workflow (proposal → confirmation → lifecycle → outcome → acknowledgement) **and** the bounded real-environment single-vendor pilot on the approved CP ClusterXL pair. | — | `OP.2.C`; `C-D4`, `C-D6`; real-env procedure | normal (UI) / normal (real-env) |

**Order (PO 2026-09-04, confirmed by the independent review):** `OP.2.A`
→ `OP.2.B`, with `OP.2.1` (the CP mutation command/API gate — vendor
semantics, official documentation, PO approval) running **in parallel**
with them and required only before `OP.2.C`. `A`/`B` consume nothing from
the command gate: the adapter surface is typed and narrow, `B` contains no
adapter at all, and the only thing `OP.2.1` can change is the declared
content of a plan's material parameters and its settle behaviour — neither
of which the coordinator interprets. Blocking `A`/`B` on `OP.2.1` would
serialise pure work behind official-source research for no safety gain.
`OP.2.C` is the first point at which a CLASS 2 capability could exist and
stays blocked by the authorization and readiness prerequisites; `OP.2.D` is
the console flow and the controlled real-environment pilot.

PAN is deliberately **not** a movement here (PO 2026-09-04: not an initial
CLASS 2 target). It becomes one only after `B₂` is established, `D-V3a`
closes, S8-C passes, and `OP.2.D`'s pilot proves the model on one vendor —
i.e. it is `OP.3` work, consistent with `op_aa_vsls_scope`'s
recommendation and §10.1 item 9. No generic cross-vendor implementation is
forced now. Active/Active and VSLS semantics are outside the initial scope
and no VSLS assumption may enter the initial CP adapter.

---

## Acceptance criteria

Bars for the implementation movements, evaluated against this contract once
frozen — not conditions on freezing it.

- **AC-1** `authorize()` has exactly one call site per entry point, defaults
  to `DENY`, and returns `DENY` unconditionally while `DEPLOY.1A` is absent.
  Proven over a generated matrix, not by inspection.
- **AC-2** A positive readiness verdict alone cannot cause the guarded
  boundary transition. Proven by a test that supplies a positive verdict
  with authorization denied (no record is created) and with confirmation
  absent (the action stays in `AWAITING_CONFIRMATION`).
- **AC-3** Eligibility consumes only a preflight generation produced by the
  same `action_id`; a stored snapshot, a report, stored telemetry or another
  action's generation is refused. No TTL constant exists in the code.
- **AC-4** `proposal_digest` mismatch refuses the confirmation; mutating any
  bound field changes the digest. Proven per bound field.
- **AC-5** `mutation_boundary_crossed` is durably `YES` before any adapter
  submission is attempted, proven by a test that kills the flow between the
  commit and the submission and asserts the reconciled state is
  `OUTCOME_UNKNOWN`.
- **AC-6** No code path transitions out of a terminal state; no code path
  re-enters `EXECUTING`; no code path replays a `YES`-boundary action.
  Enforced over the generated transition matrix.
- **AC-7** Crash reconciliation matches the §"Crash / restart recovery"
  table exactly, per row.
- **AC-8** The HA-entity lock is the record-uniqueness rule keyed on the
  operational entity, never a member or endpoint; a second action on the
  same entity is refused at creation, including under a create race; a
  crash with `YES` leaves the entity quarantined by the reconciled record,
  and a restart cannot create a new action on it until acknowledgement.
  Member admission is held only during a device-contact stage — proven by
  a test that admits a collection on a member while an action awaits
  confirmation, and refuses it while the action is in `PREFLIGHTING` or
  `EXECUTING`.
- **AC-9** Verification uses a `preflight_run_id` distinct from the
  pre-action one; a one-sided or failed observation yields
  `OUTCOME_UNKNOWN`, never `SUCCEEDED` and never `FAILED_NO_CHANGE`.
- **AC-10** No command string exists in `utils/operate/`, in any action
  record, in any console payload, in any report payload, or in the support
  bundle. Proven by a source-level and an artifact-level assertion.
- **AC-11** `utils/failover/`'s module allowlist is unchanged;
  `utils/operate/` contains no vendor adapter and imports no transport
  module until `OP.2.C`.
- **AC-12** `CLASS_2_OPERATIONAL_STATE_CHANGE` gains no member and
  `console/registry.py` gains no class ≥ 2 job type before `OP.2.C`'s full
  prerequisite set is met.
- **AC-13** Repository privacy gate PASS / 0; no raw identity in any action
  artifact; operator reason redaction-filtered and bundle-excluded.
- **AC-14** Automatic rollback exists nowhere: no code path issues a second
  mutation without its own authorization, preflight and confirmation.
- **AC-15** The boundary commit is a guarded transition: two concurrent
  confirmations, and a confirmation racing a cancellation, yield exactly
  one winner and at most one `execute_once` call — proven with real
  concurrency, not by inspection.
- **AC-16** No argv/CLI entry point, no `workflow_argv()` output and no
  `console/runner.py` dispatch exists for a class 2 action; no module
  outside `tests/` defines a `PERMIT`-returning authorizer; the coordinator
  has no runtime authorizer selector. Source-level assertions.
- **AC-17** The transport path `execute_once` uses has automatic retry and
  reconnect structurally disabled (HTTP adapter retries zero; no SSH
  reconnect-and-resend), asserted by test in `OP.2.C`.
- **AC-18** `FAILED_NO_CHANGE` is unreachable while the capability's
  `settle_observation` is `UNKNOWN`; `SUCCEEDED_WITH_WARNINGS` does not
  exist as a state; continuity observations are recorded and never decide
  an outcome. Proven over a generated verification matrix.
- **AC-19** `check_precondition` returning `CHANGED` or `UNKNOWN` ends the
  action `ABORTED_PRE_MUTATION` with `mutation_boundary_crossed = NO` and
  no `execute_once` call.

---

## Validation / merge gate

For this document (architecture/docs + project metadata only):

- architecture convergence (`tests/test_architecture_convergence.py`) —
  including project-state consistency (`metadata_warnings == []`) and the
  build-history index check;
- repository privacy gate (`py .\main.py --repository-privacy-check`);
- `git diff --check`;
- full regression **not required** — no product code changes.

For the implementation movements: the standard ladder (targeted → subsystem
→ full serial regression), the privacy gate, the render harness whenever a
payload or UI file changes, and — for `OP.2.C`/`OP.2.D` — real-environment
validation as a separate gate that automated tests never substitute for.

---

## Risks

| Risk | Mitigation in this contract |
| --- | --- |
| The design parent's auto-rollback text is implemented by a future session that did not read §10.1 | P12 removes it from the model and raises `op_reversal_model` for explicit PO resolution |
| A future implementer reuses `ConsoleJobStore` for class 2 records | §"Audit / evidence model" names the specific incompatible behaviour and forbids the reuse |
| A future implementer reuses the per-endpoint coordinator lock as "the lock" | P8 specifies two levels explicitly, with the new domain-separated key |
| `OUTCOME_UNKNOWN` gets quietly collapsed into `FAILED` because it is awkward to render | P10 + AC-9 + the console must-surface rule |
| A numeric TTL or settle timer gets invented to make the flow feel complete | P4 and §"Post-action verification" forbid it; AC-3 tests for its absence |
| PAN is made a class 2 target because its preflight looks green | P16 + §"Identity invariants" + the blocker matrix; `B₂` is a hard prerequisite |
| `OP.2.A`/`OP.2.B` are read as making CLASS 2 reachable | P2's unconditional `DENY` + AC-12 |
| The vendor-independent freeze is treated as approving a vendor command | §"Scope — out"; `OP.2.1` is the only place a command may be proposed |
| An early "nothing changed" verification read closes the action as `FAILED_NO_CHANGE` and releases an entity that then fails over unattended | §"Post-action verification": `FAILED_NO_CHANGE` unreachable while `settle_observation` is `UNKNOWN`; AC-18 |
| Member admission is held across the human wait and starves the vendor budget | P8: admission per device-contact stage only; AC-8 |
| Two writers (double confirmation, confirm/cancel race, stale process) submit twice | P6 guarded transition; AC-15 |

---

## Rollback semantics

*(document lifecycle, not device behaviour — device reversal is
§"Reversal / failback semantics")*

Documentation and project metadata only; nothing to roll back operationally.
If this contract is superseded, mark its status and name the superseding
path; never delete. The three project-metadata additions this session makes
(one backlog item, two open decisions) are independently valid regardless of
whether this contract is ever frozen — they record a gap and two
contradictions that exist in the repository either way.

---

## Definition of done (for a future FROZEN version)

1. Every §"Required structure" section present and filled — **done**.
2. `op_reversal_model` and `op_outcome_unknown_recovery` resolved by the
   product owner — **done 2026-09-04** (`status: decided`).
3. `op_four_eyes`, `op_continuity_tolerance`, `op_emergency_evac`,
   `op_aa_vsls_scope` — **reclassified 2026-09-04 as not architecture
   freeze blockers**; each stays open at its own later milestone
   (release/deployment policy, pilot calibration, `OP.3` scoping) and
   the architecture supports each as a boundary without deciding it.
4. `op_degraded_verdict` — **reclassified**: a readiness-layer decision
   (`decide_by: OP.1 contract freeze`); this contract consumes only the
   currently authoritative eligibility outcomes and does not depend on it.
5. `ha_entity_operational_lock` recorded in project state — **done this
   session**.
6. No vendor command proposed, described or approved anywhere in this
   document — **done**.
7. CLASS 2 still has no member and `utils/failover/`'s tested absence is
   intact — **done**.
8. Parent-authority reconciliation recorded (`FAILOVER_ENGINE_ARCHITECTURE.md`
   §10.2; `project/*.json`) so no two authoritative documents disagree —
   **done 2026-09-04**.
9. Product-owner freeze — **open**. This document does not self-freeze.

---

## Explicit blockers

Separated by what actually blocks them.

### A. Safe to freeze now (vendor-independent, evidence complete)

P2–P18 except where a named decision is called out; the core flow; the
action/command surface boundary; correctness, safety, privacy, identity and
provenance contracts; authorization boundary; confirmation model; locking
model; state machine; mutation boundary; exactly-once; verification *shape*;
unknown-outcome semantics; audit model; crash recovery; UI boundary; module
placement; implementation sequencing.

### B. Product-owner decisions (resolved or reclassified 2026-09-04)

| Item | Decision | Status |
| --- | --- | --- |
| Reversal model — automatic rollback removed, reversal is a new typed action | `op_reversal_model` | **decided** |
| `OUTCOME_UNKNOWN` recovery — quarantine-until-acknowledged; never `FAILED` | `op_outcome_unknown_recovery` | **decided** |
| Second approver / approval policy | `op_four_eyes` | open; **deployment/release policy**, not a freeze blocker; the architecture supports the boundary |
| Continuity tolerance and its numbers | `op_continuity_tolerance` | open; recorded-only observations until decided; not a freeze blocker |
| Emergency evacuation path | `op_emergency_evac` | open at `OP.3`; initial CLASS 2 has no emergency bypass; not a freeze blocker |
| PAN A/A and VSX VSLS | `op_aa_vsls_scope` | open at `OP.3`; outside initial scope; not a freeze blocker |
| `DEGRADED_PROCEED_WITH_RISK` reachable in v1 | `op_degraded_verdict` | open at `OP.1` (readiness layer); not coupled to this freeze |

Nothing in this section blocks the product owner from freezing the
vendor-independent architecture. The **only** remaining freeze input is
the product owner's own acceptance (section D).

### C. Blocked on vendor evidence

- Every vendor mutation primitive and its reversal — no gate row exists
  (`OP.2.1`).
- CP `D-V7b` — configured-recovery/preemption read surface,
  `STILL_UNKNOWN`; blocks check 6 and the reversal preemption disclosure.
- CP `D-F3` — flap/failover-frequency threshold; blocks check 7 for both
  vendors, therefore blocks a positive readiness verdict.
- PAN `D-V3a` — `peer-info/serial-num` semantics, `STILL_UNKNOWN`.
- PAN `D-V3b` / `B₂` — real-pair serial correspondence, `NOT ESTABLISHED`,
  root cause `UNKNOWN`.
- Per-capability `settle_observation` — `UNKNOWN` for every vendor.

### D. Blocked on product-owner decision

Acceptance (freeze) of `OP.2.0` itself — the one open input to freezing
the architecture — and, later and separately, the signed change-management
/ safety review with the network-security leads before `OP.2.C`
(`FAILOVER_ENGINE_ARCHITECTURE.md` §10 — an organizational gate, not a code
gate).

### E. Blocked only on real-environment validation

- S8-B (approved VSX pair) and S8-C (approved PAN pair) — `OP.0b` cannot
  close without them.
- `CON.2` real-device read-class job.
- `OP.0a`/`OP.0c` `ha_cluster_mode` resolution confirmation.
- The `OP.2.D` single-vendor pilot itself.

### F. Blocked on deployment / infrastructure

- `DEPLOY.1A` OIDC boundary + RBAC `OPERATE` role — the authorization gate.
- `cp_production_ssh_host_key_trust_hardening` — P0 before CLASS 2 by its
  own backlog entry; the class 2 identity gate is host-key trust + hostname
  match, and compatibility mode is deliberately, explicitly incomplete.
- `pan_tls_ca` / `pan_auth_transport_convergence` — P0 before CLASS 2 for
  the PAN path (`OP.0b.0` §26 PAN-8).

---

## CP / PAN CLASS 2 readiness matrix

`YES` = established by repository authority. `NO` = established as absent.
`PARTIAL` = exists but insufficient. Nothing here is inferred.

| Dimension | CP ClusterXL (HA) | CP VSX (physical, non-VSLS) | PAN A/P |
| --- | --- | --- | --- |
| Generic architecture ready | YES (this contract, once frozen) | YES | YES |
| Operational identity ready | **YES** — `group_id`, mutual VIP set, role-independent; real-env validated (S8-A) | PARTIAL — parent identity modelled; VSID subordination frozen; **not real-env validated** (S8-B not executed) | **NO** — `B₂` NOT ESTABLISHED; `D-V3a` STILL_UNKNOWN; serial-keyed key NOT FROZEN; hostname fallback is mutable |
| Fresh same-workflow preflight ready | **YES** — S5 persistent Expert shell, 8/8 reads, REAL_ENV_VALIDATED 2026-09-04 | PARTIAL — same seam, synthetic only; S8-B owed | PARTIAL — S6 implemented; S8-C owed |
| Readiness eligibility can be positive | **NO** — check 6 blocked by `D-V7b`, check 7 by `D-F3`; `SAFE` structurally unreachable | NO — same, plus S8-B | NO — check 7 (`D-F3`), plus identity |
| Mutation semantics known | **NO** — no gate row, no official-source confirmation in this repository | NO | NO |
| Command / API authority approved | **NO** — `OP.2.1` does not exist | NO | NO |
| Post-verification evidence available | PARTIAL — role/state observable by the validated class 0 battery; `settle_observation` UNKNOWN | PARTIAL — plus `sk165432` per-VS reliability unvalidated (§26 CP-4) | PARTIAL — P1/P2/P4 implemented, unvalidated on the real pair |
| Real-env validation required | YES (`OP.2.D` pilot) | YES (S8-B, then a pilot) | YES (S8-C, then a pilot) |
| Transport/trust production-ready | **NO** — `cp_production_ssh_host_key_trust_hardening` (P0 before CLASS 2) | NO — same | **NO** — `pan_tls_ca`, §26 PAN-8 (P0 before CLASS 2) |
| **Blocked by** | `OP.2.1`; `D-V7b`; `D-F3`; `DEPLOY.1A`; SSH trust hardening; `ha_entity_operational_lock` (X-1, closes in `OP.2.B`); change-management review; §26 CP-11 (legacy `cluster` key must leave the failover path) | all of the CP column, **plus** S8-B, §26 CP-4, and §26 CP-5 (`fw ctl set int vsid` must never reach a preflight/action path) | all of the CP column, **plus** `B₂`/`D-V3b`, `D-V3a`, S8-C, PAN TLS |
| **Verdict** | **Only credible first pilot** | Second, after S8-B | **Not a CLASS 2 target** while `B₂` is unestablished |

---

## Backlog / technical-debt triage

Bounded to architecture-relevant items. No item is redesigned or solved here.

**P0 — product blocker (blocks a currently supported capability):** none.
`cp_remote_collection_done_marker_diagnostics` is `in_progress` but is
hardware-blocked on a real recurrence and does not block `OP.0b` or this
architecture.

**P1 — productization / release blocker (must close before a supported release):**

| Item | Note |
| --- | --- |
| `deploy1_oidc_viewer` (`DEPLOY.1A`) | also the class 2 authorization prerequisite — the single highest-leverage item on this list |
| `cp_production_ssh_host_key_trust_hardening` | P0 before CLASS 2 by its own entry; deliberately incomplete by PO decision |
| `pan_tls_ca` / `pan_auth_transport_convergence` | production TLS trust for the PAN path |
| `pan_serial_representation_identity_evidence_closure` | P0; blocks PAN class 2 entirely |
| `on_hardware_real_env_validation` | the accumulated real-env debt across 0.6.3→0.6.6B + DEV.2.1 |
| `recovery_offhost_key_custody`, `deploy1_release_assurance`, `deploy1_least_privilege_runtime`, `deploy1_database_migrations_and_roles`, `deploy1_evidence_egress_policy`, `deploy1_report_volume_isolation` | the `DEPLOY.1` production-boundary set; none blocks current local feature work |
| `ha_entity_operational_lock` (**new**) | X-1; P0 before CLASS 2; previously untracked |

**P2 — production hardening (important, must not block current feature work):**
`op0b_s7_s6_test_order_isolation`, `pan_ha_serial_identity_hardening`,
`pan_hostname_parser_unification`, `render_harness_happydom_pin`,
`per_vendor_worker_split`, `inventory_exclusions_management_ui` (in progress
by design — do not wire its write functions to an HTTP surface before
`DEPLOY.1A`), `cp_preflight_ccp_tablestat_evidence` (a NEW command: gate row
+ readiness mapping first).

**P3 — later / nice-to-have:**
`cp_cphaprob_peer_observation_corroboration`,
`op0c_uitest_fixture_verdict_diversity`, `overview_eos_release_guidance`,
`pan_ha_peer_ipv6_pairing` (P4/deferred).

**Duplicates / stale debt found:** none requiring action.
`cp_ssh_trust_r2_prod_server` (P1) is correctly reconciled as one line item
under the broader `cp_production_ssh_host_key_trust_hardening` rather than a
duplicate — its own note says so. `pan_ha_serial_identity_hardening` (P2,
the design decision) and `pan_serial_representation_identity_evidence_
closure` (P0, the evidence gap) look overlapping but are genuinely
different: one is "should identity be serial-keyed", the other is "why does
one member's serial evidence not match". Both stay.

**Explicitly not elevated:** the `DEPLOY.1`/`DEV.4` security-hardening set is
production-boundary work. It gates a supported release and `OP.2.C`; it does
not gate `OP.0b` closure, `OP.2.A`, `OP.2.B`, or any current local feature
work, and treating every hardening item as current product work is the
failure mode this triage exists to prevent.

---

## Deliberately not designed in this session

Not load-bearing for CLASS 2 architecture, and named so a future session
does not re-litigate the omission: container/pod migration; SSH known-hosts
enrollment and host-key rotation mechanics; a generic parser framework;
test-order isolation; pacing tuning; generic storage redesign; cached
preflight snapshots; any TTL architecture; UI visual polish; full RBAC
implementation; PAN Active/Active and VSX VSLS execution semantics;
cross-vendor primitive abstraction.

---

## Independent challenge review — 2026-09-04

Movement: `ARCHITECTURE` review (extended reasoning), independent of the
drafting pass. Brief: challenge, converge, simplify, find contradictions,
verify implementability — not redesign. Product-owner decisions taken as
authoritative for this review: 1A no automatic rollback (reversal is a new
typed action); 1B post-boundary loss is never merely `FAILED`; 1C
same-workflow preflight, no TTL; 1D durable boundary commit before
submission, no replay; 1E PAN is not an initial target; 1F CP ClusterXL is
the first pilot; 1G four-eyes is a policy boundary, not a quorum framework;
1H continuity observations recorded, not verdict-bearing; 1I no emergency
bypass; 1J A/A and VSLS out of initial scope; 1K degraded readiness stays a
readiness-layer decision.

**Naming confirmed.** `OP.1` = plan compiler / dry-run / class 0 already
exists (`project/roadmap.json` `now_next.upcoming`, `project/backlog.json`
and `project/feature_registry.json` `failover_plan_compiler`,
`FAILOVER_ENGINE_ARCHITECTURE.md` §10). `OP.2.0` is the correct
non-colliding name for the CLASS 2 architecture contract.

### Challenge matrix

| Area | Opus design | Challenge result | Required change | Freeze blocker? |
| --- | --- | --- | --- | --- |
| State machine | 8 non-terminal + 7 terminal states; `AWAITING_CONFIRMATION → PREFLIGHTING` re-preflight loop | `LOCKING`/`LOCKED` had no in-progress meaning (try-acquire never waits) and the crash table already treated them as `CREATED`; `EVALUATING` is a pure function; `VERIFYING` differs from `EXECUTING` in nothing reconciliation, cancellation or locking cares about; the re-preflight loop was a dead re-entry path (bound facts are immutable) and a second route into `PREFLIGHTING`; `PREFLIGHTING → ABORTED` vs `NOT_ELIGIBLE` was ambiguous for a failed read | Collapsed to 4 + 6 states; loop removed; every transition is a from-state-guarded durable write; failed preflight read = `INSUFFICIENT_EVIDENCE` = `NOT_ELIGIBLE` | was — fixed |
| Lock timing | Outer HA-entity lock + inner per-member leases acquired before preflight and held through the human confirmation wait to verification | Lock-before-preflight is right and kept. Holding the *inner* admission across the wait is not: coordinator admission takes the per-vendor budget (capacity 1) for the job lifetime, so one deliberating operator would block every collection of that vendor with no TTL to bound it; and a class 0 read between stages cannot invalidate the preflight — only another action can, and the outer lock excludes it | Outer lock spans the workflow; inner admission per device-contact stage (preflight; precondition + submit + verify), refused-not-waited; adapter precondition re-observation immediately before the boundary closes the "role changed during the wait" hole | was — fixed |
| Exactly-once | `action_id` idempotency; no replay after boundary | Correct in principle, but the mechanism was unnamed: double confirmation, confirm/cancel race and a stale process each needed a guarded write to be safe | Boundary commit specified as a guarded compare-and-set with exactly one winner; only the winner submits; single-coordinator-process invariant stated; transport-level retry disabled and test-asserted (AC-15/16/17) | was — fixed |
| Mutation boundary | Durable `EXECUTING` + `mutation_boundary_crossed = YES` before submission; `SUBMISSION_NOT_SENT` maps back only on adapter proof | Sound under crash-before-send, crash-during-send, response loss, browser retry and worker restart once the guarded transition exists. PO 1D false-positive `OUTCOME_UNKNOWN` accepted | Wording added; no structural change | no |
| Quarantine | Described in three places: a lock that "converts to `ACTION_QUARANTINED`", an entity state, and an action-record field | Three owners, and the first is not implementable — the existing lease is connection-lifetime and vanishes on crash; a "retained lock" would need a new lock mechanism. | Quarantine = derived predicate over the record store (unacknowledged `OUTCOME_UNKNOWN` record); the outer lock = record uniqueness; no lock table, no new key domain; acknowledgement is an authorized, audited write on that record | was — fixed |
| Confirmation | Digest over action, type, entity, postcondition, generation, eligibility, material parameters; does not survive restart | Binding complete except the single most material parameter — the subject member — which was only implied by "material parameters". Not-surviving-restart is consistent with the durable machine because the whole pre-boundary workflow is discarded on restart; UX is honest (start a new action) | `subject_member_token` bound explicitly; digest mismatch = refusal, not re-preflight; one `approval_policy` boundary carrying second approver, maintenance window and change reference as policy inputs | minor — fixed |
| Authorization | `authorize()` unconditional `DENY` until `DEPLOY.1A`; one call site per entry point; CLI "subject to the same boundary" | Structurally unreachable, provided the test double cannot be selected at runtime and no argv route exists — a CLI route, even a gated one, would be reachable from `workflow_argv()`/the runner | No argv/CLI entry point at all; authorizer is a constructor argument with no runtime selector; `PERMIT` only under `tests/`, source-asserted (AC-16); "admin" bearer token means nothing to the boundary | minor — fixed; strategy preserved |
| Verification | Independent fresh class 0 read, new `preflight_run_id`; `FAILED_NO_CHANGE` on "original state observed"; "always `OUTCOME_UNKNOWN` until settle known" | Two defects: an early "nothing changed" read while `settle_observation` is `UNKNOWN` would close the action `FAILED_NO_CHANGE` and release an entity that then fails over unattended; and the "always unknown" sentence made `SUCCEEDED` unreachable in the very pilot meant to measure settle | `SUCCEEDED` reachable on positive observation regardless of settle; `FAILED_NO_CHANGE` unreachable while settle is `UNKNOWN`; explicit negative list (error/timeout/crash/missing response are never `FAILED_NO_CHANGE`) | was — fixed |
| `SUCCEEDED_WITH_WARNINGS` | Terminal state reachable on an off-nominal secondary observation | Reachability depended on an unapproved continuity policy | Removed from the state set; observations still recorded; reintroduction needs `op_continuity_tolerance` + amendment | minor — fixed |
| Reversal | New typed action; auto-rollback removed; contradiction reported | Correct; PO 1A resolves it. The parent still said the opposite in §1, §2, §3.3, §5, §6, §8 and in two `project/*.json` files | Parent §10.2 reconciliation + metadata text corrected (below) | was — fixed |
| Crash recovery | Reconciliation before worker start; per-state table | Decidable and complete for a single coordinator process; multi-process reconciliation would mis-attribute ownership | Single-coordinator-process invariant made explicit; table reduced to the four states; restart never lifts quarantine or re-runs verification | minor — fixed |
| Audit | One append-only record; identity-lean; no `ConsoleJobStore` reuse | Sufficient for accountability, reconstruction, boundary proof, reconciliation, verification and outcome; lock fields referred to a lock that no longer exists separately | `admissions[]`, `precondition_result`, `acknowledged_by` added; lock-row fields dropped; `ConsoleJobBackend` storage shape may be reused minus the sweep | minor — fixed |
| Vendor adapter | `capability` / `build_plan` / `execute_once` / `observe_postcondition`; command text only in the adapter | Correctly narrow; no `execute(command)`, no XML passthrough; no forced CP/PAN symmetry | `check_precondition` added (approved class 0 battery only); transport no-retry rule | minor — fixed |
| CP/PAN scoping | CP ClusterXL first; PAN `OP.3`; VSX after S8-B | Matches PO 1E/1F/1J and the evidence matrix | None; A/A-VSLS exclusion and "no VSLS assumption in the CP adapter" stated | no |
| Implementation order | `OP.2.1` → `OP.2.A` → `OP.2.B` → `OP.2.C` → `OP.2.D` (gate first) | `A`/`B` consume nothing from the command gate; serialising them behind official-source research buys no safety | PO order adopted: `A` → `B` with `OP.2.1` in parallel, both before `C`; then `D`; PAN at `OP.3` | no |
| Parent authority | Contradictions reported, not reconciled | Also found: §10.1 item 3 "declared freshness window" (contradicts 1C), §7 module layout (`utils/failover/plan.py`/`executor.py`/`adapters/` vs the test-enforced allowlist and `utils/operate/`), §3.1 VSX per-VS failover units (contradicts VSID subordination and 1J), §8 item 1 feature flag + per-action token (subsumed by `authorize()` + digest), maintenance window silently dropped from a frozen handoff list | `FAILOVER_ENGINE_ARCHITECTURE.md` §10.2 appended; inline supersession markers; `project/*.json` text corrected; maintenance window placed in the approval policy boundary | was — fixed |

### Freeze decision of the review

**FREEZE WITH CHANGES** — every required change above is documentation-only
and deterministic, and all were applied in the same session. After them the
vendor-independent architecture is, in the reviewer's judgment, safe,
complete enough, implementable and free of contradictory authority:
**READY FOR PRODUCT-OWNER FREEZE**. The review does not freeze it.

### Lock-timing conclusion

Lock-before-preflight is the safest minimal design *for the HA-entity lock*
and is kept. The earlier model of also holding member admission through the
human wait was not: it starved the vendor budget with no bound, contradicted
the lease's own connection-lifetime semantics, and protected against nothing
a class 0 read could do. The adopted sequencing — record (= entity lock) for
the whole workflow, coordinator admission per device-contact stage,
precondition re-observation immediately before the guarded boundary commit —
preserves the authoritative fresh preflight, no concurrent mutation,
confirmation binding and no stale plan, without any numeric expiry.

### Parent-authority corrections made

- `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`: new §10.2 recording what
  §1–§8 and §10.1 item 3 no longer govern for `OP.2` (automatic rollback,
  `FAILED_ROLLED_BACK`, the numeric freshness window, the §7 execution
  module layout, per-VS VSX failover units, feature-flag/per-action-token
  wording, the class 0 orphan sweep not being universal); one-line
  supersession markers at each affected paragraph; a status-block pointer.
  Historical text is not rewritten.
- `project/roadmap.json`: `op_reversal_model` and
  `op_outcome_unknown_recovery` → `decided`; `op_four_eyes`,
  `op_continuity_tolerance` (area/question no longer "auto-rollback"),
  `op_emergency_evac`, `op_aa_vsls_scope`, `op_degraded_verdict` stay open
  with `decide_by` re-pointed to their real milestone and a note that none
  blocks the `OP.2.0` freeze.
- `project/feature_registry.json` and `project/backlog.json`
  `failover_controlled_execution`: "auto-rollback" wording replaced by the
  reversal-as-new-action law; `ha_entity_operational_lock` note updated to
  the record-uniqueness model.
- `OP_0B_0` §26 row X-1 and `CON_2` C2-5 are frozen historical records and
  are left as written; the former is closed by the backlog item it asked
  for, the latter is scoped to class 0 by §10.2 and this document.

## Next movement / reasoning tier

1. **Product-owner freeze decision on this document.** Section B is
   resolved or reclassified; the independent review found no remaining
   architecture blocker. *(Decision, not a build.)*
2. **Resume operational work meanwhile:** `OP.0b` S8-B (approved VSX pair)
   → S8-C (approved PAN pair), operator-executed, SAFE counts only.
   Recommended: **normal reasoning** — bounded real-environment procedure
   against a frozen contract; a higher tier is more than the step needs.
3. **After PO freeze:** `OP.2.A` (typed action model + lifecycle + audit +
   `DENY` boundary, zero device I/O) at **normal** reasoning —
   deterministic implementation against a frozen contract. Reserve
   **extended** reasoning for `OP.2.B` (concurrency/safety boundary) and
   `OP.2.1` (security boundary / vendor-semantic calls), the latter in
   parallel with `A`/`B`.

This document authorizes none of the above. It is `DRAFT` — reviewed and
ready for the product owner's freeze, and not frozen until they say so.
