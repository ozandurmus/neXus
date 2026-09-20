#!/usr/bin/env python3
"""Execute Stage 2: Durable Storage, Managed Keys & Dual-Control Architecture using Claude Sonnet High CLI.

This script invokes Claude Code CLI (--model sonnet --effort high --dangerously-skip-permissions)
to implement the durable Flyway schema, PostgreSQL persistence, managed key service,
and startup crash reconciliation across ui2/service.
"""

import os
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

PROMPT = """You are acting as the Lead Implementation Engineer using Claude Sonnet High for the neXus project.
Your task is to implement Stage 2: Durable Storage, Managed Keys & Dual-Control Architecture for the Failover Engine across ui2/job-engine and ui2/service.
You have full tool access to read, edit, and write files in the repository. Make exact, production-grade code edits directly.

### Stage 2 Hardening Requirements:

1. **Flyway Migration V31 (`ui2/service/src/main/resources/db/migration/V31__failover_engine_durable_schema.sql`)**:
   - Create tables:
     - `failover_schedules`:
       `schedule_id TEXT PRIMARY KEY, cluster_ref TEXT NOT NULL, masked_cluster_name TEXT NOT NULL, vendor TEXT NOT NULL, command_family_id TEXT NOT NULL, action_kind TEXT NOT NULL, signed_mutation_target TEXT NOT NULL, window_start TIMESTAMPTZ NOT NULL, window_end TIMESTAMPTZ NOT NULL, max_start_delay_minutes INT NOT NULL, execution_deadline TIMESTAMPTZ NOT NULL, requester_id TEXT NOT NULL, approver_id TEXT NOT NULL, grant_id TEXT NOT NULL UNIQUE, baseline_digest TEXT NOT NULL, baseline_json JSONB NOT NULL, envelope_signature TEXT NOT NULL, status TEXT NOT NULL, client_nonce TEXT NOT NULL, scheduled_at TIMESTAMPTZ NOT NULL, claimed_at TIMESTAMPTZ, executed_at TIMESTAMPTZ, execution_result_id TEXT, abort_reason_code TEXT, abort_reason TEXT, cancelled_by TEXT, cancelled_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 1`
     - `failover_grant_consumption`:
       `grant_id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL, attempt_id TEXT NOT NULL, consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()`
     - `failover_quarantine`:
       `cluster_ref TEXT PRIMARY KEY, execution_id TEXT NOT NULL, reason TEXT NOT NULL, quarantined_member_ids TEXT[] NOT NULL DEFAULT '{}', quarantined_at TIMESTAMPTZ NOT NULL DEFAULT now(), acknowledged_at TIMESTAMPTZ, acknowledged_by TEXT, second_approver_id TEXT, review_notes TEXT, active BOOLEAN NOT NULL DEFAULT true`
     - `failover_schedule_ledger`:
       `id BIGSERIAL PRIMARY KEY, entry_index BIGINT NOT NULL UNIQUE, prev_hash TEXT NOT NULL, entry_hash TEXT NOT NULL, schedule_id TEXT NOT NULL, from_status TEXT, to_status TEXT NOT NULL, attempt_id TEXT NOT NULL, actor_id TEXT NOT NULL, details TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
   - Add appropriate indices on cluster_ref, status, window bounds, and schedule_id.

2. **Durable Managed Key Resolver (CF-P0.5, CF-P1.2)**:
   - Add status enum `ABORTED_KEY_UNAVAILABLE` to `FailoverScheduleStatus.java`.
   - Create `FailoverKeyManagementService.java` in `com.securityexpert.nexus.ui2.service.failover`:
     - Reads master secret from environment `NEXUS_FAILOVER_MASTER_SECRET`, system property `nexus.failover.master-secret`, or persists/reads deterministically from local storage file (`.nexus_failover_master.key`).
     - NEVER generates an ephemeral random key that gets wiped on JVM restart!
     - Provides key lookup by `keyId` ("k1"). If key is unavailable, returns empty optional, allowing the service to fail cleanly with `ABORTED_KEY_UNAVAILABLE` (not false `ABORTED_TAMPERED`).

3. **Durable Repositories & State Synchronization (CF-P0.7, CF-P1.4)**:
   - Update `FailoverScheduleLedger.java`:
     - Use Spring `JdbcTemplate` to persist and load ledger entries and grant consumptions into/from `failover_schedule_ledger` and `failover_grant_consumption`.
     - Support fallback to in-memory store if DB is absent during lightweight unit tests, but default to database execution.
     - `consumeGrant` catches unique constraint violation and translates to single-use grant invariant exception.
   - Update `DurableQuarantineStore.java`:
     - Back active quarantines and audit history with `failover_quarantine` table via `JdbcTemplate` (with CAS on `execution_id`).
   - Update `FailoverScheduleService.java`:
     - Back `schedules` storage with `failover_schedules` table via `JdbcTemplate`.

4. **Startup Crash Recovery & Reconciliation Protocol (CF-P0.20)**:
   - In `FailoverScheduleService.java`:
     - Add `@EventListener(ApplicationReadyEvent.class)` / `reconcileOrphanedAttemptsOnStartup()`:
     - Scan `failover_schedules` for records left in `CLAIMED_VERIFYING` or `DISPATCHING`.
     - Reconcile their status to `OUTCOME_UNKNOWN` with reason code `ERR_SERVICE_RESTART_RECONCILIATION` and engage sticky quarantine on the cluster.

5. **Dual Control Canonical Principal Hardening (CF-P0.14)**:
   - In `FailoverScheduleService.java` and `DurableQuarantineStore.java`:
     - Canonicalize principal strings: `principal.trim().toLowerCase(Locale.ROOT)`.
     - Reject if canonical requester matches canonical approver, or if strings contain control characters, tabs, or trailing spaces.

6. **Booking Admission Exact Bounds (CF-P1.14)**:
   - In `FailoverBookingAdmissionControl.java`:
     - Exact rolling 24-hour volume check: count schedules where `existing.windowStart()` falls within `[request.windowStart() - 24h, request.windowStart()]`.
     - Directional cooldown check: compute gap in both directions (earlier window end -> later window start).

7. **Unit & Integration Tests**:
   - Update and extend tests in `ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/failover/`:
     - Verify database migration compiles and runs.
     - Verify grant consumption rejects duplicates.
     - Verify startup reconciliation moves orphaned claimed schedules to OUTCOME_UNKNOWN.
     - Verify key manager persists key across service restarts.

Proceed and implement all Stage 2 changes now.
"""


def main() -> int:
    claude_bin = "/Users/OzanDur/.local/bin/claude"
    if not Path(claude_bin).exists():
        claude_bin = "claude"

    print("Launching Claude Sonnet High for Stage 2 implementation...")
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
