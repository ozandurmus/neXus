# SecurityExpert — Current State

Hot-path checkpoint only. **No durable law, no rule, no lifecycle detail
lives here** — that's `AGENTS.md`/`AI_START_HERE.md`. **Predecessor build
detail is not here either** — it is in `project/build_history.json`
(structured, newest-first) and its linked docs under `docs/history/`.
`docs/history/INDEX.md` is the generated one-line timeline.

- **Checkpoint:** 2026-09-07, `M8.3` read-only first-contact producer
  (`m8_3_first_contact_producer_real_env_gate`) **AUTOMATED_VALIDATED** (branch
  `claude/m8-3-first-contact-producer-nh0260`, PR not yet opened/merged; see
  "Active build"). `M8.2` merged to `main` via PR #98 (commit `16c39ab`). `M8`
  architecture stays **FROZEN — PRODUCT OWNER APPROVED, 2026-09-06** from
  verified `main` at `0a9048ceeb2a318444f918e2688b126641eaeab0`.
- **Next** (`now_next.next`): Product-Owner-gated real-environment
  validation of this producer, `planned`, no new code — `M8.4` must not
  begin without a real-environment-validated relationship from it.
  `m7_real_device_targeted_collect_now`/`op2_c_cp_clusterxl_adapter_scoping`
  stay `upcoming`/`blocked`. `DEV.TEST.1`, `PCP.1`, `M1`-`M6`, `M8.1`, `M8.2`
  complete/automated_validated — `project/build_history.json`.
- **OP.2.0 CLASS 2 architecture** (`docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md`):
  **CONTRACT FROZEN 2026-09-04**; `OP.2.A`/`OP.2.B` IMPLEMENTED; `OP.2.1` CP
  command gate DRAFTED — CLASS 2 still has **no member**, no adapter,
  unconditional `DENY`. `D-V7b`/`D-F3`/`D-F2` no longer block the readiness
  roll-up (`OP.2.1b`, see "Active build") — remaining `CLASS 2` blockers are
  authorization/trust/adapter/change-management, not readiness.
- **Product baseline:** `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
- **Engineering baseline:** `DEV.3.3` — AUTOMATED_VALIDATED. `DEV.1`,
  `DEV.4` complete.
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

**`m8_3_first_contact_producer_real_env_gate`** (`M8.3`) —
**AUTOMATED_VALIDATED, 2026-09-07**, branch
`claude/m8-3-first-contact-producer-nh0260`, PR not yet opened. New
`utils/first_contact_producer.py::run_first_contact_producer` (contract
§3/§4): fail-closed registry resolution, exact-one `PhysicalTarget` selection
by `management_ip`, mandatory trust-before-credential
(`utils.cp_ssh_trust.lookup_trusted_host_key` first), the existing unmodified
`_collect_host`/`_identity_gate`/`_collector_identity_gate`/`_entity_id`
primitives, then the positive write gate (identity accepted + serial present
+ resolvable producing evidence) before the existing `M8.1`
`record_first_contact_proof`. New CLI mode `--identity-first-contact
DEVICE_ID`; not console-submittable. Full evidence: `project/build_history.json`.

Parent — **`m8_first_contact_trust_identity_evidence_producer_architecture`**
(`M8` architecture) — **FROZEN, PRODUCT OWNER APPROVED, 2026-09-06 (PR #96,
merged).** Sequence: `M8.1` → `M8.2` → `M8.3` (above) → `M8.4` (`M6`
resolver consumption) → `M7`. Open: `operator_assertion`, single-sourced
identity evidence, DEFERRED serial-contradiction detection. Full contract:
`docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`.

Predecessor — **`m8_2_endpoint_specific_trusted_key_lookup`** (`M8.2`) —
**AUTOMATED_VALIDATED**, merged to `main` via PR #98 (commit `16c39ab`):
`utils/cp_ssh_trust.py::lookup_trusted_host_key(endpoint, port)`, local-only
exact endpoint+port trusted-key check, no device contact.

Predecessor — **`m8_1_relationship_storage_api`** (`M8.1`) —
**AUTOMATED_VALIDATED**, `M4` schema-version-2 migration plus typed
read/write API. No producer/consumer yet.

Predecessor — **`registry_keyed_job_targets`** (`M6`) — **AUTOMATED_VALIDATED,
Option D fail-closed admission shell, not functional per-device targeting**.

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

`now_next.next` is `m8_3_real_environment_validation` (`planned`, no new
code) — see "Real-environment validation owed" below for the exact command.
`operator_assertion` stays unaccepted. `op2_c_cp_clusterxl_adapter_scoping`
stays `upcoming`/blocked; `OP.2.D`'s console flow is expected on the
`PCP.4` device/HA tab, never a second one.

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

- **`M8.3`** — the one bounded, read-only `--identity-first-contact` command against a real registry device_id, under explicit Product Owner authorization; also measures real CP config evidence-retention duration. Gates `M8.4`.
- **`CON.2`** — trigger a `read`-class job from the console against a real device. No new code; closes it to DONE.
- **`OP.0a`/`OP.0c`** — real-device confirmation `ha_cluster_mode` resolves, not `"unknown"`. Fixture-drift, not a safety gate.
- **PAN HA serial identity (`OP.0a.P7`/`OP.0b.0`)** — see "PAN HA serial evidence" above; its own next technical movement, not folded in here.
- **`RB.3b`** — the watched single-gateway run.
- **`DEV.3.2`** — real multi-container-against-real-MDS Postgres advisory-lock evidence, server-blocked.

## Automated test baseline

```
M8.3: targeted 37 passed (19 producer + 18 CLI). Affected sweep (M8.1/M8.2/
  PCP.1/M4/architecture convergence/OP.0d/OP.0b S7.5): 335 passed. Wide
  CP-config collector/probe/trust sweep (24 files): 441 passed, 1 skipped, 0
  failed. Privacy PASS. No full-regression run (risk-based). Detail:
  project/build_history.json.
M8.2 (correction round 1): 20 passed. Wide sweep (26 files): 608 passed,
  1 skipped, 1 pre-existing unrelated Paramiko-drift failure. Privacy PASS.
M8.1 targeted: 101 passed. Affected (M4/M8.1/M6/M5/PCP.1/CON.2/architecture
  convergence): 388 passed, 0 failed. Privacy gate PASS. No full-regression
  run (risk-based). Detail: project/build_history.json.
Predecessor build detail (M6 and earlier, including the last full parallel
  suite run) lives only in project/build_history.json now.
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
