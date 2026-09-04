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

- Date: 2026-09-04. S8 merged to `main` (PR #67, FAST CI green). Product
  build: `op0b_s9_pan_ha_label_authority_correction` **DONE** — the bounded
  S9 slice this session took: the S8-C-recorded PAN UI debt (VSYS-composed
  HA-pair label; `control_sync_link_health unknown:pan_path_monitoring_any_down`
  presentation clarity). The BROADER S9 scope (`static/inventory_ui.js`,
  `utils/merge.py`, `utils/config_ui.py`) is untouched, still NOT STARTED.
- Branch: `claude/s8-merge-s9-ui-authority-xafolg`, reset onto post-merge
  `main`, S9 work committed there. No PR opened for S9 this session (not
  requested yet).

## 2. What changed this session

- **S8 close:** opened PR #67 (`claude/pan-real-env-validation-tum9pg` →
  `main`), FAST CI (`validate` job) green, merged, local `main` synced.
- **PAN HA unit label (S9, bounded):** `utils/failover/assessment.py`
  — `HaUnit` gains `context_vsys: list[str]` (additive, serialized in
  `to_dict`). The four PAN-pair `HaUnit` construction sites in
  `_derive_pan_units`/`_apply_pan_explicit_candidate` now pass VSYS names
  via `context_vsys=` instead of folding them into `display_name` via the
  removed `_pan_display_name`; PAN pair `display_name` is no longer set
  (falls back to the canonical `unit_id`). Pairing/matching logic in
  `_derive_pan_units` is completely untouched.
- **Reason-code clarity (S9, PAN UI debt item 4):** traced
  `control_sync_link_health unknown:pan_path_monitoring_any_down` end-to-end
  (`preflight_readiness.py::_state_reason` → generic, shared, cross-vendor
  formatter; `pan_preflight_extraction.py::parse_pan_path_monitoring` →
  legitimate fail-closed `UNKNOWN` when path monitoring isn't configured or
  the shape is unrecognized) and confirmed **presentation-only debt, not a
  parser/projection defect**. `utils/failover_readiness_ui.py` gains
  `_humanize_reason`/`_annotate_reason_display` — a fixed reason-code-to-
  plain-English gloss (`reason_display`), additive alongside the unchanged
  raw `reason`, never reinterpreting readiness semantics.
- **JS:** `static/failover_readiness_ui.js` renders `context_vsys` as a
  subordinate `<span class="eyebrow">` line under the unit label (never
  composed into it), and `reason_display || reason` in both the unit and
  per-check reason cells.
- **Tests updated to the new, honest contract** (old VSYS-first
  `display_name` was itself the debt being fixed, so these needed to
  change, not just pass): `tests/test_op0a_ha_readiness.py` (2 tests
  renamed/rewritten), `tests/test_op0b_s7_readiness_v2.py` (test_32), and
  `tests/test_op0b_s7_5_fresh_readiness_console_parity.py`
  (`test_report_units_are_projected_verbatim` now asserts every field
  including every `reason` still projects byte-for-byte unchanged, with
  `reason_display` as the one allowed pure-gloss addition).
- **Project-state:** new phase doc
  `docs/history/phase/OP_0B_S9_PAN_HA_LABEL_AUTHORITY_CORRECTION.md`; new
  `build_history.json` record (newest); `roadmap.json` `now`/`current_build`
  updated to this build, `next` retitled to make explicit it is the
  *remaining, broader* S9 scope; `feature_registry.json`
  `ui_authority_reconciliation` criterion note updated but **left
  `pending`** (its named files are untouched); `CURRENT_STATE.md` rewritten
  (trimmed back to the 200-line cap); `docs/history/INDEX.md` regenerated.

## 3. Exact next action

**`op0b_s9_ui_authority_reconciliation`** (`now_next.next`, the remaining,
broader S9 scope) — retire client-side PAN/CP pairing and HA-vocabulary
heuristics on the **Inventory page**, a different UI surface from the
failover-readiness module this session corrected:
`static/inventory_ui.js` (`clusterNameSource: "inferred_ha_runtime_pair"` +
hostname-ordinal/VSYS-Jaccard pairing + hostname-token VSX cluster
synthesis), `utils/merge.py` (hostname-suffix cluster heuristic,
unreviewed), `utils/config_ui.py` (`_ha_header_evidence`'s independent HA
vocabulary, unreviewed) — in favor of the one canonical
`utils.failover.assessment.compute_ha_readiness` evaluator. Cross JS +
Python surface, needs its own scoping/audit pass before implementation.
`Sonnet 5, normal` for the scoping pass; escalate to extended thinking only
if retiring a heuristic turns out load-bearing for something the audit
didn't expect.

Independent, any order, unaffected by this session: **A.** `D-V3a`/`D-V7b`
closure (GitHub-mirror then human-fetch, extended thinking). **B.** `D-F3`
flap threshold (product-owner call). **C.** PAN serial identity closure
(hardware-blocked; unreconciled tension between the S0 MISMATCH finding and
S8-C's manual observation stands as recorded).

## 4. Test delta

- Not a full serial run this session (bounded, presentation-only slice).
  Targeted: 203 passed (`test_op0a_ha_readiness`, `test_op0b_s7_readiness_v2`,
  `test_op0c_failover_readiness_ui`, `test_op0b_s7_5_fresh_readiness_console_parity`,
  `test_op0d_deterministic_target_selection`, `test_frontend_module_composition`,
  `test_phase0_5_final_ui_closure`). Broader sweep
  `-k "failover or pan_ha or op0b_s8 or vsx or vsls or op0a"`: 410 passed.
  Architecture convergence: 20/20.
- Prior full-suite baseline (unaffected, still holds): 1681 passed / 26
  skipped / 0 failed (`op0b_s8c_pan_dedicated_ha1_real_env_correction`).
- Note for Linux/container sessions: `py` does not exist there; `python3 -m
  pytest` with `requirements.txt` + `requirements-dev.txt` +
  `requirements-console.txt` installed. `pytest` may need a plain
  `python3 -m pip install` first if the environment is fresh.

## 5. New risks

- None introduced. This session touched only presentation code
  (`assessment.py`'s `HaUnit`/label construction, `failover_readiness_ui.py`,
  `failover_readiness_ui.js`) — no collector, command battery, identity
  resolution, or readiness-check predicate changed.
- The broader S9 scope (Inventory-page JS heuristics) remains real,
  confirmed-needed work — do not defer indefinitely; direct evidence of the
  confusion it causes was already recorded in S8-C.
- Everything else unchanged from the S8-C handover: PAN B2 tension (S0
  MISMATCH vs. manual all-MATCH observation) not reconciled; `group-id`
  correspondence best-effort; `D-V7b`/`D-F3` keep readiness
  INSUFFICIENT_EVIDENCE; CLASS 2 stays frozen architecture, not implemented,
  not reachable.
