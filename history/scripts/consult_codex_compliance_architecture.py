#!/usr/bin/env python3
"""GOV.PO.3 / Codex Architecture Consultation Script for ui2-compliance microservice.

Consults Codex CLI non-interactively to review and validate the proposed
`ui2-compliance` microservice architecture against frozen contracts,
CIS/PCI-DSS/NIST/COBIT frameworks, DATA_UNAVAILABLE semantics, and UI2 boundaries.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
ARCH_DOC = BASE_DIR / "docs" / "design" / "UI2_COMPLIANCE_MICROSERVICE_ARCHITECTURE.md"
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CODEX_COMPLIANCE_ARCHITECTURE_REVIEW.md"


def main() -> int:
    if not ARCH_DOC.exists():
        print(f"Error: Architecture document not found at {ARCH_DOC}", file=sys.stderr)
        return 1

    arch_content = ARCH_DOC.read_text(encoding="utf-8")

    prompt = f"""You are acting as the Principal Software Architect / Codex Reviewer (Astra) for the neXus / UI2 project.

The Product Owner requested a dedicated microservice for Compliance (`ui2-compliance`) with the following core constraints:
1. A separate microservice running alongside ui2-service, ui2-worker, and ui2-configuration.
2. Industry-leading benchmarks and regulatory frameworks: CIS Benchmark (Check Point Gaia Benchmark v1.1.0+, PAN-OS Benchmark), PCI-DSS v4.0 (Firewall Requirements 1.2, 1.3, 2.2, 8.2, 8.3, 10.2), NIST SP 800-41 / 800-53, and COBIT / ISO 27001 / BDDK.
3. Controls must be individually assignable to firewalls, as well as via framework bundles/profiles.
4. Hard Requirement on Missing Evidence: If a control cannot be evaluated because the required configuration/operational command is not yet collected from the firewall, the control must NOT be omitted or discarded. It must be marked as DATA_UNAVAILABLE / EVIDENCE_MISSING ("Veri Yok / Eksik"), clearly stating what command is missing, so it automatically activates once collection is added.
5. Compliance & Drift Scoring: Generate a compliance score and drift score.
6. Multi-vendor SPI: Check Point first, then Palo Alto Networks.
7. Dedicated Compliance screen in UI with control tables, device counts, compliance rates, and critical deficiencies.

Below is the proposed architecture specification:

---
{arch_content}
---

Please perform an in-depth architectural review and evaluate:
1. Service boundary & statelessness: Should ui2-compliance be strictly stateless (evaluation engine over passed JSON configuration/inventory), with ui2-service managing PostgreSQL persistence and assignments?
2. Data model & assertion engine: Adequacy of declarative JSON rules with fixed assertion operators (gte, lte, matches, in, none_match) vs custom evaluators.
3. DATA_UNAVAILABLE mathematical & operational treatment: How to compute overall compliance percentage and drift score without unfairly penalizing or artificially inflating when data is missing.
4. Per-device and bundle assignment model in PostgreSQL (schema design for compliance_assignments, compliance_eval_runs, compliance_eval_items).
5. Frontend UI contract & aiview privacy filtering (ensuring no secret leakage or IP topology leakage).
6. Operational feasibility on K3s (Port 8085, resource requests/limits, healthz).

Conclude with a clear verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], and provide actionable recommendations.
"""

    print("[*] Launching Codex consultation via `codex exec`...")
    cmd = [
        "codex",
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
            print("\n=== CODEX COMPLIANCE ARCHITECTURAL REVIEW SUMMARY ===")
            print(review_text[:1500] + ("..." if len(review_text) > 1500 else ""))
            print(f"\n[+] Full review written to: {REVIEW_OUTPUT}")
        else:
            print(f"[!] Warning: Review output file {REVIEW_OUTPUT} was not created.")
        return 0
    except subprocess.CalledProcessError as err:
        print(f"[-] Codex consultation failed: {err}", file=sys.stderr)
        print(f"Stderr:\n{err.stderr}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
