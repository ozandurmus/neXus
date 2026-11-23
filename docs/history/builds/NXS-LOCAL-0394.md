# NXS-LOCAL-0394 — VSX chassis failover, Script Execution slice 2, failover authority recheck, database roles step 0, aiview read-only admin slice 1

## Summary

VSX chassis failover (2bdb6008, V255/V256), the Check Point per-VS cpstat policy gate (3ad90651, V254), Script Execution slice 2 with a per-run pod and masked audited output (084c89d7, V258) and the worker-start recheck of initiator and approver authority (4501dff4, V260) are merged. Database roles step 0 adds SECURITY DEFINER triggers and views (594c811e, V261), the aiview masked read-only admin switch ships default off (26330958), and ASA and FortiGate pairs are first-class HA rows (c84f5d7d).
