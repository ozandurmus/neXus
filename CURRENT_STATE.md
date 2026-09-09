# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law/rule/lifecycle detail here** —
see `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build detail lives in
`project/build_history.json`** (structured, newest-first) and its linked
docs under `docs/history/`; `docs/history/INDEX.md` is the one-line timeline.

- **Checkpoint:** 2026-09-09, `ui2_b0_baseline_directory` (`UI2 B0-8`) — new `docs/design/UI2_0_BASELINE_DIRECTORY.md` (status: **DRAFT — FOR PRODUCT OWNER FREEZE**), written under `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) and `UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B0-8. A single index cataloguing owner document/section/version for the six baseline-directory concerns: navigation (adopts `NAVIGATION_INFORMATION_ARCHITECTURE.md`'s frozen `D-NAV` principles and `C3`'s RBAC visible-but-refused mechanism, without carrying over Line-1's own six-root baseline as UI 2.0's root set); shared screen states (the mockup's own eight-term vocabulary and severity rule, adopted verbatim); feature-contribution contract (new minimal text tying `C4`'s registry to `C2`'s `job_type`, gated on `CAP-RELEASED`); alarm lifecycle (confirmed genuinely new — no existing owner found — kept schema-level against `C1`'s audit pattern and `C2`'s job-state-machine/`OUTCOME_UNKNOWN` pattern); log/audit data classes (cites `C1` §4 directly); SNMP status exposure (restates the read-vs-poll/status-vs-trap-out distinction, schema-level). Second-to-last `B0` item; runs concurrently with the extraction-tooling movement. Detail: `project/backlog.json`'s own note. Predecessors `C7` (PR #170), `C5` (PR #169), `C6` (PR #168), `C3` (PR #167), `C4` (PR #166), `C2` (PR #164), `C1` (PR #163), all DRAFT pending Product Owner freeze. Full chain: `project/build_history.json`.
- **Next** (`now_next.next`): `gov_po_2_implementation` — unrelated and
  unchanged by this movement; not yet started. `m7_real_device_targeted_
  collect_now` **AUTOMATED_VALIDATED 2026-09-08**, real-device confirmation
  pending Product Owner execution. `op2_c_cp_clusterxl_adapter_scoping`
  stays `upcoming`/`blocked`. `event_signal_intake` stays `in_progress` —
  cross-vendor timeline (0.8.x) and any real network exposure of
  `signal_intake/` remain later, separately-decided work.
  `DEV.TEST.1`/`PCP.1`/`M1`-`M11`/`M7` complete/automated_validated/real_env_validated.
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

See checkpoint above for `m10_2_capability_state_resolver_core`, the
current build. Full detail: `project/build_history.json`.

Predecessors, all **MERGED**, detail in `project/build_history.json` /
`docs/history/INDEX.md`: `gov_po_1_step_6_plan_po2_boundary` (PO2 boundary
plan), `gov_po_1_local_relay_protocol` (PR #120), `gov_po_1_gate_4_issue_
close_path` (PR #118), `m10_1_registry_evidence_reconciliation_projection`
(`M10`'s first slice, `D4` producer, PR #117 — no canonical id spans
`device_id`/`entity_id` today, `RELAY_DECISION` #11 option 4),
`gov_po_1_step_5_first_plan_episode` (PR #113),
`m9_enrollment_preview_confirmation_ui` (PR #104),
`gov_po_1_gate_1_command_safety_correction` (PR #114),
`gov_po_1_gate_2_self_sync_capability` (PR #115),
`gov_po_1_step_4_direction_record_ratification` (PR #112, `RATIFIED`),
`gov_po_1_step_3_docs_reconciliation` (PR #111),
`gov_po_1_step_2_implementation` (PR #110),
`gov_po_1_role_migration_contract` (PR #109, contract FROZEN),
`gov_git_authority_reconciliation` (PR #108),
`gov_relay_1_question_routing` (PR #107),
`gov_relay_1_canonical_agent_relay` (PR #105/#106),
`m8_evidence_host_key_fingerprint_not_persisted` (PR #103),
`gov_session_1_unified_packet` (`GOV.SESSION.1A`, PR #102, protocol-v2
`NEXUS_SESSION_PACKET`), `collector_target_selection_seam` (`M5`, PR #94),
`local_control_plane_metadata_store` (`M4`, PR #93). `T1`–`T7` still do not
exercise packet emission (step 6).

## Predecessor — `M3`
**`nav_3_capability_state_vocabulary`** — COMPLETE / FROZEN (PO approved
2026-09-06). `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` is
implementation authority for the vocabulary/resolution/presentation matrix
(`D1`–`D7`, `E1`–`E7`, `AC-CS-1`…`97`). `D4` has its producer as of
`M10.1`; `D2`/`D3` as of `M10.3`; the resolver core (stages 0-3: union tag,
`CX1`/`RI-1`/`RI-2`, primary ladder, qualifiers) as of `M10.2` (both
above); `D1` (stage 0) is now wired into the navigation availability_rule
as of `M11` (2026-09-08) — `D2`..`D7` still resolve `UNKNOWN`/`POLICY_UNKNOWN`
in any actual render pending their own producers. Stages 4-5 and `D5`'s
producer are still open; `M12` builds `D5`, `M14` builds `D7`.

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
join — `RELAY_DECISION` #11 left that undecided. `M8.3` is now
`real_env_validated` (2026-09-08, see "Real-environment validation owed")
and `M7` is unblocked (not yet started). `operator_assertion` stays unaccepted.
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
| `M12` per-device schedules | needs `M7` completed (unblocked, not yet started -- `M8.3`'s real-env debt is paid) | in-scope work, not a deferral |

Concurrency budget stays at 1 per vendor pending real-env evidence.

## Real-environment validation owed

- **`M8.3`** — **PAID 2026-09-08**: real `--identity-first-contact` run, `relationship_id e4671bc45e5e49e2bf9257d32b3fa067`, Product-Owner-executed. `M7` unblocked. Full detail: `project/backlog.json` id `m8_3_real_environment_validation`.
- **`CON.2`** — trigger a `read`-class job from the console against a real device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial evidence" above; its own movement. **`RB.3b`** — the watched single-gateway run. **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence, server-blocked.
- **`CE.2`** (`compliance_check_engine_primitives`, `relay/NXS-LOCAL-0037`) — the two proof primitives (`cp_gaia_show_version_all`, `pan_show_system_info`) are AUTOMATED_VALIDATED only; a real device/Panorama run confirming their actual output shape against the registry's redaction rule is owed before promotion beyond opt-in `--compliance-probe` mode. No live device reachable from this workspace.
- **`diagnostic_runbooks_read_only`** (`PCP.8`, `relay/NXS-LOCAL-0045`) — the catalog/validator layer is AUTOMATED_VALIDATED; it executes entirely through `CE.2`'s own primitives above, so it inherits the same real-device output-shape debt and stays owed together with it. No live device reachable from this workspace.

## Automated test baseline

```
ui2_b0_c5_amendments_bundle: full one-shot regression, foreground, awaited
(.venv/bin/python -m pytest -q -n auto --dist worksteal, 170.64s): 3214
passed, 27 skipped, 2 failed -- both pre-existing/unrelated DLP-token
collisions (tests/test_dev_0_5b_auth_consumer_canonical_config.py, tripping
on relay/NXS-LOCAL-0030-credential-profiles-reference-model.json and other
already-committed historical prose), unchanged in kind from the prior
baseline below and untouched by this movement's own diff.

Prior baseline, op0b_s7_s6_test_order_isolation: full one-shot regression
after merging origin/main (through m10_2_capability_state_resolver_core, PR
#132) + tool-gate interpreter fix (+2 tests, DEV.TEST.1): 2943 passed, 25
skipped, 2 failed -- the same DLP-token collisions. S7+S6 both orders: 110
passed each. Earlier predecessor build detail lives only in
project/build_history.json.
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
