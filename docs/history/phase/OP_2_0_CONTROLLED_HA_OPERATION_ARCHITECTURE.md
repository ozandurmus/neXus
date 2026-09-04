# OP.2.0 — Controlled HA operation / CLASS 2 execution architecture contract

## Status

**DRAFT — DO NOT FREEZE.**

Per `AGENTS.md` "Contract-status law" this document may guide investigation
and sequencing; it **authorizes no implementation**, approves no command,
and does not make `CLASS_2_OPERATIONAL_STATE_CHANGE` reachable. It becomes
implementation authority only if a product owner freezes it, and even then
only for the slices whose own prerequisites (§"Explicit blockers") are met.

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
  canonical homes regardless of this document's status.

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
   `op_reversal_model` decision.
3. **The existing durable job store would resolve a crash the wrong way.**
   `console/jobs.py::sweep_orphaned_running` marks a job left `running` by a
   dead process as `failed` / `console_restarted`. For a class 0 collection
   that is correct. For a class 2 action it is the single most dangerous
   possible answer: it asserts *the mutation did not happen* from evidence
   that only proves *the process died*. A CLASS 2 action registry cannot
   reuse that sweep.

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
- The emergency "evacuate a failing member" path (`op_emergency_evac`,
  recommendation unchanged: defer to `OP.3`).
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
preflight generation. Concretely — if the action leaves `LOCKED` for any
reason before entering `EXECUTING`, or if the process restarts at any point
before `EXECUTING`, the preflight generation is discarded and the action is
terminated pre-mutation. A new action must run a new preflight.

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
preflight_generation_id
eligibility_result               (verdict + reason codes + check statuses)
material_action_parameters       (typed; adapter-declared, bounded set)
```

The digest is shown to the operator with the proposal and returned with the
confirmation; the server recomputes it and refuses a mismatch. If any bound
fact changes, the digest changes, the prior confirmation is invalid, and the
action returns to `PREFLIGHTING`.

This is a content digest for binding, **not** a signature: the repository
has an established precedent for exactly this (`group_id` =
`sha256(CMA + sorted VIP set)[:16]`; `derive_lock_key`'s domain-separated
HMAC) and no requirement anywhere for non-repudiation of an operator action
beyond the audit record. Cryptographic signing is **not** introduced.

Number of required confirmations is a configuration of this one gate (one
operator, or one operator plus a second approver). Which it is, is
`op_four_eyes` — a product-owner decision due at this freeze, not decided
here.

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
`entity_id` row. Two locks, acquired in a fixed order, no distributed
consensus:

| Level | Key | Purpose | Mechanism |
| --- | --- | --- | --- |
| **outer** | `derive_lock_key(secret, "ha_entity", operational_entity_id)` | at most one non-terminal class 2 action per HA entity | new, durable |
| **inner** | the existing `("endpoint", canonical_id)` lease, one per member, acquired in sorted `canonical_id` order | no concurrent collection on any member of the entity while an action is in flight | existing `CollectionCoordinator` |

Acquire outer first, then every inner lease in sorted order; release in
reverse. The deterministic order is what makes deadlock between an action
and a collection structurally impossible rather than improbable. `derive_
lock_key` is reused as-is — same HMAC, same domain separation (`"ha_entity"`
is a new domain, so it can never collide with `"endpoint"` or `"gate"`); no
new lock mechanism is invented.

Current entity examples: the CP ClusterXL cluster (`group_id`), and the CP
VSX **physical** cluster parent. VSIDs are subordinate contexts and are
never lock subjects. PAN pairs are not lock subjects while their operational
identity is unresolved (§"Identity invariants").

Concurrency behaviour:

- **Same entity, second action:** refused at `LOCKING` with
  `ABORTED_PRE_MUTATION:entity_action_in_flight`. Not queued — a queued
  class 2 action would execute against a preflight it did not generate,
  violating P4.
- **Same entity, concurrent collection:** the inner lease refuses it, with
  the existing coalescing behaviour.
- **Reads during lock:** class 0 reads belonging to the owning action (its
  own preflight and its own verification) are permitted and are how the
  action makes progress. Every other class 0 read on the entity's members is
  refused for the duration.
- **Different entities:** independent; no global action lock exists.

**Crash while the lock is held is not an auto-release.** See P10.

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

On `OUTCOME_UNKNOWN` the operational HA entity enters
`ACTION_QUARANTINED`:

- No new class 2 action on that entity is eligible.
- Class 0 reads on that entity stay permitted — they are how a human finds
  out what happened.
- The quarantine is lifted only by an explicit, audited operator
  acknowledgement of the specific action record. It is never lifted by a
  timer, a restart, a successful read, or a subsequent green readiness
  verdict.

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
available action. This is a **reported contradiction with the design
parent**, raised for product-owner resolution as `op_reversal_model`, not a
unilateral reinterpretation.

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
  HA-ENTITY LOCK           outer lock acquired here, held to the end
        │                  + inner per-member leases, sorted order
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
  EXPLICIT HUMAN           confirmation bound to proposal_digest
  CONFIRMATION             (+ second approver if op_four_eyes says so)
        │
        ▼
  ══ durable commit: EXECUTING, mutation_boundary_crossed = YES ══
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
  FINAL ACTION OUTCOME     SUCCEEDED | SUCCEEDED_WITH_WARNINGS |
                           FAILED_NO_CHANGE | OUTCOME_UNKNOWN
```

**Lock placement note.** The lock is acquired *before* the preflight, not
after eligibility: a preflight is the evidence the whole decision rests on,
and evidence collected while another action could be mutating the same
entity is not evidence. This is one deviation from the flow sketch in the
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
3. The entity is not `ACTION_QUARANTINED`.
4. The outer HA-entity lock is held by this `action_id`, and every inner
   member lease is held.
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
6. Per-HA-entity lock held across preflight → verification; a collection and
   an action never run on one entity at once.
7. Every run fully audited; the shareable summary is value-free.
8. A management-plane configuration push (Panorama/MDS) is forbidden by the
   plan for the duration of an action — stated as a plan invariant, not
   enforced by this product, which has no such write capability.
9. **New:** crossing the mutation boundary is a durable commit (P6); a crash
   after it is `OUTCOME_UNKNOWN` and quarantines the entity (P10).
10. **New:** no component may replay, resume, or "complete" an action whose
    `mutation_boundary_crossed` is `YES`.

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
- The CLI is not a privilege escalation path: a class 2 action invoked from
  the CLI is subject to the same boundary. (This differs from `RB.x`
  class 1, whose gates are deliberately CLI-side — that asymmetry is
  acceptable for a ledgered backup and is not for a failover.)

---

## Confirmation model

| Property | Rule |
| --- | --- |
| Binds to | `proposal_digest` (P5) |
| Displayed before | the full `ActionPlan`, the eligibility line items, the intended postcondition, the impact disclosure, the reversal note |
| Invalidated by | any change to a bound fact; a new preflight generation; leaving `AWAITING_CONFIRMATION`; process restart |
| Survives restart | **no** — a confirmation binds to a preflight generation that a restart discards (P4) |
| Batching | prohibited; one confirmation confirms one action on one entity |
| Implicit confirmation | prohibited; no default, no timeout-to-accept, no "remember this choice" |
| Second approver | supported as a configuration of this gate; whether it is mandatory is `op_four_eyes` (PO, due at this freeze) |
| Cancellation | legal from `CREATED`, `PREFLIGHTING`, `AWAITING_CONFIRMATION`, `LOCKING`, `LOCKED`; **impossible** from `EXECUTING` onward |
| Reason text | mandatory, bounded, redaction-filtered, never in the support bundle |

---

## Locking / concurrency model

See P8 for the grain and ordering. Remaining rules:

| Situation | Behaviour |
| --- | --- |
| Second class 2 action, same entity | refused at `LOCKING`; `ABORTED_PRE_MUTATION:entity_action_in_flight`. Never queued. |
| Collection request, same entity, action in flight | refused by the inner per-member lease (existing coalescing behaviour) |
| Action request, same entity, collection in flight | the inner lease is unavailable → `ABORTED_PRE_MUTATION:member_busy`. The action is not held waiting: a wait would separate preflight from execution. |
| Failover and failback overlapping | structurally impossible — failback is a new action and the entity's outer lock is not released until its predecessor is terminal |
| Reads during lock | the owning action's own preflight and verification only; every other read on the entity's members is refused |
| Different entities | fully independent |
| Lock ownership | the `action_id`, recorded durably with the lock |
| Crash, `mutation_boundary_crossed = NO` | lock released on reconciliation; the action terminates `ABORTED_PRE_MUTATION:process_restart` |
| Crash, `mutation_boundary_crossed = YES` | **the lock is not released.** It converts to `ACTION_QUARANTINED` (P10), lifted only by explicit operator acknowledgement. |
| Lock backend | durable, via the existing `utils.evidence_backend` / `utils.coordinator_backend` pattern (filesystem default, opt-in Postgres). An in-memory lock is not sufficient for an action that can cross the mutation boundary. |

No distributed consensus, leader election or quorum is introduced. The
current topology (one worker process per deployment; opt-in shared Postgres
for cross-container admission, `DEV.3.2`) does not require it, and adding it
would be the largest unforced complexity in this design.

---

## Execution state machine

Deterministic, durable, and boring by intent.

### States

| State | Durable across restart | Terminal | Meaning |
| --- | --- | --- | --- |
| `CREATED` | yes | no | authorized typed intent, entity resolved, record durable |
| `LOCKING` | yes | no | acquiring outer + inner locks |
| `LOCKED` | yes | no | locks held; nothing has been read or sent |
| `PREFLIGHTING` | yes | no | this action's own class 0 preflight is running |
| `EVALUATING` | yes | no | readiness projection + eligibility |
| `AWAITING_CONFIRMATION` | yes | no | a bound proposal exists |
| `EXECUTING` | yes | no | **mutation boundary crossed**; exactly one submission attempted |
| `VERIFYING` | yes | no | independent fresh observation in progress |
| `NOT_ELIGIBLE` | yes | **yes** | refused by the eligibility contract; nothing was sent |
| `ABORTED_PRE_MUTATION` | yes | **yes** | ended before the boundary; nothing was sent |
| `CANCELLED` | yes | **yes** | operator-cancelled before the boundary; nothing was sent |
| `SUCCEEDED` | yes | **yes** | intended postcondition independently observed |
| `SUCCEEDED_WITH_WARNINGS` | yes | **yes** | postcondition observed; a recorded secondary observation is off-nominal |
| `FAILED_NO_CHANGE` | yes | **yes** | submitted; the entity is coherently observed in its **original** state |
| `OUTCOME_UNKNOWN` | yes | **yes** | boundary crossed; postcondition not independently determinable |

Every state is durable across process restart — that is what makes the
reconciliation table (§"Crash / restart recovery") decidable.

### Legal transitions

```
CREATED ──► LOCKING ──► LOCKED ──► PREFLIGHTING ──► EVALUATING ──► AWAITING_CONFIRMATION
   │           │           │            │               │                    │
   │           │           │            │               │                    ├──► PREFLIGHTING   (proposal invalidated: re-preflight)
   │           │           │            │               │                    │
   │           │           │            │               └──► NOT_ELIGIBLE    │
   │           │           │            │                                    │
   │           │           │            └──► ABORTED_PRE_MUTATION            │
   │           │           │                                                 ▼
   │           │           └──► ABORTED_PRE_MUTATION            ══ MUTATION BOUNDARY ══
   │           │                                                             │
   │           └──► ABORTED_PRE_MUTATION                                     ▼
   │                                                                    EXECUTING
   └──► CANCELLED  (also legal from LOCKING, LOCKED,                        │
                    PREFLIGHTING, EVALUATING,                                ▼
                    AWAITING_CONFIRMATION)                              VERIFYING
                                                                             │
                        ┌──────────────┬──────────────┬─────────────────────┤
                        ▼              ▼              ▼                     ▼
                   SUCCEEDED   SUCCEEDED_WITH_   FAILED_NO_CHANGE    OUTCOME_UNKNOWN
                                  WARNINGS
```

Additional rules:

- `EXECUTING → OUTCOME_UNKNOWN` directly is legal (the submission's outcome
  family was unknown and verification could not start).
- No transition **out of** a terminal state exists. A `post_hoc_observation`
  appends to the record; it does not transition it.
- No transition re-enters `EXECUTING`. There is exactly one entry and it
  happens once per `action_id`.
- `AWAITING_CONFIRMATION → PREFLIGHTING` marks the prior proposal
  `superseded` in the audit record; the superseded proposal is retained.

### Cancellation

Legal in every pre-mutation state. **Impossible** from `EXECUTING` onward —
not "discouraged", not "best-effort": the API has no route, the console has
no control, and the coordinator has no code path. A request to stop an
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
| Crash resolution | `ABORTED_PRE_MUTATION` | `OUTCOME_UNKNOWN` + quarantine |
| Lock on crash | released | retained as quarantine |
| Audit | records the attempt was not made | records the attempt was made |

The boundary is crossed by the durable state commit, not by the submission
(P6). Generic exception handling must not blur it: the coordinator maps only
`SUBMISSION_NOT_SENT` back to the pre-mutation family, and only when the
adapter positively proves it (e.g. no session was ever established). Every
other failure mode stays on the post-boundary side.

---

## Exactly-once / retry semantics

| Scenario | Behaviour |
| --- | --- |
| Browser double-submit / retry with the same `action_id` | returns the existing record; no second action, no second submission |
| Browser retry with no `action_id` | new action; refused at `LOCKING` if the entity already has one in flight |
| Reverse-proxy or client-library replay | same as above — `action_id` is the idempotency subject, not the HTTP request |
| Transport error before submission, adapter proves nothing was sent | `ABORTED_PRE_MUTATION:submission_not_sent`; a new action may be created |
| Transport timeout after submission | `OUTCOME_UNKNOWN` unless verification determines otherwise; **never** re-sent |
| Backend restart, `mutation_boundary_crossed = NO` | `ABORTED_PRE_MUTATION:process_restart` |
| Backend restart, `mutation_boundary_crossed = YES` | `OUTCOME_UNKNOWN:process_restart_after_mutation_boundary` + quarantine |
| Operator asks to "try again" after `OUTCOME_UNKNOWN` | refused while quarantined; after acknowledgement, a **new** action with a **new** preflight — which will observe the real current state and may well be a different action entirely |

State is never held only in memory: `action_id`, lifecycle state,
`mutation_boundary_crossed`, lock ownership and the audit record are durable
before they are relied upon.

---

## Post-action verification

Independent, fresh, class 0, new `preflight_run_id` (P9).

| Observation | Outcome |
| --- | --- |
| Intended postcondition observed, coherent, both members, no new blocking condition | `SUCCEEDED` |
| Intended postcondition observed + a recorded secondary observation off-nominal | `SUCCEEDED_WITH_WARNINGS` |
| Original state observed, coherent, both members | `FAILED_NO_CHANGE` |
| Partially changed / members disagree / `RELATIONSHIP_INCONSISTENT` | `OUTCOME_UNKNOWN` |
| One-sided (only one member observable) | `OUTCOME_UNKNOWN` — a member's report about its peer is not an observation of the peer |
| Read failed / device unreachable / session lost | `OUTCOME_UNKNOWN` — collection failure is not a known-bad state |
| Mode changed to one the contract does not support | `OUTCOME_UNKNOWN` |

**No numeric timers are invented.** How long to wait before the postcondition
is stably observable is a *per-vendor, per-capability* fact
(`settle_observation`) that the adapter declares and that is `UNKNOWN` until
real-environment evidence establishes it. The first pilot's job is to
**measure and record** it, not to assume it. Until it is known, the
verification stage performs a single bounded observation using the existing
preflight command timeouts and reports `OUTCOME_UNKNOWN` rather than
guessing a settle window.

**Continuity observations are recorded, never verdict-bearing** while
`op_continuity_tolerance` is open. Session/connection continuity is a
numeric, tolerance-bearing observation; the intended postcondition is a
deterministic role/state fact. Only the latter decides `SUCCEEDED` vs
`FAILED_NO_CHANGE`. This mirrors the pattern the repository already uses for
`member_skew_ms` and for check 6 — record the fact, withhold the judgment
until the threshold is a decision rather than a guess.

---

## Unknown-outcome semantics

Fully specified in P10. Summarized:

- `OUTCOME_UNKNOWN` ≠ `FAILED`. It is the honest answer, and it is terminal.
- It quarantines the operational HA entity against further class 2 actions.
- Class 0 reads remain permitted — they are the recovery path.
- The quarantine lifts only by explicit, audited operator acknowledgement.
- A post-hoc observation appends to the record and never rewrites its state.
- It maps to the design parent's `FAILED_MANUAL_INTERVENTION_REQUIRED`
  (§6) but is named for what is actually known rather than for what someone
  must do about it.

---

## Reversal / failback semantics

Fully specified in P12. Summarized:

- Failback is a new typed class 2 action carrying the full gate chain.
- No automatic rollback exists in this model; `FAILED_ROLLED_BACK` is not a
  state.
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
| lock acquisition | `outer_lock_acquired_at`, `member_lease_count`, `lock_owner_action_id` |
| **whether the boundary was crossed** | `mutation_boundary_crossed` (`NO` \| `YES`) + `boundary_committed_at` |
| selected vendor capability | `capability_id`, `adapter_version` — **never the command** |
| submission outcome | `submission_outcome_family` (`NOT_SENT` \| `UNKNOWN`) |
| post-verification | `post_action_preflight_run_id`, `observed_postcondition`, `continuity_observations[]` (recorded, non-verdict) |
| final lifecycle state | `state`, `terminal_reason`, `finished_at` |
| lineage | `reverses_action_id`, `acknowledged_at`, `post_hoc_observations[]` |
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
`mutation_boundary_crossed`, a field `JobRecord` does not have.

---

## Vendor-adapter contract

Conceptual surface. Names are a design decision; the boundary is not.

```
capability(entity_kind, action_type, evidence)   -> Capability | UNSUPPORTED(reason)
build_plan(entity, action_type, evidence)        -> ActionPlan
execute_once(plan, action_id)                    -> SUBMISSION_NOT_SENT | SUBMISSION_OUTCOME_UNKNOWN
observe_postcondition(entity, plan)              -> Observation          (class 0 only)
```

| Rule | |
| --- | --- |
| Sole holder of vendor command/API text | the adapter module, nothing above it |
| `ActionPlan` contents | typed intent, intended postcondition, the subject member the adapter selected and why, impact disclosure, reversal/preemption note, `settle_observation` (may be `UNKNOWN`), declared material parameters. **No command string, no argv, no XML, no API path.** |
| Unsupported | explicit, reasoned, fail-closed. Never "try the closest thing." |
| Submission | exactly one attempt; the adapter performs no retry, no fallback, no alternate primitive, no reconnect-and-resend |
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
| before preflight (`CREATED`/`LOCKING`/`LOCKED`) | `NO` | `ABORTED_PRE_MUTATION:process_restart`; locks released |
| during/after preflight (`PREFLIGHTING`/`EVALUATING`) | `NO` | `ABORTED_PRE_MUTATION:process_restart`; preflight generation discarded; locks released |
| after confirmation, before boundary (`AWAITING_CONFIRMATION`) | `NO` | `ABORTED_PRE_MUTATION:confirmation_context_lost`; the confirmation does **not** survive, because the preflight generation it bound to cannot be revalidated post hoc |
| during submission (`EXECUTING`) | `YES` | `OUTCOME_UNKNOWN:process_restart_after_mutation_boundary`; **lock retained as quarantine** |
| after submission, before response (`EXECUTING`) | `YES` | identical to the row above — the product cannot distinguish these two, and must not pretend it can |
| during verification (`VERIFYING`) | `YES` | `OUTCOME_UNKNOWN:verification_interrupted`; quarantine |

Invariants:

- A restart **never** automatically replays a possibly-executed mutation.
- A restart **never** automatically resumes an action past the boundary.
- A restart **never** resolves a post-boundary record to `FAILED`.
- Reconciliation is idempotent and produces an audit transition of its own.

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
- surface `OUTCOME_UNKNOWN` and `ACTION_QUARANTINED` prominently and
  honestly, with the acknowledgement path;
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
| **`OP.2.A`** | Typed action model, lifecycle state machine, durable action/audit record, `authorize()` boundary returning unconditional `DENY`. Pure + storage; no adapter, no transport, no command. New package `utils/operate/` with its own convergence assertion. | none — CLASS 2 stays memberless | this contract frozen | normal |
| **`OP.2.B`** | Action coordinator: HA-entity lock (outer) + member leases (inner), idempotency on `action_id`, mutation-boundary commit, crash reconciliation, quarantine. Still no adapter, no mutation, no device contact. | none | `OP.2.A`; the `ha_entity_operational_lock` gap closed | extended (concurrency + safety boundary) |
| **`OP.2.C`** | First vendor capability adapter (**CP ClusterXL**): `capability`/`build_plan`/`execute_once`/`observe_postcondition`; post-action verification wiring. **This is where CLASS 2 gains its first member.** | **CLASS 2** | every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite; `OP.2.1` approved; `DEPLOY.1A` OIDC + `OPERATE`; `D-V7b`, `D-F3`; `cp_production_ssh_host_key_trust_hardening`; signed change-management review | extended |
| **`OP.2.D`** | Operator Console class 2 workflow (proposal → confirmation → lifecycle → outcome → acknowledgement) **and** the bounded real-environment single-vendor pilot on the approved CP ClusterXL pair. | — | `OP.2.C`; `C-D4`, `C-D6`; real-env procedure | normal (UI) / normal (real-env) |

PAN is deliberately **not** a movement here. It becomes one only after
`B₂` is established, `D-V3a` closes, S8-C passes, and `OP.2.D`'s pilot
proves the model on one vendor — i.e. it is `OP.3` work, consistent with
`op_aa_vsls_scope`'s recommendation and §10.1 item 9.

---

## Acceptance criteria

Bars for the implementation movements, evaluated against this contract once
frozen — not conditions on freezing it.

- **AC-1** `authorize()` has exactly one call site per entry point, defaults
  to `DENY`, and returns `DENY` unconditionally while `DEPLOY.1A` is absent.
  Proven over a generated matrix, not by inspection.
- **AC-2** A positive readiness verdict alone cannot cause any transition
  past `EVALUATING`. Proven by a test that supplies a positive verdict with
  authorization denied and with confirmation absent.
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
- **AC-8** The outer lock is keyed on the operational entity, never a member
  or endpoint; a second action on the same entity is refused; a crash with
  `YES` retains the lock as quarantine.
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
   product owner — **open**.
3. `op_four_eyes`, `op_continuity_tolerance`, `op_emergency_evac`,
   `op_aa_vsls_scope` resolved (all carry `decide_by: OP.2 contract
   freeze`) — **open**.
4. `op_degraded_verdict` resolved (`decide_by: OP.1 contract freeze`,
   upstream of this contract) — **open**.
5. `ha_entity_operational_lock` recorded in project state — **done this
   session**.
6. No vendor command proposed, described or approved anywhere in this
   document — **done**.
7. CLASS 2 still has no member and `utils/failover/`'s tested absence is
   intact — **done**.

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

### B. Must remain draft (product-owner decision required)

| Item | Decision |
| --- | --- |
| Reversal model — is automatic rollback removed, as P12 proposes? | `op_reversal_model` (**new**) |
| `OUTCOME_UNKNOWN` recovery — is quarantine-until-acknowledged the model? | `op_outcome_unknown_recovery` (**new**) |
| Second approver mandatory or configurable | `op_four_eyes` |
| Continuity tolerance — fixed default or tunable; and its numbers | `op_continuity_tolerance` |
| Emergency evacuation path in `OP.2` or `OP.3` | `op_emergency_evac` |
| PAN A/A and VSX VSLS first-class or deferred | `op_aa_vsls_scope` |
| `DEGRADED_PROCEED_WITH_RISK` reachable in v1 | `op_degraded_verdict` (due at `OP.1`, upstream) |

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

Section B, plus: acceptance of `OP.2.0` itself, and the signed
change-management / safety review with the network-security leads
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
| **Blocked by** | `OP.2.1`; `D-V7b`; `D-F3`; `DEPLOY.1A`; SSH trust hardening; X-1 lock; change-management review; §26 CP-11 (legacy `cluster` key must leave the failover path) | all of the CP column, **plus** S8-B, §26 CP-4, and §26 CP-5 (`fw ctl set int vsid` must never reach a preflight/action path) | all of the CP column, **plus** `B₂`/`D-V3b`, `D-V3a`, S8-C, PAN TLS |
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

## Next movement / reasoning tier

1. **Product-owner review of this document.** Resolve section B, starting
   with `op_reversal_model` — it changes the state machine's terminal set
   and therefore everything downstream. *(Decision, not a build.)*
2. **Resume operational work meanwhile:** `OP.0b` S8-B (approved VSX pair)
   → S8-C (approved PAN pair), operator-executed, SAFE counts only.
   Recommended: **normal reasoning** — bounded real-environment procedure
   against a frozen contract; a higher tier is more than the step needs.
3. **After PO acceptance:** `OP.2.A` (typed action model + lifecycle +
   audit) at **normal** reasoning — deterministic implementation against a
   frozen contract. Reserve **extended** reasoning for `OP.2.B`
   (concurrency/safety boundary) and `OP.2.1` (security boundary /
   vendor-semantic calls).

This document authorizes none of the above. It is `DRAFT — DO NOT FREEZE`.
