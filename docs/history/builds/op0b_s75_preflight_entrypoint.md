# op0b_s75_preflight_entrypoint — OP.0b S7.5 - Controlled preflight application entrypoint

## Summary

S8 proved S5/S6 were unreachable through the controlled application boundary; this slice adds the bounded seam. Two closed CLI modes (--cp-ha-preflight-check --cp-preflight-targets, --pan-ha-preflight-check --pan-preflight-targets) resolve one explicit HA operational entity and its exact bounded physical members (<=2) from already-collected local inventory only -- fail-closed before any device contact, reusing the existing exact CP entity_id / PAN serial selectors (configuration.checkpoint_config_collector._apply_cp_target_selector, configuration.panorama_config_collector._apply_pan_target_selector) -- then invoke run_cp_preflight/run_pan_preflight exactly once and hand the resulting PreflightSnapshot, unmodified and unpersisted, straight into the canonical utils.failover.compute_ha_readiness. New utils.failover.derive_ha_units export (pure refactor of compute_ha_readiness internal unit derivation, no behavior change) lets the application layer resolve the same operational_unit_id the readiness evaluator will independently derive, so PAN pairing continues to rely on the existing config-intent pair derivation without redesigning B2 (a two-member request that only partially matches a known pair fails closed). No new device command, API operation, retry, fallback collector, or persisted preflight artifact; CLASS 2 stays unreachable.

## Evidence

- tests/test_op0b_s75_preflight_entrypoint.py -- 46 passed (CLI surface, fail-closed CP/PAN target resolution, zero-collector-invocation-on-failure, S5/S6-invoked-exactly-once composition, fresh-snapshot-XOR-legacy-telemetry, no-retry, no-persistence, structural no-arbitrary-command / no-verdict-rollup / no-raw-identifier-disclosure proofs, derive_ha_units/compute_ha_readiness derivation parity)
- tests/test_op0b_s5_cp_preflight_collector.py, test_op0b_s6_pan_preflight_collector.py, test_op0b_s7_readiness_v2.py, test_op0a_ha_readiness.py, test_op0c_failover_readiness_ui.py, test_op0b_s1_preflight_model.py, test_application_package.py, test_ci_workflow_fast_pr_regression.py -- 341 passed
- full local suite: 1418 passed / 26 skipped / 0 failed (pytest/lxml/paramiko/requests + console extras installed session-locally, no repository dependency change)
- py main.py --repository-privacy-check -- PASS, 0 findings

## Risks forward

- No device contact in this implementation session (REAL_ENV_VALIDATION_PROTOCOL): S8-A owns the first live run of both new CLI modes against the approved real-env pairs.
- PAN B2 (bidirectional pair-identity corroboration) remains NOT ESTABLISHED, unchanged by this slice -- pairing continues to rely on the existing mutual configured peer-ip agreement (Grade A, configuration intent), and a request that only partially matches a known pair fails closed rather than guessing.
- CP single-member preflight (one of a two-member ClusterXL/VSX cluster) is accepted when the caller explicitly selects only the reachable member; the resulting snapshot still evaluates against the full inventory-derived unit, so cross-member checks correctly stay INSUFFICIENT_EVIDENCE rather than fabricating a peer observation.
