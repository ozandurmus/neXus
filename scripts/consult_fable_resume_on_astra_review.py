#!/usr/bin/env python3
"""The Fable session that wrote UI_VISUAL_REVIEW_2026_09_23_FABLE.md comments on Astra's independent review
(PO request 2026-09-23: "ask the Fable session that did the earlier work for its comments").

Per AGENTS.md "External model and second-opinion consultation law": the same external session is resumed through
its own CLI (`claude -p --resume <session-id>`, run from the directory that session was started in, so its
transcript is found) and its answer is saved unaltered to docs/design/.

  scripts/consult_fable_resume_on_astra_review.py --session <id> --cwd <dir the Fable review ran in> \
      --after <dir with the post-redesign screenshots, inside --cwd>
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
FABLE_REVIEW = BASE_DIR / "docs" / "design" / "UI_VISUAL_REVIEW_2026_09_23_FABLE.md"
ASTRA_REVIEW = BASE_DIR / "docs" / "design" / "UI_VISUAL_REVIEW_2026_09_23_ASTRA.md"
OUT = BASE_DIR / "docs" / "design" / "UI_VISUAL_REVIEW_2026_09_23_FABLE_ON_ASTRA.md"

PROMPT = """This continues your review of the neXus UI earlier today (your output is quoted at the end for
reference). Since then the Product Owner approved all 27 of your items and they were implemented; the screenshots
of the product after the redesign are in {after} (read every one with the Read tool; same aiview masking as before).

An independent reviewer, Astra, has now reviewed the redesigned product without seeing your review. Its full
review follows. The Product Owner wants to close the improvement round with your comments:

1. Implementation check: for each of your 27 items, from the new screenshots -- done as you meant it, done
   differently (say how), or not visible / not done. Be concrete.
2. Astra's review: where you agree, where you disagree and why, and what Astra saw that you missed.
3. The final list: the changes that are still worth making, merged from both reviews, prioritised (P0/P1/P2), each
   with the screen, effort (S/M/L) and the capability it preserves. Keep the hard constraint: no loss of capability.
4. One paragraph: is the product now fit for its purpose for the deputy GM, the manager, the operator and the
   auditor -- or what still stands between it and that.
Mark anything you cannot judge as UNKNOWN. English, precise, one Markdown document.

## Astra's review (unaltered)

{astra}

## Your earlier review (for reference)

{fable}
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", required=True)
    ap.add_argument("--cwd", required=True, help="the directory the original Fable review ran in")
    ap.add_argument("--after", required=True, help="post-redesign screenshots, inside --cwd")
    args = ap.parse_args()
    cwd = Path(args.cwd).resolve()
    after = Path(args.after).resolve()
    prompt = PROMPT.format(after=after, astra=ASTRA_REVIEW.read_text(encoding="utf-8"),
                           fable=FABLE_REVIEW.read_text(encoding="utf-8"))
    cmd = ["/Users/OzanDur/.local/bin/claude", "-p", prompt, "--resume", args.session, "--model", "claude-fable-5-1",
           "--tools", "Read", "--add-dir", str(cwd), "--dangerously-skip-permissions"]
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, cwd=str(cwd), check=True, timeout=2400)
    except subprocess.CalledProcessError as err:
        print(f"fable: failed (exit {err.returncode}): {err.stderr[-800:]}", file=sys.stderr)
        return 1
    except Exception as err:  # noqa: BLE001 -- report verbatim
        print(f"fable: failed: {err}", file=sys.stderr)
        return 1
    OUT.write_text(proc.stdout.strip() + "\n", encoding="utf-8")
    print(f"fable: ok -> {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
