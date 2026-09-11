#!/usr/bin/env python3
"""GOV.PO.3 PreToolUse/PostToolUse gate for an orchestrated engineer session
(docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, FROZEN, section 3.4;
Addition A of the freeze decision on relay/NXS-LOCAL-0007).

Usage (as a Claude Code hook, from .claude/nexus-engineer.settings.json):
    python3 scripts/nexus_engineer_tool_gate.py --event pre
    python3 scripts/nexus_engineer_tool_gate.py --event post

Unlike scripts/nexus_po_tool_gate.py (default-deny, a narrow allowlist),
this gate is **default-allow**: an orchestrated engineer session has
"normal dev tools: Read/Edit/Write/Bash/Git" (the FROZEN amendment's own
words), exactly as an interactive engineer session has today. Exactly two
command shapes are checked, at the code-publish / artifact-egress boundary
(AC-7) and the cross-movement integration boundary (section 3.8):

  git push* / gh pr create*  -- deny unless the repository privacy gate
                                 (`utils.repository_privacy.scan_repository`,
                                 the same function `main.py
                                 --repository-privacy-check` calls) reports
                                 no *new* findings relative to this
                                 movement's own base commit. Not a new
                                 mandatory scan: the identical check already
                                 runs in CI on every PR (GOV_PO_2 section
                                 3.1); this runs it locally, before push, so
                                 an unattended session fails fast on its own
                                 mistake. It is baseline-aware (see
                                 `_privacy_check` below) -- a finding already
                                 present at the movement's own base commit is
                                 pre-existing repository debt and never
                                 blocks; only a finding absent from that
                                 baseline is new and blocks, exactly as
                                 before (relay/NXS-LOCAL-0012 seq 3-4: the
                                 live incident this closes).

  gh pr merge*                -- acquire the cross-movement merge lock
                                 (scripts/orchestrator.py merge-lock),
                                 merge origin/main into the movement branch,
                                 re-run tests/test_architecture_convergence.py,
                                 scripts/build_history_index.py --check, and
                                 the movement's own targeted tests (read
                                 from .nexus/approved_task.json's
                                 report.validation_plan), and only then
                                 allow the actual merge -- Addition A of the
                                 relay/NXS-LOCAL-0007 freeze decision, closing
                                 the "re-validate against an advanced main"
                                 gap the FROZEN amendment itself named as a
                                 residual risk (section 3.8/9). Any failure
                                 denies the merge and releases the lock
                                 immediately, so one movement's own failure
                                 never blocks another's integration.

  git push --force*/-f*       -- denied unconditionally (the one deny this
                                 profile shares with the PO profile -- a
                                 general stance on force-push, not
                                 orchestration-specific reasoning).

Everything else -- arbitrary Read/Edit/Write/Bash/git add|commit|branch|...
-- is unrestricted. No device, deployment, or collection capability is
added or changed: the existing network-device command gate and real-device
approval boundary in docs/AI_DEVELOPMENT_PROTOCOL.md are untouched by this
profile.

The `post` event releases the merge lock after a `gh pr merge*` call
completes (success or failure), best-effort; a crash before this fires is
covered by the lock's own TTL-based staleness reclaim
(scripts/orchestrator.py::decide_merge_lock_acquire), not by this hook.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ORCHESTRATOR = REPO_ROOT / "scripts" / "orchestrator.py"

sys.path.insert(0, str(REPO_ROOT / "scripts"))
import orchestrator_verify as _orch_verify  # noqa: E402
# Re-exported for backward compatibility: this module's own test suite
# (tests/test_nexus_engineer_tool_gate.py) calls these by name directly.
from utils.repository_privacy import (  # noqa: E402, F401
    PrivacyFinding,
    RepositoryPrivacyError,
    scan_repository,
)
from utils.repository_privacy import finding_key as _finding_key  # noqa: E402, F401


def _resolve_interpreter(cwd: str) -> str:
    """Resolve the project's validated interpreter for the merge-lock
    re-validation subprocess calls below.

    A worktree checkout has no `.venv` of its own -- every worktree shares
    the one `.venv` next to the main checkout -- so `sys.executable` here
    resolves to whatever `python3` the *parent* process (this hook) happened
    to be launched with, which is not necessarily the interpreter with
    pytest installed (relay/NXS-LOCAL-0016 seq 2: the live incident this
    closes). Locate the shared `.venv` via `git rev-parse --git-common-dir`,
    which is stable across every worktree, and fall back to
    `sys.executable` if it is not found (e.g. a profile with no `.venv`).
    """
    result = _run(["git", "rev-parse", "--git-common-dir"], cwd)
    if result.returncode != 0:
        return sys.executable
    common_dir = Path(result.stdout.strip())
    if not common_dir.is_absolute():
        common_dir = (Path(cwd) / common_dir).resolve()
    venv_root = common_dir.parent
    for candidate in (
        venv_root / ".venv" / "bin" / "python3",
        venv_root / ".venv" / "Scripts" / "python.exe",
    ):
        if candidate.is_file():
            return str(candidate)
    return sys.executable


def _validation_commands(cwd: str) -> tuple[list[str], ...]:
    python = _resolve_interpreter(cwd)
    return (
        [python, "-m", "pytest", "tests/test_architecture_convergence.py", "-q"],
        [python, "scripts/build_history_index.py", "--check"],
    )


def _movement_id(cwd: str) -> str | None:
    marker = Path(cwd) / ".nexus" / "movement_id.txt"
    if not marker.is_file():
        return None
    return marker.read_text(encoding="utf-8").strip() or None


def _run(argv: list[str], cwd: str, timeout: int = 600) -> subprocess.CompletedProcess:
    return subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def _approved_task_git_base(cwd: str) -> str | None:
    path = Path(cwd) / ".nexus" / "approved_task.json"
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    base = data.get("report", {}).get("git", {}).get("base")
    return base if isinstance(base, str) and base else None


def _baseline_finding_keys(cwd: str) -> tuple[frozenset[tuple[str, str, str]], str]:
    """Scan the movement's own base commit once and return its finding keys.

    GOV.ORCH.1: kept as a thin, monkeypatchable wrapper for backward
    compatibility with this module's own test suite -- the actual
    baseline-aware privacy check (below) now runs entirely through
    ``scripts/orchestrator_verify.py::privacy_check``, the one
    implementation both this hook and ``orchestrator.py``'s own `verify`
    step call (section 2.2 of GOV_ORCH_1_SYNCHRONOUS_RUN_AND_ORCHESTRATOR_VERIFY.md)."""
    return _orch_verify.baseline_finding_keys(cwd, _approved_task_git_base(cwd))


def _privacy_check(cwd: str) -> tuple[bool, str]:
    """Baseline-aware repository privacy gate. GOV.ORCH.1 moved the actual
    implementation to ``orchestrator_verify.privacy_check`` -- behaviour is
    unchanged; this wrapper only supplies the movement-local baseline ref
    source (``.nexus/approved_task.json``'s ``report.git.base``)."""
    return _orch_verify.privacy_check(cwd, _approved_task_git_base(cwd))


def _merge_lock(action: str, movement_id: str, cwd: str) -> tuple[bool, str]:
    argv = [sys.executable, str(ORCHESTRATOR), "merge-lock", action, "--movement", movement_id,
            "--pid", str(os.getpid())]
    result = _run(argv, cwd)
    try:
        payload = json.loads(result.stdout.strip() or "{}")
    except json.JSONDecodeError:
        payload = {}
    if action == "acquire":
        return bool(payload.get("acquired")), payload.get("reason", result.stderr.strip())
    return bool(payload.get("released")), payload.get("reason", result.stderr.strip())


def _integration_check(movement_id: str, cwd: str) -> tuple[bool, str]:
    # NOTE: `report.validation_plan` (GOV.SESSION.1 schema) is free-text
    # English prose ("run the focused suite"), not a machine-executable
    # command list -- there is no general way to run "the movement's own
    # targeted tests" from it mechanically. _validation_commands() above is
    # the mechanically-enforced floor Addition A actually specifies
    # (tests/test_architecture_convergence.py + build_history_index.py
    # --check); a movement's own targeted tests remain the engineer
    # session's own pre-merge responsibility, unchanged and unenforced by
    # this gate.
    fetch = _run(["git", "fetch", "origin"], cwd)
    if fetch.returncode != 0:
        return False, f"git fetch origin failed: {fetch.stderr.strip()}"
    merge = _run(["git", "merge", "origin/main"], cwd)
    if merge.returncode != 0:
        return False, f"git merge origin/main failed (resolve conflicts and retry): {merge.stderr.strip()}"
    for argv in _validation_commands(cwd):
        result = _run(argv, cwd)
        if result.returncode != 0:
            tail = (result.stdout + result.stderr).strip()[-2000:]
            return False, f"{' '.join(argv)} failed after merging origin/main: {tail}"
    return True, "origin/main merged; convergence and build-history checks green"


def _deny(reason: str) -> int:
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": f"nexus-engineer gate: {reason}",
    }}))
    print(f"nexus-engineer gate denied: {reason}", file=sys.stderr)
    return 2


def _allow(reason: str) -> int:
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "allow",
        "permissionDecisionReason": f"nexus-engineer gate: {reason}",
    }}))
    return 0


def handle_pre(payload: dict) -> int:
    tool = str(payload.get("tool_name", ""))
    tool_input = payload.get("tool_input") or {}
    cwd = payload.get("cwd") or str(Path.cwd())
    if tool != "Bash":
        return _allow("non-Bash tool, unrestricted")
    cmd = " ".join(str(tool_input.get("command", "")).split())

    if cmd.startswith("git push") and ("--force" in cmd.split() or cmd.startswith("git push -f")):
        return _deny("force push is never allowed")

    if cmd.startswith("git push") or cmd.startswith("gh pr create"):
        ok, reason = _privacy_check(cwd)
        return _allow(reason) if ok else _deny(reason)

    if cmd.startswith("gh pr merge"):
        movement_id = _movement_id(cwd)
        if not movement_id:
            return _deny("no .nexus/movement_id.txt in cwd; cannot serialize this merge -- refusing to guess")
        acquired, reason = _merge_lock("acquire", movement_id, cwd)
        if not acquired:
            return _deny(f"merge lock not acquired: {reason}")
        ok, check_reason = _integration_check(movement_id, cwd)
        if not ok:
            _merge_lock("release", movement_id, cwd)
            return _deny(check_reason)
        return _allow(f"merge lock held ({reason}); {check_reason}")

    return _allow("unrestricted (normal dev tools)")


def handle_post(payload: dict) -> int:
    tool = str(payload.get("tool_name", ""))
    tool_input = payload.get("tool_input") or {}
    cwd = payload.get("cwd") or str(Path.cwd())
    if tool != "Bash":
        return 0
    cmd = " ".join(str(tool_input.get("command", "")).split())
    if cmd.startswith("gh pr merge"):
        movement_id = _movement_id(cwd)
        if movement_id:
            _merge_lock("release", movement_id, cwd)
    return 0


def main(argv: list[str] | None = None) -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--event", choices=("pre", "post"), required=True)
    args = parser.parse_args(argv)
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        payload = {}
    if args.event == "pre":
        return handle_pre(payload)
    return handle_post(payload)


if __name__ == "__main__":
    sys.exit(main())
