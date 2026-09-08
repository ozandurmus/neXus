"""GOV.PO.3 -- deterministic local orchestration of an approved movement
(docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, FROZEN).

    py scripts/orchestrator.py start      --movement <relay-id> [options]
    py scripts/orchestrator.py status     [--movement <relay-id>] [options]
    py scripts/orchestrator.py stop       --movement <relay-id> [--force] [options]
    py scripts/orchestrator.py merge-lock {acquire|release} --movement <relay-id> [options]

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
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "scripts"))
import local_relay as lr  # noqa: E402

EXIT_OK, EXIT_REFUSED, EXIT_USAGE = 0, 1, 2

#: Runtime bookkeeping lives outside the repository, mirroring
#: nexus_po_tool_gate.py's NEXUS_PO_HOOK_LOG precedent -- process records
#: are not durable governance history and must not spam the git-tracked
#: relay file with per-invocation diffs.
DEFAULT_STATE_DIR = Path(
    os.environ.get("NEXUS_ORCHESTRATOR_STATE_DIR")
    or os.path.join(tempfile.gettempdir(), "nexus_orchestrator")
)
DEFAULT_WORKTREES_DIR = REPO_ROOT.parent / f"{REPO_ROOT.name}-nexus-worktrees"
DEFAULT_MAX_WORKERS = 3
DEFAULT_RETRY_LIMIT = 2
DEFAULT_MERGE_LOCK_TTL = 600
DEFAULT_ENGINEER_PROFILE = REPO_ROOT / ".claude" / "nexus-engineer.settings.json"
#: An unattended spawn has no human watching live spend; bound it. Not part
#: of the FROZEN amendment's own text -- a safety addition made before the
#: first real dispatch, named as such in this movement's own evidence.
DEFAULT_MAX_BUDGET_USD = 3.0

PHASE_RUNNING = "running"
PHASE_DONE = "done"
PHASE_FAILED = "failed"
PHASE_CANCELLED = "cancelled"
TERMINAL_PHASES = frozenset({PHASE_DONE, PHASE_FAILED, PHASE_CANCELLED})

#: The fixed prompt handed to every engineer process, identical for every
#: movement -- never task-content-dependent (section 3.5 of the FROZEN
#: amendment: the approved task itself is never interpolated into an argv
#: element; the engineer reads it with its own Read tool).
ENGINEER_PROMPT = (
    "Read .nexus/approved_task.json in the current directory -- it is your "
    "SESSION_START for this movement, delivered verbatim by the orchestrator "
    "and content-hash-verified against the Product Owner's own relay entry. "
    "Immediately before opening your PR, re-read the canonical relay file "
    "(NEXUS_RELAY_FILE) and act on any RELAY_CORRECTION or RELAY_DECISION "
    "entries appended after dispatch. Proceed as the engineer role under "
    "AGENTS.md, AI_START_HERE.md, and CLAUDE.md exactly as an interactively "
    "started session would."
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


def _spawn_engineer(
    *, worktree_path: Path, profile_path: Path, canonical_relay_dir: Path,
    relay_file: Path, resume_session_id: str | None = None,
    max_budget_usd: float = DEFAULT_MAX_BUDGET_USD,
) -> subprocess.Popen:
    env = dict(os.environ)
    env[lr.ENV_CANONICAL_RELAY_DIR] = str(canonical_relay_dir)
    env[lr.ENV_RELAY_FILE] = str(relay_file)
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
    argv = ["claude", "-p", ENGINEER_PROMPT, "--settings", str(profile_path),
            "--add-dir", str(canonical_relay_dir),
            "--permission-prompts", "none", "--max-budget-usd", str(max_budget_usd)]
    if resume_session_id:
        argv += ["--resume", resume_session_id]
    log_path = worktree_path / ".nexus" / "engineer.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_fh = open(log_path, "ab")
    return subprocess.Popen(
        argv, cwd=str(worktree_path), env=env,
        stdout=log_fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        start_new_session=True,
    )


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

def _cmd_start(args: argparse.Namespace) -> int:
    relay_dir = Path(args.relay_dir).resolve()
    state_dir = Path(args.state_dir)
    worktrees_dir = Path(args.worktrees_dir)
    try:
        relay_file = _resolve_relay_file(relay_dir, args.movement)
        relay_obj = _load_relay(relay_file)
    except OrchestratorError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE

    existing = _load_state(state_dir, args.movement)
    is_alive = pid_alive(existing.get("pid")) if existing else False
    active = _active_count(state_dir, exclude_movement=args.movement)
    action, reason = decide_start(
        relay_obj=relay_obj, existing_record=existing, is_pid_alive=is_alive,
        active_count=active, max_workers=args.max_workers,
    )
    if action == "refuse":
        print(f"error: {reason}", file=sys.stderr)
        return EXIT_REFUSED

    session_start = relay_obj["entries"][0]
    report = session_start.get("report", {})
    git_cfg = report.get("git", {})
    base, lane = git_cfg.get("base"), git_cfg.get("lane")
    ok, ref_reason = validate_git_refs(base, lane)
    if not ok:
        print(f"error: {ref_reason}", file=sys.stderr)
        return EXIT_USAGE

    hash_hex = task_hash(session_start)

    if action == "resume":
        worktree_path = Path(existing["worktree_path"])
        branch = existing["branch"]
        base_sha = existing["base_sha"]
        revision = existing["revision"]
        session_id = existing.get("session_id")
        proc = _spawn_engineer(
            worktree_path=worktree_path, profile_path=Path(args.profile),
            canonical_relay_dir=relay_dir, relay_file=relay_file,
            resume_session_id=session_id, max_budget_usd=args.max_budget_usd,
        )
        record = {**existing, "pid": proc.pid, "phase": PHASE_RUNNING,
                  "last_action": "resumed", "task_hash": hash_hex,
                  "dispatch_seq": len(relay_obj["entries"])}
        _save_state(state_dir, args.movement, record)
        print(json.dumps({"action": "resume", "movement_id": args.movement, "pid": proc.pid,
                           "worktree_path": str(worktree_path), "revision": revision}))
        return EXIT_OK

    # action == "dispatch"
    revision = 1 if existing is None else existing.get("revision", 0) + 1
    worktree_path = worktrees_dir / args.movement
    if worktree_path.exists():
        print(f"error: worktree path already exists: {worktree_path}", file=sys.stderr)
        return EXIT_REFUSED
    try:
        base_sha = _git_rev_parse(base, relay_dir.parent)
        _git_worktree_add(worktree_path, base, lane, relay_dir.parent)
    except OrchestratorError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_REFUSED

    nexus_dir = worktree_path / ".nexus"
    nexus_dir.mkdir(parents=True, exist_ok=True)
    (nexus_dir / "approved_task.json").write_text(canonical_json(session_start), encoding="utf-8")
    (nexus_dir / "movement_id.txt").write_text(args.movement + "\n", encoding="utf-8")

    proc = _spawn_engineer(
        worktree_path=worktree_path, profile_path=Path(args.profile),
        canonical_relay_dir=relay_dir, relay_file=relay_file,
        max_budget_usd=args.max_budget_usd,
    )
    record = {
        "movement_id": args.movement, "revision": revision, "base_sha": base_sha,
        "task_hash": hash_hex, "worktree_path": str(worktree_path), "branch": lane,
        "pid": proc.pid, "session_id": None, "phase": PHASE_RUNNING,
        "started_at": lr._utc_now_iso(), "heartbeat_at": lr._utc_now_iso(),
        "retry_count": 0, "last_error": None, "last_action": "dispatched",
        "dispatch_seq": len(relay_obj["entries"]),
    }
    _save_state(state_dir, args.movement, record)
    print(json.dumps({"action": "dispatch", "movement_id": args.movement, "pid": proc.pid,
                       "worktree_path": str(worktree_path), "branch": lane, "revision": revision}))
    return EXIT_OK


# ---------------------------------------------------------------------------
# status
# ---------------------------------------------------------------------------

def _status_row(record: dict, relay_dir: Path, retry_limit: int) -> dict:
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
    phase = reconcile_phase(record, is_alive, relay_status, retry_limit)
    if phase != record.get("phase"):
        record = {**record, "phase": phase}
    return {
        "movement_id": record["movement_id"], "phase": phase, "pid": record.get("pid"),
        "pid_alive": is_alive, "revision": record.get("revision"), "branch": record.get("branch"),
        "worktree_path": record.get("worktree_path"), "started_at": record.get("started_at"),
        "retry_count": record.get("retry_count", 0), "relay_status": relay_status,
        "last_marker": last_marker, "last_timestamp": last_timestamp,
    }


def _cmd_status(args: argparse.Namespace) -> int:
    relay_dir = Path(args.relay_dir).resolve()
    state_dir = Path(args.state_dir)
    if args.movement:
        record = _load_state(state_dir, args.movement)
        if record is None:
            print(f"error: no state record for {args.movement}", file=sys.stderr)
            return EXIT_USAGE
        row = _status_row(record, relay_dir, args.retry_limit)
        _save_state(state_dir, args.movement, {**record, "phase": row["phase"]})
        print(json.dumps(row, sort_keys=True))
        return EXIT_OK

    rows = []
    for record in _list_state_records(state_dir):
        row = _status_row(record, relay_dir, args.retry_limit)
        _save_state(state_dir, record["movement_id"], {**record, "phase": row["phase"]})
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
    p_start.set_defaults(func=_cmd_start)

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

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
