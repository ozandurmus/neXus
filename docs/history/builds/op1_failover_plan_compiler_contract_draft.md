# op1_failover_plan_compiler_contract_draft — OP.1 -- failover plan compiler and dry-run: contract draft (DRAFT -- awaiting Product Owner freeze)

## Summary

Produces docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md (DRAFT -- awaiting Product Owner freeze): a write-free FailoverPlan/DryRunReport contract for classic Check Point ClusterXL only, compiled purely from already-collected OP.0a/OP.0b evidence (utils.failover.assessment.compute_ha_readiness) and reusing OP.2.0/OP.2.1's already-pure, zero-I/O checkpoint.clusterxl_capability_adapter.CPClusterXLCapabilityAdapter.capability()/build_plan() methods -- never check_precondition()/execute_once()/observe_postcondition(), never an ActionCoordinator, never a taxonomy change. Presents op_degraded_verdict to the Product Owner as one decision with the existing recommendation (keep DEGRADED_PROCEED_WITH_RISK structurally unreachable) and the consequence of each option for the plan/dry-run vocabulary; confirms op_four_eyes/op_emergency_evac/op_continuity_tolerance/op_aa_vsls_scope stay non-blocking. Includes a one-slice implementation plan (OP.1.S1, 5 non-test files, 8 acceptance criteria) sized to GOV.PO.1 section 7 targets.

## Evidence

Relay-authorized movement (.nexus/approved_task.json SESSION_START, content-hash-verified against the Product Owner's relay entry). Read FAILOVER_ENGINE_ARCHITECTURE.md sections 1-10.2 and OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md in full before drafting, per the SESSION_START's own requirement. Verified (not assumed) that checkpoint.clusterxl_capability_adapter.CPClusterXLCapabilityAdapter.capability()/build_plan() perform zero I/O by reading their implementation directly (lines 175-278): neither references self._session_resolver; check_precondition()/execute_once()/observe_postcondition() all do and are excluded from this contract's design by construction. No code, test or checkpoint/ / utils/failover/ file touched, per the SESSION_START's explicit scope-out.

## Risks forward

Document is DRAFT only; promotion to FROZEN and the op_degraded_verdict decision remain a separate Product Owner action. Implementation slice OP.1.S1 leaves one narrow open implementation-detail question for its own PLAN step: whether to duplicate or extract-and-share the two private clusterxl_preflight_provider helpers (_map_cluster_mode/_resolve_member_tokens) the new utils/failover_plan/compiler.py needs -- deliberately left unresolved at contract level (see the doc's own S4 note).
