#!/usr/bin/env python3
"""Fable evaluates neXus as a product architect and designer: vision, IA, density, summaries (PO request 2026-10-18).

Per AGENTS.md "External model and second-opinion consultation law" the review is written by the external reviewer
through its own CLI (`claude -p`, Fable model) and saved unaltered to docs/design/.

Inputs live outside the repository (screenshots of the live estate under the masked aiview persona, taken headless
through the aiview machine session) and are passed with --inputs <dir>:
  <dir>/*.png              aiview screenshots, named by screen
  <dir>/context/*            earlier review records (council synthesis, Argon, Fable parts 1 and 2)

The reviewer may only read files (Read tool, input directory only). Output: one Markdown document.
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
OUT = BASE_DIR / "docs" / "design" / "UI_PRODUCT_REVIEW_2026_10_18_FABLE.md"

PROMPT = """You are Fable, acting as a product architect and a senior product designer for neXus. The Product Owner (a security
expert, not a software engineer) asks for a holistic evaluation on top of the earlier screen-level reviews (Argon
2026-10-17, your own parts 1 and 2 of 2026-10-18, all in {inputs}/context). Do NOT repeat screen-level defects already
listed there; reference their IDs when relevant. Think about the product as a whole.

## What neXus is
An on-premises operations and assurance platform for an enterprise (bank) firewall estate: about 132 enrolled devices
(Check Point gateways, ClusterXL, VSX, an MDS; Palo Alto firewalls, HA pairs, Panorama; Fortinet, Cisco ASA, Blue Coat,
Radware, Infoblox) in clusters. Planes: inventory/interfaces/routing (SEE), configuration and compliance (VERIFY),
change and policy history (TRACE), encrypted backups and restore (RECOVER), job windows, HA readiness/failover, system
logs, packet analysis, data sanitizer (OPERATE). Principle: SEE -> VERIFY -> TRACE -> RECOVER -> OPERATE. Audiences:
deputy GM and network-security manager (posture, risk, decisions), team leads (shift triage), operators and security
admins (daily work, incidents), auditors (evidence).

## Inputs
- Screenshots in {inputs}: the live product under the masked aiview persona (read-only Viewer), taken today after
  several fix releases. File names give the screen.
- {inputs}/context: the earlier reviews and the 2026-09-25 effectiveness council synthesis.

## Hard constraints
No recommendation may reduce what the product can do or show today; keep every field, action, RBAC boundary and the
aiview masking, and the rules "UNKNOWN is written as UNKNOWN, never 0", "zero is neutral, never green", "status
colour always paired with a word", "no composite score", "no 'outdated' version judgement". You may restructure,
regroup, merge or split screens and menus; say where everything goes.

## What to write (one Markdown document, English, concrete, no generic advice)
1. Product vision in one paragraph: what neXus should feel like to each audience, and whether the current product
   reads that way. Name the single biggest gap between vision and reality.
2. Information architecture: evaluate the menus (left rail, drawer, top bar, tabs) against the SEE -> VERIFY -> TRACE
   -> RECOVER -> OPERATE principle and the audiences. Propose a target menu tree (top level, second level, tabs), with
   what moves where and why. Say which screens are overloaded, which are thin, and which duplicate each other.
3. Density and complexity: where the product shows too much at once, where too little; a rule of thumb per screen
   type (summary screen, list screen, object page, tool page) for how much belongs above the fold.
4. Summary and "pill" information: what each audience should see in 5 seconds (the "hap bilgi" the PO asks for);
   propose the headline facts per screen and per object page, with the exact wording style; how an object page header
   should summarize health, freshness, identity and risk without a composite score.
5. Navigation and workflow: the three most common journeys per audience (e.g. morning posture check, incident on one
   device, audit evidence), how many clicks they take today, and how the target IA shortens them.
6. Visual language: a short design-system direction (type scale, density modes, colour roles, components to unify)
   that would make the product feel like one product.
7. A phased roadmap (Phase 1 quick wins, Phase 2 structural, Phase 3 polish), each item with screen(s), effort S/M/L
   and the capability it preserves.
Mark anything you cannot judge from the screenshots as UNKNOWN.

Output: write the whole document as your reply text. Do not create artifacts, documents or files with any tool.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True, help="directory with the screenshots and context/ records")
    args = ap.parse_args()
    inputs = Path(args.inputs).resolve()
    shots = sorted(inputs.glob("*.png"))
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
