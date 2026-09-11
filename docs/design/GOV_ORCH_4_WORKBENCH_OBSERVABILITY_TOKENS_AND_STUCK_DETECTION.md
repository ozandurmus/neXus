# GOV.ORCH.4 — Workbench observability: token accounting, stuck detection, work board

## Status

**DRAFT — FOR PRODUCT OWNER FREEZE (2026-09-11).** Extends the shipped
PO + Orchestrator workbench (`py scripts/orchestrator.py dashboard`,
relay `NXS-LOCAL-0021`, `scripts/orchestrator_dashboard.py`,
`scripts/dashboard_assets/`). Depends on GOV.ORCH.1 (record fields,
`run` report) and GOV.ORCH.2 (provider adapters). Keeps every existing
workbench invariant: stdlib backend, vanilla JS, localhost, token +
Origin check, read + relay-write only, no fabricated progress.

## 1. Product Owner intent (verbatim, translated)

"Watch the worker nodes Claude or Codex created: what went out, what came
back, what was updated, whether one is stuck, what is open and what is
closed, without asking all the time. Count tokens: this task cost this
many tokens, this much was cache, this much was the query. Follow the
PO + Orchestrator's workers and get information."

## 2. Problem

The workbench shows movement cards, stage and a log tail, but:

- No token or cost accounting exists anywhere. `engineer.log` already
  carries the data (Claude stream-json `assistant`/`result` events
  carry `usage`; Codex `turn.completed` carries `usage`) and nobody
  reads it.
- "Stuck" is not a first-class state. `pid_alive` and `last_activity`
  exist, but nothing says "alive and silent for 25 minutes" or
  "exited without SESSION_CLOSE".
- Open/closed is spread across relay `status`, record `phase` and PR
  state; there is no single board answering "what is open, what is
  closed, what did each one produce".
- What was sent to and received from each worker (dispatch, corrections,
  notes, SESSION_CLOSE) is visible only by reading the relay JSON.

## 3. Design

### 3.1 Usage accounting (`scripts/orchestrator_usage.py`, new)

One pure function per provider, both returning the same shape:

```json
{
  "provider": "claude",
  "turns": 7,
  "input_tokens": 120345,
  "cache_creation_input_tokens": 40210,
  "cache_read_input_tokens": 601200,
  "output_tokens": 18450,
  "total_tokens": 780205,
  "uncached_input_tokens": 120345,
  "cache_hit_ratio": 0.83,
  "cost_usd": 1.42,
  "cost_source": "reported | estimated | unavailable",
  "model": "claude-sonnet-5",
  "last_event_at": "2026-09-11T19:02:11Z"
}
```

- **Claude**: sum `usage` from every `assistant` event's `message.usage`
  (`input_tokens`, `cache_creation_input_tokens`,
  `cache_read_input_tokens`, `output_tokens`); when a `result` event is
  present, prefer its `usage` totals and take `total_cost_usd` as
  `cost_usd` with `cost_source: "reported"`; `model` from the
  `system`/`init` event. Duplicate `assistant` events for the same
  `message.id` (streaming re-emits) are counted once.
- **Codex**: sum `usage` from every `turn.completed` event
  (`input_tokens`, `cached_input_tokens` → `cache_read_input_tokens`,
  `output_tokens`); `cost_source: "unavailable"` unless a price table
  entry exists (§3.2).
- `cache_hit_ratio = cache_read / (input + cache_creation + cache_read)`,
  `0.0` when the denominator is zero.
- The function reads `.nexus/engineer.log` incrementally: it persists a
  byte offset and running totals in `<state-dir>/usage/<movement>.json`
  so a 200 MB log is not re-parsed on every poll. Corrupt or partial
  lines are skipped, never raised.
- `ProviderAdapter` gains `usage_from_event(obj) -> dict | None`; the
  accounting module calls the adapter, never parses provider shapes
  itself.

### 3.2 Price table

`config/model_prices.json` (new, committed, hand-maintained):
`{"<model-id>": {"input_per_mtok": x, "cache_write_per_mtok": y,
"cache_read_per_mtok": z, "output_per_mtok": w}}`. When a model has an
entry and the provider did not report a cost, `cost_usd` is computed
and `cost_source: "estimated"`. Unknown model → `cost_source:
"unavailable"`, `cost_usd: null`. The table ships empty except for a
comment key; prices are a PO decision, never guessed by a worker.

### 3.3 Stuck detection (`orchestrator_dashboard.derive_health`)

A fourth field next to process status / work stage / last activity,
computed from real signals only:

| health | condition |
|---|---|
| `healthy` | pid alive and `engineer.log` grew within `stuck_after_seconds` |
| `silent` | pid alive, no log growth for ≥ `stuck_after_seconds` (default 900) |
| `exited_without_close` | pid dead, relay not `CLOSED`, phase not terminal |
| `awaiting_po` | relay `next_actor == "po"` (question or close pending review) |
| `failed` | record phase `failed` (carry `failure_reason`) |
| `done` | relay `CLOSED` or phase `done` |

`stuck_after_seconds` lives in the existing `/api/config` document
next to `poll_interval_seconds`, editable from the UI. Health never
uses git dirtiness or heartbeat timestamps.

### 3.4 Work board and traffic view

- `GET /api/board`: `{open: [...], awaiting_po: [...], closed: [...],
  totals: {open, awaiting_po, closed, silent, failed}}`. Each row:
  movement id, objective (first 120 chars), provider/model/effort
  (requested and observed), health, stage, started_at, ended_at,
  duration_s, usage summary (total tokens, cache ratio, cost), PR
  number/state, verify.passed (from the GOV.ORCH.1 record), branch.
- `GET /api/movements/<id>/traffic`: the relay entries rendered as a
  chronological "sent / received" list: every `po`-actor entry is
  **sent to worker**, every `engineer`-actor entry is **received from
  worker**, with marker, timestamp, subject, and for SESSION_CLOSE the
  `outcome`, `changed` files and `validation` summary. Dispatch, resume
  and verify events from the process record are interleaved as
  orchestrator rows.
- `GET /api/usage`: totals per movement and grand totals, for the whole
  state dir; `?since=<iso>` filters by `started_at`.
- Existing `GET /api/movements` rows gain `health` and `usage`; the
  existing shape is otherwise unchanged (backward compatible).

### 3.5 UI (vanilla JS, existing assets)

- Summary strip gains: `N silent · N exited without close · tokens today
  · cost today`.
- Movement card gains a health badge and a one-line usage string
  (`780k tok · 83% cache · $1.42`).
- New top-level "Board" tab: three columns open / awaiting you / closed
  from `/api/board`, closed collapsed by default, each row expandable
  to the traffic list.
- Detail panel "History" tab is replaced by the traffic view (§3.4);
  "Evidence" tab shows the GOV.ORCH.1 verify steps (name, exit code,
  duration, tail) when a record has them.
- No charts beyond the numbers; no new dependency.

### 3.6 CLI

`py scripts/orchestrator.py usage [--movement ID] [--since ISO] [--json]`
prints the same data as `/api/usage` as a table or JSON, so a PO in a
terminal-only tool gets the numbers without the browser.

## 4. Scope

In: `scripts/orchestrator_usage.py` (new), `scripts/orchestrator_providers.py`
(`usage_from_event`), `scripts/orchestrator_dashboard.py` (health, board,
traffic, usage routes), `scripts/orchestrator.py` (`usage` subcommand,
read the usage file for the `run` report), `scripts/dashboard_assets/*`,
`config/model_prices.json` (new), tests (`tests/test_orchestrator_usage.py`
new, `tests/test_orchestrator_dashboard.py`, `tests/test_orchestrator_providers.py`).

Out: any write action beyond the existing relay-write set; live session
channels; charts; changes to relay tooling or packet schema; price values.

## 5. Acceptance criteria

- AC-1: Claude fixture log (assistant events with usage, duplicate
  message id, one result event with total_cost_usd) → exact totals,
  result totals preferred, cost reported, cache ratio correct.
- AC-2: Codex fixture log (two `turn.completed` with usage) → summed
  totals, `cache_read_input_tokens` from `cached_input_tokens`,
  `cost_source: "unavailable"`.
- AC-3: Incremental parse: appending to the log and calling again adds
  only the new events; a truncated last line is skipped and picked up
  once completed.
- AC-4: Price table: entry present → `estimated` cost equals the
  formula; absent → `unavailable` and `null`.
- AC-5: `derive_health` returns each of the six values for the matching
  fixture inputs and never anything else.
- AC-6: `/api/board` groups a fixture state dir with one movement per
  health into the right columns and totals.
- AC-7: `/api/movements/<id>/traffic` labels po entries sent, engineer
  entries received, and includes SESSION_CLOSE outcome/changed/validation.
- AC-8: `/api/movements` existing test fixtures still pass unchanged
  (backward compatible), with `health` and `usage` added.
- AC-9: `orchestrator.py usage --json` output equals `/api/usage` for
  the same state dir.
- AC-10: `stuck_after_seconds` round-trips through `/api/config`.
- AC-11: `git diff --check` clean; privacy gate 0 new findings; no new
  third-party dependency.

## 6. Validation plan (machine-readable)

```
python3 -m pytest -q tests/test_orchestrator_usage.py tests/test_orchestrator_dashboard.py tests/test_orchestrator_providers.py tests/test_orchestrator.py
python3 scripts/repository_privacy_check.py
git diff --check
```

## 7. Worker route

`Sonnet 5, normal (low effort)`. Fixture-driven parsing, one pure health
function, three read-only JSON routes and plain DOM rendering; every
shape is specified above. Not Haiku: the incremental log parser and the
backward-compatibility requirement on `/api/movements` need care.

## 8. Open items for the PO at freeze

- Price table values (PO supplies; worker ships the empty table).
- `stuck_after_seconds` default 900.
- Whether "closed" should also list movements older than N days or
  paginate.
