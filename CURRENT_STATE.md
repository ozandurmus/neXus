# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law/rule/lifecycle detail here** —
see `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build detail lives in
`project/build_history.json`** (structured, newest-first) and its linked
docs under `docs/history/`; `docs/history/INDEX.md` is the one-line timeline.

- **Checkpoint:** 2026-09-13, the Check Point discovery domain core is implemented in Java and merged; its transport layer is the next build; its design is complete and its gate open. `docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md` (**FROZEN — PO APPROVED 2026-09-13**, review of record in its status block) decides Check Point and VSX discovery from behaviour measured against a live management server: ten candidate kinds identified by flags and field presence, one host-resolution invariant over two address fields, member-to-cluster joins on stable identifiers and never on names, and liveness refused with evidence — three management-plane signals disproved and a three-plane negative search recorded, plus a connection-table channel state carried under its own name and explicitly not liveness. `docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` (DRAFT) maps what the existing Python does and where discovery and collection are fused in it. **FROZEN — authority for discovery only**, bounded by the gate lift. Its §9 checks 8/10/12/13 are now real JUnit tests in `ui2/platform-core` (`com.securityexpert.nexus.ui2.discovery.cp`); checks 7/9/11/14-18 still need a management server. The twelve `UNKNOWN`s stay open. **Transport (§3) is deliberately unimplemented**: the field binding is one isolated site, every entry `UNVERIFIED`, pending a Product-Owner-run confirmation.
- **UI 2.0 stays incomplete and the row stays open.** The shell carries a navigation rail, a top app bar and six routed screens against the Product Owner's Material 3 frames, and `M3Tabs` is now a real tab control with one panel per tab across four screens. Default screen stays the empty state; populated screens stay behind the labelled preview route. Per-screen fidelity against the frames remains the Product Owner's call. `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` and `UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` remain FROZEN **by the agent, not Product Owner reviewed** (`docs/design/UI2_0_AGENT_FROZEN_CONTRACT_AUDIT.md`, DRAFT). No B1 row has real-environment evidence. Detail: `project/build_history.json`.
- **UI 2.0 runs on plain Kubernetes.** `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` is FROZEN — Product Owner approved after review — and `ui2_b1_12_deployment_slice` is AUTOMATED_VALIDATED: an OCI image built inside the cluster with no host toolchain, plain manifests driven with `kubectl`, PostgreSQL 16 with migrations `V1`-`V7`, the required clean empty first state, and no credential in any tracked file. The image runs under a platform-assigned arbitrary UID with group 0, which is what carries it unchanged to the corporate platform; a `Route` replacing the `Ingress` is the only manifest difference. Runtime decisions: `docs/design/PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md`.
- **COLLECTION GATE — HELD, WITH ONE BOUNDED LIFT:** no vendor data-collection or extraction work proceeds until the Product Owner specifies, per vendor, collection type and methods (`docs/design/PO_DECISION_RECORD_2026_09_12.md` section 1). UI 2.0 shell work is exempt. **Lifted 2026-09-13 for Check Point *discovery only*** (`docs/design/PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md`): candidate enumeration by four management-plane methods, **no device contacted**. Every device-facing path stays gated, and Palo Alto is untouched — section 1 requires a per-vendor statement.
- **Implementation language:** new feature implementation is Java written from scratch; the existing Python scripts are know-how only, never ported or wrapped (same record, section 2).
- **Next** (`now_next.next`): `M12` — per-device / per-capability schedules (`D5` producer). No device contact or real write is implied.
  collect_now` **AUTOMATED_VALIDATED 2026-09-08**, real-device confirmation
  pending Product Owner execution. `op2_c_cp_clusterxl_adapter_scoping`
  stays `upcoming`/`blocked`. `event_signal_intake` stays `in_progress` —
  cross-vendor timeline (0.8.x) and any real network exposure of
  `signal_intake/` remain later, separately-decided work.
  `DEV.TEST.1`/`PCP.1`/`M1`-`M11`/`M7` complete/automated_validated/real_env_validated.
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` IMPLEMENTED; `OP.2.1` CP
  command gate DRAFTED — CLASS 2 still has **no member**, no adapter,
  unconditional `DENY`. `D-V7b`/`D-F3`/`D-F2` no longer block the readiness
  roll-up (`OP.2.1b`) — remaining `CLASS 2` blockers are authorization/
  trust/adapter/change-management, not readiness.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1`/`DEV.4` complete.
- **Product evidence baseline:** `0.6.1B.1.2` interactive CP config is REAL_ENV_VALIDATED.
- **PO operating decisions:** durable routing, OpenRouter disposition, Graphify scope policy, worker status format, and queue-runner gap are recorded in `docs/reference/COPILOT_OPERATING_MODEL.md`; `AI_HANDOVER.md` is only the cold-session pointer.

## Reading this file

`project/roadmap.json` owns NOW / NEXT / AFTER / BLOCKED / DEFERRED.
`project/feature_registry.json` owns feature delivery state.
`project/backlog.json` owns open debt; `project/archive/backlog_terminal.json` owns terminal debt and is never loaded at cold start (GOV.ORCH.9). `project/build_history.json` owns history.
This file owns only the hot checkpoint above and the sections below, and it
must not contradict them — `utils/project_plan._cross_authority_warnings`
plus `tests/test_architecture_convergence.py` fail the build if it does.

## Safety status — the action taxonomy

`utils/action_taxonomy.py` is the single source of truth; `AI_START_HERE.md`
carries the full table; `AGENTS.md` "Architectural invariants" carries the
test-enforced boundaries.

## Active build

`ui2_d1_restore_c7_c2_amendments` — `automated_validated`.
`UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` applied; restore stays
disabled in Java. Frozen successors replace the drafts that stood in C1–C4/C7's
and B1's authority chains; `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §6/§9
block `REAL_ENV_VALIDATED` for all B1. Rows: `project/QUEUE.md`.

Predecessors, all **MERGED**: `project/build_history.json` / `docs/history/INDEX.md`.

## Predecessor — `M3`
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` — COMPLETE / FROZEN (PO approved 2026-09-06).

## `OP.0b.0` — FROZEN WITH REAL-ENV VALIDATION GATES
`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`.

## PAN HA serial evidence
`docs/design/PAN_HA_SERIAL_IDENTITY_HARDENING_DECISION.md`; see "Open blockers" below.

## Next candidate and open mapping question

`now_next.next` is unset (see checkpoint above). A future PLAN episode
needs to (1) size/authorize `M10.2` or another candidate and (2) decide
whether `utils/device_identity_relationships.py`'s `mapping_scope` may
widen beyond `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` to give `D4` a real
join — `RELAY_DECISION` #11 left that undecided. `M8.3` is now
`real_env_validated` (2026-09-08, see "Real-environment validation owed")
and `M7` is unblocked (not yet started). `operator_assertion` stays unaccepted.
`op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`/blocked; `OP.2.D`'s
console flow is expected on the `PCP.4` device/HA tab, never a second one.

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (`docs/design/PAN_HA_SERIAL_IDENTITY_HARDENING_DECISION.md`) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | architecture FROZEN; readiness no longer blocks (`OP.2.1b`); CP ClusterXL adapter (`OP.2.C`), its real `ClusterXLMemberSession` transport, and its real `PreflightProvider`/`EligibilityEvaluator` now all IMPLEMENTED + unit-tested, all unwired — change-management/network-security review now DRAFTED but unsigned (`docs/history/phase/OP_2_C_CHANGE_MANAGEMENT_NETWORK_SECURITY_REVIEW.md`) — blocked on `DEPLOY.1A`/`OPERATE`, SSH trust hardening, this review's sign-off, a protected entry point | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |
| `M12` per-device schedules | needs `M7` completed (unblocked, not yet started -- `M8.3`'s real-env debt is paid) | in-scope work, not a deferral |

Concurrency budget stays at 1 per vendor pending real-env evidence.

## Real-environment validation owed

- **`M8.3`** — **PAID 2026-09-08**: real `--identity-first-contact` run, `relationship_id e4671bc45e5e49e2bf9257d32b3fa067`, Product-Owner-executed. `M7` unblocked. Full detail: `project/backlog.json` id `m8_3_real_environment_validation`.
- **`CON.2`** — trigger a `read`-class job from the console against a real device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial evidence" above; its own movement. **`RB.3b`** — the watched single-gateway run. **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence, server-blocked.
- **`CE.2`** (`compliance_check_engine_primitives`, `relay/NXS-LOCAL-0037`) — the two proof primitives (`cp_gaia_show_version_all`, `pan_show_system_info`) are AUTOMATED_VALIDATED only; a real device/Panorama run confirming their actual output shape against the registry's redaction rule is owed before promotion beyond opt-in `--compliance-probe` mode. No live device reachable from this workspace.
- **`diagnostic_runbooks_read_only`** (`PCP.8`, `relay/NXS-LOCAL-0045`) — the catalog/validator layer is AUTOMATED_VALIDATED; it executes entirely through `CE.2`'s own primitives above, so it inherits the same real-device output-shape debt and stays owed together with it. No live device reachable from this workspace.

## Automated test baseline

```
ui2_b0_c5_amendments_bundle: full one-shot regression, foreground, awaited
(.venv/bin/python -m pytest -q -n auto --dist worksteal, 170.64s): 3214
passed, 27 skipped, 2 failed -- both pre-existing/unrelated DLP-token
collisions (tests/test_dev_0_5b_auth_consumer_canonical_config.py, tripping
on relay/NXS-LOCAL-0030-credential-profiles-reference-model.json and other
already-committed historical prose), unchanged in kind from the prior
baseline below and untouched by this movement's own diff.

Prior baseline, op0b_s7_s6_test_order_isolation: full one-shot regression
after merging origin/main (through m10_2_capability_state_resolver_core, PR
#132) + tool-gate interpreter fix (+2 tests, DEV.TEST.1): 2943 passed, 25
skipped, 2 failed -- the same DLP-token collisions. S7+S6 both orders: 110
passed each. Earlier predecessor build detail lives only in
project/build_history.json.
```
## Known xfails

None currently known (two earlier ones became passing regressions in
`0.6.6A`; record here if either resurfaces).

## Production posture

Development-ready, **not** production-ready. The container runs as root by
design at this stage. Open before any production claim: OIDC/RBAC, trusted
TLS/SSH in production, database role separation, report-only publication
surface, secret management, off-host recovery custody with a restore drill,
audit retention. `.github/workflows/validation.yml` is the deterministic CI
gate (fast PR `validate`, automatic; parallel `full-regression` via
`workflow_dispatch` only, `DEV.TEST.1`); it runs no device, container or
registry step.
