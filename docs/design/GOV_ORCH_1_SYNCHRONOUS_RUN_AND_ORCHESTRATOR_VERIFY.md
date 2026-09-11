# GOV.ORCH.1 — Synchronous movement run and orchestrator-side verification

## Status

**DRAFT — FOR PRODUCT OWNER FREEZE (2026-09-11).** Amends
`docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (FROZEN) §3.1,
§3.5 and §3.7. It does not change the relay transports
(`NEXUS_AGENT_RELAY_PROTOCOL.md`, `LOCAL_RELAY_PROTOCOL.md`) or the
`NEXUS_SESSION_PACKET` schema (`GOV_SESSION_TRANSFER_PROTOCOL.md`) except
for one additive, backward-compatible field in §4.

## 1. Problem

`scripts/orchestrator.py start` returns immediately after spawning the
engineer. Completion is inferred only from the relay file reaching
`status == "CLOSED"`; `retry_count` is never incremented, so
`reconcile_phase` can never reach `failed` and a dead engineer stays
`running` forever. The Product Owner session is the only thing that ever
polls. When the PO runs in a tool that cannot be woken by a background
process (any single-turn CLI, Codex included), no one polls and the
movement is unmanaged. Separately, the engineer's own claim that its named
tests passed is the only evidence the merge path has; the
background-task-exit race recorded at `relay/NXS-LOCAL-0018` shows that
claim can be wrong without anyone noticing.

## 2. Design

### 2.1 `run`: dispatch, wait, verify, report in one foreground call

New subcommand:

```
py scripts/orchestrator.py run --movement NXS-LOCAL-NNNN
    [--timeout SECONDS] [--heartbeat-timeout SECONDS]
    [--model MODEL] [--effort EFFORT] [--max-budget-usd USD]
    [--no-verify]
```

Behaviour, in order:

1. `dispatch` exactly as `start` does today (same `decide_start`, worktree,
   `approved_task.json`, hash, process record). `start` remains for
   callers that want fire-and-forget; `run` reuses its code path, never a
   copy.
2. `wait`: block until one of
   - the engineer process exits (primary signal: `Popen.wait`, not the
     relay file);
   - `--timeout` elapses (default 5400 s);
   - `--heartbeat-timeout` (default 900 s) elapses with no growth of
     `.nexus/engineer.log`.
   On timeout the engineer is terminated via the existing `_kill_pid`
   (SIGTERM, grace, SIGKILL) and the record phase becomes `failed` with
   `failure_reason` in `{"timeout", "heartbeat_timeout"}`.
3. `verify` (§2.2) unless `--no-verify`.
4. `report`: print one JSON object on stdout (§2.4) and exit
   `0` when the engineer exited 0, the relay is `CLOSED`, and verification
   passed; `1` otherwise. Nothing is left running when `run` returns.

`run` is the documented default path for a PO session. A PO that cannot be
woken uses `run`; a PO that can keeps `start` + `status`.

### 2.2 Orchestrator-side verification

After the engineer exits, the orchestrator runs the movement's validation
commands itself, in the movement worktree, and records the results. The
engineer's `SESSION_CLOSE.report.validation.*` strings stay in the packet
but are **non-authoritative**: the orchestrator's own run is the evidence
the merge gate reads.

Verification steps, each recorded with `argv`, `exit_code`, `duration_s`
and the last 40 lines of combined output:

1. `git status --porcelain` in the worktree must be empty (everything
   committed) — otherwise `verify` fails with `uncommitted_changes`.
2. Every entry of `report.validation_plan` that is machine-readable (§4)
   is executed as an argv list with `shell=False`, cwd = worktree,
   per-command timeout 1800 s. A prose entry (a plain string) is recorded
   as `skipped_prose` and does not fail verification, so existing relay
   files keep working.
3. `git diff --check` against `report.git.base`.
4. The repository privacy gate exactly as
   `scripts/nexus_engineer_tool_gate.py::_privacy_check` runs it today
   (baseline-aware against the movement's `base_sha`). The gate function
   is moved to a shared module (`scripts/orchestrator_verify.py`) and the
   hook calls it from there; behaviour unchanged.

`verify` is also a standalone subcommand
(`py scripts/orchestrator.py verify --movement NXS-LOCAL-NNNN`) so a PO can
re-verify a finished worktree without re-dispatching.

### 2.3 Retry and failure accounting

- `retry_count` is incremented on every resume dispatch (`decide_start ==
  "resume"`) and persisted before the spawn. `reconcile_phase` is
  unchanged, so a movement whose engineer died `retry_limit` times now
  really reaches `failed`.
- A record gains `failure_reason` (string or null) and `exit_code`
  (int or null), set by `run`/`status` when the process is observed dead.

### 2.4 Report object

```json
{
  "movement": "NXS-LOCAL-0071",
  "phase": "done | failed",
  "exit_code": 0,
  "failure_reason": null,
  "relay_status": "CLOSED",
  "provider": "claude", "model": "...", "effort": "...",
  "duration_s": 1234.5,
  "verify": {
    "passed": true,
    "steps": [{"name": "validation_plan[0]", "argv": ["py","-m","pytest","-q","tests/test_orchestrator.py"],
               "exit_code": 0, "duration_s": 41.2, "tail": "..."}]
  },
  "worktree_path": "...", "branch": "feature/..."
}
```

`provider` is `"claude"` until GOV.ORCH.2 lands; the field exists now so
the report shape does not change later.

### 2.5 Process-record location

`NEXUS_ORCHESTRATOR_STATE_DIR` default moves from the system temp
directory to `<worktrees-dir>/.state/` (sibling of the movement
worktrees, never inside the repository checkout). `status` reads both the
new and the old location for one release and reports a record found only
in the old one as `legacy_state_dir`.

## 3. Packet amendment (additive)

`SESSION_START.report.validation_plan` items may be either a string
(unchanged) or an object:

```json
{"name": "targeted tests", "argv": ["py", "-m", "pytest", "-q", "tests/test_orchestrator.py"]}
```

`gov_session_transfer.py`'s schema accepts both forms for this field only
(`list of str | {name: str, argv: list of str}`); every other field is
untouched. A packet with only string items validates exactly as today.

## 4. Scope

In:
- `scripts/orchestrator.py` (`run`, `verify`, retry accounting, state
  dir default, report object)
- `scripts/orchestrator_verify.py` (new; shared privacy-gate call and
  validation-plan runner)
- `scripts/nexus_engineer_tool_gate.py` (call the shared function; no
  behaviour change)
- `scripts/gov_session_transfer.py` (validation_plan item union)
- `tests/test_orchestrator.py`, `tests/test_gov_session_transfer.py`,
  new `tests/test_orchestrator_verify.py`
- `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md`: one
  "Amended by GOV.ORCH.1" note under §3.1/§3.5/§3.7 pointing here (no
  restatement)

Out:
- Any provider other than `claude` (GOV.ORCH.2)
- WORKER.md, worker prompt content, hook removal (GOV.ORCH.3)
- Dashboard changes beyond reading the new record fields
- Any device, deployment or collection code

## 5. Acceptance criteria

- AC-1: `run` on a fake engineer that exits 0 and closes the relay returns
  0, prints the §2.4 object, and leaves no child process (test uses a
  stub `claude` script on PATH, as `test_start_cli_*` already does).
- AC-2: `run` with `--timeout 1` on a stub that sleeps returns 1, the
  record is `failed` / `timeout`, and the stub PID is dead.
- AC-3: heartbeat: a stub that writes nothing to `engineer.log` for longer
  than `--heartbeat-timeout` is killed and recorded `heartbeat_timeout`.
- AC-4: `verify` executes object-form `validation_plan` entries with
  `shell=False`, records exit codes, and fails when any is non-zero;
  string entries are recorded `skipped_prose`.
- AC-5: `verify` fails on a worktree with uncommitted changes.
- AC-6: `retry_count` increments on resume; after `retry_limit` dead
  resumes `status` reports `failed`.
- AC-7: `gov_session_transfer.py validate` accepts both
  `validation_plan` item forms and rejects an object missing `argv` or
  with a non-list `argv`.
- AC-8: The privacy gate hook behaves identically before and after the
  move to `orchestrator_verify.py` (existing
  `tests/test_gov_po_3_ci_privacy_gate_baseline.py` green).
- AC-9: State dir default is under `<worktrees-dir>/.state/`; a record in
  the legacy temp location is still listed by `status`.
- AC-10: `git diff --check` clean; privacy gate 0 new findings; no change
  to `local_relay.py` or the relay JSON files.

## 6. Validation plan (machine-readable)

```
py -m pytest -q tests/test_orchestrator.py tests/test_orchestrator_verify.py tests/test_gov_session_transfer.py tests/test_gov_po_3_ci_privacy_gate_baseline.py tests/test_local_relay_protocol.py
py scripts/repository_privacy_check.py
git diff --check
```

## 7. Worker route

`Sonnet 5, normal (medium effort)`. Deterministic implementation against
this contract in one Python module plus tests; no vendor-semantic or
security-boundary decision is left to the worker. Haiku is not enough:
the process-lifecycle code (wait/kill/heartbeat) has to be correct on the
first pass.

## 8. Open items for the PO at freeze

- Default `--timeout` 5400 s / `--heartbeat-timeout` 900 s.
- Whether `run` should refuse when `--max-workers` would be exceeded
  (proposed: yes, identical to `start`).
