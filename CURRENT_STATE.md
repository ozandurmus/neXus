# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law/rule/lifecycle detail here** —
see `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build detail lives in
`project/build_history.json`** (structured, newest-first) and its linked
docs under `docs/history/`; `docs/history/INDEX.md` is the one-line timeline.

- **Checkpoint:** 2026-09-08, `gov_po_1_gate_4_issue_close_path` — adds
  `gh issue close` to the interactive-form PO tool gate allowlist (an
  inline `--comment` must carry one of the five relay markers; a close
  with no comment is unconditional) so a Product Owner can close a
  resolved relay issue without a manual terminal command; delegated form
  still denies it entirely. Filed and closed from relay #14.
  Predecessor `gov_po_1_gate_3_agent_tool_council_path` **MERGED** PR #116
  (Agent-tool council-seat exception); own history record still outstanding.
  Predecessor `m10_1_registry_evidence_reconciliation_projection` **MERGED**
  PR #117 (`M10`'s first slice: `D4`/`RI-2` registry↔evidence projection).
  Predecessor `gov_po_1_step_5_first_plan_episode` **MERGED** PR #113: first
  Phase A `PLAN` episode, sliced `M10`, chose the M10.1 build above as `next`.
  Predecessor `M9` **MERGED** PR #104. `M8.4` **MERGED** PR #101.
  **PO §12:** `M8.3` deferred, `M7` blocked. `M8` **FROZEN 2026-09-06**.
- **Next** (`now_next.next`): **unset** — sizing/authorizing the next
  actionable build (e.g. `M10.2`, whose own `upcoming` row already says it
  needs its own `PLAN`/`REVIEW` episode) is a Product Owner function, not
  decided by an engineering build. `m8_3_real_environment_validation` stays
  `upcoming`,
  **deferred debt**, unpaid; `m7_real_device_targeted_collect_now`/
  `op2_c_cp_clusterxl_adapter_scoping` stay `upcoming`/`blocked`.
  `DEV.TEST.1`/`PCP.1`/`M1`-`M6`/`M8.1`-`M8.4`/`M10.1` complete/automated_validated.
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

## Safety status — the action taxonomy

`utils/action_taxonomy.py` is the single source of truth; `AI_START_HERE.md`
carries the full table; `AGENTS.md` "Architectural invariants" carries the
test-enforced boundaries.

| Class | Permitted? | Where |
| --- | --- | --- |
| 0 — read | yes | everywhere; most of the product |
| 1 — controlled recovery write | yes, **only** under the `RB.x` contracts | CLI only; never console-submittable |
| 2 — operational state change (failover) | **no member exists**; architecture frozen (`OP.2.0`), not implemented | hard-gated, `FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1/§10.2 |
| 3 — configuration write | prohibited | — |
| 4 — policy / deployment / remediation | prohibited | — |
## Active build

**`m10_1_registry_evidence_reconciliation_projection`** (`M10`'s first
slice, `D4` producer) — new `utils/registry_evidence_reconciliation.py`:
`resolve_d4()` derives the five frozen values from classified registry/
evidence sides; `detect_ri2()` implements `RI-2` (`AC-7`). Blocked, then
resolved: no canonical id spans `device_id` and the merged evidence model's
`entity_id` (`console/registry_targets.py` already refuses to translate
between them); posted `RELAY_QUESTION` on relay #11 rather than inventing a
join. Product Owner `RELAY_DECISION` (option 4): ship with no join input —
`utils/device_identity_relationships.py` stays unread, its `mapping_scope`-
widening question stays undecided. Only `REGISTRY_DISABLED` and
`RECONCILIATION_UNKNOWN` are reachable in production today; `RECONCILED`/
`EVIDENCE_ONLY`/`REGISTRY_ONLY` are implemented and unit-tested but need a
future movement's authorized canonical id. No resolver/UI/navigation/payload
wiring — scoped to the module plus its test file. Full detail:
`project/build_history.json`.

Predecessor — **`gov_po_1_step_5_first_plan_episode`** (`GOV.PO.1` §10 step
5) — **MERGED** PR #113: first interactive Phase A `PLAN` episode. Chose
`M10.1` as `next`; sliced `M10` into `M10.1`/`M10.2`/`M10.3` (§7 precedent);
ranked the backlog; filed `dlp_scanner_venv_exclusion` and
`project_state_wording_drift_reconciliation` (verified, not fixed).
Predecessors — its own two PO gate corrections, both **MERGED**: `GOV_PO_1_GATE_1` (PR #114) and `GOV_PO_1_GATE_2` (PR #115); `T1`–`T7` still do not exercise packet emission (step 6).

Predecessor — **`gov_po_1_step_4_direction_record_ratification`** —
**MERGED** PR #112: the record is **RATIFIED** (contract §4.1). Phase A
authorized (relay #5); Phase B closed until T1–T6.

Predecessor — **`m9_enrollment_preview_confirmation_ui`** (`M9`) —
**MERGED** PR #104: local-loopback manual-endpoint enrollment; candidate-id
enrollment stays server-refused pending `M10`'s `D4` — closed above.

Predecessors, all **MERGED**, detail in `project/build_history.json`:
`gov_po_1_step_3_docs_reconciliation` (PR #111),
`gov_po_1_step_2_implementation` (PR #110),
`gov_po_1_role_migration_contract` (PR #109, contract FROZEN),
`gov_git_authority_reconciliation` (PR #108),
`gov_relay_1_question_routing` (PR #107),
`gov_relay_1_canonical_agent_relay` (PR #105/#106),
`m8_evidence_host_key_fingerprint_not_persisted` (PR #103),
`gov_session_1_unified_packet` (`GOV.SESSION.1A`, PR #102, protocol-v2
`NEXUS_SESSION_PACKET`), `collector_target_selection_seam` (`M5`, PR #94),
`local_control_plane_metadata_store` (`M4`, PR #93).

## Predecessor — `M3`
**`nav_3_capability_state_vocabulary`** — COMPLETE / FROZEN (PO approved
2026-09-06). `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` is
implementation authority for the vocabulary/resolution/presentation matrix
(`D1`–`D7`, `E1`–`E7`, `AC-CS-1`…`97`). `D4` has its producer as of `M10.1`
(above); every other dimension still resolves `UNKNOWN`. `M10.2`/`M10.3`
build the resolver core and `D2`/`D3`; `M12` builds `D5`; `M14` builds `D7`.

## `OP.0b.0` — FROZEN WITH REAL-ENV VALIDATION GATES
`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
is implementation authority for the bounded S0–S9 slice sequence it defines
— citable for command/schema/identity-model *interpretation*, but **still
authorizes no CLASS 2 action**. `D-V4`/`D-V7a` are `CLOSED_BY_DOCS`. Every
other row's minimal safe interpretation is frozen — `D-V1`/`D-V2`
(field-binding, fail-closed predicates); `D-V5a`/`D-V5b`; `D-V6` (pnote
via `-ia list`); `D-V9a`/`D-V9b`. **`D-V3a`/`D-V7b` stay `STILL_UNKNOWN`**
as vendor facts — `D-V3a` (PAN) still scoped as a CLASS-2-time blocker;
`D-V7b` (CP) no longer blocks the readiness roll-up (`OP.2.1b`,
2026-09-05: advisory-exempt) though the vendor question is unchanged.
`D-F3` (flap threshold) is **DECIDED** (2026-09-05): no threshold
invented, advisory-exempt permanently, both vendors. `D-V8` remains open,
non-blocking. Full reasoning: `project/roadmap.json` `open_decisions`.

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

## Next candidate and open mapping question

`now_next.next` is unset (see checkpoint above). A future PLAN episode
needs to (1) size/authorize `M10.2` or another candidate and (2) decide
whether `utils/device_identity_relationships.py`'s `mapping_scope` may
widen beyond `CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY` to give `D4` a real
join — `RELAY_DECISION` #11 left that undecided. `M8.3` stays `deferred`
(see "Real-environment validation owed") and `M7` stays blocked until it
runs; `M10.1` pays none of that debt. `operator_assertion` stays unaccepted.
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
| `M12` per-device schedules | inherits `M7`'s block, which is `M8.3`'s deferred real-environment run | deferred debt |

Concurrency budget stays at 1 per vendor pending real-env evidence.

## Real-environment validation owed

- **`M8.3`** — **deferred to backlog** (PO decision, §12): the one bounded, read-only `--identity-first-contact` command against a real device_id, PO-authorized; also measures real CP config evidence-retention duration. Gates `M7`, not `M8.4` (already shipped ahead of it).
- **`CON.2`** — trigger a `read`-class job from the console against a real device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial evidence" above; its own movement. **`RB.3b`** — the watched single-gateway run. **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence, server-blocked.

## Automated test baseline

```
M10.1: targeted 56 passed; targeted + PCP.1/CON.1/CON.2/M6/architecture-
  convergence sweep 308 passed, metadata_warnings empty.
M9 rounds 1+2: 247 targeted+affected passed; full regression 2359 passed,
  2 pre-existing unrelated .venv DLP-scanner false positives (now filed:
  backlog `dlp_scanner_venv_exclusion`). Render harness + Playwright green.
Earlier predecessor build detail lives only in project/build_history.json.
```
## Known xfails

None currently known (two earlier ones became passing regressions in
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
