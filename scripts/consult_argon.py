#!/usr/bin/env python3
"""Consult Argon (Google Antigravity CLI, `agy`) as an external reviewer, read-only.

AGENTS.md "External model and second-opinion consultation law": the consultation runs through the installed external
CLI and its full, unaltered output is written to a repository artefact.

Usage:
    python3 scripts/consult_argon.py --brief BRIEF.md --output docs/design/ARGON_<TOPIC>_REVIEW.md
        [--model gemini-4-argon-high] [--effort high]
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--brief", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--model", default="gemini-4-argon-high")
    p.add_argument("--effort", default="high", choices=["low", "medium", "high", "xhigh", "max"])
    a = p.parse_args()

    out = (BASE_DIR / a.output).resolve() if not a.output.is_absolute() else a.output
    if not str(out).startswith(str((BASE_DIR / "docs").resolve())):
        print("refused: the review output must live under docs/", file=sys.stderr)
        return 2
    prompt = a.brief.read_text(encoding="utf-8")
    cmd = ["agy", "-p", prompt, "--model", a.model, "--effort", a.effort, "--mode", "plan", "--print-timeout", "0"]
    print(f"[*] consulting Argon ({a.model}, effort {a.effort}) via `agy -p --mode plan`...", flush=True)
    result = subprocess.run(cmd, cwd=BASE_DIR, capture_output=True, text=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"<!-- Argon consultation {stamp}, model {a.model}, effort {a.effort}; unaltered output -->\n\n"
                   + result.stdout + (("\n\n<!-- stderr -->\n" + result.stderr) if result.stderr.strip() else ""),
                   encoding="utf-8")
    print(f"[*] written: {out.relative_to(BASE_DIR)} (exit {result.returncode})")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
