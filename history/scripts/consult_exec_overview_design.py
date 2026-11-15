#!/usr/bin/env python3
"""Executive Overview design (PO request 2026-09-25): Astra designs, Fable reviews and finalises.

AGENTS.md external-consultation law: Astra via `codex exec` (gpt-6-astra), Fable via `claude -p` (claude-fable-5-1);
each answer written unaltered to docs/design/. Screenshots (aiview, masked) live outside the repository: --inputs.
"""
import argparse
import subprocess
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
ASTRA_OUT = BASE / "docs" / "design" / "EXEC_OVERVIEW_DESIGN_2026_09_25_ASTRA.md"
FABLE_OUT = BASE / "docs" / "design" / "EXEC_OVERVIEW_DESIGN_2026_09_25_FABLE.md"

BRIEF = """## The ask (Product Owner, a security expert; verbatim in meaning)
"This is the most striking screen; it must be live and truthful. Do not just draw charts: design it the way sector
leaders do, the way a deputy general manager (GMY), a director or an executive would want to see this estate, with the
charts they would want." He rejected (a) the old Overview (text triage, six count tiles, raw job-failure strings) and
(b) a first dashboard attempt (four ring gauges, framework bars, top failed checks, change donut, cluster-diff bars,
patch bars). Screenshots of both are in {inputs} (old_* and 0*_overview_dashboard_*). Known defects of attempt (b):
compliance gauge shows "292 / 1000"; one check appears twice (same title, two vendors); "patch currency 7.5%" reads as
alarming because "newest build in the fleet" is not the vendor-recommended build.

## neXus
On-premises operations platform for a bank's firewall estate: 112 devices (63 Check Point incl. ClusterXL/VSX and a
Multi-Domain Server, 40 Palo Alto incl. Panorama, Infoblox, Radware DefensePro + Cyber Controller, Symantec ProxySG via
Management Center, Cisco ASA and Fortinet being added), 40 clusters. Principle SEE -> VERIFY -> TRACE -> RECOVER ->
OPERATE. Audience of this screen: deputy GM (network security), director, manager; also shown on a wall display.

## Data neXus has TODAY (design only with these; anything else must be marked NEW DATA with its collection cost)
- Devices: vendor, role, model, software version (CP major + jumbo take; PAN version; content versions), HA role,
  cluster membership, virtual systems, onboarding state, enrollment, backup-target flag, uptime text, serial (masked).
- Inventory: interfaces, routes, per device; last successful read time per device (age buckets <24h/24-72h/>72h/never).
- Configuration: per-device sanitized configuration by section; "changed since previous read" per device with the
  number of sections; cluster member differences per cluster (setting count, sections); installed policy name and
  install time (CP), read nightly.
- Compliance: controls (CIS, PCI-DSS 4.0.1, NIST 800-53, financial baseline) x firewalls: pass/fail/data-unavailable,
  severity, affected devices; per framework pass/fail/unavailable totals; assured % (29.2), evidence coverage (83.3%),
  172 critical failing checks, 404 data gaps.
- Backups: per device latest archive time, size, validation level, deviation (changed/first/unchanged), 110 targets,
  98 with an archive, schedule and retention policy.
- Jobs: every read/backup with state, outcome, reason, duration (operational -- the PO says not for executives).
- History: only "since yesterday" deltas for a few counts; no long time series is stored yet.

## Constraints
Every figure computed from stored evidence, with its time; UNKNOWN when not evidenced; names masked for aiview; the
screen issues no device command; loads in < 1 s; React + MUI (Material 3) with our own SVG charts (no heavy library
required but allowed if justified); light and dark themes; wall mode (?wall=1).
"""

ASTRA_PROMPT = """You are Astra (gpt-6-astra), chairing a design panel for the neXus executive Overview. Seats, each in
its own short section, then converge: (1) enterprise dashboard/data-visualisation designer (Material 3, Tufte/Few
principles), (2) deputy general manager, network security (risk, audit/BDDK, board reporting), (3) network security
director/manager, (4) market analyst who knows how sector leaders present such estates -- Tufin, AlgoSec, FireMon,
Skybox, BackBox, Check Point Infinity/SmartConsole dashboards, Palo Alto Strata Cloud Manager/AIOps, FortiManager/
FortiAnalyzer, ServiceNow SecOps/IRM. Be honest where you are not sure what a product shows; say so.

""" + BRIEF + """

Deliver in Markdown, English, concrete:
A. What sector leaders put on the executive landing page, and what neXus should take or avoid (one table).
B. The screen, top to bottom, as a wireframe in text: each block with its chart type, exact metric definition
   (numerator/denominator/time), data source from the list above, colour logic, click target. At most 7 blocks above
   the fold on 1440x900. Mark NEW DATA explicitly and give the cheapest way to get it (e.g. a nightly snapshot table
   for trends).
C. The "posture score" question: one headline score or not? If yes, its formula from our data, and why it is honest.
D. Fixes for the defects of attempt (b), and what to do about "patch currency" (vendor-recommended builds, end of
   support dates -- say which need NEW DATA).
E. Wall-display variant differences.
F. A first release that can ship this week with today's data, then a second with snapshots/trends.
Cite screenshot files for claims about the current screens.
"""

FABLE_PROMPT = """You are Fable (claude-fable-5-1), second designer and reviewer. Astra's panel output is {astra}; read
it, read every screenshot in {inputs}, then produce the FINAL specification the engineer will build.

""" + BRIEF + """

1. Where Astra is right, wrong, generic or over-engineered (short).
2. The final screen spec, top to bottom: block, chart type, metric definition, source, colour rule, click target,
   empty/UNKNOWN state -- precise enough to implement without questions. Keep it striking and executive: few numbers,
   strong visuals, plain words, each vendor in its own terms.
3. What ships now with today's data vs what needs NEW DATA (trend snapshots, vendor-recommended versions, end of
   support), in a table.
4. Copy: the exact headline texts and labels (English).
Markdown, English, concise.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inputs", required=True)
    a = ap.parse_args()
    inputs = Path(a.inputs).resolve()
    images = sorted(str(p) for p in inputs.glob("*.png"))
    cmd = ["codex", "exec", "-m", "gpt-6-astra", "-c", "model_reasoning_effort=high", "--sandbox", "read-only", "-o", str(ASTRA_OUT)]
    for img in images:
        cmd += ["-i", img]
    cmd += ["--", ASTRA_PROMPT.format(inputs=inputs)]
    p = subprocess.run(cmd, text=True, capture_output=True, cwd=str(BASE), timeout=2400)
    if p.returncode != 0 or not ASTRA_OUT.exists() or ASTRA_OUT.stat().st_size == 0:
        print(f"astra: failed rc={p.returncode}\n{p.stderr[-3000:]}")
        return 1
    print(f"astra: ok -> {ASTRA_OUT}", flush=True)
    p = subprocess.run(["/Users/OzanDur/.local/bin/claude", "-p", FABLE_PROMPT.format(astra=ASTRA_OUT, inputs=inputs), "--model",
                        "claude-fable-5-1", "--tools", "Read", "--add-dir", str(inputs), "--dangerously-skip-permissions"],
                       text=True, capture_output=True, cwd=str(BASE), timeout=2400)
    if p.returncode != 0 or not p.stdout.strip():
        print(f"fable: failed rc={p.returncode}\n{p.stderr[-3000:]}")
        return 1
    FABLE_OUT.write_text(p.stdout.strip() + "\n", encoding="utf-8")
    print(f"fable: ok -> {FABLE_OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
