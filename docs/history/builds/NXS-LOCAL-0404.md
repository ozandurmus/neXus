# NXS-LOCAL-0404 — ASA ACL_NOT_BOUND unknown reason, ASA A0 conformance tests and debt cleanups

## Summary

An ASA ACL named by an unparsed feature is UNKNOWN, not unused (PO 2026-11-23, 65430d84); every A0-ASA amendment entry is pinned by a conformance test. Debt: deterministic snapshot test, dead adminApi exports removed, packet address math extracted.

## Batches

65430d84 (batch auto-1123-2146: lanes 433-p1-asa-acl-not-bound-unknown, 439-debt-parity-det-a-reuse-core-compare, 438-debt-background-snapshot-timing-flake, 436-p1-asa-a0-conformance-tests-retry, 440-debt-adminapi-remove-dead-exports, 442-debt-packet-correlation-split-address-math), 2026-11-23, 21:46. No schema change. Section 16 appended to the ASA/FortiGate vendor semantics amendment.

## Open

Not real-environment validated. The lt/gt/neq port operators contradict A0-ASA-14 and stay one disabled DEFECT test plus a fail-closed guard.
