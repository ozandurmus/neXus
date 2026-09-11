# ui2_b0_c4_capability_registry_gate_resolution_contract — UI2 B0/C4 -- capability registry & command-gate resolution contract (DRAFT, for Product Owner freeze)

## Summary

New docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md: the runtime capability registry schema (transport, closed 8-member step-kind set, KNOWN/UNKNOWN/NOT_APPLICABLE field states, shared-fact reuse) as the target of workflow §3.1's extraction spec; the K-4 gate-registry resolution algorithm (exact-match, ambiguity fails closed, action_class derived from the gate row) with an execution-eligible-view mechanism so UNKNOWN blocks device execution but never the offline spec/parser; the CP-D6 VSX/ClusterXL target model (composite identity over one physical device_id, never a VS-scoped devices row); the CP-D7 transport decision (ssh_exec default, with the exact Line-1 evidence bar for ssh_interactive). Documentation-only; no code, no schema migration, no device execution.

## Evidence

Relay-authorized movement (.nexus/approved_task.json SESSION_START, content-hash-verified against the Product Owner's own relay entry). Implements council items K-3, K-4, CP-D6, CP-D7 (UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md §4) under UI2_0_BASELINE_CONTRACT.md (FROZEN 2026-09-09). Read before writing: UI2_0_BASELINE_CONTRACT.md, UI2_0_DEVELOPMENT_WORKFLOW.md §3.1-§3.3, UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md §3, UI2_0_C2_JOB_EXECUTION_CONTRACT.md §5, UI2_0_ARCHITECTURE_DESIGN.md §6.3, BACKUP_RECOVERY_CONTRACTS.md §7.3/§7.4/§7.7/§7.8 (the real RB.3b command tuple used as the K-3-corrected worked example, replacing design §6.3's invented show-backup-status-polling sample). Self-checked against AC-1..AC-10 in the document's own §7/§8. No code changed; runs concurrently with C3 (out of scope here) and shares the freeze slice with C6 (extraction contract, not yet dispatched) per FREEZE-SLICING.

## Risks forward

The document is DRAFT -- FOR PRODUCT OWNER FREEZE, not yet implementation authority. The gate-registry sign_off_state vocabulary and the xml_api_call step-kind addition are this document's own proposals, flagged as open items for the PO in §8 rather than pre-existing repository convention. VSX/ClusterXL target-model columns on a capability's own projection table are deferred to that capability's own later C1-successor migration, not added to cp_inventory_projection now. Depends on C6 (not yet dispatched) to actually populate the extraction-spec fields this registry's slots reference.
