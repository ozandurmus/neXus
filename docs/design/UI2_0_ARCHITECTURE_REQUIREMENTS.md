# UI 2.0 — DB-backed, LDAP-authenticated, role-scoped live operator console: architecture requirements

## Status

**DRAFT — requirements and tradeoffs only. NOT a scope decision. NOT
implementation authority. DO NOT FREEZE without the Product Owner
decisions named in §9.** Produced 2026-09-08/09 by movement
`UI2_0_ARCHITECTURE_REQUIREMENTS_DRAFT` (relay/NXS-LOCAL-0025) under
explicit Product Owner direction that this round is **requirements
gathering**, and that the eventual scope choice — full rewrite versus an
incremental database + typed live-state API layer over the existing
`D1`–`D7` / resolver / registry foundation — is **deferred to a later
round**. Every recommendation in §6 is a recommendation with stated
tradeoffs; none of them is decided by this document, and freezing this
document would still not authorize dispatch of any implementation movement
(§8).

Mirrors the `DRAFT` framing of `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md`
(before its Phase A freeze), `OP.1`'s contract draft, and
`docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`. It extends — and
does not reopen or contradict — the open `pcp_storage_engine` decision
recorded in `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §10.

No source, test, `FROZEN` document, or runtime artifact was changed by the
session that produced it. No live database, LDAP/AD, or device call was made.

---

## 0. How to read this document

- §1 states the Product Owner's functional target, verbatim-referenced.
- §2 is the **evidentiary basis**: a code-grounded audit of which parts of
  today's console path are static-export-shaped and which already produce
  structured, DB-ready data. Everything after it builds on §2, not on
  assumption.
- §5 turns each functional point into a concrete requirement with three
  answers: what exists today (real file / function), what is genuinely new,
  and what it depends on.
- §6 resolves the four cross-cutting sub-decisions as **recommendations**.
- §7 sketches one possible incremental path. It is a sketch, not the plan.
- §8 and §9 say what is not decided, and which contradictions with frozen
  authority the Product Owner must resolve before any freeze.

Vocabulary: `D1`…`D7` are the seven capability-state dimensions of
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` (FROZEN).
"Line-1" is the current `M`-series movement line under
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12. "UI 2.0" is
the proposed second track described here.

---

## 1. Objective and the Product Owner's functional target

**Objective (from the movement's `SESSION_START`):** a proposed "UI 2.0"
deployment track — an empty-shell, DB-backed, LDAP-authenticated, role-scoped
live operator console, fed incrementally by the existing collection modules,
to replace today's static-export-oriented `console/` + `utils/html_export`
path *for production operational use*.

**Functional target, verbatim-referenced.** The Product Owner's target was
transmitted as acceptance criterion `AC-2` of relay/NXS-LOCAL-0025, grouped
there as points (1)–(10) and described as an "11-point" target. This document
enumerates it as `F-1`…`F-11`, splitting the packet's point (8) into its two
distinct asks (backup jobs; backup drift). Each row cites the packet's own
wording.

| id | Packet point | Product Owner wording (verbatim from `AC-2`) |
| --- | --- | --- |
| `F-1` | (1) | "empty-shell UI" |
| `F-2` | (2), first part | "DB-backed state" |
| `F-3` | (2), second part | "LDAP auth + role-based menu authorization (link to M14's D7 design, do not re-derive LDAP/AD decisions -- reference them)" |
| `F-4` | (3) | "device onboarding (discovery + manual, no static HTML generation, no unnecessary data pull)" |
| `F-5` | (4) | "auto config/inventory population post-onboarding eligible for compliance checking" |
| `F-6` | (5) | "configuration-only fetch scripts that write straight to DB with no formatting side-output" |
| `F-7` | (6) | "inventory-only fetch with no other data pulled" |
| `F-8` | (7) | "compliance: operator selects benchmark (e.g. CIS) then applies to selected devices" |
| `F-9` | (8), first part | "backup: add/select-device/schedule" |
| `F-10` | (8), second part | "plus drift comparison against prior backups" |
| `F-11` | (9), (10) | "(9) failover menu auto-populated from live active/passive state at configurable intervals, (10) a live/dynamic project-plan view, optionally GitHub-sourced" — carried as `F-11a` (failover) and `F-11b` (project plan) so the count stays at the packet's eleven |

**Standing constraint, verbatim (`baseline.sequencing`):** "Product Owner
explicitly wants the current M-series (Line 1) to keep shipping in parallel;
this document must describe an incremental evolution path where maturing
Line-1 features (D1-D7 producers, registry, resolver) become UI2.0's live
data sources one at a time, not a stop-the-world rewrite."

**Standing corporate constraint (`AC-4(d)`):** "a stated corporate-
infrastructure requirement for Java." Its exact content is not in the
repository and is recorded as unknown `U-1` (§10).

---

## 2. Source evidence inspected — what is static-export-shaped, what is DB-ready

Read-only inspection of this worktree at `origin/main` `60fa401`
(2026-09-09). File and function names are real; where a line number is
given it was checked in this session.

### 2.1 The console request path, route by route

`console/app.py::create_app` enumerates the whole route table in its module
docstring. Classified here by what each route actually reads:

| Route | Reads | Shape |
| --- | --- | --- |
| `GET /`, `/assets/app.js`, `/assets/style.css`, `/assets/console_actions.js` | `templates/console.html`; `utils.html_export.compose_modules` concatenating the same `static/*.js` modules the exported report inlines | **static-export-shaped** — the console shell *is* the report's script bundle served as an asset (`OPERATOR_CONSOLE_ARCHITECTURE.md` §6, "one UI source tree") |
| `GET /api/payloads` | `console/payloads.py::build_console_payloads` → `utils/html_export.py::build_report_payloads` | **static-export-shaped** — by frozen contract `C1-4` it "may not reshape, filter, enrich or reorder"; it returns the exact eight payload dicts (`rawData`, `configUiData`, `complianceUiData`, `cryptoUiData`, `projectPlanData`, `discoveryUiData`, `exclusionsUiData`, `failoverReadinessData`) the HTML report embeds, built from three files on `output_root`: `unified.json`, `pan_config_telemetry.json`, `cp_config_telemetry.json` |
| `POST /api/jobs` (`target_mode == "entity_ids"`) | `console/app.py::_known_entity_ids` reads `output_root/unified.json` | **static-export-shaped** — the target universe for `entity_ids` jobs is the *render input file*, not a store |
| `POST /api/jobs` (`target_mode == "device_ids"`) | `console/registry_targets.py::resolve_registry_targets` against `utils/device_registry.py::DeviceRegistry` | **DB-ready** — resolved against a persistent product object, re-checked immediately before execution (`console/runner.py::_execute`, `M6`) |
| `GET /api/job-types` | `console/registry.py::JOB_REGISTRY` + `utils/action_taxonomy.py::console_refusal` | **DB-ready** — a closed typed vocabulary with class-derived refusal, no file read |
| `GET /api/jobs`, `/api/jobs/{id}`, `/api/jobs/{id}/events` | `console/jobs.py::ConsoleJobStore` over `utils/evidence_backend.py::ConsoleJobBackend` | **DB-ready** — `JobRecord.to_dict` is identity-lean by construction; a `PostgresConsoleJobBackend` already exists |
| `POST /api/enrollment/probe`, `GET /api/registry/devices`, `POST /api/registry/enrollments` | `DeviceRegistry`, `utils/enrollment_audit.py::EnrollmentAuditStore`, the probe job's durable `preview` | **DB-ready** — typed intent, audit-before-mutation, opaque `device_id`; the probe never triggers a render |

### 2.2 The execution path — where the static export is actually coupled

`console/runner.py::ConsoleJobRunner._execute` builds argv through
`utils/collection_executor.py::workflow_argv` and re-enters `main.main()`;
the scheduler (`application/workflows/maintenance.py::_scheduler_workflow_argv`)
uses the same function. `workflow_argv` maps a workflow name to one of
exactly three argv families: `--only <plane>` (`cp`, `vsx`, `pan-config`),
`--cp-config-collect --cp-config-stage all [--cp-config-targets …]`, and
`--recovery-collect --recovery-vendor … [--recovery-gateways …]`. Only
`cp-config`, `recovery-pan`, `recovery-cp` accept a target set
(`_TARGET_SEAM_WORKFLOWS`); every other workflow is plane-wide and a targeted
request is refused before `main()` runs (`UnsupportedTargetSelectionError`).

**The coupling to the static export is not in the collectors and not in the
console. It is the tail of every collection workflow in
`application/workflows/`:**

| Workflow | Collects / derives | Then unconditionally |
| --- | --- | --- |
| `--cp-config-collect` (`application/workflows/checkpoint.py::cp_config_collect`) | CP configuration → CAS snapshot via `utils/config_evidence.py::ConfigEvidenceStore` + `output/cp_config_telemetry.json` (`configuration/checkpoint_config_collector.py:2211`) | `run_html_export(...)` (`checkpoint.py:195`) |
| `--only cp` / `--only vsx` (`checkpoint.py::integration_checkpoint`, partial mode) | inventory JSON → `utils/merge.py::run_merge` → `output/unified.json` | `run_html_export(...)` (`checkpoint.py:312`) |
| `--only pan-config` | PAN configuration → CAS + `output/pan_config_telemetry.json` (`configuration/panorama_config_collector.py:711`) | `run_html_export(...)` (`checkpoint.py:536`) |
| full checkpoint | all of the above + snapshot + verify | `run_html_export(...)` + `run_support_bundle` (`checkpoint.py:703`/`724`) |
| `--render-only` | nothing | `run_html_export(...)` (`maintenance.py:297`) — already exposed to the console as the `report_rebuild` job type |
| HA preflight | preflight snapshot | `run_html_export(...)` (`application/workflows/preflight.py:183`) |

So today a "configuration-only fetch" (`F-6`) *is* a config collection
**plus** a full HTML render, and an "inventory-only fetch" (`F-7`) *is* an
inventory collection **plus** merge **plus** a full HTML render. The render
is a side effect of the workflow layer, invoked by name at six call sites,
not a property of any collector. `utils/html_export.py::build_report_payloads`
itself is documented as "pure computation, no write side effect"; only
`run_html_export` writes `index.html` and appends the compliance trend
ledger on a full checkpoint.

### 2.3 What is already structured and DB-ready

| Concern | Module / function | Persistence today | Notes |
| --- | --- | --- | --- |
| Device Registry (`PCP.1`) | `utils/device_registry.py::DeviceRegistry.{enroll,list,disable}`, `DeviceRecord.to_dict` | filesystem JSON behind `utils/evidence_backend.py::DeviceRegistryBackend` (the "eighth concern"), mutation lock file | business rules live in the module, not the backend, "so both a future Postgres backend and today's filesystem one would behave identically" (module docstring). Migration through the seam is a governed storage movement (`PCP` §10) |
| Local control-plane metadata (`M4`) | `utils/control_plane_store.py::ControlPlaneStore` | **SQLite**, `<data_root>/state/`, versioned migrations, tables `job_definitions`, `job_submissions`, `job_runs`, `schedules`, `capability_projections`, `device_identity_relationships` | "SQLite is not the production engine" (`AC-ST-7`); must never own registry rows, credentials, raw config, backup bytes, CAS objects, `OP.2` authority, or "resolved presentation state as durable truth" (`AC-ST-3`) |
| Evidence store concerns (`DEV.3.3`) | `utils/evidence_backend.py` | filesystem default, **opt-in PostgreSQL, byte-compatible**, for: config-snapshot metadata index, run manifests, last-known-good, scheduler state, operational-write ledger, console job records, `OP.2` action records | CAS payload blobs (`data/artifacts/config/sha256/**`) are explicitly out of scope and "never touched here"; the recovery store likewise never moves |
| Admission coordinator (`DEV.3.2`) | `utils/collection_executor.py::CollectionCoordinator`, `select_coordinator_backend` | in-process default, **opt-in PostgreSQL** (`utils/coordinator_backend.py::PostgresCoordinatorBackend`) | the per-endpoint lock / vendor budget / coalescing state is already multi-process-capable |
| `D4` reconciliation (`M10.1`) | `utils/registry_evidence_reconciliation.py::resolve_d4`, `detect_ri2` | none — pure projection | no canonical id spans `device_id` and evidence `entity_id` yet (`RELAY_DECISION` #11) |
| Capability-state resolver (`M10.2`) | `utils/capability_state_resolver.py::resolve_union_tag`, `evaluate_stage1`, `resolve_primary_status`, `resolve_qualifiers`, `to_wire` | none — "pure: no I/O, no device contact"; every dimension is a typed input | stages 4–5 not modeled; `D2`/`D3`/`D5` producers do not exist yet (`M10.3`/`M12`); nothing wired into `html_export` or the console |
| First-contact identity producer (`M8.3`) | `utils/first_contact_producer.py::run_first_contact_producer` | evidence into the CAS store; registry read-only | trust-before-credential sequence; single target |
| Pre-enrollment identity probe (`M9`) | `utils/pre_enrollment_identity_probe.py::run_pre_enrollment_identity_probe` via `execute_admitted_collection` | job record + `preview` | the one job type that bypasses `main.main()` by explicit `RELAY_DECISION` |
| HA readiness (`OP.0a/0c`) | `utils/failover/assessment.py::derive_ha_units`, `compute_ha_readiness`; `utils/failover_readiness_ui.py::extract_cp_ha_runtime`, `extract_pan_ha_runtime`, `build_failover_readiness_payload`; `application/workflows/failover.py` writes `data/state/ha_readiness.json` | file | offline derivation over already-collected telemetry; cannot emit `SAFE_TO_FAILOVER` |
| Configuration history / diff (`0.6.3`) | `utils/config_history.py::build_history_payload`, `_compute_pan_pair` | reads CAS metadata | PAN structured safe diff exists; CP diff is `INSUFFICIENT_EVIDENCE` by design (no raw Gaia text diff) |
| Recovery / backup plane (`RB.x`) | `utils/recovery_collect.py::run_recovery_collection`, `utils/recovery_manifest.py`, `utils/recovery_store.py`, `utils/recovery_operational_ledger.py::OperationalWriteLedger` | manifests + encrypted artifacts on a separate root; ledger has a Postgres backend | class 1; never console-submittable (`console_refusal`), never reachable over HTTP as bytes (`CON.0` §7.6) |
| Discovery lifecycle + capability profiles (`0.6.1C`) | `utils/discovery_lifecycle.py::LifecycleStore`, `utils/capability_registry.py::CapabilityStore` | in-memory per run, serialized only into `discoveryUiData` | `PCP` §17 names lifecycle `DISCOVERED` records as "the natural candidate set" for onboarding |
| Compliance engine (`0.6.6B`, `CE.1`) | `utils/compliance_posture.py::build_compliance_posture`, `utils/framework_catalog.py::{framework_entry,requirements_for}` (`CIS`, `PCI-DSS`, `BDDK`), `utils/compliance_check_pack.py::load_compliance_checks`, `utils/control_assignment.py` | file policies in `data/state/` (`control_assignments.json`, `compliance_checks.json`); trend ledger in `data_root` | **its primary input is `configuration_ui`, the presentation projection** built by `build_configuration_ui_payload` — the evaluator is keyed on a UI payload, not on a persisted evidence projection (see `F-8`) |
| Project plan | `utils/project_plan.py::build_project_plan_payload` | reads `project/*.json` from the repository checkout | engineering state, not product data; no network |

### 2.4 Summary of the mismatch, as measured

- **Static-export-shaped, exhaustively:** (a) the six `run_html_export`
  call sites at the tail of the workflow layer (§2.2); (b) the eight
  `*_ui.py` payload builders as the console's *only* read model, frozen to
  byte parity with the report (`C1-4`, `CON.0` §6); (c) `output_root` JSON
  files (`unified.json`, the two telemetry JSONs) as the inter-stage medium
  and the `entity_ids` target universe; (d) the console shell being the
  report's own script bundle; (e) the compliance evaluator consuming the
  configuration *UI projection* as input.
- **DB-ready-shaped, already:** the registry, the `M4` control-plane store,
  seven evidence concerns with Postgres implementations, a
  multi-process-capable admission coordinator, the identity-lean job record,
  the enrollment audit trail, and the entire `D4`/`M10.2` resolver chain as
  pure typed functions. The `M8.3`/`M9` producers already write evidence and
  job state without rendering anything.

The mismatch is therefore **narrow and located**: it lives in the workflow
tail and the read model, not in the collectors, the registry, the job
engine, or the capability-state foundation. §6.1 builds on exactly this.

---

## 3. Scope

### In

- A requirements-and-tradeoffs document for the UI 2.0 track (`F-1`…`F-11`,
  §5) and its resolved sub-decisions as recommendations (§6).
- The code-grounded audit above (§2).
- One sketch of an incremental migration path (§7), explicitly not the plan.
- A named list of what is undecided (§8), contradictions with frozen
  authority to be resolved by the Product Owner (§9), and unknowns (§10).

### Out

- Any code, test, schema, migration, dependency addition, or live DB / LDAP
  / device call.
- A binding scope decision (rewrite vs incremental), a final storage engine,
  a final language / runtime split, or a final LDAP persistence model —
  each is a recommendation or a deferral here.
- `M14`'s own LDAP/AD sub-decisions (`LD-1`…`LD-7`): referenced, never
  re-derived.
- The `DEPLOY.1A` server OIDC/RBAC track: referenced for contrast only, not
  extended.
- Any relaxation of `utils/action_taxonomy.py`: classes 2–4 stay
  prohibited; class 1 stays under `RB.x`.

---

## 4. Where this sits in the existing authority

| Authority | Status | What it fixes for UI 2.0 |
| --- | --- | --- |
| `AGENTS.md` | constitution | evidence / identity / raw-evidence laws; "no second orchestration path"; approval boundaries |
| `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` | FROZEN (`PCP.0`), §10 amended 2026-09-05 | §9 job plane, §10 persistence seam + the **open** `pcp_storage_engine` criteria this document builds on, §13 "the control plane's console **is** the `CON.x` console", §17 no-rewrite table |
| `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`) | ARCHITECTURE FROZEN | §3 hard boundaries ("not a dynamic `index.html`", "not a generic REST wrapper", "no frontend framework / bundler"), §4 intent boundary, §6 payload parity, §7 security model |
| `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` | FROZEN | §6.4 store ownership, §9.1 enrollment conditions, §11.3 "local shortcuts are not production posture", §12 `M`-series |
| `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` | FROZEN 2026-09-06 | `D1`…`D7`, result algebra, §5.3 authorization is "independent, action-scoped, server-enforced", `AC-CS-33` (varying `D7` changes no rendered structure), §6 presentation contract, §6.8 static-report parity |
| `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` | FROZEN | the rail / entity workspace / `#<module>[/<entityId>]` route that `static/navigation_ui.js` implements |
| `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md` | DRAFT | `A1` (actor binding) vs `A2` (`D7` producer); `LD-1`…`LD-7`; §7 the `M14`/`M14L` naming contradiction; §11 what a database would additionally enable |
| `docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md`, `DEV.3.2`, `DEV.4.6` | implemented / precondition | the existing opt-in Postgres backends; "runtime schema creation is pre-production only" |

UI 2.0 inherits every row. Where a functional point cannot be met without
amending a frozen row, §9 says so and stops; this document never resolves
such a conflict itself.

---

## 5. Functional requirements `F-1`…`F-11`

Each entry: **(a)** what exists today that can feed it, **(b)** what is
genuinely new, **(c)** dependency on the DB-engine decision (`X-2`), the
`M14` LDAP design, or neither.

### `F-1` — Empty-shell UI

**Requirement.** A new, initially empty application shell whose navigation
and entity-workspace structure are driven entirely by server-resolved state,
with no evidence, payload, or action inlined at build time.

- **(a) Exists.** The shell *contract* — not the shell: `NAVIGATION_
  INFORMATION_ARCHITECTURE.md` (rail, entity workspace, `#<module>[/<entityId>]`
  route) and the presentation contract in `CAPABILITY_STATE…` §6. Its current
  implementation, `static/navigation_ui.js::navigationResolveSurface`, is
  "the sole, explicit render/not-render gate" mirroring the resolver's stage
  0 (`resolve_union_tag`) — that mirror rule is the reusable design fact:
  **a surface renders iff `D1` says the build ships it**. `templates/
  console.html` + `compose_modules` is the current, report-derived shell.
- **(b) New.** The shell itself; a typed read API replacing `/api/payloads`'
  monolithic eight-payload dict with per-module, per-entity, freshness-
  carrying resources; the decision whether the new shell is built with a
  framework / bundler at all — `CON.0` §3 forbids one *for the shared UI
  source tree* (`D-MOD1`); a separate shell is either bound by that row or
  needs it explicitly amended (§9, `C-1`).
- **(c) Dependency.** Independent of `X-2` and of `M14`. Depends on the
  read-API design (`F-2`).

### `F-2` — DB-backed state

**Requirement.** Every product object and live projection UI 2.0 renders is
read from a database, never from `output_root` render artifacts; evidence
blobs stay on their volume and are referenced, not copied.

- **(a) Exists.** Seven Postgres-capable concerns in `utils/evidence_backend.py`
  (§2.3); the Postgres admission coordinator; the `M4` SQLite store's schema
  (`job_definitions`, `job_submissions`, `job_runs`, `schedules`,
  `capability_projections`, `device_identity_relationships`) as the
  local-engine shape of the control-plane tables; `DeviceRegistry` behind its
  backend seam; `snapshot.py`'s last-known-good with a Postgres backend as
  the natural "current inventory projection" store.
- **(b) New.** (i) A **single, deployment-controlled migration lineage**
  across concerns that today each `_ensure_schema` at runtime — `DEV.4.6`
  is the named precondition and "runtime schema creation is pre-production
  only" (`PCP` §10). (ii) A **read-model / projection layer**: today's
  `*_ui.py` builders compute projections at render time from files; UI 2.0
  needs those projections either persisted per collection run (write-side)
  or computed on request from persisted evidence (read-side). `AC-ST-3`
  forbids *resolved presentation state* as durable truth, so persisted rows
  must be evidence-grade projections with provenance and freshness, and the
  `D1`–`D7` resolution stays a per-request computation over them.
  (iii) A registry migration through `DeviceRegistryBackend` — a governed
  storage movement with the parity proofs `PCP` §10 lists. (iv) A canonical
  id spanning `device_id` and evidence `entity_id`, which does not exist
  (`RELAY_DECISION` #11) and which every per-device live view needs.
- **(c) Dependency.** **Depends on `X-2`** (engine). Independent of `M14`.

### `F-3` — LDAP authentication + role-based menu authorization

**Requirement.** An operator authenticates against the corporate directory;
each action's `D7` outcome derives from directory group membership; the UI
reflects authorization honestly.

- **(a) Exists.** The `M14` draft, referenced in full and not re-derived:
  `A1` actor binding vs `A2` `D7` producer; `LD-1` bind credential via the
  `DEV.2.1` env / `<VAR>_FILE` mechanism; `LD-2` fail-closed
  `AUTHZ_NOT_EVALUATED`; `LD-3` nothing persisted; `LD-4` group-to-permission
  mapping with a single access group as first slice; `LD-5` `ldap3`; `LD-6`
  verified TLS; `LD-7` one bind at launch on the TTY. Also `console/auth.py`
  (per-launch bearer token, `C-D2`) and `utils/operate/authorization.py::
  DenyAllAuthorizer` (class 2 only).
- **(b) New.** Two things `M14` explicitly does not cover, and one thing the
  frozen contract explicitly forbids:
  1. **Multi-operator sessions.** `M14` is single-operator, loopback,
     TTY-bind-at-launch (`LD-7`). A production, multi-user UI 2.0 needs a
     browser-initiated login, a server-side session, and per-session `D7`
     state — which is the shape `DEPLOY.1A` was reserved for. `M14` §11 says
     a database "would additionally enable" durable authorization history
     and role mapping outside runtime config, and that each "needs its own
     decision". UI 2.0 is the concrete reason to take those decisions;
     this document does not take them (§8).
  2. **A queryable authorization audit store** (`M14` §11 row 2) — today one
     redaction-aware log line per decision.
  3. **"Role-based menu authorization" as menu hiding contradicts frozen
     authority.** `AC-CS-33`: "Varying `D7` across all values changes no
     rendered structure — no entry, module or tab appears or disappears";
     `CON.0` §4.1: "Navigation visibility is never the authorization
     boundary… refusal happens server-side"; `CAPABILITY_STATE…` §5.3. What
     the frozen contract permits: entries render per `D1`, and each action
     shows its `D7` outcome (`PERMITTED` / `DENIED` / `AUTHZ_NOT_EVALUATED`
     / `NO_APPLICABLE_AUTHORITY`) as an honest affordance, with server-side
     refusal. If the Product Owner means "hide what this role may not do",
     that is a frozen-contract amendment (§9, `C-2`), not a UI 2.0 feature.
- **(c) Dependency.** **Depends on `M14`** for `A2`; depends on `X-2` only
  for the optional audit store and session persistence. `M14` §7's own
  `M14`/`M14L` identifier contradiction remains a Product Owner decision.

### `F-4` — Device onboarding: discovery + manual, no static HTML generation, no unnecessary data pull

**Requirement.** An operator adds a device either from a discovered
candidate list or by typed manual entry; the request contacts no device; a
single-target identity probe runs as a queued class 0 job; confirmation
persists exactly one registry record; nothing renders a report.

- **(a) Exists — and already meets two of the three constraints.**
  `POST /api/enrollment/probe` → `device_enrollment_identity_probe` →
  `run_pre_enrollment_identity_probe` through `execute_admitted_collection`
  (single endpoint, trust before credential, no `run_html_export` anywhere
  on that path); `POST /api/registry/enrollments` → `EnrollmentAuditStore.
  record_confirmation` → `DeviceRegistry.enroll` (audit before mutation,
  duplicate detection, mutation lock). CLI parity: `--registry-enroll`.
  "No static HTML generation" and "no unnecessary data pull" are already
  true of this path today.
- **(b) New.** (i) **Discovery candidates**: `candidate_id` is accepted by
  the schema and refused with `candidate_enrollment_not_available_pending_m10`
  because the registry↔evidence join (`A6`) does not exist. `PCP` §13/§17
  name the sources: Panorama `show devices all` (serial-keyed) and Check
  Point management object lists, both already collected — but only *as part
  of a plane-wide inventory run that ends in a render*. A **discovery-only
  provider job** (candidates → lifecycle `DISCOVERED` records persisted, no
  merge, no render) is new. (ii) Persisting `LifecycleStore` (today
  in-memory per run). (iii) Credential / trust *profiles*: today exactly one
  sentinel each (`system_cp_config_ssh`, `system_known_hosts`); a multi-device
  production onboarding needs a named-profile system, which "is explicitly
  out of scope" of `M9` and is a credential-boundary decision.
  (iv) Server-mode exposure of the enrollment write: `pcp_server_enrollment_
  exposure` is open and blocked on `DEPLOY.1A`
  (`console/app.py::_require_local_deployment_profile`).
- **(c) Dependency.** Independent of `X-2` (registry seam) and of `M14`
  for the probe/confirm mechanics; **the production write is gated on a
  real actor-authorization authority** — `M14`'s `A2` or `DEPLOY.1A`.

### `F-5` — Auto config/inventory population post-onboarding, eligible for compliance checking

**Requirement.** Enrolling a device schedules, by default policy, one
inventory collection and one configuration collection *for that device
only*; the results become evidence rows the compliance engine can evaluate.

- **(a) Exists.** `cp-config` has a real target seam (`--cp-config-targets`,
  `OP.0d`); `M6`'s `device_ids` admission shell; the `M4` `schedules` and
  `job_definitions` tables; `D5` (capability policy) as a frozen dimension.
  `M7` (`m7_real_device_targeted_collect_now`, unblocked, not started) is the
  Line-1 movement that delivers the first real device-targeted "collect
  now".
- **(b) New.** (i) Target seams for the inventory collectors — CP inventory
  is one MDS script over all gateways, VSX is nested SSH per host, PAN
  runtime is per serial (`PCP` §9, `PCP.6`): "real work and… recorded as
  such, not assumed". (ii) A default `D5` policy on enrollment — a `M12`
  concern. (iii) Translation `device_id` → collector target, which `M6`
  refuses today with `IDENTITY_TRANSLATION_REQUIRED` because no canonical
  id exists. (iv) Keying compliance on persisted per-device evidence rather
  than on `configUiData` (see `F-8`).
- **(c) Dependency.** Independent of `X-2` and `M14`. **Depends on Line-1
  `M7` / `PCP.6` / `M12`** — this is the clearest case of a Line-1 feature
  "evolving into" UI 2.0 rather than being rebuilt.

### `F-6` — Configuration-only fetch that writes straight to DB, no formatting side-output

**Requirement.** A configuration collection job writes the snapshot's
metadata index, provenance, and derived safe projections to the database
and the content-addressed blob to the volume, and returns; no HTML, no
telemetry JSON on `output_root` as the medium of record.

- **(a) Exists.** `ConfigEvidenceStore` (CAS, dedup, `sha256` addressing);
  `ConfigSnapshotBackend` with `PostgresConfigSnapshotBackend` for the
  metadata index; run manifests with a Postgres backend; the
  `config_refresh_cp` / `config_refresh_pan` job types; the `report_rebuild`
  job type proving the render can already be an explicit, separate job.
- **(b) New.** (i) **Decoupling the tail render**: the six
  `run_html_export` call sites (§2.2) become conditional on an explicit
  "produce the portable report" request; the report stays a product
  deliverable (`CON.0` §1) produced on demand, not on every collection.
  (ii) Replacing `cp_config_telemetry.json` / `pan_config_telemetry.json` as
  the medium between collector and downstream consumers
  (`failover_readiness_ui`, recovery attestation, the config UI builder all
  read them) with a persisted, versioned projection. (iii) **Clarifying the
  Product Owner's "straight to DB"**: the frozen persistence design keeps
  raw configuration bytes *out* of the database ("payload blobs stay on the
  volume — CAS and the recovery store never move", `PCP` §10; raw-evidence
  law). This document reads "write straight to DB" as *metadata, provenance
  and safe projections in the DB; content-addressed blob on the volume*. If
  the Product Owner means raw configuration text in database rows, that is
  a storage and privacy decision to raise explicitly (§10, `U-3`).
- **(c) Dependency.** **Depends on `X-2`** (which engine holds the index and
  projections). Independent of `M14`.

### `F-7` — Inventory-only fetch, no other data pulled

**Requirement.** An inventory job contacts only the inventory plane (or, once
seamed, only the selected devices), writes the merged inventory projection
and last-known-good to the database, and pulls no configuration, backup, or
telemetry.

- **(a) Exists.** `--only cp` / `--only vsx` (`inventory_refresh_cp` /
  `inventory_refresh_vsx`) already collect one plane and *reuse* the rest —
  "no other data pulled" is already true at plane granularity; `utils/
  merge.py::run_merge` (unified model); `utils/snapshot.py` with
  `PostgresLastKnownGoodBackend`. PAN runtime inventory
  (`panorama/panorama_runtime_runner.py`) is per serial already.
- **(b) New.** (i) The merged inventory as a DB projection instead of
  `output/unified.json` (which is also today's `entity_ids` target universe
  and the input to every payload builder). (ii) Per-device inventory
  targeting (`PCP.6` seams, as in `F-5`). (iii) Dropping the tail render (as
  in `F-6`).
- **(c) Dependency.** **Depends on `X-2`**. Independent of `M14`.

### `F-8` — Compliance: operator selects a benchmark (e.g. CIS), applies it to selected devices

**Requirement.** A benchmark from the framework catalog is assigned to a set
of registered devices; evaluation runs against those devices' persisted
evidence; results are per (device, control) rows with history.

- **(a) Exists.** `utils/framework_catalog.py` (`CIS`, `PCI-DSS`, `BDDK`;
  `requirements_for`), `utils/compliance_rulepack.py::DEFAULT_RULE_PACK`,
  `utils/compliance_catalog.py`, `utils/control_assignment.py::
  ControlAssignmentPolicy` ("which catalogued controls apply to which
  firewall, and which cells are waived"), `utils/compliance_check_pack.py`
  (`CE.1` user-authored checks), `utils/compliance_history.py` (trend
  ledger), and `build_compliance_posture` as the evaluator.
- **(b) New.** (i) **The evaluator's input.** `build_compliance_posture
  (configuration_ui, project_plan, …)` takes the *configuration UI payload*
  as its subject source — the one place in §2 where a static-export-shaped
  projection is load-bearing for a non-UI computation. UI 2.0 needs the
  evaluator keyed on a persisted, registry-linked evidence projection.
  (ii) Control assignment and check packs as database rows instead of
  `data/state/*.json`, with a UI intent to edit them. That intent is a
  **policy write from a browser**; it does not control device polling (so
  the `inventory_exclusions_management_ui_backend` "wait for `DEPLOY.1A`"
  precedent does not apply verbatim), but it changes what the product
  reports as compliant, and it needs its own authorization (`D7`) and audit
  decision. (iii) Benchmark-scoped evaluation — today the roll-up is
  catalog-wide with framework cross-references, not "run CIS only against
  these three devices".
- **(c) Dependency.** **Depends on `X-2`** (rows, history) and on
  **`M14`/`D7`** for the assignment write. Evaluation logic itself is
  independent of both.

### `F-9` — Backup: add / select device / schedule

**Requirement.** An operator enrols a device into backup, selects targets,
and sets a schedule; runs execute under the `RB.x` contracts; the UI shows
readiness, results and the ledger state.

- **(a) Exists.** `run_recovery_collection` (single "who gets collected"
  path), the `recovery-pan` allowlisted schedulable workflow, `recovery-cp`
  (deliberately not schedulable, `D3` pilot allowlist
  `SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`), `OperationalWriteLedger`
  (24 h ceiling, Postgres backend), recovery manifests and the encrypted
  store, `RB.4` validation, `compute_restore_readiness`, the `M4`
  `schedules` table, `data/state/scheduler_policy.json` (`interval_minutes
  >= 10`, default-disabled).
- **(b) New.** (i) **Backup as a class 1 action is never console-
  submittable** — `console_refusal` returns 409 for `cp_gaia_backup` today,
  and `AI_START_HERE.md` restates that class 1 "is never exposed on an HTTP
  surface". UI 2.0's "add / select device" for backup can therefore mean
  only: *edit the schedule policy and the target set server-side, with
  execution by the scheduler under `RB.x`* — and policy editing from a
  browser is itself an open gate (`C-D7`: "a privilege-escalation path into
  unattended device contact and needs its own gate"). This is a
  contradiction to resolve, not a feature to build (§9, `C-3`). (ii)
  Schedule persistence in the DB (the `M4` schema shape exists). (iii) A
  per-device backup capability projection from `D3` vendor evidence
  (`PCP` §11).
- **(c) Dependency.** Independent of `X-2` for execution; **depends on
  `M14`/`D7` and the `C-D7` gate** for any browser-originated schedule or
  target change.

### `F-10` — Drift comparison against prior backups

**Requirement.** For a device, compare the latest backup / configuration
snapshot with prior ones and present a safe, structured delta with history.

- **(a) Exists.** `utils/config_history.py` (timeline + PAN structured diff
  over the allowlisted projection; CP `INSUFFICIENT_EVIDENCE` by design),
  `utils/recovery_manifest.py` (digests, `known_gaps`, RMA grade),
  `utils/recovery_validation.py` (`V1`–`V4`), CAS content addressing (equal
  digest ⇒ identical content, cheaply).
- **(b) New.** (i) A drift projection keyed by `device_id` over manifests
  and CAS metadata, persisted with the run. (ii) Any CP configuration diff
  beyond digest equality is blocked by the raw-evidence law and `0.6.3`'s
  own decision; a safe CP diff would need a new vendor-format-aware
  projection and its own design. (iii) Never diffing backup *bytes* over
  HTTP (`CON.0` §7.6): drift is computed server-side and only the delta
  projection is served.
- **(c) Dependency.** **Depends on `X-2`** for history rows; independent of
  `M14`.

### `F-11a` — Failover menu auto-populated from live active/passive state at configurable intervals

**Requirement.** An HA / failover surface lists derived HA units with their
last-observed active/passive roles, readiness verdict, and freshness; the
observation cadence is a policy the operator can see and, within gates, set.

- **(a) Exists.** `derive_ha_units` + `compute_ha_readiness` (offline, over
  already-collected telemetry, "never talks to a device"),
  `extract_cp_ha_runtime` / `extract_pan_ha_runtime`, the
  `failoverReadinessData` payload, `data/state/ha_readiness.json`; the
  frozen fact that "ha-readiness" is absent from `ALLOWLISTED_WORKFLOWS`
  because "it performs no device I/O… scheduling an offline derivation
  would add runs without adding evidence".
- **(b) New.** (i) Persisting the readiness projection per HA unit with
  freshness (already the `PCP` §12 intent: "last-known topology projections
  with freshness"). (ii) **"At configurable intervals" is a device-contact
  cadence**: fresh active/passive state requires re-collecting HA runtime
  from the devices, which is a class 0 collection with a schedule — bounded
  by the admission coordinator and the vendor interaction-safety gate
  (`AGENTS.md` "do not increase polling/concurrency"). The derivation can run
  at any interval; the *observation* cannot without that gate's evidence.
  (iii) The menu is readiness-only: `CLASS 2` has no member, `authorize()`
  is unconditional `DENY`, and "Start failover" begins an `OP.2` workflow
  that UI 2.0 neither designs nor unblocks (`PCP` §16).
- **(c) Dependency.** Independent of `X-2` and `M14` for the derivation;
  the cadence depends on `D5`/`M12` and the interaction-safety gate.

### `F-11b` — Live / dynamic project-plan view, optionally GitHub-sourced

**Requirement.** The project-plan module reads plan state live rather than
from a render-time snapshot; optionally it reads the plan from GitHub.

- **(a) Exists.** `build_project_plan_payload` over `project/roadmap.json`,
  `feature_registry.json`, `backlog.json`, `build_history.json`, including
  the cross-authority warnings (`_cross_authority_warnings`) that
  `tests/test_architecture_convergence.py` enforces.
- **(b) New.** (i) Serving it live is trivial once the read API exists — the
  builder is already a pure function over files. (ii) **GitHub sourcing is a
  new outbound network path** from a product surface that today makes none:
  a token, an egress rule, and a cache all need the "new network-access
  patterns or credential paths" approval boundary. Recommendation: a
  server-side fetcher using the `DEV.2.1` `<VAR>_FILE` mechanism for the
  token, read-only, cached in the DB, never invoked from the browser.
  (iii) Note honestly that `project/*.json` is engineering state; whether
  it belongs on a production operator console at all is a product question.
- **(c) Dependency.** Independent of `M14`; `X-2` only for the cache.

### 5.1 Dependency matrix

| | Depends on `X-2` (engine) | Depends on `M14` / a real `D7` producer | Depends on Line-1 in-flight work |
| --- | --- | --- | --- |
| `F-1` shell | — | — | navigation / capability contracts (done) |
| `F-2` DB state | **yes** | — | `M4` schema shape (done), `DEV.4.6` |
| `F-3` LDAP + roles | audit / sessions only | **yes** | — |
| `F-4` onboarding | — | write exposure only | `M10` `A6` join |
| `F-5` auto-populate | — | — | **`M7`, `PCP.6`, `M12`** |
| `F-6` config-only | **yes** | — | — |
| `F-7` inventory-only | **yes** | — | `PCP.6` for per-device |
| `F-8` compliance | **yes** | assignment write | — |
| `F-9` backup jobs | — | **yes** + `C-D7` gate | — |
| `F-10` drift | **yes** | — | — |
| `F-11a` failover menu | — | — | `M12` cadence, safety gate |
| `F-11b` project plan | cache only | — | — |

Five of eleven points depend on the engine decision; three depend on a real
authorization producer; the rest are independent of both and are mostly
Line-1 work maturing.

---

## 6. Cross-cutting sub-decisions — recommendations with tradeoffs

Each is resolved here **as a recommendation**. None is decided.

### `X-1` — Ground-up rewrite vs incremental DB + typed live-state API over the existing foundation

**Recommendation: incremental.** Add a database-backed projection layer and
a typed live-state read API over the existing registry / job engine /
evidence backends / `D1`–`D7` resolver, keep the collection modules as data
producers only, and build the new shell (`F-1`) against that API. Discard
nothing; **retire** the tail render from collection workflows by making the
portable report an explicit export job.

**Why, from §2 rather than preference.**

1. The static-export coupling is six call sites and one read model (§2.4).
   Everything below them — registry, job store, control-plane store, seven
   Postgres-capable evidence concerns, admission coordinator, resolver,
   producers — is already the shape UI 2.0 needs. A rewrite would
   re-implement the part that is already right in order to remove the part
   that is small.
2. The Product Owner's own constraint decides it: Line-1 keeps shipping, and
   maturing features "evolve into" UI 2.0 one at a time. Only an incremental
   layer can consume `M7`'s targeted collect, `M10.3`/`M12`'s `D2`/`D3`/`D5`
   producers, and `M14`'s `D7` producer *as they land*. A rewrite would fork
   the codebase at a moment when the foundation is still being completed,
   and every Line-1 merge would then have to be ported twice.
3. The frozen invariants that make an action-capable UI acceptable at all —
   the closed job registry, the typed intent boundary, "one orchestration
   path", audit-before-action, identity-lean records — are test-enforced in
   the existing code (`tests/test_architecture_convergence.py`, `CON.1`/`CON.2`
   suites). A rewrite starts with none of those tests.
4. Real-environment validation is not portable. `0.6.1B.1.2` interactive CP
   configuration and `M8.3` first contact are `REAL_ENV_VALIDATED`; those
   verdicts attach to the code that earned them (`AGENTS.md` evidence laws:
   "automated validation != real-environment validation").

**Tradeoffs, stated.**

- Incremental inherits `CON.0` §6's payload-parity invariant, which is in
  direct tension with `F-6`/`F-7` ("no formatting side-output") and with
  `F-1` (a different shell). The honest resolution is an explicit amendment:
  parity between report and console is replaced by *both surfaces read the
  same persisted projections*; the report becomes one consumer among others.
  That is a `CON.0` amendment the Product Owner must approve (§9, `C-1`).
- Incremental keeps Python on the request path unless `X-3`/`X-4` move it;
  the two decisions are coupled.
- A rewrite would give a cleaner shell and a single language, at the cost of
  everything in 1–4 above, and of an indefinite period in which two consoles
  with different truths exist.

### `X-2` — Storage engine, against `PCP` §10's criteria and UI 2.0's specific needs

**Recommendation: PostgreSQL as the production engine for UI 2.0's
control-plane, projection and audit state; SQLite stays the local engine
(`M4`, unchanged); content-addressed blobs and the recovery store stay on
the volume (unchanged).**

Mapping to the criteria `PCP` §10 already recorded — not a generic
re-derivation:

| `PCP` §10 criterion | UI 2.0-specific need | PostgreSQL | SQLite only | Document store |
| --- | --- | --- | --- | --- |
| transactions (enrollment + relationship atomically) | enrollment + audit + candidate consumption in one commit | yes | yes (single writer) | weak / engine-specific |
| migrations, deployment-controlled (`DEV.4.6`) | one lineage across all concerns | mature tooling | possible | varies |
| concurrent workers (`DEV.3.4` deferred) | several operators + a scheduler + collection workers, possibly in another language (`X-3`) | designed for it; the coordinator backend already exists | single-writer lock; not multi-process across hosts | yes |
| uniqueness / locking | one active registry row per endpoint per vendor; one run per definition in flight | constraints + row locks | constraints | app-level |
| append/history workloads vs small mutable rows | job runs, projection history, compliance history, authorization audit | both fine | fine at laptop scale | append-friendly, joins poor |
| retention | per-table policies | yes | manual | yes |
| JSON / structured evidence | projections are structured; `jsonb` for qualifier sets and diagnostics | `jsonb` | JSON1 | native |
| deployment topology | laptop today; container volume; `DEPLOY.1` server | already opt-in in compose (`DEV.3.3`) | laptop only | new infrastructure |
| backup of neXus's own state | product state worth restoring | standard | file copy | varies |
| enterprise operation (role separation, TLS DSN, audit retention) | **the new criterion UI 2.0 adds**: LDAP-scoped actors, per-role read grants, audit tables that survive the process | roles, row-level security, TLS DSN already required by `PRIVACY_AND_DATA_HANDLING.md` "Distributed evidence store" | none | varies |

Two UI 2.0-specific facts tip it: (1) seven concerns and the admission
coordinator already have byte-compatible Postgres implementations, so the
migration cost is concentrated in the *new* projection tables, not in
porting existing state; (2) a service layer in another language (`X-4`)
reaches PostgreSQL natively (JDBC), whereas the filesystem backends are
Python-shaped.

**Tradeoffs.** Operational burden (a database to run, back up, and patch);
a hard dependency (`psycopg` today; a driver in any second language); the
`DEV.4.6` migration discipline becomes mandatory rather than opt-in; the
registry migration through its seam is a governed movement with parity
proofs. Against SQLite-only: it fails the concurrent-workers and enterprise
rows outright for a multi-user production console. Against a document
store: it discards the transactional uniqueness the registry contract
depends on and adds infrastructure the repository has never validated.

This resolves `pcp_storage_engine` **as a recommendation only**; the
decision itself stays open exactly as `PCP` §10 records it.

### `X-3` — Deployment runtime split: must every request-serving component be one language?

**Recommendation: no.** The collection modules stay Python, invoked as
workers behind a **typed boundary that is already defined**: a job is
`job_type` + opaque targets (`device_id[]`), the argv template lives
server-side (`workflow_argv`), admission goes through
`execute_admitted_collection` / `CollectionCoordinator`, and the result is
an identity-lean job record plus evidence rows. A service layer in another
language may own HTTP, sessions, `D7`, the read API and DB access, provided
it **never** constructs a device command, an argv fragment, or a path — the
same rule `CON.0` §4 imposes on the browser, now imposed on the service.

Two boundary transports fit the existing code; the choice is deferred:

| Transport | Mechanism | Fits today's code because | Cost |
| --- | --- | --- | --- |
| **queue-through-database** | the service inserts a `job_submissions` row; a Python worker process polls and executes through `main.main(argv)` exactly as `ConsoleJobRunner._execute` does | the `M4` schema already has `job_submissions` / `job_runs`; the coordinator has a Postgres backend, so several workers share one admission state | a worker daemon; polling latency; a second process to supervise |
| **local subprocess** | the service spawns `python main.py <argv from a fixed template>` per job, as the scheduler effectively does | zero new IPC; `RunContext` manifests already capture outcome | the service must hold the argv template — a second copy of `workflow_argv`'s knowledge, in another language, which is the drift `C2-2` exists to prevent |

Recommendation between the two: **queue-through-database**, because the
argv template stays in exactly one Python function.

**Tradeoffs.** Two runtimes to build, test, deploy and patch; a typed
contract (job record, projection rows) that must be versioned and
conformance-tested from both sides; two logging / redaction stacks that must
both honour the redaction registry. Mitigation: the contract is the database
schema plus a small set of JSON shapes that already exist (`JobRecord.
to_dict`, `DeviceRecord.to_dict`, `to_wire`).

### `X-4` — Java: whether, and how much

The Product Owner states a corporate-infrastructure requirement for Java.
Its exact scope is unknown (`U-1`): "deployable services must be Java",
"the UI must be Java-served", "all code must be Java", and "the database
access layer must be Java" are different requirements with very different
costs. The evaluation below covers the two mandated options plus the
baseline.

| Option | What is Java | What stays Python | Rework of frozen / validated work |
| --- | --- | --- | --- |
| **(0) baseline** | nothing | everything (FastAPI service, workers) | none |
| **(i) Java service layer** | HTTP / session / `D7` enforcement / read API / DB access / schedule UI; the new shell's server | all collectors, parsers, identity gates, CAS, recovery plane, admission coordinator, the `D1`–`D7` resolver, `main.py` as the worker entry point | **none of the collection logic**; new conformance fixtures for the boundary |
| **(ii) full rewrite** | everything, including collectors | nothing | **all of it** |

**Cost and risk of (ii), concretely.** The collection modules encode
real-environment-validated vendor semantics that are not visible in any
specification: the interactive PTY SSH handshake and secret-aware redaction
for Check Point (`configuration/checkpoint_config_collector.py`), `vsenv
<VSID>` context handling, Expert-vs-Clish capability detection, the Panorama
XML API with `effective-running` as primary evidence, identity gates on
direct-device reads, the trust-before-credential sequence, the `RB.x`
ledgered class 1 write, and every "field presence != field semantic proof"
lesson recorded in the phase history. Under `AGENTS.md`'s evidence laws a
port would reset **every** `REAL_ENV_VALIDATED` status to `PLANNED`: a
fixture-driven test proves the parser, not the vendor's real output. The
155 test modules would need re-authoring in a second ecosystem; the
network-device command gate would have to be re-walked for every command
because "command presence in source != command approval" applies to a new
implementation. It also freezes Line-1: no `M`-movement could land while
its target was being ported. This document recommends **against (ii)** on
cost, on risk, and because it directly contradicts the Product Owner's
"Line-1 keeps shipping in parallel" constraint.

**Cost and risk of (i).** New: a Java service (HTTP, sessions, LDAP bind via
a JNDI/UnboundID-class library — `M14`'s `LD-5` choice of `ldap3` is
Python-specific and would be re-asked for Java, but `LD-1`…`LD-4`, `LD-6`,
`LD-7` transfer unchanged), the read API, DB access and migrations. Risks:
(a) **the `D1`–`D7` resolver is pure Python** (`M10.2`); Java would either
re-implement it (dual-implementation drift against a frozen contract) or
call it. Recommendation: keep resolution in the Python worker, persist
resolver *inputs* (evidence-grade projections) in the DB, and expose the
frozen contract's own atomic resolution cases (`CAPABILITY_STATE…` §5.5) as
**cross-language conformance fixtures** so a later Java port is provable,
not assumed. (b) Two redaction stacks (`U-2`). (c) Enterprise Java brings
its own dependency surface into a repository whose supply-chain posture is
"four dependencies plus optional extras"; that is an approval-boundary
item, not a footnote.

**Recommendation.** If the Java requirement is binding on deployable
services: **option (i)**, with the boundary of `X-3` (queue-through-
database), the resolver kept in Python behind conformance fixtures, and
`M14`'s decisions carried over except `LD-5`. If the requirement is *not*
binding on the service layer: **option (0)** is cheaper and has no boundary
to maintain; FastAPI already carries the security model. In neither case is
(ii) recommended. **This is a requirements-document recommendation; the
decision is the Product Owner's and is not made here.**

---

## 7. One possible incremental migration path — a sketch, not the plan

Written to show that §6's recommendations are executable alongside Line-1.
Nothing here is sequenced, prioritized or authorized; the `M`-series stays
the only authorized line.

```
Stage A  "shell + DB + auth, read-only over existing evidence"
         PostgreSQL enabled for the seven existing concerns (opt-in path
         already exists) + new projection tables under one migration
         lineage (DEV.4.6). New empty shell (F-1) over a typed read API
         that serves: registry rows, job records, last-known-good
         inventory, config-snapshot index, HA readiness projection --
         every one of them read from the DB or the CAS index, none from
         output_root. M14 A1/A2 (or its server-mode successor) supplies
         D7; AC-CS-33 honored: nothing hides, everything shows its outcome.
         Line-1 unaffected: the CLI, report, and existing console keep
         working; the report is still rendered by the workflows.

Stage B  "producers write projections; the render becomes a job"
         Collection workflows persist their projections (inventory, config
         index, telemetry-derived HA runtime) with provenance + freshness;
         the six run_html_export tail calls become conditional on an
         explicit report job (report_rebuild already exists). CON.0 §6
         parity amended to "same persisted projections". F-6/F-7 met at
         plane granularity.

Stage C  "module by module, as Line-1 matures"
         M7 targeted collect  -> F-5 per-device population
         M10.3 / M12 D2/D3/D5 -> capability-aware entity workspace, F-11a cadence
         M10 A6 join          -> F-4 discovery candidates
         compliance re-keyed  -> F-8 benchmark assignment (+ its D7 gate)
         drift projection     -> F-10
         GitHub fetcher       -> F-11b (behind the network-access approval)
         F-9 waits on the C-3 contradiction (§9) being resolved.

Stage D  "retire"
         The report-derived console shell and /api/payloads are retired
         only when every module in the new shell reads live projections;
         the portable report remains a product deliverable (CON.0 §1).
```

What this sketch deliberately does not do: pick an engine (§6.2 is a
recommendation), pick a language (§6.4), amend a frozen document, or
promise dates.

---

## 8. What this document does **not** decide or authorize

- **Not the storage engine.** `pcp_storage_engine` stays open; §6.2 is a
  recommendation against its recorded criteria.
- **Not the language / runtime split.** §6.3/§6.4 recommend; the Product
  Owner decides, once `U-1` is known.
- **Not the LDAP persistence model.** Deferred entirely to the `M14` draft
  (`LD-3`, §11) and its eventual freeze. This document adds the multi-user
  requirement as *input* to that draft, not as a change to it.
- **Not the rewrite-vs-incremental scope decision.** §6.1 recommends
  incremental; the Product Owner's own packet defers the decision "to a
  later round".
- **No implementation movement is authorized by this document**, and
  freezing it would not authorize one either: every eventual slice needs its
  own explicit Product Owner go-ahead, exactly as `M1`…`M14` do.
- **No code, test, dependency, schema, migration or frozen-document change**
  was made. No live database, directory or device was contacted.
- **No relaxation of `utils/action_taxonomy.py`.** Class 1 stays
  ledgered and non-console; classes 2–4 stay prohibited.

Reconciliation with the Product Owner's stated intent: this is an
**evolution alongside Line-1**, described in §7 as reading existing evidence
first and expanding module-by-module as Line-1 producers land — never a
stop-the-world replacement. If a later round chooses the rewrite instead,
§2 and §6.1 record what that choice would discard.

---

## 9. Contradictions with frozen authority — reported, not resolved

Per `AGENTS.md` "Authority hierarchy": *"Never silently reconcile a
disagreement between two authorities… report the contradiction and let the
human or the higher authority resolve it."*

| id | Functional point | Frozen text | Nature | Options (Product Owner's choice) |
| --- | --- | --- | --- | --- |
| `C-1` | `F-1`, `F-6`, `F-7` | `CON.0` §3 "not a frontend framework / bundler"; §6 "the console never introduces a payload shape the exporter does not also produce" (`C1-4`); `PCP` §13 "the control plane's console **is** the `CON.x` console" | UI 2.0 as a separate shell with its own read API, and collection without a render, both violate these rows as written | (1) amend `CON.0` §6 to "both surfaces read the same persisted projections" and scope §3's bundler rule to the shared report bundle; (2) keep the rows and build UI 2.0 inside the existing shell / payload model, which forecloses `F-1` as stated |
| `C-2` | `F-3` | `AC-CS-33` "varying `D7`… no entry, module or tab appears or disappears"; `CON.0` §4.1 "navigation visibility is never the authorization boundary" | "role-based **menu** authorization" read as hiding menus by role | (1) keep the frozen rule: entries render per `D1`, actions show their `D7` outcome, server refuses — recommended; (2) amend `CAPABILITY_STATE…` §5.3/`AC-CS-33` |
| `C-3` | `F-9` | `utils/action_taxonomy.py` + `AI_START_HERE.md`: class 1 "never console-submittable… never exposed on an HTTP surface"; `C-D7` policy editing from a browser needs its own gate | "backup: add / select device / schedule" from the UI is either a class 1 submission or a browser policy write | (1) UI 2.0 shows backup state and readiness only; schedule / target edits stay a CLI or file operation — no contradiction; (2) open the `C-D7` gate with a `D7`-authorized, audited schedule-edit intent (still never a direct class 1 submission); (3) amend the taxonomy — not recommended |
| `C-4` | `F-3` | `M14` `LD-7` "one bind at launch on the TTY, never from the browser"; `LOCAL_CONTROL_PLANE…` §11.3 local shortcuts do not become production posture; `C-D5` server mode blocked until `DEPLOY.1A` | a multi-user, browser-login production console is the `DEPLOY.1A` shape, not the `M14` shape | (1) treat UI 2.0's auth as the successor design to `DEPLOY.1A` with LDAP instead of OIDC, reusing `M14`'s `A1`/`A2` split and `LD-1`…`LD-4`, `LD-6`; (2) extend `M14` — its own §3 says it is single-operator by construction |
| `C-5` | `F-11a` | `ALLOWLISTED_WORKFLOWS` omits `ha-readiness` deliberately; `AGENTS.md` "do not increase polling/concurrency until the… gate permits" | "configurable intervals" for live HA state is a new recurring device-contact cadence | (1) intervals apply to the derivation over whatever evidence the existing scheduled collections produce; (2) a new scheduled HA-runtime collection, only with interaction-safety-gate evidence |
| `C-6` | `F-4`, `F-8` | `pcp_server_enrollment_exposure` open; `console/app.py::_require_local_deployment_profile` | production-mode registry and policy writes are blocked pending a real authorization authority | resolved by whichever of `C-4`'s options supplies `D7`; not by this document |

---

## 10. Named unknowns — must be closed before any freeze

| id | Unknown | Why it matters | How it closes |
| --- | --- | --- | --- |
| `U-1` | The exact content of the corporate Java requirement (services only? all code? DB access? UI serving?) | decides between `X-4` (0) and (i), and whether (i)'s boundary is even needed | Product Owner states it in writing |
| `U-2` | Whether a second-language service can honour the redaction registry and the sensitive-identity reporting law byte-for-byte | a Java log line that prints a DN or an endpoint breaks `AG-1`-class gates | design of the boundary's logging contract in the eventual `CONTRACT` movement |
| `U-3` | Whether "write straight to DB" includes raw configuration bytes | contradicts `PCP` §10 ("CAS… never move") and the raw-evidence law if it does | Product Owner clarifies; this document assumed **metadata + projections in DB, blobs on the volume** |
| `U-4` | The canonical id spanning registry `device_id` and evidence `entity_id` | every per-device live view, `F-5`, and `M6`'s `IDENTITY_TRANSLATION_REQUIRED` refusal depend on it | a Line-1 movement after `M10`, with an explicit identity-law review |
| `U-5` | Multi-user session semantics for `D7` (how many operators, concurrent sessions per actor, re-validation on the server) | `M14` is single-operator; none of this is designed anywhere | the successor design named in `C-4` |
| `U-6` | Whether the GitHub-sourced plan (`F-11b`) is wanted on a production surface at all | new egress + token custody for engineering, not product, data | Product Owner decides |

---

## 11. Acceptance gates for the eventual implementation (inherited, not gates on this document)

| id | Gate |
| --- | --- |
| `AG-U1` | No route in the new service accepts a command, argv fragment, path, hostname, address or credential from a browser; the closed job registry and typed enrollment intent are the only mutation vocabularies (`CON.0` §4, §4.1) |
| `AG-U2` | Every collection run still enters through `main.main()` / `execute_admitted_collection`; no second orchestration path exists in either language — test-enforced as `CON.2` AC-2 is today |
| `AG-U3` | The `D1`–`D7` resolution is computed per request from persisted evidence-grade projections; no resolved presentation state is durable truth (`AC-ST-3`) |
| `AG-U4` | `AC-CS-33` holds in the new shell: varying `D7` changes no rendered structure |
| `AG-U5` | Class 1 remains unreachable from any HTTP surface; classes 2–4 remain refused with the class named |
| `AG-U6` | Raw configuration, backup bytes, credentials, DNs and management addresses appear in no database row served to a browser and in no log line of either runtime (`U-2`) |
| `AG-U7` | The portable report still renders, action-free, from the same persisted projections (`CON.0` §1) |
| `AG-U8` | Cross-language conformance fixtures for every boundary shape (`JobRecord`, `DeviceRecord`, `to_wire`, the §5.5 resolution cases) pass on both sides before any Java component serves a request |
| `AG-U9` | Full regression, repository privacy gate, `git diff --check`, render harness where a UI module or payload builder changes; `DEV.4.6` migration discipline for every schema change |

---

## 12. Next movement / reasoning tier

The next step is a Product Owner `DECIDE` episode on `C-1`…`C-4` and
`U-1`, not an engineering movement. If the Product Owner then wants the
scope decision, the recommended next engineering movement is an
`ARCHITECTURE` / `CONTRACT` draft for **Stage A only** (§7), scoped to the
read API and projection schema over the existing evidence concerns.

Reasoning tier for that contract: **`Sonnet 5, extended thinking (high)`**
— a storage and security boundary. `Opus / Fast` was the correct tier for
this cross-subsystem requirements round and is more than the Stage A
contract needs.

---

## 13. Cross-references

- `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §9, §10, §11, §12,
  §13, §16, §17 — FROZEN; the persistence seam and the open engine decision.
- `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` — the console's boundaries;
  `C-D2`, `C-D5`, `C-D7`.
- `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §6.4, §9.1,
  §11.3, §12 — FROZEN.
- `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §3, §5,
  §6, `AC-CS-33` — FROZEN.
- `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` — FROZEN.
- `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md` — DRAFT; the
  `D7` producer design this document references and does not re-derive.
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` — the `DRAFT`→`FROZEN`
  framing precedent.
- `docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md` — the
  existing Postgres backends and their identity-fidelity decision.
- `utils/evidence_backend.py`, `utils/control_plane_store.py`,
  `utils/coordinator_backend.py` — persistence seams.
- `utils/capability_state_resolver.py`, `utils/registry_evidence_
  reconciliation.py` — the pure resolver chain.
- `console/app.py`, `console/runner.py`, `console/registry.py`,
  `console/payloads.py`, `utils/collection_executor.py::workflow_argv`,
  `application/workflows/checkpoint.py` — the audited console path.
- `utils/action_taxonomy.py` — the class vocabulary, untouched.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — approval boundaries (dependency
  additions, new network-access patterns, schema / storage migration).
