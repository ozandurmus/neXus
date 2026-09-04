# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-04, branch `claude/op2-execution-foundation-5wu5a2`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op2_a_b_execution_foundation` — **DONE** (see "Active build"). `OP.0b`'s
  full S1–S9 read-only scope stays **CLOSED** (predecessor build). `now_next.next`
  = `op0b_0_close_d_v3a_d_v7b_pre_class2` (real-env, independent of `OP.2`).
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` **IMPLEMENTED 2026-09-04**
  (see "Active build") — CLASS 2 still has **no member**, still not
  reachable: no command approved, no vendor adapter, unconditional `DENY`.
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

**`op2_a_b_execution_foundation`** — **DONE**, 2026-09-04. Implemented the
vendor-independent typed action lifecycle, durable `action_id`,
operational-HA-entity lock/quarantine, confirmation binding, mutation
boundary and `OUTCOME_UNKNOWN` recovery from the frozen `OP.2.0` contract, in
new package `utils/operate/` — zero device I/O, no vendor adapter, no
transport import, no command text, no CLI/argv entry point, no console job
type. CLASS 2 stays structurally unreachable: `authorize()` is unconditional
`DENY` at `create_action` (no record is ever created on `DENY`), and
independently eligibility fails `no_adapter_capability` since no adapter
exists anywhere in the product. Closes backlog `ha_entity_operational_lock`
(`OP.0b.0` §26 row X-1). 67 targeted tests
(`tests/test_op2_a_b_execution_foundation.py`), real-thread-concurrency
proofs for the guarded boundary transition and the entity-lock create race,
crash-reconciliation coverage for every state in the contract's table.
Detail: `docs/history/phase/OP_2_A_B_EXECUTION_FOUNDATION.md`.

**Predecessor `op0b_s9_ui_authority_reconciliation`** — DONE, 2026-09-04:
retired the S9 remainder's three UI-side HA heuristics in favor of canonical
backend data; closed `OP.0b`'s full S1–S9 read-only scope. Detail:
`docs/history/phase/OP_0B_S9_UI_AUTHORITY_RECONCILIATION.md`.

**S8-A CP ClusterXL: PASS. S8-B'' VSX and S8-C PAN: both REAL_ENV_VALIDATED**
(`op0b_s8c_pan_dedicated_ha1_real_env_correction`) — PAN B2 stays **NOT
ESTABLISHED** (pair correspondence `MATCH`, but serial-based `B2` itself
unresolved). **OP.0b closure: full read-only S1–S9 scope CLOSED 2026-09-04**
— `D-V3a`/`D-V7b`/`D-F3`/PAN B2/CLASS-2 gates remain correctly,
intentionally open (none are read-only gaps inside `OP.0b`'s own scope; see
the S8-C phase doc's "OP.0b closure assessment").
**Stalled, `now_next.upcoming`:** `cp_remote_collection_done_marker_diagnostics`
— independent, does not block `OP.0b`; resume on a real recurrence.
**Predecessors:** `op0b_s8a_clusterxl_execution_model_console_parity` through
`op0b_s1_preflight_fact_provenance_model` — `project/build_history.json`.

## `OP.0b.0` — FROZEN WITH REAL-ENV VALIDATION GATES

`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md` is
now cleared as implementation authority for the bounded S0–S9 slice sequence
it already defines — citable for command/schema/identity-model
*interpretation*, but **still authorizes no CLASS 2 action** (P4 invariant
unchanged; `OP.0b.1` command-gate package still separately required before
any command is approved). `D-V4`/`D-V7a` are `CLOSED_BY_DOCS`. Every other
row's minimal safe interpretation is explicitly frozen — `D-V1`/`D-V2`
(field-binding confirmed, fail-closed predicates); `D-V5a` (minimal
count/reason/time contract) / `D-V5b` (not load-bearing, dropped); `D-V6`
(pnote problem/no-problem via `-ia list`); `D-V9a` (already-frozen non-VS0
rule) / `D-V9b` (real-env, non-blocking either way). **`D-V3a`/`D-V7b` stay
genuinely `STILL_UNKNOWN`** but were already scoped by the contract's own
pre-existing text as **CLASS-2-time blockers, not architecture blockers**.
New non-blocking decision: `D-F3` (flap/failover threshold, parallel to
`D-F1`/`D-F2`). `D-V8` remains open, non-blocking. Full reasoning:
`project/roadmap.json` `open_decisions`; contract §"Final semantic blocker closure — session 4".

## PAN HA serial evidence

The approved real PAN pair's S0 result: both devices were directly
identity-gated successfully; one member's `self_identity_consistent` and
`runtime_peer_serial_state` are `MATCH`, the other's are both `MISMATCH`.
**B2 bidirectional corroboration: NOT ESTABLISHED.** Root cause: **UNKNOWN**
— representation divergence, a genuine runtime discrepancy, and another
semantic mismatch are all still possible; whitespace/numeric-conversion
causes are ruled out by source inspection. Leading-zero normalization is
**not authorized** (opaque-identifier law). Tracked as `project/backlog.json`
`pan_serial_representation_identity_evidence_closure`.

**2026-09-04 addendum:** a manual (non-authoritative) `show
high-availability all` observation appears to conflict with the `MISMATCH`
above — not reconciled, B2 stays NOT ESTABLISHED. S8-C separately
established genuine fresh **management-plane** (not serial) correspondence
= `MATCH`, a narrower question never promoted toward B2 — see the S8-C phase doc.

## Exact next build

`now_next.next` is **`op0b_0_close_d_v3a_d_v7b_pre_class2`** (promoted from
`upcoming`) — try an official GitHub mirror first (as closed `D-V4`/`D-V7a`),
falling back to a human fetching the contract's named source pages. Gates
only the PAN successor identity model and CLASS 2 (PAN-7, CP-3); blocks
nothing else. `Sonnet 5, extended thinking (high)`.

`cp_remote_collection_done_marker_diagnostics` (`upcoming`) needs a real
recurrence with the new diagnostic fields, independent of `OP.0b`. Backlog
(PO request): `cp_preflight_ccp_tablestat_evidence` — a NEW command, gate
row + readiness mapping required first. Also independent, any order:
**B.** `D-F3` flap threshold — product-owner call. **C.** PAN serial
identity closure — hardware-blocked, in tension with a manual 2026-09-04
observation (see above), not reconciled.

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | architecture FROZEN 2026-09-04 (`OP.2.0`); implementation blocked on every `FAILOVER_ENGINE_ARCHITECTURE.md` §10 prerequisite incl. `OP.2.1`, `DEPLOY.1A` OIDC + `OPERATE` role | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-environment evidence.

## Real-environment validation owed

- **`CON.2`** — trigger a `read`-class job from the console against a real
  device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — one real-device confirmation that `ha_cluster_mode`
  resolves rather than falling back to `"unknown"`. Fixture-drift check, not
  a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial
  evidence" above; tracked as its own next technical movement (B), not
  folded into an unrelated build.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-a-real-MDS evidence for the Postgres advisory-lock path. Server-blocked.

## Automated test baseline

```
1681 passed / 26 skipped / 0 failed (2026-09-04, serial baseline,
  op0b_s8c_pan_dedicated_ha1_real_env_correction); not rerun for
  op0b_s9_ui_authority_reconciliation -- targeted 24 (test_merge_
  characterization, test_phase0_5_3_cluster_hierarchy_ui,
  test_phase0_6_0a4_3_configuration_ui, test_frontend_module_composition)
  + broader sweep 532 (-k "failover or pan_ha or op0b_s8 or vsx or vsls or
  op0a or inventory or merge or configuration_ui") + convergence/render-
  harness/UI-contract sweep 38 passed/1 skipped, per the bounded-change
  ladder (three named files plus their direct test coverage).
Repository privacy gate: FAIL / 3, all the known gitignored data/logs/
  .support_hmac.key runtime-artifact finding (confirmed untracked, not
  repository content).
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
gate (fast PR `validate` job + `full-regression` on main push/manual
dispatch — see "Active build" above); it runs no device, container or
registry step.
