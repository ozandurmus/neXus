#!/usr/bin/env python3
"""GOV.PO.3 / Claude Final Comprehensive Security Review Script for neXus Failover Engine (Phases A-D, Stages 1 & 2).

Consults Claude CLI non-interactively to perform a comprehensive final enterprise security
and architectural safety review of the completed neXus Failover Engine implementation across
Phases A, B, C, and D, incorporating the final review from Codex (Astra) after Stages 1 & 2.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
CODEX_FINAL_REVIEW = BASE_DIR / "docs" / "design" / "CODEX_FAILOVER_ENGINE_FINAL_REVIEW.md"
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_FAILOVER_ENGINE_FINAL_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
    codex_final_review_content = read_file_safely(CODEX_FINAL_REVIEW)
    v31_schema = read_file_safely(
        BASE_DIR / "ui2/service/src/main/resources/db/migration/V31__failover_engine_durable_schema.sql"
    )
    key_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverKeyManagementService.java"
    )
    principal_canon = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/PrincipalCanonicalization.java"
    )
    clock_check = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/checks/ClockHealthCheck.java"
    )
    drift_engine = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/FailoverDriftEngine.java"
    )
    crypto_service = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/ScheduleCryptographicService.java"
    )
    envelope = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/FailoverScheduleEnvelope.java"
    )
    baseline_summary = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/BaselineSnapshotSummary.java"
    )
    schedule_record = read_file_safely(
        BASE_DIR / "ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/FailoverScheduleRecord.java"
    )
    execution_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverExecutionService.java"
    )
    schedule_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverScheduleService.java"
    )
    schedule_ledger = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverScheduleLedger.java"
    )
    quarantine_store = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/DurableQuarantineStore.java"
    )
    admission_control = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverBookingAdmissionControl.java"
    )
    authz_service = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverAuthorizationService.java"
    )

    prompt = f"""You are acting as the Enterprise Security Architect / Claude Reviewer (Fable) for the neXus project.
In this single-turn review consultation, you are performing the FINAL COMPREHENSIVE SECURITY RE-REVIEW for the complete neXus Failover Engine (Phases A, B, C, and D) following the remediation of all prior review findings across Stage 1 (Deterministic Code Hardening) and Stage 2 (Durable Flyway Schema, Managed Keys, Dual Control Hardening, Startup Crash Recovery).
You have also been provided with the preceding Final Re-Review from the Lead Software Architect / Codex (Astra).
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert security review directly in your response text.

### Remediations Delivered across Stage 1 & Stage 2:
1. **Action & Envelope Cryptographic Binding (CF-P0.1)**:
   - `actionKind` is strictly bound into `FailoverScheduleEnvelope` and its length-prefixed canonical framing (`len:actionKind|`).
2. **Deterministic Baseline SHA-256 Digest (CF-P0.2)**:
   - Eliminated random UUID. `BaselineSnapshotSummary.computeCanonicalDigest()` computes a deterministic SHA-256 hash over canonical fields.
3. **Dynamic Execution Deadline Invariant (CF-P0.3, CF-P0.10)**:
   - Stored deadline is validated against `computeExecutionDeadline` in record constructor; re-checked dynamically in-lock immediately prior to lease consumption and mutation dispatch.
4. **Durable Flyway PostgreSQL Persistence (CF-P0.7, CF-P1.4)**:
   - `V31__failover_engine_durable_schema.sql` creates persistent tables:
     - `failover_schedules` with optimistic lock `version`.
     - `failover_grant_consumption` with real `UNIQUE(grant_id)` storage constraint.
     - `failover_quarantine` with CAS acknowledgment on `execution_id`.
     - `failover_schedule_ledger` with append-only hash chain and `UNIQUE(entry_index)`.
   - `FailoverScheduleLedger`, `DurableQuarantineStore`, and `FailoverScheduleService` use Spring `JdbcTemplate` for real transactional persistence.
5. **Durable Key Management Service (CF-P0.5, CF-P1.2)**:
   - `FailoverKeyManagementService` provides managed, non-ephemeral master key resolution (env var -> sysprop -> durable local file) and keyId resolution. Added `ABORTED_KEY_UNAVAILABLE` so missing keys never trigger false tamper alerts.
6. **Single-Snapshot T₀ Invariant & Fail-Closed Drift (CF-P0.11, CF-P0.12, CF-P0.13)**:
   - Enforced snapshot provenance: `t0Report` must match `liveSnapshot.snapshotId()`, returning `NOT_EVALUABLE` on mismatch.
   - Missing version, policy, or topology evaluates to `NOT_EVALUABLE` and aborts.
   - Flap counter rollback/decrease evaluates to `NOT_EVALUABLE`.
7. **Monotonic Clock Health (CF-P0.8)**:
   - Removed `Math.abs()` on wall delta; backward clock steps fail immediately with `ERR_CLOCK_BACKWARD_STEP`.
8. **Typed Transport Delivery Certainty (CF-P0.16)**:
   - Added `DeliveryCertainty` (`DEFINITELY_NOT_SUBMITTED`, `DEFINITELY_REJECTED`, `SUBMITTED_SUCCESS`, `DELIVERY_UNKNOWN`).
   - Transport resets, timeouts, drops unconditionally quarantine as `OUTCOME_UNKNOWN`.
9. **Affirmative `RETURN_TO_SERVICE` Verification (CF-P0.18)**:
   - Checked both members against affirmative vendor-safe ready roles (`ACTIVE`, `STANDBY`, `READY`, `FUNCTIONAL`).
10. **Startup Crash Recovery (CF-P0.20)**:
    - `@EventListener(ApplicationReadyEvent.class)` reconciles any orphaned `CLAIMED_VERIFYING`/`DISPATCHING` rows on startup to `OUTCOME_UNKNOWN` and quarantines the cluster.
11. **Canonical Dual-Control Principals (CF-P0.14)**:
    - `PrincipalCanonicalization` trims, lowercases, and validates principals, rejecting trailing spaces, confusables, and control characters.
12. **AIView Masking Compliance (CF-P0.21)**:
    - Replaced raw member IDs and `ex.getMessage()` with masked names and safe error codes.

---
### Preceding Codex (Astra) Final Comprehensive Re-Review:
```markdown
{codex_final_review_content}
```

---
### Code Base Under Final Review:

#### 1. V31__failover_engine_durable_schema.sql
```sql
{v31_schema}
```

#### 2. FailoverKeyManagementService.java
```java
{key_service}
```

#### 3. PrincipalCanonicalization.java
```java
{principal_canon}
```

#### 4. ClockHealthCheck.java
```java
{clock_check}
```

#### 5. FailoverDriftEngine.java
```java
{drift_engine}
```

#### 6. ScheduleCryptographicService.java
```java
{crypto_service}
```

#### 7. FailoverScheduleEnvelope.java
```java
{envelope}
```

#### 8. BaselineSnapshotSummary.java
```java
{baseline_summary}
```

#### 9. FailoverScheduleRecord.java
```java
{schedule_record}
```

#### 10. FailoverExecutionService.java
```java
{execution_service}
```

#### 11. FailoverScheduleService.java
```java
{schedule_service}
```

#### 12. FailoverScheduleLedger.java
```java
{schedule_ledger}
```

#### 13. DurableQuarantineStore.java
```java
{quarantine_store}
```

#### 14. FailoverBookingAdmissionControl.java
```java
{admission_control}
```

#### 15. FailoverAuthorizationService.java
```java
{authz_service}
```

---
### Final Review Deliverables:
Please evaluate the complete remediated implementation against enterprise security architecture requirements:
1. **P0/P1 Remediation Assessment**: Evaluate the closure of CF-P0.1 through CF-P0.21.
2. **Cryptographic & State Integrity**: Verify the HMAC binding of `actionKind`, deterministic baseline SHA-256 digest, managed non-ephemeral keys, and full ledger chain attribution.
3. **Persistence, Storage & Crash Recovery**: Evaluate the PostgreSQL Flyway schema, real `UNIQUE(grant_id)` constraint, CAS quarantine, and startup crash reconciliation protocol.
4. **Safety Invariants**: Verify the in-lock dynamic deadline checks, fail-closed drift evaluation, single-snapshot gate, typed transport delivery certainty, affirmative return-to-service, and AIView masking.
5. **Final Verdict**: Provide your concrete overall verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by your final security assessment.
"""

    print("Submitting Final Comprehensive Security Review prompt to Claude CLI non-interactively...")
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
