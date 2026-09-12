# UI 2.0 — architecture contract (separate Java console, LDAP multi-admin sessions, profile-based backup, per-capability port)

## Status

**FROZEN — 2026-09-12**, under the Product Owner's standing written
authorization to approve, revise or cancel UI 2.0 architecture and B-series
contracts. Ruling of 2026-09-12: write a successor and mark the predecessor
`SUPERSEDED`; the alternative offered (freeze the predecessor as it stood)
was declined deliberately and is not reopened here.

Supersedes `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (DRAFT, never frozen,
PR #160, movement `UI2_0_ARCHITECTURE_DESIGN_FABLE_HIGH`), together with the
amendments applied to it by `docs/design/UI2_0_C5_AMENDMENTS_BUNDLE.md` §4,
which are folded in below rather than layered on top. That predecessor's own
status line read "DRAFT — design resolved, NOT frozen, NOT implementation
authority", yet it stood at item 4 of the declared authority chain of four
FROZEN contracts (`C1` §1, `C2` §1.3, `C3` §1.4, `C4` §1.3) and was relied on
in body by `C1` §9/§10 and by `C3` §6's `U-4` disposition. `AGENTS.md`
"Authority hierarchy" item 2 and "Contract-status law" forbid a DRAFT being
implementation authority, so the whole UI 2.0 C-series rested on a document
that disclaimed being authority. The predecessor is retained for history only
and is never implementation authority again.

### Why a successor instead of freezing the predecessor

Freezing the predecessor would have frozen, as one act, clauses of three
different kinds: decisions with shipped artifacts, decisions with no
implementation at all, and questions the document itself left `UNKNOWN`. A
single `FROZEN` stamp over that mixture is what "Contract-status law" exists
to prevent — it would have read, to a later session, as authority to
implement the unresolved parts. This contract instead sorts every carried
clause into exactly one of three buckets and says which (§0), so that being
authority and being built are separately legible.

This document does **not** restate what a frozen C-series contract already
owns. It is the architecture layer: the decision, its bucket, and the
contract or artifact that owns the detail.

---

## 0. How to read this — the three buckets

Every clause below carries exactly one bucket marker.

- **`S-B` — settled and built.** A decision that is made and has an artifact
  in the repository, named by path. The artifact's existence is evidence of
  shape, never of correctness or of real-environment validation (§14).
- **`S-U` — settled, not built.** A decision the frozen C-series already
  relies on, for which **no implementation exists**. Being unbuilt does not
  unmake the decision; this is the bucket that makes this contract
  authoritative rather than descriptive. Every `S-U` clause states plainly
  that nothing implements it yet.
- **`O` — open.** Not decided. Carries an open-item id in §9 and `UNKNOWN`
  for the undecided part. **Nothing may be implemented against an `O`
  clause.** An `O` item is never promoted to `S-U` or `S-B` to make this
  document tidier.

### Predecessor section → this contract

A FROZEN contract citing a predecessor section finds its content here:

| Predecessor § | Subject relied on | Here |
| --- | --- | --- |
| §4, §4.2 | shell boundary; persisted-projection definition; projection parity | §2 |
| §4.3 | `CON.0` `UA-1`/`UA-2` replacement text | §2.3, §10 |
| §5, §5.1–§5.3 | RBAC is `D1`/`D7`, never menu-hiding; role model; per-request gate order | §3 |
| §5.2 | role bindings; group reference encrypted under the application role's key | §3.3, §9 `O-5` |
| §5.4 | storage/audit pattern (`role_bindings`, `authz_decisions`, `sessions`) | §3.3 |
| §6.1–§6.2 | backup operating model; objects; no artefact byte in a database row | §5.1, §5.2 |
| §6.3 | profile/step model; closed step-kind set; the six expectation rules | §5.3 |
| §6.4 | raw-output handling inside an expect engine; `discard_raw` | §5.4 |
| §6.5 | profile lifecycle `DRAFT`→`VALIDATED`→`APPROVED`→`RETIRED`; four-eyes on a version | §5.5 |
| §6.6 | taxonomy reconciliation; what a browser may originate | §5.6 |
| §6.7 | the `D7`-authorized, audited schedule-edit intent | §5.7 |
| §6.8 | backup UI surfaces | §5.8 |
| §6.9, §6.10 | `PCP` §11 and "no Browser → device path" contradiction reports; two-phase authoring | §10, §9 `O-9` |
| §6.11 | items a Backbox reference would settle | §9 `O-8` |
| §7, §7.3–§7.6 | concurrent multi-admin, one active session per identity; login; session state; re-validation | §4 |
| §7.7 | `M14` carry-over table, including the `U-4` disposition | §4.5 |
| §8.1–§8.3 | full Java stack; stack choices | §6.1 |
| §8.4 | Java constructions for the safety boundaries | §6.2 |
| §3.2, §8.5 | capability maturity states; the worked port | §6.3 |
| §9 | storage: PostgreSQL recorded; Oracle deferred with portability rules | §7 |
| §10 | invariants preserved and extended | §8 |
| §11 | `UA-1`…`UA-8` contradictions | §10 |
| §12 | still-open items | §9 |
| §13 | `AG-J1`…`AG-J13` acceptance gates | §6.2, §8, and each owning contract |

---

## 1. Scope and authority

**In scope.** The architecture-level decisions above the C-series: the shell
boundary, the authorization model's shape, the identity/session model's
shape, the backup subsystem's operating model, the language/stack commitment,
the storage engine, and the invariant set UI 2.0 inherits.

**Out of scope.** Schema text (`C1`), job execution and lifecycle (`C2`),
the identity/sessions/RBAC specification (`C3`), the capability registry,
closed step-kind set and gate resolution (`C4`), the extraction template
(`C6`), the backup artefact/restore engine (`C7`), the Java module skeleton
(`B1-1a`), and every screen contract. Where this contract and one of those
disagree, **the C-series or B-series contract wins** and the disagreement is
a reportable contradiction, not a local reconciliation.

Authority above this contract: `AGENTS.md`, then
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09), whose §1 direction and §2 Phase 0 decisions bind every clause
below and are not re-litigated here.

---

## 2. The shell boundary

### 2.1 Separate shell — `S-B`

UI 2.0 is a separate application, not a surface inside the existing console's
eight-payload model: its own typed read API, its own front-end build, its own
deployment unit. Built: `ui2/service` (Spring MVC controllers under
`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/`) and
`ui2/frontend` (a TypeScript/React workspace driven by root `Exec` tasks,
`B1-1a` §2). The exported Line-1 report survives as a product deliverable and
becomes one consumer of the projections; it is not this contract's subject.

### 2.2 Persisted projection — `S-U`

A **persisted projection** is an evidence-grade row set written by a producer
at collection or derivation time, keyed by `device_id` and/or `entity_id`,
carrying collection/derivation timestamp, provenance and completeness. It is
**never** resolved presentation state: `primary_status`, tone, copy and
affordances are computed per request over projections and never stored as
truth. One projection table exists (`cp_inventory_projection`, `V4`); the
inventory, configuration, compliance and backup-readiness projection set the
definition governs does not. `C1` owns the table discipline.

### 2.3 Projection parity — `S-U`

Every delivery surface reads the same persisted projections. No surface
computes a fact from a source another surface cannot read. For a surface both
shells ship, identical evidence yields identical `primary_status`,
`capability_qualifiers` and `evidence_presentation`. The payload-equality
test of the Line-1 console era is replaced by a **projection-parity test**
that renders one projection set through each surface's resolver. Neither the
parity test nor a second surface exists. The replacement text for the frozen
`CON.0` clauses this requires was drafted as `UA-1`/`UA-2`, approved in
direction (baseline §2 `CON.0-AMENDMENT`), and **written into
`OPERATOR_CONSOLE_ARCHITECTURE.md` on 2026-09-12** — see §10. What is still
absent is the parity test and the second surface, not the clause.

### 2.4 Inherited verbatim — `S-B` in part

`CON.0` §4 (the browser sends intent, never a command), §4.1 (typed
enrollment intent), §7 rules 4–10 and §9 (honest affordances) are inherited
unchanged. Built for the paths that exist: the closed action registry and
interceptor chain (`ui2/service/.../service/security/ActionRegistry.java`,
`GateChainInterceptor.java`) and the no-role-conditional-rendering proof
(`ui2/architecture-tests/.../NoRoleConditionalRenderingInFrontendTest.java`).
`CON.0` rules 1–3 (loopback, per-launch bearer token, data-free shell) do not
apply to UI 2.0; §4 replaces them.

---

## 3. Authorization — RBAC is `D1`/`D7`, never menu-hiding

### 3.1 The rule — `S-B`

Every navigation entry always renders per `D1`; every declared action shows
its own `D7` outcome (`PERMITTED` / `DENIED(authority, reason)` /
`AUTHZ_NOT_EVALUATED(authority)` / `NO_APPLICABLE_AUTHORITY`); the server
refuses an unauthorized action independently of anything the UI showed. No
client-side visibility toggle exists and the front end has no role concept.
Built: `RbacEvaluator.java`, `GateChain.java`, and the architecture test
above, which greps the built assets for role tokens. `C3` owns the
specification.

### 3.2 Three levels of indirection — `S-B` mechanism, `S-U` coverage

`action_id` (closed registry, repository-committed) → **role token**
(repository-committed, carrying no corporate identity) → **role binding**
(a database row, admin-edited, audited) → **directory group reference**
(opaque; never normalized, never case-folded). Every registry entry declares
exactly one required role token, or none, which resolves
`NO_APPLICABLE_AUTHORITY`. Tokens are additive only by explicit declaration,
never by hierarchy inference.

Built: the mechanism end to end for the actions that exist — `ActionRegistry`
declares `role:onboarding_admin` for manual device registration, and
`role_bindings` is a real table (`V2__identity_sessions_rbac.sql`).
Not built: the rest of the token→action mapping across the six tokens (`C3`
§ role-token table owns them); there is no backup, compliance or schedule
action in the registry to bind, because those subsystems do not exist (§5).

### 3.3 Storage and audit — `S-B`

`role_bindings`, `sessions`, `actor_authz_state` and `authz_decisions` exist
in `ui2/service/src/main/resources/db/migration/V2__identity_sessions_rbac.sql`;
`audit_log` in `V1__initial_schema.sql`; the redaction policy in
`V5__audit_redaction_policy.sql`. The group reference is the one place a
corporate identity lives, encrypted at rest, and `binding_id` is the only
identifier that leaves the table: no DN, group name or address enters a log,
an API response or a projection. `authz_decisions` is append-only and never
enters a support bundle. Two things about it are **not** settled: the key's
source, custody and rotation (§9 `O-5`) and its retention policy (§9 `O-6`).

### 3.4 Per-request evaluation order — `S-B`

`E1` session authenticity → actor resolution → `E2` action identity in the
closed registry → `E3` taxonomy admissibility → `E4` `D7` → `E5`
subject/target integrity → `E6` declared prerequisites → `E7` admission and
immediately-before-execution checks. `E3` is never re-evaluated inside `E4`;
both reasons are recorded when `E3` refuses and `E4` denies, authorization
last; `E4` reads only the actor, the action id and the bindings, never a
capability projection; a configured authority that cannot be evaluated is
`AUTHZ_NOT_EVALUATED`, never `NO_APPLICABLE_AUTHORITY`. Built in
`GateChain.java` behind an interceptor no route bypasses
(`GateChainInterceptor.java`, `SecurityWebMvcConfig.java`), proved by
`GateChainTest.java` and `DeviceRegistrationGateTest.java`. `C3` owns the
per-gate refusal vocabulary.

Membership evaluation is a set comparison of the actor's resolved group
references against the binding's, exact after whitespace stripping, never a
client-side normalization. Nested-group semantics stay `UNKNOWN` (§9 `O-3`).

---

## 4. Identity and sessions

### 4.1 Concurrent multi-admin, one active session per identity — `S-B`

Several administrators work concurrently, each in their own live session.
One identity has at most one active session: a login for an identity that
already has one either **ends** the prior session (`takeover`) or **refuses**
the new login (`refuse`), chosen at login time, and the prior session's owner
learns what happened on their next request. Built: `LoginFlow.java`,
`SessionReconciler.java`, `LoginController.java`, `SessionAdminController.java`,
over the `sessions` row set. `C3` owns the state machine and the closed
`end_reason` vocabulary, including the idle and absolute deadlines that this
contract's predecessor carried only as proposals (§11 item 3).

### 4.2 Login binds as the operator — `S-B`

LDAP simple bind as the operator over verified TLS, the password used for
exactly one bind and then cleared — never stored, logged or held for re-binds
— behind a rate limit and lockout counter, with the empty-secret guard
applied before the call. The session cookie is the only credential that
persists. Built: `UnboundIdOperatorBindAdapter.java`, with
`PasswordNeverInStringTest.java` as the standing proof.

A browser login form is a deliberate, multi-user-specific departure from the
local loopback console's rule that the browser never handles a directory
credential: a multi-admin server has no TTY per administrator. The
compensations are part of the decision — TLS with corporate CA only, no
plaintext listener, one-bind password lifetime, redaction, rate limiting and
lockout.

### 4.3 Sessions are persisted — `S-B`

Sessions and the resolved group set are database rows, not process memory: a
restart must not log everyone out, a second instance must see the first's
sessions, and the single-session rule must hold across instances. The cookie
value itself is never stored (`SessionHasher.java`); `actor_authz_state`
holds the group references encrypted, bounded by the re-validation interval,
and is deleted when the actor has no active session.

### 4.4 Re-validation without the password — `S-U`, gated

Periodic membership re-read uses a dedicated **read-only directory service
account** with the minimum read right needed, resolved through the
deployment secret mechanism; login itself still binds as the operator, and
the service account never authenticates anyone. A re-read that fails leaves
that actor's actions `AUTHZ_NOT_EVALUATED` and refused at submission — the
actor is not logged out by a directory hiccup, but cannot act until the
directory answers; a stale positive is never served past its interval.

An adapter exists (`UnboundIdRevalidationAdapter.java`), but the function is
**not enabled**: baseline §2 `DIRECTORY-POSTURE` (D-6) makes group-reference
persistence and the service-account bind conditional on a corporate-policy
verification that is not recorded (§9 `O-7`). Treat this clause as settled in
shape and closed in operation until that record exists.

### 4.5 Re-evaluating `D7` immediately before execution — `S-U`

For any action whose submission-to-execution gap can exceed the re-validation
interval — every scheduled job, every queued class 1 run — `E4` is
re-evaluated at execution time against the actor recorded on the submission.
This is the disposition `C3` §6 reads through, and it is carried here
unchanged. No scheduler and no queued class 1 run exist yet, so nothing
implements it; `C2` owns the claim-time check battery.

---

## 5. The backup subsystem — profile-based

Everything in §5 is **`S-U`**: the decisions are settled and relied on by
frozen `C4` and `C7`, and **no part of the backup engine is built.** There is
no profile, assignment, schedule, run or step-result table in `V1`–`V5`; no
SSH/SFTP transport dependency in `ui2/gradle/libs.versions.toml`; and the
transport seam
(`ui2/job-engine/.../jobs/transport/DeviceTransport.java`) answers every
device call with `TransportNotImplementedException`. Naming these clauses is
what keeps a later reader from mistaking them for delivered work.

### 5.1 Operating model

A device exists only because it was onboarded through the device registry;
backup never adds devices. A **backup profile** is a named, versioned,
ordered sequence of **steps**, each one SSH or SFTP interaction with an
**expectation gate**: a step succeeds only if the device's response matches
what the profile declared, and the next step runs only if the previous one
succeeded. A profile is written per vendor/version from that vendor's own
admin guide, so a device the product has no native collector for is backed up
the same way as a natively supported one, provided SSH/SFTP is reachable and
someone authored the steps. There is **no built-in static backup method**:
every run is "profile P against device set S", and the two native Line-1
implementations are re-expressed as the first two shipped profiles. Execution
is a class 1 job under the `RB.x` ledger, credential and allowlist contracts,
run by the scheduler or a worker — or by a worker claiming a Run Now job
request (§5.6), never by the HTTP request itself.

### 5.2 Objects

`BackupProfile` → `ProfileVersion` → `Step[]`, with `ProfileState` on the
version; `BackupAssignment` (which profile backs up this device, with its
credential and trust profile references); `BackupSchedule`; `BackupRun` with
`StepResult[]`; and the artefact with its verification record. Profiles,
assignments, schedules, runs and step results are database rows. **No
artefact byte ever enters a database row or an HTTP response** — artefacts
and manifests stay in the recovery store on the volume. `C7` owns these
tables; `C1` §4 owns the data classes.

### 5.3 Profile and step model

A profile version is data — no code, no expression language, no free-form
shell. Every `send` in a shipped profile must be backed by a network-device
command-gate record whose semantics were established from official vendor
documentation and real-environment evidence. Variables are only captured
groups from previous steps, profile constants, transport/terminal settings,
and **credential references — never values**; a `send` that resolves to
secret material is rejected at validation.

The step-kind set is **closed**. Its current authority is `C4` §2.3, which
ported it from the predecessor and added `xml_api_call`; `StepKind.java`
implements that set. Adding a kind is an amendment to `C4`, never a runtime
configuration choice.

The six expectation rules are carried unchanged, because each is written to
be testable and `C4` §2.3 ports them verbatim: a step with no expectation is
invalid; matching is anchored where the author anchored it and carries the
pattern-safeguard set (length cap, complexity linter, wall-clock timeout,
timeout ⇒ `UNKNOWN` ⇒ run fails closed); `expect` and `fail_on` both matching
is a failure, not a guess; **a timeout is never a success** and a prompt
reappearing without the expected text is an unmet expectation; a failure
stops the sequence while `finally` runs regardless and its own failure is
surfaced as an actionable incomplete-cleanup state; captured variables are
validated against a declared shape so a `send` template can never receive
uncontrolled device output.

The predecessor's illustrative Check Point profile is **not** carried
forward as a command sequence. `C4` §1.4 and §2.4 refuse it and substitute
the real gate-signed-off command tuple; this contract records that refusal
rather than reprinting either.

### 5.4 Raw output inside an expect engine

An expect engine necessarily reads raw device output into memory. The
transcript lives in memory only, bounded, and the step records only: outcome,
which expectation matched, byte and line counts, a digest of the exact text,
and the declared captures — the transcript is discarded in the same function
that captured it. `discard_raw` is the only retention value at current
maturity; anything else needs its own evidence/forensics contract, per
`AGENTS.md` raw-evidence law and baseline `RAW-RETENTION` (D-2d, deferred,
default no). The fetched artefact is not a transcript and follows the
recovery-store envelope rules instead. Partially provable today at the schema
layer only: `ui2/integration-tests/.../schema/NoRawOutputColumnTest.java`.

### 5.5 Profile lifecycle

`DRAFT` → `VALIDATED` → `APPROVED` → `RETIRED`. Static validation gates
`VALIDATED`: schema, closed step kinds, an expectation on every step, pattern
safeguards, class consistency, a gate record reference for every `send`, no
secret placeholders, declared capture shapes. Approval to `APPROVED` requires
a `role:backup_admin` **other than the author**, enforced server-side from
the recorded author. An `APPROVED` version is immutable; a change is a new
version. A `RETIRED` version is not assignable and a run referencing it fails
closed at admission. A class 0 **connectivity check** (connect plus the first
read-only step against one device, everything discarded) is how an author
learns the prompt expectation is right before approval; it never runs a class
1 step.

### 5.6 What a browser may originate

The action taxonomy is unchanged: class 1 is permitted only through the
`RB.x` contracts and is not console-submittable. The backup flow is
decomposed so every browser-originated request is one of exactly three
things: a class 0 read or class 0 typed job; a **policy/intent write** with
its own `D7` (assignment, profile approval, schedule edit, target-set edit);
or a class 1 **job request** that creates a queued row and contacts no device
itself. A class 1 **step** runs only inside a scheduled run, a CLI-started
run, or a worker's execution of a queued Run Now request — never inside the
HTTP request/response cycle.

Run Now of an **approved** profile version against an **approved** target
requires the `D7` role and a mandatory reason, with **no second approver**,
and approval is re-checked fresh at claim time, never assumed from request
time. Against an unapproved profile or target it is refused with the class 1
non-submittability reasoning for every role, and the refusal names the next
scheduled run and offers the connectivity check as the class 0 alternative.
This is baseline `UI-OPERATIONAL-RUN-NOW` (D-2b) and `APPROVAL-MODEL` (D-2c);
`C2` owns the job lifecycle and the claim-time checks. The predecessor's
original unconditional "back up now does not exist as a browser action"
framing is withdrawn (§11 item 2).

Profile step content is never submitted through the job path: a run
references profile id and version, and the worker loads the approved,
immutable version from the database.

### 5.7 The schedule-edit intent

A schedule change is the strongest thing a browser may do to the backup
plane, so it is a typed intent over a closed schema — interval, daily or
weekly cadence with an optional window, plus a mandatory reason; no cron
string, no command, no path — and every bound is server-owned: the interval
floor and default-disabled posture; a per-device class 1 ceiling derived from
the ledger's minimum re-execution interval; and an aggregate per-endpoint
contact bound across all schedules touching the device set. A violating
intent is **refused with a named reason, never silently clamped**. Enabling a
schedule for a device set whose assigned profile has no prior successful
connectivity check is refused, and a target outside the profile's allowlist
is refused at intent time and again at `E7`. An immutable audit row is
durable before the schedule row changes. The effect is policy: the scheduler
picks it up on its next evaluation and the UI shows "next run" from the
scheduler's own projection, never from the intent.

Schedule **authorisation** is four-eyes: the intent that first enables a
schedule, or that widens an enabled schedule's device set or shortens its
interval, requires a second `role:backup_admin` distinct from the requester.
A disable, or a narrowing edit, needs only `D7` plus a reason — it can only
reduce unattended contact. A routine Run Now is deliberately not in this
set: it touches no schedule row.

The same intent shape, with a different role token, serves the inventory,
configuration and compliance cadences. One schedule-edit intent for the
product, class-aware through its ceilings — not one per subsystem.

### 5.8 UI surfaces

Four surfaces are settled in substance: a **backup overview** rolling the
fleet up by readiness with the unprotected list first and coverage measured
against the registry (a device with no assignment counts as unprotected,
never omitted); a **device backup tab** with assignment, schedule, and the
last run as a step timeline carrying kind, outcome, matched expectation and
duration but **never output text**; a **profiles** view rendering each step
read-only with its `send` shown verbatim, because that is repository/database
content and not device output; and a **schedules** view carrying the
aggregate-contact figure per device alongside the edit intent. The visual
system is Material Design 3 (baseline `VISUAL-DESIGN-LANGUAGE`) and the
screen-state vocabulary belongs to `UI2_0_BASELINE_DIRECTORY.md`, not to
this contract.

---

## 6. The Java stack and the per-capability port

### 6.1 Full Java stack — `S-B`

UI 2.0's entire stack is Java: HTTP service, API, sessions and authorization,
read models, projection writers, scheduler, workers, the profile engine, and
every capability once ported. It is not a thin Java layer over Python
workers; baseline §1 item 2 (`RUNTIME-DIRECTION`) makes that binding — no
Line-1 executable, sidecar, subprocess or queue consumer is part of the
runtime.

Built and binding, each observable in `ui2/` and pinned by
`docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §4, which owns the
detail: Java 21 via a Gradle toolchain; Gradle with the Kotlin DSL, a single
version catalog, per-module lockfiles and checked-in dependency verification;
Spring Boot on the servlet stack, whose filter/interceptor model is exactly
what §3.4 needs; **jOOQ over JDBC with Flyway** versioned SQL migrations,
migration role ≠ application role, so the schema stays the contract rather
than an object model; the UnboundID LDAP SDK, chosen because the TLS posture
and the bind semantics including the empty-secret guard are expressible
explicitly; and a TypeScript/React front end built to static assets, with
Node as build tooling only.

Two stack choices are **`S-U`**: Apache MINA SSHD as the SSH/SFTP client for
the profile engine — no SSH dependency is declared anywhere in `ui2/` — and
the thin Java CLI that preserves the product rule that every UI action has a
CLI equivalent, for which `ui2/cli` holds a single entry-point class and no
command. Not chosen anywhere, and still not: Kotlin, a reactive stack, any
ORM with runtime schema generation, and any embedded scripting for profiles
or checks.

### 6.2 Safety boundaries as compile-time or test-time facts — `S-B` in part

The Python product enforces its boundaries with module-level constants and a
convergence test; the Java equivalents are constructions, not conventions:
the action-class enum as the only place submittability lives; a sealed
`ActionDefinition` hierarchy with one registry map, so a new action is a
source change the compiler sees; request DTOs whose fields are typed value
objects rather than strings, so no route can accept a command, argv fragment,
path or address; one transport package as the only holder of a device client
library, with admission in front of it; the class 1 refusal evaluated in the
web layer before routing; identifiers as value objects with no numeric parse
and no case normalization; and one redaction service registered as the
logging framework's message converter.

Built: the ten dependency-direction rules and the identity and
role-rendering rules as ArchUnit tests
(`ui2/architecture-tests/.../Ui2ArchitectureTest.java`,
`NoRoleConditionalRenderingInFrontendTest.java`), the closed action registry,
and the gate chain of §3.4. Not built: the sealed `ActionDefinition`
hierarchy as such, the route enumeration proving no route accepts a class 1
action, and the redaction-appender proof — each belongs to the B-series
movement that adds the routes it would constrain.

### 6.3 Capability maturity states — `S-B`

A capability — a semantic unit, not a Line-1 file — moves through
`CAP-SPEC` → `CAP-OFFLINE` → `CAP-VALIDATED` → `CAP-RELEASED`, recorded per
capability. Nothing skips `CAP-VALIDATED`: a capability is never presented as
validated without a Product-Owner-run real-environment result for a
device-contacting capability, or a contract plus offline-test record for a
pure-function one. The capability spec is the contract, not the Line-1 code;
a Line-1 change moves no Java capability's state. A released capability is
invoked from the CLI as well as the UI. Release slices (`REL-*`) group
capabilities and are never themselves maturity states.

Built: `MaturityState.java` and the spec loader/validator under
`ui2/capability-registry/`. `C6` owns the extraction template and `C4` the
registry.

The predecessor's `F0`–`F5` ladder, its `F2`/`F3` Python-execution rest
states and its `F4` shadow-run parity step are **withdrawn** and are not
carried into this contract in any form (§11 item 1). Consequently the
one-orchestration-path gate is: **one job-execution path, in Java,
test-enforced** — no language-crossing round-trip proof exists to require,
because only one language is on the path.

---

## 7. Storage — `S-B`

PostgreSQL is the production storage engine for UI 2.0's control-plane,
projection and audit state. Line-1's SQLite local engine is unchanged.
Content-addressed evidence blobs and the recovery store stay on the
filesystem volume; no artefact byte enters a database row. Built:
`ui2/service/src/main/resources/db/migration/V1__initial_schema.sql` through
`V5__audit_redaction_policy.sql`, applied by Flyway from `ui2/persistence`,
with PostgreSQL 16 as the proving version for integration tests
(`B1-1a` §5). `C1` owns the schema, the data classes and the key-custody
boundary.

**Oracle is deferred, not rejected** — `S-U`. It is the corporate default
database and a future mandate could still introduce it, so four portability
rules hold now because they cost nothing: schema and queries go through the
dialect layer with migrations kept to the common SQL subset where that is
free; engine-specific features sit behind a named repository interface with a
documented fallback; no stored procedures and no triggers carrying business
logic; and identifiers, timestamps and UUIDs use types both engines support.
The migration-role/application-role separation and the TLS DSN posture are
engine-neutral requirements. An Oracle option would additionally need a jOOQ
commercial licence (§9 `O-2`), an Oracle test carrier, and a re-run of the
storage-engine criteria for that engine. None of that is done or scheduled.

---

## 8. Invariants this contract preserves — `S-B` as rules

Carried in full, and none of them weakened by anything above:
`AGENTS.md`'s identity law (identifiers opaque — made a compile-time property
by §6.2), the evidence laws, the UNKNOWN/fail-closed law (every expectation
gate and every authorization outcome has an honest unknown), the raw-evidence
law (§5.4), the sensitive-identity reporting law (fingerprints and binding
ids in audit rows, never raw identities), the diagnostic-path law (the backup
engine reuses the existing trust and credential-profile mechanisms; no
parallel credential path), and the network-device command gate for every
profile step. `D1`–`D7` and the frozen resolution contract are extended with
a real `D7` producer and a per-request evaluation path: nothing hides,
nothing is computed from `D7`, `E3` is never re-evaluated inside `E4`. The
action taxonomy is unchanged in kind; class 1 never reaches an HTTP surface
as an execution, and classes 2–4 are refused with the class named. `RB.x`
remains the execution contract of every class 1 profile. Enrollment stays the
only way a device exists; backup never enrolls.

One clarification this contract makes explicit, because the predecessor's
shorthand invites the error: the product is **not** "read-only". Class 1
controlled recovery writes ship under their `RB.x` contracts and are never
console-submittable, and baseline `DEVICE-WRITE-CLASS` (D-8) adds a
narrowly-scoped controlled-restore write class whose own admission contract
is still gated. Restore stays disabled in Java until that gate clears.

---

## 9. Open items — nothing may be implemented against these

Each carries `UNKNOWN` for the undecided part. The predecessor's §12 list is
reconciled here: an item a frozen contract has since closed is named in the
relevant section above with that contract; only the genuinely undecided
remain.

| id | Open question | Predecessor id | State |
| --- | --- | --- | --- |
| `O-1` | Corporate JDK/runtime availability for Java 21. The build commits to Java 21 (§6.1); the platform statement confirming corporate availability is `UNKNOWN` and was never recorded | `U-J1` | open |
| `O-2` | jOOQ commercial licence acceptability — load-bearing only if Oracle arrives | `U-J3` | open, deferred with Oracle |
| `O-3` | Nested-group membership semantics, to be closed from official vendor documentation. `C3` keeps it open and performs no nested expansion | `M14 U-1` | open |
| `O-4` | Directory TLS shape, and whether a domain-controller read falls under the network-device command gate | `M14 U-2`, `U-3` | open |
| `O-5` | The application-role encryption key's source, custody and rotation, and off-host custody of the database's own backups. `C1` states explicitly that it does not decide this; it joins the existing off-host-key-custody backlog item | `X-2` gaps, `SR-D8`/`DO-D8` | open |
| `O-6` | Retention policy per audit/job-log table. `C1` §4 records a default *proposal* of 400 days, explicitly to be set by the Product Owner at a later movement | `§5.4` proposal | open |
| `O-7` | The corporate-policy verification that `DIRECTORY-POSTURE` (D-6) makes a precondition for persisted group references and the read-only service-account bind (§4.4). Until recorded, that function stays disabled | baseline D-6 | open |
| `O-8` | The seven reference-product UI details the predecessor marked `[SCREENSHOT]`. No reference material was supplied; the chosen defaults stand and none blocks. The Material Design 3 decision settled the visual system, not these items | `§6.11` | open, non-blocking |
| `O-9` | An in-browser profile editor authoring step content. Deferred by baseline §5 item 5 until a later phase, and it requires an `AGENTS.md` amendment that has not been made. Until then profile authoring is a governed non-browser content path: versioned files imported by an administrator CLI, validated on import, approved in the UI by a second `role:backup_admin`. The browser selects, assigns, schedules, approves and reads; it never writes step content | `UA-5`, Phase 2 | open; declining it permanently is a legitimate product outcome |
| `O-10` | A canonical identifier spanning the device registry's `device_id` and evidence's `entity_id`. Every per-device view eventually needs it; no frozen contract supplies it | `U-4` (requirements draft) | open |

Closed by the repository since the predecessor was written, and therefore
**not** open: the build-tool question (Gradle, `B1-1a` §4) and the
repository-layout question (this repository's `ui2/` sub-tree, baseline §2
`REPOSITORY`). The predecessor's `U-J5` — a real-environment protocol for an
`F4` shadow run — is **void**, not open: no `F4` state exists (§6.3).

---

## 10. Contradictions reported, not reconciled

`AGENTS.md` requires reporting a disagreement between authorities rather than
silently reconciling it. The predecessor's `UA-1`…`UA-8` are carried here
with their current state; none is reconciled by this contract.

| id | Authority | Disagreement | State at freeze |
| --- | --- | --- | --- |
| `UA-1` | `OPERATOR_CONSOLE_ARCHITECTURE.md` §6 (FROZEN) | payload-shape parity between console and exporter vs UI 2.0's separate typed read API | **Applied 2026-09-12.** The projection-parity invariant replaced the payload-shape-equality paragraph in the frozen file, recorded in that document's own amendment section. Resolved |
| `UA-2` | `OPERATOR_CONSOLE_ARCHITECTURE.md` §3 (FROZEN) | "no frontend framework / bundler" vs UI 2.0's own build | **Applied 2026-09-12.** The §3 row now binds the shared report bundle only, so UI 2.0's own build is not caught by it. Resolved |
| `UA-3` | `PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §13 (FROZEN) | "the control plane's console **is** the `CON.x` console" vs UI 2.0 succeeding it module by module | **Applied 2026-09-12**, with the module-by-module succession clause spliced into §13's sentence. Resolved |
| `UA-4` | `PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §11 (FROZEN) | "the operator does not choose SSH/API commands … and will not" vs profile-driven backup | amendment text drafted: the operator chooses **among approved profiles** and the run path never accepts a command; authoring is a governed content path. **Applied 2026-09-12** as an appended amendment; §11's original sentence is retained above it. Resolved |
| `UA-5` | `AGENTS.md` architectural invariant | "no command … ever originates in the browser" vs an in-browser profile editor | **no conflict in the shipped shape**: authoring is outside the browser (§9 `O-9`). The conflict exists only for the deferred editor, which needs a constitutional amendment first |
| `UA-6` | `M14` §6 rule 4, `LD-3`, `LD-7` | no browser credential; nothing persisted; one bind on the TTY | amended for UI 2.0 only (§4.2, §4.3). `M14` itself is unchanged and stays the local loopback design, parked by the Product Owner. Not a live contradiction, recorded so the local rules are not read as broken |
| `UA-7` | `OPERATOR_CONSOLE_ARCHITECTURE.md` `C-D7` | schedule editing "not in this track" | answered by the gate `C-D7` asked for (§5.7), for UI 2.0 only |
| `UA-8` | `OPERATOR_CONSOLE_ARCHITECTURE.md` §7.1–§7.3 | loopback listener with a per-launch token vs a network-exposed TLS listener with sessions | this is the deployment-class decision those rows reserved; baseline `SR-D4-DISPOSITION` confirms `C3` is that decision. Disposed |

One further contradiction, recorded by this contract and **not** reconciled:
the predecessor is cited as authority item 4 by four FROZEN contracts, and
marking it `SUPERSEDED` does not by itself repoint those citations. Until
each citing contract's own authority chain names this contract instead, those
four chains point at a superseded document. Rewriting a frozen contract's
authority chain changes what it requires, so it is the contract owner's act,
not this movement's.

---

## 11. Predecessor claims the repository or a frozen contract disproves

Recorded so the same assertions are not reintroduced:

1. **The `F0`–`F5` migration ladder**, with `F2` "Python writes projections,
   Java reads" and `F3` "Python executes, Java submits" as *rest states*, and
   `F4` as a Python/Java shadow-run parity state. Disproved by baseline §1
   item 2 and the development workflow: UI 2.0 executes every job in Java
   from the first job, and no Python sits on the execution path in any state.
   The predecessor continued to use `F`-state vocabulary in its own §3.1,
   §8.1–§8.3, §12, §14 and `AG-J12` after its §3.2 was amended — an internal
   inconsistency this successor removes by carrying only `CAP-*`.
2. **"Back up now does not exist as a browser action", refused for every
   role unconditionally.** Disproved by baseline `UI-OPERATIONAL-RUN-NOW`
   (D-2b) and `APPROVAL-MODEL` (D-2c): a Run Now of an approved profile
   against an approved target is a queued job request under `D7` plus a
   reason. The refusal survives only for the unapproved case (§5.6).
3. **Four-eyes approval on every backup run.** Disproved by `APPROVAL-MODEL`:
   four-eyes attaches to the profile **version** and to **schedule
   authorisation**; a routine run inside an approved scope needs no second
   human; restore and later controlled failover need per-operation
   independent approval instead.
4. **The illustrative Check Point profile** — `show backup status` polling,
   discovering the artefact by listing a directory, deleting it by the
   discovered name, and a precondition the engine rather than the profile
   owns. Refused by `C4` §1.4/§2.4 against the real gate-signed-off command
   tuple; none of it ships.
5. **Session timeouts stated as proposals** (30-minute idle, 10-hour
   absolute, 15-minute re-validation interval). Not wrong, but no longer
   proposals: `C3` fixes them as contract values with a closed `end_reason`
   vocabulary, and `C3` wins.

No predecessor claim about an artifact's existence was found to be false in
the way the B1-1 predecessor's were: that document asserted a build it could
not observe, whereas this one asserted no implementation at all. Its defects
are the five above — superseded decisions and one invented command sequence —
not false observations.

---

## 12. Bucket census at freeze

Counted over the clauses §2–§8 carry: **`S-B` 13, `S-U` 12, `O` 10.** The
`S-U` count is the honest measure of how much of the UI 2.0 architecture is
decided and unbuilt — most of the backup subsystem (§5, eight clauses), the
projection set and its parity test, the pre-execution `D7` re-evaluation, the
SSH transport choice and the Java CLI. Two clauses are deliberately split
rather than forced into one bucket, and the split is stated in place: §3.2
(mechanism built, token coverage not) and §6.2 (four constructions built,
three not). One clause is settled in shape but closed in operation by an
unmet precondition: §4.4.

---

## 13. What this contract does not prove

A frozen clause describing an existing artifact proves the artifact exists
and is shaped as stated. It does not prove the artifact is correct, and it
proves nothing about real-environment behaviour. At freeze time `ui2/` has no
real-environment evidence of any kind, no capability has reached
`CAP-VALIDATED`, and `REAL_ENV_VALIDATED` is unreachable for every row until
device contact is separately authorized. An `S-U` clause proves a decision,
never a delivery; an `O` item proves only that the question is open.

---

## 14. Cross-references

- `AGENTS.md` — authority hierarchy, contract-status law, identity law,
  evidence laws, raw-evidence law, UNKNOWN/fail-closed law, action taxonomy.
- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — §1 direction, §2 Phase 0
  decisions, §3 acceptance sentences, §5 amendment list.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` — the plan and the capability
  movements.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` — schema, data classes,
  retention, key-custody boundary, Oracle portability in schema terms.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` — job lifecycle, claim-time
  checks, owner/approver/execution identity.
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` — the identity,
  session and RBAC specification §§3–4 of this contract point to.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` —
  capability registry, closed step-kind set, gate resolution, transport
  decision.
- `docs/design/UI2_0_C5_AMENDMENTS_BUNDLE.md` — the amendments folded into
  this contract.
- `docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md`,
  `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md`.
- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` — the module map,
  build, reproducibility and test-carrier rules §6.1 points to.
- `docs/design/UI2_0_BASELINE_DIRECTORY.md`,
  `docs/design/UI2_0_MOCKUP_REFERENCE_NOTES.md` — screen vocabulary and UX
  ground truth.
- `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`,
  `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`,
  `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`,
  `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md`,
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md`,
  `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`,
  `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`,
  `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`.
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` — superseded predecessor,
  historical only, never implementation authority.
- `docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md`,
  `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`,
  `docs/design/PCP_STORAGE_ENGINE_DECISION.md`,
  `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` —
  evidentiary background only; each is DRAFT and none is authority here.
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` — the review
  record behind the baseline.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — network-device command gate, approval
  boundaries. `PRIVACY_AND_DATA_HANDLING.md` — data classes.
- `utils/action_taxonomy.py` — the single source of truth for action classes.
