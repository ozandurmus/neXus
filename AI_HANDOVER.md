# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline `0.7.7`; engineering `DEV.3.3` — both AUTOMATED_VALIDATED,
  unchanged this session. `RB.3b` `in_progress` (hardware-gated), unchanged.
- **This session was `ARCHITECTURE` only: it froze `CON.x` — the Operator
  Console — as `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` plus all five
  phase contracts (`CON.1`…`CON.5`).** No source file touched.
- `codebase_modularization` (frontend) is still contract-frozen and still the
  next implementation task — and is now also `CON.1`'s hard precondition.
- Branch: `claude/dynamic-ui-backup-management-y74cra`.

## 2. What changed this session

**`operator_console_architecture` — ARCHITECTURE, docs + metadata only.**

- New `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`): the decision
  (a second delivery surface, **not** a dynamic report), the intent boundary
  (browser sends a `job_type` from a closed registry plus validated
  `entity_id`s — never a command or argv fragment), a reuse map showing the
  console adds an HTTP boundary, an auth boundary, a job record and one
  provenance value and **no new device path**, a ten-rule security model,
  the phasing, and eight open decisions `C-D1`…`C-D8`.
- Five phase contracts under `docs/history/phase/`: `CON_1_OPERATOR_CONSOLE_READ_ONLY.md`,
  `CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md`,
  `CON_3_CONSOLE_OPERATIONAL_WRITE_ACTIONS.md`,
  `CON_4_CONSOLE_RECOVERY_MODULE.md`, `CON_5_CONSOLE_SCHEDULER_SURFACE.md` —
  each with scope/out-of-scope, design decisions, acceptance criteria, an
  implementation plan, risks, rollback and a definition of done.
- `docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`:
  amendment note in §1 and a new §7 reconciling the console against every
  boundary that document set. **No prohibition is relaxed** — the console is
  not a generic REST wrapper, not part of the viewer, not exposed beyond
  loopback.
- Metadata: new `project/backlog.json` `operator_console` (P1); new `CON.x`
  roadmap track + `upcoming` entry + `C-D1`…`C-D8` in `open_decisions`; four
  new features in `project/feature_registry.json`; `restore_readiness` gained
  its missing `recovery_ui_module` criterion (RB.5 was never actually built,
  so the 0.9.x track percentage corrected downward — a fix, not a regression);
  one `project/build_history.json` record; `CURRENT_STATE.md` "Architecture
  direction" + "Active build".

## 3. Exact next action

**Unchanged: implement `docs/history/phase/CODEBASE_MODULARIZATION_FRONTEND.md`**
(the `static/app.js` split). Read it first — it is the spec. Recommended tier
**`Sonnet 5, normal`**; its own 7-step plan stands. Nothing about the console
changes that build's scope — but it is now a precondition for `CON.1`, so do
not defer it.

**Then, before `CON.1` starts, `C-D1` and `C-D2` must be answered by the
product owner / security** (optional `fastapi`+`uvicorn` dependency; cookieless
per-launch bearer token). Both have a recommendation recorded in the
architecture doc §11 and in `project/roadmap.json` `open_decisions`; neither is
an engineering task.

`CON.1` implementation is then a fresh session against its contract at
`Sonnet 5, normal`.

## 4. Test delta

None — docs and project metadata only, no source file touched. The last
evidence (**888 passed / 23 skipped / 0 failed**) is unaffected and still holds.
`project/*.json` edits were validated by building the Project Plan payload:
zero warnings, `current_track`/`current_build` unchanged, `CON.x` present.
Repository privacy gate re-run on the working tree: **PASS / 0**.

## 5. New risks / debt

- **Scope drift is this track's main risk.** The console is a surface over an
  existing engine. Any phase proposing a new collector, a console-only payload
  shape, or a second orchestration path has left the architecture. `CON.2` AC-2
  exists to make the single-orchestration-path rule structurally checkable —
  if it is ever weakened, the 24 h `operational-write` ledger stops being
  enforceable.
- **Two hard orderings that must not be shortcut:** `CON.1` after
  `codebase_modularization` (it changes `app_bootstrap.js` initialization);
  `CON.3` after `RB.3b` reaches `REAL_ENV_VALIDATED` (a UI must never be the
  first thing to run a device write nobody has run by hand).
- **`C-D1` adds supply-chain surface** (`fastapi`/`uvicorn`). It is scoped as an
  optional extra, absent from the base image and unreachable from every other
  mode — keep it that way.
- **`D1` (the BackBox estate inventory) is still open and is unaffected by any
  of this.** The console improves operability, not vendor coverage; if the
  estate holds non-CP/PAN devices, this product still does not replace BackBox
  for them. `CON.4` `C4-2` deliberately counts uncovered devices rather than
  omitting them so a polished screen cannot hide that gap.

## 6. Continue or fresh chat

**Fresh chat**, for `codebase_modularization` implementation — same convention
the previous contract-freeze session used. Read `AI_START_HERE.md` →
`CURRENT_STATE.md` (hot section) → this file →
`docs/history/phase/CODEBASE_MODULARIZATION_FRONTEND.md` in full before touching
`static/app.js`. The console contracts do **not** need to be read for that
build; read `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` plus the one `CON_*`
contract when the console session starts.

## 7. main.py / UI effect

**None.** This session was documentation and project metadata only; no source
file, template, payload builder or CLI mode changed, so a normal run produces
exactly the report it produced before. The one visible difference is inside the
report's own Project Plan module, which embeds `project/*.json` on every render:
a new `CON.x` track (0%), a new backlog item, four new features, eight new open
decisions, and one new build-history record — content, not shape.
