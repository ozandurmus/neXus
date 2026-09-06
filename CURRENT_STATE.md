# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-06, `main` at merge commit `081a976` (PR #85, `M2`,
  onto `9a946fe`/PR #84's head). NAV lineage (`5a5a1f7`…`259874e`) unrewritten.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `nav_1_accessibility_closure` (`M2`) — **AUTOMATED_VALIDATED, MERGED**:
  closed `AC-A11Y-1`…`4`, confirmed `AC-A11Y-5`, validated the combined
  branch, merged to `main` via PR #85 (see "Active build"). Predecessors
  both complete: `M0` architecture FROZEN (reviewed head `ba56d2b`); `M1`
  AUTOMATED_VALIDATED, merged via PR #84. `now_next.next` is `M3` (not started).
  `op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`, blocked on
  `DEPLOY.1`. `PCP.1` is complete — detail in `project/build_history.json`.
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

**`nav_1_accessibility_closure`** (`M2`) — **AUTOMATED_VALIDATED, MERGED**.
Incorporated `main` (`M1`) into the NAV branch, closed `AC-A11Y-1`…`4`,
confirmed `AC-A11Y-5`, validated the combined branch (182 targeted + 1958
full-parallel passed, 0 failed — see "Automated test baseline"), merged to
`main` via **PR #85** (merge commit `081a976a3e1cc16fb57af7f0c9aa0e0501a7625b`).
Predecessors, both complete — full detail in `project/build_history.json`:
`M0` froze `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (§19) and
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` (§15), Product
Owner approved at reviewed head `ba56d2b`, no product code; `M1` repaired
two `tests/test_pcp1_device_registry.py` uuid4 call-count defects via
`utils/device_registry.py::_generate_device_id()`, AUTOMATED_VALIDATED
(1931 passed / 24 skipped / 0 failed), merged via PR #84 — no frozen
`PCP.1` `AC-1a`..`AC-15` behavior changed.

**Freeze is not implementation authority.** `M1`…`M14` remain separately
authorized movements; `NAV.1` (`5a5a1f7`) required `M2` to close the four
accessibility requirements before merging — done, via PR #85.
`left_vertical_product_navigation` stays `in_progress` in
`project/feature_registry.json` regardless: `availability_rule` (the
remaining capability predicates) is genuinely pending, owned by later
movements, independent of `M2`'s scope. Frozen (unchanged): fifteen
`D-NAV` decisions, no operative row provisional; the six-root baseline,
Recovery reserved seventh, Jobs under Operations; the four-predicate
capability model; the logical-entity-first workspace, no second identity
authority; the difference contract preserving pale yellow/gold
expected-member emphasis. Parent contracts narrowly amended: `CON.0` §4.1/§7.11;
`PCP.0` §19/§10/§20.1. Decision register: `pcp_console_registry_write_gate`
DECIDED (local loopback only); `pcp_server_enrollment_exposure` OPEN
(`DEPLOY.1A`); `pcp_local_control_plane_storage` DECIDED (Option A),
`pcp_storage_engine` stays OPEN — SQLite is not the production engine;
trust/auto-enrollment policies DECIDED. Benchmark screenshot evidence stays
excluded from authority (unauditable provenance, no frozen decision depends
on it). PAN B2 stays NOT ESTABLISHED.

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

`now_next.next` is **`nav_3_capability_state_vocabulary`** (`M3`): map the ten
UX capability-state semantics onto canonical states, settle the two
`PO-NAV-7` concepts in their correct owning domains, own the `PO-NAV-6`
colour/label contract. Prerequisite `M0` complete (it is); must not alter the
job lifecycle vocabulary. `Sonnet 5, extended thinking (high)`. `M1`/`M2` are
both complete and merged (PR #84, PR #85) — see "Active build"; `M3` itself
is not started.

Deferred detail is **implementation-contract work inside a frozen direction**:
state names at `M3`, SQLite schema at `M4`, trust mechanics at `M8`, enrollment
schemas at `M9`. Separate future decisions are listed in the two contracts'
own sections. `M5` stays the critical path — **every collection job type today
is `target_mode="none"`**.

`op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`/blocked with its notes in
`project/roadmap.json` (adapter, member session and preflight/eligibility all
IMPLEMENTED + unit-tested, none wired; CLASS 2 unreachable). `OP.2.D`'s console
flow is expected on the `PCP.4` device/HA tab — one console, never two.

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
M2 targeted (navigation IA + M2 a11y + architecture convergence + frontend
  composition + rendering boundary + both render harnesses + CON.1/CON.2 +
  PCP.1 registry): 182 passed, 1 skipped, 0 failed. Real-Chromium: accessible
  names (expanded+collapsed), reduced-motion emulation, focus transfer (root
  + grouped child + keyboard, both shells), group-label association
  (Devices/Operations/Administration), AC-A11Y-5 confirmed unregressed --
  zero console errors.
Full parallel suite (pre-merge, this branch): `py -m pytest -q -n auto
  --dist worksteal` (4 workers) = 1958 passed, 23 skipped, 0 failed, 29.46s
  wall-clock. Completely clean, no serial rerun. M1's uuid4 defect stays
  fixed (merged via PR #84).
Post-merge CI on main (PR #85, run 34016204567, commit 081a976): privacy
  gate/project-state/build-history-index checks success; full-regression
  terminal result in project/build_history.json (nav_1_accessibility_closure).
Repository privacy gate: PASS / 0 findings. metadata_warnings == [];
  build-history index --check clean; git diff --check clean.
```
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
