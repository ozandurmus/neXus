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

## 4. Follow-up

- CI without `bun`: the JSON-validity test still runs; add `bun` to CI to get
  the navigation smoke test there too.
- The fixture is authored at the payload layer. If a future build changes how
  `build_configuration_ui_payload` shapes its output, the fixture must be
  updated to match (growth rule) — the harness cannot catch a fixture that has
  drifted from the real builder.
