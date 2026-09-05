# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-05. Branch: `claude/nexus-control-plane-arch-mutgr9`
  (from `main` `ff700e38`).
- Build: `product_control_plane_architecture_draft` (`PCP.0`) — **IN
  PROGRESS, pending Product Owner review.** PRODUCT DIRECTION +
  ARCHITECTURE PROMOTION movement: the parked Product Control Plane mega
  prompt reconciled against live `main` into one design parent,
  `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` (status **DRAFT**).
- Docs/state only. No product code, test, taxonomy, console route, device
  command, schema or UI change. No frozen `OP.2`/`CLASS 2`, `CON.x`, `RB.x`
  decision reopened; `CLASS 2` stays memberless and unreachable.
- **Merge to `main` is blocked** until the Product Owner reviews the
  design doc (its own §23 gate). No PR merged by this session.

## 2. What changed this session

- **New** `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` — the single
  design parent: boundary (§2), target architecture (§3), four-layer truth
  model (§4), identity layering `device_id` ≠ `canonical_id` ≠ `entity_id`
  ≠ `operational_entity_id` (§5), Device Registry (§6), candidate-first
  enrollment (§7), capability model (§8), typed job plane (§9), persistence
  seam + storage-engine criteria (§10), capability-driven backup (§11),
  device ≠ failover unit with CP/PAN semantics preserved (§12), console
  intents (§13), SNMPv3 slot (§14), Diagnostic Runbooks slot (§15), `OP.2`
  relationship (§16), existing-capability mapping (§17), reconciliation
  table (§18), FROZEN / PO-APPROVED / DEFERRED / OPEN classification (§19),
  movement sequence `PCP.0`–`PCP.8` (§20), **`PCP.1` bounded contract with
  AC-1..AC-9 and non-goals (§21)**, amendments to apply only at freeze (§22).
- `project/roadmap.json`: new track `PCP.x` (8 features); `now` = this
  build (`in_progress`); `next` = `pcp_1_device_registry_manual_enrollment_
  foundation` (planned); `op2_c_cp_clusterxl_adapter_scoping` moved to
  `upcoming` (still `blocked`, notes preserved + dated reason); four open
  decisions (`pcp_console_registry_write_gate`, `pcp_auto_enrollment_policy`,
  `pcp_storage_engine`, `pcp_first_contact_trust_policy`); one roadmap note
  and one architecture-review note.
- `project/feature_registry.json`: eight `PCP.x` features (all `planned`,
  `fast_telemetry_plane` `deferred`), criteria pending.
- `project/backlog.json`: four new items (`pcp_collector_target_selection_
  seams`, `pcp_storage_engine_decision`, `snmpv3_fast_telemetry_plane`,
  `diagnostic_runbooks_read_only`); notes updated on
  `compliance_assignment_ui_and_registry` (registry half subsumed),
  `credential_profiles`, `bulk_fleet_ops`.
- `project/build_history.json`: new head record (`in_progress`,
  `ARCHITECTURE`, docs → the design doc); `docs/history/INDEX.md`
  regenerated; `CURRENT_STATE.md` rewritten (194 lines).

## 3. Exact next action

1. Open a PR from `claude/nexus-control-plane-arch-mutgr9` to `main`
   (fast PR CI is sufficient — docs/state only). **Do not merge**: request
   Product Owner review of `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`,
   in particular §18 (the one contradiction), §19 open decisions, §20
   sequence, §21 `PCP.1` contract.
2. On approval, a `Sonnet 5, extended thinking (high)` session: apply §22
   amendments, flip the doc status to `FROZEN`, set the build record to
   `done`, keep `next` = `PCP.1`, merge, sync `main`.
3. Then `PCP.1` (`Sonnet 5, normal`): one short prompt pointing at §21 and
   `tests/test_pcp1_device_registry.py`. No device contact, no UI.

Unchanged and independent: `op2_c_cp_clusterxl_adapter_scoping` stays
blocked on `DEPLOY.1`; `op0b_0_close_d_v3a_d_v7b_pre_class2`; PAN serial
identity closure (hardware-blocked); `cp_remote_collection_done_marker_
diagnostics` (needs a recurrence).

## 4. Test delta

- No product code changed; full `pytest` **not run** — this sandbox has no
  `pytest`/`lxml`/`paramiko` (reported, not bootstrapped, per `CLAUDE.md`).
  Verified directly instead: `utils.project_plan.build_project_plan_payload()
  ["metadata_warnings"] == []`; `scripts/build_history_index.py --check`
  clean; `CURRENT_STATE.md` names `now.build` and is ≤ 200 lines; the
  DRAFT design doc backs only a non-terminal (`in_progress`) record; every
  `build_history.json` doc link resolves; `git diff --check` clean.
- Baseline 1825 passed / 24 skipped / 0 failed carried forward, not re-run.
- Repository privacy gate not re-run (no secret-bearing content class
  touched; no runtime artifacts created).

## 5. New risks

- None to any reachable capability — no code changed.
- Process: the head `build_history.json` record is deliberately
  `in_progress` (a DRAFT doc may not back a terminal record). It must not be
  flipped to `done` before the PO review, and `PCP.1` must not start before
  the doc is `FROZEN`.
- Product: the manual-enrollment console intent must not be implemented
  before `pcp_console_registry_write_gate` is decided; candidate-based
  enrollment and CLI manual enrollment do not depend on it.
