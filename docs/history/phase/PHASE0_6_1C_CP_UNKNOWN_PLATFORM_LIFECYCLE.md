# 0.6.1C — Classify Collectable Unknown CP Platforms in the Discovery Lifecycle

## Status

**DONE — AUTOMATED_VALIDATED 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id: `cp_unknown_platform`
(P1, was `in_progress`, now `automated_validated`).

### Closure evidence and scope correction (2026-08-30)

A fact discovered during implementation changes what "wired end-to-end from
collector to Discovery UI" (this doc's original Definition of Done) can
honestly mean, so this section states precisely what shipped:

- **Discovered:** `utils/capability_registry.py`'s `CapabilityStore` /
  `CapabilityProfile` are never populated from a real collection run anywhere
  in this repository today (`grep -rn "CapabilityProfile(\|CapabilityStore("`
  outside `tests/` returns only the three data-model files themselves). The
  "0.6.1C Phase 4 collector wiring" this contract's implementation plan
  assumed as an existing integration point **does not exist yet, for any
  capability field** -- not something specific to platform classification.
  Building that live wiring is a materially larger, separate piece of work
  (main.py orchestration, a real per-run collector-to-store fold) outside
  this contract's bounded scope, and is not re-scoped in here.
- **What shipped instead, matching the contract's actual text ("sourced from
  the existing `_classify_platform()` output", AC-1):**
  - `CapabilityProfile.platform_family` / `.platform_confidence` (+
    `to_dict`/`from_dict`), independent of every other field (AC-2's
    correctness contract).
  - `platform_fields_from_classification()`: the one pure, sanctioned
    function that turns a `_classify_platform()`-shaped dict into those two
    fields. An integration test
    (`test_platform_fields_from_classification_accepts_real_classify_platform_output`)
    calls the actual collector's `_classify_platform()` and feeds its output
    through this function, proving the shapes stay compatible without
    requiring the live Phase-4 wiring to exist.
  - `discovery_capability_ui.py`'s `_entity_row()` + payload now carry
    `platform_family`/`platform_confidence`/`platform_label` and a
    `platform_family_labels` map (AC-1, AC-4 — no raw asset/serial data).
  - `app.js`'s `renderDiscoveryModule()` gained a Platform column (AC-3).
  - `test_platform_family_never_changes_the_collection_plan` proves
    `plan_collection()` is byte-identical across every `platform_family`
    value for an otherwise-identical profile (AC-2, the hard requirement).
- **Also discovered (not fixed, out of scope, flagged for a future item):**
  `tests/fixtures/uitest/discovery_ui.json`'s entity rows use key names
  (`entity_id`, `collection_mode`, `deferred`, `last_transition_reason`) that
  do **not** match `_entity_row()`'s real output shape (`canonical_id`,
  `shell_type`, `planned_mode`, `plan_allowed`, `plan_reason_code`) that
  `app.js` actually reads. This is a pre-existing mismatch (present before
  this build), not introduced here -- `app.js` was already silently reading
  `undefined` for Shell/Planned mode/Allowed/Reason on every Discovery row in
  the render harness. This build's new `platform_family`/`platform_label`
  keys were added to the fixture using the *correct* real names so the new
  Platform column renders meaningfully; the broader mismatch is a separate,
  larger fixture-regeneration task and is being raised as a new backlog
  candidate (`discovery_fixture_shape_drift`) rather than silently absorbed
  into this contract.
- Real device-command surface: unchanged. No new command; `_classify_platform()`
  itself was not touched.
- Evidence: `tests/test_phase0_6_1c_discovery_lifecycle.py` (+8 tests) and
  `tests/test_phase0_6_1c_discovery_capability_ui.py` (+3 tests). Full suite:
  569 passed, 2 skipped, 2 failed (both pre-existing, unrelated — same two
  tests documented in the `cp_ha_runtime`/`immutable_store_permission`
  closures). Net +14 from baseline 555, zero regressions.
  `tests/fixtures/uitest/` regenerated via `build_fixture.py`; JSON-payload
  render-harness checks (`tests/test_html_render_harness.py`) pass. The
  `bun`/`happy-dom` DOM-execution half of the harness could not run in this
  session's container (`window.eval is not a function` — an environment gap
  in this sandbox's bun/happy-dom versions, unrelated to this change) and is
  owed on a working local toolchain.

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
