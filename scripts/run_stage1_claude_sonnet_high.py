#!/usr/bin/env python3
"""Execute Stage 1: Deterministic Code-Level Security Hardening using Claude Sonnet High CLI.

This script invokes Claude Code CLI (--model sonnet --effort high --dangerously-skip-permissions)
to perform the code edits across ui2/job-engine and ui2/service.
"""

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

PROMPT = """You are acting as the Lead Implementation Engineer using Claude Sonnet High for the neXus project.
Your task is to implement Stage 1: Deterministic Code-Level Security Hardening for the Failover Engine across ui2/job-engine and ui2/service.
You have full tool access to read, edit, and write files in the repository. Make exact, production-grade code edits directly.

### Stage 1 Hardening Requirements:

1. **Envelope Action Binding (CF-P0.1)**:
   - File: `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/FailoverScheduleEnvelope.java`
   - Add `actionKind` (String) into the envelope record fields and canonical length-prefixed bytes.
   - Canonical framing: `DOMAIN_SEPARATOR | scheduleId | clusterRef | vendor | commandFamilyId | actionKind | signedMutationTarget | windowStart | windowEnd | maxStartDelayMinutes | requesterId | approverId | grantId | baselineDigest | clientNonce | keyId | algVersion`.

2. **Deterministic Baseline Digest (CF-P0.2)**:
   - File: `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/BaselineSnapshotSummary.java`
   - Implement `computeCanonicalDigest()` calculating a deterministic SHA-256 hash over canonical length-prefixed representation of its fields (`clusterRef`, `vendor`, `haMode`, `activeMemberId`, `standbyMemberId`, `softwareVersion`, `policyHash`, `transitionCounter`, `recordedAt`).
   - Eliminate `"digest-" + UUID.randomUUID()`.

3. **Schedule Record & Dynamic Deadline (CF-P0.3)**:
   - File: `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/FailoverScheduleRecord.java`
   - In constructor, assert `executionDeadline.equals(computeExecutionDeadline(windowStart, windowEnd, maxStartDelayMinutes))`.
   - Ensure `actionKind` is correctly passed and envelope factory method creates envelope with `actionKind.name()`.

4. **Cryptographic Key & Byte Verification (CF-P1.1, CF-P1.3)**:
   - File: `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/ScheduleCryptographicService.java`
   - Enforce `masterSecretKey.length >= 32` bytes.
   - In `verifyEnvelope`, safely decode `expectedHexSignature` into raw 32 bytes (returning false if malformed hex) and compare raw bytes using `MessageDigest.isEqual`.

5. **Drift Engine Fail-Closed Semantics (CF-P0.11, CF-P0.12, CF-P0.13)**:
   - File: `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/schedule/FailoverDriftEngine.java`
   - If `baseline.softwareVersion()` is present but live software version is missing/blank -> `DriftDimensionStatus.NOT_EVALUABLE` with reason `REASON_INSUFFICIENT_EVIDENCE`.
   - If `baseline.policyHash()` is present but live policy hash is missing/blank -> `DriftDimensionStatus.NOT_EVALUABLE` with reason `REASON_INSUFFICIENT_EVIDENCE`.
   - Cluster topology: if live topology cannot be verified -> `NOT_EVALUABLE`.
   - Flap / transition: if `liveTransitions < baseline.transitionCounter()` (indicates reboot/counter rollover) -> `NOT_EVALUABLE` with reason `REASON_CLUSTER_FLAP_DETECTED`.
   - Single snapshot invariant: assert `t0Report.evidenceSnapshot() != null && liveSnapshot.snapshotId() != null && liveSnapshot.snapshotId().equals(t0Report.evidenceSnapshot().snapshotId())`; if mismatched, return `NOT_EVALUABLE`.

6. **Clock Health Check Monotonic Step Detection (CF-P0.8)**:
   - File: `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/checks/ClockHealthCheck.java`
   - Remove `Math.abs()` on `Duration.between(PROCESS_START_WALL, currentWall)`. If negative, fail immediately with `ERR_CLOCK_BACKWARD_STEP`.

7. **Typed Delivery Certainty (CF-P0.16)**:
   - File: `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/failover/execution/FailoverCommandResult.java`
   - Add enum `DeliveryCertainty`: `DEFINITELY_NOT_SUBMITTED`, `DEFINITELY_REJECTED`, `SUBMITTED_SUCCESS`, `DELIVERY_UNKNOWN`.
   - Add `DeliveryCertainty certainty` to `FailoverCommandResult` record and update factory methods.

8. **Hardened In-Lock Mutation & Delivery Handling (CF-P0.10, CF-P0.16, CF-P0.18, CF-P0.21)**:
   - File: `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverExecutionService.java`
   - In-lock deadline recheck: add `Instant executionDeadline` parameter to `executeScheduledFailover` and verify `Instant.now().isBefore(executionDeadline)` immediately before lease consumption and immediately before mutation execution.
   - If command fails with `DELIVERY_UNKNOWN` (timeouts, drops, exceptions), unconditionally engage sticky quarantine as `OUTCOME_UNKNOWN`. Handle null `errorReason` safely without throwing NPE.
   - If command fails with `DEFINITELY_REJECTED`, evaluate `rejectObs`: if members changed roles, engage sticky quarantine as `OUTCOME_UNKNOWN`.
   - `RETURN_TO_SERVICE`: verify affirmative vendor-safe role on both members (e.g. `ACTIVE`, `STANDBY`, `READY`, `FUNCTIONAL`). Do not use negative-list `!DOWN && !SUSPENDED`.
   - AIView masking: replace raw member IDs and raw cluster refs in error/abort messages with masked names (`active.maskedName()`) or opaque summaries. Do not leak raw `ex.getMessage()` containing transport/network IPs into summaries or logs.

9. **Ledger Full Chain Attribution (CF-P0.6)**:
   - File: `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverScheduleLedger.java`
   - In `computeHash`: include `actorId` and sanitized `details` in SHA-256 payload chain:
     `prevHash | index | scheduleId | fromStatus | toStatus | attemptId | actorId | details | timestamp`.

10. **Service Integration**:
    - File: `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/failover/FailoverScheduleService.java`
    - Update `scheduleMaintenanceWindow` and `dispatchScheduledExecution` to wire the new envelope (with `actionKind` and deterministic baseline digest), recompute `executionDeadline`, and pass deadline into `executeScheduledFailover`.

11. **Unit Tests**:
    - Update `ui2/job-engine/src/test/java/com/securityexpert/nexus/ui2/jobs/failover/FailoverScheduleJobEngineTest.java` and `ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/failover/FailoverScheduleServiceTest.java`.
    - Ensure all unit and integration tests compile and pass.

Proceed and make all required code edits now.
"""


def main() -> int:
    claude_bin = "/Users/OzanDur/.local/bin/claude"
    if not Path(claude_bin).exists():
        claude_bin = "claude"

    print("Launching Claude Sonnet High for Stage 1 implementation...")
    try:
        proc = subprocess.Popen(
            [
                claude_bin,
                "--model", "sonnet",
                "--effort", "high",
                "--dangerously-skip-permissions",
                "-p", PROMPT
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            cwd=str(BASE_DIR),
        )
        stdout, stderr = proc.communicate()
    except Exception as e:
        print(f"Failed to execute Claude CLI: {e}", file=sys.stderr)
        return 1

    print("Claude Sonnet High execution output:")
    print(stdout)
    if stderr:
        print("STDERR:\n" + stderr, file=sys.stderr)

    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
