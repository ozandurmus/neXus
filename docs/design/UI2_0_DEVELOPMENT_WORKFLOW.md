# UI 2.0 — development workflow (revised: Line-1 collectors are reference, not runtime)

## Status

**PLAN — Product Owner directive recorded, not yet a frozen contract.**
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
amendment.

**P-5 — Line-1 stops growing product features.** From this directive on,
Line-1 movements are limited to: contracts, vendor-semantic findings,
real-environment captures (fixture sources), gate-record work, and fixes
needed to keep the static product usable. New user-facing Python features
are not dispatched (this matches the earlier "no new tasks" instruction).

---

## 2. What is withdrawn from the earlier plan and design

| Item | Where it lived | Disposition |
|---|---|---|
| `F2` "Python writes projections, Java reads" and `F3` "Python executes, Java submits" as *rest states* | `UI2_0_ARCHITECTURE_DESIGN.md` §3.2 ladder | **Withdrawn.** The ladder collapses to: `F0` not in UI 2.0 → `F1` specified (capability spec + fixtures exist) → `F2` implemented in Java against fixtures → `F3` real-environment validated by the PO → `F4` shipped in UI 2.0. No state has Python on the execution path. |
| `main.py --worker` (PostgreSQL job-queue consumer in Python) and the `workflow_argv` single-orchestration-path rule (`AG-U2`) | Design §3.2 `F3`, §15; brief §7 step 4(a); earlier workflow B1 step 11 | **Withdrawn.** The worker is Java (§4, B1). `AG-U2` is re-stated as "one job-execution path, in Java, test-enforced". |
| `F4` "shadow run: Java executor replaces the Python run on pilot targets, MATCH on fingerprints" and Astra's offline replay comparison | Design §3.2/§8.5; brief §6.6 | **Replaced.** Parity is proven against *fixtures extracted from real captures* (§3.1) plus a PO-run real-environment validation. There is no dual run, so the contact-budget concern disappears. Astra's replay idea survives only as "fixtures are real sanitized captures, not hand-written samples". |
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

Capability spec fields (all mandatory; "none" is a valid value, "unknown" is
not):

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
   gate record cannot be extracted as executable — it is recorded as
   "requires gate entry" and blocks `F2`).
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
   move the Java capability to `F3`.

Fixture set rules:

- Every fixture is a **sanitized real capture** produced through the
  Private Replay tokenizer path (`docs/design/PRIVATE_REPLAY_ARCHITECTURE.md`,
  Phase A slice 1 merged 2026-09-09): domain-separated HMAC pseudonyms for
  every identity, synthetic display labels, no raw hostnames/serials/IPs.
  Hand-written samples are allowed only for error paths that have never
  been captured, and are marked `SYNTHETIC`.
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
| b | **Implement** | `BUILD` in Java | Sonnet 5, normal | capability + parser + tests against fixtures, `F2` |
| c | **Validate** | PO-run real-environment session | human | `F3` record with fingerprint; spec amended with anything new |
| d | **Integrate** | `BUILD` (UI + API + projections) | Sonnet 5, normal | screen(s), `F4` |

Steps a and b can be one movement for small capabilities; c is always the
PO's own session (device-contacting commands are proposed by the agent and
executed by the human, standing rule).

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
| `utils/failover_plan/` (OP.1.S1, movement 0048, in progress) | Failover plan compiler + dry run | read (compile only) | contract FROZEN (Option A) | the frozen contract is the spec; Java implements it in F5 |
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

Agenda, in order; each item changes text in the design or the brief:

1. **Karar 1 (restated)** — Line-1 collectors are reference only; no Python
   in the UI 2.0 runtime (P-1…P-5). *Proposed: accept as stated.*
2. **Karar 2 — "Back up now" / Run Now** as a taxonomy amendment: a D7-gated,
   audited, mandatory-reason, four-eyes "run once" for operational-write
   profiles on an HTTP surface. *Recorded as B (accept the amendment)
   pending the PO's explicit confirmation.*
3. Freeze slicing: platform contract (C1–C5) and backup profile engine (C6
   + profile rules) as two freezes. *Proposed: two.*
4. Persisted encrypted directory group references + read-only directory
   service account (design §5.2/§7.5). *Corporate posture question.*
5. Same repository, `ui2/` sub-tree, second toolchain in CI. *Proposed: yes.*
6. Oracle: PostgreSQL frozen (X-2); jOOQ Oracle licence check deferred until
   a corporate mandate appears. *Proposed: confirm deferral.*
7. M14/M14L local LDAP console: design kept, not implemented before UI 2.0
   §7. *Proposed: park.*
8. Disposition of in-flight Line-1 movements 0048 (OP.1.S1), 0049
   (tablestat), 0050 (RB.5) under P-5 (§8, D-5).

### Phase B0 — Contracts (7 movements, `S-h`)

| # | Movement | Scope |
|---|---|---|
| 1 | **C1 Platform & schema contract** | UI 2.0 schema ownership, Flyway as sole migration authority, projection tables, audit table from the first mutation (new finding id, correcting the brief's §11.5 misattribution to SR-D8/DO-D8), per-component secrets (K-8), key custody (K-7) |
| 2 | **C2 Job execution contract** | job record, leasing/heartbeat, timeout, worker loss, duplicate detection, `OUTCOME_UNKNOWN`, owner/approver/execution identity for scheduled jobs, schedule optimistic concurrency. **No worker mode in Python.** |
| 3 | **C3 Identity, sessions, RBAC contract** | design §5/§7 as amended; single active session table; role tokens → bindings → AD group refs; gate chain E1–E7 on HTTP |
| 4 | **C4 Capability registry & command-gate resolution contract** | runtime form of §3.1; gate-registry resolution rule (K-4); closed step kinds; VSX/ClusterXL rules (CP-D6); transport decision (CP-D7) |
| 5 | **C5 Amendments bundle** | CON.0 amendment (separate shell, approved), taxonomy amendment for Run Now (if Karar 2 = B), AGENTS.md amendment for the Phase-2 browser profile editor (UA-5) and, later, the mediated terminal |
| 6 | **C6 Capability extraction contract** | §3.1 verbatim as a frozen template, plus the extraction inventory (§4) as the ordered queue |
| 7 | **Extraction tooling** (`S-n`, Line-1) | a Line-1 script that runs the Private Replay tokenizer over an existing evidence/capture directory and emits a fixture set in the C6 layout; DLP-gated; this is the one Python deliverable that serves UI 2.0, at extraction time only |

### Phase B1 — Skeleton and first real job (12 movements, `S-n` unless noted)

| # | Movement | Scope |
|---|---|---|
| 1 | Gradle multi-module skeleton, CI job, Docker image, Testcontainers PostgreSQL | `ui2/` sub-tree; P-1 dependency-graph test present from day one |
| 2 | Schema V1 via Flyway | tables from C1; audit table; projections for the first capability |
| 3 | Identity & sessions | LDAP bind (UnboundID), single-active-session rule (design §7.4 table as tests), role bindings, E1–E7 chain on the HTTP layer (`S-h`) |
| 4 | **Collection engine core** (`S-h`) | transport adapters (SSH exec, PAN XML API with header auth, SFTP), capability registry, step executor with the C2 crash-safe job record, parser framework, evidence writer |
| 5 | **Extract: CP inventory** (pattern step a, Line-1 read-only) | spec + fixtures from `cp_inventory.sh`/`cp_runner.py`; channel-drain quirk recorded |
| 6 | **Implement: CP inventory capability** (step b) | first Java capability end to end against fixtures; `F2` |
| — | **PO validation session** (step c) | PO runs the proposed read commands on the test CP; `F3` |
| 7 | Jobs screen + Run Now (read class) + step log | submit a read job from the browser, watch steps, see outcome; Run Now for read-class only until Karar 2 lands |
| 8 | Audit & logs screen | every mutation since B1 step 2 visible; export-free |
| 9 | Device workspace (first read screen) | device list + inventory projection from step 6; RBAC visible-but-refused behaviour proven |
| 10 | Acceptance scenario A | three admins on one device concurrently, single-session takeover/refuse, correct state throughout (Astra's scenario, first half) |
| 11 | Acceptance scenario B | worker killed mid-step; job lands in `OUTCOME_UNKNOWN`; no duplicate device contact; recovery path visible (second half) |
| 12 | Deployment slice | Docker compose for the DEPLOY.1 server shape; secrets per component; DEPLOY.1/1A gates pass |

Exit of B1: an operator can log in, pick a device, run a read collection,
watch it, see the evidence, and see the audit trail — all in Java, no
Line-1 process anywhere.

### Phase B2 — Platform breadth (6 movements)

| # | Movement | Scope |
|---|---|---|
| 1 | Capability registry UI | list capabilities, gate coverage, `D1`–`D7` state per device |
| 2 | Commands editor (compose-only) | Backbox-style profile authoring from closed step kinds; free-command stays separate and unimplemented until the AGENTS.md amendment (`S-h`) |
| 3 | Alert pipeline | thresholds/derivations over projections; alert table; acknowledgement audit |
| 4 | SMTP + HTTP-out notifications | outbound only; secrets per component |
| 5 | Syslog + SNMP-trap-out | outbound only; SNMP polling stays bound to the Line-1 command gate rules (no new device contact class) |
| 6 | HTTP-in signal intake | port of the `event_signal_intake` interface contract |

### Phase F — Feature slices (each 4 movements: extract, implement, PO validation, integrate)

| Slice | Capabilities | Movements | Notes |
|---|---|---|---|
| **F1 Devices & Discovery** | registry, enrollment, identity relationships, pre-enrollment probe, first contact | 4 | daily-usable inventory of the estate |
| **F2 Inventory** | VSX contexts, CP configuration snapshot, Panorama runtime/config (PAN transport fix included) | 5 | PAN P0 closed here for UI 2.0 |
| **F3 Backup & Recovery** | RB.2 PAN export profile, RB.3b CP Gaia profile (transport per CP-D7), store/manifest/validation/retention/key custody, recovery readiness projection | 6 | first operational-write class; Run Now per Karar 2; four-eyes approval live |
| **F4 Checks** | CE.2 engine primitives and rule packs, posture/trend projections | 4 | inherited status `UNPROVEN`; PO validation mandatory before `F4` |
| **F5 Failover (read-only)** | OP.0 readiness battery (CP + PAN), tablestat item if 0049 proves it, OP.1 plan compiler + dry run per the frozen contract | 5 | OP.2 controlled execution stays out of scope (roadmap OP.2.C prerequisites) |

### Phase S — Later, gated on amendments

| # | Movement | Gate |
|---|---|---|
| 1 | Browser profile editor (free-command) | AGENTS.md amendment (UA-5) accepted |
| 2 | Mediated terminal | AGENTS.md amendment; recorded session; class-1 command refusal in the mediator |
| 3 | Line-1 evidence history import | D-4 below |

---

## 6. Totals and duration

| Phase | Movements | Calendar (2 concurrent movements + PO sessions) |
|---|---|---|
| 0 DECIDE | 0 (1 PO episode) | 1 day |
| B0 | 7 | 1–2 weeks |
| B1 | 12 (+1 PO validation) | ~6 weeks |
| B2 | 6 | ~3 weeks |
| F1–F5 | 24 (+5 PO validations) | ~15–18 weeks (3–4 weeks per slice) |
| S | 3 | after amendments |
| **Total** | **~52 movements** | **~5 months to daily use (end of F1/F2), ~7 months to full scope (end of F5)** |

Compared with the earlier plan (~45 movements, 4–6 months): about seven
more movements and one more month, in exchange for removing the Python
worker, the migration ladder, the shadow-run comparison, the dual-runtime
deployment and the "two products forever" risk. B1 grows because the
collection engine is now real work; F-slices grow by one movement each for
extraction; B0 shrinks because C2 no longer describes a Python worker.

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
  UI 2.0 slice reaches `F4`; no new pages, no new collectors.
- Its test suite stays green on `main`; the one known red
  (`tests/test_nexus_engineer_tool_gate.py::test_ac1_live_bug_regression_against_real_repository_state`,
  worktree-only) is tracked separately.

---

## 8. Open confirmations for the Product Owner

| id | Question | Proposed |
|---|---|---|
| D-1 | Accept P-1…P-5 as the wording of Karar 1? | Yes |
| D-2 | Karar 2 = B (Run Now as a taxonomy amendment, D7-gated, four-eyes, mandatory reason)? | Awaiting explicit "tamam" |
| D-3 | Two freezes (platform; backup engine)? | Yes |
| D-4 | Import Line-1 evidence history into UI 2.0 (one-time, sanitized), or start the history at UI 2.0 go-live? | Start fresh; revisit after F2 |
| D-5 | In-flight Line-1 movements: 0048 OP.1.S1 finish and merge (contract validation, keeps value); 0050 RB.5 — projection shape has value, the HTML module does not: finish and merge if it closes within the current budget, otherwise stop and harvest the projection in F3 extraction; 0049 tablestat — hold until the PO can run `cphaprob tablestat` on hardware | As stated |
| D-6 | Group-reference persistence + directory service account (corporate posture) | PO decision |

---

## 9. Cross-references

- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (PR #160) — design under amendment; §3.2 ladder, §8.5, `AG-U2` superseded as listed in §2.
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` — council + Astra record; §8 there records this directive.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7 — gate records reused as capability spec field 3.
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` — fixture sanitization path.
- `docs/design/PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md` — P0 fixed in the Java transport adapter (F2 slice).
- `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md` — frozen contract implemented by F5.
- `utils/action_taxonomy.py`, `utils/capability_state_resolver.py` — semantics ported as rules (P-4).
- `AGENTS.md` network-device command gate; `docs/AI_DEVELOPMENT_PROTOCOL.md` approval boundaries — unchanged and binding on the Java engine.
