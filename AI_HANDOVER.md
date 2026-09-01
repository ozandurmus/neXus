# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-01. Branch `main`, **uncommitted** — 36 changed paths.
- Product baseline `0.7.7`; engineering `DEV.3.3` — both unchanged.
- `CON.1` DONE; `CON.2` AUTOMATED_VALIDATED (real-env owed); `OP.0a`
  AUTOMATED_VALIDATED; `RB.3b` blocked on hardware — all unchanged.
- New this session: **`architecture_convergence` AUTOMATED_VALIDATED** —
  `ARCHITECTURE` movement. No device contact, no new device command, no
  dependency change.

## 2. What changed this session

**The finding that mattered most:** the two "long-standing order-dependent test
failures" were neither order-dependent nor flaky. `scripts/render_uitest.py`
rebound three `utils.html_export` payload builders with bare module assignment
and never restored them. `tests/test_frontend_rendering_boundary.py` and
`tests/test_html_render_harness.py` call `render()` in-process, so every later
`run_html_export()` in that worker returned uitest **fixture** payloads — which
is why `test_..._embeds_discovery_payload_...` read `cp-edge-01` instead of its
own `DEV-Z`, and why `test_checkpoint_render_appends_one_record` saw a populated
configuration payload and wrote the ledger. Fixed with a `try/finally` restore.
Serial and `-n auto` now agree at **988 / 27 / 0**.

- `utils/action_taxonomy.py` (new) — the five classes. `console/registry.py`
  derives `action_class` from it; `console/app.py` + `console/runner.py` gate on
  it and name the refusing class. `command_class` stays on the wire and in
  durable records, so no data migration.
- `utils/project_plan.py` — six cross-authority rules added to
  `_metadata_warnings`. The pre-existing `metadata_warnings == []` assertion was
  green while three files each named a different current build; it is now a real
  gate. Repaired: `roadmap.json` (NOW/NEXT/AFTER/BLOCKED/DEFERRED rebuilt,
  `current_build`, `current_track`, four track statuses), `feature_registry.json`
  (3), `build_history.json` (1 + the `architecture_convergence` record),
  `backlog.json` (1).
- `scripts/build_history_index.py` (new) — `docs/history/INDEX.md` claimed to be
  generated and was hand-maintained, stale at `0.7.4`. Now really generated;
  `--check` gates it.
- Read-only claim removed from `README.md`, `AI_START_HERE.md`,
  `docs/ARCHITECTURE.md` ("Read-only invariant"),
  `docs/AI_DEVELOPMENT_PROTOCOL.md` ("No new write command"). `AI_START_HERE.md`
  also had a test baseline stale by ~750 tests and a CLI table missing 15 flags.
- `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 (new) — stage map plus the
  nine-point safety contract `OP.2` must satisfy.
- `CURRENT_STATE.md` 764 → 179 lines; `.github/workflows/validation.yml` (new,
  first CI); 12 superseded handover snapshots deleted.
- `tests/test_architecture_convergence.py` (new, 13 tests).

**Deliberately not done:** the 92 `docs/history/phase/` documents were **not**
bulk-deleted. `build_history.json` carries 94 doc links, 72 into that directory,
0 broken — deleting them would break the exact mechanism `AGENTS.md` designates
for reaching archived detail. They already cost zero context because the reading
order excludes them. Only the superseded *rotating* handovers went.

## 3. Exact next action

**`OP.0c` — the §9 failover readiness UI module.** Read-only, no new device
command, buildable now against the `ha_readiness.json` `OP.0a` produces. It is
NEXT because it is simultaneously the next Operator Console surface and the next
step on the failover path.

Two things it must get right: carry `OP.0a`'s framing into the UI
(`INSUFFICIENT_EVIDENCE` means "not asked yet", not "unhealthy" — without that
line an empty-looking dashboard reads as a broken feature), and budget for the
render harness plus a `tests/fixtures/uitest/` growth step, which it triggers.

Independent alternatives, none blocking the others: `OP.0b` gate review (a
product-owner/security call, not engineering), `CON.2`'s real-environment run
(no new code), `RB.3b`'s watched hardware run.

## 4. Test delta

Full suite **988 passed / 27 skipped / 0 failed**, confirmed **both serially and
under `-n auto --dist worksteal`** (`pytest_result.log` is the serial run).
From 971/27/**2** serial. `+13` `tests/test_architecture_convergence.py`,
`+1` isolation regression in `test_frontend_rendering_boundary.py`, `+1` class
invariant in `test_con2_console_job_engine.py`; `-2` failures.

Privacy gate **PASS / 0**, 415 files, clean checkout. `INDEX.md --check` green.
`git diff --check` clean. Render harness not triggered (no `templates/`,
`static/` or payload-builder change) and green in the suite.

**Two test assertions were changed, both deliberately, neither weakened:**
`test_..._project_plan_payload_is_data_driven` pinned `current_track == "0.6.x"`
— a *test* acting as a fourth current-state authority; it now asserts the
invariant (the track is declared) instead. The CON.2 refusal-code assertions now
expect `recovery_write_not_console_submittable` rather than the catch-all
`operational_write_not_enabled`, because naming the class is the point.

## 5. New risks / debt

- **CLASS 2 is empty and must stay empty.** `test_no_console_job_type_is_class_2_or_above`
  and the `utils/failover/` absence test are the guards. If either fails, the
  `OP.2` gate is what needs revisiting — not the assertion.
- **Tests still write into the repository-root `data/`.** Gitignored, so not a
  leak, but it is shared mutable state across the suite and it is why the
  privacy gate needs a manual `rm -rf data logs` first. Known debt, not fixed
  here — the fix is passing `data_root` at every `run_html_export` call site.
- **The CI workflow has never run.** It is written against this repository's own
  local commands and its Python 3.12 baseline, but GitHub Actions has not
  executed it; treat the first run as validation, not as a regression.
- Carried over: `tests/test_con1_*` / `test_con2_*` have no top-level
  FastAPI/uvicorn skip guard. `C-D4`…`C-D8` remain open.

## 6. Continue or fresh chat

**Fresh chat.** This build is closed and `OP.0c` is independent of its context.
`AI_START_HERE.md` → `CURRENT_STATE.md` → this file → the `OP.0a` contract doc
is sufficient for a cold start.

## 7. main.py / UI effect

**No CLI flag, mode or exit-code path changed. No UI change, and that is
intended** — this build touches no `templates/`, `static/` or payload builder,
so the exported static report and the operator console render byte-identically.
The one observable behaviour change is the console's refusal payload for a
non-class-0 job type: 409 with `{"error": "recovery_write_not_console_submittable",
"action_class": "recovery-write"}` instead of `{"error":
"operational_write_not_enabled"}`, and `/api/job-types` rows now carry
`action_class` + `action_class_level`. The existing UI renders `blocked_reason`
as an opaque tooltip, so nothing visible changes there either.
