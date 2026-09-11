"""GOV.ORCH.2 -- provider adapters for engineer dispatch (docs/design/
GOV_ORCH_2_PROVIDER_ADAPTER.md, section 2.1-2.3).

Two adapters ship: `ClaudeAdapter` (today's `claude -p ...` dispatch,
argv byte-for-byte unchanged except the prompt is now read from the
`.nexus/engineer_prompt.txt` file `orchestrator.py::_spawn_engineer`
writes before building argv, rather than an inline string the caller
composed itself) and `CodexAdapter` (`codex exec`, prompt on stdin,
`codex exec resume <id>` for a resumed session).

Codex flag-name note (section 2.3's own discipline, previously applied
to `--verbose` in relay `NXS-LOCAL-0013`): verified against the
installed `codex-cli 0.154.0` (`codex exec --help`, `codex exec resume
--help`, and one `codex exec --json` probe run). Findings applied here:

- Fresh dispatch: `codex exec --cd <dir> --json -m <model>
  -c model_reasoning_effort=<effort> -c
  sandbox_workspace_write.network_access=true --sandbox workspace-write
  --add-dir <dir> -o <file> -` are all real flags; `-` reads the prompt
  from stdin. `codex exec` is non-interactive and never prompts for
  approval, so no approval flag is needed.
- Resume: `codex exec resume <id> [PROMPT]` accepts `--json`, `-m`, `-c`
  and `-o` but NOT `--cd`, `--sandbox` or `--add-dir`; the sandbox and
  the extra writable roots are therefore passed as config overrides
  (`-c sandbox_mode="workspace-write"`,
  `-c sandbox_workspace_write.writable_roots=[...]`) and the working
  directory comes from the spawner's `cwd`, which the orchestrator sets
  to the worktree for every provider.
- JSON-lines events observed: `{"type":"thread.started","thread_id":
  "<uuid>"}`, `{"type":"turn.started"}`, `{"type":"error","message":..}`;
  item events follow the `item.started`/`item.completed` shape with an
  `item` object. The stream carries NO model or effort field, so
  `observed_model` returns `(None, None)` for Codex and every Codex
  dispatch is reported with `audit_exception: "provider_default_used"`
  until Codex exposes the served model in its event stream; the
  requested values are still recorded on the process record.

Task content (the approved-task JSON) never appears in argv for either
adapter -- both receive it only indirectly, via the fixed `ENGINEER_PROMPT`
text (itself not task content) and the engineer's own later `Read` of
`.nexus/approved_task.json`.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import IO, Protocol

#: Section 2.4: per-provider `--effort` allowlists. An unrecognized value
#: is a usage error (exit 2), never silently passed through.
EFFORT_ALLOWLISTS: dict[str, frozenset[str]] = {
    "claude": frozenset({"low", "medium", "high", "max"}),
    "codex": frozenset({"minimal", "low", "medium", "high"}),
}

PROVIDERS = frozenset(EFFORT_ALLOWLISTS)

#: Section 2.5: merge-mode forced for a Codex dispatch until GOV.ORCH.3's
#: git-level gates land (a Codex engineer never runs `gh pr merge` itself).
FORCED_MERGE_MODE: dict[str, str] = {"codex": "orchestrator"}
DEFAULT_MERGE_MODE = "engineer"

#: Section 2.5: appended to the engineer prompt whenever the resolved
#: merge-mode is "orchestrator" -- the engineer must not attempt
#: `gh pr merge` itself; the orchestrator's own `run` integrates after a
#: green `verify` (scripts/orchestrator_verify.py::integrate).
NO_MERGE_PROMPT_NOTE = (
    "This dispatch runs under --merge-mode orchestrator: do not run "
    "`gh pr merge` yourself. Open the pull request as usual, publish your "
    "SESSION_CLOSE, and stop -- the orchestrator merges after its own "
    "verification passes."
)


class InvalidEffortError(ValueError):
    """`--effort` is not in the chosen provider's allowlist (section 2.4)."""


def validate_effort(provider: str, effort: str | None) -> None:
    """Raises `InvalidEffortError` for an effort value outside the chosen
    provider's allowlist. `effort=None` (unset -- the CLI's own default) is
    always accepted for every provider."""
    if effort is None:
        return
    allowlist = EFFORT_ALLOWLISTS.get(provider)
    if allowlist is None or effort not in allowlist:
        allowed = ", ".join(sorted(allowlist)) if allowlist else "(unknown provider)"
        raise InvalidEffortError(
            f"invalid --effort {effort!r} for provider {provider!r}; allowed: {allowed}"
        )


def resolve_merge_mode(provider: str, requested: str | None) -> str:
    """Section 2.5: `--merge-mode` default is 'engineer', forced to
    'orchestrator' for a provider named in `FORCED_MERGE_MODE` regardless
    of what was requested."""
    forced = FORCED_MERGE_MODE.get(provider)
    if forced is not None:
        return forced
    return requested or DEFAULT_MERGE_MODE


class ProviderAdapter(Protocol):
    """Section 2.1's adapter interface. `_spawn_engineer` becomes
    provider-neutral against this: it writes the prompt to
    `.nexus/engineer_prompt.txt`, asks the adapter for argv/env/stdin,
    and spawns with `shell=False`."""

    name: str

    def build_argv(
        self, *, prompt_path: Path, worktree: Path, model: str | None, effort: str | None,
        budget_usd: float | None, extra_dirs: list[Path], resume_session_id: str | None,
    ) -> list[str]: ...

    def build_env(
        self, base_env: dict[str, str], *, worktree: Path, relay_dir: Path, relay_file: Path,
    ) -> dict[str, str]: ...

    def stdin_source(self, prompt_path: Path) -> IO | None: ...

    def summarize_line(self, raw_line: str) -> str | None: ...

    def session_id_from_log(self, log_path: Path) -> str | None: ...

    def observed_model(self, log_path: Path) -> tuple[str | None, str | None]: ...


def _iter_json_lines(log_path: Path):
    """Yields one parsed JSON object per complete, valid line of `log_path`.
    Never raises: a missing file, an unreadable file, or an individual line
    that is not valid JSON are all silently skipped (mirrors
    `orchestrator.py::summarize_stream_json_line`'s own tolerance)."""
    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            yield obj


class ClaudeAdapter:
    """Section 2.2: reproduces `orchestrator.py`'s pre-GOV.ORCH.2 argv
    byte-for-byte -- only the prompt's *source* changes (the caller writes
    it to `prompt_path` first; this adapter reads it back rather than
    receiving it as an in-memory string). Every existing
    `test_spawn_engineer_*` assertion keeps passing unchanged because the
    content read from that file is identical to what was built in memory
    before."""

    name = "claude"

    def __init__(self, profile_path: Path):
        self.profile_path = Path(profile_path)

    def build_argv(
        self, *, prompt_path: Path, worktree: Path, model: str | None, effort: str | None,
        budget_usd: float | None, extra_dirs: list[Path], resume_session_id: str | None,
    ) -> list[str]:
        prompt = Path(prompt_path).read_text(encoding="utf-8")
        argv = [
            "claude", "-p", prompt, "--settings", str(self.profile_path),
        ]
        for extra_dir in extra_dirs:
            argv += ["--add-dir", str(extra_dir)]
        argv += ["--output-format", "stream-json", "--verbose", "--permission-prompts", "none"]
        if budget_usd is not None:
            argv += ["--max-budget-usd", str(budget_usd)]
        if model:
            argv += ["--model", model]
        if effort:
            argv += ["--effort", effort]
        if resume_session_id:
            argv += ["--resume", resume_session_id]
        return argv

    def build_env(
        self, base_env: dict[str, str], *, worktree: Path, relay_dir: Path, relay_file: Path,
    ) -> dict[str, str]:
        # All of today's env wiring (NEXUS_WORKTREE_ROOT, the canonical
        # relay dir/file, BASH_DEFAULT_TIMEOUT_MS) is set by
        # `_spawn_engineer` itself before this hook runs, identically for
        # every provider -- nothing Claude-specific to add here.
        return base_env

    def stdin_source(self, prompt_path: Path) -> IO | None:
        return None  # unchanged: stdin stays DEVNULL, prompt travels via argv.

    def summarize_line(self, raw_line: str) -> str | None:
        # Lazy import: orchestrator.py imports this module at load time,
        # so importing it back here at call time (not at module scope)
        # avoids a load-order cycle. `orchestrator.summarize_stream_json_line`
        # stays the one implementation both direct callers (tests) and this
        # adapter use.
        import orchestrator as _orch
        return _orch.summarize_stream_json_line(raw_line)

    def session_id_from_log(self, log_path: Path) -> str | None:
        for obj in _iter_json_lines(Path(log_path)):
            if obj.get("type") == "system" and obj.get("subtype") == "init":
                sid = obj.get("session_id")
                if isinstance(sid, str) and sid:
                    return sid
        return None

    def observed_model(self, log_path: Path) -> tuple[str | None, str | None]:
        # stream-json's own "system"/"init" event carries the model actually
        # in effect; it carries no reasoning-effort field, so effort_observed
        # is always None for this provider.
        for obj in _iter_json_lines(Path(log_path)):
            if obj.get("type") == "system" and obj.get("subtype") == "init":
                model = obj.get("model")
                return (model if isinstance(model, str) and model else None), None
        return None, None


class CodexAdapter:
    """Section 2.3: `codex exec` non-interactive dispatch. The Codex CLI has
    no PreToolUse hook; until GOV.ORCH.3's worktree-local pre-push hook
    lands, a Codex engineer is always dispatched under `--merge-mode
    orchestrator` (enforced by `orchestrator.py`, not here) so it never
    reaches `gh pr merge` itself.

    UNVERIFIED (see module docstring): flag names are taken from the
    contract text; `codex` was not installed in this environment when this
    was implemented."""

    name = "codex"

    def build_argv(
        self, *, prompt_path: Path, worktree: Path, model: str | None, effort: str | None,
        budget_usd: float | None, extra_dirs: list[Path], resume_session_id: str | None,
    ) -> list[str]:
        # Section 2.3: a resume carries the same config flags as a fresh
        # dispatch (sandbox, network, model, effort, add-dir, output file);
        # only the leading verb differs.
        resume = bool(resume_session_id)
        if resume:
            argv = ["codex", "exec", "resume", resume_session_id, "--json"]
        else:
            argv = ["codex", "exec", "--cd", str(worktree), "--json"]
        if model:
            argv += ["-m", model]
        if effort:
            argv += ["-c", f"model_reasoning_effort={effort}"]
        argv += ["-c", "sandbox_workspace_write.network_access=true"]
        if resume:
            # `codex exec resume` has no --sandbox/--add-dir (module
            # docstring); the same policy goes through config overrides.
            argv += ["-c", 'sandbox_mode="workspace-write"']
            if extra_dirs:
                roots = ", ".join(json.dumps(str(d)) for d in extra_dirs)
                argv += ["-c", f"sandbox_workspace_write.writable_roots=[{roots}]"]
        else:
            argv += ["--sandbox", "workspace-write"]
            for extra_dir in extra_dirs:
                argv += ["--add-dir", str(extra_dir)]
        argv += ["-o", str(Path(worktree) / ".nexus" / "engineer_last_message.txt")]
        argv += ["-"]  # prompt on stdin
        return argv

    def build_env(
        self, base_env: dict[str, str], *, worktree: Path, relay_dir: Path, relay_file: Path,
    ) -> dict[str, str]:
        return base_env

    def stdin_source(self, prompt_path: Path) -> IO | None:
        # Task content never enters argv for either provider (module
        # docstring); Codex's own non-interactive mode reads the prompt
        # from stdin (the trailing "-" in build_argv), so the file the
        # caller already wrote is simply opened and handed to Popen.
        return open(prompt_path, "rb")

    def summarize_line(self, raw_line: str) -> str | None:
        """One raw `codex exec --json` line -> one short summary line, or
        `None` to skip (blank / invalid JSON). An unrecognized-but-valid
        event shape falls back to the truncated raw line -- never raises
        (AC-4) -- exactly `summarize_stream_json_line`'s own discipline,
        because the exact Codex event vocabulary is unverified here."""
        line = raw_line.strip()
        if not line:
            return None
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            return None
        if not isinstance(obj, dict):
            return line[:100]

        kind = obj.get("type")
        if kind in ("item.started", "item.completed"):
            item = obj.get("item") or {}
            item_type = item.get("type", "?") if isinstance(item, dict) else "?"
            status = "started" if kind == "item.started" else "completed"
            detail = ""
            if isinstance(item, dict):
                for key in ("command", "text", "message"):
                    value = item.get(key)
                    if isinstance(value, str) and value:
                        detail = " ".join(value.split())[:60]
                        break
            return f"{item_type} ({status}){': ' + detail if detail else ''}"
        if kind == "thread.started":
            return f"thread: started ({obj.get('session_id', obj.get('thread_id', '?'))})"
        if kind == "turn.completed":
            return "turn: completed"
        if kind == "error":
            return f"error: {obj.get('message', obj.get('error', '?'))}"
        # Recognized JSON, unrecognized shape -- never drop a real event.
        return line[:200]

    def session_id_from_log(self, log_path: Path) -> str | None:
        for obj in _iter_json_lines(Path(log_path)):
            if obj.get("type") == "thread.started":
                sid = obj.get("session_id") or obj.get("thread_id")
                if isinstance(sid, str) and sid:
                    return sid
        return None

    def observed_model(self, log_path: Path) -> tuple[str | None, str | None]:
        for obj in _iter_json_lines(Path(log_path)):
            if obj.get("type") in ("thread.started", "turn.started", "configured"):
                model = obj.get("model")
                effort = obj.get("model_reasoning_effort") or obj.get("effort")
                if model or effort:
                    return (
                        model if isinstance(model, str) and model else None,
                        effort if isinstance(effort, str) and effort else None,
                    )
        return None, None


_ADAPTER_CLASSES: dict[str, type] = {"claude": ClaudeAdapter, "codex": CodexAdapter}


def build_adapter(provider: str, profile_path: Path | None = None) -> ProviderAdapter:
    """Factory used by `orchestrator.py`. `profile_path` is only consulted
    for the Claude adapter (`--settings`); it is ignored otherwise."""
    if provider not in _ADAPTER_CLASSES:
        raise ValueError(f"unknown provider {provider!r}; known: {sorted(_ADAPTER_CLASSES)}")
    if provider == "claude":
        return ClaudeAdapter(profile_path or Path("/dev/null"))
    return CodexAdapter()
