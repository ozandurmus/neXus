#!/usr/bin/env python3
"""GOV.PO.3 / Codex Architecture Consultation Script.

Consults Codex CLI non-interactively to review and validate the proposed
`ui2-configuration` microservice architecture against frozen contracts,
Check Point 1:1 Python logic, and UI2 integration boundaries.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
ARCH_DOC = BASE_DIR / "docs" / "design" / "UI2_CONFIGURATION_MICROSERVICE_ARCHITECTURE.md"
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CODEX_CONFIGURATION_ARCHITECTURE_REVIEW.md"


def main() -> int:
    if not ARCH_DOC.exists():
        print(f"Error: Architecture document not found at {ARCH_DOC}", file=sys.stderr)
        return 1

    arch_content = ARCH_DOC.read_text(encoding="utf-8")

    prompt = f"""You are acting as the Software Architect / Codex Reviewer for the neXus / UI2 project.

The Product Owner requested a dedicated microservice for Configuration, specifically focusing on Check Point Gaia first (`ui2-configuration`), with architectural extensibility to add Palo Alto Networks (PAN-OS XML / Panorama) in Phase 2.
The Check Point parser and sanitizer must port 1:1 the battle-tested Python logic from `nexus/configuration/checkpoint_config_collector.py` (canonical hash on set lines, SECRET_LINE_RE, PASSWORD_POLICY_SAFE_RE, MESSAGE_BODY_RE banner body masking, 14 governed sections, setting AST breakdown, key highlights).
It must integrate seamlessly with UI2 (`ui2-service`, `ui2-worker`, and PostgreSQL persistence) and support the `aiview` privacy role (relationship-preserving HMAC masking).

Below is the proposed architecture specification:

---
{arch_content}
---

Please perform a thorough architectural review and evaluate:
1. Multi-vendor SPI design & domain model adequacy for Check Point now and Palo Alto later.
2. Faithfulness to the 1:1 Check Point Python logic (hashing, secret withholding, policy safe allowlist, banner masking, 14 sections, highlights).
3. Service integration with `ui2-worker` and `ui2-service`, database schema compatibility (`device_configuration_run`, `device_configuration_index`), and `aiview` privacy masking funnel.
4. Operational feasibility on K3s (`deploy/ui2/54-configuration-deployment.yaml`).
5. Any architectural blind spots, edge cases, or recommendations.

Conclude with a clear verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], and summarize any agreed-upon refinements.
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
        print(f"[+] Codex consultation completed successfully.")
        if REVIEW_OUTPUT.exists():
            review_text = REVIEW_OUTPUT.read_text(encoding="utf-8")
            print("\n=== CODEX ARCHITECTURAL REVIEW SUMMARY ===")
            print(review_text[:1500] + ("..." if len(review_text) > 1500 else ""))
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
