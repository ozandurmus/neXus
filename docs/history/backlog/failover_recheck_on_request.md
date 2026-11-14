# A failover request must re-run every readiness check at that moment; an earlier green never authorizes

status: planned · target: PO rule 2026-09-12; OP.2 contract

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): A failover request must re-run every readiness check at that moment; an earlier green never authorizes

2026-11-14 backlog truth: DONE. Evidence: PR 546 request-bound rechecks, PR 549; CpFailoverServiceTest warning/request flow. Request re-runs readiness; earlier green not reused
