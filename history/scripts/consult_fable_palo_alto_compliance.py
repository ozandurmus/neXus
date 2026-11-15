#!/usr/bin/env python3
"""Consultation Script for Palo Alto PAN-OS Compliance Architecture (Fable / Claude Reviewer).
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
PLAN_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/implementation_plan.md")
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_PALO_ALTO_COMPLIANCE_REVIEW.md"

def main() -> int:
    plan_content = PLAN_DOC.read_text(encoding="utf-8") if PLAN_DOC.exists() else "Plan not found"

    prompt = f"""You are Fable, the Enterprise Security Architect for the neXus project.
In this single-turn review consultation, you are reviewing the Palo Alto PAN-OS Compliance implementation plan.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert architectural review directly in your response text.

Here is the proposed Implementation Plan:

---
{plan_content}
---

Please provide your comprehensive architectural and security review covering:
1. Catalog Completeness & Multi-Framework Mapping (CIS Palo Alto Firewall Benchmark, PCI-DSS v4.0.1, NIST SP 800-53 Rev 5, BDDK Financial Baseline).
2. PAN-OS XML Parsing & DLP Sanitization (<phash>, <admin-password>, <pre-shared-key>, <private-key>, etc.) to ensure raw-evidence law compliance.
3. Fail-Closed DATA_UNAVAILABLE handling for missing evidence and the 4 gated entries (PAN-GATE-*).
4. Stateless microservice routing (Ui2ComplianceServer) and mixed-fleet UI safety (ComplianceService).

State your verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by your specific recommendations.
"""

    print("[*] Launching Fable (Claude) consultation...")
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
        print("\n=== FABLE (CLAUDE) PALO ALTO COMPLIANCE REVIEW SUMMARY ===")
        print(review_text[:2000] + ("..." if len(review_text) > 2000 else ""))
        print(f"\n[+] Full review written to: {REVIEW_OUTPUT}")
        return 0
    except subprocess.CalledProcessError as err:
        print(f"[-] Fable consultation failed: {err}", file=sys.stderr)
        print(f"Stderr:\n{err.stderr}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
