# PO decision record - 2026-11-11: aiview may start configuration collection and discovery runs

**Status: RATIFIED - PO DECISION (chat, 2026-11-11).** It amends item 5 of `PO_DECISION_RECORD_2026_11_05_EVENING_BATCH.md`
(which left configuration collection refused to a machine session) for the two purposes below, and adds one sentence to
the AIView masking law in `AGENTS.md`.

## PO answer (verbatim)
"konfig ve keşif çalıştırması içinde aiview yetki al. poll etmende sorun yok sonuçta detayını göremiyosun"
("Give aiview the right to run configuration and discovery too. Polling is fine, since you cannot see the details
anyway.")

The answer followed the 2026-11-10/11 night manual fleet collection run as aiview, where the configuration collect
returned 403 and the discovery start needed `onboarding_admin`.

## 1. Decision

The aiview persona (`role:replay_viewer`), including its machine session, may start:

- **a configuration collection** - `device_configuration_collect` (`POST /devices/{id}/configuration/collect` and
  `POST /devices/configuration/collect-all`), the same shape as `device_inventory_collect`;
- **a discovery run** - `discovery_run_start` (`POST /discovery/runs`).

Both gain `replay_viewer` as an alternative role and join the machine-session POST allow-list. Both remain the read
commands already approved for those capabilities; no device command is added.

## 2. What does not change

- `discovery_run_import`, `discovery_acknowledge`, the SSH and PAN trust enrolments, `device_register`,
  `device_delete` and `device_configuration_text_read` stay closed to aiview (`onboarding_admin` or `security_admin`).
- aiview still never gets failover, approvals, schedules or backups.
- aiview submissions stay window-gated, like its other manual runs; the machine session stays read-only for every
  method other than the POSTs listed.
- The view stays masked. `GET /discovery/runs/{id}` reaches a replay viewer as the run's state, its counts-only
  `outcome_summary` and a `candidate_count`; the candidate rows (display names, own and management addresses, cluster
  references) are withheld, because the generic masking does not cover them. Polling the run is allowed (PO answer).

## 3. Enforcement

- `ActionRegistry`: `DEVICE_CONFIGURATION_COLLECT` and `DISCOVERY_RUN_START` list `REPLAY_VIEWER`.
- `GateChain.isReadOnlyPost` adds both actions for machine sessions.
- `DiscoveryController.read` withholds the candidate rows from a replay viewer.
- `AiviewDiagnosticReadOnlyTest` pins the exact allow-list (now including these two) and asserts the sibling writes
  stay closed; `MachineGateTest` pins the machine guard; `DiscoveryControllerReplayViewerTest` pins the read.
