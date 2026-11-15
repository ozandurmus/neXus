#!/usr/bin/env python3
"""GOV.PO.3 / Codex Architecture Consultation Script for neXus Failover Engine.

Consults Codex CLI non-interactively to review and validate the proposed
Failover Engine architecture, pluggable pre-flight checks SPI, and execution
state machine for Check Point ClusterXL and Palo Alto Networks Active/Passive clusters.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
PLAN_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/implementation_plan.md")
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CODEX_FAILOVER_ARCHITECTURE_REVIEW.md"


def main() -> int:
    plan_content = PLAN_DOC.read_text(encoding="utf-8") if PLAN_DOC.exists() else "Plan not found"

    prompt = f"""You are acting as the Lead Software Architect / Codex Reviewer (Astra) for the neXus project.
In this single-turn review consultation, you are reviewing the neXus Failover Engine architecture and implementation plan.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert architectural review directly in your response text.

The Product Owner requested a dedicated Failover Engine providing:
1. Pluggable pre-flight readiness checks evaluating vendor documentation (Check Point ClusterXL & Palo Alto Networks HA), community operational best practices, and the 7 canonical stop-conditions from legacy Python (`utils/failover/`), plus enterprise checks (critical devices/pnotes, interfaces/VIPs, session sync tables, routing stability, resource availability).
2. Clean extensibility SPI (`PreflightCheck`) allowing new checks (e.g., BGP peering, synthetic probes, change window ticket validation) to be dynamically registered.
3. Dual execution triggers:
   - Manual UI button ("Initiate Failover") with 4-eyes confirmation, mandatory reason requirement, and pre-compiled reversal plan.
   - Scheduled/timed maintenance window failover with Just-In-Time (JIT) pre-flight re-check, automatic abort on state drift, and full audit trail.
4. Clean integration across `ui2-job-engine`, `ui2-worker`, `ui2-service`, and `ui2-frontend` (OperationsScreen.tsx).

Below is the proposed Implementation Plan:

---
{plan_content}
---

Please perform a comprehensive software architecture review covering:
1. Interface and SPI Design: Does `PreflightCheck` / `CheckResult` / `PreflightRegistry` provide a clean, decoupled, and extensible abstraction?
2. Failover Lifecycle State Machine: Evaluate the transitions (`PLANNED` -> `PREFLIGHT_RUNNING` -> `READY` -> `EXECUTING` -> `POST_VERIFYING` -> `COMPLETED` / `ROLLED_BACK` / `ABORTED`). Are there race conditions, failure handling gaps, or unhandled lease expirations?
3. Scheduling Architecture: How should the JIT pre-flight re-check and abort-on-drift be wired in `ui2-service` and `job-engine`?
4. Multi-Vendor Support: Assess adequacy for Check Point ClusterXL and Palo Alto Networks Active/Passive clusters.
5. Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by specific architectural recommendations.
"""

    print("[*] Launching Codex consultation via `codex exec`...")
    cmd = [
        "/Users/OzanDur/.local/bin/codex",
        "exec",
        "--sandbox",
        "read-only",
        "-c",
        "model_reasoning_effort=high",
        "-o",
        str(REVIEW_OUTPUT),
        "-",
    ]

    try:
        proc = subprocess.run(
            cmd,
            input=prompt,
            text=True,
            capture_output=True,
            cwd=str(BASE_DIR),
            check=True,
        )
        print("[+] Codex consultation completed successfully.")
        if REVIEW_OUTPUT.exists():
            review_text = REVIEW_OUTPUT.read_text(encoding="utf-8")
            print("\n=== CODEX ARCHITECTURAL REVIEW SUMMARY ===")
            print(review_text[:2000] + ("..." if len(review_text) > 2000 else ""))
            print(f"\n[+] Full review written to: {REVIEW_OUTPUT}")
        else:
            print(f"[!] Warning: Review output file {REVIEW_OUTPUT} was not created, stdout:")
            print(proc.stdout)
        return 0
    except subprocess.CalledProcessError as err:
        print(f"[-] Codex consultation failed: {err}", file=sys.stderr)
        print(f"Stderr:\n{err.stderr}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
