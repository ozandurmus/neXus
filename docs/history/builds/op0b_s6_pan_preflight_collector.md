# op0b_s6_pan_preflight_collector — OP.0b S6 - Palo Alto dedicated preflight collector

## Summary

New panorama/preflight_collector.py implements the dedicated Palo Alto failover-preflight collector (S5's CP sibling), strictly within the PO-frozen OP.0b.1 command gate (docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md, Approval record, PR #41): for one caller-selected, bounded (<=2 member) PAN HA pair, one direct API key per member reused for P1 (show system info, identity gate, exact serial comparison) + P2 (show high-availability state, existing S2 extraction/projection unchanged) + P4 (show high-availability path-monitoring, new), one preflight_run_id, no application-level command retry. New panorama/pan_preflight_battery.py (fixed typed command plan, a deterministic guard proving P3/P5 and every rejected mutating PAN operation are absent by construction) and panorama/pan_preflight_extraction.py (one pure fail-closed parser for P4); panorama/pan_preflight_projection.py gains project_pan_identity_fact/project_pan_path_monitoring_facts on the existing S2 seam. No readiness verdict, no raw output persisted, no new API session shape/credential path/TLS policy, B2 stays NOT ESTABLISHED (no pair-identity redesign, no serial normalization). tests/test_op0b_s6_pan_preflight_collector.py (41 tests) plus S1/S2/S4/architecture-convergence regression, the affected configuration-marked subsystem regression (399 passed), the full local suite (1318 passed / 26 skipped / 0 failed, serial), and the repository privacy gate (0 findings) all green locally. No PR/CI evidence recorded here yet.

## Evidence

- tests/test_op0b_s6_pan_preflight_collector.py -- 41 passed
- tests/test_op0b_s1_preflight_model.py, test_op0b_s2_pan_extraction.py, test_op0b_s2_pan_projection.py, test_op0b_s4_command_gate.py -- 88 passed (unaffected)
- tests/test_architecture_convergence.py -- passed, metadata_warnings == []
- pytest -q -m configuration -- 399 passed, 1 skipped (affected PAN/configuration subsystem regression)
- full local suite: 1318 passed / 26 skipped / 0 failed (serial, py -m pytest -q)
- py .\main.py --repository-privacy-check -- PASS, 0 findings

## Risks forward

- Real-env validation owed for P1/P2/P4 (S8) -- exact real PAN-OS path-monitoring response shape/vocabulary is UNKNOWN pending a real device read; the extraction parser is fail-closed on anything unrecognized.
- D-T1 (direct vs Panorama-proxy transport) is resolved as 'direct for every row' inside this collector only -- a non-blocking, revisitable choice, not a new frozen contract decision.
- D-V3a (PAN HA serial identity) and B2 (bidirectional pair-identity corroboration) both remain unresolved/NOT ESTABLISHED -- this build does not touch pair-identity model or serial normalization.
- S7 (readiness v2 integration) is the next dependency, now unblocked -- both S5 and S6 dedicated preflight collectors exist.
