# UI 2.0 — Architecture design: separate Java operator console, LDAP multi-admin sessions, profile-based backup, per-feature port from Line-1

## Status

**DRAFT — design resolved, NOT frozen, NOT implementation authority.**
Successor to `docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md` (the
requirements-and-tradeoffs draft, PR #140), produced 2026-09-09 by movement
`UI2_0_ARCHITECTURE_DESIGN_FABLE_HIGH` (relay/NXS-LOCAL-0047). Unlike the
requirements draft, every open point it left — `C-1`, `C-2`, `C-3`, `C-4`,
`U-1`, plus the already-resolved `X-2` — is resolved here **as a stated
decision**, matching the Product Owner's direction of 2026-09-09. Those
decisions are recorded, not re-opened; where the requirements draft's own
analysis disagreed with a decision, the decision wins and the disagreement is
noted in place.

What this document is for: the Product Owner's own review, an external
second opinion, and a later formal decision process (freeze). What it does
**not** do: authorize any implementation, code, test, dependency, schema,
migration, or frozen-document change. Two frozen documents and one
constitutional invariant need amendment for this design to be implementable;
each amendment is drafted here as replacement text (§4.3, §6.9, §6.10) and
each is a separate Product Owner freeze decision.

No source, test, `FROZEN` document, or runtime artifact was changed by the
session that produced it. No live database, LDAP/AD, or device call was made.

---

## 0. How to read this document

- §1 is the purpose statement — the bar every later choice is measured
  against. Read it first.
- §2 lists what the requirements draft left open and what each is now
  resolved to. It is the index of decisions.
- §3 is the parallel-track operating model and the mechanical per-feature
  migration ladder (`AC-1`).
- §4 is the shell boundary and the `CON.0` amendment text (`AC-2`).
- §5 is authorization: role model over `D1`/`D7` (`AC-3`).
- §6 is the backup subsystem, in the most depth (`AC-4`).
- §7 is authentication and sessions (`AC-5`).
- §8 is the Java stack and the `CE.2` worked port (`AC-6`).
- §9 records the storage decision (`AC-7`).
- §10–§14: invariants preserved, contradictions reported for freeze,
  still-open items, acceptance gates for the eventual implementation, next
  movement.

Vocabulary: `D1`…`D7` and `E1`…`E7` are the frozen capability-state
dimensions and gates of `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`
(`AC-CS`). "Line-1" / "Track A" is the current Python `M`-series and feature
line; "UI 2.0" / "Track B" is the product designed here. `RB.x`, `PCP.x`,
`CON.x`, `M14`, `CE.x` keep their repository meanings. `LD-1`…`LD-7` are
`M14`'s decisions. "Backbox" is the Product Owner's named reference product
for the backup subsystem's operating model (§6).

---

## 1. Purpose — the bar (`AC-0`)

Today's console (`py main.py --console`) is a thin authenticated wrapper over
collection modules that were built to produce a static HTML/JSON export. The
requirements draft measured exactly where that shows: six unconditional
`run_html_export` tail calls in `application/workflows/*`, eight `*_ui.py`
payload builders as the console's only read model, `output_root` JSON files as
the inter-stage medium and the `entity_ids` target universe, a shell that is
the report's own script bundle, and a compliance evaluator keyed on a UI
projection (`UI2_0_ARCHITECTURE_REQUIREMENTS.md` §2.4). The observed symptom
— content on first load, then breakage when a real job ran — is what that
shape produces under real use. It is a demonstration surface, not an
operational tool.

The Product Owner's actual product goal is a **production-grade operator
console a real network security team runs daily**: onboard devices, pull
configuration and inventory, run compliance checks, manage backups, monitor
failover readiness — and trust the result. UI 2.0 is that product's first
real definition. The Product Owner has decided that it is worth a large,
deliberate, incremental commitment — a new language stack, a new
authentication model, a real backup subsystem — rather than a patch on the
static-export console.

**The bar this document applies to every choice below:** *would a real
operator trust and rely on this daily?* Concretely, that means:

- **Honest state, always.** Every screen shows what the product knows, how
  fresh it is, and what it does not know. `UNKNOWN` is a first-class answer;
  a blank, a stale number presented as current, or a control that silently
  vanishes is a defect (`AGENTS.md` UNKNOWN/fail-closed law; `AC-CS` §6.5).
- **Actions are safe by construction, not by convention.** The closed
  typed-intent boundary, server-side authorization, audit-before-action and
  the action taxonomy are inherited invariants, re-stated as tests in the new
  language (§8.4, §13).
- **Nothing ever surprises the operator with device contact.** No screen,
  refresh, or dashboard causes a collector to run; device contact happens only
  as an explicitly submitted or explicitly scheduled typed job under the
  admission coordinator (`CON.0` §7.10, `PCP` §9).
- **Multi-operator by design.** Several administrators, concurrently, each
  identified against the corporate directory, each action attributable
  (§5, §7).
- **The backup subsystem is the operating model the team already knows.**
  Profile-driven, expect-style step validation, per-device state, history and
  readiness — the Backbox model (§6), implemented under this product's
  evidence, identity and taxonomy laws.
- **Every migrated feature carries its proof with it.** A feature is not
  "ported" until the Java implementation has passed the same conformance
  fixtures the Python one earned, and the real-environment status is
  re-earned, never inherited (§3.3, §8.5).

Everything below is a design consequence of that bar plus the frozen
authority it must respect.

---

## 2. What was open, and what it is now

| id | Left open by the requirements draft | Resolved to (this document) | Section |
| --- | --- | --- | --- |
| `C-1` | separate shell vs inside the existing payload model; `CON.0` §3/§6 | **Separate shell/application.** `CON.0` §6 amended to "both surfaces read the same persisted projections"; `CON.0` §3 bundler rule narrowed to the shared report bundle. Replacement text drafted | §4 |
| `C-2` | "role-based menu authorization" vs `AC-CS-33` | **RBAC is `D1`/`D7`, never menu-hiding.** Entries render per `D1`; every action shows its `D7` outcome; the server refuses. Concrete role model: AD group → role token → action set, evaluated per request server-side | §5 |
| `C-3` | class 1 backup from a browser; `C-D7` | **Backbox-style profile-based backup subsystem** with a browser UI for state, readiness, history, profile selection, target selection and schedule intent; execution stays class 1 under `RB.x`; the browser never submits a class 1 run; a `D7`-authorized, audited **schedule-edit intent** replaces `C-D7`'s "not in this track" for UI 2.0 | §6 |
| `C-4` | `M14` single-operator vs multi-user login | **Concurrent multi-admin, single active session per identity**, LDAP instead of OIDC, successor to `DEPLOY.1A`'s auth shape; `M14` decisions carried over or amended one by one | §7 |
| `U-1` | the exact Java requirement | **Full Java stack** for UI 2.0: service layer, API, DB access, and every feature once migrated. Not a thin layer over Python workers. Ported per feature, `CE.2` as the worked example | §8 |
| `X-2` | storage engine | **Already resolved — recorded, not re-derived.** PostgreSQL for control-plane / projection / audit state; SQLite local; CAS and recovery store on the volume; Oracle deferred, not rejected | §9 |

Consequences of these decisions that the requirements draft's own analysis
did not anticipate, and that this document therefore reports as
contradictions for freeze rather than reconciling silently: the constitutional
"no Browser → device path" invariant against in-browser backup-profile
authoring (§6.10), `PCP` §11's "the operator does not choose SSH/API commands"
against operator-authored profiles (§6.9), and `M14` §6 rule 4 ("the browser
never handles a directory credential") against a browser login form (§7.4).

---

## 3. The parallel-track operating model (`AC-1`)

### 3.1 Two tracks, neither blocks the other

**Track A — "Line-1".** The existing Python feature and collection-module
line: the `M`-series under `LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`
§12, `PCP.x`, `CE.x`, `RB.x`, `OP.x`, the compliance and configuration
collectors, the identity and reconciliation producers. **It continues exactly
as before, indefinitely.** Its authority documents, its test suite, its
real-environment validation ladder, its relay/orchestration process and its
`main.py` entry point are unchanged by UI 2.0's existence. No Line-1 movement
waits for a UI 2.0 milestone, and no UI 2.0 movement may block a Line-1 merge.

**Track B — UI 2.0.** The separate Java application designed here: its own
repository sub-tree or repository (§8.3), its own build, its own tests, its
own deployment unit, reading and writing the shared PostgreSQL state (§9) and
the shared filesystem volumes (CAS, recovery store) through contracts, not
through Python imports.

**What connects them** is not code but **persisted projections and typed
job records**: Track A producers write evidence-grade projections; Track B
reads them. Until a feature is ported, Track B *reads* what Track A produced
and *submits* typed jobs that Track A executes (§3.2, states `F2`/`F3`).
After a feature is ported, Track B does both.

**Why per-feature and never stop-the-world.** The requirements draft's §6.4
cost analysis stands and is the reason: the collectors encode
real-environment-validated vendor semantics that no specification captures,
and `AGENTS.md`'s evidence laws reset every `REAL_ENV_VALIDATED` verdict on
re-implementation. A stop-the-world port would freeze Line-1 and reset every
verdict at once. A per-feature port resets one verdict at a time, for a
feature that has already matured to real-environment validation in Python,
with that Python implementation still running as the reference until the
Java one has re-earned the verdict.

### 3.2 The per-feature migration ladder — what mechanically changes

A feature moves through named states. The state is recorded per feature in
`project/feature_registry.json` (a new `ui2_0_port_state` field is proposed;
not added by this document). Each transition names what changes in the code,
the storage, the request path and the proof.

| State | Name | Where the feature's logic runs | What UI 2.0 does with it | What changes to reach this state |
| --- | --- | --- | --- | --- |
| **F0** | Line-1 only | Python, as today | nothing — the feature is invisible to UI 2.0 | — |
| **F1** | Mature in Line-1 | Python; the feature has reached `REAL_ENV_VALIDATED` (or `AUTOMATED_VALIDATED` where the feature has no device contact) | still nothing | the feature's own Line-1 movements; this is the **entry condition** for porting — nothing is ported from `F0` |
| **F2** | Projected | Python writes; Java reads | UI 2.0 **renders** the feature from persisted projections | **Storage:** the Python workflow writes its result as an evidence-grade projection row set (provenance, freshness, `device_id`/`entity_id` keys, qualifier facts) into PostgreSQL through the existing `utils/evidence_backend.py` seam (a new concern, or an existing one such as the config-snapshot index). The tail `run_html_export` call for that workflow becomes conditional on an explicit report request. **Contract:** the projection's row shape is frozen as a versioned schema + JSON conformance fixtures under `tests/fixtures/ui2_conformance/<feature>/`, generated by the Python producer's own tests. **Java:** a read model + API resource over those rows; the Java build consumes the same fixtures. **Proof:** fixture parity (Java reads what Python wrote, byte-for-byte on the wire shape) |
| **F3** | Intent-routed | Python executes; Java submits | UI 2.0 **submits typed jobs** for the feature | **Request path:** the Java service inserts a `job_submissions` row (the `M4` schema shape, in PostgreSQL) with `job_type` + opaque targets + actor + idempotency key, after `E1`–`E6` (§5.4). A Python worker (`main.py --worker`, a bounded new mode) claims it and executes through `execute_admitted_collection` / `main.main(argv)` exactly as `console/runner.py::_execute` does today — the argv template stays in `workflow_argv`, in one place, in Python. **Proof:** the job record round-trips both ways (Java-written submission → Python-written run + outcome → Java-rendered state) against fixtures; one orchestration path is test-enforced in both languages (`AG-U2`) |
| **F4** | Ported (shadow) | **Both**: Java implementation exists and runs in shadow; Python remains authoritative | UI 2.0 shows Python's result; Java's result is recorded for comparison only | **Java:** the feature's logic re-implemented (§8.5 for the worked example) behind the same projection schema and the same job type. **Runtime:** the Java implementation runs the same job against the same evidence (or, for device-contacting features, against the same device under the admission coordinator's existing budget — never additional contact: the shadow run *replaces* the Python run for that job on the selected pilot targets, it does not double it). **Proof:** the frozen conformance fixtures pass in Java; for device-contacting features, real-environment validation is re-executed under `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md` and a SAFE SUMMARY records `MATCH` between the Python and Java projections for the same device (relationship, never values) |
| **F5** | Ported (authoritative) | **Java**; Python implementation retired for this feature | UI 2.0 owns the feature end to end | **Retirement:** the Python job type is removed from `console/registry.py` and `ALLOWLISTED_WORKFLOWS` for that feature *only after* `F4`'s proof is recorded in `project/build_history.json`; the Python module stays in the repository as reference until a later cleanup movement. The report exporter, if it still renders the feature, reads the same projection rows Java now writes (§4) |

Rules that make the ladder safe:

1. **A feature enters the ladder only at `F1`.** Porting an immature feature
   would fork it; Line-1 keeps sole ownership until real-environment maturity.
2. **`F2` and `F3` are the default resting states** for most of the product
   for a long time. They are already real value: an operator uses UI 2.0 for a
   feature whose logic is still Python. This is what makes the tracks
   genuinely parallel — Track B ships screens while Track A ships producers.
3. **Nothing is ever ported directly `F1` → `F5`.** The shadow state `F4` and
   its parity proof are mandatory. Skipping it would be exactly the "assumed,
   not proven" port the evidence laws forbid.
4. **The projection schema is the contract, not the code.** A Line-1 movement
   may change a Python producer freely as long as the frozen projection schema
   (or its versioned successor) still validates. A schema version bump is a
   cross-track change and needs both tracks' tests green.
5. **Device contact never doubles.** During `F4`, the shadow run replaces the
   Python run on the pilot targets; aggregate contact per endpoint stays under
   the admission coordinator's budget (`PCP` §9, `AGENTS.md` "do not increase
   polling/concurrency").
6. **A feature at `F5` may still be invoked from the CLI.** The Java service
   exposes it as a typed job; the operator-facing CLI parity that
   `LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` and `PCP.1` insist on is
   provided by a thin Java CLI (§8.3) — the Python `main.py` is not the only
   CLI forever, and the *product* rule "every UI action has a CLI equivalent"
   survives the language change.

### 3.3 Worked ladder for three current features (illustrative, not authorized)

| Feature | Today | Enters at | Notes |
| --- | --- | --- | --- |
| Device Registry (`PCP.1`) + enrollment (`M9`) | filesystem JSON, CLI + loopback console intents | `F1` now | `F2`: registry rows through `DeviceRegistryBackend` → PostgreSQL (the governed storage movement `PCP` §10 already requires, with its parity proofs); `F3`: enrollment intent submitted from Java, executed by Python (audit-before-mutation preserved); `F4`/`F5`: the registry's business rules ported (normalization, duplicate detection, lifecycle, lock) with the `PCP.1` test contract as fixtures |
| CE.2 compliance primitives | Python registry + opt-in probe, `AUTOMATED_VALIDATED` | `F2` once real-env validated (`F1` not yet reached — real-device output shape validation owed) | the §8.5 worked example describes `F4`/`F5` for it |
| HA readiness (`OP.0a/0c`) | offline derivation over collected telemetry | `F1` now (no device contact) | `F2`: readiness projection per HA unit with freshness; `F4`/`F5`: the verdict roll-up ported with the generated-matrix test (`SAFE_TO_FAILOVER` unreachable) as the primary fixture |

---

## 4. The shell boundary (`AC-2`, resolves `C-1`)

### 4.1 Decision

**UI 2.0 is a separate shell and application.** It is not built inside the
existing console's `/api/payloads` eight-payload model, does not serve the
report's script bundle, and is not bound to `CON.0` §3's "no frontend
framework / bundler" row. It has its own typed read API (per module, per
entity, freshness-carrying), its own front-end build, and its own deployment
unit. Today's console (`CON.1`/`CON.2`) keeps working unchanged until each of
its modules exists in UI 2.0 (`F2`+), and is retired only then (the
requirements draft's Stage D).

**The exported report survives as a product deliverable** (`CON.0` §1) and
becomes **one consumer among others** of the persisted projections. It is
produced on explicit request (`report_rebuild` already exists as a job) and
stays action-free (`AC-SH-1`).

### 4.2 What "both surfaces read the same persisted projections" means precisely

| Term | Meaning |
| --- | --- |
| **persisted projection** | an evidence-grade row set written by a producer at collection/derivation time into PostgreSQL (or, for local mode, SQLite) through the evidence-backend seam: keyed by `device_id` and/or `entity_id`, carrying `collected_at`/`derived_at`, provenance, completeness, and the qualifier facts the resolver needs. **Never** resolved presentation state (`AC-ST-3`): `primary_status`, tone, copy and affordances are computed per request over projections, never stored as truth |
| **surface** | the exported report, today's console, UI 2.0's shell — each with its own `D1` (declared surface set) and its own declared action set (`I20`) |
| **same** | for a surface both shells ship, identical evidence yields identical `primary_status`, identical `capability_qualifiers`, identical `evidence_presentation` (`AC-CS-39`, `AC-CS` §6.8). The **wire shape** may differ per surface; the **facts** may not |
| **parity test** | replaces `CON.1` AC-4's payload-equality test: a fixture-driven test that renders one persisted projection set through each surface's resolver and asserts the `AC-CS-39` triple is equal per shell-scoped surface |

### 4.3 Proposed amendments to `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`, ARCHITECTURE FROZEN)

Drafted in the shape `AC-CS` §2.7 uses for frozen-parent amendments: clause
amended, wording replaced, conflict resolved, correction now in force, owner,
status. Both rows are **PROPOSED**; applying them is a Product Owner freeze
decision. The full replacement text follows the table.

| # | Parent clause amended | Wording replaced | Conflict resolved | Correction now in force | Owner | Status |
| --- | --- | --- | --- | --- | --- | --- |
| **UA-1** | `CON.0` §6 "Two delivery modes, one UI source", the closing invariant (l.205–207) | *"Invariant: **the console never introduces a payload shape the exporter does not also produce.** If the console needs a field, the builder gains it and both surfaces get it. Enforced by an equality test in `CON.1` (AC-4 there)."* | UI 2.0 is a separate application with a typed, per-module, freshness-carrying read API; byte-identical payload shapes between it and the static exporter are neither achievable nor desirable. What must be preserved is that no surface can show a *different truth* from another for the same evidence | replace the invariant with a **projection-parity** invariant: every surface reads the same persisted projections; no surface computes a fact from a source another surface cannot read; `AC-CS-39` parity holds per shell-scoped surface; the equality test becomes the projection-parity test (§4.2) | Product Owner (`UI2_0` freeze) | **PROPOSED** 2026-09-09 |
| **UA-2** | `CON.0` §3 "What this is not", the row *"A frontend framework / bundler"* (l.87) | *"A frontend framework / bundler \| Breaks the 'one portable inline script, no build step' invariant just frozen \| `CODEBASE_MODULARIZATION_FRONTEND.md` D-MOD1"* | the row's reason is about the **portable report bundle** (one inline script, no build step, survives without a server). UI 2.0 is not a portable file and never becomes one; applying the row to it would forbid a production application from having a build | narrow the row to the **shared report bundle**: the exported report and any surface that inlines the report's script modules keep the no-framework/no-bundler rule; a separate application that does not ship the report bundle is not bound by it, provided the report bundle itself gains no build-step dependency | Product Owner (`UI2_0` freeze) | **PROPOSED** 2026-09-09 |

**UA-1 — replacement text for `CON.0` §6, closing invariant:**

> Invariant: **every delivery surface reads the same persisted projections.**
> A projection is an evidence-grade row set written by a producer at
> collection or derivation time (keyed by `device_id` / `entity_id`, carrying
> collection timestamp, provenance and completeness); it is never resolved
> presentation state. No surface computes a fact from a source another
> surface cannot read, and no surface introduces a projection another surface
> could not consume. Surfaces may differ in wire shape, declared action set
> and liveness (`CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §6.8); they
> may not differ in `primary_status`, `capability_qualifiers` or
> `evidence_presentation` for the same evidence on a surface both ship
> (`AC-CS-39`). Enforced by a projection-parity test rendering one persisted
> projection set through each surface's resolver. The static exporter is one
> consumer of the projections; a collection workflow renders the portable
> report only on explicit request, never as an unconditional tail step.
> *(Amended 2026-09-09 under the `UI2_0` freeze; the prior payload-equality
> wording and `CON.1` AC-4's equality test are superseded for surfaces other
> than the report-derived console, which keeps them until it is retired.)*

**UA-2 — replacement text for the `CON.0` §3 row:**

> | A frontend framework / bundler **in the shared report bundle** | The exported report is one portable inline script with no build step (`CODEBASE_MODULARIZATION_FRONTEND.md` D-MOD1); any surface that ships that bundle inherits the rule. A separate application that does not ship the report bundle (UI 2.0, `UI2_0_ARCHITECTURE_DESIGN.md` §4) may use its own build, provided the report bundle itself gains no build-step or framework dependency | `CODEBASE_MODULARIZATION_FRONTEND.md` D-MOD1; `UI2_0_ARCHITECTURE_DESIGN.md` §4 |

**Companion note for `PCP` §13** (FROZEN; "the control plane's console **is**
the `CON.x` console"): once `UA-1`/`UA-2` are applied, that sentence should
read "…is the `CON.x` console until each module ships in UI 2.0, which
inherits `CON.0` §4 (intent boundary), §7 (security model) and §9 (honest
affordances) unchanged and is bound by `CON.0` §6 as amended by `UA-1`". This
is a one-sentence amendment for the same freeze decision, not a separate
design question. `PCP` §13's payload-parity sentence ("if the device
experience needs a field, the payload builder gains it for both surfaces")
becomes "the projection gains it for every surface".

### 4.4 What UI 2.0 inherits from `CON.0` verbatim

`CON.0` §4 (the browser sends intent, never a command), §4.1 (typed
enrollment intent), §7 rules 4–10 (credentials never reach the browser; worker
trust zone; no recovery payload over HTTP; three independent gates for a
class 1 action; identity-lean job records; audit before action; device
contact frequency unchanged), and §9 (honest affordances — the five action
states with the reason always shown). Rules 1–3 (loopback, per-launch bearer
token, data-free shell) are replaced by §7 of this document: a network-exposed
listener behind TLS with LDAP-authenticated sessions is exactly the
`DEPLOY.1A`-class decision `CON.0` §7.1 reserved.

---

## 5. Authorization — RBAC is `D1`/`D7`, never menu-hiding (`AC-3`, resolves `C-2`)

### 5.1 Decision

Role-based authorization in UI 2.0 is implemented exactly as `AC-CS-33`,
`AC-CS-35` and `AC-CS` §5.3 already require: **every navigation entry always
renders per `D1`** (surface eligibility); **every declared action shows its
own `D7` outcome** (`PERMITTED` / `DENIED(authority, reason)` /
`AUTHZ_NOT_EVALUATED(authority)` / `NO_APPLICABLE_AUTHORITY`); **the server
refuses an unauthorized action** independently of anything the UI showed. The
UI's job is to say "you cannot do this, and here is why" — never to make the
option disappear. No client-side visibility toggle exists; the front end has
no role concept at all beyond rendering the affordance the server resolved.

Why, beyond "the contract says so": a hidden control tells an operator
nothing, teaches them nothing about who to ask, and hides the product's actual
authorization posture from audit. A visible, refused control with a named
authority and reason is how a daily-use tool earns trust (§1).

### 5.2 The role model — three levels of indirection

```
action_id  ──►  role token  ──►  role binding  ──►  AD group reference
(source,        (source,         (PostgreSQL row,    (corporate directory;
 closed          reviewable)      admin-edited,       never in the repository,
 registry)                        audited)            referenced by opaque
                                                      binding id in every log)
```

- **`action_id`** — the closed action vocabulary. In UI 2.0 this is the Java
  action registry (§8.4), the successor of `console/registry.py`'s
  `JOB_REGISTRY` plus the non-job intents (enrollment confirm, schedule edit,
  profile approve, waiver edit, …). Every entry declares exactly one
  **required role token** (or none → `NO_APPLICABLE_AUTHORITY`, per `LD-4`).
- **Role tokens** — repository-committed, reviewable, containing no corporate
  identity (`LD-4` carried over unchanged). Proposed initial set, chosen to
  match what the product actually distinguishes today rather than an
  aspirational matrix:

| Role token | May (examples) | May not |
| --- | --- | --- |
| `role:viewer` | read every projection UI 2.0 ships (inventory, configuration, compliance, backup state/history, HA readiness, jobs, audit *of own actions*) | submit any job or intent |
| `role:operator` | everything `viewer` may, plus submit class 0 typed jobs ("collect now", identity probe, report rebuild, compliance evaluation), retry | enrollment confirm; schedule edit; any class 1; policy writes |
| `role:onboarding_admin` | `operator` plus enrollment preview/confirm, registry disable, credential-profile *reference* assignment | schedules; class 1; profiles |
| `role:backup_admin` | `operator` plus backup **schedule-edit intent** (§6.7), backup target-set edit, profile **selection/assignment**, profile approval where author ≠ approver | authoring an executable profile in the browser (§6.10 until amended); direct class 1 submission (never, any role) |
| `role:compliance_admin` | `operator` plus benchmark assignment, waiver edit, check-pack enable/disable, advisory ↔ enforced | class 1; schedules outside compliance evaluation cadence |
| `role:security_admin` | role-binding administration (bind a role token to an AD group reference), session administration (end another actor's session, §7.6), audit read across all actors | any device-facing action — separation of duties is deliberate: the actor who administers authorization does not also execute |

  Tokens are additive by explicit declaration in the registry entry, not by
  hierarchy inference; the "plus" wording above is a reading aid, the source
  of truth is one token per action. A later movement may split or add tokens;
  the model does not change.

- **Role bindings** — the one place a corporate identity lives: a PostgreSQL
  row `role_bindings(binding_id uuid, role_token text, group_reference text,
  created_by principal_fingerprint, created_at, revoked_at, revoked_by)`.
  `group_reference` is the directory's own group identifier as returned by
  the directory (opaque; never normalized, never case-folded — identity law),
  stored encrypted at rest under the application role's key, and **never
  written to any log, API response, or projection**: every audit line names
  `binding_id`, and the UI's role-binding administration screen shows the
  directory's display attribute only to `role:security_admin` and marks the
  screen `LOCAL_OPERATOR_SENSITIVE`. `M14` §11 row 4 said moving the
  role → group binding into a database "changes where a corporate identity
  lives" and is a privacy decision: **this document takes that decision** for
  UI 2.0, because a multi-admin server cannot be reconfigured by environment
  variable on each administrator's laptop, and the mitigation is the
  encryption, the `binding_id` indirection and the audit above.

### 5.3 Evaluation — per request, server-side, in UI 2.0's own path

Every mutating request and every render request that carries affordances runs
the frozen gate order, in Java, in one interceptor chain that a request cannot
bypass (test-enforced, §13 `AG-J2`):

```
request ──► E1  session authenticity (§7): valid session id, bound to this actor,
        │       not superseded, CSRF token valid, origin checked
        ├─► A1' actor resolution: session → actor record (fingerprint, resolved
        │       group set with resolved_at)                                [E1 family]
        ├─► E2  action identity: action_id ∈ closed Java registry
        ├─► E3  taxonomy admissibility: ActionClass.consoleSubmittable
        │       (class 1: refused here, always — §6.6)
        ├─► E4  D7 = A2'(actor, action_id):
        │         no role token on action          → NO_APPLICABLE_AUTHORITY
        │         token bound, actor ∈ group        → PERMITTED
        │         token bound, actor ∉ group        → DENIED(ui2_ldap, actor_not_in_required_group)
        │         token bound, group set stale/unresolvable → AUTHZ_NOT_EVALUATED(ui2_ldap)
        │         token declared, no active binding → AUTHZ_NOT_EVALUATED(ui2_ldap)   [LD-4 rule]
        ├─► E5  subject/target integrity the action's contract requires
        ├─► E6  the action's declared prerequisites
        └─► E7  (execution phase only) admission + immediately-before-execution checks
```

Rules carried from `M14` §4 verbatim: `E3` is never re-evaluated inside `E4`;
both reasons are recorded when `E3` refuses and `E4` denies, authorization
last (`H5c`); `E4` reads only the actor, the action id and the bindings —
never a capability projection (`AC-CS-68`); a configured authority that
cannot be evaluated is `AUTHZ_NOT_EVALUATED`, never `NO_APPLICABLE_AUTHORITY`
(`LD-2`'s banned transition, `AG-4`).

**Membership evaluation.** The actor's group set is resolved from the
directory at login (§7.3) and re-validated on `LD-3`'s bounded interval,
against the directory, using the directory's own membership semantics
(nested-group matching stays `UNKNOWN` until `M14` `U-1` closes with
Microsoft documentation — carried over as an open item, §12). The per-request
check is then a set membership over the actor's resolved group references
against the binding's `group_reference`, exact after whitespace stripping —
never a client-side normalization (identity law).

**Render path.** `GET` requests that return affordances run `E1`–`E6` per
declared action and emit `action_affordance[action_id]` exactly as `AC-CS`
§5.4 specifies. The front end renders it; it decides nothing.

### 5.4 Storage and audit

| Table | Purpose | Privacy |
| --- | --- | --- |
| `role_bindings` | §5.2 | group reference encrypted; `binding_id` is the only identifier that leaves the table |
| `authz_decisions` | append-only: `decided_at, session_id, actor_fingerprint, action_id, target_ref, outcome (4-valued), authority, reason_code, binding_id?` | no DN, no group name, no target address; `target_ref` is `device_id`/`entity_id` — opaque |
| `sessions` | §7.5 | — |

`authz_decisions` is the queryable authorization audit `M14` §11 row 2
described and did not build. Retention: a per-table policy decided at
`DEV.4.6`; default proposal 400 days. Read access: `role:security_admin`
across actors; every actor for their own rows. It never enters the support
bundle.

---

## 6. The backup subsystem — profile-based, Backbox-style (`AC-4`, resolves `C-3`)

This is the part of the design the Product Owner cares most about getting
right. It is designed to depth accordingly. Where a Backbox reference
screenshot would settle a UI detail, the detail is marked `[SCREENSHOT]` and
a reasonable default is chosen so nothing blocks on it (§6.11).

### 6.1 Operating model, in one paragraph

A **device** exists because it was onboarded through the Device Registry —
discovery candidate or manual enrollment, identity-verified, trust-first
(`PCP.1`, `M8`/`M9`). Backup does not add devices. A **backup profile** is a
named, versioned, ordered sequence of **steps**; each step is one SSH or
SFTP interaction with an **expectation gate** — the step succeeds only if the
device's response matches what the profile author declared, and the next step
runs only if the previous one succeeded, exactly like an `expect` script. A
profile is written per vendor/version from that vendor's own admin guide
(`"CP R81.20 Gaia"`, `"PAN-OS 11 device-state"`, `"Vendor X model Y"`), so a
device the product has **no native collector for** is backed up the same way
as a Check Point gateway, as long as SSH/SFTP is reachable and someone
authored the steps. There is **no built-in static backup method**: every
backup run is "profile P against device set S", and the two native
implementations the product already has (`RB.2` PAN device-state export,
`RB.3` CP Gaia backup) are re-expressed as the first two shipped profiles.
The UI shows per-device backup **state, readiness and history**, lets a
`backup_admin` **select** a profile, **select** target devices, and set a
**schedule**; execution is a class 1 typed job under the `RB.x` ledger,
credential and allowlist contracts, run by the scheduler or the worker — never
started by a browser request.

### 6.2 Objects

```
DeviceRecord (PCP.1, unchanged)        BackupProfile ─┬─ ProfileVersion ─── Step[]
      │                                               │        (immutable once APPROVED)
      │  device_id                                    └─ ProfileState: DRAFT → VALIDATED → APPROVED → RETIRED
      ▼
BackupAssignment {device_id, profile_id, profile_version, credential_profile_ref, trust_profile_ref,
                  enabled, created_by, approved_by?}            ← "which profile backs up this device"
      │
      ▼
BackupSchedule {assignment_id or device_set_id, cadence, window, retention_policy_ref, enabled}
      │
      ▼  (scheduler / worker, never the browser)
BackupRun {run_id, assignment_id, started_at, provenance, actor?, outcome, step_results[], artifact_ref?, ledger_entry_ref}
      │
      ├─► StepResult {step_index, kind, outcome, matched_expectation?, duration_ms, error_class?, output_fingerprint, output_bytes}
      └─► Artifact (recovery store, encrypted, manifest — RB.x unchanged) + BackupVerification (RB.4 V1–V4)
```

Storage: profiles, assignments, schedules, runs and step results are
PostgreSQL rows (control-plane state, §9). Artifacts and manifests stay in the
recovery store on the volume, exactly as today. No artifact byte ever enters a
database row or an HTTP response (`CON.0` §7.6, `BACKUP_AND_RECOVERY` §10).

### 6.3 The profile model

A profile version is data — JSON (or YAML that compiles to the same JSON) —
with no code, no expression language, and no free-form shell. Illustrative
shape (field names are proposals for the eventual contract):

```json
{
  "profile_id": "cp_gaia_r81_20_backup",
  "version": 3,
  "title": "Check Point Gaia R81.20 — local backup + SFTP fetch",
  "vendor_hint": "check_point",
  "transport": { "kind": "ssh", "port_ref": "default", "host_key_policy": "strict_known_hosts" },
  "credential_profile_ref": "backup-cp",
  "terminal": { "pager": "off", "width": 200, "prompt_regex": "(?m)^[\\w.-]+> $|^\\[Expert@[\\w.-]+:\\d+\\]# $" },
  "action_class": "recovery-write",
  "preconditions": [
    { "kind": "exec", "send": "df -k /var/log", "expect": { "regex": "(?m)^\\S+\\s+\\d+\\s+\\d+\\s+(\\d+)", "capture": "free_kb" },
      "assert": { "op": "gte", "left": "{{free_kb}}", "right": "{{expected_artifact_kb}}", "factor": 3 } }
  ],
  "steps": [
    { "kind": "connect",  "expect": { "regex": "{{terminal.prompt_regex}}", "timeout_s": 15 } },
    { "kind": "exec",     "send": "clish -c 'show version all'", "expect": { "regex": "(?i)product version", "timeout_s": 20 },
      "gate_review_ref": "cp_gaia_show_version_all" },
    { "kind": "exec",     "send": "clish -c 'add backup local'", "expect": { "regex": "(?i)creating backup package", "timeout_s": 30 },
      "gate_review_ref": "rb3b_add_backup_local", "class": "recovery-write" },
    { "kind": "poll",     "send": "clish -c 'show backup status'", "until": { "regex": "(?i)backup succeeded" },
      "fail_on": { "regex": "(?i)backup failed" }, "interval_s": 15, "timeout_s": 900 },
    { "kind": "exec",     "send": "ls -1t /var/log/CPbackup/backups/ | head -1", "expect": { "regex": "(?m)^(backup_\\S+\\.tgz)$", "capture": "artifact_name" } },
    { "kind": "sftp_get", "remote": "/var/log/CPbackup/backups/{{artifact_name}}", "expect": { "min_bytes": 1048576, "max_bytes": 4294967296 } },
    { "kind": "verify",   "checks": ["sha256_recorded", "tar_header_readable", "size_within_bounds"] }
  ],
  "finally": [
    { "kind": "exec", "send": "clish -c 'delete backup local {{artifact_name}}'", "expect": { "regex": "(?i)deleted|removed" },
      "gate_review_ref": "rb3b_delete_backup_local", "class": "recovery-write", "on_failure": "record_and_continue" },
    { "kind": "disconnect" }
  ],
  "artifact": { "class": "cp_gaia_backup", "content_type": "application/gzip", "retention_policy_ref": "gfs_default" },
  "output_policy": "discard_raw"
}
```

Every command string and expectation above is **illustrative**. Vendor
semantics law: each `send` in a shipped profile must be backed by a
network-device command-gate record (`gate_review_ref`) whose semantics were
established from official vendor documentation and real-environment evidence,
exactly as `RB.3b`'s `add backup local` contract already is; the example
reuses the command names the frozen `RB.3` contracts already gate-reviewed
and asserts nothing new about them.

**Step kinds (closed set):**

| kind | What it does | Expectation gate |
| --- | --- | --- |
| `connect` | opens the SSH session through the existing trust preflight (strict known-hosts, no TOFU) and authenticates with the referenced backup credential | the login banner/prompt must match `expect.regex` within `timeout_s`; a mismatch is `STEP_EXPECTATION_UNMET`, never "probably fine" |
| `exec` | sends one line, reads until the prompt regex or timeout | the response must match `expect.regex` (optionally capturing named groups into profile variables); an optional `fail_on.regex` short-circuits to failure with the matched class |
| `poll` | repeats `send` every `interval_s` until `until.regex` matches, `fail_on.regex` matches, or `timeout_s` elapses | `until` matched → success; otherwise failure named (`STEP_POLL_TIMEOUT` / `STEP_POLL_FAILED`) |
| `sftp_get` / `scp_get` | fetches one remote path into the recovery store's staging area (streamed; never to a database) | size bounds; transfer integrity (SFTP checksum where the server supports it, else size + local digest recorded) |
| `sftp_put` | reserved, refused at validation: no profile may push bytes to a device at current maturity (class 3 territory) | — |
| `verify` | local checks over the fetched artifact (`RB.4` V1–V4 mapped: digest recorded, container header readable, size within bounds, vendor-format signature where defined) | all listed checks pass |
| `disconnect` | closes the session | — |

**Variables** are only: captured groups from previous steps, profile
constants, transport/terminal settings, and **credential references** (never
values — a `{{secret}}` placeholder does not exist; authentication is done by
the transport from the referenced profile, and a `send` containing anything
that resolves to secret material is rejected at validation).

**Expectation semantics**, stated so a test can be written against each:

1. A step with no `expect` (or `until`) is invalid — every step gates
   (validation error `STEP_WITHOUT_EXPECTATION`).
2. Matching is anchored where the author anchored it; patterns carry the
   `CE.1` `D3` safeguards verbatim (512-char cap, complexity linter, wall-clock
   timeout; timeout ⇒ step `UNKNOWN` ⇒ run fails closed).
3. **Fail-closed on ambiguity.** `expect` and `fail_on` both matching is a
   failure (`STEP_AMBIGUOUS_RESPONSE`).
4. **A timeout is never a success**, and a prompt reappearing without the
   expected text is `STEP_EXPECTATION_UNMET`.
5. A step's failure stops the sequence; `finally` runs regardless, each
   `finally` step recording its own outcome; a `finally` failure is
   `RUN_CLEANUP_INCOMPLETE` and surfaces in the UI as an actionable state
   (an on-device artifact may remain — the operator must know).
6. Captured variables are validated against a declared shape (`capture`
   with a `pattern`); a capture that could contain a path separator or shell
   metacharacter is rejected at validation unless declared `path_component`
   with an allowlist pattern — a `send` template can never receive
   uncontrolled device output.

### 6.4 Raw output handling — the raw-evidence law inside an expect engine

An expect engine necessarily reads raw device output into memory. The design:

- the session transcript lives **in memory only**, bounded (proposed 4 MiB
  per step, then the step fails `STEP_OUTPUT_OVERFLOW`);
- after each step, the engine records: outcome, which expectation matched,
  `output_bytes`, `output_lines`, `fingerprint_sha256` of the exact text, and
  the **captured variables only** — the transcript is discarded in the same
  function that captured it (the `CE.2` `_redact_text` pattern);
- `output_policy: "discard_raw"` is the only value at current maturity; a
  future `retain_redacted_excerpt` would need its own evidence/forensics
  contract (`AGENTS.md` raw-evidence law) and is refused by validation now;
- the fetched **artifact** is not a transcript: it goes to the recovery
  store's encrypted envelope exactly as `RB.x` defines, with a manifest.

### 6.5 The profile lifecycle, and who may do what

| State | Meaning | Transition | Who (role token) |
| --- | --- | --- | --- |
| `DRAFT` | authored, editable | validate → `VALIDATED` | authoring path per §6.10 (phase 1: CLI/file import; phase 2: browser editor after the amendment) |
| `VALIDATED` | passed static validation: schema, closed step kinds, every step gated, pattern safeguards, denylist/class consistency (§6.6), `gate_review_ref` present for every `send`, no secret placeholders, capture shapes declared | approve → `APPROVED` | `role:backup_admin` **other than the author** (four-eyes; enforced by the server from `created_by ≠ approver`) |
| `APPROVED` | immutable; assignable; schedulable | new version → new `DRAFT`; retire → `RETIRED` | assignment: `role:backup_admin`; retirement: `role:backup_admin` |
| `RETIRED` | not assignable; runs referencing it fail closed at admission; history keeps it | — | — |

An **optional connectivity check** ("run the `connect` step and the first
class 0 `exec` step only, against one device, discard everything") is a
class 0 typed job (`backup_profile_connect_check`) an `operator` may submit
from the UI; it never runs a class 1 step. It is how an author learns the
prompt regex is right before approval.

### 6.6 Reconciliation with the action taxonomy — what is browser-safe, what is not

`utils/action_taxonomy.py` is unchanged: class 1 is permitted only through
the `RB.x` contracts and is **not console-submittable**. In Java the same
five classes exist with the same `consoleSubmittable` flags (§8.4). The
backup flow is decomposed so that every browser-originated request is class 0
or a **policy/intent write** with its own `D7`, and every class 1 step runs
only inside a scheduled or CLI-started run:

| Flow element | What the browser sends | Class / nature | Gate | Executes where |
| --- | --- | --- | --- | --- |
| view backup state, readiness, history, run detail, step outcomes | `GET` | class 0 read of projections | `E1`, `D7` for `role:viewer` | Java read model |
| view profiles, versions, validation results | `GET` | class 0 read | same | Java |
| select a profile for a device set (create/modify a `BackupAssignment`) | typed intent `{device_id[], profile_id, version, credential_profile_ref}` | **policy write** — no device contact | `D7` `role:backup_admin`; audit-before-mutation; registry re-check of every `device_id` | Java |
| approve a `VALIDATED` profile | `{profile_id, version}` | policy write | `D7` `role:backup_admin`, author ≠ approver | Java |
| set / change a schedule | **schedule-edit intent** (§6.7) | policy write — **the `C-D7` question** | `D7` `role:backup_admin` + §6.7 conditions | Java |
| connectivity check of a profile against one device | `{profile_id, version, device_id}` | **class 0 typed job** (connect + first read-only step only) | `E1`–`E7`, admission coordinator | worker (Python at `F3`, Java at `F5`) |
| "back up now" | — **does not exist as a browser action** | class 1 | refused at `E3` with `recovery_write_not_console_submittable`, always, every role | — |
| a backup run | a due `BackupSchedule` (scheduler) or the CLI (`--recovery-collect` today; the Java CLI at `F5`) | class 1 under `RB.x`: per-entity ledger, minimum re-execution interval, distinct backup credential, fail-closed allowlist, mandatory reason (`C-D6`) for CLI-started runs | `E7` admission + immediately-before-execution registry and allowlist re-check | worker |
| retention deletion of artifacts | — | destructive local-data operation | dry-run proposal in the UI; `--apply` from the CLI with explicit approval (`BACKUP_AND_RECOVERY` §9) | CLI |

Three consequences the Product Owner should read as decisions:

1. **"Back up now" from the UI is out, deliberately, and the UI says so**: the
   backup screen shows the class 1 refusal with its taxonomy reason and the
   next scheduled run, and offers "run the connectivity check" as the class 0
   alternative. If the Product Owner later wants an on-demand run from the
   browser, that is a taxonomy amendment (`AI_START_HERE.md` "never exposed
   on an HTTP surface") — the requirements draft's option (3), not
   recommended here.
2. **A schedule change is the strongest thing a browser can do** to the
   backup plane, which is why §6.7 spends design on it.
3. **Profile step content is never submitted through the job path.** A run
   references `{profile_id, version}`; the worker loads the approved,
   immutable version from the database. The browser cannot influence what a
   run sends to a device except by choosing among approved profiles.

### 6.7 The `D7`-authorized, audited schedule-edit intent (successor to `C-D7`)

`CON.0` `C-D7` decided "not in this track" for editing scheduler policy from
a browser because it is "a privilege-escalation path into unattended device
contact and needs its own gate". This is that gate, for UI 2.0 only:

| Condition | Statement |
| --- | --- |
| **Typed intent, closed schema** | `{schedule_id?, assignment_id \| device_set_id, cadence: {kind: "interval", minutes} \| {kind: "daily", at, tz} \| {kind: "weekly", days[], at, tz}, window?: {start, end, tz}, enabled, reason}` — no cron string, no command, no path |
| **Floors and ceilings are server-owned** | `interval_minutes ≥ 10` and the default-disabled posture stay (`PCP` §9); additionally a **per-device class 1 ceiling** derived from the `RB.x` ledger's minimum re-execution interval — a schedule that could violate the ledger interval is refused at intent time (`SCHEDULE_VIOLATES_LEDGER_INTERVAL`), never silently clamped |
| **Aggregate contact bound** | the server computes the resulting per-endpoint cadence across *all* schedules touching the device set and refuses an intent that would raise it above the vendor interaction-safety budget the admission coordinator enforces; the refusal names the budget |
| **`D7`** | `role:backup_admin`; `DENIED`/`AUTHZ_NOT_EVALUATED` refused and shown per `AC-CS` §6.3 |
| **Mandatory reason** | ≥ 8 characters, redaction-filtered, stored with the audit record (`C-D6` pattern) |
| **Audit before mutation** | an immutable `schedule_changes` row (`who, when, before, after, reason`) is durable before the `BackupSchedule` row changes (`CON.0` §7.9 ordering) |
| **Effect is deferred and visible** | the intent changes *policy*; the scheduler picks it up on its next evaluation; the UI shows "next run" from the scheduler's own projection, never from the intent |
| **Enable is stronger than edit** | enabling a schedule for a device set with no prior successful connectivity check on the assigned profile is refused (`SCHEDULE_ENABLE_REQUIRES_CONNECT_CHECK`) — an unattended class 1 run against an unverified profile is the failure this gate exists to prevent |
| **Never for `recovery-cp` outside its allowlist** | the `D3` pilot allowlist semantics carry over as the profile-level allowlist: a schedule targeting a device outside the assigned class 1 profile's allowlist is refused at intent time and again at `E7` |

The same intent shape, with a different role token, serves the inventory /
configuration / compliance cadences (`M12`'s per-capability schedules) — one
schedule-edit intent for the product, class-aware through the ceilings, not
one per subsystem.

### 6.8 UI surfaces (what the operator sees)

- **Backup overview** — fleet roll-up by readiness (`READY` / `STALE` /
  `PARTIAL` / `UNPROTECTED` / `UNKNOWN`, `BACKUP_AND_RECOVERY` §11 vocabulary
  unchanged), the unprotected list first, coverage vs registry (devices with
  no assignment counted as `UNPROTECTED`, never omitted), next scheduled runs,
  last failures with their step-level reason. `[SCREENSHOT]` the roll-up's
  grouping (by vendor, by group, by site) — default: by vendor, then by
  registry tag.
- **Device → Backups tab** — assignment (profile + version), schedule, last
  run with **step timeline** (each step: kind, outcome, matched expectation,
  duration; never output text), artifact list with digest, size, validation
  level, age; retention preview; the class 1 refusal explanation for "back up
  now". `[SCREENSHOT]` whether Backbox shows the step timeline inline or on a
  drill-down — default: inline, collapsed to the failing step when a run
  failed.
- **Profiles** — list, versions, state, validation report, connectivity-check
  history, assignments count; step list rendered read-only with every `send`
  shown verbatim (it is repository/database content, not device output) and
  its `gate_review_ref`. Phase 2 (§6.10): the editor. `[SCREENSHOT]`
  Backbox's per-step field layout (command / expected / timeout / on-failure)
  — default: one row per step with those four columns.
- **Schedules** (Operations → Schedules, `PO-NAV-8`) — global cadence audit
  with the aggregate-contact figure per device; the schedule-edit intent.
- **Runs / Jobs** — every run as a job record with provenance
  (`scheduled` / `manual` / `console` / `event`), actor fingerprint for
  operator-started runs, ledger entry reference.

### 6.9 Contradiction reported: `PCP` §11 (FROZEN)

`PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §11: *"The operator does not choose
SSH/API commands for a supported platform today (closed registry) and will
not under the control plane."* Operator-authored profiles contradict the
second clause as written. Proposed amendment, for the same freeze decision as
§4.3:

> Amendment (2026-09-09, `UI2_0_ARCHITECTURE_DESIGN.md` §6): backup becomes
> **profile-driven**. A profile is a versioned, validated, four-eyes-approved
> ordered step sequence with expectation gates; its commands are backed by
> network-device command-gate records and its class is the maximum class of
> its steps. The operator chooses **among approved profiles**; the run path
> never accepts a command. Authoring a profile is a governed content path
> (§6.10), distinct from the closed job registry, which stays closed. For a
> platform with a native contract (`RB.2`, `RB.3`) the shipped profile *is*
> that contract's step sequence.

### 6.10 Contradiction reported: constitutional "No Browser → device path", and the two-phase authoring decision

`AGENTS.md` "Architectural invariants": *"No Browser → device path. … no
command, argv fragment, path, or API route ever originates in the browser."*
A profile authored in a browser editor contains commands that later reach a
device. Even with validation, four-eyes approval, immutability and the job
path never carrying the text, the command **originates** in the browser.
`AGENTS.md` is authority #1; this document does not reconcile it.

**Decision for this design:**

- **Phase 1 (this design's resolution, no constitutional change):** profile
  authoring is a **governed content path outside the browser** — profiles are
  versioned files (`YAML`/`JSON`) in a governed profile repository or the
  RuntimeRoot, imported by an administrator CLI (`ui2 profile import`, the
  successor of the `compliance_checks.json` / `control_assignments.json`
  file-policy pattern), validated on import, approved in the UI by a second
  `backup_admin`. The browser **selects, assigns, schedules, approves and
  reads** profiles; it never writes step content. This delivers `AC-4(d)`'s
  "select a profile, select target devices, set a schedule" fully, and
  "create a profile" through the CLI/file path.
- **Phase 2 (requires a Product Owner amendment to `AGENTS.md`):** an
  in-browser profile editor. Drafted amendment wording, for the Product
  Owner's decision, not applied:

  > No Browser → device path. The operator console submits typed intent
  > against a closed registry; no command, argv fragment, path, or API route
  > that a **request** carries ever originates in the browser. **Exception
  > (UI 2.0 backup profiles, `UI2_0_ARCHITECTURE_DESIGN.md` §6):** a backup
  > profile's step content may be authored in the browser as **governed
  > content** — validated, immutable once approved by a different actor,
  > gate-referenced per step, class-checked, and never carried by any run
  > request, which references `{profile_id, version}` only. The exception
  > is test-enforced: no route other than the profile-draft route accepts
  > step content, and no run route accepts any.

  If the Product Owner declines the amendment, Phase 1 is the permanent
  shape and `AC-4(d)`'s "create" stays a CLI/file operation. That is a
  legitimate product outcome, not a design failure — it is what the
  requirements draft's own `C-3` analysis said the taxonomy implied.

### 6.11 Items a Backbox screenshot would settle (none blocking)

| Item | Default chosen |
| --- | --- |
| overview grouping | vendor → tag |
| step timeline placement | inline in the run detail |
| per-step field layout in the profile view/editor | one row: kind / send / expect / timeout / on-failure |
| how Backbox labels the expect gate ("Expected response"? "Success string"?) | "Expected response" |
| whether Backbox exposes a "test connection" separate from a full run | yes — mapped to the class 0 connectivity check |
| schedule editor shape (calendar vs interval form) | interval/daily/weekly form, no cron text |
| history depth shown per device | last 30 runs, paged |

---

## 7. Authentication and sessions — concurrent multi-admin, single active session per identity (`AC-5`, resolves `C-4`)

### 7.1 Decision

UI 2.0's authentication is the **successor to `DEPLOY.1A`'s auth shape**
(server-mode, multi-user, behind TLS) with **LDAP/Active Directory** as the
identity provider instead of OIDC. Several administrators work concurrently,
each in their own live session. **One identity has at most one active
session**: a login for an identity that already has an active session either
**ends the prior session** or **refuses the new login** — the operator chooses
at login time (`takeover` / `refuse`), and the prior session's owner is told
what happened on their next request. `M14`'s `A1`/`A2` split is reused: `A1'`
(actor binding + session admission) is `E1`-family; `A2'` is the `D7`
producer (§5.3).

### 7.2 Where the requirements draft's analysis is overruled

The requirements draft (§5 `F-3`(b), `C-4`) treated multi-user login as
"designed nowhere" and left it to the successor design. This is that design;
it makes the decisions `M14` §11 deferred (durable role mapping, §5.2; durable
authorization audit, §5.4; multi-session actor state, §7.5) — for UI 2.0
only. `M14` itself is unchanged and stays the local loopback design; nothing
here retroactively validates or inherits a local shortcut
(`LOCAL_CONTROL_PLANE…` §11.3).

### 7.3 Login flow

```
browser ── GET /login ──► form (username, password, [takeover|refuse])   over TLS only
browser ── POST /login ─► Java service:
     1. rate-limit + lockout counter per identity and per source (fail closed under abuse)
     2. LDAP simple bind AS THE OPERATOR over verified TLS (LD-1, LD-6): the password is used for
        exactly one bind and then cleared; it is never stored, logged, or held for re-binds
     3. empty-secret guard before the call (RFC 4513 unauthenticated-bind trap, AG-2)
     4. group set resolved from the directory for this actor (LD-4 directory-side evaluation);
        access group required to open a session at all (A1' session admission)
     5. single-session rule (§7.4): existing active session for this actor?
          takeover → prior session row marked SUPERSEDED(by=new session id, at, reason=login_elsewhere)
          refuse   → 409 LOGIN_REFUSED_ACTIVE_SESSION, nothing changes
     6. session row created (§7.5); opaque 256-bit session id; cookie: Secure, HttpOnly, SameSite=Strict
     7. audit row: login, actor_fingerprint, outcome, takeover?, source class (never the address)
```

**Why a browser login form, when `M14` §6 rule 4 forbids the browser handling
a directory credential.** `M14`'s rule protected a *loopback* console whose
only authentication was a per-launch token, on the operator's own machine:
there, a login form would have been a new credential surface for no benefit.
A multi-admin server has no TTY per administrator; the credential must
arrive from the administrator's browser over TLS, exactly as every corporate
web application does. This is a **multi-user-specific amendment** of `LD-7`
and `M14` §6 rule 4 (table §7.7), and the design compensates: TLS with
corporate CA only (no plaintext listener exists), the password lives for one
bind, no password ever reaches a log (redaction registry), rate limiting and
lockout, and the session cookie is the only credential that persists — never
the directory password.

### 7.4 The single-session rule, precisely

| Situation | Behaviour |
| --- | --- |
| identity `I` has no active session; logs in | session created |
| `I` has active session `S1`; logs in from elsewhere choosing **takeover** | `S1` → `SUPERSEDED`; `S2` created; `S1`'s next request gets 401 with `SESSION_SUPERSEDED` and a human message ("your session was ended by a new login for this identity at \<time\>"); an audit row records both |
| `I` has `S1`; logs in choosing **refuse** | `S2` not created; 409; `S1` unaffected; audit row |
| `I` has `S1`; the same browser re-opens a tab | `S1` continues — one session, many tabs; "session" is the server row, not the tab |
| `S1` idle past the idle timeout (proposed 30 min) or absolute lifetime (proposed 10 h) | `S1` → `EXPIRED`; login required |
| `role:security_admin` ends `S1` | `S1` → `REVOKED(by, reason)`; 401 on next request with the reason class |
| directory re-validation (§7.6) finds `I` no longer in the access group | `S1` → `REVOKED(reason=access_group_lost)` immediately |
| the service restarts | sessions survive (they are database rows, §7.5); each is re-validated against the directory on first use after restart |

The default of `takeover` vs `refuse` is the operator's choice on the login
form, remembered per browser as a preference (not per identity — it is a UI
preference, never product state). `[SCREENSHOT]` not needed; this is a
product rule, not a layout question.

### 7.5 Session state — the multi-user amendment of `LD-3`

`LD-3` ("nothing is persisted; in-memory, process-lifetime") was right for a
single process serving a single operator. A multi-process, restartable server
with several administrators cannot keep session and actor state in one
process's memory: a restart would log everyone out, a second service instance
would not know the first's sessions, and the single-session rule could not be
enforced across instances. **Amendment:** sessions and the resolved group set
are persisted in PostgreSQL:

| Row | Fields | Lifetime | Privacy |
| --- | --- | --- | --- |
| `sessions` | `session_id (hash of the cookie value, never the value)`, `actor_fingerprint`, `created_at`, `last_seen_at`, `expires_at`, `state (ACTIVE\|SUPERSEDED\|EXPIRED\|REVOKED)`, `ended_by?`, `end_reason?`, `csrf_secret` | until end + audit retention | no DN; the fingerprint is `principal_fingerprint` (12-hex SHA-256 prefix, the existing correlator) |
| `actor_authz_state` | `actor_fingerprint`, `group_refs (encrypted)`, `resolved_at`, `valid_until` | bounded by the re-validation interval; deleted when the actor has no active session | encrypted at rest; never logged, never served |

What `LD-3` protected is preserved in a different way: the **bind secret is
still never held** (one bind per login, then cleared), a **stale positive is
still never served past its interval** (`AG-11`), a **revocation still
propagates within one interval** — and the durable record of "a named human's
directory position" that `LD-3` refused to write is written **encrypted,
keyed by fingerprint, deleted at session end**, with retention limited to
the audit rows that carry only the fingerprint.

### 7.6 Re-validation without the password

`M14` re-read membership over the launch-time bound connection; the operator's
password was not needed again because the connection persisted. A server
cannot keep one bound connection per administrator open for hours. **Decision:
re-validation uses a dedicated read-only directory service account** (a
deliberate departure from `LD-1`'s "no service account", justified below),
resolved through the `DEV.2.1` `<VAR>_FILE` mechanism into the Java service's
environment, with the minimum directory read right needed to evaluate group
membership for a given identity. Login itself still binds **as the operator**
(`LD-1` carried over for authentication); the service account is used only
for the periodic membership re-read (`A2'`'s freshness), never to
authenticate anyone.

Why this is acceptable where `LD-1` rejected it: `LD-1` rejected a service
account because it would decouple "the human at this console" from "the
principal we searched for" — on a loopback console with no user identity in
the HTTP session. UI 2.0 has a user identity per session (§7.5), established
by the operator's own bind; the service account only re-answers "is this
already-authenticated identity still in these groups". The tradeoff accepted
is a long-lived read-only directory secret in the service's secret store —
the same class of secret as the database DSN, held under the same `DEPLOY.1`
secrets-vault requirement.

Interval and floors: `LD-3`'s recommended 15 minutes, hard floor, expiring
toward `AUTHZ_NOT_EVALUATED` (`AG-11`); a re-read that fails leaves every
action for that actor `UNDETERMINED` in the UI and refused at submission with
`authorization_unevaluated` — the actor is *not* logged out by a directory
hiccup (that would be a denial they did not receive), but they cannot act
until the directory answers.

### 7.7 `M14` decisions — carried over unchanged, or amended for multi-user

| `M14` decision | UI 2.0 | Detail |
| --- | --- | --- |
| `A1`/`A2` split | **unchanged in kind** (`A1'`/`A2'`) | session admission is `E1`-family; `D7` is per action |
| `LD-1` bind as the operator, `DEV.2.1` mechanism, distinct variable namespace, never in argv/URL/repo, empty-secret guard, fingerprint-only audit | **carried over for authentication**; **amended** to add a read-only directory service account for re-validation only (§7.6) | the service account is a second, narrowly scoped identity, not a fallback for the operator's |
| `LD-2` fail-closed on execution, honest-unknown in presentation, banned transition to `NO_APPLICABLE_AUTHORITY` | **unchanged** | `AG-4` restated in Java (§13) |
| `LD-3` nothing persisted, bounded re-validation, stale positive never served | **amended**: sessions and encrypted group state persisted in PostgreSQL (§7.5); the freshness and revocation rules unchanged | the reason (`LD-3`'s own §11 row 3: "out of scope by construction — single-operator") no longer holds |
| `LD-4` action → role token (repo) → group reference (runtime config); no token → `NO_APPLICABLE_AUTHORITY`; token unbound → `AUTHZ_NOT_EVALUATED`; directory-side membership evaluation | **carried over**, with the group reference moved from environment variables to an encrypted, audited database row administered in the UI by `role:security_admin` (§5.2) — `M14` §11 row 4's deferred decision, taken | per-action granularity from day one (UI 2.0 has actions worth distinguishing; `LD-4`'s "single access group first" was a local-slice pragmatism) |
| `LD-5` `ldap3` | **not applicable** — Python-specific; re-asked for Java in §8.3 | UnboundID LDAP SDK recommended |
| `LD-6` verified TLS to the directory, corporate CA bundle, no insecure fallback, hard failure before any network call on an unreadable bundle | **unchanged**, plus the same rule for the service's own HTTPS listener and the PostgreSQL DSN | one trust-material posture for all three |
| `LD-7` one bind at launch on the TTY, never from the browser | **replaced**: one bind per login, from the browser over TLS, password held for one bind (§7.3) | the multi-user amendment; `M14` §6 rule 4 amended for UI 2.0 accordingly |
| `M14` §6 non-substitution rules 1–3 (token ≠ membership, neither inferred from the other) | **unchanged** with "session" for "token" | `AG-9` |
| `M14` `U-1` nested-group semantics | **still open** (§12) — must close before freeze, with Microsoft documentation | unchanged |
| `M14` `U-3` whether a DC read falls under the command gate | **carried as open**; this design records the gate's ten fields for both the login bind and the service-account read regardless | unchanged |
| `M14` `U-4` re-evaluate `E4` immediately before execution | **decided for UI 2.0**: yes — `E4` is re-evaluated at execution time for any action whose submission-to-execution gap can exceed the re-validation interval (every scheduled job; every queued class 1 run), against the actor recorded on the submission | conservative direction of `M14`'s own note |

### 7.8 Reconciliation with the existing console and the CLI

Today's console keeps its per-launch bearer token untouched until retired
(`CON.0` `C-D2`). The Python CLI keeps its `DEV.2.1` credential pattern. The
future Java CLI (§8.3) authenticates the same way UI 2.0 does — an LDAP bind
per invocation, or a short-lived session token minted by a prior login —
never a stored password.

---

## 8. The Java stack and the per-feature port (`AC-6`, resolves `U-1`)

### 8.1 Decision

**UI 2.0's entire stack is Java**: the HTTP service, the API, sessions and
authorization, the read models, the projection writers, the scheduler, the
job workers, the backup profile engine, and **every feature once it is
ported** (§3.2 `F5`). It is not a thin Java layer over Python workers; the
requirements draft's option (i) is the *transitional* shape (`F2`/`F3`) and
option (ii) is the *destination*, reached one feature at a time, never at
once. The Python runtime remains the worker for every feature below `F4` for
as long as that takes; a feature's Python implementation is retired only at
`F5`.

### 8.2 What this changes about the requirements draft's recommendation

The draft recommended keeping the `D1`–`D7` resolver in Python behind
conformance fixtures, and against any port of collectors. Under the full-stack
decision: the resolver is ported early (it is pure, fully specified by `AC-CS`
§5.5's atomic cases, and every UI 2.0 render needs it — it is the first
`F4`/`F5` candidate); collectors are ported **last and individually**, each
only after `F1` maturity and with real-environment re-validation (§3.2). The
draft's cost analysis is not contradicted — it is the reason the ladder has
the shape it has.

### 8.3 Stack choices — with tradeoffs, at `LD-5`'s rigor

Each choice is a **recommendation with a stated tradeoff**; the final library
commitment is outside this movement's scope and is a dependency-approval
decision under `docs/AI_DEVELOPMENT_PROTOCOL.md` "Approval boundaries".

| Concern | Recommended | Considered | Why the recommendation |
| --- | --- | --- | --- |
| **Runtime** | Java 21 LTS (records, sealed interfaces, virtual threads) | Java 17 LTS | sealed interfaces and records are load-bearing for the closed registries below (§8.4); virtual threads make per-device SSH sessions cheap without a reactive rewrite. Tradeoff: corporate JDK availability must be confirmed (`U-J1`, §12) |
| **Web framework** | **Spring Boot 3.x** (Spring MVC, servlet stack) | Quarkus; Micronaut; Helidon; plain Jakarta EE | Spring is the corporate default in most Java estates, has the broadest LDAP/security/JDBC integration, the largest hiring pool, and its security filter chain is exactly the interceptor model §5.3 needs. Tradeoffs: startup time and memory (irrelevant for a long-running service), a large dependency surface (mitigated by the BOM, SBOM and the release-assurance gate `SERVER_PRODUCTIZATION` §3), and "magic" that must be constrained by ArchUnit rules (§8.4). Quarkus would win on footprint and native image; neither matters here and its LDAP/AD story is thinner |
| **DB access** | **jOOQ** over JDBC, with **Flyway** migrations | JPA/Hibernate; Spring Data JDBC; MyBatis; raw JDBC | jOOQ gives type-safe SQL generated *from the migrated schema* — the schema stays the contract (`DEV.4.6`), not an object model; there is no lazy-loading or dirty-checking surprise in a request path that must be auditable; and its dialect abstraction is the cheapest honest answer to the Oracle-later requirement (§9). Tradeoffs: a commercial licence is required for Oracle dialect support (open-source jOOQ covers PostgreSQL); developers must know SQL (a feature here). Hibernate would give portability "for free" and hide the SQL the audit needs to see |
| **Migrations** | Flyway, versioned SQL, deployment-controlled, migration role ≠ application role | Liquibase | `DEV.4.6`'s own requirement stated in tooling; Flyway's plain SQL files are reviewable like code. Liquibase's XML/YAML abstraction adds little here |
| **LDAP** | **UnboundID LDAP SDK** | JNDI (`com.sun.jndi.ldap`); Spring LDAP (over JNDI) | UnboundID is a complete, actively maintained, pure-Java implementation with explicit TLS configuration (the `LD-6` posture is expressible directly), explicit control over bind semantics (the empty-secret guard, `AG-2`), and no JNDI global-state surprises. Tradeoff: one more dependency; Spring LDAP's integration convenience is lost, which is acceptable because the bind and re-validation paths are small and must be explicit anyway |
| **SSH/SFTP (backup engine)** | **Apache MINA SSHD** (client) | JSch; sshj | MINA SSHD is the only actively maintained client with full modern algorithm support, explicit known-hosts verification hooks (strict, no TOFU), and streaming SFTP; it is Apache-governed. Tradeoffs: a larger API than sshj; the expect-style state machine (§6.3) is written by us over its channel API — deliberately, so the expectation semantics are ours and testable. JSch is effectively unmaintained upstream |
| **Front end** | a TypeScript single-page application (framework choice deferred; React or Vue are both adequate), built to static assets served by the Java service; no server-side templating of evidence | server-rendered Thymeleaf | the shell renders resolver output and affordances; a component model with typed props is the natural fit for the `AC-CS` chip/affordance vocabulary; the build step is permitted by `UA-2`. Tradeoff: a second toolchain (Node) at build time only — never at runtime |
| **Build** | **Gradle** (Kotlin DSL), version catalog, reproducible builds, dependency verification metadata | Maven | Gradle's dependency-verification file gives the supply-chain pin `SERVER_PRODUCTIZATION` §3 asks for; the version catalog is one reviewable place for every version. Maven would be equally acceptable if the corporate estate standardises on it (`U-J2`) |
| **Packaging / deployment** | one **container image** per component (service, worker, scheduler — the same jar, different entry point), non-root, read-only root filesystem, distroless base, image digest pinned; a **fat jar** for the local/laptop profile | separate images per feature | one artifact, several roles, mirrors the existing compose shape; the local profile keeps the "one command on a laptop" experience the Python product has |
| **Java CLI** | a thin CLI in the same jar (`ui2 …`) using the same service layer, for every intent UI 2.0 exposes | none (UI only) | preserves the product rule that every UI action has a CLI equivalent (`PCP.1` precedent), and gives `F5` features a non-browser entry point |
| **Testing** | JUnit 5, Testcontainers (PostgreSQL), ArchUnit (architecture rules as tests), the shared JSON conformance fixtures (§8.5) | — | ArchUnit is how `AGENTS.md`'s "test-enforced, not merely current" invariants survive the language change |

Not chosen anywhere: Kotlin (adds a language to the mandate), reactive stacks
(no need; virtual threads), any ORM with runtime schema generation (`DEV.4.6`
forbids it), any embedded scripting for profiles or checks (§6.3, `CE.1` D2).

### 8.4 Enforcing the safety boundaries in Java

The Python product enforces its boundaries with module-level constants, a
frozen dataclass and `tests/test_architecture_convergence.py`. The Java
equivalents, each a compile-time or test-time fact:

| Python boundary | Java construction |
| --- | --- |
| `utils/action_taxonomy.py` five classes, `console_submittable`, `refusal_code` | `enum ActionClass { READ, RECOVERY_WRITE, OPERATIONAL_STATE_CHANGE, CONFIGURATION_WRITE, POLICY_DEPLOYMENT }` with the same fields; the enum is the *only* place submittability lives; an ArchUnit rule forbids any `switch` on the enum outside the taxonomy package |
| closed `JOB_REGISTRY` | `sealed interface ActionDefinition permits …` — every action is a `record` implementing it, listed in one `ActionRegistry` `static final Map`; the sealed hierarchy means a new action is a source change the compiler sees; an ArchUnit rule forbids constructing an `ActionDefinition` outside the registry package |
| "no route accepts a command / argv / path / address" (`AG-U1`) | request DTOs are records whose fields are typed ids (`DeviceId`, `ProfileId`, …) — value objects with validated opaque constructors; an ArchUnit rule forbids `String`-typed request fields in the API package except the enumerated free-text fields (`reason`, search terms), each redaction-filtered |
| "one orchestration path" (`AG-U2`) | the only class that may open an SSH session, an SFTP channel or an HTTPS client to a device is in `…transport`; ArchUnit forbids any other package from depending on the SSH/HTTP client libraries; every device call passes through `AdmissionCoordinator` (same package) |
| class 1 never on an HTTP surface (`AG-U5`) | the web layer's interceptor evaluates `ActionClass.consoleSubmittable` before routing; an ArchUnit rule forbids the web package from depending on the `recovery` package's executor types; a test enumerates every route and asserts none accepts a class 1 action |
| identity law (opaque identifiers) | every identifier is a value object with no numeric parse, no case normalization, no trim beyond whitespace; equality is `String.equals` on the stored form; an ArchUnit rule forbids `Long.parseLong`/`Integer.parseInt`/`toLowerCase` in identity packages |
| redaction registry | one `Redaction` service registered as the logging framework's message converter; every secret and principal is registered at resolution; a test injects synthetic sensitive values and asserts none appears in any log appender's output (`AG-1`) |

### 8.5 Worked example — porting `CE.2` (compliance command primitives)

`CE.2` (PR #154) is the example because it is real, recent, small, and
already carries a safety boundary: a static, fail-closed registry of gate-
reviewed read-only command primitives, executed only under an opt-in probe,
redacted to shape + fingerprint, exposed to checks as `primitive.<id>`.

**Entry condition.** `CE.2` is `AUTOMATED_VALIDATED`; its real-environment
validation against each primitive's real device output shape is owed. It
enters the ladder at `F1` only once that is recorded. Until then it stays
`F0`. (The port below is therefore described, not scheduled.)

**`F2` — projected.** The Python probe workflow writes `PrimitiveResult`s
into a `primitive_results` projection (PostgreSQL through the evidence-backend
seam: `run_id, device_id/endpoint_id, primitive_id, vendor, success,
error_class, executed_at, bytes, lines, fingerprint_sha256`) instead of only
`output/compliance_probe_<ts>.json`. Its shape is frozen as
`tests/fixtures/ui2_conformance/ce2/primitive_result.v1.json` plus a set of
example rows generated by the existing 33 tests. UI 2.0 renders them on the
device's Compliance tab (freshness, per-primitive outcome, fingerprint).

**`F3` — intent-routed.** UI 2.0 exposes `compliance_probe` as a class 0
typed job (`operator` role); the Python worker executes it via
`main.main(["--compliance-probe", …])` from the queue.

**`F4` — the Java implementation, in shadow.** The registry's shape in Java:

```java
public sealed interface CommandPrimitive permits CpGaiaShowVersionAll, PanShowSystemInfo {
    PrimitiveId id();
    Vendor vendor();
    ReadOnlyCommand command();          // value object: constructor runs the write-marker denylist
    CommandGateReview gateReview();     // record with all ten gate fields, all required (non-null, validated)
    RedactedOutput redact(String raw);  // returns shape + fingerprint only; raw is unreachable afterwards
    default SourceNamespace sourceNamespace() { return SourceNamespace.of("primitive." + id().value()); }
}

public record CommandGateReview(String reason, ActionClass actionClass, String vendorPlatformShellContext,
                                int timeoutSeconds, int retryCount, int maxFrequencyPerEndpointMinutes,
                                String sessionReuse, String unsupportedBehavior, String secretOutputRisk,
                                String safeTelemetry) {
    public CommandGateReview {                          // compact constructor = register_primitive()'s checks
        if (actionClass != ActionClass.READ) throw new CommandPrimitiveException("only READ may be registered");
        if (timeoutSeconds < 1 || timeoutSeconds > 120) throw new CommandPrimitiveException("timeout out of range");
        if (retryCount < 0 || retryCount > 2) throw new CommandPrimitiveException("retry out of range");
        if (maxFrequencyPerEndpointMinutes < 1) throw new CommandPrimitiveException("frequency out of range");
        // every string field: non-blank, checked here
    }
}

public final class PrimitiveRegistry {                  // the single choke point
    private static final Map<PrimitiveId, CommandPrimitive> REGISTRY = Map.of(
        CpGaiaShowVersionAll.INSTANCE.id(), CpGaiaShowVersionAll.INSTANCE,
        PanShowSystemInfo.INSTANCE.id(),     PanShowSystemInfo.INSTANCE);
    public static List<CommandPrimitive> forVendor(Vendor v) { … }
}
```

How the **read-only safety boundary** is enforced in the new language, layer
by layer — none of which relies on convention:

1. **Type**: `CommandPrimitive` is `sealed`; adding a primitive is adding a
   permitted class — a compiler-visible source change, reviewed like a gate
   record.
2. **Construction**: `ReadOnlyCommand`'s constructor applies the same
   write-marker denylist (`set|add|delete|clear|install|commit|…`,
   word-boundary, case-insensitive) as `_WRITE_COMMAND_MARKERS`; `CommandGateReview`'s
   compact constructor applies `register_primitive()`'s numeric and class
   checks. There is no way to hold an instance that failed them.
3. **Architecture test**: ArchUnit forbids any class outside
   `…compliance.primitives` from depending on the SSH/HTTP transport;
   forbids any `CommandPrimitive` implementation outside that package; and
   asserts the registry's `Map` is the only place `CommandPrimitive`
   instances are enumerated.
4. **Execution**: `PrimitiveExecutionTracker` (once per device per run, in
   memory, as today) and the `AdmissionCoordinator`'s per-vendor budget of 1
   are the only path to `execute(...)`; a test with a mocked transport asserts
   exactly one session per endpoint per probe batch (the existing
   call-count tests, re-expressed).
5. **Redaction**: `redact(String)` returns `RedactedOutput(bytes, lines,
   fingerprintSha256)`; the raw string is a local in `execute(...)` that is
   not returned, not logged, not stored; a test asserts that no raw token
   survives into the result or any log line.

**Test-parity proof required before the Python version is retired (`F5`):**

| Proof | Source | Passes when |
| --- | --- | --- |
| registration rejection (write-marker commands, wrong class, out-of-range gate values) | the 33 `test_ce2_…` cases, exported as a JSON table of `{candidate, expected_error_class}` | the Java constructors throw the mapped exception for every row; the Python test re-consumes the same table (so the two suites cannot drift) |
| tracker frequency / session-reuse | call-count fixtures | identical counts on a mocked transport |
| redaction | fixture raw outputs (synthetic) → expected `{bytes, lines, fingerprint}` | byte-identical `RedactedOutput` |
| engine wiring | a check pack referencing `primitive.<id>.<field>`, evaluated bare vs wired vs omitted | identical verdicts to the Python engine's for the same fixtures (this proof is shared with the `CE.1` engine's own port) |
| projection parity | `F2`'s frozen `primitive_result.v1.json` | the Java executor writes rows that validate against the same schema and equal the Python rows for the same fixture transport |
| **real-environment** | `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md`: one bounded, read-only probe per vendor, executed by the Product Owner, Java executor on the pilot target, Python executor's last result on the same target | SAFE SUMMARY reports `MATCH` on `{success, error_class, bytes, lines, fingerprint_sha256}` per primitive (the fingerprint of the same command's output on the same device within the same window is the parity witness; values are never reported) |

Only when every row is recorded in `project/build_history.json` is
`--compliance-probe` removed from the Python CLI and the Python module marked
reference-only. The pattern generalises: **the Python test suite of a feature
becomes the fixture table of its Java port**, and the real-environment
verdict is re-earned by the Java implementation on the same device.

---

## 9. Storage — recorded, not re-derived (`AC-7`, `X-2` already resolved)

**Decision (recorded).** **PostgreSQL is the production storage engine for
UI 2.0's control-plane, projection and audit state.** SQLite remains the
local engine unchanged (`M4`, `LOCAL_CONTROL_PLANE…` §6.4). Content-addressed
evidence blobs (`utils/config_evidence.py` CAS) and the recovery store stay on
the filesystem volume unchanged; no artifact byte enters a database row.
`docs/design/PCP_STORAGE_ENGINE_DECISION.md`'s criterion-by-criterion check
stands; its gap #9 (off-host custody of the database's own backups joins
`recovery_offhost_key_custody`) and its `DEV.4.6` precondition are carried
into the freeze conditions (§12), not re-argued.

**Oracle — considered, deferred, not rejected.** Oracle is the corporate
default database. It was considered and explicitly deferred: a future
corporate mandate could still introduce it, and nothing in this design is
allowed to make that needlessly hard. The design spends no further effort on
it now beyond the following portability rules, which cost nothing:

| Rule | Why |
| --- | --- |
| schema and queries are expressed through jOOQ's dialect layer; migrations are plain SQL kept to the ANSI/common subset where the cost is nil | the dialect switch is a configuration change plus a migration review, not a rewrite |
| PostgreSQL-specific features (`jsonb` operators, partial indexes, `LISTEN/NOTIFY`, extensions) are used only behind a named repository interface with a documented fallback (`jsonb` → `CLOB` + application-side filtering; `NOTIFY` → polling) | isolates the engine-specific surface to a list that can be enumerated at the time a mandate arrives |
| no stored procedures, no triggers carrying business logic, no engine-specific types in the application's public schema contract | business rules live in Java; the database stays a store |
| identifiers, timestamps (`timestamptz` ↔ `TIMESTAMP WITH TIME ZONE`) and UUIDs use types both engines support | avoids the classic porting traps |
| the migration role / application role separation and TLS DSN posture are engine-neutral requirements (`PRIVACY_AND_DATA_HANDLING.md` "Distributed evidence store") | the security posture does not depend on the engine |

An Oracle option would additionally need: a jOOQ commercial licence (§8.3),
Testcontainers or an equivalent for Oracle in CI, and a re-run of the
`PCP` §10 criteria for that engine. None of that is done or scheduled.

---

## 10. Invariants this design preserves and extends

- **`AGENTS.md`** identity law (identifiers opaque; §8.4 makes it a
  compile-time property), evidence laws, UNKNOWN/fail-closed law (every
  expectation gate and every authorization outcome has an honest unknown),
  raw-evidence law (§6.4), sensitive-identity reporting law (fingerprints and
  binding ids in every audit row), diagnostic-path law (the backup engine
  reuses the existing trust and credential-profile mechanisms; no parallel
  credential path — the backup credential is `D4`'s distinct identity, by
  reference), and the network-device command gate (every profile step).
- **`D1`–`D7`** and the frozen resolution contract: extended with a real `D7`
  producer and a per-request evaluation path; nothing hides, nothing is
  computed from `D7`, `E3` is never re-evaluated inside `E4`.
- **Action taxonomy**: unchanged; the same five classes in Java; class 1 never
  on an HTTP surface; classes 2–4 refused with the class named.
- **`RB.x`**: the ledger, minimum interval, distinct credential, allowlist and
  encrypted store are the execution contract of every class 1 profile.
- **`PCP.1`/`M9`**: enrollment is the only way a device exists; backup does
  not enroll.
- **One orchestration path** per language, test-enforced in both; no
  browser-originated command (Phase 1) — Phase 2 is an amendment decision.

---

## 11. Contradictions with frozen or constitutional authority — reported for the freeze decision

| id | Authority | Text | This design | Resolution offered |
| --- | --- | --- | --- | --- |
| `UA-1` | `CON.0` §6 (FROZEN) | payload-shape parity | separate shell, typed read API | replacement text §4.3 |
| `UA-2` | `CON.0` §3 (FROZEN) | no framework/bundler | UI 2.0 has a front-end build | replacement text §4.3 |
| `UA-3` | `PCP` §13 (FROZEN) | "the control plane's console **is** the `CON.x` console"; payload parity | UI 2.0 succeeds it module by module | one-sentence amendment §4.3 |
| `UA-4` | `PCP` §11 (FROZEN) | "the operator does not choose SSH/API commands … and will not" | operator-authored profiles | amendment text §6.9 |
| `UA-5` | `AGENTS.md` architectural invariant | "no command … ever originates in the browser" | Phase 2 browser profile editor | Phase 1 needs no change; Phase 2 amendment text §6.10 |
| `UA-6` | `M14` (DRAFT) §6 rule 4, `LD-3`, `LD-7` | no browser credential; nothing persisted; one bind on the TTY | browser login, persisted sessions | multi-user amendments §7.7 — `M14` itself unchanged for the local console |
| `UA-7` | `CON.0` `C-D7` | schedule editing "not in this track" | schedule-edit intent | the gate `C-D7` asked for, §6.7 |
| `UA-8` | `AI_START_HERE.md` / `CON.0` §7.1–7.3 | loopback, per-launch token | network-exposed TLS listener with sessions | the `DEPLOY.1A`-class decision those rows reserved; §7 |

The `M14`/`M14L` naming contradiction (`M14` §7) and the PAN serial
real-device investigation are parked by the Product Owner and untouched here.

---

## 12. Still open — must close before freeze or before the named slice

| id | Item | Closes how |
| --- | --- | --- |
| `U-J1` | corporate JDK/runtime version availability (Java 21 assumed) | Product Owner / platform statement |
| `U-J2` | Gradle vs Maven as the corporate build standard | same |
| `U-J3` | jOOQ commercial licence acceptability (only load-bearing if Oracle arrives) | procurement, deferred with Oracle |
| `U-J4` | whether UI 2.0 lives in this repository (`ui2/` sub-tree, one PR flow, shared fixtures) or a sibling repository (separate CI, fixture publication) — **recommendation: this repository**, because the conformance fixtures and the relay process are shared | Product Owner |
| `M14 U-1` | nested-group membership semantics (Microsoft documentation) | before any authorization freeze |
| `M14 U-2`, `U-3` | directory TLS shape; command-gate applicability to a DC read | as `M14` records |
| `X-2` gaps | `DEV.4.6` migrations before any production role; off-host custody of database backups | `PCP_STORAGE_ENGINE_DECISION.md` §4 |
| `U-4` (req. draft) | canonical id spanning `device_id` and evidence `entity_id` | a Line-1 movement after `M10`; every per-device view needs it |
| `[SCREENSHOT]` items | §6.11 | Product Owner shares Backbox references; defaults stand otherwise |
| Phase 2 profile editor | `UA-5` amendment | Product Owner decision; Phase 1 does not wait |
| `U-J5` | the real-environment re-validation protocol for a `F4` shadow run (how the Product Owner executes a Java worker against a pilot device and returns a SAFE SUMMARY) | an extension of `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md`, before the first `F4` |

---

## 13. Acceptance gates for the eventual implementation (inherited and added; not gates on this document)

`AG-U1`…`AG-U9` from the requirements draft §11 are inherited unchanged.
Added:

| id | Gate |
| --- | --- |
| `AG-J1` | Every `ActionClass`, registry, identity, transport and redaction boundary in §8.4 exists as an ArchUnit or unit test in the Java build; the build fails on violation |
| `AG-J2` | The gate chain `E1`→`E6` runs in one interceptor no route can bypass; a test enumerates every route and asserts the chain is applied; `E7` runs in the execution phase for every job |
| `AG-J3` | Varying `D7` across all four values changes no rendered structure in UI 2.0 (`AC-CS-33`, `AG-7`); the front-end build contains no role-conditional rendering (grep-level test on the built assets for role tokens) |
| `AG-J4` | A configured directory that cannot be evaluated never resolves `NO_APPLICABLE_AUTHORITY` (`AG-4`); an expired re-validation yields `AUTHZ_NOT_EVALUATED` (`AG-11`) |
| `AG-J5` | Single-session rule: a second login for the same identity with `takeover` supersedes the prior session and the prior session's next request is refused with `SESSION_SUPERSEDED`; with `refuse` nothing changes; both audited |
| `AG-J6` | No route accepts profile step content except the profile-draft route (Phase 2) or the import CLI (Phase 1); no run route accepts any; a run references `{profile_id, version}` only |
| `AG-J7` | Every profile step has an expectation; a timeout, an ambiguous match, an overflow, and a prompt-without-match each fail the step with its named class; `finally` runs on every failure and its own failure is `RUN_CLEANUP_INCOMPLETE` |
| `AG-J8` | No transcript byte survives a step: the result carries only outcome, matched expectation, counts, fingerprint and declared captures; synthetic secret markers in a fixture transcript appear in no row and no log |
| `AG-J9` | The schedule-edit intent refuses (never clamps) an interval below the floor, a cadence violating the ledger interval, an aggregate contact above the vendor budget, an enable without a connectivity check, and a device outside the profile's allowlist — each with its named refusal |
| `AG-J10` | "Back up now" is refused on every HTTP route for every role with `recovery_write_not_console_submittable`; the refusal renders per `CON.0` §9 with the next scheduled run named |
| `AG-J11` | Projection parity: one persisted projection set rendered through the report exporter, today's console and UI 2.0 yields identical `primary_status`, `capability_qualifiers` and `evidence_presentation` per shell-scoped surface (`AC-CS-39`) |
| `AG-J12` | A feature reaches `F5` only with every §8.5-style parity row recorded in `project/build_history.json`, including the real-environment `MATCH` |
| `AG-J13` | Full regression of both tracks, repository privacy gate (extended to the Java tree: no DN, group name, address, credential or profile step content in any committed file), `git diff --check`, `DEV.4.6` migration discipline for every schema change |

---

## 14. Next movement / reasoning tier

The next step is a **Product Owner review**, then (as the Product Owner
intends) an external second opinion, then a **`DECIDE` episode** on the
freeze of this document together with the amendments `UA-1`…`UA-4` (and the
separate decisions `UA-5` Phase 2 and `UA-8`). No engineering movement is
recommended before that.

If frozen, the recommended first engineering movement is a **`CONTRACT`
draft for `F2` on two features only** — the Device Registry rows and the HA
readiness projection — plus the UI 2.0 skeleton (service, login, sessions,
role bindings, the gate chain, the read API for those two projections), with
no backup engine and no port. Tier: **`Sonnet 5, extended thinking (high)`**
— a storage and security boundary. The backup profile engine's own contract
(§6) is a separate, later `CONTRACT` movement at the same tier. `Fable, high`
was the right tier for this cross-subsystem design and is more than either
contract needs.

---

## 15. Cross-references

- `docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md` — the evidentiary base;
  §2 audit, §5 `F-1`…`F-11`, §6 `X-1`…`X-4`, §9 `C-1`…`C-6`, §10 `U-1`…`U-6`,
  §11 `AG-U1`…`AG-U9`.
- `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`) — §3, §4, §4.1,
  §6, §7, §9, `C-D2`, `C-D5`, `C-D6`, `C-D7`.
- `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` (FROZEN) —
  §2.7 (amendment-record shape), §3.1, §5.3, §5.4, §5.4.1, §5.4.3, §6.1,
  §6.3, §6.8, `AC-CS-33`, `AC-CS-35`, `AC-CS-39`, `AC-CS-68`, `AC-CS-80`.
- `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md` (DRAFT) —
  `A1`/`A2`, `LD-1`…`LD-7`, §6, §8 `U-1`…`U-4`, §11, §12 `AG-1`…`AG-12`.
- `docs/design/PCP_STORAGE_ENGINE_DECISION.md`; `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`
  §9, §10, §11, §13.
- `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` (FROZEN) —
  §6.4, §9.1, §10.2, §11.3, §12.
- `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §5, §9, §9.1, §10, §11;
  `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`;
  `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`;
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.
- `docs/design/COMPLIANCE_CHECK_ENGINE.md` §3, §5, §10;
  `configuration/command_primitives.py`;
  `tests/test_ce2_compliance_check_engine_primitives.py`.
- `docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`
  §2, §3, §6 (the `DEPLOY.1`/`DEPLOY.1A` gate set this design's server shape
  must still pass).
- `utils/action_taxonomy.py`, `console/registry.py`, `console/runner.py`,
  `utils/collection_executor.py::workflow_argv`, `utils/evidence_backend.py`,
  `utils/control_plane_store.py`, `utils/device_registry.py`.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — network-device command gate; approval
  boundaries (dependency additions, new network-access patterns, schema /
  storage migration).
- `docs/reference/REAL_ENV_VALIDATION_PROTOCOL.md` — the procedure every
  `F4` parity proof runs under.
