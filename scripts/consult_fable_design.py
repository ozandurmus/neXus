#!/usr/bin/env python3
"""Consult Fable (`claude -p`, Fable model) for an independent design from a brief, read-only.

AGENTS.md "External model and second-opinion consultation law": the design is written by the external model through
its own CLI and saved unaltered under docs/. The model may only read the repository (Read, Grep, Glob).

Usage:
    python3 scripts/consult_fable_design.py --brief docs/design/X_BRIEF.md --output docs/design/X_FABLE.md
"""
import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--brief", required=True, type=Path)
    ap.add_argument("--output", required=True, type=Path)
    ap.add_argument("--model", default="claude-fable-5-1")
    a = ap.parse_args()
    out = (BASE_DIR / a.output).resolve()
    if not str(out).startswith(str((BASE_DIR / "docs").resolve())):
        print("refused: the output must live under docs/", file=sys.stderr)
        return 2
    prompt = ((BASE_DIR / a.brief).read_text(encoding="utf-8") + "\n\nYou are Fable, acting as the neXus product and "
              "systems architect. The repository is your working directory; read what you need. Write the whole design "
              "document as your reply text; do not create or edit files.\n")
    cmd = ["/Users/OzanDur/.local/bin/claude", "-p", prompt, "--model", a.model,
           "--tools", "Read,Grep,Glob", "--dangerously-skip-permissions"]
    print(f"[*] consulting Fable ({a.model}) via `claude -p`...", flush=True)
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, cwd=str(BASE_DIR), timeout=3600)
    except Exception as err:  # noqa: BLE001 -- report verbatim
        print(f"fable: failed: {err}", file=sys.stderr)
        return 1
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"<!-- Fable consultation {stamp}, model {a.model}; unaltered output -->\n\n" + proc.stdout
                   + (("\n\n<!-- stderr -->\n" + proc.stderr) if proc.stderr.strip() else ""), encoding="utf-8")
    print(f"[*] written: {out.relative_to(BASE_DIR)} (exit {proc.returncode})")
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
