# 0.6.6B — Compliance Rule-Pack Transition Foundation (implementation contract)

**Status:** AUTOMATED_VALIDATED (2026-08-28) — `py -m pytest -q` **440 passed,
3 skipped, 0 failed**; `--render-only` PASS; repository privacy gate PASS / 0.
Real-environment validation not required (no network-facing behavior). Human
`main` merge blocked pending review.
**Movement:** IMPLEMENTATION
**Architecture contract (frozen):** `docs/history/phase/PHASE0_6_6B_COMPLIANCE_RULE_PACK_TRANSITION.md`
**Backlog:** `compliance_posture_rulepack_transition` (P1) · **Track:** 0.6.x

Active build contract; moves to `docs/history/phase/` on close.

---

## 1. What exists (frozen baseline)

`utils/compliance_posture.py`:

- `COMPLIANCE_SCHEMA_VERSION = "0.6.1B.1.6"`, `STATUS_VALUES` = PASS / FINDING /
  UNKNOWN / NOT_APPLICABLE / PLANNED.
- `VENDOR_NEUTRAL_CONTROLS` — 10 dicts: `control_id`, `title`, `control_area`,
  `cis_reference`, `evidence_fields`.
- `_evaluate_vendor_neutral_control(device, control)` dispatches `control_id` →
  one of 10 `_evaluate_*` deterministic functions (reads only the normalized,
  privacy-safe `current_configuration.sections[].settings[]`), else
  `_planned_evidence_gap`.
- `_subject_controls(device)` = the 10, per device with `available` current
  config. `_platform_controls(...)` = 6, `_fleet_controls(...)` = 2 (separate
  posture signals, **not** part of "the ten").
- `build_compliance_posture(configuration_ui, project_plan)` → payload:
  `schema_version, available, classification, disclaimer, fleet{...},
  fleet_controls[], platform_controls[], subjects[]{controls[]}, privacy{...}`.

Consumers: `utils/html_export.py:76` only. `static/app.js` /
`templates/index.html` read `available`, `subjects[]`, and control fields
(`status`, `framework_mappings`, `benchmark_reference`, `control_lifecycle`,
`control_id`, `title`, `evidence_fields`) — **never `schema_version` or any pack
field**. One test asserts `schema_version == "0.6.1B.1.6"`.

## 2. Change

### 2a. `utils/compliance_rulepack.py` (new) — the versioned declarative pack

```python
RULE_PACK_SCHEMA_VERSION = "1.0"
DEFAULT_RULE_PACK_ID      = "securityexpert.baseline.cp-pan"
DEFAULT_RULE_PACK_VERSION = "0.6.6B"

# Single source of truth for the ten controls (moved here from
# compliance_posture.VENDOR_NEUTRAL_CONTROLS, unchanged content).
BASELINE_CONTROLS: tuple[dict, ...] = ( ... 10 dicts, verbatim ... )

DEFAULT_RULE_PACK = {
    "pack_id": DEFAULT_RULE_PACK_ID,
    "pack_version": DEFAULT_RULE_PACK_VERSION,
    "schema_version": RULE_PACK_SCHEMA_VERSION,
    "title": "SecurityExpert baseline CP/PAN control pack",
    "source": "in_repository_static",
    "certification_claim": False,
    "disclaimer": "Evidence-backed control-area evaluation only. Not a "
                  "certification, attestation or complete framework assessment.",
    "rules": [
        {
            "rule_id": f"{DEFAULT_RULE_PACK_ID}::{c['control_id']}",
            "control_id": c["control_id"],
            "title": c["title"],
            "control_area": c["control_area"],
            "applicability": {"vendors": ["check_point", "palo_alto"], "scope": "SUBJECT"},
            "evidence_fields": list(c["evidence_fields"]),
            "benchmark": "CIS",
            "benchmark_reference": c["cis_reference"],
            "evaluator": c["control_id"],          # dispatch key into compliance_posture
        }
        for c in BASELINE_CONTROLS
    ],
}

def pack_control_views(pack=DEFAULT_RULE_PACK) -> list[dict]:
    """Return each rule as the {control_id,title,control_area,cis_reference,
    evidence_fields} view the existing _evaluate_* functions consume — so
    routing through the pack cannot change evaluation inputs."""
```

Static, frozen (a module-level tuple/dict). No I/O, no network, no dynamic load.

### 2b. `utils/compliance_posture.py`

- `COMPLIANCE_SCHEMA_VERSION` → `"0.6.6B"`.
- `from utils.compliance_rulepack import DEFAULT_RULE_PACK, pack_control_views`.
  `VENDOR_NEUTRAL_CONTROLS = tuple(pack_control_views())` (kept for internal use;
  identical shape/content to today). One-directional import — no cycle.
- `_subject_controls(device)`: iterate `DEFAULT_RULE_PACK["rules"]`; for each,
  call `_evaluate_vendor_neutral_control(device, view)` with the rule's control
  view, then **stamp** the result:
  `result["rule_pack"] = {"pack_id", "pack_version", "rule_id"}`.
  Evaluation inputs are unchanged → outcomes unchanged (AC-2, AC-4).
- `_control(...)` gains `rule_pack: dict | None = None` in the returned dict, so
  every control has the key. Platform/fleet controls pass `None`.
- `build_compliance_posture(...)` payload gains additive top-level:
  ```json
  "rule_pack": {
    "pack_id": "...", "pack_version": "0.6.6B", "schema_version": "1.0",
    "title": "...", "source": "in_repository_static",
    "certification_claim": false, "disclaimer": "...", "rule_count": 10
  }
  ```
  Present in both the `available: false` and `available: true` returns.

Nothing else in `compliance_posture.py` changes — the 10 `_evaluate_*`
functions, `_platform_controls`, `_fleet_controls`, partitioning, `privacy`
block, status counting are untouched.

## 3. Acceptance mapping

| AC | Covered by |
| --- | --- |
| AC-1 static pack, immutable `pack_id` + version | `DEFAULT_RULE_PACK` module constant; test asserts identity + `certification_claim is False` |
| AC-2 ten controls through the boundary, stable IDs + equivalent outcomes | `_subject_controls` routes via `DEFAULT_RULE_PACK["rules"]`; test snapshots `(control_id, status)` for both vendors' sample evidence and compares to the frozen baseline values |
| AC-3 applicability + evidence-field + benchmark traceability per control | each rule carries `applicability`/`evidence_fields`/`benchmark[_reference]`; stamped `rule_pack` on each result; test asserts all present and non-empty |
| AC-4 missing/insufficient evidence → UNKNOWN/PLANNED, never inferred PASS | unchanged evaluators; test with an empty-section device asserts no `PASS` |
| AC-5 payload shape backward-compatible, additive only | test asserts every prior top-level key still present + type-stable; `rule_pack` is the only new key; `app.js`/template unchanged |
| AC-6 no secret / raw config / real identity / certification claim | `rule_pack.certification_claim is False`; `json.dumps(payload)` has no "certified"/"attestation"/"compliant with" positive claim; existing privacy test still passes |
| AC-7 targeted + regression + privacy gate | `pytest -n auto`; `py -B main.py --repository-privacy-check`; `py -B main.py --render-only` |

## 4. Files

- `utils/compliance_rulepack.py` — new.
- `utils/compliance_posture.py` — schema bump, import + route + stamp + additive `rule_pack` block.
- `tests/test_phase0_6_6b_compliance_rulepack.py` — new (AC-1…AC-6).
- `tests/test_phase0_6_1b_1_5_compliance_posture.py` — `schema_version` `"0.6.1B.1.6"` → `"0.6.6B"` (1 line); add an additive assertion that each subject control has a non-null `rule_pack`.
- `project/roadmap.json`, `project/backlog.json`, `project/build_history.json`, `CURRENT_STATE.md` — state on close.

## 5. Definition of Done

AC-1…AC-7 pass; `--render-only` HTML healthy; diff review confirms no
collector / CAS / scheduler / storage / network semantics changed;
`compliance_posture_rulepack_transition` advanced to `automated_validated`.
No real-environment validation required (no network-facing behavior).

## 6. Open decisions (my recommendation)

1. **Bump `COMPLIANCE_SCHEMA_VERSION` to `"0.6.6B"`** (vs keep `"0.6.1B.1.6"` and
   only add `rule_pack`). Recommend **bump** — matches repo precedent
   (0.6.1B.1.5 → 0.6.1B.1.6 bumped it), only one test assertion changes, and it
   is the honest signal that the payload evolved (additively).
2. **Move the 10 control definitions into `compliance_rulepack.py`** as the
   single source of truth; `compliance_posture.VENDOR_NEUTRAL_CONTROLS` becomes
   a derived view. Recommend **yes** — avoids a circular import and keeps one
   definition. Symbol stays exported (internal-only today).
3. **Platform/fleet controls** get `rule_pack: None` (not routed through the
   pack — they are not "the ten"). Recommend **yes** — matches AC-2 scope and
   the frozen architecture ("the existing ten deterministic controls").

All three accepted.

---

## 7. Implementation record (2026-08-28)

- `utils/compliance_rulepack.py` (new) — `BASELINE_CONTROLS` (the 10, verbatim
  from the former `compliance_posture.VENDOR_NEUTRAL_CONTROLS`),
  `RULE_PACK_SCHEMA_VERSION="1.0"`, `DEFAULT_RULE_PACK_ID`,
  `DEFAULT_RULE_PACK_VERSION="0.6.6B"`, `DEFAULT_RULE_PACK` (rules are a frozen
  `tuple`), `rule_pack_summary()`.
- `utils/compliance_posture.py` — `COMPLIANCE_SCHEMA_VERSION="0.6.6B"`; import
  from `compliance_rulepack`; `VENDOR_NEUTRAL_CONTROLS = BASELINE_CONTROLS`
  (alias); `_control(...)` gains `rule_pack` param + field; `_subject_controls`
  iterates `DEFAULT_RULE_PACK["rules"]`, evaluates with the rule dict (which
  carries the exact keys the evaluators read) and stamps
  `result["rule_pack"] = {pack_id, pack_version, rule_id}`;
  `build_compliance_posture` adds `"rule_pack": rule_pack_summary()` to both the
  `available: false` and `available: true` returns.
- `tests/test_phase0_6_6b_compliance_rulepack.py` (new) — 7 tests, AC-1…AC-6.
- `tests/test_phase0_6_1b_1_5_compliance_posture.py` — `schema_version` →
  `"0.6.6B"`; additive `rule_pack` assertions on payload and per subject control.

Evidence: `pytest` 440/3/0 (was 433; +7). `_evaluate_vendor_neutral_control`
called directly with the matching `BASELINE_CONTROLS` entry returns the same
`status` as the routed subject control for every device in the synthetic
fixtures (AC-2 proof by construction). `--render-only` renders
`"rule_pack"` into `output/index.html` with no error. Privacy gate PASS / 0.
`app.js` / `templates/index.html` unchanged (they read none of the new fields).

State updated: `CURRENT_STATE.md`, `project/roadmap.json` (`current_build` →
`0.6.6B`, `now_next`), `project/backlog.json`
(`compliance_posture_rulepack_transition` → `automated_validated`),
`project/build_history.json` (0.6.6B record).
