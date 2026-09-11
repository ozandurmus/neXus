"""GOV.PO.3 -- deterministic local orchestration of an approved movement
(docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, FROZEN).

    py scripts/orchestrator.py start      --movement <relay-id> [options]
    py scripts/orchestrator.py run        --movement <relay-id> [--timeout S] [--heartbeat-timeout S] [--no-verify] [options]
    py scripts/orchestrator.py verify     --movement <relay-id> [options]
    py scripts/orchestrator.py status     [--movement <relay-id>] [options]
    py scripts/orchestrator.py stop       --movement <relay-id> [--force] [options]
    py scripts/orchestrator.py merge-lock {acquire|release} --movement <relay-id> [options]
    py scripts/orchestrator.py watch      [--interval SECONDS] [options]
    py scripts/orchestrator.py dashboard  [--port N] [options]
    py scripts/orchestrator.py usage      [--movement <relay-id>] [--since ISO] [--json] [options]

`<relay-id>` is the relay file's own id (`NXS-LOCAL-NNNN`), unambiguous and
resolvable to exactly one file (`relay/<relay-id>-*.json`).

`start` reads the movement's already-approved `SESSION_START` (`entries[0]`
of its canonical relay file), computes its SHA-256 content hash, creates one
git worktree from the packet's own `report.git.base`/`report.git.lane`
fields, writes the canonical bytes to `.nexus/approved_task.json` inside
that worktree, and spawns a detached `claude -p` engineer process there
using the engineer permission profile
(`.claude/nexus-engineer.settings.json`) -- then returns immediately. It
does not review, decide, or merge anything; merge authority stays with the
engineer's own session under the standing authorization
(`ozandurmus/nexus-agent-relay#13`). `merge-lock` is the cross-movement
serialization primitive that profile's `PreToolUse` hook
(`scripts/nexus_engineer_tool_gate.py`) calls before a `gh pr merge`.

Every subprocess this module spawns is built as an argument list
(`subprocess.run`/`Popen` with the default `shell=False`), never a
formatted/interpolated shell string -- the approved-task content itself is
never placed in an argv element; it is written to a file the engineer
process reads with its own `Read` tool (section 3.2/3.5 of the FROZEN
amendment).

Process records are persistent but outside the relay file (one JSON file
per movement under `NEXUS_ORCHESTRATOR_STATE_DIR`, default a fixed
subdirectory of the system temp root -- runtime bookkeeping, not durable
governance history). Liveness is always checked live (`os.kill(pid, 0)`),
never inferred from a stale timestamp: this is stricter and simpler than a
staleness-threshold heuristic and removes any dependency on clock skew.

Offline except for the subprocesses it spawns (`git`, `claude`, `gh`
indirectly via the engineer session). No device, credential, or vendor
collector access; stdlib only.

Exit codes: 0 success, 1 invalid/refused (wrong turn, duplicate start, no
free worker slot, lock held by another movement), 2 usage error (bad
arguments, missing/invalid relay file).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))
import local_relay as lr  # noqa: E402
import orchestrator_verify as ov  # noqa: E402
import orchestrator_providers as op  # noqa: E402
import orchestrator_usage as ou  # noqa: E402

EXIT_OK, EXIT_REFUSED, EXIT_USAGE = 0, 1, 2

DEFAULT_WORKTREES_DIR = REPO_ROOT.parent / f"{REPO_ROOT.name}-nexus-worktrees"

#: GOV.ORCH.1 section 2.5: the default moves from the system temp directory
#: to a sibling of the movement worktrees -- runtime bookkeeping, never
#: inside the repository checkout, but no longer sharing the system temp
#: root with every other tool on the machine. `NEXUS_ORCHESTRATOR_STATE_DIR`
#: still overrides this, exactly as before.
DEFAULT_STATE_DIR = Path(
    os.environ.get("NEXUS_ORCHESTRATOR_STATE_DIR")
    or str(DEFAULT_WORKTREES_DIR / ".state")
)

#: GOV.ORCH.1 section 2.5 / AC-9: the pre-GOV.ORCH.1 default location.
#: `status` still reads a record found only here for one release, flagging
#: it `legacy_state_dir` rather than losing track of it.
LEGACY_STATE_DIR = Path(os.path.join(tempfile.gettempdir(), "nexus_orchestrator"))

#: GOV.ORCH.4 section 3.2: the PO-owned, hand-maintained token price table.
DEFAULT_PRICE_TABLE_PATH = REPO_ROOT / "config" / "model_prices.json"

DEFAULT_MAX_WORKERS = 3
DEFAULT_RETRY_LIMIT = 2
DEFAULT_MERGE_LOCK_TTL = 600
DEFAULT_ENGINEER_PROFILE = REPO_ROOT / ".claude" / "nexus-engineer.settings.json"
#: An unattended spawn has no human watching live spend; bound it. Not part
#: of the FROZEN amendment's own text -- a safety addition made before the
#: first real dispatch, named as such in this movement's own evidence.
DEFAULT_MAX_BUDGET_USD = 3.0

#: AC-3 (orchestrator_background_task_exit_race, relay/NXS-LOCAL-0019):
#: verified against the installed `claude` CLI's own runtime behavior (its
#: shipped code reads BASH_DEFAULT_TIMEOUT_MS as the fallback timeout for a
#: Bash-tool call that does not pass its own explicit `timeout`, default
#: 120000ms -- exactly the "120s timeout" the incident's log line named --
#: and separately reads BASH_MAX_TIMEOUT_MS, floored at that same default,
#: as the ceiling on any explicit `timeout` a tool call does pass). Setting
#: only BASH_DEFAULT_TIMEOUT_MS raises both: the fallback itself, and (since
#: the ceiling's own fallback is `max(600000, BASH_DEFAULT_TIMEOUT_MS)`) the
#: ceiling too. This does not disable auto-backgrounding outright -- no such
#: switch was found -- it only buys a full pytest regression more foreground
#: headroom before the CLI would consider backgrounding it; ENGINEER_PROMPT's
#: own instruction (never treat validation as backgroundable; wait for a real
#: result) is what AC-3 actually leans on for the guarantee.
DEFAULT_ENGINEER_BASH_TIMEOUT_MS = 1_800_000  # 30 minutes

#: AC-3's dashboard default poll interval, and AC-2's engineer-log summary
#: line/argument truncation widths -- short enough that `watch`'s table
#: stays legible without wrapping on an ordinary terminal.
WATCH_INTERVAL_DEFAULT = 5
SUMMARY_TEXT_LIMIT = 100
SUMMARY_ARG_LIMIT = 60
#: How often the summary tailer companion process (spawned alongside the
#: engineer process, see _spawn_engineer) re-polls engineer.log for growth.
TAIL_POLL_INTERVAL_DEFAULT = 1.0

PHASE_RUNNING = "running"
PHASE_DONE = "done"
PHASE_FAILED = "failed"
PHASE_CANCELLED = "cancelled"
TERMINAL_PHASES = frozenset({PHASE_DONE, PHASE_FAILED, PHASE_CANCELLED})

#: The fixed prompt handed to every engineer process, identical for every
#: movement -- never task-content-dependent (section 3.5 of the FROZEN
#: amendment: the approved task itself is never interpolated into an argv
#: element; the engineer reads it with its own Read tool).
#: GOV.ORCH.3 Part A.2: shrunk to point at the generated worker brief --
#: the interactive reading order (AGENTS.md/AI_START_HERE.md/CLAUDE.md) and
#: the pytest-foreground/relay-re-read rules move into WORKER.md itself
#: (sections 7-8, see `render_worker_md`), never restated here.
ENGINEER_PROMPT = (
    "Read .nexus/WORKER.md and .nexus/approved_task.json in the current "
    "directory and follow them. Read nothing else unless WORKER.md names it."
)

#: AC-4: injected as an extra line onto ENGINEER_PROMPT for one specific
#: resume dispatch -- when the detection in `needs_resume_recovery_note`
#: fires (staged-but-uncommitted git changes, relay still at its own
#: SESSION_START, prior pid dead: the exact pattern observed live in
#: relay/NXS-LOCAL-0018) -- so the resumed session is told this explicitly
#: rather than left to notice it unprompted.
RESUME_RECOVERY_NOTE = (
    "Before doing anything else this turn: this resume was dispatched "
    "because your worktree has staged-but-uncommitted git changes while the "
    "relay is still at its own SESSION_START (no RELAY_ACK/RELAY_NOTE/"
    "RELAY_QUESTION/SESSION_CLOSE was ever posted) and the prior engineer "
    "process is dead -- this matches the known background-task-exit-race "
    "pattern, where a previous turn's own long-running validation command "
    "outlived that turn without the session waiting to see its result. "
    "Run `git status` and `git log` to find the prior staged-but-uncommitted "
    "work, and check whether a background task from the prior turn has a "
    "pending or already-completed result, before taking any further action -- "
    "in particular, before committing/pushing/merging on the strength of any "
    "test run, confirm it actually finished and you saw the real result."
)


class OrchestratorError(Exception):
    """A CLI-level request could not be satisfied."""


# ---------------------------------------------------------------------------
# Pure helpers (unit-testable without spawning anything)
# ---------------------------------------------------------------------------

def canonical_json(obj: Any) -> str:
    """The exact serialization used both to hash the approved task and to
    write it to disk -- reusing local_relay.py's own `_dump` convention so
    the hash matches the bytes actually written, never a re-derived
    approximation of them."""
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def task_hash(session_start_entry: dict) -> str:
    return hashlib.sha256(canonical_json(session_start_entry).encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# GOV.ORCH.3 Part A: WORKER.md -- a fixed-template worker brief, never a
# model call, rendered from `approved_task.json` at dispatch.
# ---------------------------------------------------------------------------

#: GOV.ORCH.7 section 2.1: these two fixed sections are no longer Python
#: string literals -- they are parsed, verbatim, from the checked-in
#: template `roles/WORKER.md` (the same file `render_worker_md` documents
#: itself against), so the template lives in one place, readable without
#: Python. `_load_worker_md_template` does the parse; module import time
#: populates RELAY_CLOSEOUT_TEXT/STANDING_RULES_TEXT from it exactly once.
ROLES_WORKER_MD_PATH = REPO_ROOT / "roles" / "WORKER.md"


def _load_worker_md_template(path: Path = ROLES_WORKER_MD_PATH) -> tuple[str, str]:
    """Extract the "## Relay closeout" and "## Standing rules" section
    bodies from `roles/WORKER.md`, verbatim (trailing/leading blank lines
    stripped only). Raises if either section is missing -- a missing
    template is a fail-closed condition for `preflight`, not a silent
    empty brief."""
    text = path.read_text(encoding="utf-8")
    closeout_marker = "## Relay closeout\n\n"
    standing_marker = "\n\n## Standing rules\n\n"
    if closeout_marker not in text or standing_marker not in text:
        raise OrchestratorError(f"{path} is missing the expected WORKER.md template sections")
    _, rest = text.split(closeout_marker, 1)
    closeout, standing = rest.split(standing_marker, 1)
    return closeout.strip(), standing.strip()


try:
    RELAY_CLOSEOUT_TEXT, STANDING_RULES_TEXT = _load_worker_md_template()
except (OSError, OrchestratorError):
    # roles/WORKER.md missing at import time (e.g. a stale checkout) --
    # leave both empty rather than crashing import; `preflight` fails
    # closed on the missing file for any real dispatch.
    RELAY_CLOSEOUT_TEXT, STANDING_RULES_TEXT = "", ""

_TEST_FILE_RE = re.compile(r"tests/[\w./-]+\.py")


def _worker_md_test_files(validation_plan: list) -> list[str]:
    """A.2 §2: "the direct tests named in the validation plan" -- scraped
    from string entries and object-form `argv` entries alike, de-duplicated,
    order preserved."""
    found: list[str] = []
    for entry in validation_plan or []:
        tokens: list[str] = []
        if isinstance(entry, str):
            tokens = [entry]
        elif isinstance(entry, dict):
            tokens = [str(a) for a in (entry.get("argv") or [])]
        for tok in tokens:
            for match in _TEST_FILE_RE.findall(tok):
                if match not in found:
                    found.append(match)
    return found


def _worker_md_validation_commands(validation_plan: list) -> str:
    """A.2 §5: object-form entries render as fenced commands; string
    (prose) entries are listed under "manual"."""
    fenced: list[str] = []
    manual: list[str] = []
    for entry in validation_plan or []:
        if isinstance(entry, str):
            manual.append(entry)
        elif isinstance(entry, dict):
            argv = entry.get("argv")
            name = entry.get("name")
            if argv:
                cmd = " ".join(str(a) for a in argv)
                header = f"# {name}\n" if name else ""
                fenced.append(f"{header}```\n{cmd}\n```")
            elif name:
                manual.append(name)
    parts = list(fenced)
    if manual:
        parts.append("manual:\n" + "\n".join(f"- {item}" for item in manual))
    return "\n\n".join(parts) if parts else "(none)"


def _bullets(items: list) -> str:
    items = list(items or [])
    return "\n".join(f"- {item}" for item in items) if items else "(none)"


def render_worker_md(*, movement_id: str, session_start: dict, merge_mode: str) -> str:
    """GOV.ORCH.3 Part A.2: the fixed template rendered to `.nexus/WORKER.md`
    at dispatch -- no model call, content-and-order fixed by contract. The
    eight sections, in order: (1) movement id/objective/type, (2) files you
    may read / must not touch, (3) acceptance criteria, (4) invariants and
    risks, (5) validation commands, (6) git, (7) relay closeout, (8)
    standing rules."""
    report = session_start.get("report", {})
    scope = report.get("scope", {})
    scope_in = list(scope.get("in", []) or [])
    scope_out = list(scope.get("out", []) or [])
    validation_plan = report.get("validation_plan", []) or []
    git_cfg = report.get("git", {})

    sections = [
        (
            f"Movement: {movement_id}\n\n"
            f"Objective: {report.get('objective', '')}\n\n"
            f"Movement type: {report.get('movement_type', '')}"
        ),
        (
            "## Files you may read\n\n"
            + _bullets(scope_in + _worker_md_test_files(validation_plan))
            + "\n\n## Files you must not touch\n\n"
            + _bullets(scope_out)
        ),
        "## Acceptance criteria\n\n" + _bullets(report.get("acceptance_criteria", [])),
        (
            "## Invariants and risks\n\n"
            "Invariants:\n" + _bullets(report.get("invariants", []))
            + "\n\nRisks:\n" + _bullets(report.get("risks", []))
        ),
        "## Validation commands\n\n" + _worker_md_validation_commands(validation_plan),
        (
            "## Git\n\n"
            f"- base: {git_cfg.get('base', '')}\n"
            f"- lane: {git_cfg.get('lane', '')}\n"
            f"- merge gate: {report.get('merge_gate', '')}\n"
            f"- merge mode: {merge_mode}"
            + ("\n\n" + op.NO_MERGE_PROMPT_NOTE if merge_mode == "orchestrator" else "")
        ),
        "## Relay closeout\n\n" + RELAY_CLOSEOUT_TEXT,
        "## Standing rules\n\n" + STANDING_RULES_TEXT,
    ]
    return "# WORKER.md\n\n" + "\n\n".join(sections) + "\n"


# ---------------------------------------------------------------------------
# GOV.ORCH.3 Part B: per-worktree pre-push git hook install.
# ---------------------------------------------------------------------------

#: The pre-push wrapper git actually invokes -- a thin shell shim so the
#: hooksPath mechanism (which expects an executable file, not a python
#: script directly) can hand off to the real hook body.
_PREPUSH_HOOK_TEMPLATE = (
    "#!/bin/sh\n"
    "exec python3 \"{script}\" \"{record}\"\n"
)


def install_prepush_hook(worktree_path: Path, record_path: Path | None = None) -> Path:
    """GOV.ORCH.3 Part B.2 item 1: install a `pre-push` hook scoped to this
    worktree only -- `git config --worktree core.hooksPath` (requiring
    `extensions.worktreeConfig=true`, set first) plus the hook file itself,
    executable. Returns the installed hook path. Never touches the main
    checkout's own hooksPath (each worktree's `--worktree` config is local
    to that worktree's own config section)."""
    # `--worktree` itself only works once `extensions.worktreeConfig` is
    # already on -- a chicken-and-egg that only the plain (repo-wide) form
    # of `git config` can resolve from inside a linked worktree.
    subprocess.run(
        ["git", "config", "extensions.worktreeConfig", "true"],
        cwd=str(worktree_path), check=True,
    )
    hooks_dir = worktree_path / ".nexus" / "hooks"
    hooks_dir.mkdir(parents=True, exist_ok=True)
    script_path = Path(__file__).resolve().parent / "nexus_worker_prepush.py"
    hook_path = hooks_dir / "pre-push"
    record = str(record_path) if record_path else ""
    hook_path.write_text(
        _PREPUSH_HOOK_TEMPLATE.format(script=script_path, record=record), encoding="utf-8",
    )
    hook_path.chmod(0o755)
    subprocess.run(
        ["git", "config", "--worktree", "core.hooksPath", str(hooks_dir)],
        cwd=str(worktree_path), check=True,
    )
    return hook_path


def validate_git_refs(base: str, lane: str) -> tuple[bool, str]:
    """Mirrors, and does not relax, the two argument-shape restrictions
    GOV_PO_1_GATE_5 already puts on the PO role's own `git worktree add`:
    the base commit-ish must be an `origin/` ref, the branch must be a
    `feature/*` lane -- `gov/po-*` stays reserved for the PO's own
    governance branch."""
    if not base or not base.startswith("origin/"):
        return False, f"report.git.base must start with 'origin/' (got {base!r})"
    if not lane or not lane.startswith("feature/"):
        return False, f"report.git.lane must start with 'feature/' (got {lane!r})"
    return True, "ok"


def pid_alive(pid: int) -> bool:
    if not pid:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True  # exists, owned by someone else -- still alive
    except OSError:
        return False
    return True


def decide_start(
    *, relay_obj: dict, existing_record: dict | None, is_pid_alive: bool,
    active_count: int, max_workers: int,
) -> tuple[str, str]:
    """Pure dispatch decision. Returns (action, reason); action is one of
    "dispatch" (fresh worktree + spawn), "resume" (same worktree/branch/
    session, no new worktree), or "refuse"."""
    next_actor = relay_obj.get("next_actor")
    if next_actor != "engineer":
        return "refuse", f"not engineer's turn (next_actor is {next_actor!r})"

    if existing_record is not None and existing_record.get("phase") not in TERMINAL_PHASES:
        if is_pid_alive:
            return "refuse", (
                f"movement already running (pid {existing_record.get('pid')}, "
                f"phase {existing_record.get('phase')})"
            )
        return "resume", "recovering an interrupted run (same worktree, branch, and session)"

    # No record, or the prior run ended terminally: a fresh dispatch, which
    # needs a free worker slot. (A resume above is not a NEW slot -- it is
    # already occupying the one it was given originally.)
    if active_count >= max_workers:
        return "refuse", f"max_workers ({max_workers}) reached; no free slot"
    return "dispatch", "fresh dispatch"


def relay_never_advanced_past_session_start(relay_obj: dict) -> bool:
    """True iff `entries` holds only its own SESSION_START (index 0) -- no
    RELAY_ACK/RELAY_NOTE/RELAY_QUESTION/RELAY_DECISION/RELAY_CORRECTION or
    SESSION_CLOSE was ever appended after dispatch."""
    entries = relay_obj.get("entries") or []
    return len(entries) <= 1


def has_staged_uncommitted_changes(porcelain_status: str) -> bool:
    """Parses already-captured `git status --porcelain` output (never spawns
    git itself -- callers pass the text in, keeping this a pure function per
    AC-6). Porcelain's first column is the index/staged status; anything
    other than ' ' (unstaged-only) or '?' (untracked) there means the file
    has a staged, uncommitted change."""
    for line in porcelain_status.splitlines():
        if not line:
            continue
        if line[0] not in (" ", "?"):
            return True
    return False


def needs_resume_recovery_note(
    *, relay_obj: dict, has_staged_uncommitted_changes: bool, is_pid_alive: bool,
) -> bool:
    """AC-4's detection: the exact background-task-exit-race pattern observed
    live in relay/NXS-LOCAL-0018 -- staged-but-uncommitted git changes, the
    relay still at its own SESSION_START, and the recorded pid dead. Only
    ever consulted from the resume branch of `decide_start` (where the pid
    being dead is already established), but takes `is_pid_alive` explicitly
    so this stays correct and testable independent of that caller."""
    if is_pid_alive or not has_staged_uncommitted_changes:
        return False
    return relay_never_advanced_past_session_start(relay_obj)


def decide_merge_lock_acquire(
    *, lock_record: dict | None, requester_movement: str, requester_pid: int,
    now: float, ttl: int, is_pid_alive=pid_alive,
) -> tuple[bool, dict | None, str]:
    """Pure lock-acquisition decision (section 3.8 of the FROZEN amendment):
    a lock whose holder is dead or whose TTL elapsed is treated as
    abandoned and reclaimed, so a crash mid-merge cannot wedge every other
    movement's integration permanently."""
    if lock_record is None:
        return True, {"movement_id": requester_movement, "pid": requester_pid, "acquired_at": now}, "lock free"
    if lock_record.get("movement_id") == requester_movement:
        return True, {**lock_record, "pid": requester_pid, "acquired_at": now}, "already held by this movement (re-entrant)"
    holder_alive = is_pid_alive(lock_record.get("pid"))
    stale = (now - lock_record.get("acquired_at", 0)) > ttl
    if not holder_alive or stale:
        return True, {"movement_id": requester_movement, "pid": requester_pid, "acquired_at": now}, (
            "reclaimed an abandoned/stale lock"
        )
    return False, lock_record, f"held by {lock_record.get('movement_id')} (pid {lock_record.get('pid')})"


def reconcile_failure_reason(
    record: dict, phase: str, prior_phase: str,
) -> str | None:
    """GOV.ORCH.1 section 2.3: `failure_reason` is set when a record is
    opportunistically reconciled to `failed` for a reason other than an
    already-recorded one (e.g. `run`'s own `timeout`/`heartbeat_timeout`) --
    here, specifically, a dead process that has exhausted `retry_limit`."""
    if phase != PHASE_FAILED or prior_phase == PHASE_FAILED:
        return record.get("failure_reason")
    return record.get("failure_reason") or "retry_limit_exceeded"


def reconcile_phase(record: dict, is_pid_alive_now: bool, relay_status: str | None, retry_limit: int) -> str:
    """Opportunistic phase reconciliation, run whenever `status`/`start`
    observes a record: never the sole source of truth (the relay file and a
    live PID check always are), just keeps the persisted `phase` field from
    silently lying to a human reading `status` output."""
    phase = record.get("phase", PHASE_RUNNING)
    if phase in TERMINAL_PHASES:
        return phase
    if relay_status == "CLOSED":
        return PHASE_DONE
    if not is_pid_alive_now and record.get("retry_count", 0) >= retry_limit:
        return PHASE_FAILED
    return phase


# ---------------------------------------------------------------------------
# AC-2: stream-json engineer.log -> engineer.summary.log line parser
# ---------------------------------------------------------------------------

def _truncate(text: str, limit: int) -> str:
    """Collapse internal whitespace (a stream-json line's own text/thinking
    fields are frequently multi-line) then hard-truncate -- never raise on
    an over-long field, one of AC-6's own required behaviors."""
    collapsed = " ".join(str(text).split())
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[: max(limit - 1, 0)].rstrip() + "\u2026"


def _tool_arg_summary(tool_input: dict) -> str:
    """The short argument summary half of AC-2's "tool name + short argument
    summary" -- prefers whichever field a human would actually want to see
    at a glance, falling back to the whole (truncated) input for a tool
    shape this list does not name explicitly."""
    if not isinstance(tool_input, dict):
        return ""
    for key in ("command", "file_path", "path", "pattern", "description", "prompt", "query", "url"):
        value = tool_input.get(key)
        if isinstance(value, str) and value:
            return _truncate(value, SUMMARY_ARG_LIMIT)
    if tool_input:
        return _truncate(json.dumps(tool_input, sort_keys=True), SUMMARY_ARG_LIMIT)
    return ""


def _tool_result_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = [c.get("text", "") for c in content if isinstance(c, dict)]
        return " ".join(p for p in parts if p)
    return json.dumps(content) if content is not None else ""


def summarize_stream_json_line(raw_line: str) -> str | None:
    """One raw `claude -p --output-format stream-json` line -> one short
    human-readable summary line, or `None` if the line should be skipped
    (blank, or invalid JSON). Invalid JSON is deliberately treated as "not
    yet a complete line" rather than an error: the tailer (`_drain_log_once`)
    re-reads from the same offset once more bytes are appended, so a line
    truncated mid-write at read time is silently retried, never dropped or
    crashed on (AC-6). A syntactically valid but unrecognized event shape
    still produces a line -- a truncated raw-JSON fallback -- because that
    case is a real event this parser simply does not know how to summarize
    yet, not an incomplete read (the risk this movement's own approved task
    calls out explicitly: never silently drop a real event)."""
    line = raw_line.strip()
    if not line:
        return None
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return None
    if not isinstance(obj, dict):
        return _truncate(line, SUMMARY_TEXT_LIMIT)

    kind = obj.get("type")
    if kind == "assistant":
        blocks = ((obj.get("message") or {}).get("content")) or []
        parts = []
        for block in blocks:
            if not isinstance(block, dict):
                continue
            btype = block.get("type")
            if btype == "text":
                parts.append(f"assistant: {_truncate(block.get('text', ''), SUMMARY_TEXT_LIMIT)}")
            elif btype == "tool_use":
                name = block.get("name", "?")
                arg = _tool_arg_summary(block.get("input") or {})
                parts.append(f"tool_use: {name} {arg}".rstrip())
            elif btype == "thinking":
                parts.append("assistant: (thinking)")
        if parts:
            return " | ".join(parts)
        return _truncate(line, SUMMARY_TEXT_LIMIT)
    if kind == "user":
        blocks = ((obj.get("message") or {}).get("content")) or []
        for block in blocks:
            if isinstance(block, dict) and block.get("type") == "tool_result":
                status = "error" if block.get("is_error") else "ok"
                text = _tool_result_text(block.get("content"))
                return f"tool_result ({status}): {_truncate(text, SUMMARY_TEXT_LIMIT)}"
        return _truncate(line, SUMMARY_TEXT_LIMIT)
    if kind == "result":
        subtype = obj.get("subtype", "?")
        result = obj.get("result")
        suffix = f": {_truncate(result, SUMMARY_TEXT_LIMIT)}" if isinstance(result, str) and result else ""
        return f"result ({subtype}){suffix}"
    if kind == "system":
        return f"system: {obj.get('subtype', '?')}"
    if kind == "rate_limit_event":
        status = (obj.get("rate_limit_info") or {}).get("status", "?")
        return f"rate_limit: {status}"
    # Recognized JSON, unrecognized shape -- fall back rather than drop it.
    return _truncate(line, SUMMARY_TEXT_LIMIT)


def _drain_log_once(log_path: Path, summary_fh, offset: int, summarize=summarize_stream_json_line) -> int:
    """Read whatever complete lines have been appended to `log_path` since
    byte `offset`, append their summaries to the already-open `summary_fh`,
    and return the new offset. A line with no trailing newline yet (the
    writer is mid-write) is left unconsumed -- the next call re-reads it
    once it is complete, per AC-6's truncated-last-line requirement. Binary
    mode throughout so the byte offset this function returns is always
    exactly re-seekable, regardless of any single line's own encoding."""
    if not log_path.is_file():
        return offset
    with open(log_path, "rb") as log_fh:
        log_fh.seek(offset)
        data = log_fh.read()
    if not data:
        return offset
    pieces = data.split(b"\n")
    complete = pieces[:-1]
    consumed = len(data) if data.endswith(b"\n") else len(data) - len(pieces[-1])
    wrote = False
    for raw in complete:
        summary = summarize(raw.decode("utf-8", errors="replace"))
        if summary is not None:
            summary_fh.write(summary + "\n")
            wrote = True
    if wrote:
        summary_fh.flush()
    return offset + consumed


def _tail_engineer_log(
    log_path: Path, summary_path: Path, pid: int, poll_interval: float = TAIL_POLL_INTERVAL_DEFAULT,
    summarize=summarize_stream_json_line,
) -> None:
    """AC-2's companion process: `_spawn_engineer` returns immediately
    (section 3.4 of the FROZEN amendment), so nothing in the orchestrator's
    own `start` invocation lives long enough to keep tailing `engineer.log`
    -- this runs detached, independently of the parent that spawned it,
    for exactly as long as the engineer pid it was given stays alive (plus
    one final drain after it exits, to catch whatever was written between
    the last poll and process exit)."""
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    offset = 0
    with open(summary_path, "a", encoding="utf-8") as summary_fh:
        while True:
            offset = _drain_log_once(log_path, summary_fh, offset, summarize=summarize)
            if not pid_alive(pid):
                offset = _drain_log_once(log_path, summary_fh, offset, summarize=summarize)
                return
            time.sleep(poll_interval)


def _read_last_activity(worktree_path: str | None) -> str | None:
    """AC-4/AC-5: the most recent non-blank `engineer.summary.log` line for
    a movement's worktree, or `None` -- never an exception -- when the
    worktree path is unknown, the file does not exist yet (a movement
    dispatched before this change, or one whose tailer has not written
    anything yet), or it is empty."""
    if not worktree_path:
        return None
    summary_path = Path(worktree_path) / ".nexus" / "engineer.summary.log"
    try:
        text = summary_path.read_text(encoding="utf-8")
    except OSError:
        return None
    for line in reversed(text.splitlines()):
        if line.strip():
            return line.strip()
    return None


def _heartbeat_age_seconds(record: dict, now: float | None = None) -> float | None:
    ts = record.get("heartbeat_at")
    if not ts:
        return None
    try:
        started = datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()
    except ValueError:
        return None
    return (time.time() if now is None else now) - started


def _format_age(seconds: float | None) -> str:
    if seconds is None or seconds < 0:
        return "-"
    total = int(seconds)
    if total < 60:
        return f"{total}s"
    minutes, secs = divmod(total, 60)
    if minutes < 60:
        return f"{minutes}m{secs:02d}s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h{minutes:02d}m"


def _truncate_to_width(text: str, width: int) -> str:
    if width <= 0 or len(text) <= width:
        return text
    return text[: max(width - 1, 0)] + "\u2026"


# ---------------------------------------------------------------------------
# State-file IO (reuses local_relay.py's own atomic-write discipline)
# ---------------------------------------------------------------------------

def _state_path(state_dir: Path, movement_id: str) -> Path:
    return state_dir / f"{movement_id}.json"


def _load_state(state_dir: Path, movement_id: str) -> dict | None:
    path = _state_path(state_dir, movement_id)
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _save_state(state_dir: Path, movement_id: str, record: dict) -> None:
    lr._atomic_write(_state_path(state_dir, movement_id), lr._dump(record))


def _list_state_records(state_dir: Path) -> list[dict]:
    if not state_dir.is_dir():
        return []
    records = []
    for path in sorted(state_dir.glob("NXS-LOCAL-*.json")):
        try:
            records.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError):
            continue
    return records


def _list_state_records_with_legacy(state_dir: Path, legacy_dir: Path) -> list[tuple[dict, bool]]:
    """AC-9: every record under `state_dir`, plus any record found only
    under `legacy_dir` (the pre-GOV.ORCH.1 default) -- each paired with
    whether it came from the legacy location. A movement present in both is
    reported once, from `state_dir`."""
    seen = set()
    out: list[tuple[dict, bool]] = []
    for record in _list_state_records(state_dir):
        seen.add(record.get("movement_id"))
        out.append((record, False))
    if legacy_dir.resolve() != state_dir.resolve():
        for record in _list_state_records(legacy_dir):
            if record.get("movement_id") in seen:
                continue
            out.append((record, True))
    return out


def _active_count(state_dir: Path, exclude_movement: str | None = None) -> int:
    count = 0
    for record in _list_state_records(state_dir):
        if record.get("movement_id") == exclude_movement:
            continue
        if record.get("phase") not in TERMINAL_PHASES and pid_alive(record.get("pid")):
            count += 1
    return count


# ---------------------------------------------------------------------------
# Relay file resolution
# ---------------------------------------------------------------------------

def _resolve_relay_file(relay_dir: Path, movement_id: str) -> Path:
    matches = sorted(relay_dir.glob(f"{movement_id}-*.json"))
    if not matches:
        raise OrchestratorError(f"no relay file found for {movement_id} under {relay_dir}")
    if len(matches) > 1:
        raise OrchestratorError(f"more than one relay file matches {movement_id} under {relay_dir}: {matches}")
    return matches[0]


def _load_relay(relay_file: Path) -> dict:
    obj = json.loads(relay_file.read_text(encoding="utf-8"))
    errors = lr.validate_relay_object(obj)
    if errors:
        raise OrchestratorError(f"{relay_file} fails validation: {'; '.join(errors)}")
    return obj


# ---------------------------------------------------------------------------
# Git / subprocess side effects (argument lists only -- never shell text)
# ---------------------------------------------------------------------------

def _git_rev_parse(ref: str, cwd: Path) -> str:
    result = subprocess.run(["git", "rev-parse", ref], cwd=str(cwd), capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        raise OrchestratorError(f"git rev-parse {ref} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def _git_worktree_add(worktree_path: Path, base: str, branch: str, cwd: Path) -> None:
    result = subprocess.run(
        # <path> <commit-ish> -b <branch> -- matches the exact order
        # GOV_PO_1_GATE_5 already validated for the PO role's own
        # `git worktree add` calls (nexus_po_tool_gate.py, tests/test_gov_po_role.py).
        ["git", "worktree", "add", str(worktree_path), base, "-b", branch],
        cwd=str(cwd), capture_output=True, text=True, timeout=60,
    )
    if result.returncode != 0:
        raise OrchestratorError(f"git worktree add failed: {result.stderr.strip()}")


def _git_worktree_remove(worktree_path: Path, cwd: Path) -> None:
    subprocess.run(["git", "worktree", "remove", str(worktree_path)], cwd=str(cwd),
                    capture_output=True, text=True, timeout=60)


def _git_status_porcelain(worktree_path: Path) -> str:
    """AC-4: feeds `has_staged_uncommitted_changes`. Advisory only -- a
    failure here (e.g. the worktree directory is gone) must not block a
    resume that would otherwise proceed, so it is swallowed and treated as
    "nothing staged" rather than raised."""
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"], cwd=str(worktree_path),
            capture_output=True, text=True, timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    if result.returncode != 0:
        return ""
    return result.stdout


def _spawn_summary_tailer(log_path: Path, summary_path: Path, pid: int, provider: str = "claude") -> None:
    """AC-2: a second detached process, independent of both the engineer
    process and of `start`'s own (immediately-returning) invocation, that
    keeps `engineer.summary.log` growing for as long as the engineer pid it
    was given stays alive. Never awaited/joined -- `_tail-summary` is an
    internal subcommand, not part of the documented CLI surface.

    GOV.ORCH.2: `--provider` tells `_cmd_tail_summary` which adapter's
    `summarize_line` to use -- the tailer runs as a separate process, so it
    cannot simply be handed the adapter object the spawning call already
    built."""
    subprocess.Popen(
        [sys.executable, str(Path(__file__).resolve()), "_tail-summary",
         "--log", str(log_path), "--summary", str(summary_path), "--pid", str(pid),
         "--provider", provider],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL,
        start_new_session=True,
    )


def _spawn_engineer(
    *, worktree_path: Path, profile_path: Path, canonical_relay_dir: Path,
    relay_file: Path, resume_session_id: str | None = None,
    max_budget_usd: float = DEFAULT_MAX_BUDGET_USD,
    extra_prompt_note: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    provider: str = "claude",
) -> subprocess.Popen:
    adapter = op.build_adapter(provider, profile_path=profile_path)
    env = dict(os.environ)
    env[lr.ENV_CANONICAL_RELAY_DIR] = str(canonical_relay_dir)
    env[lr.ENV_RELAY_FILE] = str(relay_file)
    # orchestrator_tool_gate_hook_cwd_relative_script_path_footgun: the
    # PreToolUse/PostToolUse hook command in .claude/nexus-engineer.settings.json
    # invokes this script by a path relative to the Bash tool's own tracked
    # session cwd, not the worktree root -- an engineer `cd` anywhere else
    # (even outside the worktree entirely) permanently breaks every future
    # Bash call for that session. NEXUS_WORKTREE_ROOT is a stable,
    # cwd-independent anchor the hook command resolves itself against instead.
    env["NEXUS_WORKTREE_ROOT"] = str(worktree_path)
    # AC-3: raise the engineer session's own Bash-tool auto-background
    # threshold (see DEFAULT_ENGINEER_BASH_TIMEOUT_MS) -- setdefault so an
    # operator's own pre-set env var is never overridden by this spawn.
    env.setdefault("BASH_DEFAULT_TIMEOUT_MS", str(DEFAULT_ENGINEER_BASH_TIMEOUT_MS))
    # AC-1/AC-2/AC-3 (relay/NXS-LOCAL-0015): with --permission-prompts none
    # there is no human to answer a Read/Edit/Write-tool permission prompt
    # for a path outside the worktree, so any such prompt is denied
    # automatically -- and, once denied, stays denied for the rest of the
    # session, no retries. A fresh dispatch only ever appeared to work
    # because the engineer session happened to reach the canonical relay
    # file through a Bash-invoked scripts/local_relay.py call, which this
    # per-tool directory allowlist does not gate; nothing guarantees an
    # engineer session -- fresh or resumed -- will choose Bash over its own
    # Read/Edit tools for a "read the relay file" instruction. --add-dir
    # pre-authorizes exactly the canonical relay directory for every tool,
    # identically for a fresh dispatch and a resume, so access no longer
    # depends on which tool the session happens to pick.
    # AC-1 (relay/NXS-LOCAL-0013): --output-format stream-json requires
    # --verbose alongside --print (confirmed against the installed claude
    # version's own --help/runtime error before writing this, per that
    # movement's own requirement) -- engineer.log now fills incrementally,
    # one JSON object per line, instead of only at process exit.
    # AC-4: for the one specific resume this fires on, `needs_resume_recovery_note`
    # appends RESUME_RECOVERY_NOTE onto the prompt for this dispatch only --
    # ENGINEER_PROMPT itself stays movement- and dispatch-invariant.
    prompt = ENGINEER_PROMPT if not extra_prompt_note else ENGINEER_PROMPT + "\n\n" + extra_prompt_note
    # GOV.ORCH.2 section 2.2: the prompt is written to a file first and the
    # adapter reads it back to build argv -- never composed as an in-memory
    # string the caller passes straight through. Content is unchanged
    # (identical to the pre-GOV.ORCH.2 in-memory value), only its source is.
    nexus_dir = worktree_path / ".nexus"
    nexus_dir.mkdir(parents=True, exist_ok=True)
    prompt_path = nexus_dir / "engineer_prompt.txt"
    prompt_path.write_text(prompt, encoding="utf-8")

    argv = adapter.build_argv(
        prompt_path=prompt_path, worktree=worktree_path, model=model, effort=effort,
        budget_usd=max_budget_usd, extra_dirs=[canonical_relay_dir],
        resume_session_id=resume_session_id,
    )
    env = adapter.build_env(env, worktree=worktree_path, relay_dir=canonical_relay_dir, relay_file=relay_file)
    stdin_src = adapter.stdin_source(prompt_path)
    log_path = worktree_path / ".nexus" / "engineer.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_fh = open(log_path, "ab")
    proc = subprocess.Popen(
        argv, cwd=str(worktree_path), env=env,
        stdout=log_fh, stderr=subprocess.STDOUT,
        stdin=stdin_src if stdin_src is not None else subprocess.DEVNULL,
        start_new_session=True,
    )
    summary_path = log_path.parent / "engineer.summary.log"
    _spawn_summary_tailer(log_path, summary_path, proc.pid, provider=provider)
    return proc


def _kill_pid(pid: int, force: bool = False, grace_seconds: float = 5.0) -> None:
    if not pid_alive(pid):
        return
    try:
        os.kill(pid, signal.SIGKILL if force else signal.SIGTERM)
    except OSError:
        return
    if force:
        return
    deadline = time.monotonic() + grace_seconds
    while time.monotonic() < deadline:
        if not pid_alive(pid):
            return
        time.sleep(0.2)
    try:
        os.kill(pid, signal.SIGKILL)
    except OSError:
        pass


# ---------------------------------------------------------------------------
# start
# ---------------------------------------------------------------------------

def _do_start(args: argparse.Namespace) -> tuple[int, dict | None, subprocess.Popen | None]:
    """The full body of `start` (dispatch or resume one approved movement),
    factored out so `run` (GOV.ORCH.1 section 2.1) reuses this exact code
    path rather than a copy: `_cmd_start` and `_cmd_run` both call this and
    only differ in what they do with the outcome. Returns
    `(exit_code, stdout_payload, proc)` -- `payload` is the JSON object
    `start` prints (`None` on refusal/usage error); `proc` is the spawned
    engineer `Popen`, `None` whenever no process was spawned."""
    relay_dir = Path(args.relay_dir).resolve()
    state_dir = Path(args.state_dir)
    worktrees_dir = Path(args.worktrees_dir)
    try:
        relay_file = _resolve_relay_file(relay_dir, args.movement)
        relay_obj = _load_relay(relay_file)
    except OrchestratorError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE, None, None

    existing = _load_state(state_dir, args.movement)
    is_alive = pid_alive(existing.get("pid")) if existing else False
    active = _active_count(state_dir, exclude_movement=args.movement)
    action, reason = decide_start(
        relay_obj=relay_obj, existing_record=existing, is_pid_alive=is_alive,
        active_count=active, max_workers=args.max_workers,
    )
    if action == "refuse":
        print(f"error: {reason}", file=sys.stderr)
        return EXIT_REFUSED, None, None

    session_start = relay_obj["entries"][0]
    report = session_start.get("report", {})
    git_cfg = report.get("git", {})
    base, lane = git_cfg.get("base"), git_cfg.get("lane")
    ok, ref_reason = validate_git_refs(base, lane)
    if not ok:
        print(f"error: {ref_reason}", file=sys.stderr)
        return EXIT_USAGE, None, None

    hash_hex = task_hash(session_start)

    provider = getattr(args, "provider", "claude") or "claude"
    try:
        op.validate_effort(provider, args.effort)
    except op.InvalidEffortError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE, None, None
    merge_mode = op.resolve_merge_mode(provider, getattr(args, "merge_mode", None))
    no_merge_note = op.NO_MERGE_PROMPT_NOTE if merge_mode == "orchestrator" else None

    if action == "resume":
        worktree_path = Path(existing["worktree_path"])
        branch = existing["branch"]
        base_sha = existing["base_sha"]
        revision = existing["revision"]
        session_id = existing.get("session_id")
        # AC-4: detect the background-task-exit-race pattern (staged-but-
        # uncommitted changes + relay never advanced past SESSION_START +
        # dead pid, the last of which is already established here -- a
        # resume is only reachable when is_pid_alive was False) and, if it
        # matches, inject RESUME_RECOVERY_NOTE onto this one dispatch's
        # prompt. Does not change the resume decision itself (AC-5).
        staged = has_staged_uncommitted_changes(_git_status_porcelain(worktree_path))
        recovery_note_needed = needs_resume_recovery_note(
            relay_obj=relay_obj, has_staged_uncommitted_changes=staged, is_pid_alive=False,
        )
        # GOV.ORCH.1 section 2.3: retry_count increments on every resume
        # dispatch and is persisted before the spawn, so a crash between
        # this write and the spawn still leaves the increment recorded.
        retry_count = existing.get("retry_count", 0) + 1
        _save_state(state_dir, args.movement, {**existing, "retry_count": retry_count})
        notes = [n for n in (RESUME_RECOVERY_NOTE if recovery_note_needed else None, no_merge_note) if n]
        proc = _spawn_engineer(
            worktree_path=worktree_path, profile_path=Path(args.profile),
            canonical_relay_dir=relay_dir, relay_file=relay_file,
            resume_session_id=session_id, max_budget_usd=args.max_budget_usd,
            extra_prompt_note="\n\n".join(notes) if notes else None,
            model=args.model,
            effort=args.effort,
            provider=provider,
        )
        record = {**existing, "pid": proc.pid, "phase": PHASE_RUNNING,
                  "last_action": "resumed", "task_hash": hash_hex,
                  "dispatch_seq": len(relay_obj["entries"]), "retry_count": retry_count,
                  "failure_reason": None, "exit_code": None,
                  "provider": provider, "model_requested": args.model, "effort_requested": args.effort,
                  "merge_mode": merge_mode}
        _save_state(state_dir, args.movement, record)
        payload = {"action": "resume", "movement_id": args.movement, "pid": proc.pid,
                   "worktree_path": str(worktree_path), "revision": revision,
                   "recovery_note_injected": recovery_note_needed}
        return EXIT_OK, payload, proc

    # action == "dispatch"
    revision = 1 if existing is None else existing.get("revision", 0) + 1
    worktree_path = worktrees_dir / args.movement
    if worktree_path.exists():
        print(f"error: worktree path already exists: {worktree_path}", file=sys.stderr)
        return EXIT_REFUSED, None, None
    try:
        base_sha = _git_rev_parse(base, relay_dir.parent)
        _git_worktree_add(worktree_path, base, lane, relay_dir.parent)
    except OrchestratorError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_REFUSED, None, None

    nexus_dir = worktree_path / ".nexus"
    nexus_dir.mkdir(parents=True, exist_ok=True)
    approved_task_path = nexus_dir / "approved_task.json"
    approved_task_path.write_text(canonical_json(session_start), encoding="utf-8")
    (nexus_dir / "movement_id.txt").write_text(args.movement + "\n", encoding="utf-8")
    (nexus_dir / "WORKER.md").write_text(
        render_worker_md(movement_id=args.movement, session_start=session_start, merge_mode=merge_mode),
        encoding="utf-8",
    )
    install_prepush_hook(worktree_path, record_path=approved_task_path)

    proc = _spawn_engineer(
        worktree_path=worktree_path, profile_path=Path(args.profile),
        canonical_relay_dir=relay_dir, relay_file=relay_file,
        max_budget_usd=args.max_budget_usd,
        model=args.model,
        effort=args.effort,
        provider=provider,
        extra_prompt_note=no_merge_note,
    )
    record = {
        "movement_id": args.movement, "revision": revision, "base_sha": base_sha,
        "task_hash": hash_hex, "worktree_path": str(worktree_path), "branch": lane,
        "pid": proc.pid, "session_id": None, "phase": PHASE_RUNNING,
        "started_at": lr._utc_now_iso(), "heartbeat_at": lr._utc_now_iso(),
        "retry_count": 0, "last_error": None, "last_action": "dispatched",
        "dispatch_seq": len(relay_obj["entries"]),
        "failure_reason": None, "exit_code": None,
        "provider": provider, "model_requested": args.model, "effort_requested": args.effort,
        "merge_mode": merge_mode,
    }
    _save_state(state_dir, args.movement, record)
    payload = {"action": "dispatch", "movement_id": args.movement, "pid": proc.pid,
               "worktree_path": str(worktree_path), "branch": lane, "revision": revision}
    return EXIT_OK, payload, proc


def _cmd_start(args: argparse.Namespace) -> int:
    rc, payload, _proc = _do_start(args)
    if payload is not None:
        print(json.dumps(payload))
    return rc


# ---------------------------------------------------------------------------
# run (GOV.ORCH.1 section 2.1): dispatch (via _do_start), wait, verify, report
# ---------------------------------------------------------------------------

def _wait_for_engineer(
    proc: subprocess.Popen, log_path: Path, timeout: float, heartbeat_timeout: float,
    poll_interval: float = 0.2,
) -> tuple[int | None, str | None]:
    """Block until `proc` exits, `timeout` elapses, or `heartbeat_timeout`
    elapses with no growth of `log_path` (section 2.1 step 2). Returns
    `(exit_code, failure_reason)`: a real exit code and `None` on natural
    exit, or `(None, "timeout"|"heartbeat_timeout")` after killing `proc`
    via `_kill_pid`."""
    def _log_size() -> int:
        try:
            return log_path.stat().st_size
        except OSError:
            return 0

    deadline = time.monotonic() + timeout
    last_size = _log_size()
    last_growth = time.monotonic()
    while True:
        rc = proc.poll()
        if rc is not None:
            return rc, None
        now = time.monotonic()
        size = _log_size()
        if size > last_size:
            last_size = size
            last_growth = now
        if now - last_growth >= heartbeat_timeout:
            _kill_pid(proc.pid)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
            return None, "heartbeat_timeout"
        if now >= deadline:
            _kill_pid(proc.pid)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
            return None, "timeout"
        time.sleep(min(poll_interval, max(deadline - now, 0.01)))


def _movement_validation_plan_and_base(relay_dir: Path, movement_id: str) -> tuple[list, str | None, str | None]:
    """Best-effort: the movement's own `validation_plan` and `git.base`,
    plus the relay's own `status`, read fresh (never from the dispatch-time
    snapshot) so `verify`/`run` react to the movement's current relay file.
    Never raises -- an unresolvable/invalid relay file yields empty
    defaults, matching `_status_row`'s own tolerance."""
    try:
        relay_file = _resolve_relay_file(relay_dir, movement_id)
        relay_obj = _load_relay(relay_file)
    except OrchestratorError:
        return [], None, None
    session_start = relay_obj["entries"][0]
    report = session_start.get("report", {})
    return report.get("validation_plan", []) or [], report.get("git", {}).get("base"), relay_obj.get("status")


def _build_run_report(
    *, movement: str, phase: str, exit_code: int | None, failure_reason: str | None,
    relay_status: str | None, model: str | None, effort: str | None, duration_s: float,
    verify_result: dict, worktree_path: Path, branch: str | None,
    provider: str = "claude", merge_mode: str | None = None,
    model_observed: str | None = None, effort_observed: str | None = None,
    integrate_result: dict | None = None, usage: dict | None = None,
) -> dict:
    """GOV.ORCH.1 section 2.4's report object, extended by GOV.ORCH.2
    section 2.4 with provider/model/effort requested-vs-observed fields and
    the `provider_default_used` audit exception (section 2.4: a dispatch
    whose observed values are null -- the engineer log never surfaced a
    model -- is flagged rather than silently reported as if nothing was
    recorded), and by GOV.ORCH.4 section 3.1 with the movement's own token/
    cost usage, read from the usage file `orchestrator_usage.py` maintains."""
    report = {
        "movement": movement, "phase": phase, "exit_code": exit_code,
        "failure_reason": failure_reason, "relay_status": relay_status,
        "provider": provider, "model": model, "effort": effort,
        "model_requested": model, "effort_requested": effort,
        "model_observed": model_observed, "effort_observed": effort_observed,
        "merge_mode": merge_mode,
        "duration_s": round(duration_s, 3), "verify": verify_result,
        "worktree_path": str(worktree_path), "branch": branch, "usage": usage,
    }
    if model_observed is None:
        report["audit_exception"] = "provider_default_used"
    if integrate_result is not None:
        report["integrate"] = integrate_result
    return report


def _cmd_run(args: argparse.Namespace) -> int:
    rc, _payload, proc = _do_start(args)
    if rc != EXIT_OK:
        return rc

    state_dir = Path(args.state_dir)
    relay_dir = Path(args.relay_dir).resolve()
    movement = args.movement
    record = _load_state(state_dir, movement)
    worktree_path = Path(record["worktree_path"])
    branch = record.get("branch")
    log_path = worktree_path / ".nexus" / "engineer.log"

    started = time.monotonic()
    exit_code, failure_reason = _wait_for_engineer(
        proc, log_path, timeout=args.timeout, heartbeat_timeout=args.heartbeat_timeout,
    )
    duration_s = time.monotonic() - started

    validation_plan, base_ref, relay_status = _movement_validation_plan_and_base(relay_dir, movement)

    if failure_reason is not None:
        phase = PHASE_FAILED
        # A killed engineer never gets an orchestrator-side verify run --
        # nothing it did is trustworthy evidence of anything, and running
        # the movement's full validation plan against a worktree the
        # engineer was still writing to when killed would be a wasted,
        # potentially-misleading verify.
        verify_result = {"passed": False, "steps": [], "skipped_reason": failure_reason}
    else:
        if exit_code != 0:
            failure_reason = "engineer_exit_nonzero"
        elif relay_status != "CLOSED":
            failure_reason = "relay_not_closed"
        verify_result = (
            {"passed": True, "steps": [], "skipped_reason": "no_verify"} if args.no_verify
            else ov.verify_movement(worktree_path=worktree_path, validation_plan=validation_plan, base_ref=base_ref)
        )
        phase = PHASE_DONE if (exit_code == 0 and relay_status == "CLOSED" and verify_result["passed"]) else PHASE_FAILED

    record = _load_state(state_dir, movement) or record
    provider = record.get("provider", "claude")
    merge_mode = record.get("merge_mode") or op.resolve_merge_mode(provider, getattr(args, "merge_mode", None))

    adapter = op.build_adapter(provider)
    model_observed, effort_observed = adapter.observed_model(log_path)

    # GOV.ORCH.2 section 2.5: in orchestrator merge-mode the engineer never
    # runs `gh pr merge` itself -- `run` performs the integration here,
    # exactly once, and only after a green `verify` (never on a killed
    # engineer, a nonzero exit, an unclosed relay, or a failed verify).
    integrate_result = None
    if merge_mode == "orchestrator" and phase == PHASE_DONE:
        ok, reason = ov.integrate(worktree_path)
        integrate_result = {"passed": ok, "reason": reason}
        if not ok:
            phase = PHASE_FAILED
            failure_reason = "integration_failed"

    # GOV.ORCH.4 section 3.1: the movement's own token/cost usage, refreshed
    # one last time from the now-finished engineer.log and persisted in the
    # record itself (§3.4's board reads it from the record, never
    # re-deriving it) alongside `verify` (§3.4's Evidence tab / verify.passed).
    price_table = ou.load_price_table(DEFAULT_PRICE_TABLE_PATH)
    usage = ou.compute_usage(state_dir, movement, str(worktree_path), provider, price_table)

    _save_state(state_dir, movement, {
        **record, "phase": phase, "failure_reason": failure_reason, "exit_code": exit_code,
        "model_observed": model_observed, "effort_observed": effort_observed,
        "verify": verify_result, "usage": usage,
    })

    report_obj = _build_run_report(
        movement=movement, phase=phase, exit_code=exit_code, failure_reason=failure_reason,
        relay_status=relay_status, model=args.model, effort=args.effort, duration_s=duration_s,
        verify_result=verify_result, worktree_path=worktree_path, branch=branch,
        provider=provider, merge_mode=merge_mode,
        model_observed=model_observed, effort_observed=effort_observed,
        integrate_result=integrate_result, usage=usage,
    )
    print(json.dumps(report_obj))
    return EXIT_OK if phase == PHASE_DONE else EXIT_REFUSED


# ---------------------------------------------------------------------------
# verify (GOV.ORCH.1 section 2.2, also standalone)
# ---------------------------------------------------------------------------

def _cmd_verify(args: argparse.Namespace) -> int:
    state_dir = Path(args.state_dir)
    relay_dir = Path(args.relay_dir).resolve()
    record = _load_state(state_dir, args.movement)
    if record is None:
        print(f"error: no state record for {args.movement}", file=sys.stderr)
        return EXIT_USAGE
    worktree_path = Path(record["worktree_path"])
    validation_plan, base_ref, _relay_status = _movement_validation_plan_and_base(relay_dir, args.movement)
    result = ov.verify_movement(worktree_path=worktree_path, validation_plan=validation_plan, base_ref=base_ref)
    print(json.dumps(result))
    return EXIT_OK if result["passed"] else EXIT_REFUSED


# ---------------------------------------------------------------------------
# status
# ---------------------------------------------------------------------------

def _status_row(record: dict, relay_dir: Path, retry_limit: int, legacy: bool = False) -> dict:
    is_alive = pid_alive(record.get("pid"))
    relay_status = None
    last_marker, last_timestamp = None, None
    try:
        relay_file = _resolve_relay_file(relay_dir, record["movement_id"])
        relay_obj = _load_relay(relay_file)
        relay_status = relay_obj.get("status")
        entries = relay_obj.get("entries") or []
        dispatch_seq = record.get("dispatch_seq")
        if dispatch_seq is not None and len(entries) > dispatch_seq:
            last_marker = "REVISION_CHANGED_SINCE_DISPATCH"
        if entries:
            last_marker = last_marker or entries[-1].get("marker")
            last_timestamp = entries[-1].get("timestamp")
    except OrchestratorError:
        pass
    prior_phase = record.get("phase", PHASE_RUNNING)
    phase = reconcile_phase(record, is_alive, relay_status, retry_limit)
    failure_reason = reconcile_failure_reason(record, phase, prior_phase)
    if phase != prior_phase or failure_reason != record.get("failure_reason"):
        record = {**record, "phase": phase, "failure_reason": failure_reason}
    return {
        "movement_id": record["movement_id"], "phase": phase, "pid": record.get("pid"),
        "pid_alive": is_alive, "revision": record.get("revision"), "branch": record.get("branch"),
        "worktree_path": record.get("worktree_path"), "started_at": record.get("started_at"),
        "retry_count": record.get("retry_count", 0), "relay_status": relay_status,
        "last_marker": last_marker, "last_timestamp": last_timestamp,
        # AC-4: the most recent engineer.summary.log line, or None -- never
        # an exception -- for a movement dispatched before AC-1/AC-2 landed
        # (AC-5's own backward-compatibility requirement).
        "last_activity": _read_last_activity(record.get("worktree_path")),
        "heartbeat_age_seconds": _heartbeat_age_seconds(record),
        "failure_reason": failure_reason,
        "exit_code": record.get("exit_code"),
        # GOV.ORCH.2 section 2.4: display-only pass-through of the provider
        # selection and requested model/effort recorded at dispatch time.
        "provider": record.get("provider", "claude"),
        "model_requested": record.get("model_requested"),
        "effort_requested": record.get("effort_requested"),
        "merge_mode": record.get("merge_mode"),
        # AC-9: a record found only under the pre-GOV.ORCH.1 default
        # location (LEGACY_STATE_DIR), never a movement whose record lives
        # under the current --state-dir.
        "legacy_state_dir": legacy,
    }


def _cmd_status(args: argparse.Namespace) -> int:
    relay_dir = Path(args.relay_dir).resolve()
    state_dir = Path(args.state_dir)
    if args.movement:
        record = _load_state(state_dir, args.movement)
        legacy = False
        save_dir = state_dir
        if record is None and state_dir.resolve() != LEGACY_STATE_DIR.resolve():
            record = _load_state(LEGACY_STATE_DIR, args.movement)
            legacy = record is not None
            if legacy:
                save_dir = LEGACY_STATE_DIR
        if record is None:
            print(f"error: no state record for {args.movement}", file=sys.stderr)
            return EXIT_USAGE
        row = _status_row(record, relay_dir, args.retry_limit, legacy=legacy)
        _save_state(save_dir, args.movement, {**record, "phase": row["phase"], "failure_reason": row["failure_reason"]})
        print(json.dumps(row, sort_keys=True))
        return EXIT_OK

    rows = []
    for record, legacy in _list_state_records_with_legacy(state_dir, LEGACY_STATE_DIR):
        row = _status_row(record, relay_dir, args.retry_limit, legacy=legacy)
        save_dir = LEGACY_STATE_DIR if legacy else state_dir
        _save_state(save_dir, record["movement_id"], {**record, "phase": row["phase"], "failure_reason": row["failure_reason"]})
        rows.append(row)
    print(json.dumps(sorted(rows, key=lambda r: r["movement_id"]), sort_keys=True))
    return EXIT_OK


# ---------------------------------------------------------------------------
# stop
# ---------------------------------------------------------------------------

def _cmd_stop(args: argparse.Namespace) -> int:
    state_dir = Path(args.state_dir)
    record = _load_state(state_dir, args.movement)
    if record is None:
        print(f"error: no state record for {args.movement}", file=sys.stderr)
        return EXIT_USAGE
    _kill_pid(record.get("pid"), force=args.force)
    record = {**record, "phase": PHASE_CANCELLED, "last_action": "stopped"}
    _save_state(state_dir, args.movement, record)
    print(json.dumps({"movement_id": args.movement, "phase": PHASE_CANCELLED}))
    return EXIT_OK


# ---------------------------------------------------------------------------
# merge-lock
# ---------------------------------------------------------------------------

def _lock_path(state_dir: Path) -> Path:
    return state_dir / "merge.lock.json"


def _cmd_merge_lock(args: argparse.Namespace) -> int:
    state_dir = Path(args.state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    path = _lock_path(state_dir)
    with lr._FileLock(path, timeout=args.wait):
        if args.action == "release":
            if path.is_file():
                record = json.loads(path.read_text(encoding="utf-8"))
                if record.get("movement_id") == args.movement:
                    path.unlink()
                    print(json.dumps({"released": True}))
                    return EXIT_OK
            print(json.dumps({"released": False, "reason": "not held by this movement"}))
            return EXIT_REFUSED

        lock_record = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None
        granted, new_record, reason = decide_merge_lock_acquire(
            lock_record=lock_record, requester_movement=args.movement,
            requester_pid=args.pid, now=time.time(), ttl=args.ttl,
        )
        if granted:
            lr._atomic_write(path, json.dumps(new_record, sort_keys=True, indent=2) + "\n")
            print(json.dumps({"acquired": True, "reason": reason}))
            return EXIT_OK
        print(json.dumps({"acquired": False, "reason": reason}))
        return EXIT_REFUSED


# ---------------------------------------------------------------------------
# _tail-summary (internal -- spawned by _spawn_engineer, not user-facing)
# ---------------------------------------------------------------------------

def _cmd_tail_summary(args: argparse.Namespace) -> int:
    adapter = op.build_adapter(args.provider)
    _tail_engineer_log(
        Path(args.log), Path(args.summary), args.pid, poll_interval=args.poll_interval,
        summarize=adapter.summarize_line,
    )
    return EXIT_OK


# ---------------------------------------------------------------------------
# watch (AC-3)
# ---------------------------------------------------------------------------

def _gather_watch_rows(state_dir: Path, relay_dir: Path, retry_limit: int) -> list[dict]:
    """The exact per-movement rows `status` computes, reused verbatim (AC-3:
    "polls the same data orchestrator status already computes") -- but
    never written back to `state_dir`, unlike `_cmd_status`'s own
    reconciliation write. `watch` is read-only by its own explicit
    invariant; `status` is not."""
    rows = [_status_row(record, relay_dir, retry_limit) for record in _list_state_records(state_dir)]
    return sorted(rows, key=lambda r: r["movement_id"])


def _render_watch_screen(rows: list[dict], terminal_width: int = 80) -> str:
    lines = [f"neXus orchestrator watch -- {len(rows)} known movement(s)"]
    if not rows:
        lines.append("(no movements known)")
        return "\n".join(lines)
    header = f"{'MOVEMENT':<22}{'PHASE':<12}{'ALIVE':<7}{'AGE':<8}{'BRANCH':<26}{'LAST RELAY'}"
    lines.append(_truncate_to_width(header, terminal_width))
    lines.append("-" * min(len(header), terminal_width if terminal_width > 0 else len(header)))
    for row in rows:
        alive = "yes" if row.get("pid_alive") else "no"
        age = _format_age(row.get("heartbeat_age_seconds"))
        branch = row.get("branch") or "-"
        last_relay = f"{row.get('last_marker') or '-'}@{row.get('last_timestamp') or '-'}"
        main_line = (
            f"{row['movement_id']:<22}{(row.get('phase') or '-'):<12}{alive:<7}{age:<8}{branch:<26}{last_relay}"
        )
        lines.append(_truncate_to_width(main_line, terminal_width))
        worktree = row.get("worktree_path") or "-"
        lines.append(_truncate_to_width(f"  worktree: {worktree}", terminal_width))
        # AC-4/AC-5: last_activity is None for a movement dispatched before
        # AC-1/AC-2 landed (old-format engineer.log, no summary.log yet) --
        # render as "n/a", never raise.
        activity = row.get("last_activity") or "n/a"
        lines.append(_truncate_to_width(f"  last activity: {activity}", terminal_width))
    return "\n".join(lines)


def _cmd_watch(args: argparse.Namespace) -> int:
    """Human-facing dashboard loop. Deliberately distinct from
    `local_relay.py watch`'s own bounded, single-wait design: that command
    is an unattended primitive a script polls once for one specific
    next_actor flip, with a hard timeout ceiling so it can never become a
    silent daemon. This command is the opposite kind of tool -- a live
    display a human keeps open and reads continuously -- so it loops until
    the human interrupts it (Ctrl-C) rather than returning after one
    bounded wait; that is a real design difference, not an oversight.
    Read-only throughout: every call below is a read (`_gather_watch_rows`
    never calls `_save_state`), and it mutates no on-disk state."""
    relay_dir = Path(args.relay_dir).resolve()
    state_dir = Path(args.state_dir)
    try:
        while True:
            rows = _gather_watch_rows(state_dir, relay_dir, args.retry_limit)
            width = shutil.get_terminal_size(fallback=(100, 24)).columns
            os.system("cls" if os.name == "nt" else "clear")
            print(_render_watch_screen(rows, width))
            time.sleep(args.interval)
    except KeyboardInterrupt:
        return EXIT_OK


# ---------------------------------------------------------------------------
# dashboard (AC-3 onward, project/backlog.json id
# orchestrator_interactive_dashboard_app -- design acknowledged
# relay/NXS-LOCAL-0021 seq 2-3): a thin CLI wrapper. All real logic (HTTP
# server, action-id registry, stage derivation, config) lives in
# scripts/orchestrator_dashboard.py, imported lazily so `orchestrator.py`'s
# other subcommands never pay for http.server's import cost.
# ---------------------------------------------------------------------------

def _cmd_usage(args: argparse.Namespace) -> int:
    """GOV.ORCH.4 section 3.6: prints the same data as `GET /api/usage` for
    the same state dir (AC-9) -- table by default, `--json` for the exact
    payload. Lazily imports `orchestrator_dashboard` for the same reason
    `_cmd_dashboard` does (it, not this module, owns http.server's import
    cost, but the aggregation function this reuses lives there since it
    needs both `orchestrator.py`'s own state records and
    `orchestrator_usage.py`)."""
    import orchestrator_dashboard as dash

    state_dir = Path(args.state_dir)
    relay_dir = Path(args.relay_dir).resolve()
    repo_root = Path(args.repo_root).resolve()
    report = dash.build_usage_report(state_dir, relay_dir, repo_root, since=args.since)
    if args.movement:
        rows = [m for m in report["movements"] if m["movement_id"] == args.movement]
        report = {"movements": rows, "totals": dash._sum_usage_rows(rows)}

    if args.json:
        print(json.dumps(report, sort_keys=True))
        return EXIT_OK

    print(f"{'movement':<24}{'provider':<9}{'tokens':>12}{'cache%':>8}{'cost':>10}")
    for m in report["movements"]:
        cache_pct = f"{(m.get('cache_hit_ratio') or 0) * 100:.0f}%"
        cost = f"${m['cost_usd']:.4f}" if m.get("cost_usd") is not None else (m.get("cost_source") or "-")
        print(f"{m['movement_id']:<24}{(m.get('provider') or '-'):<9}{m.get('total_tokens', 0):>12}{cache_pct:>8}{cost:>10}")
    totals = report["totals"]
    cost_total = f"${totals['cost_usd']:.4f}" if totals.get("cost_usd") is not None else "-"
    print(f"TOTAL movements={totals['movements']} tokens={totals['total_tokens']} cost={cost_total}")
    return EXIT_OK


def _cmd_dashboard(args: argparse.Namespace) -> int:
    import orchestrator_dashboard as dash
    port = args.port if args.port is not None else dash.DEFAULT_PORT
    dash.run_dashboard(
        port=port, relay_dir=Path(args.relay_dir).resolve(), state_dir=Path(args.state_dir),
        repo_root=Path(args.repo_root).resolve(), retry_limit=args.retry_limit,
    )
    return EXIT_OK


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--relay-dir", default="relay")
    parser.add_argument("--state-dir", default=str(DEFAULT_STATE_DIR))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="orchestrator", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_start = sub.add_parser("start", help="dispatch (or resume) one approved movement")
    p_start.add_argument("--movement", required=True)
    _add_common(p_start)
    p_start.add_argument("--worktrees-dir", default=str(DEFAULT_WORKTREES_DIR))
    p_start.add_argument("--profile", default=str(DEFAULT_ENGINEER_PROFILE))
    p_start.add_argument("--max-workers", type=int, default=DEFAULT_MAX_WORKERS)
    p_start.add_argument("--max-budget-usd", type=float, default=DEFAULT_MAX_BUDGET_USD)
    p_start.add_argument("--model", default=None, help="Passed through to the provider's own --model/-m flag. Unset uses the CLI's own default.")
    p_start.add_argument("--effort", default=None, help="Passed through to the provider's own reasoning-effort flag. Unset uses the CLI's own default.")
    p_start.add_argument("--provider", default="claude", choices=sorted(op.PROVIDERS))
    p_start.add_argument("--merge-mode", default=None, choices=("engineer", "orchestrator"),
                          help="Default 'engineer' for claude, forced 'orchestrator' for codex (GOV.ORCH.2 section 2.5).")
    p_start.set_defaults(func=_cmd_start)

    p_run = sub.add_parser(
        "run", help="dispatch, wait, verify and report on one approved movement in a single foreground call"
    )
    p_run.add_argument("--movement", required=True)
    _add_common(p_run)
    p_run.add_argument("--worktrees-dir", default=str(DEFAULT_WORKTREES_DIR))
    p_run.add_argument("--profile", default=str(DEFAULT_ENGINEER_PROFILE))
    p_run.add_argument("--max-workers", type=int, default=DEFAULT_MAX_WORKERS)
    p_run.add_argument("--max-budget-usd", type=float, default=DEFAULT_MAX_BUDGET_USD)
    p_run.add_argument("--model", default=None)
    p_run.add_argument("--effort", default=None)
    p_run.add_argument("--provider", default="claude", choices=sorted(op.PROVIDERS))
    p_run.add_argument("--merge-mode", default=None, choices=("engineer", "orchestrator"),
                        help="Default 'engineer' for claude, forced 'orchestrator' for codex (GOV.ORCH.2 section 2.5).")
    p_run.add_argument("--timeout", type=float, default=5400.0)
    p_run.add_argument("--heartbeat-timeout", type=float, default=900.0)
    p_run.add_argument("--no-verify", action="store_true")
    p_run.set_defaults(func=_cmd_run)

    p_verify = sub.add_parser("verify", help="re-run orchestrator-side verification for a finished worktree")
    p_verify.add_argument("--movement", required=True)
    _add_common(p_verify)
    p_verify.set_defaults(func=_cmd_verify)

    p_status = sub.add_parser("status", help="report one movement, or every known movement")
    p_status.add_argument("--movement", default=None)
    _add_common(p_status)
    p_status.add_argument("--retry-limit", type=int, default=DEFAULT_RETRY_LIMIT)
    p_status.set_defaults(func=_cmd_status)

    p_stop = sub.add_parser("stop", help="cancel one movement's engineer process")
    p_stop.add_argument("--movement", required=True)
    _add_common(p_stop)
    p_stop.add_argument("--force", action="store_true", help="SIGKILL immediately, skip the SIGTERM grace period")
    p_stop.set_defaults(func=_cmd_stop)

    p_lock = sub.add_parser("merge-lock", help="acquire/release the cross-movement merge-serialization lock")
    p_lock.add_argument("action", choices=("acquire", "release"))
    p_lock.add_argument("--movement", required=True)
    p_lock.add_argument("--pid", type=int, default=os.getpid())
    p_lock.add_argument("--ttl", type=int, default=DEFAULT_MERGE_LOCK_TTL)
    p_lock.add_argument("--wait", type=float, default=30.0)
    p_lock.add_argument("--state-dir", default=str(DEFAULT_STATE_DIR))
    p_lock.set_defaults(func=_cmd_merge_lock)

    p_watch = sub.add_parser(
        "watch", help="read-only terminal dashboard: phase, liveness, and last activity for every movement"
    )
    _add_common(p_watch)
    p_watch.add_argument("--interval", type=int, default=WATCH_INTERVAL_DEFAULT)
    p_watch.add_argument("--retry-limit", type=int, default=DEFAULT_RETRY_LIMIT)
    p_watch.set_defaults(func=_cmd_watch)

    p_usage = sub.add_parser(
        "usage", help="per-movement token/cost usage accounting (GOV.ORCH.4 section 3.6)"
    )
    _add_common(p_usage)
    p_usage.add_argument("--repo-root", default=str(REPO_ROOT))
    p_usage.add_argument("--movement", default=None)
    p_usage.add_argument("--since", default=None, help="ISO timestamp -- only movements started_at this or later")
    p_usage.add_argument("--json", action="store_true")
    p_usage.set_defaults(func=_cmd_usage)

    p_dashboard = sub.add_parser(
        "dashboard",
        help="interactive PO + Orchestrator workbench: local HTTP server + browser UI (read+relay-write only)",
    )
    _add_common(p_dashboard)
    p_dashboard.add_argument("--port", type=int, default=None)
    p_dashboard.add_argument("--repo-root", default=str(REPO_ROOT))
    p_dashboard.add_argument("--retry-limit", type=int, default=DEFAULT_RETRY_LIMIT)
    p_dashboard.set_defaults(func=_cmd_dashboard)

    # Internal only -- spawned by _spawn_engineer, never invoked directly by
    # a human or documented in this module's own CLI docstring.
    p_tail = sub.add_parser("_tail-summary")
    p_tail.add_argument("--log", required=True)
    p_tail.add_argument("--summary", required=True)
    p_tail.add_argument("--pid", type=int, required=True)
    p_tail.add_argument("--poll-interval", type=float, default=TAIL_POLL_INTERVAL_DEFAULT)
    p_tail.add_argument("--provider", default="claude", choices=sorted(op.PROVIDERS))
    p_tail.set_defaults(func=_cmd_tail_summary)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
