#!/usr/bin/env python3
"""GOV.PO.3 / Claude Architecture Consultation Script for ui2-compliance microservice.

Consults Claude CLI non-interactively to review and validate the proposed
`ui2-compliance` microservice architecture against enterprise financial standards,
BackBox/Indeni/Tufin best practices, and UI2 integration boundaries.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
ARCH_DOC = BASE_DIR / "docs" / "design" / "UI2_COMPLIANCE_MICROSERVICE_ARCHITECTURE.md"
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_COMPLIANCE_ARCHITECTURE_REVIEW.md"


def main() -> int:
    if not ARCH_DOC.exists():
        print(f"Error: Architecture document not found at {ARCH_DOC}", file=sys.stderr)
        return 1

    arch_content = ARCH_DOC.read_text(encoding="utf-8")

    prompt = f"""You are acting as the Enterprise Security Architect / Claude Reviewer (Fable) for the neXus / UI2 project.

The Product Owner requested a dedicated microservice for Compliance (`ui2-compliance`) with the following core constraints:
1. A separate microservice running alongside ui2-service, ui2-worker, and ui2-configuration.
2. Sourced from leading industry benchmarks and financial enterprise requirements: CIS Benchmark (Check Point Gaia & Palo Alto PAN-OS), PCI-DSS v4.0 (Firewall Requirements 1.2, 1.3, 2.2, 8.2, 8.3, 10.2), NIST SP 800-41 / 800-53, and COBIT / ISO 27001 / BDDK.
3. Incorporate checks inspired by market leaders in network compliance: BackBox, Indeni, Tufin, Opinnate.
4. Controls must be individually assignable to firewalls, as well as via framework bundles/profiles.
5. Critical Requirement on Missing Data: If a control requires a piece of configuration/evidence not yet collected from the firewall, the control must NOT be omitted or discarded. It must be explicitly marked as DATA_UNAVAILABLE / EVIDENCE_MISSING ("Veri Yok / Eksik"), clearly identifying the missing command so it automatically becomes active once that command is collected in the future.
6. Dedicated Compliance Screen in UI (separate from Configuration screen) showing compliance rates, drift scores, deficiency tables, and device breakdowns.
7. Multi-vendor SPI (Check Point first, then Palo Alto).

Below is the proposed architecture specification:

---
{arch_content}
---

Please perform a thorough architectural review and evaluate:
1. Industry & Financial Compliance completeness: Does the catalog adequately reflect high-priority firewall controls required by banking/fintech audits (PCI-DSS 4.0, CIS, NIST, COBIT)? What specific controls should be added to the initial catalog?
2. DATA_UNAVAILABLE Handling & User Experience: How should the UI and API present "Data Missing / Unavailable" so that security officers see the gap clearly without mistaking it for a compliant state or a fatal error?
3. Scoring & Drift Mechanics: What is the most robust way to calculate the Device Compliance Score, Fleet Compliance Score, and Drift Score over time?
4. Assignment & Policy Architecture: How should device-to-control assignments be modeled (tags, device groups, individual overrides)?
5. Microservice Boundaries & Performance: Is the division of labor between ui2-service (DB, REST, Auth), ui2-compliance (Stateless evaluation engine), and ui2-worker optimal?
6. Privacy & aiview Replay Viewer safety: How to ensure zero secret leakage in compliance reports.

Conclude with a clear verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], and provide your specific architectural recommendations.
"""

    print("[*] Launching Claude consultation via `claude -p`...")
    cmd = [
        "claude",
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
        print("[+] Claude consultation completed successfully.")
        review_text = proc.stdout
        REVIEW_OUTPUT.write_text(review_text, encoding="utf-8")
        print("\n=== CLAUDE COMPLIANCE ARCHITECTURAL REVIEW SUMMARY ===")
        print(review_text[:1500] + ("..." if len(review_text) > 1500 else ""))
        print(f"\n[+] Full review written to: {REVIEW_OUTPUT}")
        return 0
    except subprocess.CalledProcessError as err:
        print(f"[-] Claude consultation failed: {err}", file=sys.stderr)
        print(f"Stderr:\n{err.stderr}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
