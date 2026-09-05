# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-05, branch `claude/pcp1-device-registry-gqp7s2`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `pcp_1_device_registry_manual_enrollment_foundation` — **IMPLEMENTED**,
  ceiling `AUTOMATED_VALIDATED` pending fast PR CI (see "Active build").
  `now_next.next` is `pcp_2_local_control_plane_sequencing_po_review`
  (blocked on Product Owner review, not started, not pre-designed);
  `op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`, blocked on
  `DEPLOY.1`.
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` IMPLEMENTED; `OP.2.1` CP
  command gate DRAFTED — CLASS 2 still has **no member**, no adapter,
  unconditional `DENY`. `D-V7b`/`D-F3`/`D-F2` no longer block the readiness
  roll-up (`OP.2.1b`, see "Active build") — remaining `CLASS 2` blockers are
  authorization/trust/adapter/change-management, not readiness.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1`,
  `DEV.4` complete.
- **Product evidence baseline:** `0.6.1B.1.2` interactive CP config
  collection is REAL_ENV_VALIDATED.

## Reading this file

`project/roadmap.json` owns NOW / NEXT / AFTER / BLOCKED / DEFERRED.
`project/feature_registry.json` owns feature delivery state.
`project/backlog.json` owns debt. `project/build_history.json` owns history.
This file owns only the hot checkpoint above and the sections below, and it
must not contradict them — `utils/project_plan._cross_authority_warnings`
plus `tests/test_architecture_convergence.py` fail the build if it does.
Durable engineering/security law is never here — see `AGENTS.md`.

## Safety status — the action taxonomy

`utils/action_taxonomy.py` is the single source of truth; `AI_START_HERE.md`
carries the full table; `AGENTS.md` "Architectural invariants" carries the
test-enforced boundaries. Current numbers:

| Class | Permitted? | Where |
| --- | --- | --- |
| 0 — read | yes | everywhere; most of the product |
| 1 — controlled recovery write | yes, **only** under the `RB.x` contracts | CLI only; never console-submittable |
| 2 — operational state change (failover) | **no member exists**; architecture frozen (`OP.2.0`), not implemented | hard-gated, `FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1/§10.2 |
| 3 — configuration write | prohibited | — |
| 4 — policy / deployment / remediation | prohibited | — |

## Active build

**`pcp_1_device_registry_manual_enrollment_foundation`** (`PCP.1`) —
**IMPLEMENTED**, ceiling `AUTOMATED_VALIDATED` pending fast PR CI.
Implements `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §21 exactly:
`utils/device_registry.py` (opaque `device_id`; endpoint normalization with
no DNS resolution; vendor-hint- and lifecycle-state-independent duplicate
detection; `ENROLLED_UNVERIFIED`/`DISABLED` reachable, `RETIRED`/
`CONTACT_VERIFIED`/`OBSERVED` structurally unreachable; closed schema;
`credential_ref` format-validated reference only, never resolved;
fail-closed corrupt-data handling; the registry mutation lock with an
`owner_token` instance-safe release); the eighth `utils/evidence_backend.py`
concern `DeviceRegistryBackend` (filesystem-only); `--registry-enroll` /
`--registry-list` / `--registry-disable` CLI modes
(`application/cli.py` + `application/workflows/registry.py`), mode-
exclusive, no vendor import, no credential resolution, no network;
`tests/test_pcp1_device_registry.py` (AC-1a..AC-15). Sandbox has no
`pytest`/`lxml`/`paramiko` (reported per `CLAUDE.md`); every behavior was
hand-verified directly instead — see `project/build_history.json` head
record for the exact evidence and the open risk that CI must still confirm
the suite green before the ceiling is claimed. No device contact, no
console/UI/payload change, no SQLite/PostgreSQL. `AI_START_HERE.md` §22
item 4 (deferred from the `PCP.0` freeze) landed this session.

Predecessors (full detail: `project/build_history.json` + linked phase
docs): `product_control_plane_architecture_draft` (`PCP.0`, FROZEN),
`op2_c_change_management_review_package_draft` (review package DRAFTED,
unsigned), `op2_c_release_gate_dependency_scoping`, `op2_c_cp_
clusterxl_preflight_eligibility_wiring`, `op2_c1_cp_clusterxl_member_
session`, `op2_1b_cp_pilot_readiness_policy_amendment`, `op2_1_cp_
clusterxl_command_gate`, `op2_a_b_execution_foundation` — all DONE/
AUTOMATED_VALIDATED. PAN B2 stays **NOT ESTABLISHED**.

## `OP.0b.0` — FROZEN WITH REAL-ENV VALIDATION GATES

`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
is implementation authority for the bounded S0–S9 slice sequence it defines
— citable for command/schema/identity-model *interpretation*, but **still
authorizes no CLASS 2 action**. `D-V4`/`D-V7a` are `CLOSED_BY_DOCS`. Every
other row's minimal safe interpretation is frozen — `D-V1`/`D-V2`
(field-binding, fail-closed predicates); `D-V5a`/`D-V5b`; `D-V6` (pnote
via `-ia list`); `D-V9a`/`D-V9b`. **`D-V3a`/`D-V7b` stay `STILL_UNKNOWN`**
as vendor facts — `D-V3a` (PAN) still scoped as a CLASS-2-time blocker;
`D-V7b` (CP) no longer blocks the readiness roll-up at all (`OP.2.1b`,
2026-09-05: advisory-exempt, see "Active build") even though the
underlying vendor question is unchanged. `D-F3` (flap threshold) is
**DECIDED** (2026-09-05): no threshold invented, advisory-exempt
permanently, both vendors. `D-V8` remains open, non-blocking. Full
reasoning: `project/roadmap.json` `open_decisions`.

## PAN HA serial evidence

The approved real PAN pair's S0 result: one member's `self_identity_
consistent`/`runtime_peer_serial_state` are `MATCH`, the other's both
`MISMATCH`. **B2 bidirectional corroboration: NOT ESTABLISHED**, root cause
**UNKNOWN** (representation divergence / genuine discrepancy / another
mismatch all still possible; whitespace/numeric-conversion ruled out).
Leading-zero normalization **not authorized** (opaque-identifier law).
Tracked as `pan_serial_representation_identity_evidence_closure`. A manual
2026-09-04 `show high-availability all` observation conflicts with the
`MISMATCH` above — not reconciled, B2 stays NOT ESTABLISHED; S8-C separately
established fresh **management-plane** (not serial) correspondence = `MATCH`,
a narrower question never promoted toward B2.

## Exact next build

`now_next.next` is **`pcp_2_local_control_plane_sequencing_po_review`**:
not `PCP.2` implementation itself — a Product Owner review of whether/when
a local interactive console ships and whether/when the filesystem-only
registry evolves toward SQLite (`pcp_storage_engine` stays open), and how
both relate to the still-open `pcp_console_registry_write_gate` decision.
`PCP.1`'s CLI verbs stay a bounded maintenance/bootstrap adapter, not the
Operator Console device experience, until this sequencing is decided. Not
started, not pre-designed, not pre-authorized. `Sonnet 5, extended thinking
(high)` once the Product Owner is ready to decide; `Sonnet 5, normal` for
any CI-status follow-up on `PCP.1` itself in the meantime.

`op2_c_cp_clusterxl_adapter_scoping` (`upcoming`, blocked, notes preserved):
adapter, real `ClusterXLMemberSession` and real `PreflightProvider`/
`EligibilityEvaluator` all IMPLEMENTED + unit-tested, none wired;
`DenyAllAuthorizer`/no taxonomy member keep CLASS 2 unreachable. Still
waits on `DEPLOY.1A` OIDC + `OPERATE`, CP SSH trust hardening (both on
`DEPLOY.1`, external), the review's sign-off (drafted, unsigned —
`docs/history/phase/OP_2_C_CHANGE_MANAGEMENT_NETWORK_SECURITY_REVIEW.md`)
and a protected entry point. `OP.2.D`'s console flow is expected to live on
the `PCP.4` device/HA tab (one console, never two).

`op0b_0_close_d_v3a_d_v7b_pre_class2` (`upcoming`): purely a vendor-fact
question now (D-V7b's readiness role decided; D-V3a/PAN B2 remain the PAN
identity blocker, `OP.3`). `cp_remote_collection_done_marker_diagnostics`
(`upcoming`) needs a real recurrence. PAN serial identity closure —
hardware-blocked (see above).

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | architecture FROZEN; readiness no longer blocks (`OP.2.1b`); CP ClusterXL adapter (`OP.2.C`), its real `ClusterXLMemberSession` transport, and its real `PreflightProvider`/`EligibilityEvaluator` now all IMPLEMENTED + unit-tested, all unwired — change-management/network-security review now DRAFTED but unsigned (`docs/history/phase/OP_2_C_CHANGE_MANAGEMENT_NETWORK_SECURITY_REVIEW.md`) — blocked on `DEPLOY.1A`/`OPERATE`, SSH trust hardening, this review's sign-off, a protected entry point | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-env evidence.

## Real-environment validation owed

- **`CON.2`** — trigger a `read`-class job from the console against a real
  device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial
  evidence" above; its own next technical movement, not folded in here.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence. Server-blocked.

## Automated test baseline

```
Prior baseline 1825 passed / 24 skipped / 0 failed -- NOT re-run this
  session (no pytest/lxml/paramiko in this sandbox, per CLAUDE.md). New
  tests/test_pcp1_device_registry.py added, hand-verified directly; fast PR
  CI must confirm the full suite green before AUTOMATED_VALIDATED is
  claimed (build_history record). Project-state consistency verified
  directly: metadata_warnings == []; build_history_index.py --check clean.
Repository privacy gate: PASS / 0 findings, 487 files scanned (re-run
  directly, 2026-09-05).
```

Run one-shot and read from file: `py -m pytest -q > pytest_result.log 2>&1`;
serially at least once before closing a build; delete gitignored
`data/`/`logs/` before the privacy gate (`AI_START_HERE.md` "Validation ladder").

## Known xfails

None currently known (the two tracked earlier became passing regressions in
`0.6.6A`; record here if either resurfaces).

## Production posture

Development-ready, **not** production-ready. The container runs as root by
design at this stage. Open before any production claim: OIDC/RBAC, trusted
TLS/SSH in production, database role separation, report-only publication
surface, secret management, off-host recovery custody with a restore drill,
audit retention. `.github/workflows/validation.yml` is the deterministic CI
gate (fast PR `validate` + `full-regression` on main push/dispatch); it runs
no device, container or registry step.
