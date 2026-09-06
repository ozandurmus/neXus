# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-06, branch `claude/left-nav-vertical-redesign-e673q6`
  (incorporates `origin/main` at `9a946fe`, PR #84; NAV lineage preserved
  unrewritten).
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `nav_1_accessibility_closure` (`M2`) — **IN PROGRESS**: closing
  `AC-A11Y-1`…`4` against the frozen navigation contract, confirming
  `AC-A11Y-5`, and validating the combined branch before the NAV PR (see
  "Active build"). Predecessors both complete: `M0`
  (`pcp_2_local_control_plane_sequencing_po_review`) — architecture FROZEN
  by Product Owner approval at reviewed head `ba56d2b`; `M1`
  (`pcp1_registry_uuid_call_count_test_defect_repair`) — AUTOMATED_VALIDATED,
  merged to `main` via PR #84. `now_next.next` is `M3`
  (`nav_3_capability_state_vocabulary`).
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

**`nav_1_accessibility_closure`** (`M2`) — **IN PROGRESS**. Incorporates
`main` (M1, merged) into the NAV branch, closes `AC-A11Y-1`…`4`, confirms
`AC-A11Y-5`, validates the combined branch, then opens/merges the NAV PR.
Predecessors, both complete:
**`pcp_2_local_control_plane_sequencing_po_review`** (`M0`) — **COMPLETE.
ARCHITECTURE FROZEN 2026-09-05**, Product Owner approved at reviewed head
`ba56d2b`. It produced **no product code**. Two contracts are **FROZEN —
PRODUCT OWNER APPROVED**, each with acceptance criteria:
`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (§19) and
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` (§15).
`docs/design/research/NSPM_NAVIGATION_BENCHMARK.md` stays a **research
appendix, deliberately not a frozen contract**.
**`pcp1_registry_uuid_call_count_test_defect_repair`** (`M1`) —
**AUTOMATED_VALIDATED**: repaired two `tests/test_pcp1_device_registry.py`
cases that monkeypatched module-wide `uuid.uuid4` and incorrectly counted
the registry lock's `owner_token` generation as device-id generation, via a
single narrow production seam (`utils/device_registry.py::_generate_device_id()`).
Full suite: 1931 passed, 24 skipped, 0 failed. No frozen PCP.1 AC-1a..AC-15
behavior, persistence schema, endpoint normalization, duplicate/lifecycle
behavior, CLI output, or public API changed. Merged to `main` via PR #84.
`project/build_history.json` carries the exact defect/fix/evidence for `M1`
and the full freeze record for `M0`.

**Freeze is not implementation authority.** `M1`…`M14` remain separately
authorized, bounded movements, and the `NAV.1` prototype (commit `5a5a1f7`,
runnable, behaviourally unchanged) may not merge until **`M2`** closes the four
accessibility requirements (`AC-A11Y-1`…`4`).

Frozen: fifteen `D-NAV` decisions, **no operative row left provisional**; the
six-root baseline with Recovery reserved seventh and Jobs under Operations; the
four-predicate capability model, so **a global module never vanishes because one
device lacks a capability**; the logical-entity-first workspace with **no second
identity authority**; the stable-visible device-tab rule; and a difference
contract **preserving the pale yellow/gold expected-member emphasis** with an
explicit label and no failure semantics.

**Parent contracts narrowly amended:** `CON.0` §4.1 (the typed enrollment
intent) and §7.11; `PCP.0` §19 (decided block), §10 (local storage) and §20.1
(sequencing). Every unrelated frozen law is preserved.

**Decision register scoped, not overloaded:** `pcp_console_registry_write_gate`
**DECIDED** for the local loopback profile only; new
`pcp_server_enrollment_exposure` carries the production question (**OPEN**,
`DEPLOY.1A`); `pcp_local_control_plane_storage` **DECIDED** (Option A) while
`pcp_storage_engine` stays **OPEN** — **SQLite is not the production engine**;
trust and auto-enrollment policies **DECIDED**.

Benchmark screenshot evidence is **excluded from authority** — its provenance
is not durably auditable from the repository, the appendix asserts no
provenance either way, and **no frozen decision depends on it**. PAN B2 stays
**NOT ESTABLISHED**.

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
job lifecycle vocabulary. `Sonnet 5, extended thinking (high)`. `M1` (uuid4
test-defect repair) and `M2` (this movement, NAV accessibility closure) are
both complete/in progress — see "Active build".

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
THE FULL SUITE IS NOT GREEN. The two PCP.1 registry uuid4 call-count tests
  fail deterministically on main and on this branch, unchanged and unfixed:
  the count also catches the registry lock's own owner token, so both assert
  [1] == []. Backlog pcp1_registry_uuid_call_count_test_defect -> movement M1.
  The fast PR `validate` job never ran that file; `full-regression` does.
This movement is documentation/state only and changes no runtime behaviour
  (diff from ace9813 touches no executable JS/CSS/template/Python), so
  targeted evidence rather than a full regression:
    tests/test_navigation_information_architecture.py  20 passed
      (18 prototype AC checks + 2 DRAFT/authority guards; 4 real Chromium)
    tests/test_architecture_convergence.py  20 passed
    render harnesses: node+happy-dom PASS; Playwright/Chromium PASS
  Last full serial run here: 1950 passed / 22 skipped / 2 failed (same two).
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
