# NXS-LOCAL-0422 — Policy Insights I7 safe reorder and reorder-only proposal engine, zone matrix ZM-26 fix and traceability reconciliation, SarParser fix, aiview topology context masking, delivery runner hardening

## Summary

Policy Insights gained I7 safe reorder with a per-move proof (e942a3f7) and a pure reorder-only proposal engine, not wired to a route (016e715e); zone matrix NOT_EVALUABLE rules no longer project a bare zero headline (33c26833), the scale harness and the 7.1 tab conformance landed (d9e8c0e3), and the traceability tables of zone matrix and Script Execution were reconciled with their tests (26753874, 0aeb6cc6). Also SarParser BAD_NUMBER rows (5e8cef7a), aiview masking of topology link contexts (de37c67a), the launcher test flake root cause on the build Mac (5e8cef7a), lane and ship runner fixes, and a DRAFT Policy Insights P3 storage contract; automated validation only.
