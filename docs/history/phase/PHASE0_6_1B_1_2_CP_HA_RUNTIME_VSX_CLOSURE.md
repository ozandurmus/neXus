# 0.6.1B.1.2 closure — ClusterXL/VSX Runtime HA Role Coverage

## Status

**PLANNED — architecture contract frozen 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id: `cp_ha_runtime`
(P0, `in_progress`).

## Objective

Runtime `cphaprob` HA-role evidence is functioning at the physical-member
level (real-environment coverage recovered 0 -> 42 in the original B.1.2
checkpoint). Two coverage gaps remain open per the backlog note:

1. **VSX closure**: `vsx_host` entities today receive the same
   physical-gateway-level `cphaprob stat` as a plain `clusterxl_member`
   (`configuration/checkpoint_config_collector.py:1291-1309`). VSX allows a
   virtual system to hold independent HA state from its physical member, so
   the current model cannot express "VS 3 is ACTIVE on member A while VS 7 is
   ACTIVE on member B."
2. **Direct-Clish coverage**: for `shell_mode ==
   "interactive_direct_clish"` hosts, HA role collection still falls back to
   `_run_exec(ssh, "cphaprob stat", ...)` rather than the interactive session
   proven for Expert-mode hosts — `cphaprob` is Expert/bash-level, so a
   direct-Clish-only appliance may silently get no runtime role at all.

## Scope

### In scope

- For `entity_type == "vsx_host"`, collect HA role **per virtual system**,
  not only per physical member. Reuse the already-proven `vsenv <VSID>`
  context-switch mechanism already used for VSX interface/route collection
  (`checkpoint/cp_runner.py`), issuing the same already-whitelisted
  `cphaprob` family of commands inside each VS context.
- Attach the resulting per-VS role to the corresponding virtual-system row
  rather than overloading the single physical-member `ha_role` field.
- For `shell_mode == "interactive_direct_clish"`, determine whether
  `cphaprob` is reachable at all in that shell mode; if not, the entity's
  `ha_role` must resolve to an explicit `capability_gap`/`unavailable` state
  (never a silent absence and never an inferred guess), consistent with the
  existing `_configuration_failure_reason()` capability-gap pattern used for
  `platform_family=="unknown"`.
- Extend `_parse_clusterxl_runtime_role()` (or a VSX-scoped sibling) only as
  far as needed to carry a per-VS result; keep the existing
  `CLUSTERXL_RUNTIME_STATES` vocabulary.

### Explicitly out of scope

- Any new device command beyond the already-whitelisted `cphaprob` family.
- Any change to the physical-member ClusterXL role path that already works
  (0->42 recovery stays untouched).
- Any change to collection concurrency, polling frequency, admission
  coordinator budget (stays 1 per vendor), or the CP device-interaction-safety
  guardrails.
- Inferring ACTIVE/STANDBY from hostname/config naming — explicitly forbidden
  by the existing code comment at
  `checkpoint_config_collector.py:1287-1290` and this contract does not
  relax that.
- Any write/failover-adjacent command; this is inventory evidence only.

## Correctness contract

- A per-VS HA role result must be attributable to `(physical_member,
  VSID)`, never conflated with the physical member's own role when they
  differ.
- Missing/unreachable HA role (either at the physical or VS level) degrades
  to an explicit `unavailable`/`capability_gap` status; it must never block
  otherwise-good configuration evidence for that entity (same non-blocking
  guarantee the existing `try/except` at
  `checkpoint_config_collector.py:1305-1309` already gives the physical path).

## Privacy and safety invariants

1. No new device command class; every command used here is already on the
   read-only whitelist.
2. No credential, raw configuration, or unrelated command output enters the
   stored HA-role field — role state string only.
3. Admission coordinator concurrency-budget-of-1 per vendor is unchanged;
   this closes the standing **CP device-interaction-safety audit (P0)**
   prerequisite before any concurrency increase, it does not touch it.

## Implementation plan

1. Locate the VSX per-VS iteration point already used for interface/route
   collection in `checkpoint/cp_runner.py` and confirm the `vsenv` context is
   available at the point HA role is currently collected in
   `checkpoint_config_collector.py`.
2. Add per-VS `cphaprob` collection inside that existing context loop; store
   role keyed by VSID alongside the existing per-VS row.
3. For `interactive_direct_clish`, probe (read-only) whether `cphaprob` is
   reachable; if not, set an explicit capability-gap status rather than
   omitting the field.
4. Extend `utils/config_ui.py`'s `ha_role_covered` aggregation
   (`checkpoint_config_collector.py:1838`) to count per-VS coverage
   distinctly from physical-member coverage.
5. Targeted tests for the new per-VS parsing branch; full regression;
   `--render-only` diff inspection for the Configuration module's VSX rows.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | A VSX virtual system's HA role is collected and attributed independently from its physical member's role. |
| AC-2 | `interactive_direct_clish` hosts resolve HA role to an explicit capability-gap state when `cphaprob` is unreachable, never a silent omission or an inferred guess. |
| AC-3 | The existing physical-member ClusterXL HA-role path (0.6.1B.1.2 baseline) is unchanged and still passes its existing tests. |
| AC-4 | No new device command, concurrency, scheduler, or write-capability change. |
| AC-5 | Targeted + full regression + privacy gate pass with no new xfail. |

## Validation and merge gate

Real-environment confirmation of the VSX per-VS split is desirable but not
required to merge (same posture as the original B.1.2 checkpoint — automated
validation first, real-env reconfirmation noted as owed). Merge requires AC-1
through AC-5 and a clean privacy gate.

## Definition of done

`DONE` when per-VS HA role is a first-class field distinct from physical
member role, the direct-Clish gap resolves explicitly rather than silently,
and `cp_ha_runtime` in `project/backlog.json` is updated from `in_progress`
to `automated_validated` (or `real_env_validated` once confirmed against a
real VSX cluster).
