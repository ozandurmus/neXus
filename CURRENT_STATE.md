# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-06, `main` at merge commit `d363b17` (PR #90,
  `DEV.TEST.1` final CI trigger policy). The earlier `6ca67cc`/PR #88
  checkpoint line was stale by two merges and is corrected here.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `nav_3_capability_state_vocabulary` (`M3`) — **IN_PROGRESS**, contract
  DRAFT. `now_next.next` is `M4` (`local_control_plane_metadata_store`).
  `op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`, blocked on
  `DEPLOY.1`. `DEV.TEST.1`, `PCP.1`, `M1`, `M2` complete —
  build_history.json.
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

**`nav_3_capability_state_vocabulary`** (`M3`) — **IN_PROGRESS.**
Capability-state vocabulary + presentation contract. Contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` — status
**DRAFT — DO NOT FREEZE, NOT PRODUCT OWNER APPROVED**; it authorizes no
implementation and is not citable as design authority. Delivered: seven
independently owned state dimensions (`D1`–`D7`), a ten-value
`CapabilityState` plus six non-exclusive qualifiers, a deterministic
precedence ladder, vendor normalization boundaries, an event→dimension
transition table and 53 acceptance criteria `AC-CS-1`…`53`. `PO-NAV-7`
resolved **in contract**: the capability-policy concept → `D5`
`POLICY_DISABLED`, owned by the schedule/capability-policy contract
(amendment `A5`, `M12`); "not enrolled" → `D4` `EVIDENCE_ONLY`, owned by the
registry/evidence reconciliation projection (amendment `A6`, `M10`). The
console job lifecycle, `OP.2` `ActionState`, action taxonomy, compliance,
discovery-lifecycle and registry vocabularies are **byte-unchanged**. No
runtime/UI/CSS/payload/registry/job/authorization change.

**Reported, not reconciled** (`AGENTS.md` authority hierarchy): the FROZEN
navigation contract contradicts itself on omitting a structurally
inapplicable tab — §8/§8.1 permit omission for an entity type, while
§6.5/D-NAV13/`AC-WS-7`/`AC-WS-8` forbid it. Raised as **`PO-M3-1`**; a
Product Owner decision, not an agent's.

**Six open PO decisions** `PO-M3-1`…`6` (contract §11) gate the freeze. One
is out-of-scope awareness: two live job-lifecycle vocabularies exist —
`console/jobs.py` (`queued`…`skipped`) and
`utils/coordinator_backend.JobStatus` (`pending`…`orphaned`), the latter
surfaced by `discovery_capability_ui.JOB_STATUS_LABELS`; `PO-NAV-7` protects
only the first (`PO-M3-4`).

**Predecessor builds, complete:** `parallelize_full_regression_execution`
(`DEV.TEST.1`) and `nav_1_accessibility_closure` (`M2`), both
**AUTOMATED_VALIDATED, MERGED** (PR #90, PR #85) — detail in
`project/build_history.json`. `left_vertical_product_navigation` stays
`in_progress` (`availability_rule` pending); no `D-NAV`/`PO-NAV` decision
reopened.

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

**Product Owner review of the `M3` DRAFT contract** — close `PO-M3-1`…`6`
(contract §11), then either freeze
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` or return it
with corrections. `PO-M3-1` requires a one-line correction to the FROZEN
navigation contract and cannot be applied by an agent. `M3` stays
`in_progress` until then; nothing downstream is authorized by the draft.

`now_next.next` is **`local_control_plane_metadata_store`** (`M4`): local
SQLite control-plane metadata only, additively, inside the companion
contract's §6.4 ownership boundary and §6.5 engine contract
(`AC-ST-1`…`AC-ST-8` already frozen). Independent of `M3`'s outcome; needs
its own separate authorization to start. `Sonnet 5, extended thinking
(high)`, new session.

Deferred detail remains **implementation-contract work inside a frozen
direction**: SQLite schema at `M4`, trust mechanics at `M8`, enrollment
schemas at `M9`. `M5` stays the critical path — **every collection job type
today is `target_mode="none"`**.

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

- **`CON.2`** — trigger a `read`-class job from the console against a real device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial evidence" above; its own next technical movement, not folded in here.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence, server-blocked.

## Automated test baseline

```
M3 (contract/docs-only movement) focused: architecture convergence 24 passed,
  navigation IA 20 passed, 0 failed. metadata_warnings == [];
  build-history index --check clean; git diff --check clean.
  No full local suite and no GitHub full regression run -- documentation-only
  change, per the movement's own validation scope.
Last full parallel suite (DEV.TEST.1, unchanged by M3): local `-n auto --dist
  worksteal` (4 CPUs) = 1957 passed, 24 skipped, 0 failed, two clean runs.
  GitHub Actions one-time proof (run 34020356372): 1944 passed / 38 skipped /
  0 failed, 275.74s vs 11m56s serial. Detail: project/build_history.json.
Repository privacy gate: PASS / 0 findings.
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
gate (fast PR `validate`, automatic; parallel `full-regression` via
`workflow_dispatch` only, `DEV.TEST.1`); it runs no device, container or
registry step.
