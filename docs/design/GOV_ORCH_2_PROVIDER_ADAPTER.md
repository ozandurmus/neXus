# GOV.ORCH.2 — Provider adapter for engineer dispatch (Claude, Codex)

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-11 (chat directive "hepsine ok").** Depends on GOV.ORCH.1
(report object, `run`). Amends
`docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` §3.5 (engineer
prompt and argv) and makes `docs/reference/COPILOT_OPERATING_MODEL.md`'s
"Codex when Claude is unavailable" worker route actually exist in code.

## 1. Problem

`scripts/orchestrator.py::_spawn_engineer` hard-codes the `claude` binary
and Claude-Code-only flags (`--settings`, `--add-dir`,
`--permission-prompts none`, `--max-budget-usd`,
`--output-format stream-json --verbose`, `--resume`), and
`summarize_stream_json_line` parses Claude's stream-json event shapes.
There is no way to dispatch a Codex engineer, so the documented fallback
is fiction and movement `NXS-LOCAL-0069` ran on an unrecorded default.

## 2. Design

### 2.1 Adapter interface

New module `scripts/orchestrator_providers.py`:

```python
class ProviderAdapter(Protocol):
    name: str                       # "claude" | "codex"
    def build_argv(self, *, prompt_path: Path, worktree: Path,
                   model: str | None, effort: str | None,
                   budget_usd: float | None,
                   extra_dirs: list[Path],
                   resume_session_id: str | None) -> list[str]: ...
    def build_env(self, base_env: dict[str, str], *, worktree: Path,
                  relay_dir: Path, relay_file: Path) -> dict[str, str]: ...
    def stdin_source(self, prompt_path: Path) -> IO | None: ...
    def summarize_line(self, raw_line: str) -> str | None: ...
    def session_id_from_log(self, log_path: Path) -> str | None: ...
    def observed_model(self, log_path: Path) -> tuple[str | None, str | None]: ...
```

`_spawn_engineer` takes an adapter and becomes provider-neutral: it
writes the prompt to `.nexus/engineer_prompt.txt`, asks the adapter for
argv/env/stdin, spawns with `shell=False`, `cwd=worktree`,
`start_new_session=True`, and logs to `.nexus/engineer.log` exactly as
today. The task content is still never placed in argv (GOV.PO.3 §3.5):
both adapters pass the prompt via a file or stdin.

### 2.2 Claude adapter

Reproduces today's argv byte-for-byte, except the prompt is read from
the prompt file rather than passed inline. Every existing
`test_spawn_engineer_*` test keeps passing against it.

### 2.3 Codex adapter

Non-interactive mode of the Codex CLI:

```
codex exec --cd <worktree> --json
    [-m <model>] [-c model_reasoning_effort=<effort>]
    -c sandbox_workspace_write.network_access=true
    --sandbox workspace-write
    --add-dir <canonical_relay_dir>
    -o <worktree>/.nexus/engineer_last_message.txt
    -   # prompt on stdin
```

- Resume: `codex exec resume <session_id>` with the same config flags;
  the session id is read from the first `thread.started`/session line of
  the JSON log via `session_id_from_log`.
- `summarize_line` reduces Codex's JSON-lines events (item started /
  completed, command execution, final message) to the same one-line
  summary form the tailer writes today, so `engineer.summary.log` and
  the dashboard need no change.
- The Codex CLI has no PreToolUse hook. Under this contract the two
  boundaries the Claude hook enforces (privacy scan before push, merge
  serialization before `gh pr merge`) are enforced for a Codex engineer
  by GOV.ORCH.1's orchestrator-side `verify` plus the worktree-local
  `pre-push` git hook installed by GOV.ORCH.3. Until GOV.ORCH.3 lands, a
  Codex engineer is dispatched with `--merge-mode orchestrator` (§2.5)
  so it never runs `gh pr merge` itself.
- Exact flag names are verified against the installed `codex --help`
  at implementation time and recorded in the module docstring, the same
  discipline `orchestrator.py` applied to `--verbose` (relay
  `NXS-LOCAL-0013`).

### 2.4 Selection and audit

- `start`/`run` gain `--provider {claude,codex}`; default `claude`.
- Process record and the GOV.ORCH.1 report carry
  `provider`, `model_requested`, `effort_requested`, and
  `model_observed`/`effort_observed` read from the engineer log by
  `observed_model`. A dispatch whose observed values are null is reported
  with `audit_exception: "provider_default_used"` — the
  `NXS-LOCAL-0069` rule from `COPILOT_OPERATING_MODEL.md`, now enforced.
- `--model`/`--effort` are validated per provider from a small
  allowlist table in the module (Claude: effort in
  `{low,medium,high,max}`; Codex: `{minimal,low,medium,high}`); unknown
  values are a usage error, never silently passed through.

### 2.5 Merge mode

`--merge-mode {engineer,orchestrator}` (default `engineer` for Claude,
forced `orchestrator` for Codex). In `orchestrator` mode the engineer
prompt says "do not merge; publish SESSION_CLOSE and stop", and
`run` performs the integration after a green `verify` using the
existing merge-lock + `git merge origin/main` + convergence check
sequence from `nexus_engineer_tool_gate.py`, moved into
`scripts/orchestrator_verify.py::integrate`. The hook keeps calling the
shared function.

## 3. Scope

In: `scripts/orchestrator_providers.py` (new), `scripts/orchestrator.py`
(adapter wiring, `--provider`, `--merge-mode`, audit fields),
`scripts/orchestrator_verify.py` (`integrate`), `scripts/orchestrator_dashboard.py`
(display the provider fields only), tests
(`tests/test_orchestrator_providers.py` new; `tests/test_orchestrator.py`),
one "Amended by GOV.ORCH.2" note in GOV.PO.3 §3.5, and the
`COPILOT_OPERATING_MODEL.md` worker-selection paragraph updated to name
the flag.

Out: any change to the Claude hook profiles, WORKER.md, packet schema,
relay tooling, OpenRouter, device or deployment code.

## 4. Acceptance criteria

- AC-1: Claude adapter argv equals today's argv except for prompt
  delivery; all existing `test_spawn_engineer_*` and `test_start_cli_*`
  tests pass unchanged or with only the prompt-delivery assertion
  adjusted.
- AC-2: Codex adapter argv for a fresh dispatch matches §2.3 exactly for
  given model/effort; the prompt is delivered on stdin; the task content
  never appears in argv.
- AC-3: Codex resume argv uses `codex exec resume <id>` with the id read
  from a fixture log.
- AC-4: `summarize_line` on a fixture of Codex JSON-lines events produces
  one summary line per item and never raises on unknown event types.
- AC-5: `--provider codex` forces `merge-mode orchestrator`; the prompt
  file contains the no-merge instruction; after a stub engineer closes
  the relay, `run` calls `integrate` once (mocked) and only when `verify`
  passed.
- AC-6: Invalid `--effort` for the chosen provider exits 2 with a usage
  message; the state record is not created.
- AC-7: A dispatch whose log yields no observed model is reported with
  `audit_exception: "provider_default_used"`.
- AC-8: `git diff --check` clean; privacy gate 0 new findings.

## 5. Validation plan (machine-readable)

```
py -m pytest -q tests/test_orchestrator.py tests/test_orchestrator_providers.py tests/test_orchestrator_verify.py tests/test_orchestrator_dashboard.py
py scripts/repository_privacy_check.py
git diff --check
```

## 6. Worker route

`Sonnet 5, normal (low effort)`. Pure refactor-to-interface plus one new
adapter with fixture-driven tests. The one judgement call (exact Codex
flag names) is resolved by reading `codex --help`, not by reasoning.

## 7. Open items for the PO at freeze

- Whether Codex engineers should ever be allowed `merge-mode engineer`
  once GOV.ORCH.3's git-level gates exist (proposed: yes, after one
  successful orchestrator-mode movement).
