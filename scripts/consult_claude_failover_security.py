#!/usr/bin/env python3
"""Consultation Script for neXus Failover Engine Security & Operational Risk (Fable / Claude Reviewer).
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
PLAN_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/implementation_plan.md")
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_FAILOVER_SECURITY_REVIEW.md"


def main() -> int:
    plan_content = PLAN_DOC.read_text(encoding="utf-8") if PLAN_DOC.exists() else "Plan not found"

    prompt = f"""You are Fable, the Enterprise Security Architect for the neXus project.
In this single-turn review consultation, you are reviewing the neXus Failover Engine architecture and security design.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert security & operational risk review directly in your response text.

Here is the proposed Implementation Plan for the Failover Engine:

---
{plan_content}
---

Please perform a comprehensive security, operational risk, and fail-safe review covering:
1. Operational Risk Classification (CLASS_2_OPERATIONAL_STATE_CHANGE) & Command Gate Controls:
   - Check Point: `clusterXL_admin down` (active demote) / `clusterXL_admin up` (reversal).
   - Palo Alto Networks: `request high-availability state suspend` / `request high-availability state functional` (reversal).
   - Are command injection, parameter tampering, or unauthorized privilege escalation risks completely eliminated?
2. Two-Person Integrity (4-Eyes Principle) & Authorization Leases:
   - UI manual trigger safeguards (mandatory operator reason, secondary confirmation, signed short-lived execution lease).
   - Replay attack prevention (nonce/single-use token).
3. Fail-Closed & Stop-Condition Invariants:
   - Evaluation of the 7 canonical stop-conditions (Viable Target, State Sync delta, Interface/VIP parity, Split-Brain prevention, Link/Sync health, Preemption hazards, Flap history).
   - Do fail-closed semantics strictly abort if any critical pre-flight check returns UNKNOWN or FAILED?
4. Scheduled Failover Safety (Maintenance Windows):
   - JIT (Just-In-Time) pre-flight execution at T_0 immediately prior to mutation.
   - Automatic abort on drift without human intervention.
5. Non-Automatic Rollback Rule:
   - In accordance with OP.2.0 §10.2, automatic rollback on mutation failure is prohibited to prevent cascading split-brain. Is the pre-compiled reversal plan model secure?

Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by specific security recommendations.
"""

    print("[*] Launching Fable (Claude) consultation via `claude -p`...")
    cmd = [
        "/Users/OzanDur/.local/bin/claude",
        "-p",
        prompt,
        "--tools",
        "",
        "--dangerously-skip-permissions",
    ]

    try:
        proc = subprocess.run(
            cmd,
            text=True,
            capture_output=True,
            cwd=str(BASE_DIR),
            check=True,
        )
        print("[+] Fable consultation completed successfully.")
        review_text = proc.stdout.strip()
        REVIEW_OUTPUT.write_text(review_text, encoding="utf-8")
        print("\n=== FABLE (CLAUDE) FAILOVER SECURITY REVIEW SUMMARY ===")
        print(review_text[:2000] + ("..." if len(review_text) > 2000 else ""))
        print(f"\n[+] Full review written to: {REVIEW_OUTPUT}")
        return 0
    except subprocess.CalledProcessError as err:
        print(f"[-] Fable consultation failed: {err}", file=sys.stderr)
        print(f"Stderr:\n{err.stderr}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
