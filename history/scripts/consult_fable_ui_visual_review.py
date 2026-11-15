#!/usr/bin/env python3
"""Fable reviews the whole neXus UI from aiview screenshots (PO request 2026-09-23).

Per AGENTS.md "External model and second-opinion consultation law" the review is written by the external
reviewer through its own CLI (`claude -p`, Fable model) and saved unaltered to docs/design/.

Inputs live outside the repository (they are screenshots of the live estate under the masked aiview persona,
and design-canvas artboards) and are passed with --inputs <dir>:
  <dir>/*.png            aiview screenshots, browser chrome cropped (no URL bar, no tabs)
  <dir>/design/*.dc.html Material 3 design-study artboards -- DESIGN REFERENCE ONLY (palette, type, components,
                         layout); any address in them was replaced with a documentation address (192.0.2.x)

The reviewer may only read files (Read tool, input directory only). Output: one Markdown document.
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
OUT = BASE_DIR / "docs" / "design" / "UI_VISUAL_REVIEW_2026_09_23_FABLE.md"

PROMPT = """You are Fable, reviewing the whole user interface of neXus as a senior product/UI designer who also
understands network-security operations and executive reporting. The Product Owner (a security expert, not a
software engineer) asked: "What would you additionally suggest, what should be shown and how -- UI design, data
readability, executive summary, visuals, and fit-for-purpose placement?"

## What neXus is
An on-premises operations platform for an enterprise (bank) firewall estate: ~105 devices (Check Point gateways,
ClusterXL, VSX, a Multi-Domain Server; Palo Alto firewalls, HA pairs, Panorama) in ~39 clusters. It reads what
the firewalls run (inventory, configuration, platform identity, HA state), keeps encrypted backups, evaluates
compliance (CIS, PCI-DSS 4.0.1, NIST 800-53, a financial baseline), and shows the exceptions an operator must act
on. Principle: SEE -> VERIFY -> TRACE -> RECOVER -> OPERATE. Audiences: network-security GMY (deputy GM), the
network-security manager, team leads, operators/security admins, compliance/audit reviewers.

## The inputs (read every file with the Read tool)
1. Screenshots in {inputs}: the live product under the `aiview` persona -- every name is a deterministic
   pseudonym (FW-TANGO-04, CLS-ROMEO-01), serials are keyed pseudonyms (SN-...), and addresses are keyed
   pseudo-addresses. Files are named by screen: 01-03 Overview (executive summary), 04 Compliance,
   05 Backups, 06 Operations (HA readiness, jobs, queue, history), 07 Configuration (list, a Palo Alto cluster,
   a Check Point VSX cluster side by side), 08 Devices/Inventory (list, cluster, members, identity),
   09 Administration tabs (several show the aiview role's refusal messages -- that is intended RBAC, but review
   how the refusal is presented).
2. Design study in {inputs}/design: Material 3 artboards (Palette, M3Components, M3Overview, M3Inventory,
   M3Configuration, M3Compliance, M3Operations, M3Administration) made earlier for this product.
   THESE ARE DESIGN REFERENCE ONLY. Take nothing from them as data: no numbers, names, statuses, addresses or
   content. Use them only for design elements -- colour palette and tokens, typography, component shapes,
   spacing, navigation and layout patterns -- where they would improve the live product.

## Hard constraint: no loss of capability
No recommendation may reduce what the product can do or show today. Specifically, keep: every data field
shown (serial, model, software version, jumbo hotfix / content versions, uptime, HA role, management address,
VS/VSX lists, cluster DIFF per setting, evidence timestamps, UNKNOWN states), every click-through to a filtered
list, every action (Collect now, Bulk Collect, Collect All, Run Fleet Backup, Export, Re-evaluate, Add/Import
device), every RBAC boundary and the aiview masking, and the rules "UNKNOWN is written as UNKNOWN, never 0",
"zero is neutral, never green", "status colour always paired with a word", "no composite score", "no
'outdated' version judgement". You may propose moving, regrouping, collapsing behind disclosure, re-ordering,
or re-visualising anything -- but if something leaves a screen, say exactly where it now lives.

## Data-visualisation rules already in force (respect them, or argue explicitly against one)
Categorical palette slots in fixed order #2a78d6, #eb6834, #1baf7a, #eda100, #e87ba4, #008300 (validated for
colour-vision deficiency); "Other" and UNKNOWN neutral; status palette good #0ca30c, warning #fab219,
serious #ec835a, critical #d03b3b, neutral #94A3B8, reserved for state; donuts <= 5 named slices + Other +
UNKNOWN; one axis per chart; legends with counts; every figure opens its filtered list.

## What to write (one Markdown document, English, precise, no generic advice)
1. Verdict in five lines: what works, what does not, the three changes with the highest value.
2. Executive summary (Overview): what a GMY/manager should see in the first screen, in what order, with what
   visual; what to demote below the fold; wording of the headline. Concrete layout (rows/columns, widths).
3. Screen by screen (Compliance, Backups, Operations, Configuration, Devices, Administration): readability
   problems you can see in the screenshots, each with a concrete fix (component, placement, visual).
4. Consistency across screens: navigation, headers, filters, chips, tables, empty/refused/UNKNOWN states,
   typography scale, density.
5. Palette and design system: compare the live palette with the Material 3 study; propose token changes as
   a table (token / current / proposed / why), including dark mode if you recommend it; check contrast.
6. Fit-for-purpose placement: how the screens should be used (morning review, NOC wall display, audit
   evidence export, incident) and what layout or export each needs.
7. A prioritised list (P0/P1/P2) of changes, each marked with the screen, effort (S/M/L), and the capability
   it preserves.
Mark anything you cannot judge from the screenshots as UNKNOWN rather than guessing.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True, help="directory with the screenshots and design/ artboards")
    args = ap.parse_args()
    inputs = Path(args.inputs).resolve()
    shots = sorted(inputs.glob("*.png"))
    design = sorted((inputs / "design").glob("*.dc.html"))
    if not shots:
        print(f"fable: no screenshots in {inputs}", file=sys.stderr)
        return 2
    listing = "\n".join(f"- {p}" for p in shots + design)
    prompt = PROMPT.format(inputs=inputs) + "\n## Files\n" + listing + "\n"
    cmd = ["/Users/OzanDur/.local/bin/claude", "-p", prompt, "--model", "claude-fable-5-1",
           "--tools", "Read", "--add-dir", str(inputs), "--dangerously-skip-permissions"]
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, cwd=str(inputs), check=True, timeout=1500)
    except subprocess.CalledProcessError as err:
        print(f"fable: failed (exit {err.returncode}): {err.stderr[-800:]}", file=sys.stderr)
        return 1
    except Exception as err:  # noqa: BLE001 -- report verbatim
        print(f"fable: failed: {err}", file=sys.stderr)
        return 1
    OUT.write_text(proc.stdout.strip() + "\n", encoding="utf-8")
    print(f"fable: ok -> {OUT} ({OUT.stat().st_size} bytes, {len(shots)} screenshots, {len(design)} artboards)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
