"""GOV.PO.3 -- interactive PO + Orchestrator workbench backend
(project/backlog.json id orchestrator_interactive_dashboard_app; design
acknowledged relay/NXS-LOCAL-0021 seq 2-3, FIRST DISPATCH bounded by that
movement's own "PO SCOPE BOUNDARY 2026-09-08" section).

    py scripts/orchestrator.py dashboard [--port N] [--relay-dir DIR]
        [--state-dir DIR] [--repo-root DIR]

Read+relay-write only (AC-4): every write route below ends by calling
`local_relay.py`'s own `append` path (in-process, via its public
`build_parser()`/`_cmd_append`) with a movement's own already-registered
operation/args -- this module never runs `git`/`gh` to mutate anything, and
never runs a shell string. Read-only `git status --porcelain` / `git
rev-list` / `gh pr list` calls are in scope (the backlog note's own
RECOMMENDED ARCHITECTURE section (A2) names them) purely for display; no
button here ever triggers one.

Reuses `scripts/orchestrator.py`'s own status-computation functions
(`_status_row`, `_list_state_records`, `pid_alive`, `canonical_json`, ...)
and `scripts/local_relay.py` directly (AC-6): no new scheduler, no new
state model, no third-party dependency, stdlib `http.server` only, bound to
127.0.0.1.

Security (AC-2c, acknowledged relay/NXS-LOCAL-0021 seq 3): one
`secrets.token_urlsafe(32)` bearer token per process launch, printed once
as a URL fragment (`http://127.0.0.1:<port>/#t=<token>`), held only in
memory (this module's `_TOKEN_HOLDER`, never written to disk), compared
with `hmac.compare_digest`. Every `/api/*` route -- read or write --
requires it as an `Authorization: Bearer <token>` header; `GET /` and
static assets do not (no evidence lives in them). `Origin` is checked as
defense-in-depth on every state-changing (PUT/POST) `/api/*` route:
rejected if present and not equal to `http://127.0.0.1:<port>` (absence is
allowed -- the bearer token, not Origin, is the real authorization
boundary).

Action-id registry (AC-2a): an executable-request card issues a
deterministic `action_id = sha256(canonical_json({movement_id, relay_file,
operation, args, working_directory, source_relay_seq}))[:16]`, stored in
`<state_dir>/dashboard/<movement_id>.actions.json` -- a subdirectory of
orchestrator.py's own process-state bucket, not directly inside it: a
sibling `<movement_id>.actions.json` placed straight in `state_dir` would
match `orchestrator.py::_list_state_records`'s own `NXS-LOCAL-*.json` glob
and crash `_status_row` on the missing `movement_id` key (found live while
building AC-8's acceptance demo). Still runtime bookkeeping, never under
`relay/`. Consuming an id checks it is unconsumed and
that the movement's relay entry count still equals `source_relay_seq` (the
same "REVISION_CHANGED_SINCE_DISPATCH" signal `orchestrator.py::_status_row`
already computes) before calling `local_relay.py`'s append path with the
registered args; `local_relay.py`'s own optimistic-concurrency hash compare
stays a second, independent guard at the actual write.

Blocker-source tagging: the three-way routing AC-3 requires (already
authorized / genuinely new decision / platform policy) is read from an
optional bracket tag at the start of the blocking `RELAY_QUESTION`/
`RELAY_NOTE` entry's own `subject` field -- `[AUTHORIZED]`, `[BLOCKED]`, or
no tag (defaults to a genuine decision, the safe default: an unlabeled
request never silently auto-routes). This is plain text inside an existing
field, not a new marker or a new file format, matching AC-2b's design.

Message box (AMENDMENT's "send a note to this work" field): appending to
the relay is only ever possible on the PO's own turn (the frozen protocol's
single-writer rule, docs/design/LOCAL_RELAY_PROTOCOL.md). When it is not
the PO's turn, a message is saved to a small per-movement outbox file
(`<state_dir>/dashboard/<movement_id>.outbox.json`, same runtime-bookkeeping
bucket as the action registry above) and surfaced honestly as
not-yet-appended -- never shown as delivered.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import hmac
import io
import json
import secrets
import shutil
import subprocess
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))
import local_relay as lr  # noqa: E402
import orchestrator as orch  # noqa: E402

ASSETS_DIR = Path(__file__).resolve().parent / "dashboard_assets"
DEFAULT_PORT = 8765
DEFAULT_CONFIG_FILENAME = "dashboard_config.json"
CONFIG_INTERVAL_MIN = 2
CONFIG_INTERVAL_MAX = 3600
LOG_TAIL_DEFAULT = 200
LOG_TAIL_MAX = 2000

STAGE_NOT_STARTED = "not_started"
STAGE_CODING = "coding"
STAGE_RESOLVING_CONFLICT = "resolving_merge_conflict"
STAGE_TESTING = "testing"
STAGE_AWAITING_PO = "awaiting_po"
STAGE_INTEGRATION = "integration"
STAGE_MERGED = "merged"
STAGE_UNKNOWN = "unknown"

#: git porcelain v1 XY codes that mean "an unresolved merge conflict is
#: present" (unmerged paths) -- see `git status --porcelain` documentation.
_CONFLICT_CODES = frozenset({"UU", "AA", "DD", "AU", "UA", "UD", "DU"})

#: Case-insensitive keywords in the most recent engineer.summary.log line
#: that indicate a test run is/was the last observed activity -- a real,
#: observed signal, never a guess about what is happening right now.
_TEST_ACTIVITY_KEYWORDS = ("pytest", "test_", " tests", "-n auto")

BLOCKER_ALREADY_AUTHORIZED = "already_authorized"
BLOCKER_NEW_DECISION = "new_decision"
BLOCKER_PLATFORM_POLICY = "platform_policy"
_BLOCKER_TAGS = (
    (BLOCKER_ALREADY_AUTHORIZED, "[AUTHORIZED]"),
    (BLOCKER_PLATFORM_POLICY, "[BLOCKED]"),
    (BLOCKER_NEW_DECISION, "[DECISION]"),
)

OPERATION_ROUTE_TO_ENGINEER = "route_to_engineer"
OPERATION_RECORD_DECISION = "record_decision"
OPERATION_REJECT = "reject"


class DashboardError(Exception):
    """A request could not be satisfied -- carries a human-readable reason."""


# ---------------------------------------------------------------------------
# Pure helpers -- stage derivation, process status, blocker tagging
# (AC-7: unit-testable without a server, a git checkout, or gh)
# ---------------------------------------------------------------------------

def derive_process_status(row: dict) -> str:
    """"running" / "exited" / "disconnected" -- distinct from work stage,
    sourced only from a live PID check (`orch.pid_alive`, already run by
    `_status_row`), never inferred from git or relay state."""
    if row.get("pid_alive"):
        return "running"
    if row.get("pid"):
        return "exited"
    return "disconnected"


def _looks_like_test_activity(last_activity: str | None) -> bool:
    if not last_activity:
        return False
    lowered = last_activity.lower()
    return any(keyword in lowered for keyword in _TEST_ACTIVITY_KEYWORDS)


def derive_work_stage(
    *, relay_status: str | None, next_actor: str | None, phase: str | None,
    porcelain_lines: list[str], last_activity: str | None, has_open_pr: bool,
) -> str:
    """Real per-movement stage (AC-3/invariants: never inferred from git
    dirtiness alone, never a fabricated percentage). Combines several real
    signals -- relay turn state, process phase, parsed worktree porcelain,
    the most recent engineer-posted activity line, and open-PR presence --
    in a fixed precedence order, exactly the combination the backlog note's
    own architecture section (C) and the AMENDMENT's stage list both call
    for. A worktree that no longer exists (porcelain_lines always empty for
    it) still resolves honestly via the other signals; nothing here raises."""
    if relay_status == "CLOSED" or phase == orch.PHASE_DONE:
        return STAGE_MERGED
    if next_actor == "po":
        return STAGE_AWAITING_PO
    if any(line[:2] in _CONFLICT_CODES for line in porcelain_lines):
        return STAGE_RESOLVING_CONFLICT
    if has_open_pr:
        return STAGE_INTEGRATION
    if _looks_like_test_activity(last_activity):
        return STAGE_TESTING
    if porcelain_lines:
        return STAGE_CODING
    if relay_status == "OPEN":
        return STAGE_NOT_STARTED
    return STAGE_UNKNOWN


def parse_blocker_tag(subject: str) -> tuple[str, str]:
    """Split an optional leading bracket tag off a relay entry's own
    `subject` -- returns (blocker_source, subject_without_tag). An untagged
    subject defaults to `new_decision`, the safe choice: nothing is ever
    silently auto-routed without an explicit `[AUTHORIZED]` tag."""
    subject = subject or ""
    for tag_name, prefix in _BLOCKER_TAGS:
        if subject.startswith(prefix):
            return tag_name, subject[len(prefix):].strip()
    return BLOCKER_NEW_DECISION, subject.strip()


# ---------------------------------------------------------------------------
# Config (GET/PUT /api/config) -- AC-7 targeted-test surface
# ---------------------------------------------------------------------------

def _config_path(repo_root: Path) -> Path:
    return repo_root / ".nexus" / DEFAULT_CONFIG_FILENAME


def read_config(repo_root: Path) -> dict:
    path = _config_path(repo_root)
    if not path.is_file():
        return {"poll_interval_seconds": orch.WATCH_INTERVAL_DEFAULT}
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"poll_interval_seconds": orch.WATCH_INTERVAL_DEFAULT}
    interval = obj.get("poll_interval_seconds") if isinstance(obj, dict) else None
    if not isinstance(interval, int) or isinstance(interval, bool) or not (
        CONFIG_INTERVAL_MIN <= interval <= CONFIG_INTERVAL_MAX
    ):
        interval = orch.WATCH_INTERVAL_DEFAULT
    return {"poll_interval_seconds": interval}


def write_config(repo_root: Path, poll_interval_seconds: Any) -> tuple[bool, dict | str]:
    if not isinstance(poll_interval_seconds, int) or isinstance(poll_interval_seconds, bool):
        return False, "poll_interval_seconds must be an integer"
    if not (CONFIG_INTERVAL_MIN <= poll_interval_seconds <= CONFIG_INTERVAL_MAX):
        return False, f"poll_interval_seconds must be between {CONFIG_INTERVAL_MIN} and {CONFIG_INTERVAL_MAX}"
    cfg = {"poll_interval_seconds": poll_interval_seconds}
    lr._atomic_write(_config_path(repo_root), json.dumps(cfg, sort_keys=True, indent=2) + "\n")
    return True, cfg


# ---------------------------------------------------------------------------
# Action-id registry (AC-2a / AC-7)
# ---------------------------------------------------------------------------

def compute_action_id(
    *, movement_id: str, relay_file: str, operation: str, args: dict,
    working_directory: str, source_relay_seq: int,
) -> str:
    payload = {
        "movement_id": movement_id, "relay_file": relay_file, "operation": operation,
        "args": args, "working_directory": working_directory, "source_relay_seq": source_relay_seq,
    }
    return hashlib.sha256(orch.canonical_json(payload).encode("utf-8")).hexdigest()[:16]


def _dashboard_bucket(state_dir: Path) -> Path:
    """Dashboard-only runtime bookkeeping lives one level below `state_dir`,
    never directly inside it: `orchestrator.py::_list_state_records` globs
    `state_dir/NXS-LOCAL-*.json` for process-state records, and a sibling
    `NXS-LOCAL-0001.actions.json` would match that same glob (no
    `movement_id` key -> `_status_row` raises `KeyError`, discovered live
    while exercising AC-8's acceptance demo). A subdirectory keeps this in
    the same bucket in spirit without colliding with that pattern."""
    return state_dir / "dashboard"


def _actions_path(state_dir: Path, movement_id: str) -> Path:
    return _dashboard_bucket(state_dir) / f"{movement_id}.actions.json"


def load_actions(state_dir: Path, movement_id: str) -> dict:
    path = _actions_path(state_dir, movement_id)
    if not path.is_file():
        return {}
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return obj if isinstance(obj, dict) else {}


def save_actions(state_dir: Path, movement_id: str, registry: dict) -> None:
    lr._atomic_write(_actions_path(state_dir, movement_id), json.dumps(registry, sort_keys=True, indent=2) + "\n")


def register_action(
    state_dir: Path, movement_id: str, *, relay_file: str, operation: str, args: dict,
    working_directory: str, source_relay_seq: int,
) -> str:
    """Idempotent: re-rendering the same card (same movement/operation/args/
    working_directory/source_relay_seq) always yields the same action_id and
    never clobbers an already-consumed record for that same id. A relay
    change (a new `source_relay_seq`) yields a different id -- the old one
    simply stays registered, unconsumed, and will fail the staleness check
    at the OLD id's own recorded `source_relay_seq` if anyone still holds it."""
    action_id = compute_action_id(
        movement_id=movement_id, relay_file=relay_file, operation=operation, args=args,
        working_directory=working_directory, source_relay_seq=source_relay_seq,
    )
    registry = load_actions(state_dir, movement_id)
    if action_id not in registry:
        registry[action_id] = {
            "operation": operation, "args": args, "working_directory": working_directory,
            "source_relay_seq": source_relay_seq, "issued_at": lr._utc_now_iso(),
            "consumed_at": None, "consumed_relay_seq": None,
        }
        save_actions(state_dir, movement_id, registry)
    return action_id


def check_action(state_dir: Path, movement_id: str, action_id: str, current_relay_seq: int) -> tuple[bool, dict | str]:
    """Read-only staleness/duplicate-click check -- does not consume. Returns
    (ok, record) or (False, reason)."""
    registry = load_actions(state_dir, movement_id)
    record = registry.get(action_id)
    if record is None:
        return False, "unknown action id"
    if record.get("consumed_at"):
        return False, "action id already consumed"
    if record.get("source_relay_seq") != current_relay_seq:
        return False, "stale action id -- the relay changed since this card was issued"
    return True, record


def mark_consumed(state_dir: Path, movement_id: str, action_id: str, consumed_relay_seq: int) -> None:
    registry = load_actions(state_dir, movement_id)
    if action_id in registry:
        registry[action_id]["consumed_at"] = lr._utc_now_iso()
        registry[action_id]["consumed_relay_seq"] = consumed_relay_seq
        save_actions(state_dir, movement_id, registry)


# ---------------------------------------------------------------------------
# Outbox (message box queued while it is not the PO's turn)
# ---------------------------------------------------------------------------

def _outbox_path(state_dir: Path, movement_id: str) -> Path:
    return _dashboard_bucket(state_dir) / f"{movement_id}.outbox.json"


def load_outbox(state_dir: Path, movement_id: str) -> list[dict]:
    path = _outbox_path(state_dir, movement_id)
    if not path.is_file():
        return []
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    return obj if isinstance(obj, list) else []


def queue_outbox_message(state_dir: Path, movement_id: str, text: str) -> None:
    items = load_outbox(state_dir, movement_id)
    items.append({"text": text, "saved_at": lr._utc_now_iso()})
    lr._atomic_write(_outbox_path(state_dir, movement_id), json.dumps(items, sort_keys=True, indent=2) + "\n")


# ---------------------------------------------------------------------------
# Relay-write (reuses local_relay.py's own CLI path in-process, AC-6)
# ---------------------------------------------------------------------------

def append_to_relay(relay_file: Path, **kwargs: Any) -> dict:
    """Builds the exact `local_relay append` argv the CLI itself accepts and
    dispatches through `local_relay.py`'s own parser/handler -- the same
    entry point an interactive session's `Bash` call would use -- rather
    than re-implementing any of its validation, locking, or turn-ownership
    logic. Raises DashboardError with the CLI's own stderr-equivalent
    message on any non-zero exit."""
    argv = ["append", "--file", str(relay_file), "--role", kwargs["role"], "--marker", kwargs["marker"],
            "--subject", kwargs["subject"], "--text", kwargs["text"]]
    if kwargs["marker"] == "RELAY_DECISION":
        argv += ["--authorized-by", kwargs["authorized_by"], "--scope", kwargs["scope"],
                  "--supersedes", kwargs["supersedes"]]
    if kwargs.get("close"):
        argv += ["--close"]
    else:
        argv += ["--next", kwargs["next_actor"]]

    parser = lr.build_parser()
    args = parser.parse_args(argv)
    out_buf, err_buf = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
        rc = args.func(args)
    if rc != lr.EXIT_OK:
        raise DashboardError((err_buf.getvalue() or out_buf.getvalue()).strip() or f"local_relay append failed (exit {rc})")
    return json.loads(out_buf.getvalue())


def execute_action(state_dir: Path, movement_id: str, relay_file: Path, action_id: str) -> dict:
    """The ONE mechanism behind both "automatic routing of an already-
    authorized action" and a "run this" button (AC-4): resolve the
    registered action, verify it is still fresh, append it, mark it
    consumed. Every operation this registry can hold -- route_to_engineer,
    record_decision, reject -- ends here, identically."""
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))
    current_seq = len(relay_obj.get("entries") or [])
    ok, record_or_reason = check_action(state_dir, movement_id, action_id, current_seq)
    if not ok:
        raise DashboardError(str(record_or_reason))
    record = record_or_reason
    result = append_to_relay(relay_file, role="po", **record["args"])
    mark_consumed(state_dir, movement_id, action_id, result.get("seq", current_seq + 1))
    return result


def send_message(state_dir: Path, movement_id: str, relay_file: Path, text: str) -> dict:
    """AC-5: message-delivery copy always defaults to the state actually
    achievable today. If it is the PO's turn right now, the note is
    appended immediately (still only "saved -> queued for next resume",
    never "delivered" -- appending is not the same as a running session
    reading it). If it is not the PO's turn (the frozen relay protocol's
    single-writer rule), the message is saved to a local outbox and the UI
    is told plainly that it is not yet appended."""
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))
    if relay_obj.get("next_actor") == "po" and relay_obj.get("status") != "CLOSED":
        result = append_to_relay(
            relay_file, role="po", marker="RELAY_NOTE", subject="Message from Product Owner",
            text=text, next_actor="engineer",
        )
        return {"delivery_state": "saved_queued_for_next_resume", "appended": True, "relay_result": result}
    queue_outbox_message(state_dir, movement_id, text)
    return {
        "delivery_state": "saved_not_yet_your_turn", "appended": False,
        "note": "Not this movement's PO turn right now -- saved locally; "
                "post it again once the movement shows in \"Awaiting you\".",
    }


# ---------------------------------------------------------------------------
# Read-only git/gh telemetry (side-effecting; degrade gracefully, never raise)
# ---------------------------------------------------------------------------

def git_worktree_porcelain(worktree_path: str | None) -> list[str]:
    if not worktree_path or not Path(worktree_path).is_dir():
        return []
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"], cwd=worktree_path,
            capture_output=True, text=True, timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    if result.returncode != 0:
        return []
    return [line for line in result.stdout.splitlines() if line.strip()]


def git_ahead_behind(worktree_path: str | None, base: str = "origin/main") -> tuple[int | None, int | None]:
    if not worktree_path or not Path(worktree_path).is_dir():
        return None, None
    try:
        result = subprocess.run(
            ["git", "rev-list", "--left-right", "--count", f"{base}...HEAD"],
            cwd=worktree_path, capture_output=True, text=True, timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None, None
    if result.returncode != 0:
        return None, None
    parts = result.stdout.split()
    if len(parts) != 2:
        return None, None
    try:
        return int(parts[0]), int(parts[1])
    except ValueError:
        return None, None


def gh_pr_info(branch: str | None, worktree_path: str | None) -> dict | None:
    if not branch or not worktree_path or shutil.which("gh") is None:
        return None
    try:
        result = subprocess.run(
            ["gh", "pr", "list", "--head", branch, "--json", "number,state,statusCheckRollup", "--limit", "1"],
            cwd=worktree_path, capture_output=True, text=True, timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError:
        return None
    if not data:
        return None
    pr = data[0]
    checks = pr.get("statusCheckRollup") or []
    conclusions = {c.get("conclusion") for c in checks if isinstance(c, dict)}
    ci = "unknown"
    if conclusions:
        if "FAILURE" in conclusions or "CANCELLED" in conclusions:
            ci = "red"
        elif conclusions == {"SUCCESS"}:
            ci = "green"
        else:
            ci = "running"
    return {"number": pr.get("number"), "state": pr.get("state"), "ci": ci}


def tail_log_lines(worktree_path: str | None, limit: int) -> list[str]:
    """AC-3's Live-activity/Log surface: `engineer.summary.log`, falling
    back to raw `engineer.log` if the summary does not exist yet -- never an
    exception for a movement with no logs at all."""
    if not worktree_path:
        return []
    nexus_dir = Path(worktree_path) / ".nexus"
    for name in ("engineer.summary.log", "engineer.log"):
        path = nexus_dir / name
        if path.is_file():
            try:
                lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
            except OSError:
                return []
            return lines[-limit:]
    return []


# ---------------------------------------------------------------------------
# Movement summary / detail assembly
# ---------------------------------------------------------------------------

def build_pending_action_card(
    *, movement_id: str, relay_file: Path, relay_obj: dict, worktree_path: str | None, state_dir: Path,
) -> dict | None:
    entries = relay_obj.get("entries") or []
    if not entries or relay_obj.get("next_actor") != "po" or relay_obj.get("status") == "CLOSED":
        return None
    last = entries[-1]
    if not isinstance(last, dict) or last.get("marker") not in ("RELAY_QUESTION", "RELAY_NOTE"):
        return None
    blocker_source, clean_subject = parse_blocker_tag(last.get("subject") or "")
    text = last.get("text") or ""
    source_relay_seq = len(entries)
    working_directory = worktree_path or ""

    if blocker_source == BLOCKER_PLATFORM_POLICY:
        return {
            "blocker_source": blocker_source, "requested_action": clean_subject, "detail": text,
            "primary_action_id": None, "primary_operation": None, "reject_action_id": None,
        }

    if blocker_source == BLOCKER_ALREADY_AUTHORIZED:
        operation = OPERATION_ROUTE_TO_ENGINEER
        primary_args = {
            "marker": "RELAY_NOTE", "subject": f"Routed: {clean_subject}",
            "text": f"Automatically routed to engineer -- already authorized. Original request: {text}",
            "next_actor": "engineer",
        }
    else:
        operation = OPERATION_RECORD_DECISION
        primary_args = {
            "marker": "RELAY_DECISION", "subject": clean_subject, "text": text, "next_actor": "engineer",
            "authorized_by": "Product Owner -- dashboard action", "scope": f"{movement_id} dashboard action",
            "supersedes": "none",
        }

    primary_id = register_action(
        state_dir, movement_id, relay_file=str(relay_file), operation=operation, args=primary_args,
        working_directory=working_directory, source_relay_seq=source_relay_seq,
    )

    reject_args = {
        "marker": "RELAY_DECISION", "subject": f"Declined: {clean_subject}", "text": text, "next_actor": "engineer",
        "authorized_by": "Product Owner -- dashboard action", "scope": f"{movement_id} dashboard action",
        "supersedes": "none",
    }
    reject_id = register_action(
        state_dir, movement_id, relay_file=str(relay_file), operation=OPERATION_REJECT, args=reject_args,
        working_directory=working_directory, source_relay_seq=source_relay_seq,
    )

    return {
        "blocker_source": blocker_source, "requested_action": clean_subject, "detail": text,
        "primary_action_id": primary_id, "primary_operation": operation, "reject_action_id": reject_id,
    }


def build_movement_summary(record: dict, relay_dir: Path, state_dir: Path, retry_limit: int) -> dict:
    row = orch._status_row(record, relay_dir, retry_limit)
    worktree_path = row.get("worktree_path")
    porcelain = git_worktree_porcelain(worktree_path)
    pr_info = gh_pr_info(row.get("branch"), worktree_path)

    relay_status, next_actor, pending_action = row.get("relay_status"), None, None
    try:
        relay_file = orch._resolve_relay_file(relay_dir, row["movement_id"])
        relay_obj = orch._load_relay(relay_file)
        next_actor = relay_obj.get("next_actor")
        pending_action = build_pending_action_card(
            movement_id=row["movement_id"], relay_file=relay_file, relay_obj=relay_obj,
            worktree_path=worktree_path, state_dir=state_dir,
        )
    except orch.OrchestratorError:
        pass

    stage = derive_work_stage(
        relay_status=relay_status, next_actor=next_actor, phase=row.get("phase"),
        porcelain_lines=porcelain, last_activity=row.get("last_activity"), has_open_pr=bool(pr_info),
    )
    return {
        **row,
        "process_status": derive_process_status(row),
        "work_stage": stage,
        "next_actor": next_actor,
        "open_pr": pr_info,
        "pending_action": pending_action,
        "outbox_pending": len(load_outbox(state_dir, row["movement_id"])),
    }


def gather_movements(state_dir: Path, relay_dir: Path, retry_limit: int) -> dict:
    summaries = [
        build_movement_summary(record, relay_dir, state_dir, retry_limit)
        for record in orch._list_state_records(state_dir)
    ]
    summaries.sort(key=lambda s: s["movement_id"])
    running = [s for s in summaries if s["work_stage"] not in (STAGE_MERGED,) and s["process_status"] != "disconnected"]
    awaiting = [s for s in summaries if s["pending_action"] is not None]
    recent = [s for s in summaries if s["work_stage"] == STAGE_MERGED]
    integration = [s for s in summaries if s["work_stage"] == STAGE_INTEGRATION]
    return {
        "summary": {
            "running": len(running), "awaiting_decision": len(awaiting), "in_integration": len(integration),
        },
        "movements": summaries,
    }


def build_movement_detail(movement_id: str, relay_dir: Path, state_dir: Path, retry_limit: int) -> dict:
    record = orch._load_state(state_dir, movement_id)
    if record is None:
        raise DashboardError(f"no state record for {movement_id}")
    summary = build_movement_summary(record, relay_dir, state_dir, retry_limit)

    relay_file = orch._resolve_relay_file(relay_dir, movement_id)
    relay_obj = orch._load_relay(relay_file)
    behind, ahead = git_ahead_behind(record.get("worktree_path"))

    approved_task = None
    worktree_path = record.get("worktree_path")
    if worktree_path:
        task_path = Path(worktree_path) / ".nexus" / "approved_task.json"
        if task_path.is_file():
            try:
                approved_task = json.loads(task_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                approved_task = None

    return {
        **summary,
        "relay_entries": relay_obj.get("entries", []),
        "relay_id": relay_obj.get("id"),
        "ahead": ahead, "behind": behind,
        "approved_task": approved_task,
        "outbox": load_outbox(state_dir, movement_id),
    }


# ---------------------------------------------------------------------------
# HTTP layer
# ---------------------------------------------------------------------------

def _json_bytes(obj: Any) -> bytes:
    return (json.dumps(obj, sort_keys=True) + "\n").encode("utf-8")


def make_handler_class(*, token: str, relay_dir: Path, state_dir: Path, repo_root: Path,
                        retry_limit: int, port: int) -> type[BaseHTTPRequestHandler]:
    expected_origin = f"http://127.0.0.1:{port}"

    class Handler(BaseHTTPRequestHandler):
        server_version = "neXusDashboard/1"

        def log_message(self, fmt: str, *args: Any) -> None:  # quiet by default
            pass

        # -- auth -------------------------------------------------------
        def _authorized(self) -> bool:
            header = self.headers.get("Authorization", "")
            if not header.startswith("Bearer "):
                return False
            return hmac.compare_digest(header[len("Bearer "):], token)

        def _origin_ok(self) -> bool:
            origin = self.headers.get("Origin")
            return origin is None or origin == expected_origin

        def _send_json(self, status: int, obj: Any) -> None:
            body = _json_bytes(obj)
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _send_static(self, path: Path, content_type: str) -> None:
            if not path.is_file():
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
                return
            body = path.read_bytes()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _read_json_body(self) -> Any:
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length <= 0:
                return {}
            raw = self.rfile.read(length)
            try:
                return json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                return None

        # -- routing ------------------------------------------------------
        def do_GET(self) -> None:  # noqa: N802 -- BaseHTTPRequestHandler naming
            parts = urlsplit(self.path)
            path, query = parts.path, parse_qs(parts.query)

            if path == "/":
                self._send_static(ASSETS_DIR / "index.html", "text/html; charset=utf-8")
                return
            if path == "/dashboard.css":
                self._send_static(ASSETS_DIR / "dashboard.css", "text/css; charset=utf-8")
                return
            if path == "/dashboard.js":
                self._send_static(ASSETS_DIR / "dashboard.js", "application/javascript; charset=utf-8")
                return

            if not path.startswith("/api/"):
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
                return
            if not self._authorized():
                self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "missing or invalid bearer token"})
                return

            if path == "/api/movements":
                self._send_json(HTTPStatus.OK, gather_movements(state_dir, relay_dir, retry_limit))
                return
            if path == "/api/config":
                self._send_json(HTTPStatus.OK, read_config(repo_root))
                return
            if path.startswith("/api/movements/"):
                rest = path[len("/api/movements/"):]
                if rest.endswith("/log"):
                    movement_id = rest[: -len("/log")]
                    limit = LOG_TAIL_DEFAULT
                    if "tail" in query:
                        try:
                            limit = max(1, min(LOG_TAIL_MAX, int(query["tail"][0])))
                        except ValueError:
                            pass
                    record = orch._load_state(state_dir, movement_id)
                    if record is None:
                        self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown movement"})
                        return
                    self._send_json(HTTPStatus.OK, {"lines": tail_log_lines(record.get("worktree_path"), limit)})
                    return
                movement_id = rest
                try:
                    detail = build_movement_detail(movement_id, relay_dir, state_dir, retry_limit)
                except (DashboardError, orch.OrchestratorError) as exc:
                    self._send_json(HTTPStatus.NOT_FOUND, {"error": str(exc)})
                    return
                self._send_json(HTTPStatus.OK, detail)
                return

            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

        def do_PUT(self) -> None:  # noqa: N802
            if not self.path.startswith("/api/"):
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
                return
            if not self._authorized():
                self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "missing or invalid bearer token"})
                return
            if not self._origin_ok():
                self._send_json(HTTPStatus.FORBIDDEN, {"error": "origin not allowed"})
                return
            if self.path == "/api/config":
                body = self._read_json_body()
                if not isinstance(body, dict):
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid JSON body"})
                    return
                ok, result = write_config(repo_root, body.get("poll_interval_seconds"))
                self._send_json(HTTPStatus.OK if ok else HTTPStatus.BAD_REQUEST,
                                 result if ok else {"error": result})
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

        def do_POST(self) -> None:  # noqa: N802
            if not self.path.startswith("/api/movements/"):
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
                return
            if not self._authorized():
                self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "missing or invalid bearer token"})
                return
            if not self._origin_ok():
                self._send_json(HTTPStatus.FORBIDDEN, {"error": "origin not allowed"})
                return

            rest = self.path[len("/api/movements/"):]
            body = self._read_json_body()
            if body is None:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid JSON body"})
                return

            if "/actions/" in rest and rest.endswith("/execute"):
                movement_id, remainder = rest.split("/actions/", 1)
                action_id = remainder[: -len("/execute")]
                try:
                    relay_file = orch._resolve_relay_file(relay_dir, movement_id)
                except orch.OrchestratorError as exc:
                    self._send_json(HTTPStatus.NOT_FOUND, {"error": str(exc)})
                    return
                try:
                    result = execute_action(state_dir, movement_id, relay_file, action_id)
                except DashboardError as exc:
                    self._send_json(HTTPStatus.CONFLICT, {"error": str(exc)})
                    return
                self._send_json(HTTPStatus.OK, result)
                return

            if rest.endswith("/message"):
                movement_id = rest[: -len("/message")]
                text = (body or {}).get("text") if isinstance(body, dict) else None
                if not text or not isinstance(text, str):
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "text is required"})
                    return
                try:
                    relay_file = orch._resolve_relay_file(relay_dir, movement_id)
                except orch.OrchestratorError as exc:
                    self._send_json(HTTPStatus.NOT_FOUND, {"error": str(exc)})
                    return
                try:
                    result = send_message(state_dir, movement_id, relay_file, text)
                except DashboardError as exc:
                    self._send_json(HTTPStatus.CONFLICT, {"error": str(exc)})
                    return
                self._send_json(HTTPStatus.OK, result)
                return

            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    return Handler


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def run_dashboard(*, port: int, relay_dir: Path, state_dir: Path, repo_root: Path, retry_limit: int) -> None:
    token = secrets.token_urlsafe(32)
    handler_cls = make_handler_class(
        token=token, relay_dir=relay_dir, state_dir=state_dir, repo_root=repo_root,
        retry_limit=retry_limit, port=port,
    )
    httpd = ThreadingHTTPServer(("127.0.0.1", port), handler_cls)
    print(f"neXus dashboard listening -- open http://127.0.0.1:{port}/#t={token}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="orchestrator_dashboard", description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--relay-dir", default="relay")
    parser.add_argument("--state-dir", default=str(orch.DEFAULT_STATE_DIR))
    parser.add_argument("--repo-root", default=str(REPO_ROOT))
    parser.add_argument("--retry-limit", type=int, default=orch.DEFAULT_RETRY_LIMIT)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    run_dashboard(
        port=args.port, relay_dir=Path(args.relay_dir).resolve(), state_dir=Path(args.state_dir),
        repo_root=Path(args.repo_root).resolve(), retry_limit=args.retry_limit,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
