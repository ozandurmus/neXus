# UI2 B1-11 -- acceptance scenario B: worker killed mid-step -> OUTCOME_UNKNOWN, no duplicate device contact, recovery path visible

status: automated_validated · target: workflow §5 B1-11

Acceptance sentence A-2.

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): UI2 B1-11 -- acceptance scenario B: worker killed mid-step -> OUTCOME_UNKNOWN, no duplicate device contact, recovery path visible

2026-11-14 backlog truth: still open (P3, size S). Today: No end-to-end worker-kill test; components tested separately. Evidence: No named end-to-end test; JobReconcilerTest, ReadClassBoundedRetryTest cover OUTCOME_UNKNOWN pieces

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commit 'Acceptance scenario B (worker loss): end-to-end test; fence late outcome writes to EXECUTING jobs' (WorkerLossAcceptanceScenarioTest); also 'Add the human reconciliation step for OUTCOME_UNKNOWN jobs (C2 3.5)'.
