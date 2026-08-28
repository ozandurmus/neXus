# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.6.6A` — AUTOMATED_VALIDATED (2026-08-27)
- Engineering baseline: `DEV.1 — Corporate Git Foundation`
- Date: 2026-08-28
- Next chat: **continue this session** (repository restructure in progress) or
  start fresh once it is committed.

## 2. Last session

- Build / task: AI-onboarding + repository restructure (documentation only).
- Movement type: `DOCS`.
- Completed:
  - Relocated ~89 historical documents out of the root:
    `docs/history/phase/`, `docs/history/validation/`, `docs/history/handover/`,
    plus `docs/`, `docs/reference/`, `docs/history/`.
  - Untracked seven local `pytest_*` / `render_only_*` logs; generalized
    `.gitignore`.
  - Added hot-path docs: `AI_START_HERE.md`, `docs/ARCHITECTURE.md`, this file.
  - Trimmed `CURRENT_STATE.md` to the hot-path essentials.
  - Consolidated governance docs: `AGENTS.md` canonical; `CLAUDE.md` and
    `.github/copilot-instructions.md` reduced to thin tool-specific shims;
    `START_HERE_CLAUDE.md` / `START_HERE_COPILOT.md` removed; working language
    set to English.
  - Restructured `project/build_history.json` to v2 structured records with
    links to the archived agreement/validation docs; added
    `docs/history/INDEX.md`.
- Deliberately preserved: all `.py` behavior, `project/*.json` schema keys read
  by `utils/project_plan.py`, the reusable `.github/prompts/*`, and the test
  baseline.
- Evidence: `pytest` one-shot baseline unchanged; `--repository-privacy-check`
  clean. (Fill exact numbers at close.)
- `main` merge decision: **blocked** until the human reviews the restructure
  branch `chore/ai-onboarding-restructure`.

## 3. Next session — exact starting point

- Task: the human intends to move on to a **code change**. Pull the concrete
  target from `project/roadmap.json` (`current_build`) and `project/backlog.json`
  (open `P0`/`P1` items), then confirm scope with the human before editing.
  Current standing priorities from `CURRENT_STATE.md`:
  - CP device-interaction-safety audit (P0) — still open, blocks any
    scheduling/concurrency increase.
  - DEPLOY.1 gates on server arrival (P0, external dependency).
  - `0.6.6B — Compliance Rule-Pack Transition Foundation` (planned, next product
    build after 0.6.6A).
- Movement type: `READ_ONLY_AUDIT` → `ARCHITECTURE` → `IMPLEMENTATION`.
- Reasoning level: normal for the audit; escalate for storage/CAS/vendor-semantic
  or safety-audit work.
- Files likely in scope: depends on the chosen task — narrow-search from the
  backlog item's `note`.
- Context intentionally NOT to load: `docs/history/**`, the Continuation Pack.
- Git lane: `feature/*` or `build/*` per `AGENTS.md`; `main` merge is
  human-controlled.
- Real-env validation command: task-dependent; documentation/UI-only work needs
  none.
- `main.py` / UI effect: none from the restructure. State it explicitly for the
  next build.

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit remains P0.
- Known xfails: VSX network canonicalization; PAN default-route classification
  (converted to passing regressions in 0.6.6A — reconfirm on next full run).
- Loose root scripts `_realenv_0_6_5_finalize_report.py`,
  `_realenv_r06_coalesce_probe.py`, `_write_r0x_policy.py` stay at root because
  `tests/` and validation runbooks import/reference them by root path. Moving
  them is a separate code change — logged in `project/backlog.json`.
- `.py` source comments still cite `PHASE0_*.md` by bare filename; the files
  keep their names under `docs/history/phase/`. Not updated (DOCS scope only).
