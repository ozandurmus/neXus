# `CON.5` — Console scheduler surface (read-only)

## Status

**CONTRACT FROZEN 2026-08-31**, alongside `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`
(`CON.0`). No source file changed by the session that froze it.

`project/backlog.json` `operator_console` (P1), track `CON.x`.

**Preconditions:** `CON.2` AUTOMATED_VALIDATED; decision `C-D7` resolved
(policy editing stays out of scope). May ship before or after `CON.3`/`CON.4`.

## Objective

Make the scheduler legible. Today the `RuntimeRoot` scheduler policy is a JSON
file, default-disabled, evaluated by `py main.py --scheduler-once`, and the only
way to know what it will do is to read the file and the allowlist in source. The
console shows: what is configured, what is due, when each workflow last ran and
will next run, and — the part that matters most — **which workflows cannot be
scheduled at all, and why**.

That last part is a product feature, not a diagnostic: `"recovery-cp"` is absent
from `ALLOWLISTED_WORKFLOWS` because `D3` approved the CP backup *command* and
explicitly did not approve unattended fleet backup. An operator who cannot see
that will assume the opposite.

## Scope

### In scope

1. `utils/scheduler_ui.py` — payload builder over `load_scheduler_policy`,
   `load_scheduler_state`, `is_workflow_due` and `ALLOWLISTED_WORKFLOWS`.
2. `GET /api/scheduler` in console mode; the same payload embedded in the static
   report if and only if it adds no identity content (`C5-3`).
3. Scheduler presentation inside the existing Discovery module (which already
   owns lifecycle/coordinator/scheduler concerns per the modularization ownership
   table) — not a new module.
4. `tests/test_con5_console_scheduler_surface.py`.

### Explicitly out of scope

- **Any write to the scheduler policy or state.** No create, edit, enable,
  disable, "run now for a schedule", or interval change. `C-D7`.
- Running the scheduler loop inside the console process (`C5-2`).
- Adding any workflow to `ALLOWLISTED_WORKFLOWS`. That set is changed by a
  reviewed source change with its own decision, never by a UI.
- Cron/calendar semantics beyond the existing interval model.

## Design decisions

### `C5-1` — the "not schedulable" list is derived, never authored

The set of workflows the UI shows as unschedulable is computed as
`known_workflows - ALLOWLISTED_WORKFLOWS`, with the reason text keyed to the
source comment/decision that excluded each one. A hardcoded UI list would drift
the moment the allowlist changes and would then actively mislead. AC-2 asserts
the derivation by adding a workflow to the allowlist in a test and observing the
UI classification move with it.

### `C5-2` — the console does not become the scheduler

The console displays scheduler state; it does not evaluate the policy, does not
run a timer, and does not dispatch due workflows. `--scheduler-once` remains the
one evaluation path, invoked externally (cron, task scheduler, or an operator).
Running an in-process scheduler loop inside a laptop console would mean device
collection continues for as long as a browser tab feels open — an unattended
device-contact path that no decision has approved.

### `C5-3` — static-report inclusion only if identity-free

The scheduler payload contains workflow names, intervals, timestamps and, for
`targets`-bearing schedules, `entity_id` values. `entity_id` is already carried
by every embedded payload, so inclusion is consistent — but the exporter path
must be re-checked against the support-bundle sanitisation rules before the
payload is embedded. If a schedule ever carries something the bundle sanitises
away, the console keeps the field and the export drops it, and that divergence is
recorded as a deliberate exception to `CON.1` `C1-4` rather than smuggled in.

### `C5-4` — due-ness is displayed as computed, not re-implemented

`is_workflow_due` is called; the UI does not recompute due-ness from intervals
and timestamps. One implementation, one answer, no drift between what the UI
promises and what the next `--scheduler-once` actually does.

## Privacy and safety invariants

1. No route mutates policy or state; asserted by route-table enumeration.
2. The payload contains no credential, no management address, and no path.
3. Displaying the scheduler causes no device contact and no policy evaluation
   side effect (`load_*` are reads; `write_scheduler_state` is never called from
   a console path).

## Acceptance criteria

- **AC-1** With no policy file present, the surface renders the honest
  "disabled / unconfigured — no jobs, no network access" state rather than an
  empty table.
- **AC-2** The unschedulable list is derived from `ALLOWLISTED_WORKFLOWS`;
  changing the allowlist in a test moves a workflow between classifications
  without a UI change.
- **AC-3** `"recovery-cp"` renders as unschedulable with the `D3` reason
  (`unattended fleet backup not approved`) — the specific case this phase exists
  to make visible.
- **AC-4** Due/next-run values equal `is_workflow_due` / stored state for the
  fixture; no independent recomputation.
- **AC-5** No mutating route exists; a `POST`/`PUT`/`PATCH`/`DELETE` to any
  scheduler path returns `405`.
- **AC-6** An invalid policy file surfaces `SchedulerPolicyError`'s message as a
  visible error state, not a blank panel — a policy that fails to load is
  operationally important.
- **AC-7** Render harness green in both modes; full suite at or above baseline;
  privacy gate `PASS / 0`.

## Implementation plan

1. `utils/scheduler_ui.py` + unit tests (including the no-policy and
   invalid-policy cases).
2. `GET /api/scheduler`; route-table test (AC-5).
3. Discovery-module presentation + fixture extension.
4. `C5-3` sanitisation re-check before deciding on static-report embedding.
5. Render harness, full suite, privacy gate, metadata.

## Validation and merge gate

Full suite at or above baseline, privacy gate `PASS / 0`, render harness green.
No device contact; `AUTOMATED_VALIDATED` is the terminal status for this phase.

## Risks

- **A read-only view invites an edit button.** The first request after this
  ships will be "let me change the interval here". That is `C-D7`, a decision
  with a device-contact consequence — not a UI iteration.
- **Misreading "disabled" as "broken".** The default-disabled scheduler is the
  intended posture; the empty state must say so in those words (AC-1).
- **Stale reasons.** `C5-1`'s reason text is keyed to decisions; if a decision
  changes and the text does not, the UI misinforms. Keep the reasons next to the
  allowlist in source, not in the UI layer.

## Rollback

Fully additive; removing the payload builder, the route and the presentation
returns the console to `CON.2`.

## Definition of done

AC-1…AC-7 pass; `project/*` metadata and `CURRENT_STATE.md` updated;
`AI_HANDOVER.md` rewritten; `C-D7` recorded as still governing any future
editing capability.

## Next movement / model

`IMPLEMENTATION` at **`Sonnet 5, normal`** — a payload builder and a read-only
panel over existing functions. This is the lightest phase in the track; it does
not warrant extended thinking.
