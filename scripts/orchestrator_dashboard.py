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
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))
import local_relay as lr  # noqa: E402
import orchestrator as orch  # noqa: E402
import orchestrator_usage as ou  # noqa: E402

ASSETS_DIR = Path(__file__).resolve().parent / "dashboard_assets"
DEFAULT_PORT = 8765
DEFAULT_CONFIG_FILENAME = "dashboard_config.json"
CONFIG_INTERVAL_MIN = 2
CONFIG_INTERVAL_MAX = 3600
LOG_TAIL_DEFAULT = 200
LOG_TAIL_MAX = 2000

#: GOV.ORCH.4 section 3.3: `stuck_after_seconds`' own valid range and
#: default, mirroring `CONFIG_INTERVAL_*`/`WATCH_INTERVAL_DEFAULT` above.
CONFIG_STUCK_MIN = 30
CONFIG_STUCK_MAX = 86400
DEFAULT_STUCK_AFTER_SECONDS = 900

#: Section 3.3's six-value health field -- `derive_health` below returns
#: exactly one of these and never anything else (AC-5).
HEALTH_HEALTHY = "healthy"
HEALTH_SILENT = "silent"
HEALTH_EXITED_WITHOUT_CLOSE = "exited_without_close"
HEALTH_AWAITING_PO = "awaiting_po"
HEALTH_FAILED = "failed"
HEALTH_DONE = "done"
ALL_HEALTH_VALUES = frozenset({
    HEALTH_HEALTHY, HEALTH_SILENT, HEALTH_EXITED_WITHOUT_CLOSE,
    HEALTH_AWAITING_PO, HEALTH_FAILED, HEALTH_DONE,
})

#: A sentinel distinct from `None` -- `None` is itself a legitimate
#: "clear stuck_after_seconds" value in principle, so a real "unset,
#: leave the stored value alone" needs its own marker (section 3.3's
#: config round-trip, AC-10).
_UNSET = object()

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


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.3: stuck detection -- real signals only, never git
# dirtiness or a heartbeat timestamp.
# ---------------------------------------------------------------------------

def _iso_from_epoch(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_iso(ts: str | None) -> float | None:
    if not ts:
        return None
    try:
        return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()
    except ValueError:
        return None


def _log_idle_seconds(worktree_path: str | None) -> float | None:
    """Seconds since `.nexus/engineer.log` last grew, from the file's own
    mtime -- never from `heartbeat_at` (a fixed dispatch-time value, not a
    running heartbeat) and never from git status. `None` when there is no
    worktree or no log yet (nothing observed, not "healthy")."""
    if not worktree_path:
        return None
    log_path = Path(worktree_path) / ".nexus" / "engineer.log"
    try:
        mtime = log_path.stat().st_mtime
    except OSError:
        return None
    return max(0.0, time.time() - mtime)


def derive_health(
    *, pid_alive: bool, relay_status: str | None, next_actor: str | None, phase: str | None,
    idle_seconds: float | None, stuck_after_seconds: int = DEFAULT_STUCK_AFTER_SECONDS,
) -> str:
    """Section 3.3's six-value health field, computed only from a live pid
    check, relay status/next_actor, the record's own `phase`, and
    `.nexus/engineer.log` growth -- returns exactly one of
    `ALL_HEALTH_VALUES` (AC-5). Fixed precedence: a terminal outcome (`done`,
    `failed`) always wins; `awaiting_po` comes next -- an engineer session
    ends its process after posting a question, so a dead pid together with
    `next_actor == "po"` is the ordinary, expected pause, not a problem;
    only then does a genuinely dead-but-not-terminal process count as
    `exited_without_close`; a still-alive process is `silent` or `healthy`
    depending on how long `engineer.log` has gone quiet."""
    if relay_status == "CLOSED" or phase == orch.PHASE_DONE:
        return HEALTH_DONE
    if phase == orch.PHASE_FAILED:
        return HEALTH_FAILED
    if next_actor == "po":
        return HEALTH_AWAITING_PO
    if not pid_alive:
        return HEALTH_EXITED_WITHOUT_CLOSE
    if idle_seconds is not None and idle_seconds >= stuck_after_seconds:
        return HEALTH_SILENT
    return HEALTH_HEALTHY


# ---------------------------------------------------------------------------

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
    """Returns exactly `{"poll_interval_seconds": ...}` when no
    `stuck_after_seconds` has ever been stored -- the pre-GOV.ORCH.4 shape,
    preserved byte-for-byte so the original config tests keep passing
    unchanged. `stuck_after_seconds` is only ever present in the returned
    dict once a valid value for it has actually been written (section 3.3);
    a caller that always wants a number regardless uses `config_for_api`
    below, which fills in the section 3.3 default for display."""
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
    cfg = {"poll_interval_seconds": interval}
    stuck = obj.get("stuck_after_seconds") if isinstance(obj, dict) else None
    if isinstance(stuck, int) and not isinstance(stuck, bool) and CONFIG_STUCK_MIN <= stuck <= CONFIG_STUCK_MAX:
        cfg["stuck_after_seconds"] = stuck
    return cfg


def config_for_api(repo_root: Path) -> dict:
    """`GET /api/config`'s own shape: `read_config` plus a filled-in
    `stuck_after_seconds` default (section 3.3) so the UI always has a
    number to show/edit, even before one has ever been saved."""
    cfg = read_config(repo_root)
    cfg.setdefault("stuck_after_seconds", DEFAULT_STUCK_AFTER_SECONDS)
    return cfg


def _raw_config_obj(repo_root: Path) -> dict:
    path = _config_path(repo_root)
    if not path.is_file():
        return {}
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return obj if isinstance(obj, dict) else {}


def write_config(repo_root: Path, poll_interval_seconds: Any = _UNSET, stuck_after_seconds: Any = _UNSET) -> tuple[bool, dict | str]:
    """Merges onto whatever is already stored -- a caller that only sends
    one of the two fields (e.g. the UI's separate poll-interval and
    stuck-after-seconds inputs) never clobbers the other (AC-10's
    round-trip). Passing neither is a no-op that still returns the
    (unioned) current config. `poll_interval_seconds` alone, the
    pre-GOV.ORCH.4 call shape, keeps behaving exactly as before."""
    cfg = _raw_config_obj(repo_root)
    if poll_interval_seconds is not _UNSET:
        if not isinstance(poll_interval_seconds, int) or isinstance(poll_interval_seconds, bool):
            return False, "poll_interval_seconds must be an integer"
        if not (CONFIG_INTERVAL_MIN <= poll_interval_seconds <= CONFIG_INTERVAL_MAX):
            return False, f"poll_interval_seconds must be between {CONFIG_INTERVAL_MIN} and {CONFIG_INTERVAL_MAX}"
        cfg["poll_interval_seconds"] = poll_interval_seconds
    if stuck_after_seconds is not _UNSET:
        if not isinstance(stuck_after_seconds, int) or isinstance(stuck_after_seconds, bool):
            return False, "stuck_after_seconds must be an integer"
        if not (CONFIG_STUCK_MIN <= stuck_after_seconds <= CONFIG_STUCK_MAX):
            return False, f"stuck_after_seconds must be between {CONFIG_STUCK_MIN} and {CONFIG_STUCK_MAX}"
        cfg["stuck_after_seconds"] = stuck_after_seconds
    cfg.setdefault("poll_interval_seconds", orch.WATCH_INTERVAL_DEFAULT)
    lr._atomic_write(_config_path(repo_root), json.dumps(cfg, sort_keys=True, indent=2) + "\n")
    result = {"poll_interval_seconds": cfg["poll_interval_seconds"]}
    if "stuck_after_seconds" in cfg:
        result["stuck_after_seconds"] = cfg["stuck_after_seconds"]
    return True, result


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


def _price_table_path(repo_root: Path) -> Path:
    return Path(repo_root) / "config" / "model_prices.json"


def _load_approved_task(worktree_path: str | None) -> dict | None:
    if not worktree_path:
        return None
    task_path = Path(worktree_path) / ".nexus" / "approved_task.json"
    if not task_path.is_file():
        return None
    try:
        return json.loads(task_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def build_movement_summary(
    record: dict, relay_dir: Path, state_dir: Path, retry_limit: int, *,
    stuck_after_seconds: int = DEFAULT_STUCK_AFTER_SECONDS, price_table: dict | None = None,
) -> dict:
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
    idle_seconds = _log_idle_seconds(worktree_path)
    health = derive_health(
        pid_alive=bool(row.get("pid_alive")), relay_status=relay_status, next_actor=next_actor,
        phase=row.get("phase"), idle_seconds=idle_seconds, stuck_after_seconds=stuck_after_seconds,
    )
    usage = ou.compute_usage(state_dir, row["movement_id"], worktree_path, row.get("provider", "claude"), price_table or {})
    return {
        **row,
        "process_status": derive_process_status(row),
        "work_stage": stage,
        "next_actor": next_actor,
        "open_pr": pr_info,
        "pending_action": pending_action,
        "outbox_pending": len(load_outbox(state_dir, row["movement_id"])),
        # GOV.ORCH.4 section 3.4: added to the existing shape, nothing above
        # removed or renamed (AC-8).
        "health": health,
        "idle_seconds": idle_seconds,
        "usage": usage,
    }


def gather_movements(
    state_dir: Path, relay_dir: Path, retry_limit: int, *,
    stuck_after_seconds: int = DEFAULT_STUCK_AFTER_SECONDS, price_table: dict | None = None,
) -> dict:
    summaries = [
        build_movement_summary(record, relay_dir, state_dir, retry_limit,
                                stuck_after_seconds=stuck_after_seconds, price_table=price_table)
        for record in orch._list_state_records(state_dir)
    ]
    summaries.sort(key=lambda s: s["movement_id"])
    running = [s for s in summaries if s["work_stage"] not in (STAGE_MERGED,) and s["process_status"] != "disconnected"]
    awaiting = [s for s in summaries if s["pending_action"] is not None]
    recent = [s for s in summaries if s["work_stage"] == STAGE_MERGED]
    integration = [s for s in summaries if s["work_stage"] == STAGE_INTEGRATION]
    silent = [s for s in summaries if s["health"] == HEALTH_SILENT]
    failed = [s for s in summaries if s["health"] == HEALTH_FAILED]

    # Section 3.5's summary strip: tokens/cost for whatever usage activity
    # was last observed today (UTC) -- a real signal (`usage.last_event_at`,
    # itself the engineer.log mtime at the last parsed event), never a
    # fabricated running total.
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    tokens_today = 0
    cost_today = 0.0
    have_cost_today = False
    for s in summaries:
        usage = s.get("usage") or {}
        last_event_at = usage.get("last_event_at")
        if last_event_at and last_event_at[:10] == today:
            tokens_today += usage.get("total_tokens") or 0
            if usage.get("cost_usd") is not None:
                cost_today += usage["cost_usd"]
                have_cost_today = True

    return {
        "summary": {
            "running": len(running), "awaiting_decision": len(awaiting), "in_integration": len(integration),
            "silent": len(silent), "failed": len(failed),
            "tokens_today": tokens_today, "cost_today": round(cost_today, 6) if have_cost_today else None,
        },
        "movements": summaries,
    }


def build_movement_detail(
    movement_id: str, relay_dir: Path, state_dir: Path, retry_limit: int, *,
    stuck_after_seconds: int = DEFAULT_STUCK_AFTER_SECONDS, price_table: dict | None = None,
) -> dict:
    record = orch._load_state(state_dir, movement_id)
    if record is None:
        raise DashboardError(f"no state record for {movement_id}")
    summary = build_movement_summary(record, relay_dir, state_dir, retry_limit,
                                      stuck_after_seconds=stuck_after_seconds, price_table=price_table)

    relay_file = orch._resolve_relay_file(relay_dir, movement_id)
    relay_obj = orch._load_relay(relay_file)
    behind, ahead = git_ahead_behind(record.get("worktree_path"))
    approved_task = _load_approved_task(record.get("worktree_path"))

    return {
        **summary,
        "relay_entries": relay_obj.get("entries", []),
        "relay_id": relay_obj.get("id"),
        "ahead": ahead, "behind": behind,
        "approved_task": approved_task,
        "outbox": load_outbox(state_dir, movement_id),
        # GOV.ORCH.4 section 3.5's Evidence tab: the GOV.ORCH.1 verify
        # steps, straight from the record `orchestrator.py run` saved
        # (None for a movement never run to completion by `run`).
        "verify": record.get("verify"),
    }


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.4: work board and traffic view
# ---------------------------------------------------------------------------

def _record_mtime_iso(state_dir: Path, movement_id: str) -> str | None:
    """A real, non-fabricated stand-in for "ended_at": the state record has
    no explicit end timestamp, but it is only ever re-saved when the
    movement's phase changes, so the file's own mtime at a terminal phase
    is the actual moment that terminal state was recorded."""
    try:
        return _iso_from_epoch(orch._state_path(state_dir, movement_id).stat().st_mtime)
    except OSError:
        return None


def _board_row(record: dict, summary: dict, state_dir: Path) -> dict:
    approved_task = _load_approved_task(record.get("worktree_path"))
    objective = ((approved_task or {}).get("report") or {}).get("objective") or ""
    started_at = record.get("started_at")
    started_epoch = _parse_iso(started_at)
    ended_at = None
    if summary.get("phase") in orch.TERMINAL_PHASES:
        ended_at = _record_mtime_iso(state_dir, summary["movement_id"])
    ended_epoch = _parse_iso(ended_at)
    duration_s = None
    if started_epoch is not None:
        duration_s = round((ended_epoch if ended_epoch is not None else time.time()) - started_epoch, 3)
    open_pr = summary.get("open_pr") or {}
    verify = record.get("verify") or {}
    return {
        "movement_id": summary["movement_id"],
        "objective": objective[:120],
        "provider": summary.get("provider"),
        "model_requested": summary.get("model_requested"),
        "effort_requested": summary.get("effort_requested"),
        "model_observed": record.get("model_observed"),
        "effort_observed": record.get("effort_observed"),
        "health": summary["health"],
        "stage": summary["work_stage"],
        "process_status": summary["process_status"],
        "idle_seconds": summary.get("idle_seconds"),
        "started_at": started_at,
        "ended_at": ended_at,
        "duration_s": duration_s,
        "usage": summary["usage"],
        "pr_number": open_pr.get("number"),
        "pr_state": open_pr.get("state"),
        "verify_passed": verify.get("passed"),
        "branch": summary.get("branch"),
    }


def build_board(
    state_dir: Path, relay_dir: Path, repo_root: Path, retry_limit: int,
    stuck_after_seconds: int = DEFAULT_STUCK_AFTER_SECONDS,
) -> dict:
    """Section 3.4's `GET /api/board`: three buckets (open/awaiting_po/
    closed) plus totals with `silent`/`failed` sub-counts of `open` -- the
    Kanban UI (section 3.5) further splits `open` client-side by each row's
    own `health`/`stage`, so this shape never has to change to grow a
    column."""
    price_table = ou.load_price_table(_price_table_path(repo_root))
    rows = []
    for record in orch._list_state_records(state_dir):
        summary = build_movement_summary(
            record, relay_dir, state_dir, retry_limit,
            stuck_after_seconds=stuck_after_seconds, price_table=price_table,
        )
        rows.append(_board_row(record, summary, state_dir))
    rows.sort(key=lambda r: r["movement_id"])

    columns: dict[str, list[dict]] = {"open": [], "awaiting_po": [], "closed": []}
    for row in rows:
        if row["health"] == HEALTH_DONE:
            columns["closed"].append(row)
        elif row["health"] == HEALTH_AWAITING_PO:
            columns["awaiting_po"].append(row)
        else:
            columns["open"].append(row)

    totals = {
        "open": len(columns["open"]), "awaiting_po": len(columns["awaiting_po"]), "closed": len(columns["closed"]),
        "silent": sum(1 for r in rows if r["health"] == HEALTH_SILENT),
        "failed": sum(1 for r in rows if r["health"] == HEALTH_FAILED),
    }
    return {**columns, "totals": totals}


def build_traffic(relay_entries: list) -> list:
    """Section 3.4's sent/received list: every `po`-actor relay entry is
    something sent to the worker, every `engineer`-actor entry something
    received from it; a `SESSION_CLOSE` additionally carries its own
    `outcome`/`changed`/`validation` (AC-7)."""
    items = []
    for entry in relay_entries or []:
        if not isinstance(entry, dict):
            continue
        actor = entry.get("actor")
        direction = "sent" if actor == "po" else "received" if actor == "engineer" else "other"
        item = {
            "seq": entry.get("seq"), "timestamp": entry.get("timestamp"), "direction": direction,
            "actor": actor, "marker": entry.get("marker"), "subject": entry.get("subject"),
        }
        if entry.get("marker") == "SESSION_CLOSE":
            report = entry.get("report") if isinstance(entry.get("report"), dict) else {}
            item["outcome"] = entry.get("outcome")
            item["changed"] = report.get("changed")
            item["validation"] = report.get("validation")
        items.append(item)
    return items


def build_traffic_for_movement(record: dict, relay_obj: dict) -> list:
    """`build_traffic` plus the dispatch/verify events the process record
    itself carries (contract section 3.4: "dispatch, resume and verify
    events from the process record are interleaved as orchestrator rows"),
    interleaved by timestamp."""
    entries = relay_obj.get("entries") or []
    items = build_traffic(entries)
    started_at = record.get("started_at")
    last_ts = entries[-1].get("timestamp") if entries and isinstance(entries[-1], dict) else started_at
    if started_at:
        items.append({
            "seq": None, "timestamp": started_at, "direction": "orchestrator", "actor": "orchestrator",
            "marker": "DISPATCH", "subject": f"dispatched (provider={record.get('provider', 'claude')})",
        })
    verify = record.get("verify")
    if verify is not None:
        items.append({
            "seq": None, "timestamp": last_ts, "direction": "orchestrator", "actor": "orchestrator",
            "marker": "VERIFY", "subject": f"verify passed={verify.get('passed')}",
        })
    items.sort(key=lambda i: i.get("timestamp") or "")
    return items


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.4/3.6: usage totals across the state dir
# ---------------------------------------------------------------------------

_USAGE_SUM_FIELDS = (
    "turns", "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens",
    "output_tokens", "total_tokens",
)


def _sum_usage_rows(rows: list) -> dict:
    totals = {"movements": len(rows), "cost_usd": None}
    for field in _USAGE_SUM_FIELDS:
        totals[field] = 0
    cost_sum = 0.0
    have_cost = False
    for row in rows:
        for field in _USAGE_SUM_FIELDS:
            totals[field] += row.get(field, 0) or 0
        if row.get("cost_usd") is not None:
            cost_sum += row["cost_usd"]
            have_cost = True
    if have_cost:
        totals["cost_usd"] = round(cost_sum, 6)
    return totals


def build_usage_report(state_dir: Path, relay_dir: Path, repo_root: Path, since: str | None = None) -> dict:
    """Section 3.4's `GET /api/usage` and section 3.6's `orchestrator.py
    usage` CLI share this one function (AC-9: they must return identical
    data for the same state dir)."""
    price_table = ou.load_price_table(_price_table_path(repo_root))
    rows = []
    for record in orch._list_state_records(state_dir):
        started_at = record.get("started_at")
        if since and (not started_at or started_at < since):
            continue
        provider = record.get("provider", "claude")
        usage = ou.compute_usage(state_dir, record["movement_id"], record.get("worktree_path"), provider, price_table)
        rows.append({"movement_id": record["movement_id"], **usage})
    rows.sort(key=lambda r: r["movement_id"])
    return {"movements": rows, "totals": _sum_usage_rows(rows)}


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
                stuck_after_seconds = config_for_api(repo_root)["stuck_after_seconds"]
                price_table = ou.load_price_table(_price_table_path(repo_root))
                self._send_json(HTTPStatus.OK, gather_movements(
                    state_dir, relay_dir, retry_limit,
                    stuck_after_seconds=stuck_after_seconds, price_table=price_table,
                ))
                return
            if path == "/api/config":
                self._send_json(HTTPStatus.OK, config_for_api(repo_root))
                return
            if path == "/api/board":
                stuck_after_seconds = config_for_api(repo_root)["stuck_after_seconds"]
                self._send_json(HTTPStatus.OK, build_board(state_dir, relay_dir, repo_root, retry_limit, stuck_after_seconds))
                return
            if path == "/api/usage":
                since = query.get("since", [None])[0]
                self._send_json(HTTPStatus.OK, build_usage_report(state_dir, relay_dir, repo_root, since=since))
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
                if rest.endswith("/traffic"):
                    movement_id = rest[: -len("/traffic")]
                    record = orch._load_state(state_dir, movement_id)
                    if record is None:
                        self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown movement"})
                        return
                    try:
                        relay_file = orch._resolve_relay_file(relay_dir, movement_id)
                        relay_obj = orch._load_relay(relay_file)
                    except orch.OrchestratorError as exc:
                        self._send_json(HTTPStatus.NOT_FOUND, {"error": str(exc)})
                        return
                    self._send_json(HTTPStatus.OK, {"traffic": build_traffic_for_movement(record, relay_obj)})
                    return
                movement_id = rest
                stuck_after_seconds = config_for_api(repo_root)["stuck_after_seconds"]
                price_table = ou.load_price_table(_price_table_path(repo_root))
                try:
                    detail = build_movement_detail(
                        movement_id, relay_dir, state_dir, retry_limit,
                        stuck_after_seconds=stuck_after_seconds, price_table=price_table,
                    )
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
                # AC-10: either field may be sent alone -- write_config merges
                # onto whatever is already stored rather than requiring both.
                poll = body["poll_interval_seconds"] if "poll_interval_seconds" in body else _UNSET
                stuck = body["stuck_after_seconds"] if "stuck_after_seconds" in body else _UNSET
                ok, result = write_config(repo_root, poll, stuck)
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
