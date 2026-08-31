# Dev tooling — Render Harness happy-dom/Bun Root Cause

## Status

**AUTOMATED_VALIDATED 2026-08-31**

Follow-up to `render_harness_happydom_pin`, discovered 2026-08-30 during
`backup_recovery_architecture` (incidental, recorded not fixed at the time).
Product baseline: main HEAD at start of this build (the
`html_render_optimization` merge).

## Objective

`tests/test_html_render_harness.py::test_headless_navigation_smoke` (the
happy-dom half of the mandatory render harness) failed in this session's
cloud environment with `TypeError: window.eval is not a function`, silently
invisible until `node_modules` is installed (the test `skipif`s otherwise).
The originally recorded suspicion was a happy-dom `>= 20` API removal. This
build root-causes it for real and fixes the actual problem.

## What was found

`tools/render-harness/check-render.mjs` builds the report's DOM with
happy-dom's `Window` class, then calls `window.eval(scriptToRun)` to execute
the report's inline `<script>` deterministically inside that window's scope.
happy-dom implements this by running the script inside a `node:vm` context
(`BrowserWindow.js`'s `[PropertySymbol.evaluateScript]`, `new
VM.Script(code, options).runInContext(this)`) built by
`VMGlobalPropertyScript.js`, a small `vm.Script` that copies `globalThis`'s
built-ins (`Map`, `Error`, `eval`, `Function`, ...) onto the Window object as
the VM context's globals.

**Bun's `node:vm` shim does not implement this correctly.** Verified
directly (`bun` vs `node`, same happy-dom install, same code):

| happy-dom version | Bun: `typeof window.eval` | Node: `typeof window.eval` |
| --- | --- | --- |
| 16.0.0 | `undefined` | `function` |
| 19.0.2 | `undefined` | `function` |
| 20.12.0 (pinned) | `undefined` | `function` |

Same result for `new Error(...)`/`new Map()` executed inside a
`window.eval()`'d script under Bun — `undefined is not a constructor`. This
is **not a happy-dom version regression**: it reproduces identically across
three majors spanning 16→20. It is Bun's `vm.createContext`/`Script.runInContext`
not correctly wiring up context globals the way Node's real implementation
does. The exact same `check-render.mjs`, unmodified, passes cleanly under
`node` with the committed `happy-dom@20.12.0`.

### A dead end explored and reverted

Before finding the above, `happy-dom`'s newer `enableJavaScriptEvaluation`
`Window` setting was tried as a replacement for `window.eval()`: with it set,
`document.write()`-inserted `<script>` tags do auto-execute (this genuinely
works, including under Bun, and avoids the `window.eval`/`window.Function`
removal entirely). However each such script runs in its **own isolated
scope** — a top-level `function switchModule(){}` or `var x` declaration does
**not** become a `window` property, unlike a real browser's classic-script
semantics. Since this harness's whole second phase depends on calling
`window.switchModule` after execution, that path is unusable here. This was
implemented, tested, found insufficient, and reverted — `check-render.mjs`
ships unchanged from before this build.

## Fix

`tests/test_html_render_harness.py`'s `_bun()` helper is superseded by
`_js_runtime()`, which prefers a real `node` binary (`shutil.which("node")`)
and falls back to `_bun()` only when no Node is on `PATH` at all (Bun stays a
last resort — broken for this specific check under every happy-dom version
tested, but still better than nothing if a session genuinely has no Node).
`docs/AI_DEVELOPMENT_PROTOCOL.md`'s documented manual command switched from
`bun tools/render-harness/check-render.mjs ...` to
`node tools/render-harness/check-render.mjs ...`, with the root cause
recorded inline so a future session does not re-diagnose this from scratch.
`bun install` in `tools/render-harness/` is untouched and still the
documented dependency-install step — only Bun's `vm` module is implicated,
not its package manager.

## Privacy and safety invariants

1. Dev/CI-only tooling change; nothing in `main.py`, a collector, or any
   product runtime path touched.
2. No new dependency (`node` was already present in every environment this
   harness runs in per the repo's own tooling baseline).

## Evidence

- `tests/test_html_render_harness.py`: 6 passed (previously 5 passed / 1
  skipped in this session's Bun-only environment — the happy-dom check now
  runs and passes instead of skipping).
- Manually reproduced the failure and the fix end-to-end: `check-render.mjs`
  against the uitest fixture's rendered report fails under `bun`
  (`window.eval is not a function`) and passes cleanly under `node`, with
  zero source change to the script itself.
- `bun.lock`/`package.json` diff: none (a `bun install` in this sandbox does
  rewrite `bun.lock`'s `lockfileVersion` 2→1, same as the 2026-08-30
  discovery note recorded — reverted, not committed, matches the documented
  handling).

## Definition of done

`DONE`: root cause identified with real cross-runtime evidence (not
assumption), the actual fix applied (prefer `node` to run the check) and
validated, the dead-end investigated and explicitly recorded so it is not
re-attempted, documentation corrected to state the real cause instead of the
original (incorrect) "happy-dom >= 20 removed window.eval" theory.
