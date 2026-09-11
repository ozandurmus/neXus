# ui2_b0_c2_job_execution_contract — UI2 B0/C2 -- job execution contract (DRAFT, for Product Owner freeze)

## Summary

New docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md: job record, a nine-state closed state machine with OUTCOME_UNKNOWN as terminal-until-reconciled, Postgres lease/heartbeat/fencing-token claiming, step-attempt-before-contact with per-action-class retry rules, a six-check ordered pre-execution battery, schedule optimistic concurrency, and owner/approver/execution-identity separation resolving brief SR-D6. Documentation-only; no code, no schema migration, no device execution.

## Evidence

Relay-authorized movement (.nexus/approved_task.json SESSION_START, content-hash-verified against the Product Owner's own relay entry). Implements docs/design/UI2_0_BASELINE_CONTRACT.md (FROZEN -- PRODUCT OWNER APPROVED 2026-09-09) decisions JOB-UNCERTAIN-OUTCOME (D-2a), UI-OPERATIONAL-RUN-NOW (D-2b) and APPROVAL-MODEL (D-2c), and acceptance sentence A-2. Read before writing: UI2_0_BASELINE_CONTRACT.md, UI2_0_DEVELOPMENT_WORKFLOW.md (§3.2/§3.4), UI2_0_ARCHITECTURE_DESIGN.md (§5.4, §6.2-§6.7), UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md (§6.3, §6.7, SR-D6), RECOVERY_OPERATIONAL_WRITE_LEDGER.md, utils/action_taxonomy.py, utils/operate/states.py + record.py (read as reference only, per the task's own instruction -- OP.2.0 is a single-process class-2 pattern, not ported). Self-checked against AC-1..AC-10 in the document's own §9/§10. No code changed; docs/project-state tests, full one-shot regression (3216 passed, 0 failed), git diff --check and the repository privacy gate (0 new findings vs. origin/main) all green before PR. Runs concurrently with C1 (docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md, PR #163, merged).

## Risks forward

The document is DRAFT -- FOR PRODUCT OWNER FREEZE, not yet implementation authority (AGENTS.md Authority hierarchy item 2). Lease-duration/heartbeat-interval (60s/20s) and the read-class retry budget (2 retries) are proposed defaults, not vendor-derived, named in the doc's own §10 as tunable without changing the load-bearing rules. SR-D6's resolution (owner/approver/execution-identity separation per Astra, rather than the brief's original 'last schedule editor' proposal) is flagged as an open item for explicit PO confirmation, not asserted as already decided. Depends on C1's lifecycle column names (now merged, PR #163) and on C4 for the closed step-kind vocabulary this document assumes only the minimal interface of.
