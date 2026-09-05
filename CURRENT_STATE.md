# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-05, branch `claude/left-nav-vertical-redesign-e673q6`
  (unmerged; `origin/main` + 2 commits, no PR).
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `pcp_2_local_control_plane_sequencing_po_review` — **IN_PROGRESS**, a
  Product Owner architecture review producing **DRAFTS ONLY** (see "Active
  build"). `now_next.next` is
  `pcp1_registry_uuid_call_count_test_defect_repair`.
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

**`pcp_2_local_control_plane_sequencing_po_review`** — **IN_PROGRESS**,
producing **no product code**. A Product Owner review of the architecture a
left-navigation prototype surfaced. Three DRAFTs under `docs/design/`:
`NAVIGATION_INFORMATION_ARCHITECTURE.md` (**DRAFT — PRODUCT OWNER REVIEW
REQUIRED**; its earlier self-declared FROZEN status is withdrawn), the companion
`LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`, and the research appendix
`research/NSPM_NAVIGATION_BENCHMARK.md`.

**The left-navigation implementation is a WORKING PROTOTYPE** (commit
`5a5a1f7`, runnable and behaviourally unchanged): a collapsible rail over the
six roots, one model shared by the rail and the device tab strip, routes derived
from it, no placeholders, no enrollment affordance. **Design evidence, not an
approved implementation** — not frozen, not merge-approved.

**Review round 1 applied** (decisions: `project/roadmap.json` `now.notes`). The
Product Owner substantially accepted both DRAFTs' direction and closed fourteen
decisions — six-root baseline, enrollment location, conditioned local-loopback
enrollment, storage **Option A**, the `M1`…`M14` order and more. **Both
documents stay DRAFT.**

**A material evidence correction was required.** The appendix's revision-1
attribution of supplied screenshots to third-party products is **withdrawn in
full** — they were neXus UI screenshots supplied to identify behaviours to
preserve. The grade, every observation from them and three conclusions built on
them are deleted, not softened; the appendix is rebuilt on supportable grades
and the preservation evidence re-recorded as **REPO-VERIFIED** rows citing exact
source symbols. Corrections returned rather than approved: the pale yellow/gold
member emphasis is **preserved** (explicit label, no warning iconography); the
two proposed states stay **out** of the global canonical vocabulary; Jobs gets
**no** automatic root promotion; an inapplicable device tab stays **visible and
selectable**; the accessibility gaps block **merge**, not **freeze**. PAN B2
stays **NOT ESTABLISHED**.

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

`now_next.next` is **`pcp1_registry_uuid_call_count_test_defect_repair`**
(movement `M1`): the one bounded build actionable today with **no** PO decision
required — see "Automated test baseline". Not fixed inside this architecture
movement: it is a test-mechanism decision inside `PCP.1`'s frozen §21 contract.
`Sonnet 5, normal`.

`M1` runs **from current `main`**, in its own session and its own narrow PR —
not from this branch, which then incorporates the new `main`. Still open: the
SQLite schema/migration contract (`M4`), CP/PAN trust mechanics (`M8`),
enrollment schemas (`M9`), production `pcp_storage_engine`, production
OIDC/RBAC, future auto-enrollment, raw privileged configuration access, the
exported job-history field schema (`PCP.5`), and any future Jobs root
promotion. Sequence: companion DRAFT §12/§12.1. `M5` stays the critical path —
**every collection job type today is `target_mode="none"`**, so per-device
collection cannot be offered honestly until one collector gains a target seam.

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
