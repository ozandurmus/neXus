#!/usr/bin/env python3
"""Fable checks the live result of the 2026-10-18 UI passes (D1, D2, phase 2) against its own product review: before/after.

Per AGENTS.md "External model and second-opinion consultation law" the review is written by the external reviewer
through its own CLI (`claude -p`, Fable model) and saved unaltered to docs/design/.

Inputs live outside the repository (screenshots of the live estate under the masked aiview persona, taken headless
through the aiview machine session) and are passed with --inputs <dir>:
  <dir>/before/*.png       aiview screenshots of this morning's build (before D1, D2 and phase 2)
  <dir>/after/*.png        aiview screenshots after those releases, same screens where they still exist
  <dir>/context/*          the product review and the Fable part 2 review

The reviewer may only read files (Read tool, input directory only). Output: one Markdown document.
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
OUT = BASE_DIR / "docs" / "design" / "UI_AFTER_REVIEW_2026_10_18_FABLE.md"

PROMPT = """You are Fable, product architect and senior product designer for neXus. This morning you wrote a holistic
product review ({inputs}/context/UI_PRODUCT_REVIEW_2026_10_18_FABLE.md) and a screen review (part 2). Since then three
releases implemented much of it: D1 (Logs tab summary, device header read ages, identity badge bound to the real
key/certificate comparison, Devices fleet summary, VSYS grouping, tree members), D2 (plain labels, one timestamp,
colour rules, Overview HA row, Compliance triage, Operations HA, Administration registry, rail pins, empty states) and
phase 2 (five-verb rail with Tools, object header state pills, Identity tab audit grid, Jobs state pills replacing the
Queue and History tabs, Policy tab groups, screen headlines, tool contracts, shareable control/job links).
The Product Owner wants to know, from the live screens, whether the product now reads as intended.

## Inputs
- {inputs}/before/*.png: this morning's build; {inputs}/after/*.png: the live build now, masked aiview persona
  (read-only Viewer). Same file names where the screen still exists; some tabs no longer exist by design (Operations
  Queue/History, the Cluster members tab on a member page), so a missing or error capture of those is expected.
- {inputs}/context: your product review and part 2.

## Hard constraints (unchanged)
No loss of capability; UNKNOWN written as UNKNOWN, never 0; zero neutral, never green; status colour always with a
word; no composite score; no "outdated" version judgement; aiview masking stays.

## What to write (one Markdown document, English, concrete)
1. Verdict in five lines: does the product now read as one ledger connected at the object? What improved most, what
   is still the biggest gap.
2. Roadmap check: a table of every Phase 1 and Phase 2 item of your product review: Done as designed / Done
   differently (say how, and whether that is acceptable) / Partly / Not done / Regressed, with the screenshot evidence.
3. Regressions and new defects introduced by these releases (layout breaks, clipped or overlapping elements, wrong
   colours, inconsistent wording, broken empty states, lost information). Each with screen, element, severity P0/P1/P2
   and a concrete fix.
4. The five-second test per audience (deputy GM, team lead, operator, auditor): can each now get their facts above the
   fold? Answer per audience with evidence.
5. Next work, prioritised (P0/P1/P2, effort S/M/L): the remaining Phase 2 and Phase 3 items plus fixes from section 3.
Mark anything you cannot judge from the screenshots as UNKNOWN.

Output: write the whole document as your reply text. Do not create artifacts, documents or files with any tool.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True, help="directory with the screenshots and context/ records")
    args = ap.parse_args()
    inputs = Path(args.inputs).resolve()
    shots = sorted(inputs.glob("before/*.png")) + sorted(inputs.glob("after/*.png"))
    context = sorted(p for p in (inputs / "context").iterdir() if p.suffix in (".md", ".html"))
    if not shots:
        print(f"fable: no screenshots in {inputs}", file=sys.stderr)
        return 2
    listing = "\n".join(f"- {p}" for p in shots + context)
    prompt = PROMPT.format(inputs=inputs) + "\n## Files\n" + listing + "\n"
    cmd = ["/Users/OzanDur/.local/bin/claude", "-p", prompt, "--model", "claude-fable-5-1",
           "--tools", "Read", "--add-dir", str(inputs), "--dangerously-skip-permissions"]
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, cwd=str(inputs), check=True, timeout=2400)
    except subprocess.CalledProcessError as err:
        print(f"fable: failed (exit {err.returncode}): {err.stderr[-800:]}", file=sys.stderr)
        return 1
    except Exception as err:  # noqa: BLE001 -- report verbatim
        print(f"fable: failed: {err}", file=sys.stderr)
        return 1
    OUT.write_text(proc.stdout.strip() + "\n", encoding="utf-8")
    print(f"fable: ok -> {OUT} ({OUT.stat().st_size} bytes, {len(shots)} screenshots, {len(context)} context files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
