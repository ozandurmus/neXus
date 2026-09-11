# op0b_s7_readiness_v2_integration — OP.0b S7 - Readiness v2 integration

## Summary

Fresh S1/S5/S6 PreflightSnapshot evidence now feeds the ONE canonical readiness evaluator (utils.failover.compute_ha_readiness -> _verdict_for) through a new typed fact->check mapping module (utils/failover/preflight_readiness.py, FACT_CHECK_MAP: 14 vendor x check specs over the unchanged seven stop-conditions and five verdicts) -- no second verdict engine, no UI logic, no device contact, schema string unchanged (securityexpert-ha-readiness-v1, additive per-unit evidence/provenance disclosure only). Evidence laws are test-enforced (UNKNOWN/COLLECTION_FAILED/UNSUPPORTED never PASS and never KNOWN_BAD; positive evidence required; coherence/attribution/identity/mode prerequisites block positives without fabricating failure), D-F1/D-F2/D-F3/D-V7b/B2 stay unresolved and fail-closed (SAFE and DEGRADED remain unreachable -- proven over a generated snapshot matrix), the OP.0a PAN phantom-member uplift is removed (AC-5) and a one-sided OP.0a read is now INSUFFICIENT_EVIDENCE rather than a fabricated no_viable_target.

## Evidence

- tests/test_op0b_s7_readiness_v2.py -- 53 passed (task items 1-40 + single-authority, zero-I/O, SAFE/DEGRADED-unreachable matrix, fresh-preflight-XOR-legacy-telemetry guard)
- tests/test_op0a_ha_readiness.py, test_op0c_failover_readiness_ui.py, test_op0b_s1_preflight_model.py, test_op0b_s2_pan_projection.py, test_op0b_s3_cp_projection.py, test_op0b_s5_cp_preflight_collector.py, test_op0b_s6_pan_preflight_collector.py, test_architecture_convergence.py, test_known_safety_gaps.py -- 265 passed
- full local suite: 1371 passed / 26 skipped / 0 failed (serial; pytest/lxml/paramiko/requests + console extras installed session-locally, no repository dependency change)
- py main.py --repository-privacy-check -- PASS, 0 findings
- git diff --check clean; metadata_warnings == []
- PR #44: fast CI validate green; PO architecture approval 2026-09-03 with one added regression guard (evidence-source exclusivity, both vendors)

## Risks forward

- PO decision (2026-09-03): seven-check contract KEPT -- no eighth top-level check, no top-level 5a/5b split; the frozen contract's proposed check-8 / 5a-5b evidence is carried by checks 1/5 with distinct reason codes (critical_device_problem_observed, member_failure_state_observed, member_non_functional_state_observed, ha1_link_down_observed, ha2_link_down_observed, monitored_path_down_observed). Recorded in contract §25c.
- PO decision (2026-09-03): readiness schema identifier stays securityexpert-ha-readiness-v1; 'readiness v2' is the build movement name, not a wire/schema version. Every S7 record change is additive.
- SAFE_TO_FAILOVER and DEGRADED_PROCEED_WITH_RISK remain unreachable by construction: CP -- check 6 (A9 not authorized, D-V7b) and check 7 (D-F3); PAN -- check 7 (D-F3); the roll-up additionally refuses SAFE while D-F2 (skew) / D-F1 (intent freshness) apply. Not a defect.
- S8 real-env validation still owed: vendor value vocabularies the mapping freezes minimally (PAN state-sync 'Complete', running-sync 'synchronized', conn-* 'up'/'down', *-compat 'Match'/'Mismatch'; CP sync 'ok'/'not_ok') are fail-closed on anything else and must be confirmed against real output.
- Nothing persists or orchestrates snapshots yet: compute_ha_readiness(preflight_snapshots=...) and build_failover_readiness_payload(preflight_snapshots=...) are the typed evaluation stage; an explicit collect-then-evaluate workflow is future scope (no CLI/console wiring in S7, no automatic device contact behind evaluation).
