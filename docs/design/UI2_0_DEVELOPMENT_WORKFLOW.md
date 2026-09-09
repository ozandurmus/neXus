# UI 2.0 — development workflow (revised: Line-1 collectors are reference, not runtime)

## Status

**BASELINE — Product Owner adopted as the foundation of the architecture
(2026-09-09, revision 2, after Astra's final review, §10). Not a frozen
contract; freezes happen in B0.**
Produced 2026-09-09 in the Product Owner's engineering session (standing
in-session PO precedent) after the council round and two Astra rounds
(`docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` §4–§7). It
supersedes the step list delivered in-session earlier the same day and the
parts of `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §3 and §8 that assumed
the existing Python feature code keeps executing inside UI 2.0.

Governing directive (Product Owner, 2026-09-09, verbatim in Turkish):

> Feature scriptlerini direk kullanmayacağız. Bunu kesinlikle istemiyorum. O
> scriptlerden yola çıkarak, yeni yapımızda veri toplayıp parse edecek yapıyı
> oluşturmamızı istiyorum. Oradaki scriptler statik bir sayfa beslemek için
> yaratıldı ve iş spesifik. Artık ortak amaç farklı.

Translation for the record: the existing feature scripts will **not** be
used directly. Starting from them, a new collect-and-parse structure is to
be built in the new architecture. Those scripts were written to feed a
static page and are task-specific; the shared purpose is now different.

This document is the input for the `DECIDE` episode (Phase 0 below). Nothing
in it authorises implementation.

---

## 0. What this changes, in one paragraph

Until today the plan had two runtimes cooperating for a long time: the Java
shell would render and submit, and the Python feature code would keep
collecting and parsing, first as the only executor (`F2`/`F3`, `main.py
--worker`) and later as the reference against which a Java port is proved
(`F4`). That is withdrawn. **UI 2.0 has one runtime, Java, from the first
job it executes.** The Python collectors, runners, parsers and projections
become a **reference corpus**: the place where the product's validated vendor
knowledge lives today, to be extracted into specifications and fixtures and
re-implemented under the new contracts. Line-1 (the Python product) is not
deleted and keeps serving its static pages; it simply stops being a
dependency of UI 2.0.

---

## 1. The principle, stated so a movement can test against it

**P-1 — No Python in the UI 2.0 runtime.** No UI 2.0 component invokes
`main.py`, `console/`, `utils/collection_executor.py`, any `*_runner.py`,
`*_collector.py`, `checkpoint/scripts/*.sh`, or any other Line-1 executable,
in any deployment form (subprocess, container sidecar, job queue consumer,
HTTP shim). A test in the UI 2.0 tree enforces this by inspection of the
dependency graph and the container image.

**P-2 — Reference, not reuse.** A Line-1 collector may be *read* to produce
a capability specification and sanitized fixtures (§3.1). Its code, argv
templates, HTML/projection shapes and file layouts are not ported line by
line. Where the Java implementation makes a different choice (parser
strategy, evidence shape, step granularity) the difference is recorded in
the capability spec, and the real-environment validation is re-earned.

The boundary with P-3: what is *not* carried over is the Python argv and
payload structure, file layouts and page shapes. What *is* preserved is the
validated meaning, order, timeout and trust behaviour of each device
command. Re-implementation is not a licence to change validated vendor
behaviour without a recorded reason (R-05).

**P-3 — Vendor knowledge is preserved, not the code.** What must survive the
transition is enumerated in §3.1: exact commands and API calls, their
network-device command-gate records and classes, prompt/expect behaviour,
known vendor quirks, parser semantics, and evidence-of-truth rules. Losing
any of these is a regression; losing the Python that carried them is not.

**P-4 — Line-1 contracts remain authority for semantics.** Frozen documents
such as `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7 (gate entries), the
OP.0/OP.1 readiness models, the capability-state resolver (`D1`–`D7`) and
`utils/action_taxonomy.py` classes are vendor/product semantics, not Python
artefacts. UI 2.0 implements them; it does not re-decide them without an
amendment. P-4 is a **transition** authority: once a capability is
`CAP-VALIDATED`, its UI 2.0 capability spec becomes the product-wide
authority for that capability's semantics, and the Line-1 files remain
provenance only. New validated vendor knowledge is written into the
capability spec, never back into Python code (Astra 4.3).

**P-5 — Line-1 stops growing product features.** From this directive on,
Line-1 movements are limited to: contracts, vendor-semantic findings,
real-environment captures (fixture sources), gate-record work, and fixes
needed to keep the static product usable. New user-facing Python features
are not dispatched (this matches the earlier "no new tasks" instruction).

---

## 2. What is withdrawn from the earlier plan and design

| Item | Where it lived | Disposition |
|---|---|---|
| `F2` "Python writes projections, Java reads" and `F3` "Python executes, Java submits" as *rest states* | `UI2_0_ARCHITECTURE_DESIGN.md` §3.2 ladder | **Withdrawn.** The ladder collapses to capability maturity states: `CAP-SPEC` (spec + fixtures exist) → `CAP-OFFLINE` (implemented in Java against fixtures) → `CAP-VALIDATED` (PO-run end-to-end in the Java product on a real device) → `CAP-RELEASED` (integrated in a screen/flow). No state has Python on the execution path. Feature slices are named `REL-*` (§5) so maturity and slice labels never collide (Astra 4.2). |
| `main.py --worker` (PostgreSQL job-queue consumer in Python) and the `workflow_argv` single-orchestration-path rule (`AG-U2`) | Design §3.2 `F3`, §15; brief §7 step 4(a); earlier workflow B1 step 11 | **Withdrawn.** The worker is Java (§4, B1). `AG-U2` is re-stated as "one job-execution path, in Java, test-enforced". |
| `F4` "shadow run: Java executor replaces the Python run on pilot targets, MATCH on fingerprints" and Astra's offline replay comparison | Design §3.2/§8.5; brief §6.6 | **Replaced.** Parity is proven against *fixtures extracted from real captures* (§3.1) plus a PO-run real-environment validation. There is no dual run, so the dual-run half of the contact-budget concern
disappears; coordination between Line-1's remaining schedules and Java
validation on the same devices remains a rule (§3.4, R-08). Astra's replay idea survives only as "fixtures are real sanitized captures, not hand-written samples". |
| "The Python test suite of a feature becomes the fixture table of its Java port" | Design §8.5 | **Narrowed.** Python *tests* encode Line-1 behaviour, including page-specific behaviour we do not want. Only the *fixtures and parser expectations* are harvested, through the extraction contract, and each is re-checked against the capability spec. |
| Two-axis ladder (product integration vs execution migration) | Brief §6.2 (Astra R1) | **Withdrawn.** With a single runtime there is no execution-migration axis. |
| Python-closure rule / capacity hand-off rule (Astra objection 1) | Brief §6.1, §6.10 | **Resolved by construction.** A collector is "closed" when its capability spec and fixtures are extracted and the Java capability reaches `F3`. Line-1 does not grow (P-5), so the queue cannot fail to shrink. |
| "RB.3b sequence as the first shipped profile" and "RB.2/RB.3b as the first two shipped profiles" | Design §6.3 (per K-3); earlier workflow | **Re-framed.** RB.3b's frozen command tuple and gate records (`BACKUP_RECOVERY_CONTRACTS.md` §7.3–§7.5, §7.7) are the *specification* of the CP Gaia backup capability in the new engine. Astra's CP-D1 objection (interactive-shell transport) is answered by the new engine's transport adapters, not by inheriting Python behaviour. |
| First read screen "from an existing Postgres seam" (K-11) and the Registry seam alternative | Brief §4.1 K-11, §7 step 4(b) | **Withdrawn.** UI 2.0 owns its schema from B1. Importing Line-1 evidence history is a separate, optional decision (§8, D-4). |
| "Temporary Python worker" as the way to get a daily-usable product early (Astra objection 3) | Brief §6.9 | **Withdrawn.** The first daily-usable flow is a Java collection job end to end (B1 step 6) — smaller than a backup, real from the first day. |
| Migration ladder `F1` entry condition = `REAL_ENV_VALIDATED` in Line-1 | Design §3.2 | **Replaced.** Entry to extraction requires only that the Line-1 collector exists; its Line-1 validation status is *recorded in the spec* (§3.1 field 8) and determines how much re-validation the Java capability needs. Unvalidated Line-1 features (CE.2, tablestat) are extracted as "unproven" specs, which is fine: they were unproven in Python too. |

Everything not listed here (shell boundary §4, RBAC §5, backup engine model
§6 minus the sample, sessions §7, Java stack choices §8.1–8.4, storage §9,
Oracle portability rules) stands as amended by the brief §7.

---

## 3. What is added

### 3.1 The capability extraction contract (`C6`, new meaning)

One extraction produces, per Line-1 collector or runner, a **capability
spec** and a **fixture set**. Both are committed under the UI 2.0 tree and
are the *only* thing the Java implementation is allowed to depend on.

Capability spec fields (all mandatory). Every field carries a state:
`KNOWN`, `UNKNOWN` (with a mandatory validation plan in field 9) or
`NOT_APPLICABLE` (only when the field genuinely does not apply). An
`UNKNOWN` field never blocks the spec or the offline implementation; it
blocks **device execution** of the affected step until it becomes `KNOWN`.
Inventing certainty to avoid `UNKNOWN` is a spec defect (R-04).

1. **Identity** — capability id, vendor, platform/role scope (e.g. CP Gaia
   gateway, CP management, PAN firewall, Panorama), read/operational-write
   class per `utils/action_taxonomy.py`.
2. **Transport** — SSH exec-per-command, SSH interactive/expect (only where
   the Line-1 evidence proves it necessary), PAN XML API, SFTP/SCP; with the
   trust/pinning rule that applied (`utils/cp_ssh_trust.py`,
   `utils/pan_tls_trust.py` semantics).
3. **Command / call tuple** — exact strings, ordering, per-step timeouts,
   expected prompt or completion marker, and the network-device command-gate
   entry each one is covered by (reference to the gate record; a step with no
   gate record is recorded as `UNKNOWN: requires gate entry`; the offline
   parser may still be built, but the step is not executable on a device
   until the entry exists).
4. **Parser semantics** — what is extracted from each output, the tolerant
   rules (whitespace, version drift, VSX/context multiplicity, HA member
   naming, PAN serial representation), the failure classification
   (`OUTCOME_UNKNOWN`, partial, refused), and the evidence-of-truth rule
   (which raw fragment proves each derived fact).
5. **Evidence shape** — which fields are persisted, which are discarded
   (`discard_raw` semantics from design §6.4), and how `D1`–`D7`
   capability-state inputs are produced.
6. **Known vendor quirks** — every real-environment finding recorded in
   `project/build_history.json`, roadmap notes or the collector's comments
   (e.g. channel-drain "done marker" diagnostics for CP inventory, PAN HA
   serial identity, ClusterXL member sessions).
7. **Line-1 source pointers** — files and functions read, so the extraction
   is auditable; never imported.
8. **Validation status inherited** — `REAL_ENV_VALIDATED` (with the date and
   the fingerprint of the Line-1 run), `FIXTURE_ONLY`, or `UNPROVEN`.
9. **Re-validation plan** — what the PO must run, on which device class, to
   move the Java capability to `CAP-VALIDATED`.

Fixture set rules:

- Every fixture is a **sanitized real capture** produced through the
  Private Replay tokenizer path (`docs/design/PRIVATE_REPLAY_ARCHITECTURE.md`,
  Phase A slice 1 merged 2026-09-09): domain-separated HMAC pseudonyms for
  every identity, synthetic display labels, no raw hostnames/serials/IPs.
  Synthetic material is allowed and marked: `SYNTHETIC` for never-captured
  error paths, `DERIVED` for variants built from a real capture (version
  drift, empty/multiple results, parser boundary cases, relationship
  consistency). Neither substitutes for device validation (R-05).
- The tokenizer must preserve the structure the parser tests measure:
  address format, serial length/shape, equality of the same identity across
  different outputs, cluster/context membership and ordering. Domain-
  separated HMAC pseudonyms do not guarantee this by themselves; the fixture
  set states which properties are preserved and the extraction movement
  tests them (R-05).
- Fixtures carry the command tuple they answer, the vendor version, and the
  capture date; the Java parser test asserts against the spec's parser
  semantics, not against Python output.
- The DLP gate (`utils/repository_privacy.py`, CI privacy gate) applies to
  fixtures exactly as to any other file; the tokenizer output must pass it.

### 3.2 The Java collection engine (core of B1)

- **Transport adapters** — SSH exec-per-step (Apache MINA SSHD client),
  SSH expect/interactive (only if a spec demands it; CP-D7 decision),
  PAN XML API (HTTP client with the trust rules), SFTP/SCP fetch.
  Credentials are resolved at execution time from the server-side
  credential store defined in the platform contract; nothing is written to
  the local disk of the operator.
- **Capability registry** — the runtime form of the specs in §3.1: a
  capability declares its transport, its steps (closed step kinds, per
  design §6.3), its gate references and its parser. The registry is the
  single place the E1–E7 gate chain consults for "is this action known and
  gated".
- **Step executor** — runs steps in order with expect-style validation,
  bounded timeouts, and a crash-safe job record (leasing, heartbeat,
  duplicate detection, `OUTCOME_UNKNOWN` on lost worker), answering Astra's
  objection 2 in Java, once.
- **Parser framework** — per-capability parsers with the evidence-of-truth
  rule enforced (a derived fact links to the raw fragment that proved it, or
  it is not stored).
- **Evidence writer** — persisted projections into the UI 2.0 schema (Flyway
  is the only migration authority; the Line-1 `utils/db_migrations.py` /
  `migrations/` path is not shared).
- **Backup profiles** are capabilities with the operational-write class and
  the design §6 rules (four-eyes approval, immutable versions, connectivity
  check before enable). The profile engine is therefore not a second engine;
  it is the same executor with stricter admission.

### 3.3 The per-feature movement pattern

Every product feature in UI 2.0 goes through the same four movements:

| # | Movement | Type | Tier | Produces |
|---|---|---|---|---|
| a | **Extract** | `AUDIT` (read-only over Line-1) | Sonnet 5, normal (extended-high when a vendor-semantic call is needed) | capability spec + fixture set, gaps listed |
| b | **Implement** | `BUILD` in Java | Sonnet 5, normal | capability + parser + tests against fixtures, `CAP-OFFLINE` |
| c | **Validate** | PO-run session: the PO **triggers the Java capability itself** in a controlled environment and reviews the result (R-07) | human | `CAP-VALIDATED` record with fingerprint; spec amended with anything new |
| d | **Integrate** | `BUILD` (UI + API + projections) | Sonnet 5, normal | screen(s), `CAP-RELEASED` |

Validation type follows the kind of capability (Astra 4.1): device-contacting
capabilities need step c on hardware; pure functions (plan compiler, alarm
rules, retention, local UI logic) are validated by contract and offline
tests; product flows are validated by an operator scenario. Screens may be
built on fixture data before step c; a capability is *published as usable*
only after it.

A capability is a **semantic unit, not a Line-1 file** (Astra 4.1): shared
facts such as HA identity are extracted once and consumed by inventory,
readiness and backup alike. The inventory in §4 is a source list, not the
capability boundary.

Steps a and b can be one movement for small capabilities; c is always the
PO's own session (device-contacting commands are proposed by the agent and
executed by the human, standing rule).

### 3.4 Device-contact coordination during the transition (R-08)

While Line-1 still runs schedules for the static product, the same devices
receive Java validation runs. Rule: per device and job type, only one line
may contact the device in a given window. Java validation uses an explicit
window with the corresponding Line-1 schedule paused, or an explicit target
ownership record. Job collisions *inside* Java are handled by the C2 job
contract; Line-1 versus Java collisions are handled by this rule, and no
shared runtime is needed for it.

### 3.5 Provenance after `discard_raw` (R-06)

"A derived fact links to the raw fragment that proved it" is satisfied by a
**provenance record**, not by retained raw output: run and step id, parser
and capability version, capture/artifact id, source location within the
output, and a *permitted sanitized evidence fragment* where the data class
allows one. A hash identifies the source and protects integrity; it does
not by itself prove the content of something that was deleted, and the UI
never implies raw content is retrievable when it was discarded. Job logs,
audit entries and complete encrypted backup artefacts are distinct data
classes; a raw-retention amendment is on the B0 agenda (C5) and until it is
accepted no new raw retention is introduced, while metadata logs ship.

---

## 4. Extraction inventory (what becomes what)

| Line-1 source (reference only) | UI 2.0 capability | Class | Line-1 status inherited | Notes |
|---|---|---|---|---|
| `checkpoint/scripts/cp_inventory.sh`, `checkpoint/cp_runner.py` | CP Gaia inventory / `show version`, `show asset`, HA state read | read | REAL_ENV_VALIDATED (with channel-drain quirk open) | **First capability** (B1 step 5): lowest risk, richest quirk record |
| `checkpoint/scripts/vsx_collect.sh`, `checkpoint/vsx_runner.py`, `checkpoint/vsx_parser.py` | CP VSX context enumeration | read | REAL_ENV_VALIDATED | multiplicity rules go into parser semantics |
| `configuration/checkpoint_config_collector.py` | CP configuration snapshot | read | REAL_ENV_VALIDATED | evidence shape re-decided (no HTML) |
| `panorama/panorama_runtime_runner.py`, `panorama/pan_identity.py` | PAN/Panorama runtime + identity | read | REAL_ENV_VALIDATED, **P0 open** | the PAN auth transport P0 (`PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md`) is fixed *in the Java transport adapter* (header/POST-based auth), never re-implemented as query-string credentials |
| `configuration/panorama_config_collector.py` | Panorama configuration export | read | REAL_ENV_VALIDATED | same transport fix |
| `checkpoint/preflight_collector.py`, `cp_preflight_battery.py`, `cp_preflight_extraction.py`, `cp_preflight_projection.py`; `panorama/preflight_collector.py` and siblings; `utils/failover/*` | OP.0 HA readiness battery (CP + PAN) and readiness derivation | read | mixed (per battery item; `cphaprob tablestat` UNPROVEN pending 0049) | readiness *derivation* is a semantic contract, ported as rules, not code |
| `utils/failover_plan/` (OP.1.S1, movement 0048, in progress) | Failover plan compiler + dry run | read (compile only) | contract FROZEN (Option A) | the frozen contract is the spec; Java implements it in REL-FAILOVER-READINESS |
| `utils/recovery_collect.py`, `checkpoint/checkpoint_recovery_collector.py`, `panorama/panorama_recovery_collector.py`, `BACKUP_RECOVERY_CONTRACTS.md` §7 | Backup profiles: PAN device-state/config export (RB.2), CP Gaia `add backup local` + SCP (RB.3b), `show backups` | read + operational-write | RB.2 REAL_ENV_VALIDATED; RB.3b hardware-gated | gate records §7.1–§7.7 are reused verbatim as spec field 3 |
| `utils/recovery_store.py`, `recovery_manifest.py`, `recovery_validation.py`, `recovery_retention.py`, `recovery_crypto.py`, `recovery_key_custody.py` | Backup artefact store, manifest, validation, retention, encryption, key custody | n/a (server-side) | contracts frozen | storage layout/manifest are contracts (P-4); crypto choices re-reviewed for the Java stack (K-7) |
| `utils/restore_readiness.py`, `failover_readiness_ui.py`, RB.5 (movement 0050) | Recovery readiness projection | n/a | in progress | projection *shape* is harvested; HTML module discarded |
| `utils/compliance_check_engine.py`, `compliance_*.py` (CE.2 primitives) | Checks engine and rule packs | read (derivation) | UNPROVEN (movement 0037 ran out of budget) | extracted as spec with `UNPROVEN`; re-validation required |
| `utils/device_registry.py`, `discovery_lifecycle.py`, `first_contact_producer.py`, `pre_enrollment_identity_probe.py`, `enrollment_audit.py` | Devices & discovery, enrollment, identity relationships | read | REAL_ENV_VALIDATED | first F-slice after B2 |
| `utils/capability_registry.py`, `capability_state_resolver.py`, `capability_applicability.py`, `capability_vendor_support.py` | Capability state `D1`–`D7` | n/a | contract | ported as rules; this is what RBAC visibility (design §5) keys on |
| `utils/action_taxonomy.py` | Action classes | n/a | constitutional | ported verbatim as an enumeration; class 1 remains non-console-submittable pending the taxonomy amendment (D-2 below) |
| `utils/event_signal_intake.py`, `signal_intake/` | HTTP-in signal intake | n/a | slice 1 merged | interface contract reused; implementation is Java (B2) |
| `utils/support_bundle.py::Tokenizer`, `replay/` | Sanitization for fixtures and exports | n/a | Phase A merged | **used as a tool during extraction** (Python may run at extraction time — it is not in the UI 2.0 runtime, P-1 is not violated) |
| `console/`, `templates/`, `static/`, `utils/html_export.py`, `*_ui.py` | — | — | — | **Not extracted.** Static-page concerns; UI 2.0 designs its own screens against the same evidence |

---

## 5. Revised workflow

Tier legend: `S-n` = Sonnet 5 normal, `S-h` = Sonnet 5 extended thinking
(high), `F-h` = Fable/Opus high (only where a cross-subsystem decision is
made). Counts are movements dispatched through the orchestrator; PO sessions
(real-environment validation, DECIDE episodes) are listed but not counted.

### Phase 0 — DECIDE (1 PO episode, `S-h`)

Agenda, in order; each item changes text in the design or the brief. Line-1
is the **reference/maintenance line**; the Java line is the **only product
development line** (Astra §2).

1. **RUNTIME-DIRECTION (Karar 1, restated)** — Line-1 collectors are reference only; no Python
   in the UI 2.0 runtime (P-1…P-5). *Proposed: accept as stated.*
2. **JOB-UNCERTAIN-OUTCOME** — a write was sent and the result could not be
   recorded → `OUTCOME_UNKNOWN`, no automatic second write, operator
   resolution path. *Council/Astra position; proposed: accept.*
3. **UI-OPERATIONAL-RUN-NOW** — a browser-initiated one-off run of an
   operational-write profile, as a taxonomy amendment. *Proposed: accept,
   pending the PO's explicit confirmation.* These are two separate
   decisions; accepting one does not accept the other (R-01).
4. **APPROVAL-MODEL** — what needs four-eyes: proposed *profile version +
   schedule assignment* (approved once), while a Run Now of an
   already-approved profile on an approved target needs the D7 role and a
   mandatory reason but no second approver; an unapproved profile or target
   always needs four-eyes. Night schedules and UI must use the same model,
   no second path (Astra 4.5). *PO decision.*
5. **RAW-RETENTION** — whether any raw device output may be retained beyond
   the sanitized evidence fragment, per data class (R-06). *Proposed: no,
   until a named need appears.*
6. Freeze slicing: platform contract (C1–C5) and backup/artefact/restore
   engine contract (C7) as two freezes; C6 (extraction) is neither, it is
   a working template frozen with C4. *Proposed: two.*
7. Persisted encrypted directory group references + read-only directory
   service account (design §5.2/§7.5). *Corporate posture question.*
8. Same repository, `ui2/` sub-tree, second toolchain in CI. *Proposed: yes.*
9. Oracle: PostgreSQL frozen (X-2); jOOQ Oracle licence check deferred until
   a corporate mandate appears. *Proposed: confirm deferral.*
10. M14/M14L local LDAP console: design kept, not implemented before UI 2.0
   §7. *Proposed: park.*
11. Disposition of in-flight Line-1 movements 0048 (OP.1.S1), 0049
   (tablestat), 0050 (RB.5) under P-5 (§8, D-5).

### Phase B0 — Contracts and baseline directory (9 movements, `S-h`)

B0 is not only engine and extraction. It must also name, with an owner and a
version, the product-baseline contracts the council settled: navigation and
shared screen states (UX-D1…D5), the feature contribution contract (how a
capability adds a screen, a job type and an alarm source), the alarm
lifecycle, and the log/audit data classes. Where an existing frozen document
already owns one, B0 references it bindingly instead of writing a new one
(R-03). B0/B1/B2 are dependency gates, not a serial wall: B1 starts when
C1–C4 and the authorization/audit rows are frozen; B2 adapters never block
feature slices; real mutation acceptance never skips audit/authorization
(R-03, Astra 5.5).

| # | Movement | Scope |
|---|---|---|
| 1 | **C1 Platform & schema contract** | UI 2.0 schema ownership, Flyway as sole migration authority, projection tables, audit table from the first mutation (new finding id, correcting the brief's §11.5 misattribution to SR-D8/DO-D8), per-component secrets (K-8), key custody (K-7) |
| 2 | **C2 Job execution contract** | job record, leasing/heartbeat, timeout, worker loss, duplicate detection, `OUTCOME_UNKNOWN`, owner/approver/execution identity for scheduled jobs, schedule optimistic concurrency. **No worker mode in Python.** |
| 3 | **C3 Identity, sessions, RBAC contract** | design §5/§7 as amended; single active session table; role tokens → bindings → AD group refs; gate chain E1–E7 on HTTP |
| 4 | **C4 Capability registry & command-gate resolution contract** | runtime form of §3.1; gate-registry resolution rule (K-4); closed step kinds; VSX/ClusterXL rules (CP-D6); transport decision (CP-D7) |
| 5 | **C5 Amendments bundle** | CON.0 amendment (separate shell, approved), taxonomy amendment for Run Now (if Karar 2 = B), AGENTS.md amendment for the Phase-2 browser profile editor (UA-5) and, later, the mediated terminal |
| 6 | **C6 Capability extraction contract** | §3.1 verbatim as a frozen template, plus the extraction inventory (§4) as the ordered queue |
| 7 | **C7 Backup, artefact and restore engine contract** | profile rules from design §6 as amended; artefact store, manifest, validation, retention, key custody (from the frozen RB contracts); **restore execution**: plan, preconditions, execution steps, result verification, failure behaviour, class and approval per APPROVAL-MODEL (R-02) |
| 8 | **Baseline directory** | one index: product objects and navigation, shared screen states, feature contribution contract, alarm lifecycle contract (outbox, de-duplication, resolve/silence, delivery failure, causality limits), log/audit data classes, SNMP status exposure — each with owner document and version (R-02, R-03) |
| 9 | **Extraction tooling** (`S-n`, Line-1) | a Line-1 script that runs the Private Replay tokenizer over an existing evidence/capture directory and emits a fixture set in the C6 layout; DLP-gated; this is the one Python deliverable that serves UI 2.0, at extraction time only |

### Phase B1 — Skeleton and first real jobs (14 movements, `S-n` unless noted)

| # | Movement | Scope |
|---|---|---|
| 1 | Gradle multi-module skeleton, CI job, Docker image, Testcontainers PostgreSQL | `ui2/` sub-tree; P-1 dependency-graph test present from day one |
| 2 | Schema V1 via Flyway | tables from C1; audit table; projections for the first capability |
| 3 | Identity & sessions | LDAP bind (UnboundID), single-active-session rule (design §7.4 table as tests), role bindings, E1–E7 chain on the HTTP layer (`S-h`) |
| 4 | **Collection engine core** (`S-h`) | capability registry, step executor with the C2 crash-safe job record, parser framework, evidence writer; **only the transport the first capability needs is implemented** (SSH exec); PAN XML API and SFTP adapters are defined as interfaces and built in their slices (Astra 5.3) |
| 4b | **Minimal device model and onboarding** | Device / Endpoint / CredentialReference records, an authorized manual registration flow and a "test target" flag, so a job can be started against a real, registered object; full discovery stays in REL-DISCOVERY; no hard-coded CP object (R-07) |
| 5 | **Extract: CP inventory, narrow subset** (pattern step a, Line-1 read-only) | spec + fixtures for a validated subset (`show version`, HA state) from `cp_inventory.sh`/`cp_runner.py`; channel-drain quirk recorded as `UNKNOWN` with a validation plan; the subset is chosen for being small and validated, not for quirk richness (Astra 4.6) |
| 6 | **Implement: CP inventory capability** (step b) | first Java capability end to end against fixtures; `CAP-OFFLINE` |
| — | **PO validation session** (step c) | the PO **starts the capability from the Java product** against the registered test CP and reviews transport, trust check, timeout, target identity and persisted evidence; `CAP-VALIDATED` (R-07) |
| 7 | Jobs screen + Run Now (read class) + step log | submit a read job from the browser, watch steps, see outcome; Run Now for read-class only until Karar 2 lands |
| 8 | Audit & logs screen | every mutation since B1 step 2 visible; export-free |
| 9 | Device workspace (first read screen) | device list + inventory projection from step 6; RBAC visible-but-refused behaviour proven |
| 10 | Acceptance scenario A | three admins on one device concurrently, single-session takeover/refuse, correct state throughout (Astra's scenario, first half) |
| 11 | Acceptance scenario B | worker killed mid-step; job lands in `OUTCOME_UNKNOWN`; no duplicate device contact; recovery path visible (second half) |
| 12 | Deployment slice | Docker compose for the DEPLOY.1 server shape; secrets per component; DEPLOY.1/1A gates pass |
| 13 | **Second representative flow: artefact-producing read job** | PAN configuration export (RB.2 semantics, read class) through the same executor: artefact store, manifest, job/session state; proves the executor and UI model are not CP-inventory-specific (Astra 5.4). Re-estimate the whole plan after steps 6 and 13 (Astra 4.6) |

Exit of B1: an operator can log in, pick a device, run a read collection,
watch it, see the evidence, and see the audit trail — all in Java, no
Line-1 process anywhere.

### Phase B2 — Platform breadth (7 movements, overlapping with REL slices; never a serial gate for them)

| # | Movement | Scope |
|---|---|---|
| 1 | Capability registry UI | list capabilities, gate coverage, `D1`–`D7` state per device |
| 2 | Commands editor (compose-only) | Backbox-style profile authoring from closed step kinds; free-command stays separate and unimplemented until the AGENTS.md amendment (`S-h`) |
| 3 | Alert pipeline | thresholds/derivations over projections; alert table; acknowledgement audit; implements the alarm lifecycle contract from the baseline directory (outbox, de-duplication, resolve/silence, delivery failure) |
| 4 | SMTP + HTTP-out notifications | outbound only; secrets per component |
| 5 | Syslog + SNMP-trap-out | outbound only; SNMP polling stays bound to the command gate rules (no new device contact class) |
| 5b | SNMP status exposure (agent) | the product answers NMS reads about its own and its devices' recorded state; separate contract and test; no device polling implied (R-02) |
| 6 | HTTP-in signal intake | port of the `event_signal_intake` interface contract |

### Phase REL — Feature slices (each: extract, implement, PO validation, integrate)

| Slice | Capabilities | Movements | Notes |
|---|---|---|---|
| **REL-DISCOVERY** | registry, enrollment, identity relationships, pre-enrollment probe, first contact | 4 | daily-usable inventory of the estate |
| **REL-INVENTORY** | VSX contexts, CP configuration snapshot, Panorama runtime/config (PAN transport fix included) | 5 | PAN P0 closed here for UI 2.0 only; Line-1 record separate (§7) |
| **REL-BACKUP** | RB.2 PAN export profile, RB.3b CP Gaia profile (transport per CP-D7), store/manifest/validation/retention/key custody, recovery readiness projection, **restore execution per C7** (plan → preconditions → execute → verify → failure behaviour), accepted per supported device/version and artefact type | 7 | first operational-write class; Run Now per UI-OPERATIONAL-RUN-NOW; approvals per APPROVAL-MODEL |
| **REL-CHECKS** | CE.2 engine primitives and rule packs, posture/trend projections | 4 | inherited status `UNPROVEN`; PO validation mandatory before `CAP-RELEASED` |
| **REL-FAILOVER-READINESS** | OP.0 readiness battery (CP + PAN), tablestat item if 0049 proves it, OP.1 plan compiler + dry run per the frozen contract | 5 | **controlled failover execution (OP.2) is a named later deliverable, not part of Release 1** (roadmap OP.2.C prerequisites) |

### Phase S — Later releases, gated on amendments

| # | Movement | Gate |
|---|---|---|
| 1 | Browser profile editor (free-command) | AGENTS.md amendment (UA-5) accepted |
| 2 | Mediated terminal | AGENTS.md amendment; recorded session; class-1 command refusal in the mediator |
| 3 | Line-1 evidence history import | D-4 below |
| 4 | Controlled failover execution (OP.2) | OP.2.C prerequisites; separate release |
| 5 | Session recording | AGENTS.md amendment; not counted as equivalent to CLI audit before it exists |

---

## 6. Totals and duration (assumption-based estimate, not a scope commitment)

| Phase | Movements | Calendar (2 concurrent movements + PO sessions) |
|---|---|---|
| 0 DECIDE | 0 (1 PO episode) | 1 day |
| B0 | 9 | 2 weeks |
| B1 | 14 (+2 PO validations) | ~7 weeks |
| B2 | 7 (overlapping REL) | ~3 weeks |
| REL-DISCOVERY…REL-FAILOVER-READINESS | 25 (+5 PO validations) | ~16–20 weeks |
| S | 5 | later releases |
| **Total, Release 1** | **~55 movements** | **~5–6 months to daily use, ~7–8 months to the end of REL-FAILOVER-READINESS** |

**Release 1 definition (R-02):** Java platform + limited, validated device
scope + backup, restore and checks + failover *readiness*. It is not full
BackBox equivalence; that is tracked as a later edition with a device
capability matrix, and the end of REL-FAILOVER-READINESS is **not** called
"full scope".

Movement counts are a planning inventory, not a capacity model: the engine
core and an SMTP adapter are not equal units. Durations are re-estimated
after B1 steps 6 and 13 (Astra 4.6). Line-1 not accepting features does not
shrink the queue by itself; Java acceptance capacity and discovered gaps are
tracked per slice.

Budget guidance (from the overnight batches): `S-n` movements typically
close under the default budget; `S-h` contracts and B1 steps 3–4 should be
dispatched with `--max-budget-usd` raised from the start rather than
resumed after an `error_max_budget_usd` death.

---

## 7. What Line-1 does from now on (P-5 in practice)

- Finishes contract-validating work already in flight where it produces
  spec input (OP.1.S1 compiler validates the frozen contract; keep).
- Produces real-environment captures on request, through the PO, as fixture
  sources for extraction; these are the most valuable Line-1 outputs now.
- Keeps gate records, vendor-semantic findings and the taxonomy authoritative.
- Keeps its static pages working for the PO's own use until the equivalent
  UI 2.0 slice reaches `CAP-RELEASED`; no new pages, no new collectors.
- Carries its own acceptance record for the PAN auth transport P0
  (`PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md`): the Java transport adapter
  does not close it. While the Line-1 PAN runtime path is used, it is either
  fixed on the maintenance line or its use is restricted (the PO currently
  uses their own credentials manually). Java and Line-1 records stay
  separate (Astra 4.4).
- Its test suite stays green on `main`; the one known red
  (`tests/test_nexus_engineer_tool_gate.py::test_ac1_live_bug_regression_against_real_repository_state`,
  worktree-only) is tracked separately.

---

## 8. Open confirmations for the Product Owner

| id | Question | Proposed |
|---|---|---|
| D-1 | Accept P-1…P-5 as the wording of Karar 1? | Yes |
| D-2a | JOB-UNCERTAIN-OUTCOME: `OUTCOME_UNKNOWN`, no automatic second write? | Yes |
| D-2b | UI-OPERATIONAL-RUN-NOW as a taxonomy amendment? | Awaiting explicit "tamam" |
| D-2c | APPROVAL-MODEL: four-eyes on profile version + schedule; Run Now of an approved profile on an approved target = D7 role + reason, no second approver? | PO decision |
| D-3 | Two freezes (platform C1–C5; backup/artefact/restore C7)? | Yes |
| D-7 | Restore execution in Release 1 for validated platforms only, or readiness + guided plan only with execution in a later release? | PO decision; the plan carries execution in REL-BACKUP until decided otherwise |
| D-4 | Import Line-1 evidence history into UI 2.0 (one-time, sanitized), or start the history at UI 2.0 go-live? | Start fresh; revisit after REL-INVENTORY |
| D-5 | In-flight Line-1 movements: 0048 OP.1.S1 finish and merge (contract validation, keeps value); 0050 RB.5 — projection shape has value, the HTML module does not: finish and merge if it closes within the current budget, otherwise stop and harvest the projection in REL-BACKUP extraction; 0049 tablestat — hold until the PO can run `cphaprob tablestat` on hardware | As stated |
| D-6 | Group-reference persistence + directory service account (corporate posture) | PO decision |

---

## 9. Cross-references

- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (PR #160) — design under amendment; §3.2 ladder, §8.5, `AG-U2` superseded as listed in §2.
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` — council + Astra record; §8 there records this directive.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7 — gate records reused as capability spec field 3.
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` — fixture sanitization path.
- `docs/design/PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md` — P0 fixed in the Java transport adapter (REL-INVENTORY slice).
- `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md` — frozen contract implemented by REL-FAILOVER-READINESS.
- `utils/action_taxonomy.py`, `utils/capability_state_resolver.py` — semantics ported as rules (P-4).
- `AGENTS.md` network-device command gate; `docs/AI_DEVELOPMENT_PROTOCOL.md` approval boundaries — unchanged and binding on the Java engine.

---

## 10. Revision 2 — Astra's final review (2026-09-09) and its disposition

Astra's verdict: supports the target architecture, would use the plan as
baseline after corrections; the temporary Python worker proposal is
withdrawn by Astra. Disposition of each point, as applied above:

| Astra item | Disposition | Where |
|---|---|---|
| R-01 decision identity collision (OUTCOME_UNKNOWN vs Run Now); C6 ambiguity | Accepted: `JOB-UNCERTAIN-OUTCOME`, `UI-OPERATIONAL-RUN-NOW`, `APPROVAL-MODEL`, `RAW-RETENTION`, `RUNTIME-DIRECTION` as separate ids; C7 for backup/artefact/restore | Phase 0, B0, §8 |
| R-02 "full scope" overclaims; restore, SNMP status, alarm lifecycle, session recording | Accepted: Release 1 definition; restore execution in C7/REL-BACKUP (D-7 open); SNMP status exposure B2-5b; alarm lifecycle contract; OP.2 and session recording named later releases | §5, §6 |
| R-03 B0 lost the product baseline; serial gates | Accepted: baseline directory movement; dependency-based gating | B0 |
| R-04 UNPROVEN vs "unknown invalid" | Accepted: KNOWN/UNKNOWN/NOT_APPLICABLE; offline vs executable separated | §3.1 |
| R-05 fixture policy; tokenizer structure; P-2/P-3 boundary | Accepted: SYNTHETIC/DERIVED; preserved-property list; boundary text | §1, §3.1 |
| R-06 raw-fragment link vs discard_raw | Accepted: provenance record; RAW-RETENTION decision | §3.5 |
| R-07 PO validation must exercise Java end to end; minimal device model | Accepted: step c redefined; B1 step 4b | §3.3, B1 |
| R-08 contact budget not gone | Accepted: coordination rule | §3.4 |
| 4.1 collector ≠ feature; validation by kind | Accepted | §3.3 |
| 4.2 maturity vs slice naming | Accepted: CAP-* and REL-* | §2, §5 |
| 4.3 semantic authority not permanently in Python | Accepted with one clarification: the frozen contracts live in `docs/design`, not in Python; what moves is where *new* knowledge is written | P-4 |
| 4.4 PAN P0 separate for Line-1 | Accepted | §7 |
| 4.5 approval model for routine operation | Accepted as a PO decision with a proposal | Phase 0 item 4, D-2c |
| 4.6 estimates, first capability choice, re-estimation | Accepted: narrow validated subset first; second representative flow; re-estimate after B1 6/13 | B1, §6 |

Two nuances recorded by the orchestrator, not disagreements: (1) restore
execution is a device write of the highest consequence class, so its
inclusion in Release 1 is a PO decision (D-7), not an oversight; (2) the
first capability is chosen narrow *and* validated; the channel-drain quirk
stays in the spec as `UNKNOWN` rather than being avoided.
