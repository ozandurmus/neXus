# Architecture — How SecurityExpert Works

Deep mechanism reference. For the 30-line overview, CLI mode table and directory
map, see `AI_START_HERE.md`. Read only the sections your task touches.

Scope of this document: the mature/implemented behavior as of product baseline
`0.6.6A`. Where a plane is partial, it says so.

---

## 1. Shape

A single Python CLI (`main.py`). No web server, no framework. Runtime
dependencies: `lxml`, `paramiko`, `requests`.

```
_build_runtime_config  (interactive: endpoint + login + secret)
  → RunContext.create
  → collectors (each admitted through the coordinator)
  → snapshot   → merge → verify → config-evidence
  → html_export → output/index.html
  → support_bundle → shareable zip
```

Everything a run produces is written outside the repository. `main.py` first
calls `utils.runtime_paths.resolve_runtime_paths(--runtime-root)`:

- Precedence: `--runtime-root` CLI → `SECURITYEXPERT_RUNTIME_ROOT` env →
  platform default: Windows `%LOCALAPPDATA%\SecurityExpert\runtime`, or on
  macOS/Linux `$XDG_DATA_HOME/SecurityExpert/runtime` (else
  `$HOME/.local/share/SecurityExpert/runtime`) -- a local dev/AI-session
  convenience (`dev_python_env_tooling_friction`), not a production default; a
  container/server deployment (DEV.3) must still set
  `SECURITYEXPERT_RUNTIME_ROOT` explicitly to its mounted volume.
- `data_root`, `output_root`, `logs_root` under it.
- Rejects any runtime root that equals or is nested with the repository root
  (`_validate_separation`), and write-probes every directory.
- `discover_repository_root()` keys off this source file, never the CWD.

Module-level consumers that need the output dir at import time call
`default_output_root(repository_root=BASE_DIR)`.

---

## 2. Admission coordinator + limited scheduler

`utils/collection_executor.py`. Every collector call in `main.py` goes through
`execute_admitted_collection(services, vendor=..., workflow_scope=...,
canonical_ids=[endpoint], operation=lambda: run_x(...))`.

- **Per-physical-endpoint lock.** One active job per `canonical_id` (the
  management endpoint). VSX VSID and PAN VSYS are planning dimensions, not lock
  keys.
- **Concurrency budget.** Fixed at **1** per vendor/context
  (`checkpoint`, `checkpoint_vsx`, `paloalto`). Raising it requires explicit
  real-environment evidence. The CP device-interaction-safety audit itself
  closed 2026-08-25 (`backlog.json` `cp_device_interaction_safety`).
- **Coalescing.** A second request for a busy endpoint attaches to the running
  job (`CoordinatorDecision.COALESCED`) — no second session is opened.
- A non-admitted decision (`REJECTED_BUDGET` / `REJECTED_LOCKED`) raises
  `CollectionAdmissionError` **before** `operation()` runs.
- **Backend (DEV.3.2).** `CollectionCoordinator` delegates every admission
  decision to a `utils.coordinator_backend.CoordinatorBackend`. Default
  (`SECURITYEXPERT_COORDINATOR_BACKEND=memory`, unset) is
  `InMemoryCoordinatorBackend` — the unchanged, validated single-process
  behavior. `SECURITYEXPERT_COORDINATOR_BACKEND=postgres` (+
  `SECURITYEXPERT_COORDINATOR_POSTGRES_DSN`, `requirements-postgres.txt`)
  opts into `PostgresCoordinatorBackend`: per-endpoint exclusion is a
  session-level `pg_advisory_lock` held on a dedicated connection for the
  job's lifetime (released by the server itself if the connection dies —
  no TTL, no heartbeat), the per-vendor budget is a counted check under a
  short-lived per-budget-key gate lock, and lock keys are HMAC-derived from
  `canonical_id` so no device identity reaches Postgres. A fail-closed
  startup preflight (`verify_postgres_backend_ready`) detects a
  transaction-pooling proxy (which silently breaks session-level advisory
  locks) before the backend is used. `docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`.
- In-process, in-memory. Distributed locking / durable queue are deferred to
  DEPLOY.1.

`RuntimeCollectionServices` = process-lifetime `coordinator + lifecycle_store +
capability_store + scheduler_policy`, created once in `main()` and threaded
through every stage and the HTML export.

**Scheduler.** No `data/state/scheduler_policy.json` → no jobs (default off).
If present: schema v1, `enabled` bool, only allowlisted workflows
(`checkpoint/cp/vsx/pan-config/cp-config`), `interval_minutes >= 10`. Malformed
policy fails with `SchedulerPolicyError` / `exit 2` **before any network
access**. `--scheduler-once` evaluates due workflows and re-invokes `main()`
with `provenance=scheduled`; it never creates a loop.

---

## 3. RunContext — run isolation

`utils/run_context.py`. Created only for a full run (`--only all`).

- `run_id = <YYYYmmdd_HHMMSS>_<uuid8>`; tree `data/runs/<run_id>/{raw,parsed,unified,stage}`.
- Ten core stages: `cp, vsx_collect, vsx_parse, cp_config, panorama, pan_config,
  snapshot, merge, verify, html`.
- `start_stage` / `finish_stage(status="success"|"degraded")` / `fail_stage`
  each rewrite `manifest.json` atomically (tmp → `replace`), with duration and
  secrets-free detail.
- Artifact flow: a collector writes to `output/` → `ctx.capture(name, category)`
  copies it into `raw/` or `parsed/` **and** `stage/`, recording sha256 + size +
  json-validity → at the end `ctx.publish_from_stage(name)` atomically copies
  `stage/<name>` back to `output/<name>`.
- Coordinator job metadata (`job_id, provenance, coordinator_decision,
  coalesced_to, effective_scope`) is written to the manifest — no secrets.

So during a full run `output/` stays "live", but the auditable evidence chain is
under `data/runs/<id>/`, hashed and manifested.

---

## 4. Inventory collectors — exact method

### 4a. Check Point inventory — `checkpoint/cp_runner.py` + `scripts/cp_inventory.sh`

1. `paramiko` SSH to the **MDS**. Host key: `AutoAddPolicy` (compat) by default;
   `SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1` switches to a strict `known_hosts`
   preflight (`utils/cp_ssh_trust`) that fails before `connect()` and never
   falls back.
2. `cp_inventory.sh` is uploaded via SFTP and **sha256 byte-verified** against
   the local copy; previous run markers are removed.
3. The script runs on the MDS under `bash -l`: `$MDSVERUTIL AllCMAs` → for each
   CMA `mdsenv` + `cpmiquerybin attr "" network_objects "<CP_QUERY>"` to
   discover gateways / cluster members / VSX objects. `SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES`
   (from the runtime exclusion policy, **exact name only**) is filtered out with awk.
4. For each discovered gateway:
   `timeout <N> $CPDIR/bin/cprid_util -server <IP> rexec -rcmd bash -c "<cmd>"`
   — i.e. the MDS→gateway CPRID channel, **not** SSH. Commands:
   `ip -details -4 addr show`, `ip route`, `cphaprob -a -m if`.
   Bounded parallelism (default 6), one bounded retry (first timeout 10s /
   retry 30s).
5. Output on the MDS: `/home/admin/cp_raw/*_{interfaces,routes,cluster_if}.txt`
   plus `.collection_meta` and a `.collection_status.tsv` that grows to ~21
   columns (rc, attempts, error class, `management_state`, `collection_outcome`,
   `management_ip`, `cma`, `object_type`, VSX membership flags, cluster-probe
   telemetry).
6. Python side downloads the `.txt` files, validates the fresh marker
   (`_validate_new_collection_marker` — the old marker was deleted, so a valid
   one proves this invocation produced the raw set), and parses with
   `parse_interfaces` / `parse_routes` / `parse_cluster_virtual_interfaces`.
7. **ClusterXL grouping** (`enrich_cluster_topology`): `sha256[:16]` of
   `[cma, sorted (vip_name, vip_ip) rows]` is the group id. The `-CLS` display
   name is cosmetic and flagged `inferred`; identity is always the runtime VIP
   fingerprint.
8. `checkpoint/direct_ssh_probe.py` runs an **observe-only** direct-SSH fallback
   probe for gateways whose CPRID collection failed (not promoted to inventory;
   collects Spark hints).
9. Artifacts: `output/cp.json` (parsed) + `output/cp_telemetry.json`
   (LOCAL-only; contains identities — the support bundle HMAC-tokenizes them).

### 4b. Check Point VSX — `checkpoint/vsx_runner.py` + `vsx_parser.py`

1. SSH to the MDS → `cpmiquerybin ... type='cluster_member' &
   vsx_cluster_member='true'` to discover VSX members; only real `-1`/`-2`
   members are kept.
2. `ThreadPoolExecutor(max_workers=3)`. Per member: SSH to MDS →
   `invoke_shell()` → **nested** `ssh <user>@<gw_ip>` → `cphaprob stat`
   (**skip if Standby**) → `vsx stat -v` for the VS list.
3. Per VS: `vsenv <id>; fw ctl set int vsid <id>; ifconfig` and
   `... ; ip route`. `run_cmd` waits on the Expert prompt regex
   (`[Expert@...]#`) with an idle grace and a hard `max_wait` (replaces the old
   fixed-sleep truncation).
4. `output/vsx_raw.json` → `run_vsx_parse` → `output/vsx.json`. Per-command
   telemetry (`duration_ms`, `bytes_read`, `prompt_seen`, `timeout`) →
   `vsx_telemetry.json`.

### 4c. Palo Alto Panorama runtime — `panorama/panorama_runtime_runner.py`

1. `requests` HTTPS **XML API**. TLS verify precedence:
   `SECURITYEXPERT_PAN_CA_BUNDLE` (path) > `SECURITYEXPERT_PAN_TLS_VERIFY`
   (bool) > `False` (compat default). If a CA bundle is set,
   `preflight_pan_tls_ca_bundle()` runs before any request; strict mode never
   falls back to `verify=False`.
2. `type=keygen` → API key (added to the log-redaction set) → `<show><devices>
   <all>` for managed devices (`serial`, `hostname`, `connected`,
   `ip-address` → `management_ip`).
3. Per device, **targeted** (`target=<serial>`): `<show><interface>all` and
   `<show><routing><route>`. Raw XML → `panorama_raw/<serial>_*.xml`; parsed by
   `parse_interfaces` (VR extracted from the `fwd` field; vsys/zone) and
   `parse_routes` (flags → `default` / `static` / `connected`, default
   destination takes precedence).
4. Sequential, `sleep(0.2)` between devices. Artifacts:
   `output/panorama_runtime.json` + `panorama_telemetry.json`.
   Note: on the first sub-call failure the device is skipped entirely (interface
   failure → routes not attempted) — coarser than the config collector.

---

## 5. Configuration plane — separate from inventory

Conceptual split (see `AGENTS.md`): **Inventory** = runtime/operational state;
**Configuration** = current *configured* state; **Alignment** = expected central
intent vs actual.

### 5a. PAN configuration — `configuration/panorama_config_collector.py::run_panorama_config_evidence`

One large multi-stage function (~750 lines):

- **Panorama intent**: Panorama's own active management config
  (`get_panorama_management_config`) → Template / Template Stack / Device Group
  assignment + provenance.
- **Expected compiler** (`pan_expected_compiler.compile_panorama_expected`):
  compiles Template Stack scalar precedence and Device Group policy lineage into
  a hashed expected manifest under `data/derived/panorama_expected/<run_id>/`.
- **Direct firewall evidence** (primary actual evidence): direct PAN-OS API to
  each firewall, `effective-running` config. Identity gate is mandatory
  (`get_direct_system_info` serial match). `merged` and `local-active` configs
  are corroborating evidence.
- **Setting alignment engine** (`pan_setting_alignment.align_expected_to_effective`):
  classifies each setting — `ALIGNED / LOCAL_OVERRIDE / EFFECTIVE_DRIFT /
  PANORAMA_OUT_OF_SYNC / EXPECTED_ONLY / LOCAL_ONLY / UNKNOWN`. EXPECTED_ONLY /
  LOCAL_ONLY are not automatically drift.
- **Semantic validation + policy hardening** (`pan_semantic_validation`,
  `pan_semantic_policy`): HA peer-address differences are member-relative; VSYS
  display-name ↔ internal `vsysN` resolution; `POSSIBLE_SCHEMA_EQUIVALENT`
  hypotheses never auto-promote to `ALIGNED`.
- **HA runtime role**: Panorama `ha-state` if present, else a targeted read-only
  `show high-availability state`. Never inferred from static HA Group ID.
- Outputs: artifacts in the content-addressed store;
  `data/derived/panorama_{alignment,semantic_validation}/<run_id>/`; local-only
  operator reports (`output/pan_*_<run_id>.json/csv`); shareable
  `config_support_<run_id>.zip` (counts and contracts only).
- Engine-pass and human-confirmation are separate gates
  (`a4_2_1_engine_pass=true` while `manual_confirmation_status=pending`).

### 5b. CP configuration — `configuration/checkpoint_config_collector.py::run_checkpoint_config_collection`

- Input: mature CP/VSX inventory artifacts (no fresh discovery).
- **Interactive PTY SSH capability handshake** (capability-based, not
  prompt-classification):
  ```
  SSH auth → interactive PTY
    → `show hostname`  ok    ⇒ interactive direct Clish
                       fail  ⇒ `clish -c 'show hostname'`
                          ok    ⇒ Expert → explicit Clish
                          fail  ⇒ bounded legacy exec-channel fallback
  ```
  Once proven, the mode is reused for read-only Gaia commands. A wide terminal
  is requested to avoid wrapping long `set ...` lines.
- Entity types: standalone gateway, ClusterXL physical member, VSX host, VSX
  Virtual System (validated Expert `vsenv <VSID>` path; Clish
  `set virtual-system` fallback).
- `show configuration` is processed **in memory only** — never persisted,
  printed, stored in CAS/history, or shown in the UI. Secret-bearing lines are
  withheld **before** CAS and UI projection; only a sanitized artifact plus an
  internal full-config fingerprint are kept (secret-only change stays
  detectable).
- Platform classification (Gaia / Gaia Embedded / Quantum Spark / unknown) is
  separate from capability and evidence-driven; direct-Clish behavior is never
  taken as Spark identity.
- Coverage is currently PARTIAL (`101/122` entities — see `CURRENT_STATE.md`).
- Output: `output/cp_config_telemetry.json` (LOCAL-SENSITIVE; real identities,
  addresses, non-secret values).

### 5c. Content-addressed storage — `utils/config_evidence.py`, `config_storage.py`, `config_history.py`

- `data/configs/` = snapshot metadata + history; `data/artifacts/config/sha256/`
  = immutable payload, stored **once per hash**.
- `SAME` run ⇒ reference/metadata only; `CHANGED` run ⇒ new unique object, older
  versions preserved.
- Vendor-neutral: PAN XML wired now; CP Gaia text can use the same store.
- `--storage-analyze` / `--storage-deduplicate [--apply]` migrate legacy
  per-snapshot copies into the CAS (dry-run default; delete only after
  hash + metadata verification).
- `ConfigHistoryService`: **read-only** timeline (max 50 events) + safe
  normalized diff (PAN: allowlisted structured projection; CP:
  `INSUFFICIENT_EVIDENCE` — no raw/redacted Gaia text diff). Timeline rows carry
  no sha256, object path, `management_ip`, credential, or raw config line.

---

## 6. Merge, snapshot, verify, publish

- **`run_merge`** (`utils/merge.py`): `cp.json` + `vsx.json` +
  `panorama_runtime.json` → `normalize_*` to a common shape
  (`source, device, vsys, interfaces[], routes[]`) → `unified.json`. In a full
  run the inputs are `stage/*_effective.json`.
- **`build_failure_aware_snapshot`** (`utils/snapshot.py`): adds
  `inventory_status` to every entity — `data_state ∈ {live, last_known_good,
  no_data, partial}`, plus `availability_state` and
  `last_successful_collection`. Fresh entities update
  `data/state/last_known_good.json`; entities explicitly observed unavailable
  this run are represented with their LKG data or a zero-data placeholder — so
  **collection failure is never confused with device removal**. This is the
  source of the UI's `LIVE / OLD DATA / NO LIVE DATA` semantics.
- **`run_verification`** (`utils/verification.py`): observe-only,
  `publish_blocking: false`. Writes `verification.json` with integrity warnings:
  CP collector upload/hash/exit/marker, collected-vs-parsed count mismatch, raw
  file count, raw age (`FBUDDY_CP_RAW_MAX_AGE_SECONDS`), unresolved
  route→interface references, invalid route CIDR, duplicate identity, VSX
  completeness (raw↔parsed context delta, timeouts, prompt misses), Panorama
  success↔parsed mismatch.
- **`run_html_export`** (`utils/html_export.py`): replaces placeholders in
  `templates/index.html` — `/* __STYLE_PLACEHOLDER__ */` ← `static/style.css`,
  `/* __SCRIPT_PLACEHOLDER__ */` ← `static/app.js`, and five JSON payloads:
  `__DATA_JSON_PLACEHOLDER__` (unified inventory),
  `__CONFIG_JSON_PLACEHOLDER__` (`config_ui.build_configuration_ui_payload`),
  `__COMPLIANCE_JSON_PLACEHOLDER__` (`compliance_posture` — 10 deterministic
  CIS/PCI/BDDK-mapped controls),
  `__PROJECT_PLAN_JSON_PLACEHOLDER__` (`project/*.json` via
  `utils/project_plan.py`),
  `__DISCOVERY_JSON_PLACEHOLDER__` (lifecycle / capability / coordinator /
  scheduler observability). Result: a single dependency-free `output/index.html`.
  UI modules: Overview, Network Inventory, Configuration, Compliance, Discovery,
  Project Plan. `static/app.js` (~5000 lines) does the inventory-side IP math,
  ClusterXL/VSX/PAN-HA collapsing into one logical cluster, member-divergence
  tabs, and hierarchy building.
- **`run_support_bundle`** (`utils/support_bundle.py`): sanitized shareable zip
  with HMAC-tokenized (`data/.support_hmac.key` or `FBUDDY_SUPPORT_HASH_KEY`)
  device/assignment identities; raw config and value hashes excluded.

---

## 7. Discovery lifecycle + capability

- `utils/discovery_lifecycle.py`: per-entity state machine keyed by an opaque,
  secret-free `canonical_id` — `DISCOVERED → VALIDATED → STABLE`, side states
  `EXCLUDED / REMOVED`, defined transitions + confidence + evidence-plane +
  reason code.
- `utils/capability_registry.py`: `CapabilityProfile` records observed shell
  (`EXPERT / DIRECT_CLISH / UNKNOWN`) and collection interface. Platform
  *identity* is a separate management-plane concept — never inferred from shell
  or collection capability. `plan_collection(...)` combines profile + lifecycle
  into a `CollectionPlan` (`EXPERT_EXPLICIT_CLISH / DIRECT_CLISH_CAPABLE /
  VSX_VSENV / PAN_API / DEFERRED_STANDBY / DEFERRED_LIFECYCLE / UNKNOWN`); it
  never guesses.

---

## 8. Security & privacy model (code level)

- **Read-only invariant.** No collector issues a write command. CP commands are
  `ip` / `route` / `cphaprob` / `show`; PAN is `show` + `keygen`.
- **Secret redaction.** `utils/logger.register_sensitive_value()` — in-process
  exact-match; every log line passes through `_redact()`. The principal is
  recorded only as `sha256[:12]`.
- **Runtime outside git.** `runtime_paths` rejects repo↔runtime overlap;
  `--repository-privacy-check` (`utils/repository_privacy.scan_repository`) is
  the pre-Corporate-Git DLP gate — matched values are never printed.
- **Production hardening is opt-in.** CP SSH strict `known_hosts`
  (`utils/cp_ssh_trust`), PAN strict CA bundle (`utils/pan_tls_trust`) — both
  preflight before the network call, no fallback. Compat mode stays the default.
- **Degraded-safe.** Config-stage exceptions are caught, the stage is marked
  `degraded`, and the inventory pipeline continues
  (`main.py` cp_config and pan_config blocks).

---

## 9. End-to-end data flow (full checkpoint)

```
_build_runtime_config (endpoint + login + secret)
  → RunContext.create
  → [cp]           admit → run_cp                       → capture cp.json, cp_telemetry.json
  → [vsx_collect]  admit → run_vsx                       → capture vsx_raw.json, vsx_telemetry.json
  → [vsx_parse]    run_vsx_parse                         → capture vsx.json
  → [cp_config]    admit → run_checkpoint_config_collection → capture cp_config_telemetry.json  (degraded-safe)
  → [panorama]     admit → run_panorama_runtime          → capture panorama_runtime.json, panorama_telemetry.json
  → [pan_config]   admit → run_panorama_config_evidence  → CAS + derived + config_support.zip   (degraded-safe)
  → [snapshot]     build_failure_aware_snapshot          → stage/*_effective.json, update LKG
  → [merge]        run_merge(stage/*_effective.json)     → stage/unified.json → publish
  → [verify]       run_verification(stage)               → verification.json → publish
  → [html]         run_html_export                       → stage/index.html → publish
  → write_manifest(status=completed|degraded)
  → run_support_bundle                                   → support_bundle_<run_id>.zip
```

---

## 10. Known rough edges (informational, not a work order)

- `checkpoint/cp_runner.py` `enrich_cluster_topology`: the second
  `for item in results` loop body is dead code.
- Several bare `except:` clauses (`derive_network`, `cp_runner` sftp chdir).
- `checkpoint/vsx_runner.py`: Turkish + emoji comments and a module-global
  `_TELEMETRY` + lock — stylistically detached from the other runners.
- Version naming is scattered across the code: `COLLECTOR_VERSION`, `PHASE`,
  legacy `FBUDDY_*` env vars alongside `SECURITYEXPERT_*`.
- `panorama_runtime_runner` skips a device entirely on the first sub-call
  failure; `panorama_config_collector` is far more granular. The two PAN paths
  have asymmetric error granularity.
- `run_panorama_config_evidence` is ~750 lines in one function — the highest
  single complexity point in the codebase.
