# CP chunked policy and hits landed; real-environment acceptance remains separate

status: in_progress · target: docs/design/PO_DECISION_RECORD_2026_10_02_CP_POLICY_PARALLEL_SESSIONS.md

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): CP chunked policy and hits landed; real-environment acceptance remains separate

Target (full): docs/design/PO_DECISION_RECORD_2026_10_02_CP_POLICY_PARALLEL_SESSIONS.md

2026-10-20: progress, still open for real-environment acceptance. Scheduled Check Point runs collect changed-only on every domain after the one-domain trial ran clean (full baseline at the 06:00 window, reused at 12:00 with no publish in between; 55a6ab2a); manual runs stay full and reuse is bounded by 24 h. Seven approved read gates and the revision and change-evidence tables landed (V163, f62b7c67). Check Point hit dates were null on every rule because the API time object was not parsed; fixed (e4f5f1ae), a collected zero counter reads Never. Authority: docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md.

2026-11-10 (plan refresh): the FULL Check Point policy collection COMPLETED live on 2026-11-05 (NAT by package UID, V192) and again on 2026-11-09 after the MDS fingerprint was accepted once (V196). Hit dates are parsed (PR 644). Acceptance of the hit values themselves is not recorded.
