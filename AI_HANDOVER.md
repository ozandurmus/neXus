# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.6.6A` — AUTOMATED_VALIDATED
- Engineering baseline: `DEV.1` complete; `DEV.2.1` merged (AUTOMATED_VALIDATED)
- Date: 2026-08-28
- Local `main` is **4 branches ahead of `origin/main`** and **not pushed**.
- Test deps installed for Python 3.12 (`--user`): `pytest`, `pytest-xdist`,
  `lxml`, `paramiko`, `requests`. `py` on this machine defaults to 3.14 (no
  deps) — use `py -V:3.12` or set `PY_PYTHON=3.12`.

## 2. Last session

Merged four local feature branches into `main` (no push — that is the human's):

1. **`chore/ai-onboarding-restructure`** — hot-path docs (`AI_START_HERE.md`,
   `docs/ARCHITECTURE.md`, this file), ~89 historical docs archived under
   `docs/history/**`, `CURRENT_STATE.md` trimmed, governance consolidated to
   `AGENTS.md` + thin shims, English working language, `build_history.json` v2,
   RFC 5737 fix to the 0.6.6A CIDR example. Root `.md` 72 → 8.
2. **`chore/test-parallelism`** — `pytest -n auto --dist worksteal`
   (~35s vs ~110s serial); `requirements-dev.txt`; `scripts/pytest_one_shot.ps1`
   parallel-by-default with `-Serial`.
3. **`chore/deploy-containerization-roadmap`** — `engineering_tracks` DEV.2/3/4
   step breakdown + 5 backlog items for the container/server readiness work.
4. **`feature/dev-2-1-noninteractive-config`** — DEV.2.1: `_build_runtime_config`
   sources principal / secret / CP-MDS / Panorama endpoints from
   `<VAR>_FILE` > `<VAR>` > TTY prompt; non-TTY + missing required value →
   `RuntimeConfigError` (mapped to `parser.error`, clean exit 2) before any
   collector import. New `utils/runtime_config_source.py`, `.env.example`,
   12 tests. Interactive local runs unchanged.

- Post-merge fixups on `main`: regenerated `docs/history/INDEX.md` (38 rows),
  moved the DEV.2.1 contract to `docs/history/phase/`, rewrote this file.
- Evidence on merged `main`:
  - `py -m pytest -q -n auto --dist worksteal` → **433 passed, 3 skipped,
    0 failed** (34.9s).
  - `py -B main.py --repository-privacy-check` → **PASS, 0 findings**.
  - `utils.project_plan.build_project_plan_payload()` → `metadata_warnings: []`.
- Merges were `--no-ff`, zero conflicts (incl. `build_history.json` v2 +
  DEV.2.1 record).

## 3. Next session — exact starting point

1. **Human: push `main`** to `origin` (git push is human-controlled). Nothing is
   pushed yet.
2. Then the next product build: **`0.6.6B — Compliance Rule-Pack Transition
   Foundation`**. Contract already frozen in
   `docs/history/phase/PHASE0_6_6B_COMPLIANCE_RULE_PACK_TRANSITION.md`; backlog
   `compliance_posture_rulepack_transition` (P1). Wrap the existing 10
   deterministic CP/PAN controls in `utils/compliance_posture.py` with a static
   versioned rule-pack boundary. Offline, no collector/network/CAS/UI-semantic
   change. Automated-validated only.
   - Movement: `READ_ONLY_AUDIT` → `ARCHITECTURE` → `IMPLEMENTATION`.
   - Reasoning: normal.
   - Read the frozen contract doc first.
3. Container/server work (`DEV.2.2`+, `DEV.3.*`) is gated on server arrival;
   see `roadmap.json` `engineering_tracks` and the 5 new backlog items.

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit remains P0 (blocks any scheduling /
  concurrency increase).
- `_realenv_*.py` / `_write_r0x_policy.py` stay at repo root (imported by
  `tests/` and validation runbooks). Moving them is a separate code change.
- `.py` source comments still cite `PHASE0_*.md` by bare filename; the files keep
  their names under `docs/history/phase/`.
- `scripts/pytest_one_shot.ps1` calls `py`; on this machine that resolves to
  3.14 without deps. Tracked by backlog `dev_python_env_tooling_friction`.
- The CAS / support-key path writes `data/` and `logs/` into the repo dir during
  a test run (`BASE_DIR/data`; `DEV.0.3C` History/CAS boundary deferred).
  Gitignored, not tracked.
