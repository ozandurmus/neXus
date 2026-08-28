# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.1a — Compliance Control Catalog & Framework Grouping` — AUTOMATED_VALIDATED
- Engineering baseline: `DEV.1` complete; `DEV.2.1` (non-interactive runtime config) — AUTOMATED_VALIDATED
- Date: 2026-08-29
- `main` is pushed to `origin/main` (all merges below are on `origin`).
- Test deps installed for Python 3.12 (`--user`): `pytest`, `pytest-xdist`,
  `lxml`, `paramiko`, `requests`. `py` on this machine defaults to 3.14 (no
  deps) — use `py -V:3.12` or set `PY_PYTHON=3.12`.
- Full suite: `py -m pytest -q -n auto --dist worksteal` → **464 passed,
  3 skipped, 0 failed** (~35s). `--repository-privacy-check` → PASS / 0.

## 2. Recent builds (all on `main`)

- **`0.7.1a` — Compliance Control Catalog & Framework Grouping** — the ten
  deterministic compliance controls move into a versioned declarative catalog
  (`utils/compliance_catalog.py`, `CATALOG_VERSION = "0.7.1a"`) *verbatim* (same
  ids / areas / `evidence_fields` / evaluators / outcomes) with `severity`
  (5-level, weighted), `rationale` and real per-framework `frameworks` (CIS /
  PCI-DSS / BDDK membership + reference) added. `compliance_rulepack.BASELINE_CONTROLS`
  is now a derived 5-key view (`catalog_baseline_controls()`); the 0.6.6B rule
  pack + frozen `rule_count == 10` tests are untouched. `compliance_posture._control`
  emits additive `severity` / `rationale` / `frameworks`; `app.js` control card
  gains a severity badge + real per-framework references. Purely additive payload.
  Contract: `docs/builds/0_7_1_COMPLIANCE_ASSIGNMENT.md` (§ Build split, § 8).
- **`0.7.0` — Cryptographic Posture, Crypto-Agility & PQC Readiness** — opens
  the 0.7.x VERIFY track. `utils/crypto_facts.py` + `utils/crypto_rulepack.py`
  (`securityexpert.crypto.cp-pan @ 0.7.0`) + `utils/crypto_posture.py`: IKE/
  IPsec/TLS/cert facts from the *already-stored* PAN XML → weak_algorithm /
  crypto_agility / pqc_readiness findings, additive `__CRYPTO_JSON_PLACEHOLDER__`
  payload, one card in the Compliance module. No new collector. Contract:
  `docs/history/phase/0_7_x_CRYPTO_AGILITY_PQC.md`.
- **SESSION START now opens with a Turkish `PROJE ÖZETİ`** for the non-developer
  product owner (`AGENTS.md`, `docs/AI_DEVELOPMENT_PROTOCOL.md`,
  `.github/copilot-instructions.md`).
- **code-quality cleanup** — removed dead code (`enrich_cluster_topology`
  post-loop, `_evaluate_timezone_control`), tightened 3 bare `except:`,
  English-ised 3 vsx_runner comments. No behavior change.
- **Failover Engine design** — `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`
  (design only, no code). New `OP.x — Controlled Failover` roadmap track +
  3 features + 3 backlog items (`failover_readiness_assessment` OP.0,
  `failover_plan_compiler` OP.1, `failover_controlled_execution` OP.2 deferred).
- **`clean_baseline_bootstrap`** — `main.py` `_require_bootstrap(mode,
  output_root)`: `--render-only` / `--cp-config-probe` / `--cp-config-collect` /
  `--only cp|vsx|pan-config` now exit 2 with an actionable "Missing X, produced
  by <cmd>" block before any credential prompt or collector, instead of a deep
  traceback. `--only all` unaffected. Also fixed RFC1918 literals in
  `scripts/render_sample.py` that were failing the privacy gate on `main`.
- **`0.6.6B`** — the ten deterministic CP/PAN compliance controls now execute
  through a static versioned in-repository rule pack
  (`utils/compliance_rulepack.py`, `pack_id securityexpert.baseline.cp-pan @
  0.6.6B`). Additive `rule_pack` traceability; outcomes unchanged;
  platform/fleet controls unrouted. `COMPLIANCE_SCHEMA_VERSION` → `0.6.6B`.
  Contract: `docs/history/phase/0_6_6B_COMPLIANCE_RULE_PACK.md`.
- **`DEV.2.1`** — `_build_runtime_config` sources principal / secret / CP-MDS /
  Panorama endpoints from `<VAR>_FILE` > `<VAR>` > TTY prompt; non-TTY +
  missing required value → clean `SystemExit 2`. `utils/runtime_config_source.py`,
  `.env.example`.
- Repo restructure + test parallelism + DEV.2/3/4 roadmap step breakdown
  (earlier this session).

## 3. Next work

**`0.7.1b` — the next build. Contract already written and reviewed/approved
(`docs/builds/0_7_1_COMPLIANCE_ASSIGNMENT.md` §2c–2e), not yet implemented.**
File-based per-device assignment (`utils/control_assignment.py` +
`data/state/control_assignments.json`, mirroring `inventory_exclusions.py`),
minimal file waivers (`WAIVED` state), `compliance_overview` roll-up in the
compliance payload, Overview `#overviewComplianceSummary` card, Compliance-module
KPI band + framework-readiness cards + framework filter, ~12 enrichment controls
+ a `password_policy` projection section, and `data_root` threaded to
`build_compliance_posture` via `run_html_export` / `main.py`.

Then remaining `0.7.x` VERIFY-track features (`roadmap.json` track `0.7.x`):
`compliance_engine`, `framework_mappings`, `evidence_reporting` further work.
`crypto_agility_pqc` follow-ups (later): dynamic/signed packs, scoring, and the
live `negotiated` crypto-evidence layer (needs a server).

Standing doable-now options if not starting the next 0.7.x contract:
`immutable_store_permission` (P1 bug), `html_render_performance` (P2, profile
via `scripts/render_sample.py`), `inventory_exclusions_ui` /
`overview_device_lifecycle_enrichment` (P1 UI).

**OP.x — Controlled Failover: design done, approval pending.**
`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`. The user is getting sign-off next
week and will source a test CP + PAN cluster. `roadmap.json` → `open_decisions`
holds the 6 items for that review. OP.0 (read-only HA readiness assessment +
SCC dashboard) and OP.1 (dry-run plan compiler) need only a reachable test
cluster — a laptop run suffices; OP.2 (write) is gated on the DEPLOY.1A auth
boundary + the command gate + a signed safety review.

## 3b. NOT YET DONE — real-environment / on-hardware validation

Automated tests are green but nothing since the 0.6.1x builds has been
exercised end-to-end. Backlog `on_hardware_real_env_validation` (P0), blocked
on the corporate laptop.

- **Local render check (no devices needed):** `py -V:3.12 scripts/render_sample.py`
  builds a synthetic `unified.json` and renders `index.html` (path printed) so
  the UI shell + Overview / Network Inventory / Project Plan can be eyeballed on
  a laptop. Configuration / Compliance / Discovery show their correct
  "no evidence collected" empty states — the script does not fabricate config or
  compliance evidence. Verified 2026-08-28: 6 module panels, all placeholders
  replaced, ~385 KB HTML.
- **Real collection run:** needs reachability to an MDS / Panorama and
  credentials — not possible from a bare laptop. Deferred to the server.
  `0.6.6B`'s `rule_pack` fields only populate when a configuration collection
  has run (Compliance is empty in the sample render).
- **DEPLOY.1** (`now_next.next`): server migration, gated on hardware arrival.
  Step breakdown in `roadmap.json` `engineering_tracks` DEV.2/3/4; backlog
  items `noninteractive_runtime_config` (done), `deploy_persistent_secret_material`,
  `linux_container_image`, `distributed_endpoint_lock_and_job_store`,
  `per_vendor_worker_split`.

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit remains P0 (blocks any scheduling /
  concurrency increase).
- `_realenv_*.py` / `_write_r0x_policy.py` stay at repo root (imported by
  `tests/` and validation runbooks).
- `.py` source comments still cite `PHASE0_*.md` by bare filename; files keep
  their names under `docs/history/phase/`.
- `scripts/pytest_one_shot.ps1` calls `py`; on this machine that resolves to
  3.14 without deps (backlog `dev_python_env_tooling_friction`).
- The CAS / support-key path writes `data/` and `logs/` into the repo dir
  during a test run (`BASE_DIR/data`; `DEV.0.3C` deferred). Gitignored.
- `0.7.1a` left `compliance_posture.build_compliance_posture` without a
  `data_root` param — `0.7.1b` adds it (needed for the assignment policy).
