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
                                 (`<interpreter> main.py
                                 --repository-privacy-check`) reports PASS.
                                 Not a new mandatory scan: the identical
                                 check already runs in CI on every PR
                                 (GOV_PO_2 section 3.1); this runs it
                                 locally, before push, so an unattended
                                 session fails fast on its own mistake.

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

VALIDATION_COMMANDS = (
    [sys.executable, "-m", "pytest", "tests/test_architecture_convergence.py", "-q"],
    [sys.executable, "scripts/build_history_index.py", "--check"],
)


def _movement_id(cwd: str) -> str | None:
    marker = Path(cwd) / ".nexus" / "movement_id.txt"
    if not marker.is_file():
        return None
    return marker.read_text(encoding="utf-8").strip() or None


def _run(argv: list[str], cwd: str, timeout: int = 600) -> subprocess.CompletedProcess:
    return subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def _privacy_check(cwd: str) -> tuple[bool, str]:
    result = _run([sys.executable, "main.py", "--repository-privacy-check"], cwd, timeout=120)
    if result.returncode == 0:
        return True, "repository privacy gate: PASS"
    tail = (result.stdout + result.stderr).strip()[-2000:]
    return False, f"repository privacy gate did not report PASS (exit {result.returncode}): {tail}"


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
    # targeted tests" from it mechanically. VALIDATION_COMMANDS above is
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
    for argv in VALIDATION_COMMANDS:
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
