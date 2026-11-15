#!/usr/bin/env python3
"""Overview screen design council (PO request 2026-09-22).

Runs the same panel brief through two external reviewers, independently and in
parallel, per AGENTS.md "External model and second-opinion consultation law":
Astra via `codex exec`, Fable via `claude -p`. Each writes its full, unaltered
answer to docs/design/. The synthesis is written afterwards by the engineering
session and names which reviewer said what.
"""
import subprocess
import sys
import threading
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[1]
ASTRA_OUT = BASE_DIR / "docs" / "design" / "OVERVIEW_COUNCIL_2026_09_22_ASTRA.md"
FABLE_OUT = BASE_DIR / "docs" / "design" / "OVERVIEW_COUNCIL_2026_09_22_FABLE.md"

PROMPT = """You are convening a four-seat design panel for the Overview (home) screen of neXus, an
on-premises firewall operations product used by a bank's network security department. Speak in
four distinct voices, each in its own section, then converge:

1. UI/UX designer (enterprise dashboards, Material 3).
2. Network Security Deputy General Manager (GMY): risk, audit, regulator (BDDK) and board reporting.
3. Network Security Manager (Mudur): team workload, SLA, change and incident posture.
4. Firewall administrator / operator: what they must see first thing in the morning.

The product today (real numbers from the live estate, 2026-09-22, names masked):
- 105 devices (65 Check Point: gateways, VSX, ClusterXL; 1 MDS; 40 Palo Alto incl. 1 Panorama), 39 clusters.
- Inventory: interfaces, routes, HA role per member, virtual systems (VSX/VSYS).
- Configuration: sanitized current configuration per device, per-section projection, cluster member DIFF
  (e.g. a setting that differs between the two members of a cluster), change state per collection.
- Platform identity: model, serial, software version, Check Point jumbo hotfix take (six different takes
  across the fleet: 111..161), Palo Alto content versions (app/threat/AV/WildFire/URL), uptime.
- Backups: 83 devices with a stored backup, 102 backup targets, schedule and retention policy, archive
  listing and compare, per-vendor bundles.
- Compliance: CIS / PCI-DSS 4.0.1 / NIST 800-53 / a financial baseline; ~29% assured, 82% evidence
  coverage, 172 critical deficiencies, 428 data gaps.
- Jobs: every collection/backup is a job with state, outcome, reason; ~1500 on record, failures per 24h.
- HA readiness (read-only preflight checks per cluster); no automatic failover.
- Coming: Script Execution (scheduled scripts), automation, syslog and SMTP notifications, service view.

The Overview today shows four count cards (devices with inventory, with configuration, backed up, jobs on
record), a compliance line with framework percentages, and two text panels. The Product Owner says it is
"empty and the data looks meaningless".

Constraints: every figure must be computed from stored evidence (no invented or estimated values; UNKNOWN
when not evidenced); device names are masked for the audit persona; no device command is issued by the
screen; it must load in under one second on ~100 devices.

Deliver, in Markdown:
A. Each seat's top five questions the Overview must answer, and why (one line each).
B. A converged layout: sections top to bottom, each with the exact metric or list, its definition
   (numerator/denominator, time window), the source data above, and the action a click leads to.
C. Five metrics NOT to show and why.
D. Which of these need data neXus does not collect yet (be explicit), and a minimal first release.
Be concrete and short; no generic dashboard advice.
"""


def run_astra(results):
    cmd = ["codex", "exec", "--sandbox", "read-only", "-c", "model_reasoning_effort=high", "-o", str(ASTRA_OUT), "-"]
    try:
        subprocess.run(cmd, input=PROMPT, text=True, capture_output=True, cwd=str(BASE_DIR), check=True, timeout=1500)
        results["astra"] = "ok" if ASTRA_OUT.exists() and ASTRA_OUT.stat().st_size > 0 else "empty output"
    except Exception as err:  # noqa: BLE001 -- report any failure verbatim
        results["astra"] = f"failed: {err}"


def run_fable(results):
    cmd = ["/Users/OzanDur/.local/bin/claude", "-p", PROMPT, "--model", "claude-fable-5-1", "--tools", "",
           "--dangerously-skip-permissions"]
    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, cwd=str(BASE_DIR), check=True, timeout=1500)
        FABLE_OUT.write_text(proc.stdout.strip() + "\n", encoding="utf-8")
        results["fable"] = "ok" if proc.stdout.strip() else "empty output"
    except Exception as err:  # noqa: BLE001
        results["fable"] = f"failed: {err}"


def main():
    results = {}
    threads = [threading.Thread(target=run_astra, args=(results,)), threading.Thread(target=run_fable, args=(results,))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print(f"astra: {results.get('astra')} -> {ASTRA_OUT}")
    print(f"fable: {results.get('fable')} -> {FABLE_OUT}")
    return 0 if results.get("astra") == "ok" and results.get("fable") == "ok" else 1


if __name__ == "__main__":
    sys.exit(main())
