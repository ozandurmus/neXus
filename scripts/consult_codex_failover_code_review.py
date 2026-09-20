#!/usr/bin/env python3
"""Consultation Script for neXus Failover Engine Phase A Code Review (Codex / Astra Reviewer).
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
WALKTHROUGH_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/walkthrough.md")
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CODEX_FAILOVER_CODE_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
    walkthrough = read_file_safely(WALKTHROUGH_DOC)
    spi = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/spi/PreflightCheck.java")
    registry = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/spi/PreflightRegistry.java")
    split_brain = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/checks/TwoSidedSplitBrainCheck.java")
    headroom = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/checks/StandbyResourceHeadroomCheck.java")
    flap = read_file_safely(BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/checks/FlapHistoryCheck.java")
    test_suite = read_file_safely(BASE_DIR / "ui2/job-engine/src/test/java/com/securityexpert/nexus/ui2/jobs/failover/PreflightEngineTest.java")
    controller = read_file_safely(BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/FailoverPreflightController.java")

    prompt = f"""You are Astra, the Lead Software Architect for the neXus project.
In this single-turn review consultation, you are performing an architectural and code review of the newly implemented Phase A Failover Pre-Flight Engine and UI.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert review directly in your response text.

Here is the Walkthrough of the changes:
---
{walkthrough}
---

Key Implementation Artifacts:

### 1. PreflightCheck.java (SPI)
```java
{spi}
```

### 2. PreflightRegistry.java (Closed catalog & required-check manifest)
```java
{registry}
```

### 3. TwoSidedSplitBrainCheck.java
```java
{split_brain}
```

### 4. StandbyResourceHeadroomCheck.java
```java
{headroom}
```

### 5. FlapHistoryCheck.java
```java
{flap}
```

### 6. PreflightEngineTest.java (Includes Generated Matrix Invariant Test)
```java
{test_suite}
```

### 7. FailoverPreflightController.java
```java
{controller}
```

Please perform a comprehensive software architecture and code review covering:
1. Pure Evaluator SPI & Decoupling: Confirm that checks have zero access to device transport or credentials and operate purely on immutable evidence snapshots.
2. Two-Sided Split-Brain & Invariant Handling: Assess the rigor of the two-sided corroboration logic and whether false-pass risks in live split-brain are closed.
3. Fail-Closed Manifest Coverage: Assess the required-check manifest enforcement in PreflightRegistry.
4. Test Quality: Review the unit tests and the generated matrix invariant test.
5. Overall Readiness for Phase B.

Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by your specific findings.
"""

    print("[*] Launching Codex code review consultation via `codex exec`...")
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
            print("\n=== CODEX ARCHITECTURAL CODE REVIEW SUMMARY ===")
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
