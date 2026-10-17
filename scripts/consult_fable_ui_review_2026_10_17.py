#!/usr/bin/env python3
"""Fable reviews the live neXus UI for effectiveness and visual quality (PO request 2026-10-17).

Per AGENTS.md "External model and second-opinion consultation law" the review is written by the external reviewer
through its own CLI (`claude -p`, Fable model) and saved unaltered to docs/design/.

Inputs live outside the repository (screenshots of the live estate under the masked aiview persona, taken headless
through the aiview machine session) and are passed with --inputs <dir>:
  <dir>/*.png              aiview screenshots, named by screen
  <dir>/context/*.md       earlier review records (2026-09-25 effectiveness council synthesis) for comparison

The reviewer may only read files (Read tool, input directory only). Output: one Markdown document.
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
OUT = BASE_DIR / "docs" / "design" / "UI_REVIEW_2026_10_17_FABLE.md"

PROMPT = """You are Fable, reviewing the live user interface of neXus as a senior product/UI designer who also
understands network-security operations. The Product Owner (a security expert, not a software engineer) asked for a
review of two things: effectiveness ("does each screen serve its purpose for the people who use it?") and visual
quality ("is it clear, consistent, readable and pleasant to work with?").

## What neXus is
An on-premises operations platform for an enterprise (bank) firewall estate: about 135 enrolled devices (Check Point
gateways, ClusterXL, VSX, a Multi-Domain Server; Palo Alto firewalls, HA pairs, Panorama; a few other vendors) in
clusters. It reads what the firewalls run (inventory, interfaces, routing, configuration, policy, HA state), keeps
encrypted backups, evaluates compliance, runs operational jobs only inside fixed windows (00/06/12/18 Istanbul),
analyses packet captures across several capture points (Packet Analysis), anonymises data for export (Sanitizer),
and since today collects firewall system logs (the new "Logs" tab on a device; no collection has run yet, so it shows
its empty state). Audiences: network-security deputy GM and manager (summary), team leads, operators and security
admins (daily work), compliance/audit reviewers.

## The inputs (read every file with the Read tool)
1. Screenshots in {inputs}: the live product under the `aiview` persona. Every name is a deterministic pseudonym
   (FW-APOLLO-01-M1, CLS-ALPHA-01), serials are keyed pseudonyms (SN-...), addresses are keyed pseudo-addresses.
   aiview is a read-only Viewer: disabled or refused actions are intended RBAC, but review how they are presented.
   File names give the screen: overview, devices list, a cluster, a Check Point cluster member (interfaces,
   configuration, backup, logs tabs), a Palo Alto standalone firewall, configuration, policy, compliance, backups,
   operations, administration, packet analysis, sanitizer. Most are full-page captures at 1440 px width.
2. {inputs}/context: the 2026-09-25 UI effectiveness council synthesis. Use it to say which of its recommendations
   are visible in the product now, which are not, and whether any made things worse.

## Hard constraint: no loss of capability
No recommendation may reduce what the product can do or show today: keep every data field, every click-through,
every action, every RBAC boundary, the aiview masking, and the rules "UNKNOWN is written as UNKNOWN, never 0", "zero
is neutral, never green", "status colour always paired with a word", "no composite score", "no 'outdated' version
judgement". You may move, regroup, collapse behind disclosure, re-order or re-visualise anything; if something leaves
a screen, say exactly where it now lives.

## What to write (one Markdown document, English, precise, no generic advice)
1. Verdict in five lines: what works, what does not, the three changes with the highest value.
2. Effectiveness per screen: for each screen, its purpose in one line, who uses it and when, whether the screenshot
   shows it serving that purpose (yes / partly / no, with the evidence you see), and the concrete fix.
3. Visual quality: hierarchy, density, typography, spacing, colour use, alignment, empty/refused/UNKNOWN states,
   consistency across screens. Each finding names the screen and the element and gives a concrete fix.
4. The new Logs tab: judge the empty state and the tab's place on the device page; say what its filled state should
   prioritise for an operator (summary first, then detail) without inventing data the product does not have.
5. Progress since 2026-09-25: table of the council's recommendations: done / partly / not done / regressed.
6. A prioritised list (P0/P1/P2) of changes, each with the screen, effort (S/M/L) and the capability it preserves.
Mark anything you cannot judge from the screenshots as UNKNOWN rather than guessing.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True, help="directory with the screenshots and context/ records")
    args = ap.parse_args()
    inputs = Path(args.inputs).resolve()
    shots = sorted(inputs.glob("*.png"))
    context = sorted((inputs / "context").glob("*.md"))
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
