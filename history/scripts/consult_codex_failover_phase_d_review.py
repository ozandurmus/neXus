#!/usr/bin/env python3
"""GOV.PO.3 / Codex Architecture Consultation Script for neXus Failover Engine Phase D.

Consults Codex CLI non-interactively to review and validate the proposed
Phase D Scheduled Maintenance Window Failovers with T₀ JIT Verification,
Automated Drift Abort, Cryptographic Schedule Binding, and Hard Window Bounds.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
PLAN_DOC = Path("/Users/OzanDur/.gemini/antigravity/brain/04cb73af-44ad-44ef-89bc-b3fcb7f52228/implementation_plan.md")
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CODEX_FAILOVER_PHASE_D_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
    plan_content = read_file_safely(PLAN_DOC)
    execution_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverExecutionService.java"
    )
    authz_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverAuthorizationService.java"
    )

    prompt = f"""You are acting as the Lead Software Architect / Codex Reviewer (Astra) for the neXus project.
In this single-turn review consultation, you are reviewing the architecture, state machine, cryptographic schedule binding, T₀ JIT verification, drift detection engine, and window expiration controls for Phase D of the neXus Failover Engine: Scheduled Maintenance Window Failover.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert architectural review directly in your response text.

### Context & Prior Validated Phases:
- Phase A (Validated): Read-Only Pre-Flight Readiness Inspection (12 core checks, two-sided corroboration, required check manifests, fail-closed).
- Phase B (Validated): Cryptographic 4-Eyes Dual Control Authorization (`requesterId != approverId`), 15-minute single-use HMAC-SHA256 lease tokens, length-prefixed canonical framing, and deterministic dry-run plan disclosure without consuming the execution lease.
- Phase C (Validated): Controlled Manual Failover Execution under strict CLASS 2 invariants (at-most-once command submission, zero blind retries, JIT two-sided pre-condition re-check, dual independent post-verification, server-owned pilot fence `FailoverPilotAllowlist`, sticky `OUTCOME_UNKNOWN` quarantine, and M3 Operations Console).

### Phase D Scope Under Review:
The Product Owner requested Phase D: Scheduled Maintenance Window Failovers with T₀ JIT Verification and Automated Abort on Drift:
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
### Existing Phase B/C Authorization Service:
```java
{authz_service}
```

---
### Review Deliverables:
Please perform a comprehensive software architecture review covering:
1. **Cryptographic Schedule Binding & Storage Integrity**: Is HMAC-SHA256 over length-prefixed canonical framing sufficient to prevent unauthorized schedule insertion or database row tampering? What are the key rotation and persistence considerations?
2. **Hard Execution Window & Timing Invariants**: Are the temporal bounds (`windowStart`, `windowStart + maxStartDelayMinutes`, `windowEnd`) robust against clock skew, NTP jumps, scheduler sleep lag, and server restarts?
3. **T₀ JIT Verification & Drift Detection Engine**: Is the typed comparison between baseline summary and live T₀ evidence complete? Are there any subtle drift conditions (e.g. peer communication degradation, routing table changes, policy installation in progress) that must also trigger an automated abort?
4. **Failure Modes & Automated Abort Invariants**: Does the system guarantee that automated abort is the ONLY outcome upon drift, and that no mutating commands are issued?
5. **Integration with Phase C Execution Engine**: Does the handoff from scheduler to `FailoverExecutionService` preserve all Phase C invariants (concurrency = 1, pilot fence, at-most-once submission, dual post-verification, sticky quarantine)?
6. **Provide your concrete verdict**: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by specific architectural findings, prioritized P0/P1/P2 issues, and actionable recommendations.
"""

    print("Submitting Phase D Architecture Review prompt to Codex CLI non-interactively...")
    codex_bin = "/Users/OzanDur/.local/bin/codex"
    if not Path(codex_bin).exists():
        codex_bin = "codex"

    try:
        proc = subprocess.Popen(
            [codex_bin, "exec", "-"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            cwd=str(BASE_DIR),
        )
        stdout, stderr = proc.communicate(input=prompt)
    except Exception as e:
        print(f"Failed to execute codex CLI: {e}", file=sys.stderr)
        return 1

    if proc.returncode != 0:
        print(f"Codex execution failed with return code {proc.returncode}:\n{stderr}", file=sys.stderr)
        return proc.returncode

    print(f"Codex consultation completed successfully ({len(stdout)} chars).")
    REVIEW_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    REVIEW_OUTPUT.write_text(stdout, encoding="utf-8")
    print(f"Review saved to: {REVIEW_OUTPUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
