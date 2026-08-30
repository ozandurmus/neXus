# 0.7.4a — HTML export render hotfix (P0)

**Status:** AUTOMATED_VALIDATED (2026-08-30) · **Track:** 0.7.x · **Movement:** ROOT_CAUSE + hotfix

## 1. Symptom

On a real `py .\main.py` run the report generated to completion, `output/index.html`
opened, the Overview landing page rendered — but **every module-nav button was
dead** and the view was stuck on Overview.

## 2. Root cause

`utils/html_export.py` filled `templates/index.html` with a **sequence** of
`str.replace()` calls — one per payload placeholder, applied to the whole
document in turn.

`project/backlog.json` and `project/build_history.json` each carry a note that
mentions the literal token `__CRYPTO_JSON_PLACEHOLDER__` (prose describing the
0.7.0 crypto build). `build_project_plan_payload()` embeds those files verbatim,
so the emitted `projectPlanData` JS string literal contained that token. The
later `html.replace("__CRYPTO_JSON_PLACEHOLDER__", <crypto json>)` then matched
the copy **inside `projectPlanData`** and spliced a brace/quote-laden
`{"schema_version":"0.7.0",…}` object into the middle of the string literal. The
stray `"` characters closed the literal early →

```
Uncaught SyntaxError  (parsing the single inline <script>)
```

The whole script block never executed, so no event listeners were attached. The
static Overview panel (`class="app-module active"` in the template) still
rendered, which made the page look loaded.

Not caught earlier: `scripts/render_sample.py` and the render tests check only
that no `__…_PLACEHOLDER__` tokens survive and that payloads build — after the
erroneous substitution none survive, and the JS is never parsed or executed.

## 3. Fix

- **`utils/html_export._fill_template(template, replacements)`** — one
  left-to-right pass (`re.compile("|".join(re.escape(k) …))`, longest key first,
  function replacement so no backreference expansion). Each sentinel that is
  actually in the template is replaced exactly once; text introduced by a
  replacement is never re-scanned. `run_html_export` now calls it once with all
  eight sentinels (2 CSS/JS comment slots + 6 JSON payloads) instead of chaining
  `str.replace()`.
- `_script_json` unchanged (still guards a literal `</`).

## 4. Definition of Done — met

- `tests/test_html_export_placeholder_integrity.py` (5): every embedded payload
  in a real render round-trips through `json.loads`; the sentinel token survives
  **as data** inside `projectPlanData` without being expanded; `_fill_template`
  does not re-scan inserted content; each template sentinel replaced once.
- `py -m pytest -q -n auto --dist worksteal` → **534 passed, 3 skipped, 0 failed**
  (529 → +5).
- Repository privacy gate **PASS / 0** on a clean tree.
- `scripts/render_sample.py` exit 0; the fresh sample `index.html` has all six
  `const … = …;` payloads parsing as valid JSON.

## 5. Follow-up

- Real-environment reconfirmation on the corporate laptop: regenerate the report
  and click through all six modules. (Automated evidence is strong — the failure
  reproduced deterministically from `project/*.json` content and is now guarded —
  but the on-hardware click-through is still owed, tracked under
  `on_hardware_real_env_validation`.)
- Optional hardening (not this hotfix): escape `U+2028` / `U+2029` in
  `_script_json` for pre-ES2019 engines.
