# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law/rule/lifecycle detail here** —
see `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build detail lives in
`project/build_history.json`** (structured, newest-first) and linked docs
under `docs/history/`; `docs/history/INDEX.md` is the one-line timeline.

- **Checkpoint:** 2026-09-13, the Check Point discovery domain core is
  implemented in Java and merged; transport is next.
  `docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md` (**FROZEN — PO APPROVED
  2026-09-13**) is the sole authority for CP/VSX discovery semantics;
  `docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`
  (DRAFT, discovery-only) maps where the existing Python fuses discovery
  and collection. §9 checks 8/10/12/13 are real JUnit tests in
  `ui2/platform-core` (`com.securityexpert.nexus.ui2.discovery.cp`); checks
  7/9/11/14-18 need a management server; twelve `UNKNOWN`s stay open.
  **Transport (§3) is deliberately unimplemented**, one isolated
  field-binding site, every entry `UNVERIFIED`, pending PO confirmation.
- **UI 2.0 stays incomplete and the row stays open.** Shell: navigation
  rail, top app bar, six routed screens against the Product Owner's
  Material 3 frames; `M3Tabs` is a real tab control, one panel per tab
  across four screens. Default screen is the empty state; populated
  screens sit behind the labelled preview route; per-screen fidelity is
  the Product Owner's call. `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`
  and `UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` are FROZEN **by the
  agent, not Product Owner reviewed**
  (`docs/design/UI2_0_AGENT_FROZEN_CONTRACT_AUDIT.md`, DRAFT); no B1 row
  has real-environment evidence. Detail: `project/build_history.json`.
- **UI 2.0 runs on plain Kubernetes.**
  `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` is
  FROZEN, PO approved; `ui2_b1_12_deployment_slice` is AUTOMATED_VALIDATED:
  in-cluster OCI build, no host toolchain, plain `kubectl` manifests,
  PostgreSQL 16 migrations `V1`-`V7`, clean empty first state, no tracked
  credential, arbitrary UID/group 0 (carries unchanged to the corporate
  platform, where a `Route` replaces the `Ingress`). Decisions:
  `docs/design/PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md`.
- **COLLECTION GATE — HELD, ONE BOUNDED LIFT:** no vendor data-collection
  proceeds until the PO specifies, per vendor, collection type and
  methods (`docs/design/PO_DECISION_RECORD_2026_09_12.md` section 1); UI
  2.0 shell work is exempt. **Lifted 2026-09-13 for Check Point *discovery
  only*** (`docs/design/PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md`):
  candidate enumeration by four management-plane methods, **no device
  contacted**; Palo Alto untouched, per-vendor statement still required.
- **Implementation language:** new features are Java, written from
  scratch; existing Python scripts are know-how only, never ported or
  wrapped (same record, section 2).
- **Next** (`now_next.next`): `M12` — per-device/per-capability schedules
  (`D5` producer); no device contact or write implied. `collect_now`
  **AUTOMATED_VALIDATED 2026-09-08**, real-device confirmation pending PO
  execution. `op2_c_cp_clusterxl_adapter_scoping` stays
  `upcoming`/`blocked`; `event_signal_intake` stays `in_progress` —
  cross-vendor timeline (0.8.x) and real network exposure of
  `signal_intake/` are later work. `DEV.TEST.1`/`PCP.1`/`M1`-`M11`/`M7` are
  complete/automated_validated/real_env_validated.
- **OP.2.0 CLASS 2 architecture**
  (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` IMPLEMENTED; `OP.2.1`
  CP command gate DRAFTED — CLASS 2 has **no member**, no adapter,
  unconditional `DENY`; `D-V7b`/`D-F3`/`D-F2` no longer block readiness
  (`OP.2.1b`); remaining blockers are authorization/trust/adapter/
  change-management, not readiness.
- **Baselines:** product `0.7.7 — Compliance trend retro-fill`
  AUTOMATED_VALIDATED; engineering `DEV.3.3` AUTOMATED_VALIDATED
  (`DEV.1`/`DEV.4` complete); product evidence `0.6.1B.1.2` interactive CP
  config REAL_ENV_VALIDATED.
- **PO operating decisions:** durable routing, OpenRouter disposition,
  Graphify scope policy, worker status format, and queue-runner gap:
  `docs/reference/COPILOT_OPERATING_MODEL.md`; `AI_HANDOVER.md` is only
  the cold-session pointer.

## Reading this file / safety status

`project/roadmap.json` owns NOW/NEXT/AFTER/BLOCKED/DEFERRED;
`project/feature_registry.json` owns feature delivery state;
`project/backlog.json` owns open debt (`project/archive/backlog_terminal.json`
owns terminal debt, never loaded at cold start, GOV.ORCH.9);
`project/build_history.json` owns history. This file owns only the hot
checkpoint and the sections below, and must not contradict them —
`utils/project_plan._cross_authority_warnings` plus
`tests/test_architecture_convergence.py` enforce it. Action taxonomy:
`utils/action_taxonomy.py` is the source of truth, full table in
`AI_START_HERE.md`, test-enforced boundaries in `AGENTS.md`
"Architectural invariants".

## Active build

`ui2_d1_restore_c7_c2_amendments` — `automated_validated`.
`UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` applied; restore stays
disabled in Java. Frozen successors replace the drafts in C1–C4/C7's and
B1's authority chains; `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §6/§9
block `REAL_ENV_VALIDATED` for all B1. Rows: `project/QUEUE.md`.

Predecessors, all **MERGED**: `project/build_history.json` /
`docs/history/INDEX.md`, including `M3`
(`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`,
COMPLETE/FROZEN, PO approved 2026-09-06) and `OP.0b.0` (FROZEN WITH
REAL-ENV VALIDATION GATES,
`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`).
PAN HA serial evidence: "Open blockers" below.

## Next candidate and open mapping question

`now_next.next` is unset (see checkpoint above). A future PLAN episode
must (1) size/authorize `M10.2` or another candidate and (2) decide
whether `utils/device_identity_relationships.py`'s `mapping_scope` may
widen beyond `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` to give `D4` a real
join — `RELAY_DECISION` #11 left undecided. `M8.3` is `real_env_validated`
(see "Real-environment validation owed"); `M7` unblocked, not started;
`operator_assertion` unaccepted; `op2_c_cp_clusterxl_adapter_scoping`
stays `upcoming`/blocked; `OP.2.D`'s console flow belongs on the `PCP.4`
device/HA tab, never a second one.

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | root cause `UNKNOWN` (`docs/design/PAN_HA_SERIAL_IDENTITY_HARDENING_DECISION.md`), not a side effect | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6`, `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run | hardware |
| `OP.2` controlled failover execution | architecture FROZEN, readiness unblocked (`OP.2.1b`); CP ClusterXL adapter/transport/preflight (`OP.2.C`) IMPLEMENTED, unwired; review DRAFTED, unsigned (`docs/history/phase/OP_2_C_CHANGE_MANAGEMENT_NETWORK_SECURITY_REVIEW.md`) — needs `DEPLOY.1A`/`OPERATE`, SSH trust hardening, sign-off, a protected entry point | multiple |
| `DEPLOY.1` gates | server availability | external |
| `inventory_exclusions_management_ui_backend` | `in_progress` **by design** — no write function is HTTP-reachable before `DEPLOY.1A`'s OIDC/RBAC boundary | design |
| `M12` per-device schedules | needs `M7` (unblocked, not started; `M8.3` paid) | in-scope, not a deferral |

Concurrency budget stays at 1 per vendor pending real-env evidence.

## Real-environment validation owed

- **`M8.3`** — **PAID 2026-09-08**: real `--identity-first-contact` run,
  `relationship_id e4671bc45e5e49e2bf9257d32b3fa067`, PO-executed, `M7`
  unblocked. Detail: `project/backlog.json` id
  `m8_3_real_environment_validation`.
- **`CON.2`** — trigger a `read`-class console job against a real device,
  no new code. **`OP.0a`/`OP.0c`** — real-device confirmation
  `ha_cluster_mode` resolves, not `"unknown"`; fixture-drift, not safety.
- **PAN HA (`OP.0a.P7`/`OP.0b.0`)** — see "Open blockers" above.
  **`RB.3b`** — the watched single-gateway run. **`DEV.3.2`** — real
  multi-container-against-real-MDS Postgres advisory-lock evidence,
  server-blocked.
- **`CE.2`** (`relay/NXS-LOCAL-0037`) / **`diagnostic_runbooks_read_only`**
  (`PCP.8`, `relay/NXS-LOCAL-0045`) — `CE.2`'s primitives
  `cp_gaia_show_version_all`/`pan_show_system_info` are AUTOMATED_VALIDATED
  only; a real device/Panorama run is owed before `--compliance-probe`
  promotion; `PCP.8` inherits the same debt. No live device reachable.

## Automated test baseline

```
ui2_b0_c5_amendments_bundle: full one-shot regression (.venv/bin/python -m
pytest -q -n auto --dist worksteal, 170.64s): 3214 passed, 27 skipped, 2
failed -- both pre-existing DLP-token collisions
(tests/test_dev_0_5b_auth_consumer_canonical_config.py against
relay/NXS-LOCAL-0030-credential-profiles-reference-model.json and other
committed prose), unchanged from prior. Earlier baselines:
`project/build_history.json`.
```

## Known xfails

None currently known (two earlier ones became passing regressions in
`0.6.6A`; record here if either resurfaces).

## Production posture

Development-ready, **not** production-ready; the container runs as root by
design. Open before any production claim: OIDC/RBAC, trusted TLS/SSH in
production, database role separation, report-only publication surface,
secret management, off-host recovery custody with a restore drill, audit
retention. `.github/workflows/validation.yml` is the deterministic CI gate
(fast PR `validate`, automatic; parallel `full-regression` via
`workflow_dispatch` only, `DEV.TEST.1`); no device, container or registry
step runs there.
