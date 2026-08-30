# 0.6.1C — Classify Collectable Unknown CP Platforms in the Discovery Lifecycle

## Status

**PLANNED — architecture contract frozen 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id: `cp_unknown_platform`
(P1, `in_progress`).

## Objective

The principle "platform identity and collection capability are separate" is
already implemented and real-environment validated at the **configuration
collector** layer: `_classify_platform()`
(`configuration/checkpoint_config_collector.py:873-888`) returns
`family in {gaia_embedded, gaia, unknown}` independently of collection
success, `_collector_identity_gate()` (873-959) accepts MEDIUM-confidence
identity even on unknown platform, and `_configuration_failure_reason()`
(962-992) explicitly classifies an unsupported command on an unknown
platform as `capability_gap`, not `operational_failure` (validated:
`docs/history/validation/VALIDATION_0_6_1B_1_2.txt` — "PASS Unknown platform
does not block current configuration..."). What's missing is that this
classification is invisible to the **0.6.1C discovery lifecycle model** —
`utils/discovery_lifecycle.py`'s `EntityRecord` (lines 91-118) carries only
`state`/`confidence`/`evidence_plane`, no platform-family field at all. The
concept exists but is siloed in one collector, not part of the
discovery/capability surface this backlog item targets.

## Scope

### In scope

- Add a `platform_family` (+ `platform_confidence`) field to the discovery
  lifecycle / capability record (`utils/discovery_lifecycle.py` and/or
  `utils/capability_registry.py`, whichever already owns the entity's
  collection-capability projection), populated from the existing
  `_classify_platform()` output.
- Keep this field **fully independent** from `state`/`confidence` lifecycle
  transitions — an unknown platform must never demote or gate an otherwise
  `AVAILABLE`/collecting entity, matching the collector-layer precedent.
- Thread the field through to `utils/discovery_capability_ui.py`'s payload
  builder so an unknown-but-collecting device is visibly distinguishable from
  an unknown-and-uncollectable one in the Discovery module.

### Explicitly out of scope

- Any new device command; reuse the already-parsed `cpstat os -f hw_info`/
  `show version` evidence `_classify_platform()` already consumes.
- Broadening `_classify_platform()`'s taxonomy beyond
  `{gaia_embedded, gaia, unknown}` (e.g. Maestro/cloud/1500-series
  specifics) — a separate, later item if ever needed.
- Reopening the `checkpoint/scripts/cp_inventory.sh` device-safety findings
  that already steer collection away from `show asset all` in favor of
  `cpstat os -f hw_info` (`checkpoint_config_collector.py:1238-1247`).
- Any change to `_collector_identity_gate` or `_configuration_failure_reason`
  semantics — those are already correct; this item only propagates their
  output into the lifecycle model.

## Correctness contract

- `platform_family == "unknown"` on an entity must coexist with `state ==
  "AVAILABLE"` whenever a read-only configuration path succeeded — the
  lifecycle model must not encode "unknown platform" as a collection
  blocker.
- The field is additive to `EntityRecord`; existing consumers that don't
  read it must be unaffected (default/omitted when not yet classified).

## Privacy and safety invariants

1. No new device command; source data is already-collected, already-reviewed
   evidence (`hw_info`/`show version`).
2. No raw asset/serial string is promoted into the lifecycle/UI payload —
   only the coarse `family`/`confidence` classification, matching the
   existing collector-layer output shape.

## Implementation plan

1. Confirm which module (`discovery_lifecycle.py` vs `capability_registry.py`)
   is the right home for a platform-identity field by checking which one the
   0.6.1C discovery UI actually reads per-entity from today.
2. Add the field to the dataclass + its (de)serialization path.
3. Wire `_classify_platform()`'s result into that record at the point the
   config collector's per-entity result is folded into discovery/capability
   state (existing integration point from the 0.6.1C Phase 4 collector wiring
   referenced in `utils/html_export.py`'s `discovery_ui` build).
4. Extend `discovery_capability_ui.py`'s payload + `app.js`'s
   `renderDiscoveryModule()` to surface the new field per entity.
5. Targeted tests + full regression + render harness + privacy gate.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | `platform_family`/`platform_confidence` exists on the discovery lifecycle/capability record, sourced from the existing `_classify_platform()` output. |
| AC-2 | An unknown-platform entity with successful read-only configuration collection still shows `AVAILABLE`/collecting state — never gated by platform family. |
| AC-3 | Discovery module UI visibly distinguishes unknown-but-collecting from unknown-and-uncollectable. |
| AC-4 | No new device command; no raw asset/serial data added to any payload. |
| AC-5 | Targeted + full regression + render harness + privacy gate pass. |

## Validation and merge gate

Automated validation is sufficient to merge (this is a lifecycle-model
propagation of an already real-environment-adjacent classification, not new
collection behavior). Merge requires AC-1 through AC-5 and clean privacy gate.

## Definition of done

`DONE` when platform-family classification is visible end-to-end from
collector to Discovery UI without altering any collection-capability
decision, and `cp_unknown_platform` moves from `in_progress` to
`automated_validated`.
