#!/usr/bin/env python3
"""Consultation Script for neXus Failover Engine Phase C Security & Risk Review (Claude / Fable Reviewer).

Directly executes the installed host CLI tool `/Users/OzanDur/.local/bin/claude -p`.
Feeds Codex (Astra)'s Phase C architectural review into Claude (Fable) for a sequential second-opinion review.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
PLAN_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/implementation_plan.md")
ASTRA_REVIEW_FILE = BASE_DIR / "docs" / "design" / "CODEX_FAILOVER_PHASE_C_REVIEW.md"
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_FAILOVER_PHASE_C_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
    astra_review = read_file_safely(ASTRA_REVIEW_FILE)
    plan_content = read_file_safely(PLAN_DOC)
    four_eyes_rule = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/authz/FourEyesValidationRule.java"
    )
    dry_run_planner = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/plan/FailoverDryRunPlanner.java"
    )
    authz_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverAuthorizationService.java"
    )
    authz_controller = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/FailoverAuthorizationController.java"
    )

    prompt = f"""You are Fable, the Enterprise Security Architect for the neXus project.
In this single-turn review consultation, you are performing a security, blast-radius, and second-opinion architectural review of Phase C of the neXus Failover Engine: Controlled Manual Failover Execution.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert review directly in your response text.

### Background & Phase C Invariants:
1. Operational Class & Mutation Boundary:
   - Failover execution is CLASS 2 (`CLASS_2_OPERATIONAL_STATE_CHANGE`).
   - Gated behind a validated single-use 4-eyes lease token (`requesterId != approverId`).
   - Exactly-once mutation boundary: Zero blind retries under any circumstances.
2. Same-Workflow Precondition Re-Check:
   - Immediately before crossing the mutation boundary, re-verify the active member's state. If changed or ambiguous -> `ABORTED_PRE_MUTATION`.
3. Independent Dual-Member Post-Verification:
   - Read both members independently.
   - Outcome classification: `SUCCEEDED` (intended postcondition matched), `FAILED_NO_CHANGE` (both members observed, no transition occurred), or `OUTCOME_UNKNOWN` (ambiguous/lost observation -> quarantine until audited acknowledgment).
4. Reversal Policy:
   - Reversal (failback) is an independent, confirmed new action (`clusterXL_admin up` / `request high-availability state functional`), NEVER an automatic rollback.
5. Pilot Fence:
   - Enforce strict pilot allowlist to prevent accidental execution against production firewalls.

---
### Astra's Architectural Review (from Codex):
```markdown
{astra_review}
```

---
### Proposed Phase C Implementation Plan:
```markdown
{plan_content}
```

---
### Supporting Phase B Code Artifacts:

#### 1. FourEyesValidationRule.java
```java
{four_eyes_rule}
```

#### 2. FailoverDryRunPlanner.java
```java
{dry_run_planner}
```

#### 3. FailoverAuthorizationService.java
```java
{authz_service}
```

#### 4. FailoverAuthorizationController.java
```java
{authz_controller}
```

---
### Review Deliverables:
Please perform a comprehensive security and second-opinion architectural review covering:
1. Evaluation of Astra's Findings: Assess Astra's critical findings and approval conditions. Where do you agree or disagree?
2. Mutation Boundary & Split-Brain Prevention: Does the proposed execution flow guarantee that a split-brain condition cannot be induced or worsened?
3. Replay Protection & Token Lifecycle: Audit the single-use token consumption, nonce check, and lease duration.
4. Blast Radius & Pilot Containment: Is the pilot allowlist fence robust enough to prevent unauthorized commands reaching production gateways?
5. Post-Verification & Quarantine Semantics: Assess `OUTCOME_UNKNOWN` handling, entity locking, and timeout behaviour.
6. Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by your specific findings, assessment of Astra's review, and recommendations.
"""

    print("[*] Launching Fable (Claude) Phase C review consultation via `claude -p`...")
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
            check=True,
        )
        output = proc.stdout.strip()
        REVIEW_OUTPUT.write_text(output, encoding="utf-8")
        print(f"[+] Claude Phase C review written successfully to: {REVIEW_OUTPUT}")
        print("--- STDOUT SNIPPET ---")
        print(output[:1000])
        return 0
    except subprocess.CalledProcessError as e:
        print(f"[!] Claude review failed with code {e.returncode}")
        if e.stderr:
            print(f"Error output:\n{e.stderr}")
        return e.returncode
    except FileNotFoundError:
        print("[!] Error: `/Users/OzanDur/.local/bin/claude` CLI not found on host.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
