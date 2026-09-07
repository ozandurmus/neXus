#!/usr/bin/env python3
"""GOV.PO.1 PreToolUse gate for the Product Owner assistant role (stdlib only).

Usage (as a Claude Code PreToolUse hook):
    python3 scripts/nexus_po_tool_gate.py --form delegated
    python3 scripts/nexus_po_tool_gate.py --form interactive

Reads the hook JSON on stdin, decides allow/deny, prints the documented
`hookSpecificOutput` JSON and exits 0 (allow) or 2 (deny). Every decision is
appended as one JSON line to the log named by $NEXUS_PO_HOOK_LOG (default:
<system temp dir>/nexus_po_hook.log) -- never inside the repository. The log
carries tool name, the first 200 characters of the command/path, the
decision and reason, plus session/agent ids the harness supplies; it never
carries file contents.

Forms (docs/design/GOV_PO_ROLE_MIGRATION.md section 4 and 6.2):
  delegated   -- Phase B subagent: read/search tools, `gh issue view/list`,
                 `gh issue comment` limited to the relay markers, `gh pr
                 view/checks`, read-only git, the session-transfer script.
                 No Edit/Write/NotebookEdit, no Agent, no Git write, no
                 collection, no shell redirection.
  interactive -- Phase A session: the delegated set plus Edit/Write on the
                 governance paths only, `gh issue create`, and Git branch/
                 commit/push/PR only on a `gov/po-*` branch.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from fnmatch import fnmatch

READ_TOOLS = {"Read", "Grep", "Glob", "LS", "ToolSearch", "WebFetch", "WebSearch", "TodoWrite"}
EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}

GOVERNANCE_PATHS = (
    "docs/design/PRODUCT_DIRECTION_RECORD.md",
    "project/roadmap.json",
    "project/backlog.json",
    "project/feature_registry.json",
    "project/build_history.json",
    "docs/history/INDEX.md",
    "CURRENT_STATE.md",
    "AI_HANDOVER.md",
)

RELAY_MARKERS = ("RELAY_ACK", "RELAY_NOTE", "RELAY_QUESTION", "RELAY_DECISION", "RELAY_CORRECTION")

# Command prefixes allowed in both forms. Matching is on the normalized
# leading tokens of the command; a `|`, `;`, `&&`, backtick or `$(` anywhere
# is rejected outright so an allowed prefix cannot smuggle a second command.
COMMON_PREFIXES = (
    "gh issue view", "gh issue list", "gh issue comment",
    "gh pr view", "gh pr checks", "gh pr list",
    "git status", "git log", "git diff", "git show", "git rev-parse",
    "git fetch", "git branch --show-current", "git branch --list",
    "git merge-base", "git cat-file", "git ls-files",
    "cat ", "head ", "tail ", "sed -n", "grep ", "rg ", "ls", "wc ", "find ",
    "python3 scripts/gov_session_transfer.py", "python scripts/gov_session_transfer.py",
    "py scripts/gov_session_transfer.py", ".venv/bin/python scripts/gov_session_transfer.py",
    "python3 scripts/build_history_index.py --check", "py scripts/build_history_index.py --check",
    ".venv/bin/python scripts/build_history_index.py --check",
    "python3 -m pytest", "py -m pytest", ".venv/bin/python -m pytest",
)
INTERACTIVE_EXTRA_PREFIXES = (
    "gh issue create", "gh pr create",
    "git checkout -b gov/po-", "git switch -c gov/po-",
    "git add ", "git commit", "git push",
    "python3 scripts/build_history_index.py", "py scripts/build_history_index.py",
    ".venv/bin/python scripts/build_history_index.py",
)
FORBIDDEN_FRAGMENTS = ("|", ";", "&&", "||", "`", "$(", ">", "<", " rm ", " mv ", " cp ", "sudo ", "chmod ", "curl ", "wget ", "ssh ", "main.py")


def _log(entry: dict) -> None:
    path = os.environ.get("NEXUS_PO_HOOK_LOG") or os.path.join(tempfile.gettempdir(), "nexus_po_hook.log")
    try:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, ensure_ascii=True) + "\n")
    except OSError:
        pass


def _current_branch(cwd: str | None) -> str:
    try:
        out = subprocess.run(["git", "branch", "--show-current"], cwd=cwd or None,
                             capture_output=True, text=True, timeout=5)
        return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def _path_is_governance(path: str, cwd: str | None) -> bool:
    if not path:
        return False
    rel = path
    if cwd and os.path.isabs(path):
        try:
            rel = os.path.relpath(path, cwd)
        except ValueError:
            return False
    rel = rel.replace(os.sep, "/")
    if rel.startswith("./"):
        rel = rel[2:]
    return any(fnmatch(rel, pat) for pat in GOVERNANCE_PATHS)


def decide(payload: dict, form: str, branch_lookup=_current_branch) -> tuple[bool, str]:
    """Return (allowed, reason). Pure except for the branch lookup callable."""
    tool = str(payload.get("tool_name", ""))
    tool_input = payload.get("tool_input") or {}
    cwd = payload.get("cwd")

    if tool in READ_TOOLS:
        return True, "read tool"
    if tool == "Agent":
        return False, "the PO role does not spawn agents except council seats from the nexus-po skill; use the council skill's documented path"
    if tool in EDIT_TOOLS:
        if form != "interactive":
            return False, "delegated PO episodes never edit files"
        target = str(tool_input.get("file_path") or tool_input.get("notebook_path") or "")
        if _path_is_governance(target, cwd):
            return True, "governance path"
        return False, "interactive PO edits are limited to the governance paths in GOV.PO.1 section 4"
    if tool == "Bash":
        cmd = " ".join(str(tool_input.get("command", "")).split())
        if not cmd:
            return False, "empty command"
        padded = f" {cmd} "
        for frag in FORBIDDEN_FRAGMENTS:
            if frag in padded:
                return False, f"forbidden shell fragment {frag.strip()!r}"
        prefixes = COMMON_PREFIXES + (INTERACTIVE_EXTRA_PREFIXES if form == "interactive" else ())
        if not any(cmd.startswith(p) for p in prefixes):
            return False, "command prefix not in the PO allowlist"
        if cmd.startswith("gh issue comment"):
            body = tool_input.get("command", "")
            if not any(m in body for m in RELAY_MARKERS) and "--body-file" not in body:
                return False, "relay comments must start with one of the five markers"
        if form == "interactive" and cmd.startswith(("git add", "git commit", "git push", "gh pr create")):
            branch = branch_lookup(cwd)
            if not branch.startswith("gov/po-"):
                return False, f"Git writes are allowed only on a gov/po-* branch (current: {branch or 'unknown'})"
        if cmd.startswith("git push") and " --force" in padded or cmd.startswith("git push -f"):
            return False, "force push is never allowed"
        return True, "allowlisted command"
    return False, f"tool {tool!r} is not available to the PO role"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--form", choices=("delegated", "interactive"), required=True)
    args = parser.parse_args(argv)
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        payload = {}
    allowed, reason = decide(payload, args.form)
    tool_input = payload.get("tool_input") or {}
    subject = str(tool_input.get("command") or tool_input.get("file_path") or "")[:200]
    _log({
        "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "form": args.form,
        "session_id": payload.get("session_id"),
        "agent_id": payload.get("agent_id"),
        "agent_type": payload.get("agent_type"),
        "tool": payload.get("tool_name"),
        "subject": subject,
        "decision": "allow" if allowed else "deny",
        "reason": reason,
    })
    decision = "allow" if allowed else "deny"
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": decision,
        "permissionDecisionReason": f"nexus-po gate ({args.form}): {reason}",
    }}))
    if not allowed:
        print(f"nexus-po gate ({args.form}) denied {payload.get('tool_name')}: {reason}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
