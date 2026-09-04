# OP.0b S9 (remaining, broader scope) — UI authority reconciliation

## Status

**DONE, 2026-09-04.** This closes the S9 remainder the bounded PAN-label
slice (`op0b_s9_pan_ha_label_authority_correction`) explicitly left open:
`static/inventory_ui.js`'s client-side PAN/CP pairing inference,
`utils/merge.py`'s hostname-suffix cluster heuristic, and
`utils/config_ui.py`'s independent `_ha_header_evidence` HA vocabulary.
`project/feature_registry.json`'s `ui_authority_reconciliation` criterion
moves from `pending` to `done`.

## What this session found

Every one of the three named heuristics had a canonical backend
counterpart already computed and, in two of the three cases, already
embedded in the same rendered page — the debt was that the Inventory
page's own tree builder and the config-collector's own header derivation
never consulted it:

1. **`static/inventory_ui.js` PAN pairing** (`panGroups`/`inferPairDescriptor`
   applied to PAN device names/`panoramaPairCompatible` VSYS-VR Jaccard
   similarity, thresholds 0.75/0.60) duplicated, with a weaker evidence
   basis (hostname ordinals plus live-signature similarity), exactly what
   `utils.failover.assessment._derive_pan_units` already establishes from
   mutual configured `peer-ip` agreement (or an operator's explicit,
   bounded preflight candidate) — and that result was *already* embedded
   in the same page, unused, as `failoverReadinessData.units`
   (`utils/failover_readiness_ui.py::build_failover_readiness_payload`,
   computed unconditionally on every render from the same `unified.json`
   `utils/html_export.py` already loads for every other payload builder).

2. **`static/inventory_ui.js` CP/VSX cluster synthesis**
   (`vsxByCluster`'s hostname-token-overlap fallback, `memberSetsOverlap` +
   `inferPairDescriptor(memberNames[0])`, used only when
   `aggregateCpClusters` found no runtime-proven ClusterXL VIP fingerprint)
   duplicated `utils.failover.assessment._derive_cp_units`'s own
   `cp_vsx_cluster` grouping (`cluster_topology.group_id`, or its legacy
   `cluster`-field fallback) — again already present, unused, in the same
   `failoverReadinessData.units`.

3. **`utils/merge.py` VSX cluster field** (`device.endswith("-1"/"-2") →
   device[:-2]`) guessed a physical cluster identity from a naming
   convention that does not hold across the estate, when the canonical
   grouping (`checkpoint/cp_runner.py::enrich_cluster_topology`'s runtime
   VIP fingerprint, `cluster_topology`) is already computed and attached to
   that same physical device's own `cp.json` row — `merge.py` simply never
   cross-referenced it while building the `vsx.json` row for the same
   device.

4. **`utils/config_ui.py` `_ha_header_evidence`** was not pairing/identity
   logic at all (it never resolves a peer, never forms a unit — it only
   labels one device's own HA state for the Configuration page header) and
   its runtime-evidence tiers already read the canonical collector-supplied
   `ha_state`/`ha_runtime` fields. Its one real defect: the config-state
   fallback recognized `{"yes","true","on","enabled","1"}` /
   `{"no","false","off","disabled","0"}` for a PAN-OS XML API boolean leaf
   that only ever emits the literal tokens `"yes"`/`"no"` (the same
   narrower vocabulary `utils.failover.assessment._derive_pan_units`
   already treats as canonical for the same kind of field) — an
   independently-invented tolerance for values no real device response
   contains.

## Correction

- **`static/inventory_ui.js`**: new `haReadinessUnitsByType(unitType,
  vendor)` reads `failoverReadinessData.units` (identity fields only —
  `unit_id`/`unit_type`/`vendor`/`members`/`display_name` — never
  `verdict`/`checks`/`evidence`; no readiness authority moves to the UI).
  The PAN-pairing block now iterates `pan_ha_pair` units and resolves each
  `unit.members` entity id to its `panEntries` row by device name; a pair's
  label is `display_name || unit_id` (the same canonical-identity-first law
  S9's bounded slice already established for the failover-readiness
  report), never a heuristically composed `"<BASE>-CLS"` string. The
  VSX-cluster-synthesis fallback now looks up a matching `cp_vsx_cluster`
  unit by member-token overlap against the *canonical* unit's own
  `members`, instead of deciding grouping from token overlap between two
  client-observed member lists. Fully dead code after the PAN change
  (`setSimilarity`, `panoramaRuntimeSignature`, `panoramaPairCompatible`)
  is removed. `inferPairDescriptor`/`clusterKey`/`clusterDisplayName`
  (label-only helpers, still used for e.g. `ClusterXL` fallback labeling
  and the VSX-dedup grouping key) and the first block's
  `memberSetsOverlap`-based join of an already-canonical `cp_cluster`
  parent to its VSX children are untouched — that join attaches children
  under an identity `aggregateCpClusters` already established canonically
  from `cluster_topology.group_id`; it decides no pairing itself.
- **`utils/merge.py`**: `run_merge` builds a `device -> cluster_topology`
  lookup from the already-loaded `cp_data` and passes it to `normalize_vsx`,
  which now copies the matching device's canonical `cluster_topology` onto
  the VSX row (`cluster` becomes `cluster_topology.display_name`) instead
  of guessing from the device name's trailing `-1`/`-2`. Absent a match,
  the only remaining source is the explicit, collector-supplied
  `item.get("parent")` field — never inferred.
- **`utils/config_ui.py`**: `_ha_header_evidence`'s two ad hoc token sets
  are replaced by `_PAN_HA_ENABLED_TOKENS = {"yes"}` /
  `_PAN_HA_DISABLED_TOKENS = {"no"}`, the literal PAN-OS API vocabulary.
  Runtime-evidence precedence, the "never infer Active/Passive from static
  config" invariant, and every other code path are unchanged.

## Preserved, verified by targeted tests

- **ClusterXL behavior**: `aggregateCpClusters`'s `cluster_topology.group_id`
  parent formation is untouched; the first block's member-overlap join of
  VSX children under an already-formed `cp_cluster` parent is untouched.
- **VSX/VSLS behavior**: `_derive_cp_units`'s VS/VSID grouping, the seven
  stop-condition checks, and `deduplicateInventory`'s VS-context dedup are
  untouched; the VSX-cluster-synthesis fallback still only fires when the
  canonical unit itself proves two-or-more physical members.
- **PAN HA pairing/identity semantics from S8-C**: `_derive_pan_units`,
  `_apply_pan_explicit_candidate`, and `_pair_identity_state` are
  byte-for-byte unchanged; the Inventory page now reads their output
  instead of re-deriving a weaker approximation of it.
- **Seven-check readiness semantics**: no file under `utils/failover/` was
  touched; `_evaluate_checks`, `STOP_CONDITIONS`, and every check mapping
  are unchanged.
- **No UI-side readiness or identity authority**: the Inventory tree reads
  only `unit_id`/`unit_type`/`vendor`/`members`/`display_name` off each
  `HaUnit` — never `verdict`, `checks`, or `evidence`. It forms no
  independent judgment about *whether* a pair is healthy, only *which*
  devices the canonical engine already says belong to the same unit.

## Validation

Targeted: `tests/test_merge_characterization.py`,
`tests/test_phase0_5_3_cluster_hierarchy_ui.py`,
`tests/test_phase0_6_0a4_3_configuration_ui.py`,
`tests/test_frontend_module_composition.py` — 24 passed (after updating the
frozen top-level-function-count assertion from 181 to 179: 3 removed
similarity-heuristic functions, 1 added canonical-lookup helper). Broader
sweep (`-k "failover or pan_ha or op0b_s8 or vsx or vsls or op0a or
inventory or merge or configuration_ui"`) — 532 passed. Convergence +
render-harness + UI-contract sweep
(`test_architecture_convergence.py`, `test_frontend_module_composition.py`,
`test_phase0_5_final_ui_closure.py`, `test_html_render_harness.py`,
`test_ui_contract.py`) — 38 passed, 1 skipped. Repository privacy gate:
FAIL only on the known gitignored `data/`/`logs/`/`.support_hmac.key`
runtime-artifact finding (confirmed untracked, `.gitignore`d) — not a
repository content issue. No full serial regression run for this slice
(scoped to the three named files plus their direct test coverage; the
sweep above already covers every failover/HA/inventory/config-UI path);
FAST PR CI covers the rest per this repo's validation ladder.

Three tests updated to the corrected, canonical contract (not just made to
pass): `test_merge_characterization.py`'s existing assertion on the
retired hostname-suffix guess now asserts the honest empty-cluster result
when no canonical `cluster_topology` matches and no explicit `parent`
field exists; `test_phase0_5_3_cluster_hierarchy_ui.py`'s PAN-hierarchy
test now asserts the removed heuristic's markers are *absent* and the new
canonical-lookup markers are present; `test_frontend_module_composition.py`'s
frozen function-count floor moved from 181 to 179 with the exact
by-function accounting recorded in the test's own comment.

## What remains

Nothing named by this build. `D-V3a`/`D-V7b`, `D-F3`, and PAN serial `B2`
stay open exactly as `CURRENT_STATE.md` already records — untouched by
this session, none of them blocking, none of them read-only gaps in
`OP.0b`'s own S1–S9 scope. With this closure, `OP.0b`'s full S1–S9
read-only scope is DONE.
