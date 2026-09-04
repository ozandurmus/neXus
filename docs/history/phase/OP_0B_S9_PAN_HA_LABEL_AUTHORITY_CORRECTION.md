# OP.0b S9 (bounded slice) — PAN HA unit label authority correction

## Status

**DONE, 2026-09-04**, for the bounded slice this session took: the PAN UI
debt the S8-C closure recorded (`docs/history/phase/OP_0B_S8C_PAN_DEDICATED_HA1_REAL_ENV_CORRECTION.md`,
"OP.0b closure assessment", item A). **The broader S9 scope the roadmap
`now_next.next` note and `project/feature_registry.json`'s
`ui_authority_reconciliation` criterion name — `static/inventory_ui.js`'s
client-side PAN/CP pairing and cluster-name inference, `utils/merge.py`'s
hostname-suffix cluster heuristic, and `utils/config_ui.py`'s independent
`_ha_header_evidence` HA vocabulary — is UNTOUCHED and stays PENDING.** That
is a separate, larger, cross-JS/Python inventory-page surface, not the
failover-readiness report/console module this slice corrected; per the
S8-C session's own PO decision, it remains its own, independently-scoped
dedicated movement.

## What this session found

The S8-C closure doc's own recorded PAN UI debt: the PAN HA pair's
`display_name` (`utils/failover/assessment.py::_pan_display_name`) rendered
as `"VSYS 2, 3, 4 +1 | <member-A>+<member-B>"` — a heuristically-composed
string with the VSYS scan leading, the canonical pair identity trailing.
This violated the required presentation law (canonical backend identity →
projection, never the reverse) in spirit even though the composed string's
*inputs* were themselves canonical (VSYS names from the member's own
interface evidence, entity ids from the readiness engine's own pairing
resolution) — the VSYS scan was being treated as if it were part of the
unit's identity/name, when it is context only.

Separately, the S8-C real-env run's own `control_sync_link_health
INSUFFICIENT_EVIDENCE (unknown:pan_path_monitoring_any_down)` result was
reviewed for whether it is presentation debt or a backend defect. Traced the
full path: `preflight_readiness.py::_state_reason` (`f"{fact.state.value}:{name}"`)
is a **generic, shared formatter** used identically for every fact in
`FACT_CHECK_MAP` across both vendors — not a special case for this one
check. The underlying `FactState.UNKNOWN` comes from
`panorama/pan_preflight_extraction.py::parse_pan_path_monitoring`, which
fail-closes to `None`/unknown exactly when path monitoring is not configured
on the device, the response has no recognized state leaves, or the shape is
otherwise unrecognized (gate PAN-P4, `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`).
**Conclusion: presentation-only debt, not a backend/parser defect.** The raw
`"unknown:pan_path_monitoring_any_down"` string is honest and correct but
not written for an operator glancing at a report; the fix is a plain-English
gloss of the exact same reason code, nothing about readiness semantics
changes.

## Correction (additive, presentation-layer only)

- **`utils/failover/assessment.py`**: `HaUnit` gains `context_vsys: list[str]`
  (default `[]`), serialized in `to_dict()`. The four PAN-pair `HaUnit`
  construction sites in `_derive_pan_units`/`_apply_pan_explicit_candidate`
  now pass VSYS names via `context_vsys=` instead of folding them into
  `display_name`. `display_name` is no longer set for a PAN pair (falls back
  to the canonical `unit_id`, unchanged identity — `_derive_pan_units`'s own
  pairing/matching logic is completely untouched). `_pan_display_name` is
  removed.
- **`utils/failover_readiness_ui.py`**: adds `_humanize_reason` — a fixed
  vocabulary lookup (same category as the pre-existing `VERDICT_LABELS`/
  `CHECK_STATUS_LABELS`) that glosses a `reason`/`check.reason` code into
  plain English, with an explicit entry for
  `pan_path_monitoring_any_down` naming the fail-closed-not-a-defect
  behavior. Unrecognised codes degrade to a spaced-out version of the same
  code — never a guessed sentence. `_annotate_reason_display` adds a
  `reason_display` field alongside (never replacing) each unit's/check's own
  `reason` in the payload the console/report both render. No verdict, check
  status, or `reason` value is computed, changed, or reinterpreted anywhere
  in this change.
- **`static/failover_readiness_ui.js`**: the unit-row label stays
  `display_name || unit_id` (now the canonical pair identity for PAN, same
  as it always was for Check Point); `context_vsys`, when present, renders
  as a subordinate `<span class="eyebrow">` line under the label, never
  composed into it. `reason`/`check.reason` cells render
  `reason_display || reason`.
- No collector, command battery, identity-resolution, or readiness-check
  logic touched. `_derive_pan_units`'s pairing predicate (contract OP.0a.P7,
  unchanged by S8-C on purpose) is exactly as it was.

## Validation

Targeted: `tests/test_op0a_ha_readiness.py`, `tests/test_op0b_s7_readiness_v2.py`,
`tests/test_op0c_failover_readiness_ui.py`,
`tests/test_op0b_s7_5_fresh_readiness_console_parity.py`,
`tests/test_op0d_deterministic_target_selection.py`,
`tests/test_frontend_module_composition.py`,
`tests/test_phase0_5_final_ui_closure.py` — 203 passed. Broader sweep
(`-k "failover or pan_ha or op0b_s8 or vsx or vsls or op0a"`) — 410 passed.
`tests/test_architecture_convergence.py` — 20 passed. Repository privacy
gate: clean (checked on the pre-test-run tree; the fail this session
observed after running the suite was the known gitignored `data/`/`logs/`
runtime-artifact finding, not a repository content issue — confirmed both
paths are `.gitignore`d and untracked). Two tests updated to the new,
honest contract instead of the old VSYS-first string
(`test_pan_context_vsys_surfaces_real_interface_vsys_context`,
`test_32_vsys_not_emitted_as_independent_failover_unit`) and one identity-
projection test (`test_report_units_are_projected_verbatim`) updated to
allow the additive `reason_display` gloss while still asserting every other
field, including every `reason`, is projected byte-for-byte unchanged.

No full serial regression run for this slice (bounded, presentation-only,
targeted + convergence + privacy sufficient per this repo's validation
ladder); FAST PR CI covers the rest.

## What remains (S9, honestly still open)

Per `project/feature_registry.json`'s `ui_authority_reconciliation`
criterion (unchanged, still `pending`) and the roadmap `now_next.next` note
this session did not close:

- `static/inventory_ui.js`: client-side PAN pairing inference
  (`inferPairDescriptor`, `panoramaPairCompatible`, VSYS/VR Jaccard
  similarity) and CP VSX-cluster synthesis from hostname-token overlap
  (`vsxByCluster`/`memberSetsOverlap`) — the Inventory page's own tree
  builder, a different UI surface from the failover-readiness report/console
  module this slice corrected. It never consults
  `utils.failover.assessment.compute_ha_readiness`.
- `utils/merge.py`'s hostname-suffix cluster heuristic (not reviewed this
  session).
- `utils/config_ui.py`'s independent `_ha_header_evidence` HA vocabulary
  (not reviewed this session).

These are a larger, cross-JS/Python, independent-feature retirement —
consistent with the S8-C session's own PO decision to log honestly rather
than fold it into a bounded slice. Recommended next dedicated movement.
