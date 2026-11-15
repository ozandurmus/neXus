#!/usr/bin/env python3
"""UI effectiveness council (PO request 2026-09-25): Astra convenes the council, Fable reviews its verdict.

Per AGENTS.md "External model and second-opinion consultation law": Astra runs through `codex exec`
(gpt-6-astra), Fable through `claude -p` (claude-fable-5-1); each answer is written unaltered to docs/design/.
Screenshots are the live product under the masked aiview persona (addresses and account names additionally
replaced in-page before capture, browser chrome cropped); they live outside the repository: --inputs <dir>.

Agenda (PO): 1. Is the UI effective (Devices vs Config duplication; merge inventory + configuration into one device
screen; compliance and backup planning fed from the same device set; Overview key facts)? 2. Approve or reject the
device onboarding flow (docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md). 3. The pending Cisco ASA backup decision
(docs/design/CISCO_ASA_CONTRACT.md "Backbox comparison").
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
ASTRA_OUT = BASE_DIR / "docs" / "design" / "UI_EFFECTIVENESS_COUNCIL_2026_09_25_ASTRA.md"
FABLE_OUT = BASE_DIR / "docs" / "design" / "UI_EFFECTIVENESS_COUNCIL_2026_09_25_FABLE.md"

CONTEXT = """## neXus in one paragraph
On-premises operations platform for a bank's firewall estate: 112 devices (63 Check Point gateways/ClusterXL/VSX and
one Multi-Domain Server; 40 Palo Alto incl. Panorama; Infoblox Grid Manager, Radware Cyber Controller and DefensePro,
Symantec Management Center; Cisco ASA being added), 40 clusters. It reads what devices run (inventory: interfaces,
routes, HA role, virtual systems; platform identity), their configuration (sanitized, per section, cluster member
DIFF), keeps encrypted backups, evaluates compliance (CIS, PCI-DSS 4.0.1, NIST 800-53, financial baseline) and runs
every read as an audited job. Principle: SEE -> VERIFY -> TRACE -> RECOVER -> OPERATE. Users: the network-security
deputy GM, the manager, team leads, firewall operators, auditors. The Product Owner (a security expert, not a
software engineer) says the current UI is "too technical, too mixed" and he does not like it.

## Screens (screenshot files, aiview persona; names are pseudonyms, addresses 192.0.2.x)
01-03 Overview (top, middle, bottom) | 05-08 Devices: a cluster selected -> Interfaces, Routing, Cluster members,
Identity & provenance | 09 Devices: a standalone gateway | 10 Config: list | 11-12 Config: the same cluster -> members
table and the member DIFF by section | 13-14 Compliance (summary, controls) | 15-16 Backups (fleet table, bottom
cards) | 17-18 Operations (HA & readiness, jobs) | 19 Administration -> Device management (a third device list) |
20 Add device dialog.
Observed by the engineering session while capturing (verify against the images): the device tree (manager ->
domain -> cluster -> member) is rendered twice, in Devices and in Config, each with its own detail tabs; Admin has a
third flat device list; Backups a fourth (fleet table); the Operations jobs table showed real names under aiview
(a masking gap, masked here in-page); a Management Center row in Backups reads "not enrolled" with a Delete action."""

ASTRA_PROMPT = """You are Astra (gpt-6-astra), convening the neXus council. First read, in this repository:
.claude/skills/nexus-decision-council/SKILL.md (the council procedure and seat list), roles/REVIEWER.md (the three
tables every seat answers with), PROJECT_VISION.md "Product decision lenses", docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md
and docs/design/CISCO_ASA_CONTRACT.md. You cannot launch sub-agents here: run the council yourself, one clearly
separated section per seat, each seat speaking only from its own lens, then the synthesis. Do not simulate agreement:
keep real dissent visible and unresolved where it is. Reasoning: medium-high.

Seats (the Product Owner asked for a UI designer to be seated):
1. UI/UX Product Designer (enterprise operations consoles, Material 3, information architecture) -- chair for agenda 1.
2. Network/Security Manager (daily use by a team; what must be one click away).
3. Firewall operator (Check Point / Palo Alto engineer): finding a device after adding it, reading its state.
4. Configuration Management Specialist + Data/Inventory Architect (one seat): inventory vs configuration planes --
   AGENTS.md says they are separate product planes; may they share one screen without collapsing the data?
5. Security Reviewer: privacy/masking on screen, and agenda 3 (a device write).
6. Business/Executive stakeholder (deputy GM, audit/regulator reporting): Overview.

""" + CONTEXT + """

## Agenda
1. UI effectiveness (look at every screenshot). Questions from the Product Owner, verbatim in meaning:
   - Is this UI design effective? Is everything a user looks for in the right place? Am I showing duplicate data?
   - Devices and Config show the same device twice with different sub-menus. What if inventory and configuration
     merged into ONE device screen? It would remove needless menu use, and after adding a device the user would see
     where it sits, its inventory and its configuration on one screen with no duplication. Compliance would be fed
     from all this data, and backup planning done from the Backups menu over the same devices.
   - Is the interface clean and readable? Design elements, menus, separation, and are the critical "pill" facts
     (hap bilgiler) shown correctly -- on the Overview and on the combined configuration + inventory screen?
   Deliver: a verdict per question; a proposed information architecture (navigation items, what each screen owns,
   what is removed or merged); the combined device screen's layout top to bottom (header facts, tabs or sections,
   where cluster DIFF lives, where backup and compliance status of that device appear); the Overview's key facts
   (at most 8, each with its definition); a list of concrete defects seen in the screenshots (screen + element +
   why); and a phased plan (first release small).
2. Approval of the device onboarding flow (docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md, already built and
   deployed): each seat consents or dissents with reasons; list changes required before the PO approves it, and how
   the flow should appear in the proposed combined device screen.
3. Pending decision, Cisco ASA backup (docs/design/CISCO_ASA_CONTRACT.md "Backbox comparison"): keep the read-only
   text backup, or add the ASA's own `backup /noconfirm location disk0:` archive (certificates, WebVPN, AnyConnect)
   which writes to the device's flash and needs `ssh scopy enable`, pulled by SCP and then deleted. Seat views,
   conditions for either option, and a recommendation.

## Output (Markdown, English)
Per seat: the three tables from roles/REVIEWER.md (consent, dissent, questions for the Product Owner) covering all
three agenda items. Then "Council synthesis": supported consensus, unresolved dissent with its holder, and the
decisions the Product Owner must take. Then the deliverables asked in agenda 1. Be concrete and short; cite the
screenshot file for every UI claim; mark anything you cannot see as UNKNOWN.
"""

FABLE_PROMPT = """You are Fable (claude-fable-5-1), the second, independent reviewer. Astra (a different model family,
gpt-6-astra) convened the neXus council on the Product Owner's questions; its full output is the file {astra}.
Read it, read every screenshot in {inputs} (Read tool), and read docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md and
docs/design/CISCO_ASA_CONTRACT.md.

""" + CONTEXT + """

Your job: review Astra's council, not repeat it.
1. Where is Astra right, where wrong or over-engineered, what did it miss? Check each of its UI claims against the
   screenshots and say which you could confirm.
2. Your own answer to the PO's core question: should inventory and configuration become ONE device screen? What
   does that screen look like (top to bottom), and what happens to Devices / Config / Admin device list / Backups
   fleet table in navigation? Keep AGENTS.md's rule that inventory and configuration stay separate data planes.
3. Make the UI less technical and less mixed: concrete rewrites of labels, the Overview's first screenful, the
   header facts of a device, empty/UNKNOWN states. At most 10 changes, ranked by effect on the PO's complaint.
4. Onboarding flow: approve / approve with changes / reject, with reasons.
5. Cisco ASA backup: your recommendation and its conditions.
6. Where you and Astra disagree, a table: topic, Astra, Fable, what evidence would settle it.
Markdown, English, concise; cite screenshot files.
"""


def run(cmd, **kw):
    return subprocess.run(cmd, text=True, capture_output=True, cwd=str(BASE_DIR), timeout=2400, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True)
    ap.add_argument("--skip-astra", action="store_true")
    a = ap.parse_args()
    inputs = Path(a.inputs).resolve()
    images = sorted(str(p) for p in inputs.glob("*.png"))
    if not images:
        print("no screenshots in", inputs)
        return 2
    if not a.skip_astra:
        cmd = ["codex", "exec", "-m", "gpt-6-astra", "-c", "model_reasoning_effort=high", "--sandbox", "read-only",
               "-o", str(ASTRA_OUT)]
        for img in images:
            cmd += ["-i", img]
        cmd += ["--", ASTRA_PROMPT]
        p = run(cmd)
        ok = p.returncode == 0 and ASTRA_OUT.exists() and ASTRA_OUT.stat().st_size > 0
        print(f"astra: {'ok' if ok else 'failed rc=' + str(p.returncode)} -> {ASTRA_OUT}")
        if not ok:
            print(p.stderr[-3000:])
            return 1
    prompt = FABLE_PROMPT.format(astra=ASTRA_OUT, inputs=inputs)
    p = run(["/Users/OzanDur/.local/bin/claude", "-p", prompt, "--model", "claude-fable-5-1", "--tools", "Read",
             "--add-dir", str(inputs), "--dangerously-skip-permissions"])
    if p.returncode != 0 or not p.stdout.strip():
        print(f"fable: failed rc={p.returncode}\n{p.stderr[-3000:]}")
        return 1
    FABLE_OUT.write_text(p.stdout.strip() + "\n", encoding="utf-8")
    print(f"fable: ok -> {FABLE_OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
