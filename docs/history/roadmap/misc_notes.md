# roadmap — additional long fields

## RB.3a — note

Read-only 'show backups'/'show snapshots' per physical endpoint; populates the attestations input of compute_restore_readiness. checkpoint/checkpoint_recovery_attestation.py + utils.recovery_collect.run_recovery_attestation (RecoveryAttester, not a RecoveryCollector) + data/state/recovery_attestations.json + main.py --recovery-attest. 33 tests (AC-1..AC-10); full suite 804 passed / 2 pre-existing unrelated failures. Real Gaia validation owed (on_hardware_real_env_validation). See project/backlog.json native_backup for full evidence.

## op_four_eyes — note

Rebased from 'OP.2 contract freeze' on 2026-09-04, not resolved. PO classification 2026-09-04: NOT an OP.2.0 architecture freeze blocker -- the architecture provides one approval_policy boundary in the confirmation gate (second approver, role combinations, maintenance window, change reference are its inputs); whether production requires one approver, two, or a role combination is a deployment/release policy decision. No generic quorum framework is built. Initial implementation: one confirmation by the requesting operator, no configuration surface. See docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md P5.

## op_emergency_evac — note

Rebased from 'OP.2 contract freeze' on 2026-09-04, not resolved. PO classification 2026-09-04: NOT an OP.2.0 architecture freeze blocker -- the initial CLASS 2 architecture has NO emergency bypass: no path bypasses authorization, fresh preflight, confirmation or the operational lock. A future emergency capability requires its own explicit contract and approval. See docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md 'Scope -- out'.

## op_aa_vsls_scope — note

Rebased from 'OP.2 contract freeze' on 2026-09-04, not resolved. PO classification 2026-09-04: NOT an OP.2.0 architecture freeze blocker -- Active/Active and VSLS semantics are outside the initial CLASS 2 scope; no VSLS assumption may enter the initial CP ClusterXL adapter (OP.2.C). OP.0 still assesses them. See docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md 'Identity invariants'. UPDATE 2026-09-04 (OP.0b S4-A'): real-env S8-B evidence establishes the approved VSX pair actually runs VSLS (Cluster Mode: Virtual System Load Sharing); OP.0b now collects per-VS readiness evidence and evaluates each VSID as an independent readiness unit (docs/history/phase/OP_0B_S4A_VSX_PER_VS_FAILOVER_DOMAIN_REVIEW.md). This decision's CLASS 2 scope is unchanged -- still deferred, still no VSID adapter, still OP.3 territory -- readiness and CLASS 2 execution are separate questions and only the former has moved.

## D-V3b — note

2026-09-04 (OP.0b S8-C session): the operator additionally captured `show high-availability all` manually (outside the approved S8-C battery, not runtime-authorized) and reported reciprocal serial correspondence on both members. NOT treated as closing this decision -- not independently re-verified by this session's own code path, and appears to conflict with the earlier S0 finding (one member MISMATCH, project/backlog.json pan_serial_representation_identity_evidence_closure). Recorded as potentially useful future evidence only; root cause of the earlier discrepancy remains UNKNOWN and is not reconciled here.
