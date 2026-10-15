#!/usr/bin/env python3
"""Run one development task on Argon (Google Antigravity CLI, `agy`) inside a git worktree.

PO decision 2026-10-15 (docs/design/PO_DECISION_RECORD_2026_10_15_ARGON_WORKER.md): Argon takes development lanes
next to Claude so parallel work fits the usage budget. Argon writes code and commits on the worktree's branch; it never
pushes, opens PRs, deploys, touches HOST-A or devices. Claude reviews, tests and ships.

Usage:
    python3 scripts/argon_task.py --worktree ~/Codo/nexus-<lane> --brief BRIEF.md [--model gemini-4-argon-high]
        [--effort high] [--timeout 0] [--log FILE]

Guard rails: the worktree must be a git worktree named ~/Codo/nexus-* (never the main checkout); file writes and
commands are limited by the CLI's own settings (~/.gemini/antigravity-cli/settings.json allow/deny rules: no ssh,
scp, curl, wget, git push, gh, kubectl, sudo).
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_MODEL = "gemini-4-argon-high"
WORKER_RULES = """
## Standing rules for every Argon task (neXus)
- Work ONLY in this worktree; commit on its current branch with English messages ending with
  `Co-Authored-By: Gemini 4 Argon <noreply@google.com>`.
- Never push, never open a PR, never deploy, never ssh or reach HOST-A or any network device, never print secrets.
- Read AGENTS.md first; it is the constitution (masking law, raw-evidence law, migrations rules, English artefacts).
- Run the tests the task names before you finish and report the exact counts; if you cannot make something pass,
  say so plainly instead of weakening a test.
- Finish with a short report: what changed, test results, open issues, commit hashes.
"""


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--worktree", required=True, type=Path)
    p.add_argument("--brief", required=True, type=Path)
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--effort", default="high", choices=["low", "medium", "high", "xhigh", "max"])
    p.add_argument("--timeout", default="0", help="agy --print-timeout, e.g. 7200s; 0 waits for the turn")
    p.add_argument("--log", type=Path)
    p.add_argument("--add-dir", type=Path, action="append", default=[],
                   help="extra read-only context directory (e.g. a frozen reference checkout); repeatable")
    a = p.parse_args()

    wt = a.worktree.expanduser().resolve()
    if not wt.name.startswith("nexus-") or wt.parent != (Path.home() / "Codo").resolve():
        print(f"refused: {wt} is not a ~/Codo/nexus-* worktree", file=sys.stderr)
        return 2
    git_dir = subprocess.run(["git", "-C", str(wt), "rev-parse", "--git-dir"], capture_output=True, text=True)
    if git_dir.returncode != 0 or "/worktrees/" not in git_dir.stdout:
        print(f"refused: {wt} is not a linked git worktree", file=sys.stderr)
        return 2
    branch = subprocess.run(["git", "-C", str(wt), "branch", "--show-current"], capture_output=True, text=True).stdout.strip()
    if branch in ("", "main", "master"):
        print("refused: worktree must be on a feature branch", file=sys.stderr)
        return 2

    prompt = a.brief.expanduser().read_text(encoding="utf-8") + "\n" + WORKER_RULES
    log = a.log or Path("/tmp") / f"argon-{wt.name}-{int(time.time())}.log"
    cmd = ["agy", "-p", prompt, "--model", a.model, "--effort", a.effort, "--mode", "accept-edits",
           "--print-timeout", a.timeout]
    for extra in a.add_dir:
        cmd += ["--add-dir", str(extra.expanduser())]
    print(f"[argon] worktree={wt} branch={branch} model={a.model} effort={a.effort} log={log}", flush=True)
    with log.open("w", encoding="utf-8") as out:
        rc = subprocess.run(cmd, cwd=wt, stdout=out, stderr=subprocess.STDOUT).returncode
    text = log.read_text(encoding="utf-8", errors="replace")
    print(text[-4000:])
    if "no output produced" in text or "auto-denied" in text:
        print("[argon] FAILED: a permission was auto-denied; extend ~/.gemini/antigravity-cli/settings.json "
              "(and tools/argon/settings.template.json) and rerun", file=sys.stderr)
        rc = rc or 3
    print(f"[argon] exit={rc} head={subprocess.run(['git', '-C', str(wt), 'log', '--oneline', '-1'], capture_output=True, text=True).stdout.strip()}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
