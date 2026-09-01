# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-01.
- Product baseline `0.7.7`; engineering `DEV.3.3` — both AUTOMATED_VALIDATED,
  unchanged. `RB.3b` `in_progress` (hardware-gated), unchanged. `CON.x`
  ARCHITECTURE FROZEN, unchanged.
- **This session: implemented `codebase_modularization` (backend half)** —
  `docs/history/phase/CODEBASE_MODULARIZATION_BACKEND.md`. `main.py` (2,089
  lines) is now a 47-line thin entry; the frozen `application/` package holds
  everything else. `codebase_modularization` is now `done` in
  `project/backlog.json` for **both** halves.
- Branch: `feature/codebase-modularization-backend`, everything committed
  locally, **not yet merged/pushed** — corporate git push/merge stays
  human-controlled per standing policy, but the user explicitly asked this
  session to merge it. Do that next if you are continuing this session; if
  picking this up fresh, confirm the branch hasn't already been merged before
  redoing that step.

## 2. What changed this session

- `main.py` reduced to imports + re-exports + a one-line `main()` + the
  `__main__` guard.
- New `application/` package: `cli.py` (`build_parser` / `validate_modes` /
  `dispatch` / `run`, Phase A moved verbatim), `services.py` (Phase C runtime
  foundation + the F6 bootstrap/runtime-config helpers +
  `build_collection_services` + the `make_admitted`/`make_runtime_config`
  closure factories), `context.py` (`ApplicationContext` dataclass for the
  ~15 former `main()` locals), `workflows/{maintenance,recovery,checkpoint}.py`
  per the frozen ownership table. Phase E (staged pipeline, `RunContext`
  lifecycle, degraded policy, the `try/except/finally` with
  `cfg.clear_credentials()`) moved as one unit into
  `checkpoint.integration_checkpoint`.
- New `tests/test_application_package.py` — AC-3 (static, subprocess-clean
  `sys.modules` delta for `application.cli`/`services`/`context` and each
  workflow module), AC-5 (runtime: a `--repository-privacy-check` run in a
  clean subprocess loads no vendor module), AC-6 (`main.main`'s signature is
  byte-equal to the frozen string; every F4 name resolves).
- 9 existing test files touched — see §5 for why, and the phase doc's
  "Implementation deviations" for the full reasoning. No test's *behavioral*
  assertion changed; only *where* a source-string check reads from, or *which
  module's* name a monkeypatch targets.
- Metadata: `project/build_history.json` entry `codebase_modularization_backend`;
  `project/backlog.json` `codebase_modularization` → `done`; this file;
  `CURRENT_STATE.md`; the phase doc's Status → `IMPLEMENTED` with a new
  "Implementation deviations" section.

## 3. Exact next action

**Merge `feature/codebase-modularization-backend` to `main`** — the user
explicitly asked for this in the prompt that started this session ("I Want
you tou merge it yourself afterwards"). If not already done: open/merge the PR
(or fast-forward merge locally if no PR was opened), push `main`, delete the
feature branch, and re-verify `git status`/`git log` clean against
`origin/main`, the way prior sessions' merges in this repo close out (see
`project/build_history.json` `RB.3b-impl-step5` for the pattern: branch → PR →
merge → push → verify).

If already merged: nothing else is queued for this specific build. Independent
alternatives: (a) the frontend half's still-owed human real-browser open; (b)
`CON.1`, blocked on product-owner answers to `C-D1`/`C-D2`; (c) a
vendor-collector split, only if a bounded feature actually needs one (this
build deliberately did not start it).

## 4. Test delta

Full suite `py -m pytest -q > pytest_result.log 2>&1`: **896 passed / 26
skipped / 2 failed** on this branch — the same two pre-existing
order-pollution failures noted throughout this repo's history (both pass in
isolation), **zero regressions**; `+9` from
`tests/test_application_package.py` vs the pre-build baseline on this branch
(887/26/2). Privacy gate PASS/0 on a clean checkout (delete `data/`/`logs/`
first — a local test run recreates them and the gate flags its own
gitignored output, the same standing note as always).

AC-4 proof: before/after CLI stdout+exit-code transcript diff, `git worktree`
of `main@c4a7b6f` vs this branch, over `--help` / `--repository-privacy-check`
/ `--storage-analyze` / `--restore-readiness-check` / `--recovery-store-check`
/ `--render-only` / `--recovery-collect` (no `--recovery-vendor`) / 3
`parser.error` collisions (`--apply` alone, `--cp-config-probe --only cp`,
`--scheduler-once --render-only`) — identical exit codes and stdout in every
case (the privacy-check case's raw file-count line differs only because the
after-side tree legitimately contains the new `application/` source files
plus locally-accumulated `__pycache__`/`.pytest_cache`; `Gate: PASS/PASS`,
`Findings: 0/0` in both).

## 5. New risks / debt

- **A real pre-existing gap, surfaced (not caused) by this build**: `main.py`'s
  top-level `from utils.config_storage import ...` was already pulling in
  `lxml` transitively (via `utils/config_evidence.py`) on *every* invocation —
  contradicting `AI_START_HERE.md`'s documented "vendor imports are lazy"
  claim. The original contract's F2 audit believed this boundary already held
  and only the split made it visible to a static check. Closed by making that
  import lazy in `storage_analyze()`/`storage_deduplicate()`
  (`application/workflows/maintenance.py`) — zero output/exit-code change.
  Worth a note if a future audit assumes every maintenance-mode import was
  always lazy; it was not, for this one path, until today.
- **`ApplicationContext` discipline**: kept to the F3 field list
  (`args`/`parser`/`runtime_paths`/`support_bundle_output_root`/`provenance`/
  `admission_run_context`/`services`). A future workflow needing something not
  there is a signal the seam is wrong, not a reason to add a field — see the
  phase doc's "Risks" section, unchanged by implementation.
- **Working tree**: everything is committed on
  `feature/codebase-modularization-backend`; not yet pushed/merged (see §3).

## 6. Continue or fresh chat

**Either works.** If continuing this session, the only queued action is the
merge in §3. If starting fresh: read `AI_START_HERE.md` → `CURRENT_STATE.md`
(hot section) → this file, then check `git branch`/`git log` to see whether
the merge already happened before repeating it.

## 7. main.py / UI effect

**None functional.** No CLI mode, flag, `parser.error` string, exit code,
command string, admission call, payload builder, template markup or CSS
changed. A normal `py .\main.py` run behaves identically to before this
build; only the source layout changed. `main.py` no longer contains the mode
bodies — anything reading them directly must look under `application/`.
