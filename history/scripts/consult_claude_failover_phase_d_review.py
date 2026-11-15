#!/usr/bin/env python3
"""GOV.PO.3 / Claude Security Consultation Script for neXus Failover Engine Phase D.

Consults Claude CLI non-interactively to review and validate the proposed
Phase D Scheduled Maintenance Window Failovers, incorporating the findings
from the Codex (Astra) Lead Software Architect review.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
PLAN_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/implementation_plan.md")
CODEX_REVIEW = BASE_DIR / "docs" / "design" / "CODEX_FAILOVER_PHASE_D_REVIEW.md"
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_FAILOVER_PHASE_D_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
    plan_content = read_file_safely(PLAN_DOC)
    codex_review_content = read_file_safely(CODEX_REVIEW)
    execution_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverExecutionService.java"
    )
    authz_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverAuthorizationService.java"
    )

    prompt = f"""You are acting as the Enterprise Security Architect / Claude Reviewer (Fable) for the neXus project.
In this single-turn review consultation, you are reviewing the security, cryptographic bindings, timing bounds, and failure modes for Phase D of the neXus Failover Engine: Scheduled Maintenance Window Failovers.
You have been provided with the Phase D Implementation Plan AND the preceding architectural review from the Lead Software Architect / Codex (Astra).
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert security review directly in your response text.

### Prior Validated Context & Phase Invariants:
- Phase A (Validated): Read-Only Pre-Flight Readiness Inspection (12 core checks, two-sided corroboration, required check manifests, fail-closed).
- Phase B (Validated): Cryptographic 4-Eyes Dual Control Authorization (`requesterId != approverId`), 15-minute single-use HMAC-SHA256 lease tokens, length-prefixed canonical framing, and deterministic dry-run plan disclosure without consuming the execution lease.
- Phase C (Validated): Controlled Manual Failover Execution under strict CLASS 2 invariants (at-most-once command submission, zero blind retries, JIT two-sided pre-condition re-check, dual independent post-verification, server-owned pilot fence `FailoverPilotAllowlist`, sticky `OUTCOME_UNKNOWN` quarantine, and M3 Operations Console).

### Preceding Lead Software Architect (Codex / Astra) Review:
```markdown
{codex_review_content}
```

---
### Phase D Scope Under Review:
1. **Cryptographic Schedule Binding (Claude R5)**:
   - Schedule rows in storage are sealed with HMAC-SHA256 using the server master key over canonical length-prefixed fields:
     `len:scheduleId|len:clusterRef|len:windowStart|len:windowEnd|len:actionKind|len:requesterId|len:approverId|len:baselineDigest`
   - At T₀, scheduler verifies HMAC before touching any device. Tampered records immediately transition to `ABORTED_TAMPERED`.
   - Revocation/cancellation status is checked at T₀ against the revocation ledger.
2. **Hard Execution Window & Staleness Bounds (Claude R6, R7, R8)**:
   - Maximum lead time capped at 7 days (`MAX_LEAD_TIME_DAYS = 7`). Longer schedules rejected at booking.
   - All timestamps stored and processed in UTC (`Instant`).
   - If scheduler awakens past the allowable start window (`now > windowStart + maxStartDelayMinutes`), it strictly aborts (`ABORTED_WINDOW_EXPIRED`) with ZERO device commands sent.
3. **T₀ JIT Verification & Automated Drift Abort (Claude R9, Astra Q1)**:
   - At T₀, scheduler collects fresh two-sided evidence and re-evaluates the full preflight check battery.
   - Compares live state field-by-field against baseline snapshot:
     - Any blocking check failure in T₀ report (`BLOCKING_CONDITIONS_PRESENT`) -> AUTOMATIC ABORT.
     - Active member role change (flap or prior failover) -> AUTOMATIC ABORT.
     - Standby member health or sync degradation -> AUTOMATIC ABORT.
     - Software version or installed policy hash mismatch -> AUTOMATIC ABORT.
   - Automated abort ONLY: zero blind retries, zero "pause and wait" loops.
4. **Execution Dispatch via Phase C Engine**:
   - Clean verification dispatches through `FailoverExecutionService` with fleet concurrency = 1 and pilot allowlist fence enforcement (`LAB_PILOT`).
   - Ambiguous outcome transitions to sticky `OUTCOME_UNKNOWN` quarantine.
5. **M3 Operations Console Integration**:
   - Schedule dialog with UTC datetime picker, duration, max start delay, 4-eyes approval, and baseline preview.
   - Scheduled windows table with status chips, drift report viewer, and cancellation actions.

---
### Proposed Phase D Implementation Plan:
```markdown
{plan_content}
```

---
### Existing Phase C Execution Service:
```java
{execution_service}
```

---
### Review Deliverables:
Please perform a rigorous enterprise security architecture review:
1. **Cryptographic Binding & Revocation Model**: Does HMAC-SHA256 over length-prefixed canonical framing prevent schedule tampering, replay attacks, or unauthorized privilege escalation? Is the revocation ledger check race-free at T₀?
2. **Temporal Window Enforcements**: Are the hard bounds (`windowStart`, `windowStart + maxStartDelayMinutes`, `windowEnd`) watertight against clock manipulation, delays, or scheduler lag? Does the system fail closed if the scheduler wakes up late?
3. **Drift Detection & Automatic Abort Invariant**: Are all potential drift vectors covered? Does the system strictly enforce abort-only semantics with zero retries?
4. **Unattended Execution Blast Radius**: Given that scheduled failovers execute without a human actively typing commands, evaluate the pilot fence, concurrency cap (1), and sticky quarantine under unattended execution.
5. **Evaluate Codex (Astra) Findings**: Review Astra's findings. Do you agree, dispute, or expand upon any P0/P1 items raised?
6. **Provide your concrete verdict**: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by prioritized security findings (P0/P1/P2) and required mitigations.
"""

    print("Submitting Phase D Security Review prompt to Claude CLI non-interactively...")
    claude_bin = "/Users/OzanDur/.local/bin/claude"
    if not Path(claude_bin).exists():
        claude_bin = "claude"

    try:
        proc = subprocess.Popen(
            [claude_bin, "-p", prompt],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            cwd=str(BASE_DIR),
        )
        stdout, stderr = proc.communicate()
    except Exception as e:
        print(f"Failed to execute claude CLI: {e}", file=sys.stderr)
        return 1

    if proc.returncode != 0:
        print(f"Claude execution failed with return code {proc.returncode}:\n{stderr}", file=sys.stderr)
        return proc.returncode

    print(f"Claude consultation completed successfully ({len(stdout)} chars).")
    REVIEW_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    REVIEW_OUTPUT.write_text(stdout, encoding="utf-8")
    print(f"Review saved to: {REVIEW_OUTPUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
