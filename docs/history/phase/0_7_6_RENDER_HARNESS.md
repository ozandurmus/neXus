# 0.7.6 — Automated HTML render harness + `uitest` fixture bundle

**Status:** AUTOMATED_VALIDATED (2026-08-30) · **Track:** 0.7.x · **Movement:** IMPLEMENTATION

Motivated by `0.7.4a`: the generated report is one inline `<script>`; if it fails
to parse or throws before the nav listeners attach, every button is dead while
the page still looks loaded — and nothing in CI parsed or executed that script.

## 1. What ships

### `tests/fixtures/uitest/` (committed)
Hand-authored, privacy-clean bundle that makes **every** module render
*populated*: `unified.json`, `configuration_ui.json`, `crypto_ui.json`,
`discovery_ui.json`, `state/compliance_checks.json`,
`state/compliance_history.json` (3 records → the 0.7.5 trend renders),
`build_fixture.py` (regenerator), `README.md` (file map + growth rule).
Obviously-fake names, RFC 5737 IP ranges, no secrets.

### `scripts/render_uitest.py`
Renders the report from the fixture. Injects the three builders whose real
inputs are collector telemetry / PAN XML on disk / live stores
(`build_configuration_ui_payload`, `build_crypto_posture`,
`build_discovery_capability_payload`); `build_compliance_posture`,
`build_project_plan_payload`, `_fill_template` and `_script_json` run for real.
No network, no credentials, no `main.py` change.

### `tools/render-harness/check-render.mjs` (bun + `happy-dom`)
Given an `index.html`:
1. `new Function()` the inline `<script>` on its own → catches the `0.7.4a`
   parse failure;
2. build the DOM, `window.eval` the script, assert `window.switchModule` exists;
3. click every `.module-nav-item` → its `[data-module-panel]` and the button
   must become `.active`, no new `console.error` / `window.onerror`;
4. click every `.tab[data-tab]` / `.config-tab[data-config-tab]` → no new error.

Self-contained `package.json` + `bun.lock`; `node_modules/` gitignored.

### `tests/test_html_render_harness.py`
- `test_every_embedded_payload_is_valid_json` — no JS engine needed; the
  `0.7.4a` class shows up as an invalid `const X = …;` literal.
- `test_all_six_modules_are_populated` — the bundle must exercise every module,
  not the empty states.
- `test_headless_navigation_smoke` — runs `check-render.mjs`; `skipif` when
  `bun` or the harness deps are absent.

### Governance
`docs/AI_DEVELOPMENT_PROTOCOL.md` — new "HTML render harness (mandatory for any
UI / payload change)" section. `AGENTS.md` project-state-update rule gains
`tests/fixtures/uitest/`; a UI/payload build must show the harness green
alongside the suite + privacy gate.

## 2. Verified behaviour

- Positive: `render_uitest.py` → `check-render.mjs` → `PASS: script parses,
  executes clean, 6 nav modules + all inner tabs switch with no console errors`.
- Negative 1 (corrupt a payload literal, the `0.7.4a` shape) → `FAIL: inline
  <script> does not parse` → exit 1.
- Negative 2 (rename the nav click handler) → `FAIL (16)` with
  `ReferenceError … is not defined`, panels don't activate → exit 1.

## 3. Definition of Done — met

```
py -m pytest -q -n auto --dist worksteal : 547 -> +N passed, 3 skipped, 0 failed
repository privacy gate                   : PASS / 0 on a clean tree
scripts/render_sample.py                  : exit 0 (unchanged - stays the empty-state check)
scripts/render_uitest.py + check-render   : PASS
```

No `main.py` behaviour change; the harness is dev/CI only.

## 4. `0.7.6a` — fixture expanded to a full topology matrix (2026-08-30)

`tests/fixtures/uitest/build_fixture.py` regenerated so the bundle exercises
every device shape and UI branch, not just three devices:

- **Check Point:** standalone `gateway`; ClusterXL (`clusterxl_member` ×2,
  `active` / `standby`); VSX `vsx_host` (standalone) + two `virtual_system`
  contexts (VSID 10 / 20); VSX cluster (`vsx_host` ×2) + a shared
  `virtual_system` (VSID 30); one UNAVAILABLE gateway (`capability_gap`).
- **Palo Alto:** single firewall (`vsys_count 1`, `HA Disabled`); HA pair
  (`Local Active` / `Local Passive`, `panorama_sync.out_of_sync`); multi-vsys
  firewall (`vsys_count 3`); multi-vsys HA pair (`vsys_count 2`, one
  disconnected).
- **`unified.json`:** matching rows with genuine cluster-member interface + route
  divergence (member B missing `eth2`, different static-route next-hop, an extra
  route), VSX physical-host + per-VSID `wrp*` rows, PAN vr-scoped multi-vsys
  interfaces, and `live` / `last_known_good` / `no_data` inventory states.
- Alignment: `ALIGNED` · `LOCAL_OVERRIDE` · `EFFECTIVE_DRIFT` · `MEMBER_SPECIFIC`
  · `LOCAL_ONLY` · `EXPECTED_ONLY` · `UNKNOWN` all present. History: `same` /
  `changed` / `first` + a CP `insufficient_evidence` diff + a PAN pair diff with
  MODIFIED/ADDED rows. Crypto: CP + PAN subjects, `PASS` / `FINDING` / `UNKNOWN`
  across `weak_algorithm` / `crypto_agility` / `pqc_readiness`,
  `evidence_basis` configured/inferred/insufficient. Compliance: enforced +
  advisory user checks + a `state/control_assignments.json` waiver (one WAIVED
  control) + per-framework `COVERED` / `PARTIALLY_COVERED` / `UNCOVERED`.

New `tests/test_html_render_harness.py::test_all_topologies_present` asserts each
`entity_type`, HA role, vsys/VSID shape, change state, alignment class, inventory
state and crypto status appears in the rendered payload — so a future builder
change that drops a shape fails loudly instead of the harness passing on a
thinner render.

Evidence: `py -m pytest -q -n auto --dist worksteal` → **551 passed, 3 skipped,
0 failed**. `check-render.mjs` PASS on the 16-device render (no console errors).
Repository privacy gate PASS / 0 on a clean tree.

## 5. Follow-up

- CI without `bun`: the JSON-validity tests still run; add `bun` to CI to get
  the navigation smoke test there too.
- The fixture is authored at the payload layer. If a future build changes how
  `build_configuration_ui_payload` shapes its output, the fixture must be
  updated to match (growth rule) — the harness cannot catch a fixture that has
  drifted from the real builder.
