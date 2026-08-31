# SecurityExpert — Current State

Hot-path state only. Historical build detail lives in
`project/build_history.json` (structured index) and `docs/history/` (archived
agreements and validation reports). `docs/history/INDEX.md` is the one-line
timeline.

- **Authoritative checkpoint:** 2026-08-31 (`main` at `ae10bf7`)
- **Product baseline:** `0.7.7 — Compliance trend retro-fill (PAN baseline
  reconstruction)` — AUTOMATED_VALIDATED (0.7.x VERIFY track)
- **Previous:** `DEV.3.1 — Linux worker image + Compose` — AUTOMATED_VALIDATED
- **Previous:** `0.7.6a — Render harness + uitest topology matrix` — AUTOMATED_VALIDATED
- **Hotfix `0.7.4a`** (2026-08-30) — **REAL_ENV_VALIDATED**. The report's inline
  `<script>` was broken by a placeholder-substitution collision (a `project/*.json`
  note contains `__CRYPTO_JSON_PLACEHOLDER__`), which killed every module-nav
  button. Fixed by a single-pass template fill; product owner confirmed a full
  checkpoint render on the corporate laptop — all tabs work.
  `docs/history/phase/0_7_4A_HTML_EXPORT_RENDER_HOTFIX.md`.
- **Engineering baseline:** `DEV.1` complete; `DEV.2.1` (non-interactive runtime
  config) — AUTOMATED_VALIDATED; `DEV.3.1` (Linux worker image + Compose) —
  AUTOMATED_VALIDATED.
- **Product evidence baseline:** `0.6.1B.1.2` interactive Check Point configuration
  collection is REAL-ENVIRONMENT VALIDATED.

**Note on this file:** between the `671fd6c` merge (2026-08-30) and this
checkpoint, twelve further builds landed on `origin/main` — several
sessions ran without rewriting this file / `AI_HANDOVER.md` at close, so they
were only ever recorded in `project/build_history.json`. This checkpoint
re-syncs both files against `origin/main` HEAD. If this file's "Active build"
section and `project/build_history.json`'s newest entry ever disagree again,
`build_history.json` is authoritative — treat a disagreement as a docs-sync
gap to close, not a reason to trust this file over it.

---

## Active build

**`distributed_evidence_store_migration` (DEV.3.3) — AUTOMATED_VALIDATED
2026-08-31, merged to `main` (`ae10bf7`).** The evidence-integrity half split from DEV.3.2.
Contract frozen after product-owner review, then implemented:
`docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md`.

New `utils/evidence_backend.py` puts four stores behind backends — CAS
metadata index, run manifests, last-known-good, scheduler state — each with a
filesystem implementation carrying today's exact behavior (the default,
unchanged) and an opt-in PostgreSQL one selected by
`SECURITYEXPERT_EVIDENCE_BACKEND` / `SECURITYEXPERT_EVIDENCE_POSTGRES_DSN`.
Deliberately independent of DEV.3.2's `SECURITYEXPERT_COORDINATOR_BACKEND`:
either may be enabled without the other. **Content-addressed payload blobs
never move** — they stay on the runtime volume on both backends.

The contract's one open decision (**E1**) was put to the product owner and
resolved to **full identity fidelity**: the Postgres index carries
`device`/`management_ip`/`entity_id` exactly as `metadata.json` does today,
and that instance is now documented as a **CLASS 2 identity-bearing asset**
in `PRIVACY_AND_DATA_HANDLING.md` (dedicated instance, TLS DSN, restricted
role, encryption at rest).

Nine implementation-time findings are recorded as explicit contract
amendments (A1–A9) rather than silently absorbed. Two were substantive:

- **A1** — moving last-known-good to per-entity rows does *not* by itself fix
  the lost-update race the build exists to close; the caller's
  load-mutate-save-whole-map pattern reproduces it against the table. So
  `build_failure_aware_snapshot` now reads and writes each entity
  individually, while the filesystem backend buffers those writes and still
  performs exactly one whole-file write per run.
- **A9** — found by the two-real-subprocess test: PostgreSQL's `CREATE TABLE
  IF NOT EXISTS` does not serialize against a concurrent identical `CREATE`,
  so two worker containers starting together against a fresh database could
  crash one of them. Schema creation now runs under a transaction-level
  advisory lock (pooler-safe, unlike DEV.3.2's session-level locks).

17 new tests (`tests/test_dev3_3_evidence_store_migration.py`) against a real
local PostgreSQL 16. Full suite **788 passed / 3 skipped / 2 failed** — the
same two pre-existing unrelated failures, zero regressions (the skip count
fell from 11 because DEV.3.2's Postgres tests also run when an instance is
available). Privacy gate PASS / 0. `main.py` gains a fail-closed startup
preflight; a misconfigured Postgres backend stops at a clean `parser.error`.

**Owed before `DONE`:** a multi-container real-environment run proving
last-known-good state for a fleet split across containers matches a
single-container run (server-blocked, DEPLOY.1). Backfilling existing
filesystem history into Postgres is deliberately out of scope (same
no-backfill precedent DEV.3.2 set).

**Next objective: `RB.3` (CP Gaia backup).** Blocked on **`D3` alone** — the
product-owner decision on whether `add backup local` is acceptable now as the
new `operational-write` command class — plus that command's own gate review
(drafted at contracts §7.3; point 14, the device-impact assessment, is owed
and itself gated on D3). The P0 `cp_device_interaction_safety` audit **closed
2026-08-25**; do not re-cite it as open. There is an unblocked slice: contracts
**§7.5** (`show backups` / `show snapshots`, class `read`) is the attestation
path, does not depend on D3, and the contract itself calls it "worth gating
first, independently of RB.3" — it would populate the `attestations` argument
`utils/restore_readiness.py` already accepts but nothing currently fills.
Ready-to-paste next-chat prompt:
`docs/history/handover/RB3_NEXT_CHAT_PROMPT.md`.

**Previously (before this session): no build open.** This checkpoint reconciles two independent sessions that
landed in parallel on separate branches: this session's RECOVER track
(`recovery_collect_rb2_rb4` + predecessors, PR #15) and a separate session's
`compliance_trend_reconstruction` (`0.7.7`) + `distributed_endpoint_lock`
(`DEV.3.2`) + a stale-doc correction, already on `origin/main`. Both are
below, most recent first per branch; neither superseded the other.

**CORRECTION (fixed in this merge):** this session's own RECOVER-track work
(architecture doc, contracts, `checkpoint/checkpoint_recovery_collector.py`,
`project/backlog.json`'s `native_backup` note) repeatedly cited the CP
device-interaction-safety audit (P0) as "not started" — that was stale. Per
`project/backlog.json`'s `cp_device_interaction_safety` (see "Standing
priorities and blockers" below), **it closed 2026-08-25**, before this
session began. `RB.3`'s real remaining blocker is `D3` alone (the
product-owner decision on the `operational-write` command class) plus
`add backup local`'s own command-gate sign-off (drafted, not yet approved,
in contract §7.3) — not an unstarted audit. Corrected everywhere this merge
touches; **do not re-cite the audit as open.**

Last landed on this session's branch: `recovery_collect_rb2_rb4`
(2026-08-30, this session). **`D2` RESOLVED** (product owner approval,
recorded in
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13): the PAN service
account may hold superuser for device-state export only. **`RB.2` —
IMPLEMENTED, real-environment validation owed** (no device reachability in
this sandbox). **`RB.4` — AUTOMATED_VALIDATED.**

Explicit product direction this build follows: recovery collection must not
be logic inlined in `main.py`; must be selective per gateway; must be
scheduler-integrated from day one. New `utils/recovery_collect.py` is the one
orchestration layer — target selection (`"all"` or an explicit `entity_id`
list, VSX `__vsid_` addressing included; an unresolvable id fails before any
device is touched), a `RecoveryCollector` protocol for vendor dispatch, and
admission-coordinated execution where one gateway's failure never aborts the
batch. `main.py --recovery-collect --recovery-vendor {panorama|checkpoint}
[--recovery-gateways ...]` is a thin CLI that builds a request and dispatches
— the same call a future UI action or the scheduler makes.
`panorama/panorama_recovery_collector.py` implements PAN device-state export
(contract §7.1, `read` class, gate-documented before this build) — session
reuse, no 403 retry. `checkpoint/checkpoint_recovery_collector.py` is a typed
blocked stub (`D3` unresolved — the P0 audit itself is closed, see the
CORRECTION above) so the orchestration/store/admission wiring is already
correct for CP once `D3` is decided and the command gate signs off.
`utils/collection_executor.py` gains
`"recovery-pan"` in `ALLOWLISTED_WORKFLOWS` (not `"recovery-cp"`) and an
additive optional per-schedule `targets` field — every existing scheduler
policy file's meaning is unchanged. **Correction recorded in the architecture
doc:** scheduled recovery collection does *not* need to wait for
`distributed_endpoint_lock_and_job_store` under the current single-container
deployment — verified end-to-end with a real `--scheduler-once` run.

Separately, `utils/recovery_validation.py` (RB.4) implements the V1–V3
battery (contract §4); `main.py --recovery-validate` rewrites each held
artifact's `manifest.validation`. A real bug was caught and fixed here: the
initial gate only checked the top-line verdict, but a V2-only failure still
reports `verdict=INTACT` (V1 passed) — the gate now scans every individual
check for a `FAIL`, not just the summary verdict.

85 new tests (`tests/test_rb2_recovery_collect.py`,
`tests/test_rb4_recovery_validation.py`); one pre-existing test
(`test_allowlisted_workflows_are_read_only`) updated in place for the
intentional allowlist expansion. `py -m pytest -q`: 741 passed, 3 skipped, 2
pre-existing unrelated failures unchanged. Privacy gate PASS/0.
`project/build_history.json` entry `recovery_collect_rb2_rb4`.

**Next:** `RB.3` (CP) remains blocked on `D3` alone (P0 audit closed — see
CORRECTION above). PAN
configuration-XML export (contract §7.2, secondary artifact) not yet built.
**Known gap:** PAN artifact `software_version` is recorded as the honest
`"unknown"` sentinel — `unified.json` carries no PAN version field, and
inventing an undocumented device command to fetch one was deliberately
avoided; this should become its own gate-reviewed item. **`D1` is still a
product-owner action, not engineering** — vendor scope is frozen to CP+PAN.

Prior: `recovery_store_rb1` — **AUTOMATED_VALIDATED** (2026-08-30).
Recovery-plane store: encryption, manifest, retention, validator, no
collection. `utils/recovery_crypto.py`, `recovery_manifest.py`,
`recovery_store.py`, `recovery_retention.py`; `resolve_recovery_root`; `main.py
--recovery-store-check`; `docker-compose.yml` `securityexpert-recovery` volume
on `worker` only. 41 tests.

Prior: `restore_readiness_rb0` — **AUTOMATED_VALIDATED** (2026-08-30). First
implementation against the frozen contracts (§5): `utils/restore_readiness.py`
+ `main.py --restore-readiness-check`. 16 tests. Manually verified against the
uitest fixture: 15 devices → 14 `UNPROTECTED` + 1 `UNKNOWN` — the first real
number for the `D1` BackBox-replacement decision.

Prior: `backup_recovery_architecture` — **DESIGN FROZEN** (2026-08-30).
ARCHITECTURE movement, no code. Rebases the deferred `original 0.6.0B`
native-backup milestone. Driver: BackBox is not being renewed in 2027.
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` (three-plane model,
per-vendor analysis, phasing `RB.0`–`RB.6`, seven open decisions `D1`–`D7`) +
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` (frozen shapes, the 10/14-point
command gate entries — drafts for review, not approvals — retention, twelve
security invariants). Central boundary: configuration evidence is deliberately
redacted (`secrets_redacted: True`) and therefore **non-restorable by
design** — today's Configuration module makes it easy to assume otherwise.

Prior: `deploy_persistent_secret_material — persistent
runtime volume contract` (DEV.2.2) — **AUTOMATED_VALIDATED** (2026-08-30, this
session). `data/.support_hmac.key` persistence across a container restart was
already structurally correct via `runtime_paths.data_root`; new
`utils/persistent_secret_material.py` + `main.py --persistent-secret-material-check`
make that contract explicit and offline-checkable (value-free, reuses
`utils.cp_ssh_trust` / `utils.pan_tls_trust` preflight code verbatim). New
`docker-compose.prod.yml` overlay mounts `deploy/secrets/known_hosts` +
`pan-ca-bundle.pem` read-only and sets `SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1`
/ `SECURITYEXPERT_PAN_CA_BUNDLE`, moving CP/PAN trust from opt-in to
mounted-and-required on the server while `docker-compose.yml` keeps
compatibility mode as the base default. `docs/history/phase/DEV2_2_PERSISTENT_SECRET_MATERIAL.md`;
`project/build_history.json` entry `deploy_persistent_secret_material`.

Last landed on the parallel `main`-side session's branch:
`compliance_trend_reconstruction — 0.7.7 Compliance trend retro-fill` —
**AUTOMATED_VALIDATED** (2026-08-30). Follow-up to `0.7.5`'s deliberate
no-backfill decision. Feasibility check found most of
`build_compliance_posture`'s inputs (alignment, CP config,
assignment/waiver policy, CE.1 checks) are not versioned per historical CAS
snapshot — put the scope trade-off to the product owner directly, who chose
narrow/labeled reconstruction over dropping the build or a broader
unlabeled approximation. New `utils/compliance_trend_reconstruction.py`
mines stored PAN effective-running snapshots, time-clusters them into
synthetic checkpoints (CAS carries no `run_id`), and evaluates the ten
deterministic `DEFAULT_RULE_PACK` baseline controls per entity through the
exact same live evaluator dispatch a real checkpoint uses. Every record is
stamped `reconstructed: true` /
`reconstruction_scope: "pan_baseline_rule_pack_only"` and the trend delta
never uses one as `prev`. New offline `main.py --compliance-trend-reconstruct`
maintenance mode (no network, no credentials — merged into this branch's
`main.py` alongside `--recovery-collect`/`--recovery-validate`, all three
cross-guarded against each other during conflict resolution).
`project/build_history.json` entry `compliance_trend_reconstruction`;
contract + impl record `docs/history/phase/0_7_7_COMPLIANCE_TREND_RECONSTRUCTION.md`.

Landed just before it, on a separate session/branch
(`claude/cp-device-interaction-markdown-ret13v`, merged to `main` in
`eb6cd81`, now folded into this branch by this merge): `DEV.3.2 —
distributed per-endpoint lock` (`distributed_endpoint_lock`) —
**AUTOMATED_VALIDATED** (2026-08-30). `CollectionCoordinator` now delegates
to a `CoordinatorBackend`; the new `PostgresCoordinatorBackend`
(`SECURITYEXPERT_COORDINATOR_BACKEND=postgres`) gives the single-process
per-endpoint lock and per-vendor budget a cross-process equivalent via
session-level `pg_advisory_lock`, opt-in and off by default. Real
cross-process exclusion, crash reclamation, and preflight pooler-rejection
were each verified against an actual local PostgreSQL 16 instance (real
subprocess `SIGKILL`, a real `pgbouncer` in transaction mode).
`docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`. That same
branch also corrected a standing documentation-staleness bug: the CP
device-interaction-safety audit (P0) actually closed 2026-08-25 (with its
`collection_execution_coordinator` follow-on REAL_ENV_VALIDATED 2026-08-27),
but `CURRENT_STATE.md`, the old `AI_HANDOVER.md`, `docs/ARCHITECTURE.md` and
`docs/design/COMPLIANCE_CHECK_ENGINE.md` all kept citing it as an open P0
blocker — see "Standing priorities and blockers" below, now corrected.

Prior: `immutable_store_permission — evidence-store snapshot-publish retry`
— **AUTOMATED_VALIDATED** (2026-08-30). `ConfigEvidenceStore._write_snapshot`'s
directory-publish `os.replace(tmp_dir, final_dir)` now retries on transient
lock the same way `_ensure_blob`'s blob write already did
(`_replace_with_retry`, 3 attempts, 0.1s exponential backoff) — closes the
standing P1 intermittent `PermissionError`. `project/build_history.json`
entry `immutable_store_permission`.

Twelve further predecessor builds this cycle (all landed on `origin/main` between
`671fd6c` and `101f75b`, detail in `project/build_history.json`, newest
first):

- `linux_container_image` (`101f75b`, `DEV.3.1`) — Linux worker image +
  Compose: `python:3.12-slim` worker (idle by default), `docker-compose.yml`
  pairing it with `nginx:1.27-alpine` over a shared loopback-only volume. No
  collector/transport/retry/concurrency semantic change.
- `pytest_feature_area_markers` (`5b49aa6`) — 7 pytest markers
  (`inventory`/`configuration`/`compliance`/`discovery`/`render`/
  `runtime_platform`/`security`) so `pytest -m <area>` runs a feature slice;
  purely additive, zero test logic touched.
- `inventory_exclusions_management_ui_backend` (`3463b71`) — write-path
  backend only (`add_exclusion`/`restore_exclusion` + fail-closed audit
  ledger), deliberately not wired into any HTTP-reachable surface pending
  DEPLOY.1A auth. **Stays `in_progress`** in `project/backlog.json` by
  design — the UI and OIDC/RBAC wiring are still owed.
- `playwright_render_harness_fallback` (`996aeca`) — `tools/render-harness/
  check_render_playwright.py`, a real-Chromium alternative to the bun+
  happy-dom DOM-execution check for when that toolchain's script-eval shim
  breaks against a newer bun/happy-dom pairing (as it does in this sandbox —
  used directly by this session's own 0.7.7 validation).
- `dev_python_env_tooling_friction` fix (`a593761`) — POSIX runtime-root
  default + `pytest_one_shot.ps1` interpreter pin.
- `uitest-fixture discovery_ui fix` (`2a18a3d`) — `discovery_ui.json` fixture
  corrected to match the real builder shape.
- `html_render_performance` (`e761c9d`) — opt-in stage timing + measured
  profiling report.
- `overview_device_lifecycle_enrichment` (`6dd82b7`) — fleet-composition
  card, increment 1.
- `inventory_exclusions_ui` (`ab5a9a5`) — read-only Exclusions module, phase 1.
- `cp-identity-edges` review (`3e8af0e`) — CP identity-gate edge-case review,
  no defect found.
- `cp-unknown-platform` (`8b3fc28`) — CP platform classification propagated
  into discovery lifecycle.
- `cp-ha-runtime` (`7e25391`) — per-VS HA role probe + explicit direct-Clish
  capability-gap signal.
- `immutable_store_permission` (`cb2f6f5`) — evidence-store
  snapshot-directory publish retry (this was "not yet pushed" in the prior
  version of this file; it is pushed and landed as of this checkpoint).

Predecessors before that (all AUTOMATED_VALIDATED 2026-08-30; detail in
`project/build_history.json`):

- `0.7.6a — Render harness + uitest topology matrix` —
  `docs/history/phase/0_7_6_RENDER_HARNESS.md` §4. `tests/fixtures/uitest/`
  expanded to a full topology matrix (CP standalone/ClusterXL/VSX
  host+cluster/UNAVAILABLE gateway; PAN single/HA/multi-vsys/multi-vsys HA);
  new `test_all_topologies_present`.
- `0.7.6 — HTML render harness` — `docs/history/phase/0_7_6_RENDER_HARNESS.md`.
  `tools/render-harness/check-render.mjs` (bun + happy-dom) parse-checks the
  inline `<script>`, clicks every nav module + tab, asserts no console errors.
- `0.7.5 — Compliance trend layer` — `docs/history/phase/0_7_5_COMPLIANCE_TREND.md`.
  Append-only ledger `data/state/compliance_history.json`;
  `compliance_overview.history[]` + `.trend`; deliberately **no backfill**
  (closed by `0.7.7` above).
- `0.7.4a — HTML export render hotfix (P0)` — see header. **REAL_ENV_VALIDATED.**
- `0.7.4 — framework_mappings: Requirement-Level Coverage` —
  `docs/history/phase/0_7_4_FRAMEWORK_REQUIREMENTS.md`.

Earlier 0.7.x / 0.6.x predecessors: see `project/build_history.json` /
`docs/history/INDEX.md` for the full timeline (0.7.3 CE.1 check engine, 0.7.2
compliance follow-ups, 0.7.1b assignment/waivers, 0.7.1a control catalog,
0.7.0 crypto-agility/PQC).

**Deferred:** a signed / user-authored framework pack (custom frameworks + a
UI mapping editor) — `DEPLOY.1A`-gated. CP-side trend reconstruction —
blocked on a structured CP config projection existing at all (CP currently
stores only redacted Gaia text; see `0_7_7` §2/§6).

**Product trajectory (owner, 2026-08-29):** the end-state is a **write-capable
device administration platform**; read-only now is a staging phase. Every
VERIFY-plane design must keep a future enforce/remediate capability additive.

---

## Next builds (frozen contracts)

- `DEPLOY.1 — Ubuntu + Docker Server Migration & Git Repository Foundation` —
  **CONTRACT_FROZEN** (2026-08-27). No runtime behavior change before server
  arrival. `DEV.3.1` (this cycle) is the first container-migration slice
  under this contract; the OIDC viewer boundary, evidence egress policy, CP
  strict host-key R2 validation and PAN TLS corporate-CA validation gates are
  still owed on server arrival.
  Handover: `docs/history/handover/DEPLOY_1_CONTRACT_FREEZE_HANDOVER_2026_08_27.md`
- After the engineering-readiness checkpoint, product architecture proceeds
  toward `0.6.1C` follow-ups already validated in the 0.6.x track.
- `OP.x — Controlled Failover` (new track, OPERATE theme): design frozen in
  `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`. Write-free parts (OP.0 HA
  readiness assessment + SCC dashboard, OP.1 dry-run plan compiler) are
  buildable post-`DEPLOY.1`; OP.2 controlled execution is hard-gated (see the
  doc's §10 and `roadmap_notes`).

---

## Standing priorities and blockers

1. **CP device-interaction-safety audit (P0)** — CLOSED (`backlog.json`
   `cp_device_interaction_safety`, AUTOMATED_VALIDATED 2026-08-25;
   `collection_execution_coordinator` REAL_ENV_VALIDATED 2026-08-27). This
   line stayed stale here after both closed — corrected 2026-08-30. Any
   recurring-scheduling / concurrency-budget-increase build still needs its
   own real-environment evidence (not a reopened audit).
   The single-process coordinator's admission model now has a cross-process
   equivalent: `distributed_endpoint_lock` (P0) reached AUTOMATED_VALIDATED
   2026-08-30 (`docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`) —
   `SECURITYEXPERT_COORDINATOR_BACKEND=postgres` opts a `CollectionCoordinator`
   into session-level Postgres advisory locks for cross-process endpoint
   exclusion and budget admission; default (`memory`) is unchanged. Real
   multi-container-against-a-real-MDS evidence remains owed before DONE —
   server-blocked (DEPLOY.1, external). The CAS metadata index / run
   manifests / last-known-good half is split out as
   `distributed_evidence_store_migration` (P0, `planned`, its own contract).
2. The admission coordinator concurrency budget stays at 1 per vendor pending
   its own real-environment evidence — unaffected by the above.
3. DEPLOY.1 gates are blocked on server availability (external, ~1 week).
4. Corporate Git push/merge remains **human-controlled**.
5. `inventory_exclusions_management_ui_backend` stays `in_progress` by design
   — do not wire its write functions into any HTTP-reachable surface before
   DEPLOY.1A's OIDC/RBAC boundary exists.

## Known xfails

- VSX network canonicalization.
- PAN default-route classification.

(Both were converted to passing regressions in 0.6.6A; reconfirm on the next
full regression run.)

## Automated test baseline

```
788 passed / 3 skipped / 2 failed (2026-08-31, with a live PostgreSQL 16
available; 763 passed / 11 skipped / 2 failed without one)
The 2 failures are pre-existing and unrelated to any build in this cycle:
  tests/test_phase0_6_1c_discovery_capability_ui.py::
    test_run_html_export_embeds_discovery_payload_without_leftover_placeholder
  tests/test_phase0_7_5_compliance_trend.py::test_checkpoint_render_appends_one_record
Repository privacy gate: PASS / 0 on a clean checkout. Locally it flags the
gitignored `data/` + `logs/` + `data/.support_hmac.key` that a test run
creates — delete them before running the gate.
```

Run one-shot and read from file (see `docs/AI_DEVELOPMENT_PROTOCOL.md`):
`py -m pytest -q > pytest_result.log 2>&1`

Render harness: `bun tools/render-harness/check-render.mjs <index.html>` is
the primary check; when the bun+happy-dom `window.eval` shim breaks against
the installed bun version (observed in the cloud sandbox this session), fall
back to `python tools/render-harness/check_render_playwright.py <index.html>`
(real Chromium via Playwright — `playwright_render_harness_fallback` build).
Both are wired into `tests/test_html_render_harness.py`.

---

## Engineering foundation completed before DEV.1

`DEV.0` repository readiness is complete except the intentionally deferred
pre-server storage checkpoint:

- `DEV.0.1` runtime management endpoint decoupling — DONE / real-env validated.
- `DEV.0.2` repository sanitization — DONE.
- `DEV.0.3A/B/B.1` runtime path foundation + artifact migration + direct-SSH
  closure — DONE / real-env validated.
- `DEV.0.3C` History/CAS runtime boundary — DEFERRED / pre-server; not a
  Corporate Git blocker. (Config-evidence CAS still lives at repo-root
  `data/configs` — `utils/config_evidence.py`'s `CONFIG_ROOT` default — not
  under `RuntimeRoot`; the compliance-trend ledger already lives under
  `RuntimeRoot`, so this deferral is CAS-specific, not repo-wide.)
- `DEV.0.4 / 0.4.1` local repository privacy gate + runtime inventory exclusion
  policy — DONE; clean candidate, 0 findings.
- `DEV.0.5A/B/B.1/B.2` authentication boundary + canonical config + repository-wide
  DLP closure — DONE.

## Copilot audit follow-up debt

- Environment authentication overrides remain explicit operational compatibility
  paths; do not remove implicitly.
- PAN authentication transport behavior is not fully converged across old/new
  paths; track under explicit security hardening.
- Production CP SSH host-key trust and PAN TLS corporate-CA trust remain
  production gates.
