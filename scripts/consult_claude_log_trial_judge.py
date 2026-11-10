#!/usr/bin/env python3
"""Consult a Claude model (Opus 5.5 by default, or Fable 5.2) as an external blind judge of the log-agent trial rubric (PO decision 2026-11-10).

Runs the installed host CLI `/Users/OzanDur/.local/bin/claude -p --model <model>` on the PO's subscription
(no API key), with no tools, no MCP servers, no user or project settings (so no hooks or memory), and an empty
working directory, so the judge sees only the brief and cannot read the blind key. The full, unaltered answer text is
written to the output artefact under docs/, with a header comment carrying the CLI's run metadata.

Usage:
    python3 scripts/consult_claude_log_trial_judge.py --brief docs/design/LOG_TRIAL_JUDGE_BRIEF_2026_11_10.md \
        --output docs/design/LOG_TRIAL_JUDGE_OPUS_2026_11_10.md [--model claude-fable-5-2]

The run must not bill the API: it refuses to start when ANTHROPIC_API_KEY is set in the environment, so the CLI can only
use the signed-in subscription.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
CLAUDE = "/Users/OzanDur/.local/bin/claude"
MODELS = ("claude-opus-5-5", "claude-fable-5-2")
SYSTEM = ("You are an independent judge scoring explanations written by a log-analysis agent. Follow the brief in the "
          "user message exactly. You have no tools; do not ask for any.")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--brief", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--model", default=MODELS[0], choices=MODELS)
    p.add_argument("--effort", default="high", choices=["low", "medium", "high", "xhigh", "max"])
    a = p.parse_args()

    out = (BASE_DIR / a.output).resolve() if not a.output.is_absolute() else a.output
    if not str(out).startswith(str((BASE_DIR / "docs").resolve())):
        print("refused: the judge output must live under docs/", file=sys.stderr)
        return 2
    if os.environ.get("ANTHROPIC_API_KEY"):
        print("refused: ANTHROPIC_API_KEY is set; this judge runs on the subscription only", file=sys.stderr)
        return 3
    MODEL = a.model
    prompt = (BASE_DIR / a.brief if not a.brief.is_absolute() else a.brief).read_text(encoding="utf-8")
    cmd = [CLAUDE, "-p", prompt, "--model", MODEL, "--effort", a.effort, "--tools", "", "--strict-mcp-config",
           "--setting-sources", "project", "--disable-slash-commands", "--no-session-persistence",
           "--system-prompt", SYSTEM, "--output-format", "json"]
    print(f"[*] consulting {MODEL} (effort {a.effort}) via `claude -p`, isolated working directory...", flush=True)
    with tempfile.TemporaryDirectory(prefix="judge-") as empty:
        proc = subprocess.run(cmd, cwd=empty, capture_output=True, text=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    try:
        env = json.loads(proc.stdout)
        text = env.get("result", "")
        meta = {k: env.get(k) for k in ("is_error", "duration_ms", "num_turns", "total_cost_usd", "usage", "modelUsage")}
    except json.JSONDecodeError:
        text, meta = proc.stdout, {"parse": "stdout was not the JSON envelope"}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"<!-- Claude judge consultation {stamp}, model {MODEL}, effort {a.effort}, exit {proc.returncode}; "
                   f"unaltered answer text below. CLI metadata: {json.dumps(meta)} -->\n\n" + text
                   + (("\n\n<!-- stderr -->\n" + proc.stderr) if proc.stderr.strip() else ""), encoding="utf-8")
    print(f"[*] written: {out.relative_to(BASE_DIR)} (exit {proc.returncode})")
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
