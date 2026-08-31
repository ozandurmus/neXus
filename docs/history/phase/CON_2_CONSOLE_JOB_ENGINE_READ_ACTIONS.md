# `CON.2` — Console job engine and `read`-class actions

## Status

**CONTRACT FROZEN 2026-08-31**, alongside `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`
(`CON.0`) — the architecture this binds to and does not restate, in particular
§4 (the intent boundary) and §5 (the reuse map). No source file changed by the
session that froze it.

`project/backlog.json` `operator_console` (P1), roadmap track `CON.x`.

**Preconditions:** `CON.1` AUTOMATED_VALIDATED; decision `C-D3` resolved
(`Provenance.CONSOLE`).

## Objective

Give the console the ability to *do* something, with the smallest possible
increase in risk: a durable job engine plus a closed registry of **`read`-class**
actions only — re-collect an inventory plane, run a `RB.3a` attestation, rebuild
the report. No new device command, no new collector, no write.

This is the phase where the product stops being "a report you regenerate from a
terminal" and becomes an operator tool. It is deliberately the phase *before*
anything writes to a device.

## Scope

### In scope

1. `console/registry.py` — the closed job-type registry (`C2-1`).
2. `console/jobs.py` — durable job records on the existing evidence backend.
3. `console/runner.py` — a single-worker executor invoking `main()` exactly as
   the scheduler does.
4. `utils/collection_executor.py` — `workflow_argv()` promoted from
   `main.py:_scheduler_workflow_argv` so scheduler and console share one argv
   construction path (`C2-2`).
5. `utils/coordinator_backend.py` — `Provenance.CONSOLE = "console"` and the
   audit of its consumers (`C2-3`).
6. Mutating routes: `POST /api/jobs`, plus `GET /api/jobs`,
   `GET /api/jobs/{job_id}`, `GET /api/jobs/{job_id}/events` (SSE),
   `GET /api/job-types`.
7. `static/console_actions.js` gains the action surface: job list, job detail,
   live state, per-row action buttons for `read`-class job types.
8. `tests/test_con2_console_job_engine.py`.

### Explicitly out of scope

- **Any `operational-write` job type executing.** The registry may *declare*
  them so the UI can render an honest `BLOCKED` state; the endpoint refuses to
  execute anything whose `command_class != "read"` (`C2-6`). `CON.3` lifts that,
  and only that.
- Any new device command, collector, or vendor code path.
- Scheduler policy reading or writing (`CON.5`).
- The Recovery module (`CON.4`).
- Cancelling or killing a running job (see `C2-8`).
- Multi-user identity, roles, or per-user authorization — one operator, one
  process, one token (`CON.6`).

## Design decisions

### `C2-1` — the registry is source, closed, and carries no free text

```python
@dataclass(frozen=True)
class JobType:
    id: str                      # "inventory_refresh_cp"
    label: str                   # UI text, English
    command_class: str           # "read" | "operational-write"
    workflow: str                # feeds workflow_argv(); must be in ALLOWLISTED_WORKFLOWS or an explicit read mode
    target_mode: str             # "none" | "entity_ids"
    vendor: str | None
    requires_confirmation: bool
```

`JOB_REGISTRY: Mapping[str, JobType]` is a module-level constant. It is not read
from disk, not merged with environment or policy, and not extensible at runtime.
The registry ships with:

| id | `command_class` | effect | targets |
|---|---|---|---|
| `inventory_refresh_cp` | `read` | `--only cp` | none |
| `inventory_refresh_vsx` | `read` | `--only vsx` | none |
| `config_refresh_pan` | `read` | `--only pan-config` | none |
| `config_refresh_cp` | `read` | `--cp-config-collect --cp-config-stage all` | none |
| `recovery_attest_cp` | `read` | `--recovery-attest` (`RB.3a`) | `entity_ids` |
| `report_rebuild` | `read` | `--render-only` | none |
| `cp_gaia_backup` | `operational-write` | declared, **refused** until `CON.3` | `entity_ids` |

### `C2-2` — one argv construction path, shared with the scheduler

`main.py:_scheduler_workflow_argv` becomes
`utils.collection_executor.workflow_argv(workflow, runtime_root, targets=())`
and `main.py` calls it. The console builds argv only through that function or
through an equally fixed per-job-type template in the registry. **No string
originating from an HTTP request is ever placed into argv**, with the single
exception of `entity_id` values that have already passed
`utils.restore_readiness.resolve_entity_id` against `unified.json`.

Rationale: the scheduler and the console are the two automated triggers of the
same engine. If they build argv differently, one of them will eventually build
it wrongly.

### `C2-3` — `Provenance.CONSOLE`, and the audit that comes with it

Add `CONSOLE = "console"` to `utils.coordinator_backend.Provenance`. The
implementation must enumerate and, where necessary, update every consumer that
validates or switches on the provenance value: coordinator job views, run
manifests, `RunContext.set_job_metadata`, the discovery/coordinator UI payload,
support-bundle projections, and any test asserting the value set. A UI-triggered
device action must be distinguishable from a CLI one in every durable record;
reusing `"manual"` is explicitly rejected (`C-D3`).

### `C2-4` — the job record, and what it may not contain

Stored through the `evidence_backend` abstraction (filesystem default, opt-in
PostgreSQL — a sixth concern alongside `DEV.3.3`'s five), under `data_root`,
never under `output_root`, never in the support bundle.

```
job_id, job_type, command_class, targets[entity_id], idempotency_key,
state (queued|running|succeeded|failed|blocked|skipped),
requested_at, started_at, finished_at,
run_id, coordinator_decision, outcome_counts, error_code, error_summary
```

Forbidden in a job record, asserted by test: credentials or tokens, management
addresses, hostnames beyond the `entity_id` the inventory already carries, raw
device output, backup bytes, file paths outside the runtime root, and stack
traces. `error_summary` is a bounded, redaction-registry-filtered string.

### `C2-5` — durable before running, terminal on crash

A job record reaches durable storage in `queued` before the runner may pick it
up (`CON.0` §7.9). A runner exception marks the record `failed` with an
`error_code`; a process death leaves a `running` record, which the next console
start marks `failed` with `error_code=console_restarted` rather than leaving a
zombie. "We attempted this" must survive a crash even when "we finished it" does
not.

### `C2-6` — `operational-write` is refused at the endpoint in this phase

`POST /api/jobs` with a registry entry whose `command_class != "read"` returns
`409` with `{"error": "operational_write_not_enabled"}`. This is a deliberate
staging gate, not a placeholder: it means `CON.2` can ship and be used while
`RB.3b`'s real-environment run is still owed, and it gives the UI a real
`BLOCKED` state to render (`CON.0` §9) instead of hiding the capability.

### `C2-7` — one worker, and admission still decides

The runner executes at most one job at a time, FIFO. This is a ceiling, not the
mechanism: every execution still goes through
`execute_admitted_collection`/`CollectionCoordinator`, so a job that collides
with a concurrent CLI run or a scheduled run is coalesced or refused by the
existing admission logic, unchanged. The console must not raise concurrency, and
must not bypass admission to "make the UI feel faster" (`AGENTS.md`: stability
over speed).

### `C2-8` — no cancellation in this phase

There is no `DELETE /api/jobs/{id}` and no kill path. Interrupting a collector
mid-SSH-session is a device-interaction question, not a UI question, and it needs
its own safety review. The UI shows elapsed time and the admission decision; an
operator who must stop a run stops the console process.

### `C2-9` — idempotency

`POST /api/jobs` requires an `Idempotency-Key`. A repeat with the same key
returns the original job record (`200`), never a second job. This makes a
double-click, a retried fetch, or a page reload structurally incapable of
producing two device-contacting runs — a property that matters far more in
`CON.3` and is therefore established here, where it is cheap to get right.

### `C2-10` — SSE carries state, never device output

`GET /api/jobs/{id}/events` streams job-record state transitions only. It never
streams collector stdout, log lines, or device output. The stream terminates on
a terminal state. A client that disconnects loses nothing: the job record is the
truth and `GET /api/jobs/{id}` returns the same information.

## Privacy and safety invariants

1. The invariants of `CON.1` continue to hold; `console/` may now import
   `main` and `utils.*`, but still must not import a vendor module directly —
   collection happens inside `main()`, as it does for the scheduler.
2. No response body contains a credential, a management address, or raw device
   output.
3. Device contact per operator action is exactly one engine run — identical to
   the corresponding CLI invocation. No console feature retries a failed device
   operation automatically.
4. Job records are excluded from the support bundle by construction (they live
   under `data_root/state`, which the bundle does not walk) and this is asserted.

## Acceptance criteria

- **AC-1** A `read`-class job submitted from the UI runs the same engine path a
  CLI invocation runs, produces a `RunContext` manifest with
  `provenance="console"`, and reaches a terminal job state visible in the UI
  without a page reload.
- **AC-2** No console code path calls a collector, `run_recovery_collection`, or
  a vendor module directly: every execution goes through `main()`. Asserted by
  patching `main` in the runner's module and proving no device path is reachable
  when it is not called.
- **AC-3** `POST /api/jobs` rejects, before any execution: an unknown
  `job_type` (`400`), an `entity_id` absent from `unified.json` (`400`), a
  missing `Idempotency-Key` (`400`), an `operational-write` class (`409`,
  `C2-6`), a missing/invalid token (`401`), and an `Origin` mismatch (`403`).
- **AC-4** A repeated `Idempotency-Key` returns the original job record and
  creates no second job.
- **AC-5** A job record contains none of the forbidden fields in `C2-4`;
  asserted field-by-field against a completed job and a failed job.
- **AC-6** A runner exception yields a terminal `failed` record with a bounded
  `error_summary`; a simulated process death followed by a console restart yields
  `failed` / `console_restarted`, with no record left in `running`.
- **AC-7** `workflow_argv()` produces byte-identical argv for the scheduler and
  the console for every workflow the scheduler supports (parametrised test).
- **AC-8** Every `Provenance` consumer accepts `"console"`; the enumeration of
  consumers is recorded in the implementation notes, not left implicit.
- **AC-9** Two jobs submitted concurrently serialise through the coordinator with
  the existing decisions (`admitted` / `coalesced` / refused) and both records
  reach a terminal state.
- **AC-10** SSE terminates on terminal state and never carries collector output.
- **AC-11** Render harness green; static report still contains no action surface;
  full suite at or above baseline; privacy gate `PASS / 0`.

## Implementation plan

1. `workflow_argv()` promotion + AC-7 test (pure refactor, land alone).
2. `Provenance.CONSOLE` + consumer audit + AC-8 (land alone; this is the change
   most likely to surprise a test elsewhere).
3. `console/registry.py` with the table in `C2-1`.
4. `console/jobs.py` on the evidence backend + AC-5/AC-6 tests.
5. `console/runner.py` — single worker, `main()` invocation, admission handling.
6. Routes + validation + AC-3/AC-4 tests.
7. SSE + AC-10.
8. `console_actions.js` action surface; real-Chromium walk.
9. Full suite, privacy gate, render harness, project metadata.

## Validation and merge gate

Full suite at or above baseline, privacy gate `PASS / 0`, render harness green,
real-Chromium console walk. **Real-environment validation is owed** before this
phase advances past `AUTOMATED_VALIDATED`: a watched run on the corporate
laptop where a `read`-class action triggered from the console reaches real
devices and produces the same artifacts the equivalent CLI run produces. That is
a `HUMAN_REAL_ENV` gate, not an engineering task — it needs no new code, only a
session with device reachability.

## Risks

- **The provenance change has the widest blast radius in this phase.** It
  touches manifests and payloads that several tests assert on. Land it alone.
- **Job-store scope creep.** A job store invites a "recent runs" history, a
  metrics page, a retention policy. None of that is in this phase; the job store
  exists to make one action auditable and resumable.
- **Silent divergence from the CLI.** If a job type's argv template drifts from
  what the CLI produces, the console starts doing something subtly different from
  what the operator can reproduce by hand. AC-7 covers the shared paths; keep
  per-job-type templates in the registry minimal for the same reason.
- **UI over-promising.** A button for a capability the gates refuse must render
  as `BLOCKED` with the reason, never as available-then-error. `C2-6` exists so
  that state is real and testable from day one.

## Rollback

Additive except for `workflow_argv()` and `Provenance.CONSOLE`, both of which are
safe to retain independently. Removing `console/registry.py`,
`console/jobs.py`, `console/runner.py`, the mutating routes and the action
surface returns the product to `CON.1`.

## Definition of done

AC-1…AC-11 pass; the `Provenance` consumer enumeration is recorded in the build
history entry; `project/*` metadata and `CURRENT_STATE.md` updated;
`AI_HANDOVER.md` rewritten; status set to `AUTOMATED_VALIDATED` with the
real-environment run explicitly recorded as owed.

## Next movement / model

`IMPLEMENTATION` at **`Sonnet 5, normal`** — the design decisions are made and
the boundaries are concrete. The one place to slow down rather than speed up is
`C2-3`'s consumer audit: enumerate, do not assume. Escalate to extended thinking
only if the audit reveals a provenance consumer that cannot accept a third value
without a schema change.
