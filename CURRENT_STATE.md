# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first, the authority on what shipped when) and its
linked documents under `docs/history/`. `docs/history/INDEX.md` is the
generated one-line timeline.

- **Checkpoint:** 2026-09-06, `M5` branched from `main` at `88a8d161...`
  (`M4`, PR #93, merged). Implementation complete on the branch; PR CI pending.
- **Current build** (per `project/roadmap.json` `now_next.now`):
  `collector_target_selection_seam` (`M5`) — **AUTOMATED_VALIDATED**,
  `cp-config` seam only. `now_next.next` is `M6`
  (`registry_keyed_job_targets`), not started/unauthorized.
  `op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`, blocked on
  `DEPLOY.1`. `DEV.TEST.1`, `PCP.1`, `M1`-`M4` complete — build_history.json.
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

**`collector_target_selection_seam`** (`M5`) — **AUTOMATED_VALIDATED**,
`cp-config` seam only. Contract:
`docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §9/§12/§12.1
(`AC-TGT-3`/`AC-TGT-4`/`AC-TGT-5`), companion
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12/§12.1.

Promoted OP.0d's already-validated `--cp-config-targets` selector into the
shared `utils.collection_executor.workflow_argv()` argv seam (CON.2 C2-2), so
the scheduler and console job runner build the identical `cp-config` argv the
direct CLI already built. Target-free `cp-config` stays byte-identical/
plane-wide; requested order and opaque `entity_id` spelling are preserved
exactly. A non-empty target set for any workflow with no seam yet (`cp`,
`checkpoint`, `vsx`, `pan-config`) is refused with the new
`UnsupportedTargetSelectionError` **inside argv construction, before
`main.main()` is ever called** — fail-closed pre-invocation, pre-contact, in
both the scheduler and the console runner. `recovery-pan`/`recovery-cp` keep
their pre-existing pass-through behaviour byte-for-byte.

**Boundary held.** `JOB_REGISTRY` untouched — `config_refresh_cp.target_mode`
stays `"none"` (`M6`'s job); no registry resolution, no device contact,
admission/lock/vendor-budget unchanged, no new command/retry/transport path.
**Not implemented, by design:** a second collector seam (CP inventory, VSX,
`pan-config` stay honestly plane-wide), `M6` target resolution, `M7` job/UI
behaviour, scheduling, enrollment, HTTP/UI integration, device contact.

## Predecessor — `M4`

**`local_control_plane_metadata_store`** — COMPLETE, merged via PR #93.
Additive local SQLite control-plane metadata store, schema version 1, seven
`STRICT` tables; storage infrastructure only, no job behaviour or target
resolution. Detail: `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md`.

## Predecessor — `M3`

**`nav_3_capability_state_vocabulary`** — COMPLETE / FROZEN (PO approved
2026-09-06). `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` is
implementation authority for the capability-state vocabulary, resolution
contract and presentation matrix (`D1`–`D7`, `E1`–`E7`, `AC-CS-1`…`97`). It has
no implementation: `D3` arrives with `M10`, per-(entity, capability) `D5` with
`M12`, so every capability resolves `UNKNOWN` until they ship.

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

**Create and configure the Claude-side `nexus-decision-council` before the
next *architecture* movement** (not required for `M5`/`M6` — deterministic
implementation against an already-frozen contract). The `M3` council record
is a single authoring session's self-critique, not independent validation.

`M6` (`registry_keyed_job_targets`) is **not started and not authorized**:
resolves console-submitted `device_id` targets against the `PCP.1` Device
Registry at admission and again immediately before execution (`AC-ST-4`), and
flips `JOB_REGISTRY['config_refresh_cp'].target_mode` from `"none"` to
`"entity_ids"` — `M5` deliberately left that field untouched. Needs its own
separate authorization.

`op2_c_cp_clusterxl_adapter_scoping` stays `upcoming`/blocked; `OP.2.D`'s
console flow is expected on the `PCP.4` device/HA tab — one console, never two.

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
M5 focused (cp-config collector target-selection seam):
  tests/test_m5_collector_target_selection_seam.py 22 passed. Affected
  suites combined 110 passed / 0 failed: test_op0d_deterministic_target_
  selection.py, test_con2_console_job_engine.py, test_rb2_recovery_collect.py,
  test_architecture_convergence.py (20 passed), test_application_package.py.
  metadata_warnings == []; build-history index --check clean; repository
  privacy gate PASS / 0 findings (data/, logs/ removed first); git diff
  --check clean. No full local suite (bounded blast radius: one shared argv
  function + its two existing call sites, all covered above). No GitHub full
  regression, no workflow_dispatch, no device contact.
M3 freeze focused (contract/docs-only, revision 6): combined 36 passed / 4
  skipped / 0 failed; full detail in project/build_history.json.
Last full parallel suite (DEV.TEST.1): 1957 passed, 24 skipped, 0 failed
  locally; GitHub Actions one-time proof (run 34020356372) 1944 passed / 38
  skipped / 0 failed. Detail: project/build_history.json.
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
