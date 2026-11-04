#!/usr/bin/env python3
"""Fable gives a second opinion on selected items of Argon's 2026-11-05 UI review (PO request 2026-11-05).

Per AGENTS.md "External model and second-opinion consultation law" the review is written by the external
reviewer through its own CLI (`claude -p`, Fable model) and saved unaltered to docs/design/.

Flow chosen by the PO: Argon reviewed all 42 aiview screenshots (docs/design/UI_REVIEW_2026_11_05_ARGON.md);
the engineer selected the recommendations that are contested or high-impact; Fable confirms, rejects or amends
them on a representative subset of screenshots (cost-bounded). Inputs live outside the repository (masked aiview
screenshots of the live estate) and are passed with --inputs <dir> (*.jpg).
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
OUT = BASE_DIR / "docs" / "design" / "UI_REVIEW_2026_11_05_FABLE.md"
ARGON = BASE_DIR / "docs" / "design" / "UI_REVIEW_2026_11_05_ARGON.md"

PROMPT = """You are Fable, a senior product and UI designer who also understands enterprise firewall operations and
executive reporting. You are giving an independent SECOND OPINION on another reviewer's (Argon's) UI review of neXus,
an on-premises operations platform for a bank's firewall estate (Check Point, Palo Alto and others; about 130 devices,
45 clusters). Principle: SEE -> VERIFY -> TRACE -> RECOVER -> OPERATE.

The Product Owner (a security expert, not a software engineer) asked: "The UI is clean, but there is a lot of data. Can it
be simplified and made fit for purpose? Look with an executive's eyes and an expert's eyes. What should be added,
removed, moved?"

Argon's full review is at {argon}. Read it (sections 2, 3 and 4 at least). Screenshots (aiview, masked names) are in
{inputs}; open every one listed below. Notes: development mode has scheduled collection off, so data is stale on purpose;
the Delivery plan page (32) is a product-planning view the PO uses; aiview is the PO's own masked inspection persona.

Give your verdict on THESE items (agree / agree with change / disagree, one short paragraph each, citing screenshots):
- R-15 + section 3: the 6-element executive home replacing Overview. Is the wireframe right? What would you change?
- R-03: move "Major configuration changes" out of Backups. (Context: the PO asked for backup deviations on Backups in Oct.)
- R-09: remove Delivery plan from Administration (PO uses it).
- R-10: hide admin-only pages from aiview (aiview is how the PO and agents inspect the UI).
- R-16: Policy from 7 tabs to 4.
- R-20: merge Path analysis and Policy > Lookup into one "Path & Lookup" under a TRACE heading.
- R-21 and R-22: move Diagnostics/Offline analysis to Tools; collapse Administration from 14 pages to 4.
- R-18: replace donut charts on Devices fleet summary with ranked bars.
- Section 2.2 as a whole: the target information architecture.

Then:
1. What Argon MISSED: up to 8 additional recommendations (executive lens and expert lens), each with the screenshot.
2. Your own top 10 in priority order, mixing Argon's IDs and yours, with impact and effort (S/M/L).
Rules: never write raw IPs or real hostnames; masked labels only. Mark anything not judgeable from a static screenshot
as UNKNOWN. Be concise: at most about 1,800 words.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True, help="directory with the selected screenshots (*.jpg)")
    args = ap.parse_args()
    inputs = Path(args.inputs).resolve()
    shots = sorted(inputs.glob("*.jpg"))
    if not shots or not ARGON.exists():
        print(f"fable: missing screenshots in {inputs} or {ARGON}", file=sys.stderr)
        return 2
    listing = "\n".join(f"- {p}" for p in shots)
    prompt = PROMPT.format(inputs=inputs, argon=ARGON) + "\n## Files\n" + listing + "\n"
    cmd = ["/Users/OzanDur/.local/bin/claude", "-p", prompt, "--model", "claude-fable-5-1",
           "--tools", "Read", "--add-dir", str(inputs), "--add-dir", str(ARGON.parent), "--dangerously-skip-permissions"]
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, cwd=str(inputs), check=True, timeout=1800)
    except subprocess.CalledProcessError as err:
        print(f"fable: failed (exit {err.returncode}): {err.stderr[-800:]}", file=sys.stderr)
        return 1
    except Exception as err:  # noqa: BLE001 -- report verbatim
        print(f"fable: failed: {err}", file=sys.stderr)
        return 1
    OUT.write_text(proc.stdout.strip() + "\n", encoding="utf-8")
    print(f"fable: ok -> {OUT} ({OUT.stat().st_size} bytes, {len(shots)} screenshots)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
