# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law/rule/lifecycle detail here** —
see `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build detail lives in
`project/build_history.json`** (structured, newest-first) and its linked
docs under `docs/history/`; `docs/history/INDEX.md` is the one-line timeline.

- **Checkpoint:** 2026-09-07, `gov_git_authority_reconciliation` —
  **AUTOMATED_VALIDATED** governance reconciliation; Claude remains M9 owner.
  **PO §12:** `M8.3`'s real-env validation stays deferred; `M7` stays
  blocked. `M8` architecture **FROZEN — PO APPROVED, 2026-09-06**.
- **Next** (`now_next.next`): `m8_3_real_environment_validation`, `deferred`
  per §12. `m7_real_device_targeted_collect_now`/
  `op2_c_cp_clusterxl_adapter_scoping` stay `upcoming`/`blocked`. `DEV.TEST.1`,
  `PCP.1`, `M1`-`M6`, `M8.1`-`M8.4`, and this build complete/automated_validated
  — `project/build_history.json`.
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` IMPLEMENTED; `OP.2.1` CP
  command gate DRAFTED — CLASS 2 still has **no member**, no adapter,
  unconditional `DENY`. `D-V7b`/`D-F3`/`D-F2` no longer block the readiness
  roll-up (`OP.2.1b`) — remaining `CLASS 2` blockers are authorization/
  trust/adapter/change-management, not readiness.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1`/`DEV.4` complete.
- **Product evidence baseline:** `0.6.1B.1.2` interactive CP config is REAL_ENV_VALIDATED.

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

**`gov_git_authority_reconciliation`** (`GOV.GIT.1`) — clarifies that
Corporate Git is Product Owner controlled through explicit authorization and
that an authorized agent executes and verifies the named Git action. One
concrete objection is allowed; a repeated informed PO instruction controls
unless an exact higher-authority/failing-gate exception remains.
**AUTOMATED_VALIDATED**; governance-only; Claude remains M9 owner and PR #104 is
untouched.

Predecessors — **`gov_relay_1_question_routing`** and
**`gov_relay_1_canonical_agent_relay`** — **AUTOMATED_VALIDATED, MERGED**;
`RELAY_QUESTION` and the canonical relay protocol remain in force. Full
evidence: `project/build_history.json`.

## Predecessor — `M5`/`M4`
**`collector_target_selection_seam`** (`M5`) — COMPLETE/AUTOMATED_VALIDATED,
merged via PR #94: promoted `--cp-config-targets` into `workflow_argv()`;
`M6` is the only thing since changed `config_refresh_cp.target_mode`.
**`local_control_plane_metadata_store`** (`M4`) — COMPLETE, merged via PR #93,
additive local SQLite control-plane metadata store (seven `STRICT` tables).
Detail: `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md`.

## Predecessor — `M3`

**`nav_3_capability_state_vocabulary`** — COMPLETE / FROZEN (PO approved
2026-09-06). `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` is
implementation authority for the vocabulary/resolution/presentation matrix
(`D1`–`D7`, `E1`–`E7`, `AC-CS-1`…`97`); no implementation yet (`D3`/`M10`,
per-entity `D5`/`M12`), so every capability resolves `UNKNOWN` until then.

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

`now_next.next` is `m8_3_real_environment_validation` (`deferred`, no new
code) — see "Real-environment validation owed" below for the exact command.
`M7` stays blocked until it runs and produces a genuine
`REAL_ENV_VALIDATED` relationship; neither `M8.4`'s nor this build's own
`AUTOMATED_VALIDATED` completion changes that. `operator_assertion` stays
unaccepted.
`op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`/blocked; `OP.2.D`'s
console flow is expected on the `PCP.4` device/HA tab, never a second one.

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

- **`M8.3`** — **deferred to backlog** (PO decision, §12): the one bounded, read-only `--identity-first-contact` command against a real device_id, PO-authorized; also measures real CP config evidence-retention duration. Gates `M7`, not `M8.4` (already shipped ahead of it).
- **`CON.2`** — trigger a `read`-class job from the console against a real device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial evidence" above; its own next technical movement, not folded in here.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence, server-blocked.

## Automated test baseline

```
m8_evidence_host_key_fingerprint_not_persisted: targeted 70 passed
  (collector UI/M8.3/M8.4/interactive-project-plan). Convergence/privacy/
  application-package sweep: 43 passed (1 unrelated pre-existing collection
  error, missing `yaml`). compileall clean. git diff --check clean.
M8.4: targeted 23 passed. Affected sweep (M6/M8.1/M8.2/M8.3/PCP.1/M4/CON.1/
  CON.2/OP.0d/OP.0b S7.5/architecture convergence/application-package): 440
  passed, 1 skipped, 0 failed. compileall/privacy PASS. Detail:
  project/build_history.json.
M8.3 and earlier predecessor build detail lives only in
  project/build_history.json now.
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
