# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal.

---

## 1. Snapshot

- Date: 2026-09-06. Branch `build/m5-collector-target-selection-seam` from
  `main` at `88a8d1610609f96996aca29dc9eeabd9261029c9` (`M4`, PR #93,
  **merged**). PR open for this branch; **not merged**.
- Build: `collector_target_selection_seam` (`M5`) — **AUTOMATED_VALIDATED**,
  `cp-config` seam only.
- Promoted OP.0d's already-validated `--cp-config-targets` selector into the
  shared `utils.collection_executor.workflow_argv()` argv seam (CON.2 C2-2).
- Contract: `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §9/§12/§12.1
  (`AC-TGT-3`/`AC-TGT-4`/`AC-TGT-5`), companion
  `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12/§12.1.

## 2. What this session did

- **Baseline verified, not corrected**: local `main` already equalled
  `origin/main` at the expected `88a8d161...`, PR #93 (`M4`) already merged,
  tracked tree clean. No fast-forward needed.
- **`utils/collection_executor.py::workflow_argv()`**: `cp-config` now
  appends `--cp-config-targets <comma-joined>` when a non-empty `targets` is
  passed, preserving requested order and opaque `entity_id` spelling exactly
  (identity law — no cast/strip/pad/normalize). Target-free `cp-config` argv
  is byte-identical to before.
- **New `UnsupportedTargetSelectionError(ValueError)`**: any workflow with no
  target-selection seam (`cp`, `checkpoint`, `vsx`, `pan-config`) now raises
  this, inside argv construction, if given a non-empty `targets` — before
  `main.main()` is ever called, in both the scheduler
  (`application/workflows/maintenance.py::_scheduler_workflow_argv`) and the
  console runner (`console/runner.py::_build_argv`). `recovery-pan` /
  `recovery-cp` keep their pre-existing pass-through behaviour byte-for-byte.
- **No other file changed.** `console/registry.py::JOB_REGISTRY` untouched —
  `config_refresh_cp.target_mode` stays `"none"` (M6's job). No registry
  target resolution, no device contact, no admission/concurrency/storage
  change.
- **Added `tests/test_m5_collector_target_selection_seam.py`** — 22 focused
  tests (target-free/targeted argv shape, opaque-id preservation, CLI
  round-trip, scheduler/console parity, fail-closed refusal before `main()`
  for every non-seamed workflow, empty-targets-is-the-only-plane-wide-spelling,
  recovery-* unaffected, `JOB_REGISTRY.target_mode` unchanged, structural
  no-new-command/no-concurrency-change checks).
- **Project-state rotation**: `project/build_history.json` (new `M5` record,
  newest-first), `project/roadmap.json` (`now`→`M5`/`automated_validated`,
  `next`→`M6` stub), `project/backlog.json`
  (`pcp_collector_target_selection_seams`: `planned`→`in_progress`, one CP
  seam closed, VSX/CP-inventory/pan-config still plane-wide),
  `docs/history/INDEX.md` regenerated. `CURRENT_STATE.md`'s stale
  post-merge-`M4` wording (still describing PR #93 as open) reconciled as
  part of this same rotating-state update, per session instruction — not a
  separate movement.

## 3. Exact next action

**Product Owner review of the open `M5` PR, then merge decision.** Merge is
not authorized by the session that opened it.

`M6` (`registry_keyed_job_targets`) is **next, not started, not
authorized** — it needs its own go-ahead. It resolves console-submitted
`device_id` targets against the `PCP.1` Device Registry at admission and
again immediately before execution, and is the movement that changes
`JOB_REGISTRY['config_refresh_cp'].target_mode` from `"none"` to
`"entity_ids"` — M5 deliberately left that field untouched.

**Still outstanding from `M3`:** the Claude-side `nexus-decision-council` was
never stood up. Not required for `M5` (deterministic implementation against
an already-frozen contract, per this session's explicit instruction) but
remains a prerequisite for the next *architecture* movement.

**Pre-existing, unowned, explicitly out of scope here:** the Panorama
test-residue hygiene issue (full-suite runs leave `data/`/`logs/` in the
working tree, failing the working-directory privacy gate until removed) —
not fixed in this session per its explicit boundary.

## 4. Test delta

- **New:** `tests/test_m5_collector_target_selection_seam.py` — 22 passed.
- **Affected suites, combined 110 passed / 0 failed:**
  `tests/test_op0d_deterministic_target_selection.py` (re-exercises the
  underlying OP.0d selector, unmodified here),
  `tests/test_con2_console_job_engine.py`,
  `tests/test_rb2_recovery_collect.py`,
  `tests/test_architecture_convergence.py` (20 passed),
  `tests/test_application_package.py`.
- `metadata_warnings == []`; build-history index `--check` clean (after
  regeneration); `git diff --check` clean.
- **Repository privacy gate:** `PASS`, 0 findings — `data/`/`logs/` (runtime
  residue from the focused-suite runs) removed from the working tree first,
  per `AI_START_HERE.md`'s documented gate procedure.
- **No full local suite** (blast radius bounded to one shared argv-
  construction function and its two existing call sites, all covered by the
  affected-suite run above). **No GitHub full regression, no
  `workflow_dispatch`, no device contact, no merge.**

## 5. Risks / notes forward

- **Automated tests do not prove production readiness or real-environment
  validation.** No device was contacted; the underlying OP.0d collector-side
  fail-closed behaviour (unknown/ambiguous/empty `entity_id`, contact only
  requested targets) was validated previously and is unmodified here.
- **Only one collector has a seam.** CP inventory (`cp`), VSX and `pan-config`
  remain honestly plane-wide by design (`AC-TGT-5`) — a second seam is its
  own future movement, not implied by this one landing.
- **Console cannot submit a targeted `cp-config` job yet** —
  `JOB_REGISTRY['config_refresh_cp'].target_mode` stays `"none"` until `M6`.
  Only a scheduler-policy `targets: [...]` entry or a direct
  `workflow_argv()`/CLI call can exercise the new seam today.
- **Admission coordinator, canonical endpoint lock and the vendor
  concurrency budget of 1 are unchanged** — this movement touches argv
  construction only.
