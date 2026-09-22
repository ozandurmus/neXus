#!/usr/bin/env python3
"""Astra (Codex) reviews the whole neXus UI from aiview screenshots, independently of Fable (PO request 2026-09-23).

Per AGENTS.md "External model and second-opinion consultation law" the review is written by the external reviewer
through its own CLI (`codex exec`, model gpt-6-sol per the Product Owner) and saved unaltered to docs/design/.
Astra does NOT see Fable's review: the two answers must be independent; Fable compares them afterwards
(scripts/consult_fable_resume_on_astra_review.py).

Inputs live outside the repository (screenshots of the live estate under the masked aiview persona, browser chrome
cropped): --inputs <dir> holding *.png.
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
OUT = BASE_DIR / "docs" / "design" / "UI_VISUAL_REVIEW_2026_09_23_ASTRA.md"
MODEL = "gpt-6-sol"

PROMPT = """You are Astra, reviewing the whole user interface of neXus as a senior product/UI designer who also
understands network-security operations and executive reporting. The Product Owner (a security expert, not a
software engineer) asks: "Is each screen fit for its purpose and effective for its user? What should be shown, and
how -- UI design, data readability, executive summary, visuals, fit-for-purpose placement?" Features that merely
work are not the goal; effectiveness is.

neXus: an on-premises operations platform for a bank's firewall estate (~105 devices: Check Point gateways,
ClusterXL, VSX, a Multi-Domain Server; Palo Alto firewalls, HA pairs, Panorama; ~39 clusters). It reads what the
firewalls run (inventory, configuration, platform identity, HA state), keeps encrypted backups, evaluates compliance
(CIS, PCI-DSS 4.0.1, NIST 800-53, a financial baseline), and shows the exceptions to act on.
Principle: SEE -> VERIFY -> TRACE -> RECOVER -> OPERATE. Audiences: network-security deputy GM, manager, team leads,
operators / security admins, compliance and audit reviewers.

The attached screenshots are the live product after a redesign, under the `aiview` persona: every name is a
deterministic pseudonym (FW-TANGO-04, CLS-ROMEO-01), serials are keyed pseudonyms, addresses are keyed
pseudo-addresses. Their file names say which screen each one is.

Hard constraint for every recommendation: no loss of capability (every field, filter, click-through, action, RBAC
boundary and the masking stay; you may move, regroup, collapse or re-visualise -- say where a thing goes). Rules in
force: UNKNOWN is written as UNKNOWN, never 0; zero is neutral, never green; status colour only for state words,
always with the word; no composite score; no "outdated" version judgement.

Write ONE Markdown document, English, precise, no generic advice:
1. Verdict in five lines: is the product now fit for purpose; the three highest-value changes left.
2. Per audience (deputy GM, manager, operator, auditor): what their first minute on the product looks like today,
   what they still cannot answer, what to change.
3. Screen by screen: what works, what is still ineffective, concrete fixes (component, placement, wording, visual).
4. Consistency and design system (tokens, typography, density, states, dark mode if shown).
5. A prioritised list (P0/P1/P2) with screen, effort (S/M/L) and the capability each change preserves.
Mark anything you cannot judge from the screenshots as UNKNOWN rather than guessing.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True, help="directory with the aiview screenshots (*.png)")
    args = ap.parse_args()
    inputs = Path(args.inputs).resolve()
    shots = sorted(inputs.glob("*.png"))
    if not shots:
        print(f"astra: no screenshots in {inputs}", file=sys.stderr)
        return 2
    listing = "\n".join(f"- {p.name}" for p in shots)
    prompt = PROMPT + "\n## Screenshot files\n" + listing + "\n"
    out_tmp = inputs / "astra_review.md"
    cmd = ["codex", "exec", "-m", MODEL, "-c", "model_reasoning_effort=high", "-s", "read-only",
           "--skip-git-repo-check", "-C", str(inputs), "-o", str(out_tmp)]
    for p in shots:
        cmd += ["-i", str(p)]
    cmd.append("-")
    try:
        subprocess.run(cmd, input=prompt, text=True, capture_output=True, check=True, timeout=2400)
    except subprocess.CalledProcessError as err:
        print(f"astra: failed (exit {err.returncode}): {err.stderr[-800:]}", file=sys.stderr)
        return 1
    except Exception as err:  # noqa: BLE001 -- report verbatim
        print(f"astra: failed: {err}", file=sys.stderr)
        return 1
    OUT.write_text(out_tmp.read_text(encoding="utf-8").strip() + "\n", encoding="utf-8")
    print(f"astra: ok -> {OUT} ({OUT.stat().st_size} bytes, {len(shots)} screenshots, model {MODEL})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
