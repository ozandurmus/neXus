#!/usr/bin/env python3
"""GOV.PO.3 / Codex Architecture Consultation Script for neXus Failover Engine Phase C.

Consults Codex CLI non-interactively to review and validate the proposed
Phase C Controlled Manual Failover Execution architecture, state machine,
mutation boundary, and vendor adapters for Check Point ClusterXL and Palo Alto Networks HA.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
PLAN_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/implementation_plan.md")
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CODEX_FAILOVER_PHASE_C_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
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

    prompt = f"""You are acting as the Lead Software Architect / Codex Reviewer (Astra) for the neXus project.
In this single-turn review consultation, you are reviewing the architecture, state machine, mutation boundary, and vendor adapters for Phase C of the neXus Failover Engine: Controlled Manual Failover Execution.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert architectural review directly in your response text.

### Context & Prior Phases:
- Phase A (Implemented & Validated): Read-Only Pre-Flight Readiness Inspection (12 core checks, required check manifests, two-sided corroboration, fail-closed).
- Phase B (Implemented & Validated): Cryptographic 4-Eyes Dual Control Authorization (`requesterId != approverId`), 15-minute single-use HMAC-SHA256 lease tokens, and deterministic dry-run plan disclosure (`mutationAuthorized = false`).

### Phase C Scope Under Review:
The Product Owner requested Phase C implementation:
1. Controlled Manual Failover Execution under strict command gates:
   - Check Point ClusterXL: `clusterXL_admin down` on active member, verified by `cphaprob stat`.
   - Palo Alto Networks Active/Passive: `request high-availability state suspend` on active peer, verified by `show high-availability state`.
2. Lifecycle State Machine:
   `PLANNED` -> `PRECONDITION_VERIFYING` -> `MUTATION_COMMITTED` -> `EXECUTING` -> `POST_OBSERVING` -> `SUCCEEDED` / `FAILED_NO_CHANGE` / `OUTCOME_UNKNOWN` / `ABORTED_PRE_MUTATION`.
3. Strict Operational Invariants:
   - Exactly-once mutation boundary: Zero blind retries on timeout or ambiguous output.
   - Ambiguous outcome -> `OUTCOME_UNKNOWN` with entity quarantine until manual audited acknowledgment.
   - Same-workflow pre-condition check: Re-verify active member role immediately prior to command submission.
   - Dual-member post-condition verification: Read both peers independently to confirm role transition and session continuity.
   - Reversal as a new action: Reversal/failback (`clusterXL_admin up` / `request high-availability state functional`) is a separate confirmed action, NEVER an automated blind rollback.
   - Pilot / Lab Fence: Strict allowlist gating execution to approved non-production/lab clusters.

---
### Proposed Phase C Implementation Plan:
```markdown
{plan_content}
```

---
### Supporting Phase B Code Base:

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
Please perform a comprehensive software architecture review covering:
1. Lifecycle State Machine & Mutation Boundary: Are transitions sound? Is `mutation_boundary_crossed` properly transacted? Are race conditions, worker restarts, or lost responses safely quarantined as `OUTCOME_UNKNOWN`?
2. Precondition & Post-Verification Invariants: Is same-workflow re-check sufficient to prevent split-brain? Does post-verification guarantee independent corroboration rather than one-sided claims?
3. Reversal Architecture: Is the prohibition of automatic rollback soundly maintained?
4. Multi-Vendor Mechanics: Evaluate the command sequences for Check Point ClusterXL and Palo Alto Networks HA. Are error envelopes, exit codes, and interface link settle times properly addressed?
5. Safety Gates & Blast Radius: Assess the pilot allowlist fence, role permissions (`OPERATE`), and audit immutability.
6. Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by specific architectural findings and recommendations.
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
            print("\n=== CODEX PHASE C ARCHITECTURAL REVIEW SUMMARY ===")
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
