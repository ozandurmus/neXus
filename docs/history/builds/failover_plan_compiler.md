# failover_plan_compiler — OP.1.S1 -- write-free failover plan compiler and dry-run (classic CP ClusterXL only)

## Summary

New utils/failover_plan/ package (model.py/compiler.py/dry_run.py) compiles a FailoverPlan + DryRunReport for one CP ClusterXL unit purely from already-collected OP.0a/OP.0b evidence, zero-I/O by construction (a poison session_resolver proves the two reused checkpoint.clusterxl_capability_adapter.CPClusterXLCapabilityAdapter methods are never given a real one); new main.py --failover-plan-dry-run [--failover-plan-unit UNIT_ID] mirrors --ha-readiness-check's offline shape. Full detail: project/backlog.json's failover_plan_compiler note.

## Evidence

Relay-authorized movement (.nexus/approved_task.json SESSION_START, content-hash-verified against the Product Owner's own relay entry). Implements docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md (FROZEN -- PRODUCT OWNER APPROVED 2026-09-09) section 9's single slice, all eight acceptance criteria, via new tests/test_op1_failover_plan_compiler.py (33 tests). One pre-existing test's exclusion list updated for a contract-anticipated construction site (tests/test_op2_c_cp_clusterxl_adapter.py::test_adapter_module_is_not_referenced_by_any_production_coordinator_construction now also allows utils/failover_plan/; its real invariant -- no production ActionCoordinator wiring -- is independently re-proven by this build's own AC-6 test). Targeted regression (.venv at <redacted-local-root>/neXus/.venv): tests/test_op1_failover_plan_compiler.py + tests/test_architecture_convergence.py + tests/test_op2_c_cp_clusterxl_adapter.py + tests/test_op0a_ha_readiness.py + tests/test_op0b_s7_readiness_v2.py, 228 passed; CLI-adjacent suites (test_gov_po_3_ci_privacy_gate_baseline, test_m5_collector_target_selection_seam, test_local_relay_protocol, test_m8_3_first_contact_cli, test_op0b_s75_preflight_entrypoint, test_op0b_s8_real_cli_path_regression, test_op0d_deterministic_target_selection, test_pcp8_diagnostic_runbooks_cli, test_pcp1_device_registry), 329 passed. Full one-shot regression run in the foreground and awaited to actual completion before PR.

## Risks forward

OP.2 (execution of a compiled plan) remains separately gated and unimplemented at production scope -- this build authorizes nothing and constructs no ActionCoordinator. Whether the compiled plan/dry-run is ever surfaced on the OP.0c Failover dashboard module is a later, separate UI decision, not implied here.
