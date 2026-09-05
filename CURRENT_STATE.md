# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-05, branch `claude/checkpoint-clusterxl-mutation-gate-d882mx`.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `op2_1_cp_clusterxl_command_gate` — **DONE** (see "Active build"). `OP.0b`'s
  full S1–S9 read-only scope stays **CLOSED**. `now_next.next` =
  `op0b_0_close_d_v3a_d_v7b_pre_class2` (higher-leverage now, see below).
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` IMPLEMENTED 2026-09-04;
  `OP.2.1` CP command gate DRAFTED 2026-09-05 — CLASS 2 still has **no
  member**, no adapter, unconditional `DENY`, and `D-V7b`/`D-F3` now proven
  (not merely listed) to independently block any positive readiness verdict.
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

**`op2_1_cp_clusterxl_command_gate`** — **DONE (drafted)**, 2026-09-05.
Docs-only network-device command gate, CP ClusterXL only (PAN/VSX/
`DEPLOY.1A`/SSH-hardening not touched — not required to name the CP rows).
New `docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md`
approves `clusterXL_admin down`/`up` (`CP-M1`/`CP-M1-R`, the reversal a
**separate, never-automatic** typed action per P12) as `APPROVED_FOR_OP2C`,
non-persistent only (`-p` deferred); rejects `cphastop`/`cpstop`/reboot/
priority-edit/link-pull/target-side action/Gaia Clish `set cluster member
admin`/Maestro `g_clusterXL_admin`/any preemption write. Postcondition reuses
the already-approved `CP-A5`/`A3` reads — no new command. **Safety finding:**
`D-V7b`/`D-F3` are proven, from `utils/failover/assessment.py::_verdict_for`
and eligibility item 6 / safety-contract item 2 (non-positive verdict not
operator-overridable), to be independent, already-coded reasons a positive
readiness verdict is structurally unreachable for CP — true for a bounded
local pilot exactly as much as production; no acknowledged-but-open path
exists for either. Flags (not resolves) one design-vs-implementation
tension: the design parent called `preemption_known` "not blocking" but
`_verdict_for` treats every stop-condition as equally blocking
(`OP.0a`/`OP.1` territory). No code, no taxonomy member, no device contact.
9 new tests. Detail: `docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md`.

**Predecessor `op2_a_b_execution_foundation`** — DONE, 2026-09-04: the
vendor-independent typed action lifecycle, HA-entity lock/quarantine,
confirmation binding, mutation boundary and `OUTCOME_UNKNOWN` recovery, in
new package `utils/operate/` — zero device I/O, no adapter. CLASS 2 stays
structurally unreachable (`authorize()` unconditional `DENY`; independently
`no_adapter_capability`). Closes backlog `ha_entity_operational_lock`. 67
tests. Detail: `docs/history/phase/OP_2_A_B_EXECUTION_FOUNDATION.md`.

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

`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
is implementation authority for the bounded S0–S9 slice sequence it defines
— citable for command/schema/identity-model *interpretation*, but **still
authorizes no CLASS 2 action**. `D-V4`/`D-V7a` are `CLOSED_BY_DOCS`. Every
other row's minimal safe interpretation is frozen — `D-V1`/`D-V2`
(field-binding, fail-closed predicates); `D-V5a`/`D-V5b`; `D-V6` (pnote
via `-ia list`); `D-V9a`/`D-V9b`. **`D-V3a`/`D-V7b` stay `STILL_UNKNOWN`**,
scoped as **CLASS-2-time blockers, not architecture blockers** — now
sharpened by `OP.2.1` to *proven* readiness-verdict blockers, see "Active
build". `D-F3` (flap threshold) and `D-V8` remain open, likewise `D-F3`
now proven hard-blocking. Full reasoning: `project/roadmap.json`
`open_decisions`.

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

`now_next.next` is **`op0b_0_close_d_v3a_d_v7b_pre_class2`** — try an
official GitHub mirror first (as closed `D-V4`/`D-V7a`), falling back to a
human fetching the contract's named source pages. Sharpened by `OP.2.1`:
`D-V7b`'s half is now a proven (not merely listed) blocker to any positive
CP readiness verdict, bounded pilot included. `Sonnet 5, extended thinking
(high)`.

`cp_remote_collection_done_marker_diagnostics` (`upcoming`) needs a real
recurrence, independent of `OP.0b`. Backlog (PO request):
`cp_preflight_ccp_tablestat_evidence` — a NEW command, gate row required
first. Also independent, any order: **B.** `D-F3` flap threshold —
product-owner numeric-threshold call, likewise now proven a hard blocker
(`OP.2.1`). **C.** PAN serial identity closure — hardware-blocked, not
reconciled with the manual 2026-09-04 observation (see above).

## Open blockers

| What | Blocked on | Kind |
| --- | --- | --- |
| PAN HA serial `B2` establishment | the mismatching member's root cause is `UNKNOWN` (see above) — do not resolve as a side effect of an unrelated build | investigation + hardware |
| `CON.3` console operational-write actions | open decisions `C-D4`, `C-D6` **and** `RB.3b` | decision + hardware |
| `RB.3b` CP Gaia backup collection | the watched real R81.10/R81.20 run — hardware, not engineering | hardware |
| `OP.2` controlled failover execution | architecture FROZEN 2026-09-04; `OP.2.1` gate DRAFTED 2026-09-05; still blocked on `D-V7b`/`D-F3` (proven hard blockers, not merely listed), `DEPLOY.1A` OIDC + `OPERATE` (P2 admits no local-pilot exemption either), SSH trust hardening, change-management review | multiple |
| `DEPLOY.1` gates | server availability (external) | external |
| `inventory_exclusions_management_ui_backend` | stays `in_progress` **by design** — do not wire its write functions into any HTTP-reachable surface before `DEPLOY.1A`'s OIDC/RBAC boundary exists | design |

Concurrency budget stays at 1 per vendor pending its own real-env evidence.

## Real-environment validation owed

- **`CON.2`** — trigger a `read`-class job from the console against a real
  device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves,
  not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial
  evidence" above; its own next technical movement, not folded in here.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence. Server-blocked.

## Automated test baseline

```
1681 passed / 26 skipped / 0 failed (2026-09-04, serial baseline,
  op0b_s8c_pan_dedicated_ha1_real_env_correction) -- unaffected by
  op2_1_cp_clusterxl_command_gate (docs-only; no product code path changed).
op2_1 local run: tests/test_op2_1_cp_clusterxl_command_gate.py (9 passed) +
  test_op0b_s4_command_gate.py + test_architecture_convergence.py +
  test_op2_a_b_execution_foundation.py = 107 passed, 0 failed.
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
gate (fast PR `validate` + `full-regression` on main push/dispatch); it runs
no device, container or registry step.
