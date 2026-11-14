# Failover pilot allowlist is hardcoded in a constructor and seeds AIView pseudonyms of real fleet devices instead of being recorded configuration

status: planned · target: FailoverPilotAllowlist

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Failover pilot allowlist is hardcoded in a constructor and seeds AIView pseudonyms of real fleet devices instead of being recorded configuration

2026-11-14 backlog truth: DONE. Evidence: PR #544 (b6c76b79): FailoverPilotAllowlist resolves recorded enrolment, empty by default; PR #670 per-cluster allow-list. Allowlist is configuration, not a constructor seed
