# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.6.6B — Compliance Rule-Pack Transition Foundation` — AUTOMATED_VALIDATED
- Engineering baseline: `DEV.1` complete; `DEV.2.1` (non-interactive runtime config) — AUTOMATED_VALIDATED
- Date: 2026-08-28
- `main` is pushed to `origin/main` (all merges below are on `origin`).
- Test deps installed for Python 3.12 (`--user`): `pytest`, `pytest-xdist`,
  `lxml`, `paramiko`, `requests`. `py` on this machine defaults to 3.14 (no
  deps) — use `py -V:3.12` or set `PY_PYTHON=3.12`.
- Full suite: `py -m pytest -q -n auto --dist worksteal` → **440 passed,
  3 skipped, 0 failed** (~35s). `--repository-privacy-check` → PASS / 0.

## 2. Recent builds (all on `main`)

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

## 3. NOT YET DONE — real-environment / on-hardware validation

**The user does not have the server yet and wants to run the app on their
laptop to confirm it works.** Automated tests are green but nothing since the
0.6.1x builds has been exercised end-to-end.

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
- `utils/compliance_posture._evaluate_timezone_control` is defined but not
  wired into any control or dispatch (pre-existing dead code, left as-is).
