#!/usr/bin/env python3
"""Consultation Script for neXus Failover Engine Phase A Security Code Review (Claude / Fable Reviewer).
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
WALKTHROUGH_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/walkthrough.md")
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_FAILOVER_CODE_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
    walkthrough = read_file_safely(WALKTHROUGH_DOC)
    spi = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/spi/PreflightCheck.java")
    registry = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/spi/PreflightRegistry.java")
    split_brain = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/checks/TwoSidedSplitBrainCheck.java")
    platform_gate = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/checks/PlatformAndModeGateCheck.java")
    service = read_file_safely(BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/PreflightService.java")
    controller = read_file_safely(BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/FailoverPreflightController.java")
    frontend_ui = read_file_safely(BASE_DIR / "ui2/frontend/src/screens/OperationsScreen.tsx")

    prompt = f"""You are Fable, the Enterprise Security Architect for the neXus project.
In this single-turn review consultation, you are performing a security, operational risk, and code review of the newly implemented Phase A Failover Pre-Flight Engine and UI.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert review directly in your response text.

Here is the Walkthrough of the Phase A implementation:
---
{walkthrough}
---

Key Code Artifacts for Review:

### 1. PreflightCheck.java (SPI)
```java
{spi}
```

### 2. PreflightRegistry.java (Manifest & Fail-Closed Guard)
```java
{registry}
```

### 3. TwoSidedSplitBrainCheck.java
```java
{split_brain}
```

### 4. PlatformAndModeGateCheck.java
```java
{platform_gate}
```

### 5. PreflightService.java
```java
{service}
```

### 6. FailoverPreflightController.java
```java
{controller}
```

### 7. OperationsScreen.tsx (UI & Safe Mutation Suppression)
```tsx
{frontend_ui}
```

Please perform a comprehensive security code review covering:
1. Complete Device Mutation Suppression: Verify that Phase A is strictly read-only and that zero device-mutation, command execution, or state change paths exist.
2. AIView & Privacy Compliance: Confirm that all cluster, firewall, and peer identities are strictly pseudonymized (`CLS-ROMEO-01`, `FW-TANGO-04`, `FW-JULIET-06`), and that no unmasked customer data or raw CLI strings cross to the UI or persistence.
3. Two-Sided Corroboration: Evaluate whether the two-sided observation requirement in `TwoSidedSplitBrainCheck` eliminates the single-operator / single-member false-pass risk highlighted in B6.
4. Fail-Closed Manifest & Invariant Enforcement: Assess whether the required check manifest and exception handling guarantee fail-closed behavior on missing checks or errors.
5. Verdict Vocabulary: Confirm that the overall verdict emits `NO_BLOCKING_CONDITIONS_OBSERVED` or `BLOCKING_CONDITIONS_PRESENT` and does NOT claim "SAFE_TO_FAILOVER" or "READINESS_CONFIRMED".

Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by your specific findings.
"""

    print("[*] Launching Fable (Claude) code review consultation via `claude -p`...")
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
        print("\n=== FABLE (CLAUDE) FAILOVER SECURITY CODE REVIEW SUMMARY ===")
        print(review_text[:2000] + ("..." if len(review_text) > 2000 else ""))
        print(f"\n[+] Full review written to: {REVIEW_OUTPUT}")
        return 0
    except subprocess.CalledProcessError as err:
        print(f"[-] Fable consultation failed: {err}", file=sys.stderr)
        print(f"Stderr:\n{err.stderr}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
