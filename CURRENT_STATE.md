# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-05, branch `claude/cp-clusterxl-preflight-wiring-ft80pc`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op2_c_cp_clusterxl_preflight_eligibility_wiring` — **AUTOMATED_VALIDATED**
  (see "Active build"). `now_next.next` = `op2_c_cp_clusterxl_adapter_scoping`
  (blocked on authorization/trust/change-management/wiring, not readiness).
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

**`op2_c_cp_clusterxl_preflight_eligibility_wiring`** —
**AUTOMATED_VALIDATED**, 2026-09-05. New
`checkpoint/clusterxl_preflight_provider.py`: `ClusterXLPreflightProvider`
(the real `PreflightProvider`) calls `checkpoint.preflight_collector.
run_cp_preflight` then the one canonical `utils.failover.assessment.
compute_ha_readiness` — no second readiness engine — and copies its verdict
verbatim; `ClusterXLReadinessEligibilityEvaluator` maps that verdict onto
`EligibilityResult` by equality against `VERDICT_SAFE` only. The provider
resolves opaque `subject_member_token`/`peer_member_token` from this run's
own fresh `ha_local_role` fact (never member order), translates the CP
vendor cluster-mode token to the adapter's canonical `"ha"`, and always
reports `recovery_mode="unknown"` (`D-V7b`) — every ambiguous/missing/
split-brain case fails closed to no tokens/no cluster_mode. Same-action/
same-entity binding (P4): every returned snapshot is stamped with the
caller's own `action_id`/`operational_entity_id`. No production
`ActionCoordinator`, adapter resolver, entry point, UI, trust change, or
device command added — CLASS 2 stays structurally unreachable
(`DenyAllAuthorizer` unchanged, re-asserted by test). Detail:
`checkpoint/clusterxl_preflight_provider.py` docstring,
`tests/test_op2_c_cp_clusterxl_real_preflight_wiring.py` (24 tests: binding,
canonical verdict mapping, fail-closed evidence, adapter eligibility
handoff).

Predecessors (full detail: `project/build_history.json` + linked phase
docs): `op2_c1_admin_down_pnote_safety_corrections`, `op2_c1_cp_clusterxl_
member_session`, `op2_1b_cp_pilot_readiness_policy_amendment` (`D-V7b`/
`D-F3`/`D-F2` advisory-exempt), `op2_1_cp_clusterxl_command_gate`,
`op2_a_b_execution_foundation` — all DONE. S8-A/S8-B''/S8-C real-env
validated; PAN B2 stays **NOT ESTABLISHED**.

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

`now_next.next` is **`op2_c_cp_clusterxl_adapter_scoping`**. The typed
adapter, the real `ClusterXLMemberSession` transport, and the real
`PreflightProvider`/`EligibilityEvaluator` are now all IMPLEMENTED and
unit-tested, none wired into a production `adapter_resolver`/
`ActionCoordinator` — `DenyAllAuthorizer`/no taxonomy member still keep
CLASS 2 unreachable. Remaining before a real-env pilot: `DEPLOY.1A` OIDC +
`OPERATE`, CP SSH host-key trust hardening, the signed change-management
review, and a protected entry point constructing both live (using
`RealClusterXLMemberSession`/`ClusterXLPreflightProvider`/
`ClusterXLReadinessEligibilityEvaluator`). `Sonnet 5, normal` for wiring;
extended thinking only for a genuine new authorization/trust decision.
Detail: `checkpoint/clusterxl_capability_adapter.py`,
`checkpoint/clusterxl_member_session.py`, and
`checkpoint/clusterxl_preflight_provider.py` docstrings.

`op0b_0_close_d_v3a_d_v7b_pre_class2` (`upcoming`, demoted from `next`
2026-09-05): now purely a vendor-fact question — D-V7b's readiness-roll-up
ROLE is decided (advisory-exempt); D-V3a/PAN B2 remain the PAN identity
blocker (`OP.3`, out of scope for CP). Try an official GitHub mirror first
(as closed `D-V4`/`D-V7a`), falling back to a human fetching the contract's
named source pages.

`cp_remote_collection_done_marker_diagnostics` (`upcoming`) needs a real
recurrence, independent of `OP.0b`. Backlog (PO request):
`cp_preflight_ccp_tablestat_evidence` — a NEW command, gate row required
first. Also independent, any order: PAN serial identity closure —
hardware-blocked, not reconciled with the manual 2026-09-04 observation
(see above).

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | architecture FROZEN; readiness no longer blocks (`OP.2.1b`); CP ClusterXL adapter (`OP.2.C`), its real `ClusterXLMemberSession` transport, and its real `PreflightProvider`/`EligibilityEvaluator` now all IMPLEMENTED + unit-tested, all unwired — blocked on `DEPLOY.1A`/`OPERATE`, SSH trust hardening, change-management review, a protected entry point | multiple |
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
1825 passed / 24 skipped / 0 failed (2026-09-05, serial baseline,
  op2_c1_admin_down_pnote_safety_corrections).
Focused: test_op2_c1_cp_clusterxl_member_session.py (28) + test_op2_c_cp_
  clusterxl_adapter.py (33) + test_op2_1_cp_clusterxl_command_gate.py +
  test_op2_a_b_execution_foundation.py + test_architecture_convergence.py
  (157 total), all green.
Repository privacy gate: FAIL / findings, all known gitignored data/logs/
  .support_hmac.key runtime artifacts (confirmed untracked, not repository
  content).
Project-state consistency: metadata_warnings == [] under all cross-authority rules.
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
